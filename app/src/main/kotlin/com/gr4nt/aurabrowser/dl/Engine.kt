package com.gr4nt.aurabrowser.dl

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Platform-independent segmented download engine. No Android classes are used here so it can be
 * exercised by plain JVM unit tests.
 *
 * Design:
 *  - The file is split into fixed-size chunks (0.5-8 MB depending on file size).
 *  - N worker threads pull chunks from a shared queue, each chunk is one HTTP Range request over its
 *    own HTTP/1.1 connection (HTTP/2 would multiplex everything onto a single TCP connection, which
 *    defeats the purpose of parallel connections).
 *  - Chunks are written with positional writes into a pre-sized ".part" file.
 *  - A bitmap of completed chunks is persisted, so pause/resume (and process death) lose at most the
 *    chunks that were in flight.
 *  - Servers that answer 429/503 make the engine shed connections; servers that ignore Range
 *    make it fall back to a single resumable-from-scratch stream.
 */
enum class JobState { Queued, Downloading, Paused, Completed, Failed }

class JobMeta(
    val id: String,
    val url: String,
    var fileName: String,
    var mime: String = "",
    val cookie: String = "",
    val userAgent: String = "",
    val referer: String = "",
) {
    @Volatile var size: Long = -1
    @Volatile var etag: String = ""
    @Volatile var lastModified: String = ""
    @Volatile var acceptRanges: Boolean = false
    @Volatile var chunkSize: Int = 0
    @Volatile var state: JobState = JobState.Queued
    @Volatile var error: String = ""
    @Volatile var savedUri: String = ""
    val created: Long = System.currentTimeMillis()
    val done = BitSet()
    val downloaded = AtomicLong(0)
    @Volatile var speedBps: Double = 0.0

    fun chunkCount(): Int = if (size <= 0 || chunkSize <= 0) 0 else ((size + chunkSize - 1) / chunkSize).toInt()
    fun progress(): Float = if (size > 0) (downloaded.get().toFloat() / size).coerceIn(0f, 1f) else 0f
}

class TransferListener(
    val onProgress: (JobMeta) -> Unit = {},
    val onFinished: (JobMeta, File) -> Unit = { _, _ -> },
    val onFailed: (JobMeta, String) -> Unit = { _, _ -> },
)

