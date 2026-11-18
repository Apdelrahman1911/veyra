package dev.veyra.ui.design

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.mohamedrejeb.calf.ui.ExperimentalCalfUiApi
import com.mohamedrejeb.calf.ui.dropdown.AdaptiveDropDown
import com.mohamedrejeb.calf.ui.dropdown.AdaptiveDropDownItem
import com.mohamedrejeb.calf.ui.dropdown.AdaptiveDropDownSection
import com.mohamedrejeb.calf.ui.uikit.UIKitImage

/**
 * One menu command. [symbol] is the SF Symbol shown by the native iOS menu; [icon] is the
 * Material leading icon. Either may be omitted.
 */
class MenuEntry(
    val title: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val tag: String? = null,
    val icon: ImageVector? = null,
    val symbol: String? = null,
)

/** Collects menu entries in display order; [divider] starts a new group. */
class MenuBuilder internal constructor() {
    internal val groups = mutableListOf(mutableListOf<MenuEntry>())

    fun item(
        title: String,
        tag: String? = null,
        enabled: Boolean = true,
        destructive: Boolean = false,
        icon: ImageVector? = null,
        symbol: String? = null,
        onClick: () -> Unit,
    ) {
        groups.last() += MenuEntry(title, onClick, enabled, destructive, tag, icon, symbol)
    }

    fun divider() {
        if (groups.last().isNotEmpty()) groups += mutableListOf<MenuEntry>()
    }
}

/**
 * A menu anchored to the enclosing box. iOS presents a native UIMenu (pull-down menu, grouped with
 * system separators, destructive items in red); Android and desktop show the Material menu with the
 * same entries, tags and enabled states. Entries are rebuilt on every composition, so labels and
 * availability always reflect current state.
 */
@OptIn(ExperimentalCalfUiApi::class)
@Composable
fun BoxScope.NativeMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    label: @Composable (String) -> Unit = { Text(it) },
    build: MenuBuilder.() -> Unit,
) {
    val groups = MenuBuilder().apply(build).groups.filter { it.isNotEmpty() }
    fun MenuEntry.native() = AdaptiveDropDownItem(
        title = title,
        iosIcon = symbol?.let(UIKitImage::SystemName),
        isDestructive = destructive,
        isDisabled = !enabled,
        onClick = onClick,
    )
    AdaptiveDropDown(
        expanded = expanded,
        onDismissRequest = onDismiss,
        iosItems = groups.firstOrNull().orEmpty().map { it.native() },
        iosSections = groups.drop(1).map { group -> AdaptiveDropDownSection(items = group.map { it.native() }) },
    ) {
        groups.forEachIndexed { index, group ->
            if (index > 0) HorizontalDivider()
            group.forEach { entry ->
                DropdownMenuItem(
                    text = { label(entry.title) },
                    onClick = entry.onClick,
                    enabled = entry.enabled,
                    leadingIcon = entry.icon?.let { icon -> { Icon(icon, null) } },
                    colors = if (entry.destructive) androidx.compose.material3.MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error) else androidx.compose.material3.MenuDefaults.itemColors(),
                    modifier = entry.tag?.let { Modifier.testTag(it) } ?: Modifier,
                )
            }
        }
    }
}
