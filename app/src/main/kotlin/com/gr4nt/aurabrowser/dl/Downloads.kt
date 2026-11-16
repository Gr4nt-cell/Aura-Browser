package com.gr4nt.aurabrowser.dl

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.gr4nt.aurabrowser.Prefs
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** One row in the downloads UI. Recreated on each tick so Compose sees changes. */
data class DownloadRow(
    val id: String,
    val name: String,
    val url: String,
    val state: JobState,
    val progress: Float,
    val downloaded: Long,
    val size: Long,
    val speedBps: Double,
    val error: String,
    val savedUri: String,
    val mime: String,
)

/**
 * App-level download manager: owns the Transfers, persists them under filesDir/downloads, exposes
 * observable rows and moves finished files into the public Downloads collection.
 */
object Downloads {
    lateinit var app: Context
        private set
    private lateinit var root: File
    private val client by lazy { Http.client(96) }
    private val transfers = LinkedHashMap<String, Transfer>()
    private val hls = java.util.concurrent.ConcurrentHashMap<String, HlsJob>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val rows = mutableStateListOf<DownloadRow>()
    val toast = mutableStateOf<String?>(null)
    private const val MAX_ACTIVE = 2

    fun init(context: Context) {
        app = context.applicationContext
        root = File(app.filesDir, "downloads").also { it.mkdirs() }
        root.listFiles()?.sortedBy { it.lastModified() }?.forEach { dir ->
            val meta = Transfer.load(dir) ?: return@forEach
            transfers[meta.id] = newTransfer(meta, dir)
        }
        publish()
    }

    private fun newTransfer(meta: JobMeta, dir: File): Transfer =
        Transfer(meta, dir, client, Prefs.downloadConnections, TransferListener(
            onProgress = { main.post { publish() } },
            onFinished = { m, file -> io.execute { export(m, file) } },
            onFailed = { m, msg -> main.post { toast.value = "Download failed: ${m.fileName} ($msg)"; publish(); pump() } },
        ))

    fun enqueue(url: String, userAgent: String, contentDisposition: String?, mime: String?, referer: String?) {
        val cookie = CookieManager.getInstance().getCookie(url) ?: ""
        var name = URLUtil.guessFileName(url, contentDisposition, mime)
        name = sanitize(name)
        val id = UUID.randomUUID().toString().take(12)
        val meta = JobMeta(id, url, name, mime ?: "", cookie, userAgent, referer ?: "")
        val dir = File(root, id)
        dir.mkdirs()
        val t = newTransfer(meta, dir)
        transfers[id] = t
        meta.state = JobState.Queued
        t.save()
        publish()
        toast.value = "Downloading $name"
        pump()
        ensureService()
    }

    /** Downloads an HLS (.m3u8) stream into a single file. */
    fun enqueueHls(url: String, userAgent: String, referer: String?, title: String) {
        val cookie = CookieManager.getInstance().getCookie(url) ?: ""
        val id = UUID.randomUUID().toString().take(12)
        val name = sanitize(title.ifBlank { "video" }) + ".ts"
        val meta = JobMeta(id, url, name, "video/mp2t", cookie, userAgent, referer ?: "")
        val job = HlsJob(meta, File(root, id), client, { main.post { publish() } }) { file ->
            io.execute { export(meta, file); File(root, id).deleteRecursively() }
        }
        hls[id] = job
        toast.value = "Downloading stream"
        job.start(); publish(); ensureService()
    }

    private fun sanitize(n: String): String {
        val cleaned = n.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        return if (cleaned.isEmpty()) "download" else cleaned.take(120)
    }

    /** Starts queued transfers up to the concurrency limit. */
    fun pump() {
        var active = transfers.values.count { it.meta.state == JobState.Downloading }
        for (t in transfers.values) {
            if (active >= MAX_ACTIVE) break
            if (t.meta.state == JobState.Queued) {
                t.connections = Prefs.downloadConnections
                t.start(); active++
            }
        }
        publish()
        if (active > 0) ensureService()
    }

    fun pause(id: String) { transfers[id]?.pause(); publish(); pump() }
    fun resume(id: String) {
        val t = transfers[id] ?: return
        if (t.meta.state == JobState.Paused || t.meta.state == JobState.Failed) {
            t.meta.state = JobState.Queued
            pump(); ensureService()
        }
    }
    fun pauseAll() { transfers.values.forEach { if (it.meta.state == JobState.Downloading || it.meta.state == JobState.Queued) it.pause() }; publish() }
    fun resumeAll() { transfers.values.forEach { if (it.meta.state == JobState.Paused) it.meta.state = JobState.Queued }; pump() }

