package com.gr4nt.aurabrowser.dl

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EngineTest {
    private lateinit var server: HttpServer
    private lateinit var tmp: File
    private val payload = ByteArray(9 * 1024 * 1024 + 123).also { Random(42).nextBytes(it) }
    private val concurrent = AtomicInteger(0)
    private val peak = AtomicInteger(0)
    private val slowDown = java.util.concurrent.atomic.AtomicBoolean(false)

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("aura-dl").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(48)
        server.createContext("/ranged") { ex ->
            val now = concurrent.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try {
                val range = ex.requestHeaders.getFirst("Range")
                ex.responseHeaders.add("ETag", "\"v1\"")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                if (range != null) {
                    val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)!!
                    val a = m.groupValues[1].toInt()
                    val b = m.groupValues[2].toIntOrNull()?.coerceAtMost(payload.size - 1) ?: (payload.size - 1)
                    ex.responseHeaders.add("Content-Range", "bytes $a-$b/${payload.size}")
                    ex.sendResponseHeaders(206, (b - a + 1).toLong())
                    ex.responseBody.use { out ->
                        var pos = a
                        while (pos <= b) {
                            val n = minOf(64 * 1024, b - pos + 1)
                            out.write(payload, pos, n)
                            pos += n
                            if (slowDown.get()) Thread.sleep(2)
                        }
                    }
                } else {
                    ex.sendResponseHeaders(200, payload.size.toLong())
                    ex.responseBody.use { it.write(payload) }
                }
            } finally {
                concurrent.decrementAndGet()
            }
        }
        server.createContext("/plain") { ex ->
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        tmp.deleteRecursively()
    }

    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun run(path: String, connections: Int, dir: File = File(tmp, "job")): Pair<JobMeta, File?> {
        val meta = JobMeta("j1", base() + path, "f.bin")
        val latch = CountDownLatch(1)
        var out: File? = null
        val t = Transfer(meta, dir, Http.client(), connections, TransferListener(
            onFinished = { _, f -> out = f; latch.countDown() },
            onFailed = { _, _ -> latch.countDown() },
        ))
        t.start()
        assertTrue("timed out", latch.await(60, TimeUnit.SECONDS))
        return meta to out
    }

    @Test
    fun parallelRangesAreByteExact() {
        val (meta, out) = run("/ranged", 12)
        assertEquals(JobState.Completed, meta.state)
        assertArrayEquals(payload, out!!.readBytes())
        assertTrue("expected several parallel connections, peak=${peak.get()}", peak.get() >= 4)
    }

    @Test
    fun fallsBackWhenServerHasNoRanges() {
        val (meta, out) = run("/plain", 8)
        assertEquals(JobState.Completed, meta.state)
        assertArrayEquals(payload, out!!.readBytes())
    }

    @Test
    fun pauseThenResumeKeepsFinishedChunks() {
        slowDown.set(true)
        val dir = File(tmp, "resume")
        val meta = JobMeta("j2", base() + "/ranged", "f.bin")
        val done = CountDownLatch(1)
        val listener = TransferListener(onFinished = { _, _ -> done.countDown() }, onFailed = { _, _ -> done.countDown() })
        val first = Transfer(meta, dir, Http.client(), 6, listener)
        first.start()
        val deadline = System.currentTimeMillis() + 20_000
        while (meta.downloaded.get() < 1_500_000 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        first.pause()
        Thread.sleep(300)
        assertEquals(JobState.Paused, meta.state)
        val chunksDoneBefore = synchronized(meta.done) { meta.done.cardinality() }
        assertTrue("some chunks should be finished before pause", chunksDoneBefore > 0)

        // Simulate process death: reload metadata from disk, resume with a fresh Transfer.
        val restored = Transfer.load(dir)!!
        assertEquals(JobState.Paused, restored.state)
        slowDown.set(false)
        val latch = CountDownLatch(1)
        var out: File? = null
        val second = Transfer(restored, dir, Http.client(), 6, TransferListener(
            onFinished = { _, f -> out = f; latch.countDown() },
            onFailed = { _, _ -> latch.countDown() },
        ))
        second.start()
        assertTrue(latch.await(60, TimeUnit.SECONDS))
        assertArrayEquals(payload, out!!.readBytes())
    }
}