object Http {
    /** One shared client; HTTP/1.1 only so every ranged request can own a TCP connection. */
    fun client(maxConnections: Int = 64): OkHttpClient =
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(maxConnections, 2, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = maxConnections * 2
                maxRequestsPerHost = maxConnections * 2
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
}

class Transfer(
    val meta: JobMeta,
    private val dir: File,
    private val client: OkHttpClient,
    var connections: Int,
    private val listener: TransferListener,
) {
    private val stop = AtomicBoolean(false)
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    private val allowed = AtomicInteger(connections)
    private val running = AtomicInteger(0)
    private val workers = ArrayList<Thread>()
    @Volatile private var finishedNotified = false

    private val partFile get() = File(dir, "file.part")
    private val finalFile get() = File(dir, "file")

    fun finalOutput(): File = finalFile

    fun start() {
        if (meta.state == JobState.Completed) return
        stop.set(false)
        finishedNotified = false
        meta.state = JobState.Downloading
        meta.error = ""
        val t = Thread({ runSafely() }, "dl-main-${meta.id}")
        t.isDaemon = true
        t.start()
        workers.add(t)
    }

    fun pause() {
        if (meta.state == JobState.Completed || meta.state == JobState.Failed) return
        stop.set(true)
        meta.state = JobState.Paused
        for (c in calls) c.cancel()
        save()
        listener.onProgress(meta)
    }

    fun cancelAndDelete() {
        stop.set(true)
        for (c in calls) c.cancel()
        dir.deleteRecursively()
    }

    private fun runSafely() {
        try {
            run()
        } catch (e: InterruptedException) {
            // paused
        } catch (e: Exception) {
            if (stop.get()) return
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(msg: String) {
        meta.state = JobState.Failed
        meta.error = msg
        save()
        listener.onFailed(meta, msg)
    }

    private fun request(url: String, range: String? = null): Request {
        val b = Request.Builder().url(url)
        if (meta.userAgent.isNotEmpty()) b.header("User-Agent", meta.userAgent)
        if (meta.cookie.isNotEmpty()) b.header("Cookie", meta.cookie)
        if (meta.referer.isNotEmpty()) b.header("Referer", meta.referer)
        b.header("Accept-Encoding", "identity")
        if (range != null) {
            b.header("Range", range)
            val validator = if (meta.etag.isNotEmpty()) meta.etag else meta.lastModified
            if (validator.isNotEmpty()) b.header("If-Range", validator)
        }
        return b.build()
    }

    private inline fun <T> http(req: Request, block: (Response) -> T): T {
        val call = client.newCall(req)
        calls.add(call)
        try {
            return call.execute().use(block)
        } finally {
            calls.remove(call)
        }
    }

    /** Probes size, range support and validators with a 1-byte ranged GET. */
    private fun probe() {
        http(Request.Builder().url(meta.url).apply {
            if (meta.userAgent.isNotEmpty()) header("User-Agent", meta.userAgent)
            if (meta.cookie.isNotEmpty()) header("Cookie", meta.cookie)
            if (meta.referer.isNotEmpty()) header("Referer", meta.referer)
            header("Accept-Encoding", "identity")
            header("Range", "bytes=0-0")
        }.build()) { r ->
            if (r.code == 206) {
                val cr = r.header("Content-Range") ?: ""
                val total = cr.substringAfter('/', "").toLongOrNull()
                meta.size = total ?: -1
                meta.acceptRanges = total != null
            } else if (r.isSuccessful) {
                meta.size = r.header("Content-Length")?.toLongOrNull() ?: -1
                meta.acceptRanges = false
            } else {
                throw IOException("Server answered HTTP ${r.code}")
            }
            meta.etag = r.header("ETag") ?: ""
            meta.lastModified = r.header("Last-Modified") ?: ""
            if (meta.mime.isEmpty()) meta.mime = r.header("Content-Type")?.substringBefore(';') ?: ""
        }
    }

    private fun chooseChunk(size: Long): Int {
        val target = size / (connections.coerceAtLeast(1) * 8L)
        val bounded = target.coerceIn(512L * 1024, 8L * 1024 * 1024)
        return bounded.toInt()
    }

    private fun run() {
        if (meta.size <= 0 || meta.chunkSize <= 0 || !meta.acceptRanges) {
            probe()
            if (stop.get()) return
            meta.chunkSize = if (meta.size > 0) chooseChunk(meta.size) else 0
            meta.done.clear()
            meta.downloaded.set(0)
        }
        dir.mkdirs()
        listener.onProgress(meta)

        if (!meta.acceptRanges || meta.size <= 0 || meta.size < 256 * 1024) {
            singleStream()
            return
        }
        multiStream()
    }

    private fun singleStream() {
        meta.downloaded.set(0)
        meta.done.clear()
        val out = partFile
        if (out.exists()) out.delete()
        val startedAt = System.nanoTime()
        http(request(meta.url)) { r ->
            if (!r.isSuccessful) throw IOException("Server answered HTTP ${r.code}")
            if (meta.size <= 0) meta.size = r.header("Content-Length")?.toLongOrNull() ?: -1
            val body = r.body ?: throw IOException("Empty response")
            out.outputStream().use { fos ->
                pump(body.byteStream()) { buf, n ->
                    fos.write(buf, 0, n)
                    meta.downloaded.addAndGet(n.toLong())
                    updateSpeed(startedAt)
                }
            }
        }
        if (stop.get()) return
        if (meta.size <= 0) meta.size = meta.downloaded.get()
        finish()
    }

    private inline fun pump(input: InputStream, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(128 * 1024)
        while (!stop.get()) {
            val n = input.read(buf)
            if (n < 0) break
            sink(buf, n)
        }
    }

    private val lastSpeedNanos = AtomicLong(0)
    private val lastSpeedBytes = AtomicLong(0)

    private fun updateSpeed(startedAt: Long) {
        val now = System.nanoTime()
        val prev = lastSpeedNanos.get()
        if (prev == 0L) {
            lastSpeedNanos.set(now); lastSpeedBytes.set(meta.downloaded.get()); return
        }
        if (now - prev < 700_000_000L) return
        if (!lastSpeedNanos.compareAndSet(prev, now)) return
        val bytes = meta.downloaded.get()
        val inst = (bytes - lastSpeedBytes.getAndSet(bytes)) * 1e9 / (now - prev)
        meta.speedBps = if (meta.speedBps == 0.0) inst else meta.speedBps * 0.6 + inst * 0.4
        listener.onProgress(meta)
    }

    private fun multiStream() {
        val size = meta.size
        val chunkSize = meta.chunkSize
        val count = meta.chunkCount()
        val file = partFile
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() != size) raf.setLength(size)
            val channel = raf.channel
            val pending = java.util.concurrent.ConcurrentLinkedQueue<Int>()
            synchronized(meta.done) {
                for (i in 0 until count) if (!meta.done.get(i)) pending.add(i)
            }
            meta.downloaded.set(synchronized(meta.done) { bytesDone(count, size, chunkSize) })
            lastSpeedNanos.set(0)
            val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val startedAt = System.nanoTime()
            val pool = ArrayList<Thread>()
            val n = min(connections, max(1, pending.size))
            allowed.set(n)
            val saver = Thread({
                try {
                    while (running.get() > 0 || pool.isEmpty()) {
                        Thread.sleep(1000)
                        save()
                        if (stop.get()) break
                    }
                } catch (_: InterruptedException) {
                }
            }, "dl-saver-${meta.id}")
            saver.isDaemon = true
            for (w in 0 until n) {
                running.incrementAndGet()
                val t = Thread({
                    try {
                        while (!stop.get() && failure.get() == null) {
                            if (w >= allowed.get()) break
                            val idx = pending.poll() ?: break
                            val ok = fetchChunk(channel, idx, size, chunkSize, failure, startedAt)
                            if (!ok) {
                                pending.add(idx)
                                if (failure.get() != null) break
                            }
                        }
                    } finally {
                        running.decrementAndGet()
                    }
                }, "dl-w$w-${meta.id}")
                t.isDaemon = true
                pool.add(t)
            }
            saver.start()
            pool.forEach { it.start() }
            pool.forEach { it.join() }
            saver.interrupt()
            if (stop.get()) { save(); return }
            failure.get()?.let { throw IOException(it) }
            // If some chunks are still missing (workers shed due to throttling), mop up with one worker.
            var guard = 0
            while (!stop.get() && synchronized(meta.done) { meta.done.cardinality() } < count && guard++ < 6) {
                val missing = synchronized(meta.done) { (0 until count).filter { !meta.done.get(it) } }
                for (idx in missing) {
                    if (stop.get()) break
                    fetchChunk(channel, idx, size, chunkSize, failure, startedAt)
                }
                failure.get()?.let { throw IOException(it) }
            }
            if (stop.get()) { save(); return }
            channel.force(false)
        }
        if (synchronized(meta.done) { meta.done.cardinality() } < count) throw IOException("Download incomplete")
        finish()
    }

    private fun bytesDone(count: Int, size: Long, chunkSize: Int): Long {
        var total = 0L
        for (i in 0 until count) if (meta.done.get(i)) {
            val start = i.toLong() * chunkSize
            total += min(chunkSize.toLong(), size - start)
        }
        return total
    }

    /** Returns true when the chunk is complete. */
    private fun fetchChunk(
        channel: java.nio.channels.FileChannel,
        idx: Int,
        size: Long,
        chunkSize: Int,
        failure: java.util.concurrent.atomic.AtomicReference<String?>,
        startedAt: Long,
    ): Boolean {
        val start = idx.toLong() * chunkSize
        val end = min(start + chunkSize - 1, size - 1)
        var attempt = 0
        while (!stop.get()) {
            var credited = 0L
            try {
                http(request(meta.url, "bytes=$start-$end")) { r ->
                    when {
                        r.code == 206 -> {}
                        r.code == 429 || r.code == 503 -> {
                            val a = allowed.get()
                            if (a > 2) allowed.set(max(2, a / 2))
                            throw IOException("throttled (${r.code})")
                        }
                        r.code == 200 && start == 0L && end == size - 1 -> {}
                        r.code == 200 -> throw ChangedException("Server ignored Range or the file changed")
                        else -> throw IOException("HTTP ${r.code}")
                    }
                    val body = r.body ?: throw IOException("Empty response")
                    var pos = start
                    val buf = ByteArray(128 * 1024)
                    val input = body.byteStream()
                    while (!stop.get() && pos <= end) {
                        val want = min(buf.size.toLong(), end - pos + 1).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        var w = 0
                        while (w < n) w += channel.write(bb, pos + w)
                        pos += n
                        credited += n
                        meta.downloaded.addAndGet(n.toLong())
                        updateSpeed(startedAt)
                    }
                    if (stop.get()) { meta.downloaded.addAndGet(-credited); return false }
                    if (pos <= end) throw IOException("Connection closed early")
                }
                synchronized(meta.done) { meta.done.set(idx) }
                return true
            } catch (e: ChangedException) {
                meta.downloaded.addAndGet(-credited)
                failure.compareAndSet(null, e.message)
                return false
            } catch (e: IOException) {
                meta.downloaded.addAndGet(-credited)
                if (stop.get()) return false
                attempt++
                if (attempt > 6) {
                    failure.compareAndSet(null, e.message ?: "Network error")
                    return false
                }
                try {
                    Thread.sleep(min(4000L, 250L shl attempt))
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
    }

    private class ChangedException(msg: String) : IOException(msg)

    private fun finish() {
        if (stop.get()) return
        val part = partFile
        val fin = finalFile
        if (fin.exists()) fin.delete()
        if (part.exists() && !part.renameTo(fin)) {
            part.copyTo(fin, overwrite = true); part.delete()
        }
        meta.state = JobState.Completed
        meta.speedBps = 0.0
        meta.downloaded.set(max(meta.size, fin.length()))
        save()
        if (!finishedNotified) {
            finishedNotified = true
            listener.onFinished(meta, fin)
        }
        listener.onProgress(meta)
    }

    // --- persistence (java.util.Properties, no Android dependencies) ---

    fun save() {
        try {
            dir.mkdirs()
            val p = Properties()
            p["id"] = meta.id
            p["url"] = meta.url
            p["name"] = meta.fileName
            p["mime"] = meta.mime
            p["cookie"] = meta.cookie
            p["ua"] = meta.userAgent
            p["referer"] = meta.referer
            p["size"] = meta.size.toString()
            p["etag"] = meta.etag
            p["lm"] = meta.lastModified
            p["ranges"] = meta.acceptRanges.toString()
            p["chunk"] = meta.chunkSize.toString()
            p["state"] = meta.state.name
            p["error"] = meta.error
            p["saved"] = meta.savedUri
            p["created"] = meta.created.toString()
            p["done"] = synchronized(meta.done) { meta.done.toByteArray().joinToString("") { "%02x".format(it) } }
            val tmp = File(dir, "meta.tmp")
            tmp.outputStream().use { p.store(it, null) }
            tmp.renameTo(File(dir, "meta.properties"))
        } catch (_: IOException) {
        }
    }

    companion object {
        fun load(dir: File): JobMeta? {
            val f = File(dir, "meta.properties")
            if (!f.exists()) return null
            return try {
                val p = Properties()
                f.inputStream().use { p.load(it) }
                val m = JobMeta(
                    id = p.getProperty("id"),
                    url = p.getProperty("url"),
                    fileName = p.getProperty("name", "download"),
                    mime = p.getProperty("mime", ""),
                    cookie = p.getProperty("cookie", ""),
                    userAgent = p.getProperty("ua", ""),
                    referer = p.getProperty("referer", ""),
                )
                m.size = p.getProperty("size", "-1").toLong()
                m.etag = p.getProperty("etag", "")
                m.lastModified = p.getProperty("lm", "")
                m.acceptRanges = p.getProperty("ranges", "false").toBoolean()
                m.chunkSize = p.getProperty("chunk", "0").toInt()
                m.error = p.getProperty("error", "")
                m.savedUri = p.getProperty("saved", "")
                val hex = p.getProperty("done", "")
                if (hex.isNotEmpty()) {
                    val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                    m.done.or(BitSet.valueOf(bytes))
                }
                val saved = JobState.valueOf(p.getProperty("state", "Paused"))
                m.state = if (saved == JobState.Completed || saved == JobState.Failed) saved else JobState.Paused
                if (m.size > 0 && m.chunkSize > 0) {
                    var total = 0L
                    for (i in 0 until m.chunkCount()) if (m.done.get(i)) {
                        total += min(m.chunkSize.toLong(), m.size - i.toLong() * m.chunkSize)
                    }
                    m.downloaded.set(if (m.state == JobState.Completed) m.size else total)
                }
                m
            } catch (_: Exception) {
                null
            }
        }
    }
}