    fun remove(id: String, deleteFile: Boolean) {
        hls.remove(id)?.let { j ->
            val uri = j.meta.savedUri
            j.cancel()
            if (deleteFile && uri.isNotEmpty()) try { app.contentResolver.delete(Uri.parse(uri), null, null) } catch (_: Exception) {}
            publish(); return
        }
        val t = transfers.remove(id) ?: return
        val uri = t.meta.savedUri
        t.cancelAndDelete()
        if (deleteFile && uri.isNotEmpty()) {
            try { app.contentResolver.delete(Uri.parse(uri), null, null) } catch (_: Exception) {}
        }
        publish(); pump()
    }

    fun clearFinished() {
        (transfers.values.map { it.meta } + hls.values.map { it.meta })
            .filter { it.state == JobState.Completed || it.state == JobState.Failed }
            .map { it.id }.forEach { remove(it, false) }
    }

    fun hasActive(): Boolean = transfers.values.any { it.meta.state == JobState.Downloading || it.meta.state == JobState.Queued } || hls.values.any { it.meta.state == JobState.Downloading }

    fun totalSpeed(): Double = transfers.values.filter { it.meta.state == JobState.Downloading }.sumOf { it.meta.speedBps } + hls.values.filter { it.meta.state == JobState.Downloading }.sumOf { it.meta.speedBps }
    fun overallProgress(): Float {
        val active = transfers.values.filter { it.meta.state == JobState.Downloading || it.meta.state == JobState.Queued }
        val total = active.sumOf { it.meta.size.coerceAtLeast(0) }
        if (total <= 0) return 0f
        return (active.sumOf { it.meta.downloaded.get() }.toFloat() / total).coerceIn(0f, 1f)
    }

    private fun publish() {
        val list = (transfers.values.map { it.meta } + hls.values.map { it.meta }).sortedByDescending { it.created }.map { m ->
            DownloadRow(m.id, m.fileName, m.url, m.state, m.progress(), m.downloaded.get(), m.size, m.speedBps, m.error, m.savedUri, m.mime)
        }
        rows.clear(); rows.addAll(list)
    }

    private fun ensureService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (_: Exception) {
        }
    }

    /** Copies the finished file into Downloads/Aura Browser and records its content Uri. */
    private fun export(meta: JobMeta, file: File) {
        try {
            val mime = meta.mime.ifEmpty {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(meta.fileName.substringAfterLast('.', "").lowercase())
            } ?: "application/octet-stream"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, meta.fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Aura Browser")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val cr = app.contentResolver
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore refused")
                cr.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                cr.update(uri, values, null, null)
                meta.savedUri = uri.toString()
                file.delete()
            } else {
                val outDir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Aura Browser").also { it.mkdirs() }
                var target = File(outDir, meta.fileName)
                var i = 1
                while (target.exists()) { target = File(outDir, meta.fileName.substringBeforeLast('.') + " ($i)." + meta.fileName.substringAfterLast('.', "bin")); i++ }
                file.copyTo(target, overwrite = false)
                file.delete()
                meta.savedUri = FileProvider.getUriForFile(app, "com.gr4nt.aurabrowser.files", target).toString()
            }
            transfers[meta.id]?.save()
            main.post { toast.value = "Saved ${meta.fileName} to Downloads/Aura Browser"; publish(); pump() }
        } catch (e: Exception) {
            main.post { toast.value = "Could not save ${meta.fileName}: ${e.message}"; publish(); pump() }
        }
    }

    /** Saves a file that already exists locally (blob:/data: downloads) into Downloads/Aura Browser. */
    fun saveLocal(file: File, name: String, mime: String) {
        val meta = JobMeta(UUID.randomUUID().toString().take(12), "local:", sanitize(name), mime)
        meta.state = JobState.Completed
        io.execute { export(meta, file) }
    }

    fun open(row: DownloadRow) {
        if (row.savedUri.isEmpty()) return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(row.savedUri), row.mime.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { app.startActivity(i) } catch (_: Exception) { toast.value = "No app can open this file" }
    }

    fun share(row: DownloadRow) {
        if (row.savedUri.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND).setType(row.mime.ifEmpty { "*/*" })
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(row.savedUri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
