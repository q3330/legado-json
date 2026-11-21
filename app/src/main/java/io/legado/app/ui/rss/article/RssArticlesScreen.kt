package io.legado.app.ui.rss.article

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.data.entities.RssArticle
import io.legado.app.ui.book.explore.ExploreCategoryBubbleShape
import io.legado.app.ui.book.explore.ExploreCategoryGlyph
import io.legado.app.ui.book.explore.ExploreLayoutButton
import io.legado.app.ui.book.explore.ExploreLayoutModePopupButton
import io.legado.app.ui.book.explore.ExplorePageButton
import io.legado.app.ui.book.explore.ExplorePageJumpDialog
import io.legado.app.ui.book.explore.ExploreShowLayoutMode
import io.legado.app.ui.book.explore.ExploreStatusRow
import io.legado.app.ui.book.explore.ExploreToolbarButton
import io.legado.app.ui.design.components.compose.NgExpandableActionMenu
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuItem
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuVariant
import io.legado.app.ui.design.components.compose.NgFloatingToolbarBackButton
import io.legado.app.ui.design.components.compose.NgGlassDefaults
import io.legado.app.ui.design.components.compose.NgGlassSurface
import io.legado.app.ui.design.components.compose.NgMaterialRole
import io.legado.app.ui.design.components.compose.NgPopupToggleState
import io.legado.app.ui.design.components.compose.NgPullRefreshBox
import io.legado.app.ui.design.components.compose.NgSearchBar
import io.legado.app.ui.design.components.compose.NgSearchBarVariant
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.ui.main.explore.sourceTileColor
import io.legado.app.ui.main.explore.sourceTileContentColor
import io.legado.app.ui.rss.RssEmptyState
import io.legado.app.ui.rss.RssRemoteImage

internal fun rssArticleStyleToLayoutMode(articleStyle: Int): ExploreShowLayoutMode =
    when (articleStyle) {
        2, 3 -> ExploreShowLayoutMode.GRID_2
        4 -> ExploreShowLayoutMode.GRID
        else -> ExploreShowLayoutMode.LIST
    }

internal fun layoutModeToRssArticleStyle(mode: ExploreShowLayoutMode): Int =
    when (mode) {
        ExploreShowLayoutMode.LIST -> 0
        ExploreShowLayoutMode.GRID_2 -> 2
        ExploreShowLayoutMode.GRID -> 4
    }

