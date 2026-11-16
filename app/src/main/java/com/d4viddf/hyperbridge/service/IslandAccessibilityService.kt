package com.d4viddf.hyperbridge.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.d4viddf.hyperbridge.overlay.GlassIslandOverlay

/**
 * Hosts the island window. A TYPE_ACCESSIBILITY_OVERLAY window can sit above the status bar and the camera cut-out
 * without the "display over other apps" permission. The service reads no screen content.
 */
class IslandAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: IslandAccessibilityService? = null
            private set
        const val COMPONENT = "com.d4viddf.hyperbridge/com.d4viddf.hyperbridge.service.IslandAccessibilityService"
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        GlassIslandOverlay.dismiss()
        return super.onUnbind(intent)
    }
    override fun onDestroy() { instance = null; super.onDestroy() }
}
