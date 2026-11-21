package io.legado.app.ui.rss.source.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.design.components.compose.NgExpandableActionMenu
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuItem
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckboxVariant
import io.legado.app.ui.design.components.compose.NgFileSelectionTriStateCheckbox
import io.legado.app.ui.design.components.compose.NgFlatActionRail
import io.legado.app.ui.design.components.compose.NgFlatActionRailItem
import io.legado.app.ui.design.components.compose.NgFlatActionRailVariant
import io.legado.app.ui.design.components.compose.NgGlassDefaults
import io.legado.app.ui.design.components.compose.NgGlassSurface
import io.legado.app.ui.design.components.compose.NgMaterialRole
import io.legado.app.ui.design.components.compose.NgMenuSelectionStyle
import io.legado.app.ui.design.components.compose.NgPopupToggleState
import io.legado.app.ui.design.theme.NgTheme

private const val RSS_ORDER_PARENT_ID = 0x55100002
private const val RSS_TYPE_MANAGE_ID = 0x55100003

@Composable
internal fun RssSourceManageBottomDock(
    selectedCount: Int,
    totalCount: Int,
    selectAllState: ToggleableState,
    selectedAllEnabled: Boolean = false,
    showDedupeExpandAll: Boolean = false,
    allDedupeExpanded: Boolean = false,
    modifier: Modifier = Modifier,
    onAction: (RssSourceManageAction) -> Unit,
) {
    val menuState = remember { NgPopupToggleState() }
    NgGlassSurface(
        modifier = modifier.fillMaxWidth(),
        role = NgMaterialRole.CONTROL,
        shape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp),
        style = NgGlassDefaults.bookDetailStyle(
            containerColor = colorResource(R.color.ng_bookshelf_manage_control_surface)
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showDedupeExpandAll) {
                val toggleLabel = stringResource(
                    if (allDedupeExpanded) {
                        R.string.book_source_dedupe_collapse_all
                    } else {
                        R.string.book_source_dedupe_expand_all
                    }
                )
                val buttonBg = if (allDedupeExpanded) {
                    Color(NgTheme.colors.primary)
                } else {
                    Color(NgTheme.colors.surfaceVariant)
                }
                val buttonFg = if (allDedupeExpanded) {
                    Color(NgTheme.colors.onPrimary)
                } else {
                    Color(NgTheme.colors.onSurface)
                }
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(buttonBg)
                            .semantics { contentDescription = toggleLabel }
                            .clickable { onAction(RssSourceManageAction.ToggleAllDedupe) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right_20),
                            contentDescription = null,
                            tint = buttonFg,
                            modifier = Modifier
                                .size(15.dp)
                                .rotate(if (allDedupeExpanded) 90f else 0f),
                        )
                    }
                }
            }
            NgFileSelectionTriStateCheckbox(
                state = selectAllState,
                onClick = {
                    if (selectAllState == ToggleableState.On) {
                        onAction(RssSourceManageAction.ClearSelection)
                    } else {
                        onAction(RssSourceManageAction.SelectAll)
                    }
                },
                enabled = totalCount > 0,
                variant = NgFileSelectionCheckboxVariant.COMPACT,
            )
            Text(
                text = stringResource(R.string.book_source_selected_count, selectedCount),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                color = Color(NgTheme.colors.onSurface),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(modifier = Modifier.width(84.dp)) {
                NgFlatActionRail(
                    items = listOf(
                        NgFlatActionRailItem(
                            iconRes = R.drawable.ic_more_horiz,
                            label = stringResource(R.string.more),
                            enabled = selectedCount > 0,
                            emphasized = menuState.expanded,
                        ),
                    ),
                    onItemClick = { menuState.onAnchorClick() },
                    variant = NgFlatActionRailVariant.INLINE_DIVIDED,
                    trailingOverlay = {
                        NgExpandableActionMenu(
                            expanded = menuState.expanded,
                            onDismissRequest = menuState::onDismissRequest,
                            items = rssSourceDockMoreItems(selectedAllEnabled),
                            onItemClick = { item ->
                                if (item.selectionStyle != NgMenuSelectionStyle.SWITCH) {
                                    menuState.close()
                                }
                                onAction(item.toRssSourceManageAction(selectedAllEnabled))
                            },
                            width = 184.dp,
                            rowMinHeight = 36.dp,
                            bottomPointerHeight = 8.dp,
                            bottomPointerWidth = 18.dp,
                            bottomPointerEndOffset = 34.dp,
                            menuContainerColor = colorResource(R.color.ng_surface_card),
                            offset = DpOffset(x = (-100).dp, y = (-12).dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun rssSourceDockMoreItems(selectedAllEnabled: Boolean): List<NgExpandableActionMenuItem> = listOf(
    NgExpandableActionMenuItem(
        itemId = R.id.menu_enable_selection,
        titleRes = R.string.enable_selection,
        iconRes = R.drawable.ic_check_circle_outline,
        checked = selectedAllEnabled,
        selectionStyle = NgMenuSelectionStyle.SWITCH,
    ),
    NgExpandableActionMenuItem(
        RSS_TYPE_MANAGE_ID,
        R.string.book_source_type_manage,
        R.drawable.ic_sort,
    ),
    NgExpandableActionMenuItem(
        R.id.menu_add_group,
        R.string.add_group,
        R.drawable.ic_add,
    ),
    NgExpandableActionMenuItem(
        R.id.menu_remove_group,
        R.string.remove_group,
        R.drawable.ic_clear,
    ),
    NgExpandableActionMenuItem(
        R.id.menu_clear_group,
        R.string.clear_group,
        R.drawable.ic_clear,
        dividerBefore = true,
        danger = true,
    ),
    NgExpandableActionMenuItem(
        R.id.menu_check_source,
        R.string.check_select_source,
        R.drawable.ic_check_source,
    ),
    NgExpandableActionMenuItem(
        R.id.menu_check_selected_interval,
        R.string.check_selected_interval,
        R.drawable.ic_select_interval,
    ),
    NgExpandableActionMenuItem(
        RSS_ORDER_PARENT_ID,
        R.string.sort,
        R.drawable.ic_sort,
        children = listOf(
            NgExpandableActionMenuItem(
                R.id.menu_top_sel,
                R.string.selection_to_top,
                R.drawable.ic_arrow_drop_up,
            ),
            NgExpandableActionMenuItem(
                R.id.menu_bottom_sel,
                R.string.selection_to_bottom,
                R.drawable.ic_arrow_down,
            ),
        ),
    ),
    NgExpandableActionMenuItem(
        R.id.menu_export_selection,
        R.string.book_source_export_share,
        R.drawable.ic_export,
    ),
    NgExpandableActionMenuItem(
        R.id.menu_del_selection,
        R.string.delete,
        R.drawable.ic_book_info_delete,
        dividerBefore = true,
        danger = true,
    ),
)

private fun NgExpandableActionMenuItem.toRssSourceManageAction(
    selectedAllEnabled: Boolean,
): RssSourceManageAction =
    when (itemId) {
        R.id.menu_enable_selection -> if (selectedAllEnabled) {
            RssSourceManageAction.DisableSelection
        } else {
            RssSourceManageAction.EnableSelection
        }
        R.id.menu_disable_selection -> RssSourceManageAction.DisableSelection
        RSS_TYPE_MANAGE_ID -> RssSourceManageAction.ConfigureSelectionType
        R.id.menu_add_group -> RssSourceManageAction.AddSelectionToGroup
        R.id.menu_remove_group -> RssSourceManageAction.RemoveSelectionFromGroup
        R.id.menu_clear_group -> RssSourceManageAction.ClearSelectionGroups
        R.id.menu_check_source -> RssSourceManageAction.CheckSelection
        R.id.menu_check_selected_interval -> RssSourceManageAction.CompleteSelectionInterval
        R.id.menu_top_sel -> RssSourceManageAction.TopSelection
        R.id.menu_bottom_sel -> RssSourceManageAction.BottomSelection
        R.id.menu_export_selection -> RssSourceManageAction.ExportOrShareSelection
        else -> RssSourceManageAction.DeleteSelection
    }