@Composable
internal fun RssArticlesScreen(
    title: String,
    sorts: List<Pair<String, String>>,
    selectedSort: Int,
    articles: List<RssArticle>,
    articleStyle: Int,
    currentPage: Int = 1,
    refreshing: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    loadError: String?,
    searchVisible: Boolean,
    searchQuery: String,
    searchEnabled: Boolean,
    loginVisible: Boolean,
    onBack: () -> Unit,
    onSortSelected: (Int) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onJumpToPage: (Int) -> Unit = {},
    onSelectLayoutStyle: ((Int) -> Unit)? = null,
    onRetry: () -> Unit,
    onOpenArticle: (RssArticle) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onAction: (Int) -> Unit
) {
    var categoriesExpanded by rememberSaveable(title) { mutableStateOf(false) }
    var categoryAnchorX by remember { mutableStateOf<Float?>(null) }

    val menuItems = remember(loginVisible) {
        buildList {
            if (loginVisible) {
                add(
                    NgExpandableActionMenuItem(
                        itemId = R.id.menu_login,
                        titleRes = R.string.login,
                        iconRes = R.drawable.ic_lock_outline
                    )
                )
            }
            add(
                NgExpandableActionMenuItem(
                    itemId = R.id.menu_refresh_sort,
                    titleRes = R.string.refresh_sort,
                    iconRes = R.drawable.ic_refresh_black_24dp
                )
            )
            add(
                NgExpandableActionMenuItem(
                    itemId = R.id.menu_set_source_variable,
                    titleRes = R.string.set_source_variable,
                    iconRes = R.drawable.ic_code
                )
            )
            add(
                NgExpandableActionMenuItem(
                    itemId = R.id.menu_edit_source,
                    titleRes = R.string.edit_source,
                    iconRes = R.drawable.ic_edit
                )
            )
            add(
                NgExpandableActionMenuItem(
                    itemId = R.id.menu_switch_layout,
                    titleRes = R.string.switchLayout,
                    iconRes = R.drawable.ic_grid_menu
                )
            )
            add(
                NgExpandableActionMenuItem(
                    itemId = R.id.menu_read_record,
                    titleRes = R.string.read_record,
                    iconRes = R.drawable.ic_history
                )
            )
            add(
                NgExpandableActionMenuItem(
                    itemId = R.id.menu_clear,
                    titleRes = R.string.clear,
                    iconRes = R.drawable.ic_baseline_close,
                    dividerBefore = true
                )
            )
        }
    }

    NgPullRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        showIndicator = false,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            RssArticlesTopBar(
                title = title,
                isLoading = refreshing,
                searchEnabled = searchEnabled,
                searchVisible = searchVisible,
                searchQuery = searchQuery,
                categoriesExpanded = categoriesExpanded,
                menuItems = menuItems,
                onBack = onBack,
                onSearchQueryChange = onSearchQueryChange,
                onSearch = onSearch,
                onToggleSearch = { onAction(R.id.menu_search) },
                onToggleCategories = { categoriesExpanded = !categoriesExpanded },
                onCategoryAnchorPosition = { categoryAnchorX = it },
                onRefresh = onRefresh,
                onAction = onAction,
            )

            RssShowContentCard(
                sourceKey = title,
                sorts = sorts,
                selectedSort = selectedSort,
                articles = articles,
                articleStyle = articleStyle,
                currentPage = currentPage,
                refreshing = refreshing,
                loadingMore = loadingMore,
                hasMore = hasMore,
                loadError = loadError,
                categoriesExpanded = categoriesExpanded,
                onCategoriesExpandedChange = { categoriesExpanded = it },
                categoryAnchorX = categoryAnchorX,
                onSortSelected = onSortSelected,
                onLoadMore = onLoadMore,
                onJumpToPage = onJumpToPage,
                onRetry = onRetry,
                onOpenArticle = onOpenArticle,
                onSelectLayoutStyle = onSelectLayoutStyle ?: { _ ->
                    onAction(R.id.menu_switch_layout)
                },
                modifier = Modifier.weight(1f),
                useNavigationBarsPadding = true,
            )
        }
    }
}

@Composable
private fun RssArticlesTopBar(
    title: String,
    isLoading: Boolean,
    searchEnabled: Boolean,
    searchVisible: Boolean,
    searchQuery: String,
    categoriesExpanded: Boolean,
    menuItems: List<NgExpandableActionMenuItem>,
    onBack: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onToggleCategories: () -> Unit,
    onCategoryAnchorPosition: (Float) -> Unit,
    onRefresh: () -> Unit,
    onAction: (Int) -> Unit,
) {
    val contentColor = colorResource(R.color.ng_search_icon)
    val menuState = remember { NgPopupToggleState() }
    NgGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 4.dp),
        role = NgMaterialRole.CONTROL,
        shape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp),
        style = NgGlassDefaults.bookDetailStyle(
            containerColor = colorResource(R.color.ng_bookshelf_manage_header_surface)
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(start = 0.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NgFloatingToolbarBackButton(onClick = onBack, width = 32.dp)
            if (searchVisible) {
                Spacer(Modifier.width(8.dp))
                NgSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    hint = stringResource(R.string.search),
                    onSearch = onSearch,
                    modifier = Modifier.weight(1f),
                    variant = NgSearchBarVariant.TOOLBAR,
                    containerColor = Color.Transparent,
                    hideHintOnFocus = true,
                )
                Spacer(Modifier.width(4.dp))
            } else {
                Text(
                    text = title,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp, end = 8.dp),
                    color = colorResource(R.color.ng_search_content),
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }
            if (searchEnabled) {
                ExploreToolbarButton(
                    iconRes = R.drawable.ic_search,
                    description = stringResource(R.string.search),
                    tint = if (searchVisible) Color(NgTheme.colors.primary) else contentColor,
                    onClick = onToggleSearch,
                    modifier = Modifier.background(
                        if (searchVisible) Color(NgTheme.colors.primary).copy(alpha = 0.13f)
                        else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    ),
                )
                Spacer(Modifier.width(2.dp))
            }
            ExploreToolbarButton(
                iconRes = R.drawable.ic_grid_menu,
                description = stringResource(
                    if (categoriesExpanded) R.string.explore_hide_categories
                    else R.string.explore_show_categories
                ),
                tint = if (categoriesExpanded) Color(NgTheme.colors.primary) else contentColor,
                onClick = onToggleCategories,
                modifier = Modifier
                    .onGloballyPositioned {
                        onCategoryAnchorPosition(it.boundsInRoot().center.x)
                    }
                    .background(
                        if (categoriesExpanded) Color(NgTheme.colors.primary).copy(alpha = 0.13f)
                        else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    ),
            )
            Spacer(Modifier.width(2.dp))
            if (isLoading) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                }
            } else {
                ExploreToolbarButton(
                    iconRes = R.drawable.ic_refresh_black_24dp,
                    description = stringResource(R.string.refresh),
                    tint = contentColor,
                    onClick = onRefresh,
                )
            }
            Spacer(Modifier.width(2.dp))
            Box {
                ExploreToolbarButton(
                    iconRes = R.drawable.ic_more,
                    description = stringResource(R.string.more),
                    tint = contentColor,
                    onClick = menuState::onAnchorClick,
                )
                NgExpandableActionMenu(
                    expanded = menuState.expanded,
                    onDismissRequest = menuState::onDismissRequest,
                    items = menuItems,
                    variant = NgExpandableActionMenuVariant.SIDE_SLIDE,
                    sideSlideEndMargin = 14.dp,
                    sideSlideAnchorGap = 8.dp,
                    properties = PopupProperties(focusable = true, clippingEnabled = false),
                    onItemClick = { item ->
                        if (!menuState.expanded) return@NgExpandableActionMenu
                        menuState.close()
                        onAction(item.itemId)
                    },
                )
            }
        }
    }
}

