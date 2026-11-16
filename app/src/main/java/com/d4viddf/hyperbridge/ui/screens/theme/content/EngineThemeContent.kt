package com.d4viddf.hyperbridge.ui.screens.theme.content

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import com.d4viddf.hyperbridge.ui.glass.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import com.d4viddf.hyperbridge.ui.glass.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.ui.components.EngineOptionCard
import com.d4viddf.hyperbridge.ui.components.EnginePreview
import com.d4viddf.hyperbridge.ui.components.ListOptionCard
import kotlinx.coroutines.launch

@Composable
fun EngineThemeContent(
    isNative: Boolean?,
    showDefaultOption: Boolean = true, // [NEW] Controls if the default card is visible
    onEngineChange: (Boolean?) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.engine_preview_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Fallback to false (Xiaomi Custom) for the preview graphic if it's set to null (Global)
        EnginePreview(isNative = isNative ?: false)

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.engine_design_section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        // [NEW] Hide this option if requested
        if (showDefaultOption) {
            EngineOptionCard(
                title = stringResource(R.string.use_global_default),
                description = stringResource(R.string.appearance_use_defaults_desc),
                isSelected = isNative == null,
                onClick = { onEngineChange(null) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Xiaomi Engine Option
        EngineOptionCard(
            title = stringResource(R.string.engine_xiaomi_title),
            description = stringResource(R.string.engine_xiaomi_desc),
            isSelected = isNative == false,
            onClick = { onEngineChange(false) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Native Android Live Updates Option
        EngineOptionCard(
            title = stringResource(R.string.engine_native_title),
            description = stringResource(R.string.engine_native_desc),
            isSelected = isNative == true,
            onClick = { onEngineChange(true) }
        )

        if (isNative == false) {
            Spacer(modifier = Modifier.height(32.dp))

            com.d4viddf.hyperbridge.ui.components.RootAndIslandSection(prefs)
        }
    }
}