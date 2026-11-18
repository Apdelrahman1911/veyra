package dev.veyra.nativecomponents

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosProperties

/**
 * An alert or action sheet. iOS presents a native UIAlertController built from [ios] (including
 * its text fields and enabled states, which update while it is shown); Android and desktop render
 * [material], the platform's existing Compose dialog, unchanged.
 */
@Composable
expect fun NativeAlert(ios: AlertDialogIosProperties, onDismiss: () -> Unit, material: @Composable () -> Unit)

/**
 * A modal sheet. iOS presents a native UISheetPresentationController page sheet (system grabber,
 * detents, swipe to dismiss) hosting [content]; Android and desktop render [material] unchanged.
 */
@Composable
expect fun NativeSheet(
    onDismiss: () -> Unit,
    containerColor: Color,
    material: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
)
