package com.d4viddf.hyperbridge.util

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.d4viddf.hyperbridge.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Posts / cancels the bridge notifications. When the root workaround is enabled the Xiaomi push service (xmsf)
 * is cut off from the network for a moment around every post, which stops HyperOS from intercepting it.
 */
object BridgeNotifier {
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private var restoreJob: Job? = null
    private val mutex = Mutex()

    private fun xmsfUid(context: Context): Int? = try {
        context.packageManager.getPackageUid(XMSF_PACKAGE, 0)
    } catch (_: Exception) { null }

    private suspend fun <T> guarded(context: Context, block: suspend () -> T): T {
        val enabled = AppPreferences(context).isRootWorkaroundEnabled.first()
        val uid = if (enabled && RootManager.hasRoot.value) xmsfUid(context) else null
        if (uid == null) return block()
        return mutex.withLock {
            restoreJob?.cancel()
            RootManager.setUidNetworkBlocked(uid, true)
            delay(50)
            val r = block()
            restoreJob = CoroutineScope(Dispatchers.IO).launch {
                delay(1000)
                RootManager.setUidNetworkBlocked(uid, false)
            }
            r
        }
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.POST_NOTIFICATIONS)
    fun notify(context: Context, id: Int, notification: Notification) {
        CoroutineScope(Dispatchers.IO).launch {
            guarded(context) { NotificationManagerCompat.from(context).notify(id, notification) }
        }
    }

    fun cancel(context: Context, id: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            guarded(context) { NotificationManagerCompat.from(context).cancel(id) }
        }
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.POST_NOTIFICATIONS)
    fun notifyWithCancel(context: Context, id: Int, notification: Notification) {
        CoroutineScope(Dispatchers.IO).launch {
            guarded(context) {
                NotificationManagerCompat.from(context).cancel(id)
                delay(20)
                NotificationManagerCompat.from(context).notify(id, notification)
            }
        }
    }
}
