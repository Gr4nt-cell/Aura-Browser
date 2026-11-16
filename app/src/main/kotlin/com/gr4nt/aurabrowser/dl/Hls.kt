package com.gr4nt.aurabrowser.dl

import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** HLS (.m3u8) downloader: picks the best variant, fetches segments in parallel and joins them into one file. */
class HlsJob(val meta: JobMeta, private val dir: File, private val http: okhttp3.OkHttpClient, private val onChange: () -> Unit, private val onDone: (File) -> Unit) {
    @Volatile var cancelled = false
    private val calls = ConcurrentLinkedQueue<Call>()

    fun cancel() { cancelled = true; calls.forEach { it.cancel() }; dir.deleteRecursively() }

    private fun req(url: String) = Request.Builder().url(url).apply {
        if (meta.userAgent.isNotEmpty()) header("User-Agent", meta.userAgent)
        if (meta.referer.isNotEmpty()) header("Referer", meta.referer)
        if (meta.cookie.isNotEmpty()) header("Cookie", meta.cookie)
    }.build()

    private fun text(url: String): String {
        val c = http.newCall(req(url)); calls.add(c)
        return try {
            c.execute().use { r -> if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}"); r.body!!.string() }
        } finally { calls.remove(c) }
    }

    private fun resolve(base: String, ref: String): String = try { URI(base).resolve(ref).toString() } catch (_: Exception) { ref }

    fun start() {
        meta.state = JobState.Downloading
        Thread({ runSafely() }, "hls-${meta.id}").start()
    }

    private fun fail(msg: String) { if (cancelled) return; meta.state = JobState.Failed; meta.error = msg; onChange() }

    private fun runSafely() {
        try {
            dir.mkdirs()
            var url = meta.url
            var body = text(url)
            var hops = 0
            while (body.contains("#EXT-X-STREAM-INF") && hops++ < 3) {
                val lines = body.lines()
                var best = -1L; var bestUri = ""
                for (i in lines.indices) {
                    if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                        val bw = Regex("(?<![A-Z-])BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLong() ?: 0
                        val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                        if (bw >= best) { best = bw; bestUri = uri.trim() }
                    }
                }
                if (bestUri.isEmpty()) break
                url = resolve(url, bestUri); body = text(url)
            }
            val lines = body.lines()
            if (lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) { fail("Encrypted stream (DRM) can't be saved"); return }
            val urls = ArrayList<String>()
            lines.firstOrNull { it.startsWith("#EXT-X-MAP") }?.let { l ->
                Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { urls.add(resolve(url, it)) }
                if (meta.fileName.endsWith(".ts")) meta.fileName = meta.fileName.removeSuffix(".ts") + ".mp4"
            }
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { urls.add(resolve(url, it.trim())) }
            if (urls.isEmpty()) { fail("No media segments found"); return }
            if (urls.size > 20000) { fail("Stream too long"); return }
            if (meta.fileName.endsWith(".mp4")) meta.mime = "video/mp4"
            val done = AtomicInteger(0)
            val failed = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val pool = Executors.newFixedThreadPool(8)
            val t0 = System.currentTimeMillis()
            for ((i, u) in urls.withIndex()) pool.execute {
                if (cancelled || failed.get() != null) return@execute
                var ok = false; var err = ""
                for (attempt in 1..4) {
                    if (cancelled) return@execute
                    try {
                        val c = http.newCall(req(u)); calls.add(c)
                        try {
                            c.execute().use { r ->
                                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                                File(dir, "seg_$i").outputStream().use { out -> r.body!!.byteStream().use { inp ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); meta.downloaded.addAndGet(n.toLong()) }
                                } }
                            }
                        } finally { calls.remove(c) }
                        ok = true; break
                    } catch (e: Exception) { err = e.message ?: "error"; Thread.sleep(300L * attempt) }
                }
                if (!ok) { failed.compareAndSet(null, "Segment failed ($err)"); return@execute }
                val d = done.incrementAndGet()
                val bytes = meta.downloaded.get()
                meta.size = bytes / d * urls.size
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                if (secs > 0.5) meta.speedBps = bytes / secs
                onChange()
            }
            pool.shutdown()
            while (!pool.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)) { if (cancelled) { pool.shutdownNow(); return } }
            if (cancelled) return
            failed.get()?.let { fail(it); return }
            val out = File(dir, "joined.bin")
            out.outputStream().buffered(256 * 1024).use { o ->
                for (i in urls.indices) File(dir, "seg_$i").inputStream().use { it.copyTo(o, 256 * 1024) }
            }
            for (i in urls.indices) File(dir, "seg_$i").delete()
            meta.size = out.length(); meta.speedBps = 0.0
            meta.state = JobState.Completed
            onDone(out)
        } catch (e: Exception) { fail(e.message ?: "Stream error") }
    }
}
