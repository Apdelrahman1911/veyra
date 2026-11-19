package dev.veyra.nativecomponents

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.apdelrahman1911.kira.ui.dialog.uikit.AlertDialogIosProperties

@Composable
actual fun NativeAlert(ios: AlertDialogIosProperties, onDismiss: () -> Unit, material: @Composable () -> Unit) = material()

@Composable
actual fun NativeSheet(
    onDismiss: () -> Unit,
    containerColor: Color,
    material: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) = material()