@Composable
internal fun RssShowContentCard(
    sourceKey: String,
    sorts: List<Pair<String, String>>,
    selectedSort: Int,
    articles: List<RssArticle>,
    articleStyle: Int,
    currentPage: Int = 1,
    refreshing: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    loadError: String?,
    categoriesExpanded: Boolean,
    onCategoriesExpandedChange: (Boolean) -> Unit,
    categoryAnchorX: Float?,
    onSortSelected: (Int) -> Unit,
    onRefresh: (() -> Unit)? = null,
    onLoadMore: () -> Unit,
    onJumpToPage: (Int) -> Unit = {},
    onRetry: () -> Unit,
    onOpenArticle: (RssArticle) -> Unit,
    onSelectLayoutStyle: ((Int) -> Unit)? = null,
    onOpenFullscreen: (() -> Unit)? = null,
    customContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    cardBottomPadding: Dp = 8.dp,
    bottomContentPadding: Dp = 24.dp,
    useNavigationBarsPadding: Boolean = true,
) {
    var categoryPanelLeft by remember { mutableStateOf(0f) }
    val categoryCorner = NgTheme.shapes.mediumDp.dp
    val categoryShape = remember(categoryAnchorX, categoryPanelLeft, categoryCorner) {
        ExploreCategoryBubbleShape(categoryCorner, categoryAnchorX?.minus(categoryPanelLeft))
    }
    val categoryVisibility = remember { MutableTransitionState(false) }
    categoryVisibility.targetState = categoriesExpanded
    val categoryPanelVisible = categoryVisibility.currentState || categoryVisibility.targetState
    BackHandler(enabled = categoriesExpanded) { onCategoriesExpandedChange(false) }

    val selectedSortLabel = remember(sorts, selectedSort, sourceKey) {
        sorts.getOrNull(selectedSort)?.first?.takeIf { it.isNotBlank() }
            ?: sourceKey.ifBlank { "全部" }
    }
    val layoutMode = remember(articleStyle) { rssArticleStyleToLayoutMode(articleStyle) }
    val gridState = rememberLazyGridState()
    var pagePickerPage by rememberSaveable { mutableStateOf<Int?>(null) }

    Box(modifier = modifier) {
        NgGlassSurface(
            modifier = Modifier
                .fillMaxSize()
                .then(if (useNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier)
                .padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = cardBottomPadding),
            role = NgMaterialRole.CONTENT,
            liquidCornerRadius = NgTheme.shapes.mediumDp.dp,
            shape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp),
            style = NgGlassDefaults.bookDetailStyle(
                containerColor = colorResource(R.color.ng_surface_card)
            ),
        ) {
            if (categoryPanelVisible) {
                Spacer(Modifier.fillMaxWidth().height(44.5.dp))
            } else {
                RssContentToolbar(
                    selectedSortLabel = selectedSortLabel,
                    page = currentPage.coerceAtLeast(1),
                    layoutMode = layoutMode,
                    isWebMode = customContent != null,
                    navigationAvailable = !refreshing && !loadingMore && !gridState.isScrollInProgress,
                    canGoPrevious = currentPage > 1,
                    canGoNext = hasMore,
                    onToggleCategories = { onCategoriesExpandedChange(!categoriesExpanded) },
                    onSelectPage = { pagePickerPage = currentPage.coerceAtLeast(1) },
                    onPrevious = {
                        if (currentPage > 1) onJumpToPage(currentPage - 1)
                    },
                    onNext = {
                        if (hasMore) onLoadMore()
                    },
                    onRefresh = onRefresh,
                    onOpenFullscreen = onOpenFullscreen,
                    onLayoutModeChange = if (onSelectLayoutStyle != null) {
                        { mode -> onSelectLayoutStyle(layoutModeToRssArticleStyle(mode)) }
                    } else {
                        null
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    thickness = 0.5.dp,
                    color = colorResource(R.color.secondaryText).copy(alpha = 0.12f),
                )
            }

            if (customContent != null) {
                Box(modifier = Modifier.weight(1f)) {
                    customContent()
                }
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    if (articles.isEmpty() && !refreshing) {
                        when {
                            loadingMore -> Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                ExploreStatusRow(
                                    text = stringResource(R.string.is_loading),
                                    loading = true,
                                )
                            }

                            loadError != null -> Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                RssRetryFooter(enabled = true, onRetry = onRetry)
                            }

                            else -> RssEmptyState(stringResource(R.string.empty))
                        }
                    } else {
                        RssArticleCollection(
                            articles = articles,
                            style = articleStyle,
                            hasMore = hasMore,
                            loading = loadingMore,
                            refreshing = refreshing,
                            loadError = loadError,
                            onLoadMore = onLoadMore,
                            onRetry = onRetry,
                            onOpenArticle = onOpenArticle,
                            bottomPadding = bottomContentPadding,
                            gridState = gridState,
                        )
                    }
                }
            }
        }

        if (categoryPanelVisible) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures { onCategoriesExpandedChange(false) }
                    }
            )
        }
        AnimatedVisibility(visibleState = categoryVisibility) {
            NgGlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (useNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier)
                    .padding(start = 14.dp, end = 14.dp, bottom = 4.dp)
                    .onGloballyPositioned { categoryPanelLeft = it.boundsInRoot().left }
                    .pointerInput(Unit) { detectTapGestures { } },
                role = NgMaterialRole.CONTROL,
                liquidCornerRadius = NgTheme.shapes.mediumDp.dp,
                shape = categoryShape,
                contentPadding = PaddingValues(top = 4.dp),
                style = NgGlassDefaults.bookDetailStyle(
                    containerColor = colorResource(R.color.ng_surface_card)
                ),
            ) {
                RssCategoryPanel(
                    sourceKey = sourceKey,
                    sorts = sorts,
                    selectedSort = selectedSort,
                    onSelectSort = { index ->
                        onSortSelected(index)
                        onCategoriesExpandedChange(false)
                    },
                )
            }
        }
    }

    pagePickerPage?.let { activePage ->
        ExplorePageJumpDialog(
            currentPage = activePage,
            onDismiss = { pagePickerPage = null },
            onJump = { targetPage ->
                pagePickerPage = null
                if (targetPage != activePage) {
                    onJumpToPage(targetPage)
                }
            },
        )
    }
}

