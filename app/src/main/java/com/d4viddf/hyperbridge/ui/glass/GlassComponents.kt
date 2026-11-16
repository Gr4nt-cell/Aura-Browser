@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("UNUSED_PARAMETER", "FunctionName")

package com.d4viddf.hyperbridge.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardActions

// ---- Surfaces -------------------------------------------------------------------------------

@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = Glass.shapeLarge,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    border: BorderStroke? = Glass.border(LocalGlassDark.current),
    content: @Composable ColumnScope.() -> Unit
) = androidx.compose.material3.Card(modifier, shape, colors, elevation, border, content)

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Glass.shapeLarge,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    border: BorderStroke? = Glass.border(LocalGlassDark.current),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit
) = androidx.compose.material3.Card(onClick, modifier, enabled, shape, colors, elevation, border, interactionSource, content)

@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = Glass.shapeLarge,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) = androidx.compose.material3.Surface(modifier, shape, color, contentColor, 0.dp, 0.dp, border, content)

@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Glass.shapeLarge,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) = androidx.compose.material3.Surface(onClick, modifier, enabled, shape, color, contentColor, 0.dp, 0.dp, border, interactionSource, content)

// ---- Buttons --------------------------------------------------------------------------------

@Composable
private fun glassButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Glass.shapePill,
    colors: ButtonColors = glassButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    border: BorderStroke? = Glass.border(LocalGlassDark.current),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = androidx.compose.material3.Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Glass.shapePill,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    border: BorderStroke? = Glass.border(LocalGlassDark.current),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = androidx.compose.material3.FilledTonalButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Glass.shapePill,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = Glass.border(LocalGlassDark.current),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Glass.shapePill,
    colors: ButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = androidx.compose.material3.TextButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun FilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Glass.shapePill,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) = androidx.compose.material3.FilledTonalIconButton(
    onClick,
    modifier.then(Modifier),
    enabled, shape, colors, interactionSource, content
)

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) = androidx.compose.material3.IconButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors, interactionSource = interactionSource, content = content)

// ---- Controls -------------------------------------------------------------------------------

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
        checkedBorderColor = Color.White.copy(alpha = 0.45f),
        uncheckedThumbColor = Color.White.copy(alpha = 0.9f),
        uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
        uncheckedBorderColor = Color.White.copy(alpha = 0.35f)
    ),
    interactionSource: MutableInteractionSource? = null
) = androidx.compose.material3.Switch(checked, onCheckedChange, modifier, thumbContent, enabled, colors, interactionSource)

@Composable
fun HorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 0.6.dp,
    color: Color = Color.White.copy(alpha = 0.16f)
) = androidx.compose.material3.HorizontalDivider(modifier, thickness, color)

// ---- Bars -----------------------------------------------------------------------------------

@Composable
private fun glassBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = if (LocalGlassDark.current) Color(0x660B1230) else Color(0x66FFFFFF),
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
    actionIconContentColor = MaterialTheme.colorScheme.onSurface
)

@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = glassBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null
) = androidx.compose.material3.TopAppBar(title, modifier, navigationIcon, actions, expandedHeight, windowInsets, glassBarColors(), scrollBehavior)

@Composable
fun LargeTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    collapsedHeight: Dp = TopAppBarDefaults.LargeAppBarCollapsedHeight,
    expandedHeight: Dp = TopAppBarDefaults.LargeAppBarExpandedHeight,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = glassBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null
) = androidx.compose.material3.LargeTopAppBar(title, modifier, navigationIcon, actions, collapsedHeight, expandedHeight, windowInsets, glassBarColors(), scrollBehavior)

// ---- Layout ---------------------------------------------------------------------------------

@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit
) = androidx.compose.material3.Scaffold(
    modifier, topBar, bottomBar, snackbarHost, floatingActionButton, floatingActionButtonPosition,
    Color.Transparent, contentColor, contentWindowInsets, content
)

// ---- Overlays (separate windows: need their own frosted fill) --------------------------------

@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
    containerColor: Color = Color.Unspecified,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = Color(0x66000000),
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.4f)) },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    content: @Composable ColumnScope.() -> Unit
) = androidx.compose.material3.ModalBottomSheet(
    onDismissRequest, modifier, sheetState, sheetMaxWidth, sheetGesturesEnabled, shape,
    if (LocalGlassDark.current) Glass.sheetFill else Glass.sheetFillLight,
    contentColor, 0.dp, scrimColor, dragHandle, contentWindowInsets, properties, content
)

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(30.dp),
    containerColor: Color = Color.Unspecified,
    iconContentColor: Color = MaterialTheme.colorScheme.primary,
    titleContentColor: Color = MaterialTheme.colorScheme.onSurface,
    textContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties()
) = androidx.compose.material3.AlertDialog(
    onDismissRequest, confirmButton, modifier, dismissButton, icon, title, text, shape,
    if (LocalGlassDark.current) Glass.sheetFill else Glass.sheetFillLight,
    iconContentColor, titleContentColor, textContentColor, 0.dp, properties
)
