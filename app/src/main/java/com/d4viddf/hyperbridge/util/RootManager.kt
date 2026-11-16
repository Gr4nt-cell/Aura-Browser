package com.d4viddf.hyperbridge.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Thin wrapper around `su`. Replaces the former Shizuku integration. */
object RootManager {
    private const val TAG = "RootManager"

    private val _hasRoot = MutableStateFlow(false)
    val hasRoot: StateFlow<Boolean> = _hasRoot.asStateFlow()

    data class Result(val code: Int, val out: String)

    /** Runs [command] as root. Never throws. */
    fun exec(command: String, timeoutSec: Long = 10): Result = try {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) { p.destroyForcibly(); Result(-1, "timeout") }
        else Result(p.exitValue(), p.inputStream.bufferedReader().readText().trim())
    } catch (e: Exception) {
        Result(-2, e.message ?: "su not available")
    }

    /** Triggers the root-manager prompt (first call) and reports whether root was granted. */
    suspend fun request(): Boolean = withContext(Dispatchers.IO) {
        val r = exec("id -u", 30)
        val ok = r.code == 0 && r.out.lines().lastOrNull()?.trim() == "0"
        _hasRoot.value = ok
        Log.i(TAG, "root granted=$ok ($r)")
        ok
    }

    /** Non-blocking best effort check used when the UI opens. */
    suspend fun refresh() { request() }

    // ---- network firewall (used to cut Xiaomi push services off for an instant) -----------------------------

    fun setUidNetworkBlocked(uid: Int, blocked: Boolean): Boolean {
        val rule = "OUTPUT -m owner --uid-owner $uid -j REJECT"
        val cmd = if (blocked) {
            "for t in iptables ip6tables; do \$t -w -C $rule 2>/dev/null || \$t -w -I $rule; done"
        } else {
            "for t in iptables ip6tables; do while \$t -w -D $rule 2>/dev/null; do :; done; done"
        }
        return exec(cmd).code == 0
    }

    // ---- heads-up (native pop-up) suppression ---------------------------------------------------------------

    fun readHeadsUp(): String = exec("settings get global heads_up_notifications_enabled").out.lines().lastOrNull()?.trim() ?: "1"

    fun setHeadsUpEnabled(enabled: Boolean): Boolean =
        exec("settings put global heads_up_notifications_enabled ${if (enabled) 1 else 0}").code == 0

    // ---- accessibility service enabling (so no manual trip into Settings is needed) ------------------------

    fun enableAccessibilityService(component: String): Boolean {
        val cur = exec("settings get secure enabled_accessibility_services").out.lines().lastOrNull()?.trim().orEmpty()
        val list = if (cur.isEmpty() || cur == "null") component else if (cur.split(":").contains(component)) cur else "$cur:$component"
        val a = exec("settings put secure enabled_accessibility_services '$list'").code == 0
        val b = exec("settings put secure accessibility_enabled 1").code == 0
        return a && b
    }
}
