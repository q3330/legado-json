package io.legado.app.ui.main.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.constant.BookSourceType
import io.legado.app.ui.book.source.BookSourceGroupIcon
import io.legado.app.ui.design.components.compose.NgExpandableActionMenu
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuItem
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuVariant
import io.legado.app.ui.design.components.compose.NgPopupToggleState
import io.legado.app.ui.design.components.compose.NgSearchBar
import io.legado.app.ui.design.components.compose.NgSearchBarActionButton
import io.legado.app.ui.design.components.compose.NgSearchBarTranslateButton
import io.legado.app.ui.design.components.compose.NgSearchBarVariant
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.utils.firstDisplayGrapheme

private const val EXPLORE_GROUP_ITEM_ID_BASE = 0x51000000
private const val EXPLORE_LAYOUT_LIST_ITEM_ID = 0x52000001
private const val EXPLORE_LAYOUT_GRID_ITEM_ID = 0x52000002
private const val EXPLORE_LAYOUT_GROUP_GRID_ITEM_ID = 0x52000003
private const val EXPLORE_TYPE_PARENT_ITEM_ID = 0x52000100
private const val EXPLORE_TYPE_TEXT_ITEM_ID = 0x52000101
private const val EXPLORE_TYPE_IMAGE_ITEM_ID = 0x52000102
private const val EXPLORE_TYPE_AUDIO_ITEM_ID = 0x52000103
private const val EXPLORE_TYPE_VIDEO_ITEM_ID = 0x52000104
private const val EXPLORE_TYPE_FILE_ITEM_ID = 0x52000105