@Composable
private fun RssContentToolbar(
    selectedSortLabel: String,
    page: Int,
    layoutMode: ExploreShowLayoutMode,
    isWebMode: Boolean,
    navigationAvailable: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onToggleCategories: () -> Unit,
    onSelectPage: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    onOpenFullscreen: (() -> Unit)? = null,
    onLayoutModeChange: ((ExploreShowLayoutMode) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = selectedSortLabel.ifBlank { stringResource(R.string.all) },
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleCategories)
                .padding(vertical = 4.dp),
            color = colorResource(R.color.primaryText),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!isWebMode) {
            ExplorePageButton(
                iconRes = R.drawable.ic_chevron_left_20,
                description = stringResource(R.string.prev_page),
                enabled = navigationAvailable && canGoPrevious,
                onClick = onPrevious,
            )
            Text(
                text = stringResource(R.string.menu_page, page),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = navigationAvailable, onClick = onSelectPage)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                color = colorResource(R.color.secondaryText),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ExplorePageButton(
                iconRes = R.drawable.ic_chevron_right_20,
                description = stringResource(R.string.next_page),
                enabled = navigationAvailable && canGoNext,
                onClick = onNext,
            )
        } else {
            if (onRefresh != null) {
                ExploreLayoutButton(
                    selected = false,
                    iconRes = R.drawable.ic_refresh_black_24dp,
                    description = stringResource(R.string.refresh),
                    onClick = onRefresh,
                )
                Spacer(Modifier.width(2.dp))
            }
            if (onOpenFullscreen != null) {
                ExploreLayoutButton(
                    selected = false,
                    iconRes = R.drawable.ic_web_outline,
                    description = "全屏打开",
                    onClick = onOpenFullscreen,
                )
            }
        }
        if (onLayoutModeChange != null) {
            ExploreLayoutModePopupButton(
                layoutMode = layoutMode,
                onLayoutModeChange = onLayoutModeChange,
            )
        }
    }
}

