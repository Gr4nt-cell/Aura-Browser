package com.gr4nt.aurabrowser

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.gr4nt.aurabrowser.ui.AuraTheme
import com.gr4nt.aurabrowser.ui.BrowserRoot
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity(), WebHost {
    private var fileCb: ValueCallback<Array<Uri>>? = null
    /** Every capture target offered in the current chooser; whichever one the camera app wrote to wins. */
    private val captures = ArrayList<Pair<Uri, File>>()
    private var permCb: ((Map<String, Boolean>) -> Unit)? = null

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = fileCb ?: return@registerForActivityResult
        fileCb = null
        var out: Array<Uri>? = null
        if (res.resultCode == Activity.RESULT_OK) {
            val data = res.data
            val hasData = data != null && (data.data != null || data.clipData != null)
            out = if (hasData) WebChromeClient.FileChooserParams.parseResult(res.resultCode, data)
            else captures.firstOrNull { it.second.length() > 0L }?.let { arrayOf(it.first) }
        }
        captures.filter { it.second.length() == 0L }.forEach { it.second.delete() }
        captures.clear()
        cb.onReceiveValue(out)
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
        val cb = permCb
        permCb = null
        cb?.invoke(map)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Web.host = this
        Browser.restore()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        setContent { AuraTheme { BrowserRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val url = when (i.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { resolveInput(it) }
            else -> null
        } ?: return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val cur = Browser.current
        if (cur != null && cur.isBlank) Browser.load(cur, url) else Browser.newTab(url)
        i.data = null
        i.action = null
    }

    override fun onPause() {
        super.onPause()
        Browser.persist()
        Prefs.save()
        Browser.current?.webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        Browser.current?.webView?.onResume()
    }

    // -------------------------------------------------------------- WebHost

    override fun requestPermissions(perms: Array<String>, done: (Map<String, Boolean>) -> Unit) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { done(perms.associateWith { true }); return }
        permCb?.invoke(emptyMap())
        permCb = { res -> done(perms.associateWith { p -> res[p] == true || ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED }) }
        permLauncher.launch(missing.toTypedArray())
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun chooseFiles(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean {
        fileCb?.onReceiveValue(null)
        fileCb = callback
        captures.clear()

        val accept = params.acceptTypes.flatMap { it.split(",") }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val anything = accept.isEmpty() || accept.any { it == "*/*" || !it.contains('/') && !it.startsWith(".") }
        val wantsImage = anything || accept.any { it.startsWith("image/") || it in setOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic") }
        val wantsVideo = anything || accept.any { it.startsWith("video/") || it in setOf(".mp4", ".mov", ".webm") }
        val wantsAudio = accept.any { it.startsWith("audio/") }
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        // Camera needs the CAMERA permission up front, otherwise launching the capture intent throws.
        val offerCamera = wantsImage || wantsVideo
        if (offerCamera && !hasCamera() && (params.isCaptureEnabled || accept.any { it.startsWith("image/") || it.startsWith("video/") })) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA)) { launchChooser(params, accept, anything, wantsImage, wantsVideo, wantsAudio, multiple) }
        } else {
            launchChooser(params, accept, anything, wantsImage, wantsVideo, wantsAudio, multiple)
        }
        return true
    }

    private fun newCaptureUri(ext: String): Uri {
        val dir = File(cacheDir, "capture").also { it.mkdirs() }
        val f = File(dir, "aura_${UUID.randomUUID().toString().take(8)}.$ext")
        val uri = FileProvider.getUriForFile(this, "com.gr4nt.aurabrowser.files", f)
        captures.add(uri to f)
        return uri
    }

    private fun captureIntent(action: String, ext: String): Intent {
        val uri = newCaptureUri(ext)
        return Intent(action).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { it.clipData = ClipData.newRawUri("", uri) }
    }

    private fun launchChooser(
        params: WebChromeClient.FileChooserParams, accept: List<String>, anything: Boolean,
        wantsImage: Boolean, wantsVideo: Boolean, wantsAudio: Boolean, multiple: Boolean,
    ) {
        val cb = fileCb ?: return
        try {
            val camOk = hasCamera()
            // Direct camera when the page asks for capture (<input capture>).
            if (params.isCaptureEnabled && camOk) {
                val direct = when {
                    accept.any { it.startsWith("video/") } && accept.none { it.startsWith("image/") } -> captureIntent(MediaStore.ACTION_VIDEO_CAPTURE, "mp4")
                    wantsAudio && !wantsImage -> Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                    else -> captureIntent(MediaStore.ACTION_IMAGE_CAPTURE, "jpg")
                }
                if (direct.resolveActivity(packageManager) != null) { fileLauncher.launch(direct); return }
            }

            val mimes = accept.filter { it.contains('/') }
            val doc = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            if (!anything && mimes.isNotEmpty() && mimes.size == accept.size) doc.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            if (multiple) doc.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

            val extras = ArrayList<Intent>()
            if ((wantsImage || wantsVideo) && Build.VERSION.SDK_INT >= 33) {
                val pick = Intent(MediaStore.ACTION_PICK_IMAGES)
                if (wantsImage && !wantsVideo) pick.type = "image/*" else if (wantsVideo && !wantsImage) pick.type = "video/*"
                if (multiple) pick.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
                if (pick.resolveActivity(packageManager) != null) extras.add(pick)
            }
            if (camOk) {
                if (wantsImage) captureIntent(MediaStore.ACTION_IMAGE_CAPTURE, "jpg").takeIf { it.resolveActivity(packageManager) != null }?.let { extras.add(it) }
                if (wantsVideo) captureIntent(MediaStore.ACTION_VIDEO_CAPTURE, "mp4").takeIf { it.resolveActivity(packageManager) != null }?.let { extras.add(it) }
            }
            val chooser = Intent.createChooser(doc, params.title ?: "Choose files")
            if (extras.isNotEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
            fileLauncher.launch(chooser)
        } catch (e: Exception) {
            fileCb = null
            cb.onReceiveValue(null)
            Browser.showToast("Could not open the file picker")
        }
    }
}
