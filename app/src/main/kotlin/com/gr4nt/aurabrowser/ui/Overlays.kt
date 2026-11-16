package com.gr4nt.aurabrowser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gr4nt.aurabrowser.Blocker
import com.gr4nt.aurabrowser.Browser
import com.gr4nt.aurabrowser.Prompt
import com.gr4nt.aurabrowser.Web
import com.gr4nt.aurabrowser.dl.Downloads

/** Everything that floats above the page: switcher, address entry, sheets and page prompts. */
@Composable
fun Overlays(ui: UiState, form: Form) {
    AnimatedVisibility(
        visible = ui.switcher,
        enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.94f, animationSpec = tween(260)),
        exit = fadeOut(tween(170)) + scaleOut(targetScale = 0.94f, animationSpec = tween(200)),
    ) { TabSwitcher(ui, form) }

    if (form == Form.Phone && ui.editing && !ui.switcher) AddressOverlay(ui)
    if (ui.more) MoreSheet(ui, form)
    if (ui.settings) SettingsSheet(ui, form)
    if (ui.downloads) DownloadsSheet(ui, form)
    if (ui.library) LibrarySheet(ui, form)
    if (ui.privacy) PrivacySheet(ui, form)
    if (ui.media) MediaSheet(ui, form)
    PromptHost(form)
}

private fun originLabel(o: String): String = Blocker.hostOf(o)?.removePrefix("www.") ?: o.removePrefix("https://").removePrefix("http://").trimEnd('/')

@Composable
private fun PromptHost(form: Form) {
    val p = Browser.prompts.firstOrNull() ?: return
    val done = { Browser.prompts.remove(p); Unit }
    when (p) {
        is Prompt.Permission -> {
            val what = when {
                p.wantsCamera && p.wantsMic -> "camera and microphone"
                p.wantsCamera -> "camera"
                else -> "microphone"
            }
            GlassAlert(
                title = "“${originLabel(p.origin)}” would like to use your $what",
                confirm = "Allow", onConfirm = { Web.grantMedia(p.request, p.wantsCamera, p.wantsMic); done() },
                dismiss = "Don’t Allow", onDismiss = { p.request.deny(); done() },
            )
        }
        is Prompt.Geo -> GlassAlert(
            title = "“${originLabel(p.origin)}” would like to use your location",
            confirm = "Allow", onConfirm = { Web.grantGeo(p.origin, p.callback, true); done() },
            dismiss = "Don’t Allow", onDismiss = { Web.grantGeo(p.origin, p.callback, false); done() },
        )
        is Prompt.JsAlert -> GlassAlert(
            title = originLabel(p.origin), message = p.message,
            confirm = "OK", onConfirm = { p.result.confirm(); done() }, onDismiss = { p.result.confirm(); done() },
        )
        is Prompt.JsConfirm -> GlassAlert(
            title = originLabel(p.origin), message = p.message,
            confirm = "OK", onConfirm = { p.result.confirm(); done() },
            dismiss = "Cancel", onDismiss = { p.result.cancel(); done() },
        )
        is Prompt.JsPrompt -> {
            var text by remember(p) { mutableStateOf(p.default) }
            GlassAlert(
                title = originLabel(p.origin), message = p.message,
                confirm = "OK", onConfirm = { p.result.confirm(text); done() },
                dismiss = "Cancel", onDismiss = { p.result.cancel(); done() },
                extra = { PromptField(text, { text = it }, "") },
            )
        }
        is Prompt.HttpAuth -> {
            var user by remember(p) { mutableStateOf("") }
            var pass by remember(p) { mutableStateOf("") }
            GlassAlert(
                title = "Sign in to ${p.host}", message = p.realm.ifEmpty { null },
                confirm = "Sign In", onConfirm = { p.onOk(user, pass); done() },
                dismiss = "Cancel", onDismiss = { p.onCancel(); done() },
                extra = {
                    Column {
                        PromptField(user, { user = it }, "User name")
                        PromptField(pass, { pass = it }, "Password", secret = true)
                    }
                },
            )
        }
        is Prompt.External -> GlassAlert(
            title = p.label, message = "This link will open in another app.",
            confirm = "Open", onConfirm = { p.action(); done() },
            dismiss = "Cancel", onDismiss = { done() },
        )
        is Prompt.PopupBlocked -> GlassAlert(
            title = "Pop-up blocked", message = p.url.take(120),
            confirm = "Allow Once", onConfirm = { p.onAllow(); done() },
            dismiss = "Keep Blocked", onDismiss = { done() },
        )
        is Prompt.LinkMenu -> LinkMenu(p, form, done)
    }
}

@Composable
private fun PromptField(value: String, onChange: (String) -> Unit, hint: String, secret: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp))
            .background(if (LocalDark.current) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.07f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = Ink.tertiary(), fontSize = 15.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Ink.primary(), fontSize = 15.sp),
            cursorBrush = SolidColor(Ink.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LinkMenu(p: Prompt.LinkMenu, form: Form, done: () -> Unit) {
    val ctx = LocalContext.current
    val link = p.url
    val img = p.imageUrl
    val title = (link ?: img ?: "").let { if (it.length > 70) it.take(67) + "…" else it }
    val tab = Browser.tabs.firstOrNull { it.id == p.fromTab }
    GlassSheet(onDismiss = done, wide = form != Form.Phone, title = title) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (link != null) {
                SheetRow("Open in New Tab", Ic.Plus, { done(); Browser.newTab(link, true, p.fromTab) })
                SheetRow("Open in Background", Ic.Tabs, { done(); Browser.newTab(link, false, p.fromTab); Browser.showToast("Opened in background") })
                SheetRow("Open in Another App", Ic.External, { done(); Web.Externals.chooser(ctx, link) })
                SheetRow("Share", Ic.Share, { done(); Web.Externals.share(ctx, link) })
                SheetRow("Copy Link", Ic.Copy, { done(); clip(ctx, link) })
                if (tab != null) SheetRow("Download Linked File", Ic.Download, { done(); Downloads.enqueue(link, tab.currentUA, null, null, tab.url) })
            }
            if (img != null) {
                if (tab != null) SheetRow("Save Image", Ic.Download, { done(); Downloads.enqueue(img, tab.currentUA, null, null, tab.url) })
                SheetRow("Open Image in New Tab", Ic.Globe, { done(); Browser.newTab(img, true, p.fromTab) })
                SheetRow("Copy Image Address", Ic.Copy, { done(); clip(ctx, img) })
            }
            Box(Modifier.height(6.dp))
        }
    }
}

private fun clip(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", text))
    Browser.showToast("Copied")
}
