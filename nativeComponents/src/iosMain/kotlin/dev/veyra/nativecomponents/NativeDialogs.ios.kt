package dev.veyra.nativecomponents

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mohamedrejeb.calf.ui.ExperimentalCalfUiApi
import com.mohamedrejeb.calf.ui.dialog.AdaptiveBasicAlertDialog
import com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosProperties
import com.mohamedrejeb.calf.ui.sheet.AdaptiveBottomSheet
import com.mohamedrejeb.calf.ui.sheet.rememberAdaptiveSheetState

@OptIn(ExperimentalCalfUiApi::class)
@Composable
actual fun NativeAlert(ios: AlertDialogIosProperties, onDismiss: () -> Unit, material: @Composable () -> Unit) =
    AdaptiveBasicAlertDialog(onDismissRequest = onDismiss, iosProperties = ios, materialContent = {})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun NativeSheet(
    onDismiss: () -> Unit,
    containerColor: Color,
    material: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) = AdaptiveBottomSheet(
    onDismissRequest = onDismiss,
    adaptiveSheetState = rememberAdaptiveSheetState(),
    containerColor = containerColor,
    content = content,
)
