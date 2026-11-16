package com.gr4nt.aurabrowser.dl

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tiny HTTP/1.1 server on raw sockets (the JDK's com.sun.net.httpserver is not visible to Android unit tests).
 *  /ranged  honours Range requests with 206 + Content-Range
 *  /plain   ignores Range and always answers 200 with the full body
 */
private class TestServer(private val payload: ByteArray, private val slow: AtomicBoolean) {
    private val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val concurrent = AtomicInteger(0)
    val peak = AtomicInteger(0)
    val port: Int get() = socket.localPort

    fun start() {
        pool.execute {
            while (!socket.isClosed) {
                val s = try { socket.accept() } catch (_: IOException) { break }
                pool.execute { handle(s) }
            }
        }
    }

    fun stop() {
        try { socket.close() } catch (_: IOException) {}
        pool.shutdownNow()
    }

    private fun handle(s: Socket) {
        val now = concurrent.incrementAndGet()
        peak.updateAndGet { maxOf(it, now) }
        try {
            s.use {
                val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = input.readLine() ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = input.readLine() ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val path = requestLine.split(" ").getOrElse(1) { "/" }
                val out = BufferedOutputStream(s.getOutputStream())
                val range = headers["range"]
                if (path.startsWith("/ranged") && range != null) {
                    val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)
                    if (m == null) { respond(out, "416 Range Not Satisfiable", emptyList(), null); return }
                    val a = m.groupValues[1].toInt()
                    val b = (m.groupValues[2].toIntOrNull() ?: (payload.size - 1)).coerceAtMost(payload.size - 1)
                    respond(
                        out, "206 Partial Content",
                        listOf("Content-Range: bytes $a-$b/${payload.size}", "Accept-Ranges: bytes", "ETag: \"v1\"", "Content-Length: ${b - a + 1}"),
                        payload.copyOfRange(a, b + 1),
                    )
                } else {
                    val extra = ArrayList<String>()
                    extra.add("Content-Length: ${payload.size}")
                    if (path.startsWith("/ranged")) { extra.add("Accept-Ranges: bytes"); extra.add("ETag: \"v1\"") }
                    respond(out, "200 OK", extra, payload)
                }
            }
        } catch (_: IOException) {
            // client cancelled (pause) – expected
        } finally {
            concurrent.decrementAndGet()
        }
    }

    private fun respond(out: BufferedOutputStream, status: String, headers: List<String>, body: ByteArray?) {
        val sb = StringBuilder("HTTP/1.1 $status\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n")
        headers.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null) {
            var pos = 0
            while (pos < body.size) {
                val n = minOf(64 * 1024, body.size - pos)
                out.write(body, pos, n)
                pos += n
                if (slow.get()) { out.flush(); Thread.sleep(4) }
            }
        }
        out.flush()
    }
}

class EngineTest {
    private lateinit var server: TestServer
    private lateinit var tmp: File
    private val payload = ByteArray(9 * 1024 * 1024 + 123).also { Random(42).nextBytes(it) }
    private val slow = AtomicBoolean(false)

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("aura-dl").toFile()
        server = TestServer(payload, slow)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        tmp.deleteRecursively()
    }

    private fun base() = "http://127.0.0.1:${server.port}"

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
        slow.set(true) // keep each chunk in flight long enough for the connections to overlap
        val (meta, out) = run("/ranged", 12)
        assertEquals(meta.error, JobState.Completed, meta.state)
        assertArrayEquals(payload, out!!.readBytes())
        assertTrue("expected several parallel connections, peak=${server.peak.get()}", server.peak.get() >= 4)
    }

    @Test
    fun fallsBackWhenServerHasNoRanges() {
        val (meta, out) = run("/plain", 8)
        assertEquals(meta.error, JobState.Completed, meta.state)
        assertArrayEquals(payload, out!!.readBytes())
    }

    @Test
    fun pauseThenResumeKeepsFinishedChunks() {
        slow.set(true)
        val dir = File(tmp, "resume")
        val meta = JobMeta("j2", base() + "/ranged", "f.bin")
        val done = CountDownLatch(1)
        val listener = TransferListener(onFinished = { _, _ -> done.countDown() }, onFailed = { _, _ -> done.countDown() })
        val first = Transfer(meta, dir, Http.client(), 6, listener)
        first.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (synchronized(meta.done) { meta.done.cardinality() } < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        first.pause()
        Thread.sleep(400)
        assertEquals(JobState.Paused, meta.state)
        val chunksDoneBefore = synchronized(meta.done) { meta.done.cardinality() }
        assertTrue("some chunks should be finished before pause", chunksDoneBefore > 0)

        // Simulate process death: reload metadata from disk, resume with a fresh Transfer.
        val restored = Transfer.load(dir)!!
        assertEquals(JobState.Paused, restored.state)
        assertTrue("finished chunks must survive a restart", synchronized(restored.done) { restored.done.cardinality() } >= chunksDoneBefore)
        slow.set(false)
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