@Composable
internal fun RssCategoryPanel(
    sourceKey: String,
    sorts: List<Pair<String, String>>,
    selectedSort: Int,
    onSelectSort: (Int) -> Unit,
) {
    val defaultLabel = stringResource(R.string.all)
    val scrollState = rememberScrollState()
    val rows = remember(sorts) { sorts.indices.chunked(4) }
    val primary = Color(NgTheme.colors.primary)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(scrollState)
            .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (rows.isEmpty()) {
            ExploreStatusRow(text = stringResource(R.string.explore_category_empty))
        } else {
            rows.forEach { rowIndices ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowIndices.forEach { index ->
                        val sort = sorts[index]
                        val label = sort.first.trim().ifBlank { defaultLabel }
                        val compactLabel = if (label.length > 4) "${label.take(4)}…" else label
                        val selected = index == selectedSort
                        val tileColorInt = remember(sourceKey, label, index) {
                            sourceTileColor("$sourceKey#$label#$index")
                        }
                        val tileColor = Color(tileColorInt)
                        val tileContentColor = remember(tileColorInt) {
                            sourceTileContentColor(tileColorInt)
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(62.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectSort(index) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            ExploreCategoryGlyph(
                                label = label,
                                background = tileColor,
                                foreground = tileContentColor,
                                size = 36.dp,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = compactLabel,
                                color = if (selected) primary else colorResource(R.color.primaryText),
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                lineHeight = 13.sp,
                            )
                        }
                    }
                    if (rowIndices.size < 4) {
                        Spacer(Modifier.weight((4 - rowIndices.size).toFloat()))
                    }
                }
            }
        }
    }
}

