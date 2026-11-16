package com.d4viddf.hyperbridge.util

import android.content.Context
import android.provider.Settings
import com.d4viddf.hyperbridge.service.IslandAccessibilityService

/** The island and inline-reply windows are drawn by an accessibility service; this replaces "display over other apps". */
object IslandAccess {
    fun isEnabled(context: Context): Boolean {
        if (IslandAccessibilityService.instance != null) return true
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(IslandAccessibilityService.COMPONENT, true) || it.endsWith("IslandAccessibilityService") }
    }
}