@Composable
internal fun ExploreTopBar(
    query: String,
    groups: List<String>,
    selectedGroup: String?,
    selectedType: Int?,
    layoutMode: ExploreLayoutMode,
    modernStyle: Boolean,
    currentSourceUrl: String? = null,
    currentSourceName: String = "",
    searchEnabled: Boolean = true,
    isTranslating: Boolean = false,
    categoriesExpanded: Boolean = false,
    onOpenSourcePicker: () -> Unit = {},
    onTranslateBooks: () -> Unit = {},
    onToggleCategories: () -> Unit = {},
    onCategoryAnchorPosition: (Float) -> Unit = {},
    onToggleModernStyle: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit = {},
    onGroupSelected: (String?) -> Unit,
    onTypeSelected: (Int?) -> Unit,
    onLayoutModeChange: (ExploreLayoutMode) -> Unit,
    onManageSources: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val menuState = remember { NgPopupToggleState() }
    val menuItems = remember(groups, selectedGroup, selectedType, layoutMode) {
        buildList {
            add(
                NgExpandableActionMenuItem(
                    itemId = EXPLORE_LAYOUT_LIST_ITEM_ID,
                    titleRes = R.string.replace_view_list,
                    iconRes = R.drawable.ic_chapter_list,
                    checked = layoutMode == ExploreLayoutMode.LIST
                )
            )
            add(
                NgExpandableActionMenuItem(
                    itemId = EXPLORE_LAYOUT_GRID_ITEM_ID,
                    titleRes = R.string.explore_view_grid,
                    iconRes = R.drawable.ic_grid_menu,
                    checked = layoutMode == ExploreLayoutMode.GRID
                )
            )
            add(
                NgExpandableActionMenuItem(
                    itemId = EXPLORE_LAYOUT_GROUP_GRID_ITEM_ID,
                    titleRes = R.string.explore_view_group_grid,
                    iconRes = R.drawable.ic_folder_outline,
                    checked = layoutMode == ExploreLayoutMode.GROUP_GRID
                )
            )
            add(
                NgExpandableActionMenuItem(
                    itemId = R.id.menu_source_manage,
                    titleRes = R.string.book_source_manage,
                    iconRes = R.drawable.ic_cfg_source,
                    dividerBefore = true,
                )
            )
            add(
                NgExpandableActionMenuItem(
                    itemId = R.id.menu_1,
                    titleRes = R.string.all_source,
                    iconRes = R.drawable.ic_check_source,
                    dividerBefore = true,
                    checked = selectedGroup == null && selectedType == null
                )
            )
            add(
                NgExpandableActionMenuItem(
                    itemId = EXPLORE_TYPE_PARENT_ITEM_ID,
                    titleRes = R.string.explore_type_sources,
                    iconRes = R.drawable.ic_sort,
                    children = listOf(
                        NgExpandableActionMenuItem(
                            itemId = EXPLORE_TYPE_TEXT_ITEM_ID,
                            titleRes = R.string.book_source_filter_type_text,
                            iconRes = R.drawable.ic_ai_capability_text,
                            checked = selectedType == BookSourceType.default,
                        ),
                        NgExpandableActionMenuItem(
                            itemId = EXPLORE_TYPE_IMAGE_ITEM_ID,
                            titleRes = R.string.book_source_filter_type_image,
                            iconRes = R.drawable.ic_image,
                            checked = selectedType == BookSourceType.image,
                        ),
                        NgExpandableActionMenuItem(
                            itemId = EXPLORE_TYPE_AUDIO_ITEM_ID,
                            titleRes = R.string.book_source_filter_type_audio,
                            iconRes = R.drawable.ic_tts_headphones,
                            checked = selectedType == BookSourceType.audio,
                        ),
                        NgExpandableActionMenuItem(
                            itemId = EXPLORE_TYPE_VIDEO_ITEM_ID,
                            titleRes = R.string.book_source_filter_type_video,
                            iconRes = R.drawable.ic_ai_capability_video,
                            checked = selectedType == BookSourceType.video,
                        ),
                        NgExpandableActionMenuItem(
                            itemId = EXPLORE_TYPE_FILE_ITEM_ID,
                            titleRes = R.string.book_source_filter_type_file,
                            iconRes = R.drawable.ic_folder_open,
                            checked = selectedType == BookSourceType.file,
                        ),
                    ),
                )
            )
            if (layoutMode != ExploreLayoutMode.GROUP_GRID) {
                groups.forEachIndexed { index, group ->
                    add(
                        NgExpandableActionMenuItem(
                            itemId = EXPLORE_GROUP_ITEM_ID_BASE + index,
                            titleRes = 0,
                            iconRes = BookSourceGroupIcon.resolve(group),
                            title = group,
                            checked = group == selectedGroup
                        )
                    )
                }
            }
        }
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
            ExploreSourceSwitchButton(
                sourceUrl = currentSourceUrl,
                sourceName = currentSourceName,
                onClick = onOpenSourcePicker,
            )
            Spacer(Modifier.width(spacerWidth))
        }
        val effectiveSearchEnabled = !modernStyle || searchEnabled
        val submitModernSearch: (String) -> Unit = { rawQuery ->
            focusManager.clearFocus()
            keyboardController?.hide()
            if (modernStyle && searchEnabled) {
                onSearch(rawQuery)
            }
        }
        NgSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            hint = when {
                !modernStyle -> stringResource(R.string.screen_find)
                !searchEnabled -> "当前源不支持搜索"
                currentSourceName.isNotBlank() -> "搜索 $currentSourceName"
                else -> stringResource(R.string.search)
            },
            modifier = Modifier.weight(1f),
            enabled = effectiveSearchEnabled,
            searchIcon = painterResource(
                if (modernStyle && !searchEnabled) R.drawable.ic_lock_outline
                else R.drawable.ic_search
            ),
            variant = NgSearchBarVariant.TOOLBAR,
            hideHintOnFocus = true,
            onSearch = submitModernSearch,
            onSearchIconClick = if (modernStyle && searchEnabled) {
                { submitModernSearch(query) }
            } else {
                null
            },
        )
        if (modernStyle) {
            Spacer(Modifier.width(spacerWidth))
            NgSearchBarTranslateButton(
                isTranslating = isTranslating,
                onClick = onTranslateBooks,
                contentDescription = "翻译当前列表书名",
            )
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
            contentDescription = if (modernStyle) "切换为经典发现样式" else "切换为新版发现样式",
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
                defaultExpandedItemIds = if (selectedType != null) {
                    setOf(EXPLORE_TYPE_PARENT_ITEM_ID)
                } else {
                    emptySet()
                },
                modifier = exploreDropdownMenuBounds(),
                variant = NgExpandableActionMenuVariant.SIDE_SLIDE,
                sideSlideEndMargin = 10.dp,
                sideSlideAnchorGap = 8.dp,
                properties = PopupProperties(focusable = true, clippingEnabled = false),
                onItemClick = { item ->
                    if (!menuState.expanded) return@NgExpandableActionMenu
                    menuState.close()
                    when (item.itemId) {
                        EXPLORE_LAYOUT_LIST_ITEM_ID -> {
                            onLayoutModeChange(ExploreLayoutMode.LIST)
                        }

                        EXPLORE_LAYOUT_GRID_ITEM_ID -> {
                            onLayoutModeChange(ExploreLayoutMode.GRID)
                        }

                        EXPLORE_LAYOUT_GROUP_GRID_ITEM_ID -> {
                            onLayoutModeChange(ExploreLayoutMode.GROUP_GRID)
                        }

                        R.id.menu_1 -> onGroupSelected(null)
                        EXPLORE_TYPE_TEXT_ITEM_ID -> onTypeSelected(BookSourceType.default)
                        EXPLORE_TYPE_IMAGE_ITEM_ID -> onTypeSelected(BookSourceType.image)
                        EXPLORE_TYPE_AUDIO_ITEM_ID -> onTypeSelected(BookSourceType.audio)
                        EXPLORE_TYPE_VIDEO_ITEM_ID -> onTypeSelected(BookSourceType.video)
                        EXPLORE_TYPE_FILE_ITEM_ID -> onTypeSelected(BookSourceType.file)
                        R.id.menu_source_manage -> onManageSources()
                        else -> {
                            val index = item.itemId - EXPLORE_GROUP_ITEM_ID_BASE
                            onGroupSelected(groups.getOrNull(index))
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun ExploreSourceSwitchButton(
    sourceUrl: String?,
    sourceName: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    if (sourceUrl != null && sourceName.isNotBlank()) {
        val tileColorInt = remember(sourceUrl) { sourceTileColor(sourceUrl) }
        val tileContentColor = remember(tileColorInt) { sourceTileContentColor(tileColorInt) }
        val glyph = remember(sourceName) {
            sourceName.trim().firstDisplayGrapheme().uppercase().ifEmpty { "源" }
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
            contentDescription = "切换书源",
            icon = painterResource(R.drawable.ic_cfg_source),
        )
    }
}
