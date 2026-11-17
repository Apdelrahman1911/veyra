package dev.veyra.ui.music

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.veyra.nativecomponents.LocalNativeControlsCovered
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * Where the player sheet's top edge is, measured from the top of the [ExpandablePlayerScaffold]
 * content: 0 while the full player covers the content. Native chrome that UIKit draws above
 * Compose (the iOS navigation bar) reads it to step aside as the player slides over it.
 */
internal val LocalPlayerSheetTop = staticCompositionLocalOf<State<Dp>> { mutableStateOf(Dp.Infinity) }

/** One persistent sheet; dragging changes its pixels, while navigation records settled outcomes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExpandablePlayerScaffold(
    expanded: Boolean,
    hasPlayer: Boolean,
    dragEnabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compactPlayer: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    expandedPlayer: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val requestedExpanded by rememberUpdatedState(expanded)
    val onSettled by rememberUpdatedState(onExpandedChange)
    val sheet = rememberStandardBottomSheetState(
        initialValue = if (expanded) SheetValue.Expanded else SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffold = rememberBottomSheetScaffoldState(bottomSheetState = sheet)
    var measuredPeek by remember { mutableStateOf<Dp?>(null) }
    BoxWithConstraints(modifier) {
        val sheetHeight = maxHeight
        val peek = (measuredPeek ?: if (hasPlayer) 180.dp else 96.dp)
            .coerceAtMost(sheetHeight)
        val travel = with(density) { (sheetHeight - peek).toPx() }.coerceAtLeast(1f)
        val progress = remember(sheet, travel) {
            derivedStateOf {
                val offset = runCatching { sheet.requireOffset() }.getOrDefault(if (requestedExpanded) 0f else travel)
                (1f - offset / travel).coerceIn(0f, 1f)
            }
        }
        val sheetTop = remember(sheet, travel) {
            derivedStateOf {
                val offset = runCatching { sheet.requireOffset() }.getOrDefault(if (requestedExpanded) 0f else travel)
                with(density) { offset.toDp() }
            }
        }
        val showExpanded by remember(progress) { derivedStateOf { progress.value > .01f } }
        val showCompact by remember(progress) { derivedStateOf { progress.value < .34f } }
        // Material3 recomputes the sheet's anchors only when the sheet node is remeasured, and a new
        // peek height alone does not remeasure it. The first layout uses a fallback peek until the
        // compact content is measured (and the peek changes again with the keyboard, font scale or
        // the iOS tab bar), so the collapsed sheet would rest at a stale height. Flipping the sheet's
        // height by one pixel on every peek change remeasures it; the expanded anchor moves by at
        // most that pixel.
        var anchorEpoch by remember { mutableIntStateOf(0) }
        LaunchedEffect(peek) { anchorEpoch++ }
        val sheetContentHeight = sheetHeight - with(density) { (anchorEpoch % 2).toDp() }
        LaunchedEffect(sheet, expanded) {
            if (expanded) sheet.expand() else sheet.partialExpand()
        }
        // The current item can vanish while the full player is open; never leave an empty sheet up.
        LaunchedEffect(hasPlayer) {
            if (!hasPlayer && requestedExpanded) onSettled(false)
        }
        LaunchedEffect(sheet, travel) {
            snapshotFlow {
                val value = sheet.currentValue
                val anchor = if (value == SheetValue.Expanded) 0f else travel
                val offset = runCatching { sheet.requireOffset() }.getOrDefault(Float.NaN)
                value.takeIf { !sheet.isAnimationRunning && abs(offset - anchor) < 1f }
            }.filterNotNull().distinctUntilChanged().collect { value ->
                val open = value == SheetValue.Expanded
                if (open != requestedExpanded) onSettled(open)
            }
        }
        BottomSheetScaffold(
            scaffoldState = scaffold,
            modifier = Modifier.fillMaxSize().testTag("player-sheet-host"),
            sheetPeekHeight = peek,
            sheetMaxWidth = Dp.Unspecified,
            sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            // Collapsed, the sheet is only the floating mini player and the tab bar over the app's
            // own background; it turns opaque as it opens into the full player.
            sheetContainerColor = Color.Transparent,
            sheetTonalElevation = 0.dp,
            sheetShadowElevation = 0.dp,
            sheetDragHandle = null,
            sheetSwipeEnabled = hasPlayer && dragEnabled,
            containerColor = Color.Transparent,
            sheetContent = {
                val background = MaterialTheme.colorScheme.background
                Box(Modifier.fillMaxWidth().height(sheetContentHeight).testTag("expandable-player")
                    .drawBehind { drawRect(background.copy(alpha = (progress.value * 5f).coerceIn(0f, 1f))) }) {
                    if (showExpanded) {
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            alpha = ((progress.value - .12f) / .88f).coerceIn(0f, 1f)
                        }) { expandedPlayer() }
                    }
                    if (showCompact) {
                        Column(Modifier.fillMaxWidth()
                            .onSizeChanged { measuredPeek = with(density) { it.height.toDp() } }
                            .graphicsLayer { alpha = (1f - progress.value * 3f).coerceIn(0f, 1f) }) {
                            if (hasPlayer) compactPlayer()
                            bottomBar()
                        }
                    }
                }
            },
        ) { padding ->
            // Content runs beneath the floating mini player and tab bar instead of stopping above
            // them on a blank strip; scrolling screens pad their own end by the chrome's height.
            val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
            Box(Modifier.fillMaxSize().padding(start = padding.calculateStartPadding(layoutDirection),
                top = padding.calculateTopPadding(), end = padding.calculateEndPadding(layoutDirection))
                .then(if (showExpanded) Modifier.clearAndSetSemantics { } else Modifier)) {
                // Native UIKit controls in the content draw above Compose; hide them while the player covers them.
                CompositionLocalProvider(LocalPlayerSheetTop provides sheetTop, LocalNativeControlsCovered provides showExpanded,
                    dev.veyra.ui.design.LocalBottomChromeHeight provides padding.calculateBottomPadding()) {
                    content(PaddingValues())
                }
            }
        }
    }
}
