package io.legado.app.ui.main.rss

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.ui.design.components.compose.NgExpandableActionMenu
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuItem
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuVariant
import io.legado.app.ui.design.components.compose.NgPopupToggleState
import io.legado.app.ui.design.components.compose.NgSearchBar
import io.legado.app.ui.design.components.compose.NgSearchBarActionButton
import io.legado.app.ui.design.components.compose.NgSearchBarVariant
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.ui.main.explore.sourceTileColor
import io.legado.app.ui.main.explore.sourceTileContentColor
import io.legado.app.utils.firstDisplayGrapheme

private const val RSS_FAVORITE_ITEM_ID = 0x53000001
private const val RSS_GROUP_ITEM_ID = 0x53000002
private const val RSS_ALL_GROUP_ITEM_ID = 0x53000003
private const val RSS_SOURCE_MANAGE_ITEM_ID = 0x53000004
private const val RSS_GROUP_CHILD_ITEM_ID_BASE = 0x53100000

@Composable
internal fun RssTopBar(
    query: String,
    groups: List<String>,
    selectedGroup: String?,
    modernStyle: Boolean,
    currentSourceUrl: String? = null,
    currentSourceName: String = "",
    categoriesExpanded: Boolean = false,
    onOpenSourcePicker: () -> Unit = {},
    onToggleCategories: () -> Unit = {},
    onCategoryAnchorPosition: (Float) -> Unit = {},
    onToggleModernStyle: () -> Unit,
    onQueryChange: (String) -> Unit,
    onGroupSelected: (String?) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSourceManage: () -> Unit
) {
    val menuState = remember { NgPopupToggleState() }
    val menuItems = remember(groups, selectedGroup) {
        listOf(
            NgExpandableActionMenuItem(
                itemId = RSS_FAVORITE_ITEM_ID,
                titleRes = R.string.favorite,
                iconRes = R.drawable.ic_star
            ),
            NgExpandableActionMenuItem(
                itemId = RSS_GROUP_ITEM_ID,
                titleRes = R.string.group,
                iconRes = R.drawable.ic_groups,
                checked = selectedGroup != null,
                children = buildList {
                    add(
                        NgExpandableActionMenuItem(
                            itemId = RSS_ALL_GROUP_ITEM_ID,
                            titleRes = R.string.all,
                            iconRes = R.drawable.ic_check_source,
                            checked = selectedGroup == null
                        )
                    )
                    groups.forEachIndexed { index, group ->
                        add(
                            NgExpandableActionMenuItem(
                                itemId = RSS_GROUP_CHILD_ITEM_ID_BASE + index,
                                titleRes = 0,
                                iconRes = R.drawable.ic_groups,
                                title = group,
                                checked = group == selectedGroup
                            )
                        )
                    }
                }
            ),
            NgExpandableActionMenuItem(
                itemId = RSS_SOURCE_MANAGE_ITEM_ID,
                titleRes = R.string.rss_source_manage,
                iconRes = R.drawable.ic_settings,
                dividerBefore = true
            )
        )
    }

    val spacerWidth = if (modernStyle) 6.dp else 8.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (modernStyle) {
            RssSourceSwitchButton(
                sourceUrl = currentSourceUrl,
                sourceName = currentSourceName,
                onClick = onOpenSourcePicker,
            )
            Spacer(Modifier.width(spacerWidth))
        }
        NgSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            hint = if (modernStyle && currentSourceName.isNotBlank()) {
                currentSourceName
            } else {
                stringResource(R.string.search_rss_source)
            },
            modifier = Modifier.weight(1f),
            variant = NgSearchBarVariant.TOOLBAR,
            hideHintOnFocus = true
        )
        if (modernStyle) {
            Spacer(Modifier.width(spacerWidth))
            NgSearchBarActionButton(
                onClick = onToggleCategories,
                contentDescription = stringResource(
                    if (categoriesExpanded) R.string.explore_hide_categories
                    else R.string.explore_show_categories
                ),
                modifier = Modifier.onGloballyPositioned {
                    onCategoryAnchorPosition(it.boundsInRoot().center.x)
                },
                icon = painterResource(R.drawable.ic_grid_menu),
                tint = if (categoriesExpanded) Color(NgTheme.colors.primary) else null,
            )
        }
        Spacer(Modifier.width(spacerWidth))
        NgSearchBarActionButton(
            onClick = onToggleModernStyle,
            contentDescription = if (modernStyle) "切换为经典订阅样式" else "切换为新版订阅样式",
            icon = painterResource(
                if (modernStyle) R.drawable.ic_bottom_explore_e else R.drawable.ic_chapter_list
            ),
            tint = if (modernStyle) Color(NgTheme.colors.primary) else null,
        )
        Spacer(Modifier.width(spacerWidth))
        Box {
            NgSearchBarActionButton(
                onClick = menuState::onAnchorClick,
                contentDescription = stringResource(R.string.group),
            )
            NgExpandableActionMenu(
                expanded = menuState.expanded,
                onDismissRequest = menuState::onDismissRequest,
                items = menuItems,
                variant = NgExpandableActionMenuVariant.SIDE_SLIDE,
                sideSlideEndMargin = 10.dp,
                sideSlideAnchorGap = 8.dp,
                properties = PopupProperties(focusable = true, clippingEnabled = false),
                onItemClick = { item ->
                    if (!menuState.expanded) return@NgExpandableActionMenu
                    menuState.close()
                    when (item.itemId) {
                        RSS_FAVORITE_ITEM_ID -> onOpenFavorites()
                        RSS_ALL_GROUP_ITEM_ID -> onGroupSelected(null)
                        RSS_SOURCE_MANAGE_ITEM_ID -> onOpenSourceManage()
                        else -> {
                            val index = item.itemId - RSS_GROUP_CHILD_ITEM_ID_BASE
                            onGroupSelected(groups.getOrNull(index))
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun RssSourceSwitchButton(
    sourceUrl: String?,
    sourceName: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    if (sourceUrl != null && sourceName.isNotBlank()) {
        val tileColorInt = remember(sourceUrl) { sourceTileColor(sourceUrl) }
        val tileContentColor = remember(tileColorInt) { sourceTileContentColor(tileColorInt) }
        val glyph = remember(sourceName) {
            sourceName.trim().firstDisplayGrapheme().uppercase().ifEmpty { "订" }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(shape)
                .background(Color(tileColorInt))
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                color = tileContentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    } else {
        NgSearchBarActionButton(
            onClick = onClick,
            contentDescription = "切换订阅源",
            icon = painterResource(R.drawable.ic_cfg_source),
        )
    }
}
