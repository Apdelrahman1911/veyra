package dev.veyra.ui.design

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veyra.nativecomponents.NativeSegmentedControl

/**
 * One choice among a few views of the same content (Music/Podcasts, Name/Creator, …). iOS shows
 * UIKit's segmented control; other platforms a Material segmented row in the same colours.
 * [tags] name each segment for tests on the Compose fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tags: List<String>? = null,
) {
    NativeSegmentedControl(labels, selectedIndex, onSelect, modifier) {
        SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        activeBorderColor = MaterialTheme.colorScheme.primary,
                        inactiveBorderColor = LocalVeyraColors.current.outlineSubtle,
                    ),
                    icon = {},
                    modifier = Modifier.heightIn(min = 48.dp).then(tags?.getOrNull(index)?.let { Modifier.testTag(it) } ?: Modifier),
                ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
