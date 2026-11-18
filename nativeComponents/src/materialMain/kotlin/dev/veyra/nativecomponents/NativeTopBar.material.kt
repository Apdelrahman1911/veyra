package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
actual fun NativeTopBar(
    title: String,
    leading: List<NativeBarButton>,
    trailing: List<NativeBarButton>,
    titleColor: Color,
    tintColor: Color,
    visible: Boolean,
    fallback: @Composable () -> Unit,
) = fallback()
