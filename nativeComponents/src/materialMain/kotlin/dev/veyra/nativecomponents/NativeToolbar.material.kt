package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

actual val nativeToolbarAvailable: Boolean = false
actual val nativeToolbarClearance: Dp = 0.dp

@Composable
actual fun NativeToolbar(items: List<NativeBarButton>) = Unit