@Composable
internal fun RssArticleCollection(
    articles: List<RssArticle>,
    style: Int,
    hasMore: Boolean,
    loading: Boolean,
    refreshing: Boolean,
    loadError: String?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenArticle: (RssArticle) -> Unit,
    bottomPadding: Dp = 24.dp,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val layoutMode = remember(style) { rssArticleStyleToLayoutMode(style) }
    val columns = layoutMode.columns
    RssGridLoadMoreEffect(
        state = gridState,
        count = articles.size,
        hasMore = hasMore && loadError == null,
        loading = loading || refreshing,
        onLoadMore = onLoadMore,
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = bottomPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(
            if (layoutMode == ExploreShowLayoutMode.LIST) 0.dp else 16.dp
        ),
    ) {
        if (layoutMode == ExploreShowLayoutMode.LIST) {
            itemsIndexed(
                items = articles,
                key = { _, it -> "list_" + it.origin + '\u0000' + it.sort + '\u0000' + it.link },
                span = { _, _ -> GridItemSpan(maxLineSpan) },
            ) { index, article ->
                Column {
                    RssArticleListRow(
                        article = article,
                        style = style,
                        onOpenArticle = onOpenArticle,
                    )
                    if (index < articles.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            thickness = 0.5.dp,
                            color = colorResource(R.color.secondaryText).copy(alpha = 0.12f),
                        )
                    }
                }
            }
        } else {
            items(
                items = articles,
                key = { "grid_" + it.origin + '\u0000' + it.sort + '\u0000' + it.link },
            ) { article ->
                RssArticleGridCard(
                    article = article,
                    columns = columns,
                    onOpenArticle = onOpenArticle,
                )
            }
        }
        if (articles.isNotEmpty()) {
            when {
                loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                    ExploreStatusRow(text = stringResource(R.string.is_loading), loading = true)
                }
                loadError != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                    RssRetryFooter(enabled = !refreshing, onRetry = onRetry)
                }
                !hasMore -> item(span = { GridItemSpan(maxLineSpan) }) {
                    ExploreStatusRow(text = stringResource(R.string.explore_no_more))
                }
            }
        }
    }
}

@Composable
private fun RssArticleListRow(
    article: RssArticle,
    style: Int,
    onOpenArticle: (RssArticle) -> Unit,
) {
    if (style == 1 && !article.image.isNullOrBlank()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onOpenArticle(article) }
                .padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            RssRemoteImage(
                imageUrl = article.image,
                sourceOrigin = article.origin,
                placeholder = R.drawable.image_rss_article,
                contentDescription = article.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.55f)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.height(8.dp))
            ArticleText(article)
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onOpenArticle(article) }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArticleText(article, Modifier.weight(1f))
            if (!article.image.isNullOrBlank()) {
                Spacer(Modifier.width(12.dp))
                RssRemoteImage(
                    imageUrl = article.image,
                    sourceOrigin = article.origin,
                    placeholder = R.drawable.image_rss_article,
                    contentDescription = article.title,
                    modifier = Modifier
                        .size(width = 108.dp, height = 72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}

@Composable
private fun RssArticleGridCard(
    article: RssArticle,
    columns: Int,
    onOpenArticle: (RssArticle) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onOpenArticle(article) },
    ) {
        RssRemoteImage(
            imageUrl = article.image,
            sourceOrigin = article.origin,
            placeholder = R.drawable.image_rss_article,
            contentDescription = article.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (columns == 2) 1.32f else 1.0f)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = article.title,
            color = if (article.read) {
                colorResource(R.color.secondaryText)
            } else {
                colorResource(R.color.primaryText)
            },
            fontSize = 13.sp,
            fontWeight = if (article.read) FontWeight.Normal else FontWeight.Medium,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
        )
        if (!article.pubDate.isNullOrBlank()) {
            Text(
                text = article.pubDate.orEmpty(),
                color = colorResource(R.color.secondaryText),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ArticleText(article: RssArticle, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = article.title,
            color = if (article.read) {
                colorResource(R.color.secondaryText)
            } else {
                colorResource(R.color.primaryText)
            },
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = if (article.read) FontWeight.Normal else FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (!article.pubDate.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = article.pubDate.orEmpty(),
                color = colorResource(R.color.secondaryText),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun RssRetryFooter(enabled: Boolean, onRetry: () -> Unit) {
    TextButton(
        onClick = onRetry,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.load_error_retry),
            color = Color(NgTheme.colors.primary),
            fontSize = 14.sp
        )
    }
}

@Composable
internal fun RssLoadingFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = Color(NgTheme.colors.primary),
            strokeWidth = 2.dp
        )
    }
}

@Composable
private fun RssGridLoadMoreEffect(
    state: LazyGridState,
    count: Int,
    hasMore: Boolean,
    loading: Boolean,
    onLoadMore: () -> Unit
) {
    val nearEnd by remember(state, count) {
        derivedStateOf {
            state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= count - 4 } == true
        }
    }
    LaunchedEffect(nearEnd, count, hasMore, loading) {
        if (nearEnd && count > 0 && hasMore && !loading) onLoadMore()
    }
}
