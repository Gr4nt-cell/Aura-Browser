package com.d4viddf.hyperbridge.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.d4viddf.hyperbridge.ui.glass.Button
import androidx.compose.material3.MaterialTheme
import com.d4viddf.hyperbridge.ui.glass.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.service.IslandAccessibilityService
import com.d4viddf.hyperbridge.util.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
        Spacer(Modifier.height(6.dp))
        Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Root access, camera-wrapping island and notification interception controls. Replaces the old Shizuku section. */
@Composable
fun RootAndIslandSection(prefs: AppPreferences, showXmsfWorkaround: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasRoot by RootManager.hasRoot.collectAsState()
    val workaround by prefs.isRootWorkaroundEnabled.collectAsState(initial = false)
    val islandOn by prefs.overlayIslandEnabled.collectAsState(initial = true)
    val interceptAll by prefs.interceptAllApps.collectAsState(initial = true)
    val suppress by prefs.suppressNativePopups.collectAsState(initial = false)
    val a11yReady = IslandAccessibilityService.instance != null

    Column(Modifier.fillMaxWidth()) {
        // --- root status
        Text(stringResource(R.string.root_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(if (hasRoot) R.string.root_granted else R.string.root_not_granted),
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasRoot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = { scope.launch { RootManager.request() } }, enabled = !hasRoot, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(if (hasRoot) R.string.perm_granted else R.string.root_request))
        }
        Spacer(Modifier.height(18.dp))

        // --- island window
        SwitchRow(stringResource(R.string.island_title), stringResource(R.string.island_desc), islandOn) { scope.launch { prefs.setOverlayIslandEnabled(it) } }
        if (islandOn && !a11yReady) {
            Text(stringResource(R.string.island_needs_accessibility), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.island_open_accessibility)) }
                if (hasRoot) Button(onClick = {
                    scope.launch(Dispatchers.IO) { RootManager.enableAccessibilityService(IslandAccessibilityService.COMPONENT) }
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.island_enable_with_root)) }
            }
        }
        SwitchRow(stringResource(R.string.intercept_all_title), stringResource(R.string.intercept_all_desc), interceptAll) { scope.launch { prefs.setInterceptAllApps(it) } }
        SwitchRow(stringResource(R.string.suppress_native_title), stringResource(R.string.suppress_native_desc), suppress, enabled = hasRoot) { scope.launch { prefs.setSuppressNativePopups(it) } }
        if (showXmsfWorkaround) {
            SwitchRow(stringResource(R.string.root_workaround_title), stringResource(R.string.root_workaround_desc), workaround, enabled = hasRoot) { scope.launch { prefs.setRootWorkaroundEnabled(it) } }
        }
    }
}
