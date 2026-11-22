package io.legado.app.ui.book.explore

import androidx.compose.animation.core.MutableTransitionState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.ExploreKind.Type
import io.legado.app.help.config.AppConfig
import io.legado.app.help.translate.BookTitleTranslator
import io.legado.app.help.translate.TranslateHistoryStore
import io.legado.app.ui.book.search.SearchBookCover
import io.legado.app.ui.book.search.SearchCoverBitmapCache
import io.legado.app.ui.book.search.SearchResultCard
import io.legado.app.ui.design.components.compose.NgExpandableActionMenu
import io.legado.app.ui.design.components.compose.rememberNgExpandableActionMenuContentWidth
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuItem
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuVariant
import io.legado.app.ui.design.components.compose.NgMenuSelectionStyle
import io.legado.app.ui.design.components.compose.NgPopupToggleState
import io.legado.app.ui.design.components.compose.NgGlassDefaults
import io.legado.app.ui.design.components.compose.NgGlassSurface
import io.legado.app.ui.design.components.compose.NgMaterialRole
import io.legado.app.ui.design.components.compose.NgFloatingToolbarBackButton
import io.legado.app.ui.design.components.compose.NgPullRefreshBox
import io.legado.app.ui.design.components.compose.NgToolbarTranslateButton
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.ui.main.explore.rememberExploreSourceJsExtensions
import io.legado.app.ui.main.explore.exploreDropdownMenuBounds
import io.legado.app.ui.main.explore.ExploreInfoStore
import io.legado.app.ui.main.explore.ExploreKindItem
import io.legado.app.ui.main.explore.rememberExploreKindLabel
import io.legado.app.ui.main.explore.sourceTileColor
import io.legado.app.ui.main.explore.sourceTileContentColor
import io.legado.app.utils.InfoMap
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ExploreCategoryTileHeight = 62.dp
private val ExploreCategoryRowSpacing = 6.dp

@Composable
internal fun ExploreShowScreen(
    state: ExploreShowUiState,
    layoutMode: ExploreShowLayoutMode,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onTranslateBooks: () -> Unit,
    onRefreshKinds: () -> Unit,
    onSelectKind: (ExploreKind) -> Unit,
    onLayoutModeChange: (ExploreShowLayoutMode) -> Unit,
    onSelectPage: (Int) -> Unit,
    onJumpToPage: (Int) -> Unit,
    onLoadNext: () -> Unit,
    onRetryContent: () -> Unit,
    onOpenBook: (SearchBook) -> Unit,
    onShowError: (String) -> Unit,
    onCurrentPageChanged: (Int) -> Unit = {},
) {
    var categoriesExpanded by rememberSaveable(state.source?.bookSourceUrl) { mutableStateOf(false) }
    var categoryAnchorX by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(state.kindsError) {
        if (state.kindsError != null) categoriesExpanded = true
    }

    NgPullRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        enabled = !state.isKindsLoading,
        showIndicator = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ExploreShowTopBar(
                sourceName = state.sourceName,
                isLoading = state.isKindsLoading && !state.isRefreshing,
                isTranslating = state.isTranslating,
                onBack = onBack,
                onTranslateBooks = onTranslateBooks,
                onRefresh = onRefresh,
                categoriesExpanded = categoriesExpanded,
                onToggleCategories = { categoriesExpanded = !categoriesExpanded },
                onCategoryAnchorPosition = { categoryAnchorX = it }
            )

            ExploreShowContentCard(
                state = state,
                layoutMode = layoutMode,
                categoriesExpanded = categoriesExpanded,
                onCategoriesExpandedChange = { categoriesExpanded = it },
                categoryAnchorX = categoryAnchorX,
                onRefreshKinds = onRefreshKinds,
                onSelectKind = onSelectKind,
                onLayoutModeChange = onLayoutModeChange,
                onSelectPage = onSelectPage,
                onJumpToPage = onJumpToPage,
                onLoadNext = onLoadNext,
                onRetryContent = onRetryContent,
                onOpenBook = onOpenBook,
                onShowError = onShowError,
                onCurrentPageChanged = onCurrentPageChanged,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun ExploreShowContentCard(
    state: ExploreShowUiState,
    layoutMode: ExploreShowLayoutMode,
    categoriesExpanded: Boolean,
    onCategoriesExpandedChange: (Boolean) -> Unit,
    categoryAnchorX: Float?,
    onRefreshKinds: () -> Unit,
    onSelectKind: (ExploreKind) -> Unit,
    onLayoutModeChange: (ExploreShowLayoutMode) -> Unit,
    onSelectPage: (Int) -> Unit,
    onJumpToPage: (Int) -> Unit,
    onLoadNext: () -> Unit,
    onRetryContent: () -> Unit,
    onOpenBook: (SearchBook) -> Unit,
    onShowError: (String) -> Unit,
    modifier: Modifier = Modifier,
    cardBottomPadding: Dp = 8.dp,
    bottomContentPadding: Dp = 24.dp,
    useNavigationBarsPadding: Boolean = true,
    onCurrentPageChanged: (Int) -> Unit = {},
) {
    val kindSections = remember(state.kinds) { buildExploreKindSections(state.kinds) }
    val activeSectionIndex = kindSections.sectionIndexFor(state.selectedKind)
    val visibleSections =
        if (kindSections.useTopLevelGroups) {
            kindSections.sections.getOrNull(activeSectionIndex)?.let(::listOf).orEmpty()
        } else {
            kindSections.sections
        }
    val cacheRevision by BookTitleTranslator.cacheRevision.collectAsState()
    val historyBooks by TranslateHistoryStore.booksFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val translatedCategoryLabels = remember(state.source?.bookSourceUrl) {
        mutableStateMapOf<String, String>()
    }
    var isTranslatingCategories by remember(state.source?.bookSourceUrl) { mutableStateOf(false) }
    var categoryTranslateJob by remember(state.source?.bookSourceUrl) { mutableStateOf<Job?>(null) }
    DisposableEffect(state.source?.bookSourceUrl) {
        onDispose {
            categoryTranslateJob?.cancel()
            categoryTranslateJob = null
        }
    }
    val allCategoryLabels = remember(state.kinds, state.selectedKind, kindSections) {
        buildList {
            state.selectedKind?.title?.let(::sanitizeExploreDetailLabel)?.takeIf { it.isNotBlank() }?.let(::add)
            state.kinds.forEach { kind ->
                sanitizeExploreDetailLabel(kind.title).takeIf { it.isNotBlank() }?.let(::add)
            }
            kindSections.sections.forEach { section ->
                section.header?.displaySectionLabel()?.let(::sanitizeExploreDetailLabel)
                    ?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct()
    }
    LaunchedEffect(allCategoryLabels, cacheRevision) {
        allCategoryLabels.forEach { label ->
            val cached = BookTitleTranslator.getCached(label)
            if (cached != null) {
                translatedCategoryLabels[label] = cached
            } else {
                translatedCategoryLabels.remove(label)
            }
        }
    }
    val onTranslateCategories: () -> Unit = {
        if (categoryTranslateJob?.isActive == true) {
            categoryTranslateJob?.cancel()
            categoryTranslateJob = null
            isTranslatingCategories = false
        } else {
            val cachedNow = buildMap {
                allCategoryLabels.forEach { label ->
                    BookTitleTranslator.getCached(label)?.let { put(label, it) }
                }
            }
            if (cachedNow.isNotEmpty()) {
                translatedCategoryLabels.putAll(cachedNow)
            }
            val uncached = allCategoryLabels.filter {
                it !in translatedCategoryLabels && BookTitleTranslator.needsTranslation(it)
            }
            if (uncached.isNotEmpty()) {
                isTranslatingCategories = true
                categoryTranslateJob = scope.launch {
                    try {
                        BookTitleTranslator.withTranslationSession {
                            uncached.chunked(25).forEach { batch ->
                                val result = withContext(IO) { BookTitleTranslator.translateBatch(batch) }
                                if (result.isNotEmpty()) {
                                    translatedCategoryLabels.putAll(result)
                                }
                            }
                        }
                    } finally {
                        isTranslatingCategories = false
                    }
                }
            }
        }
    }
    val translateCategoryLabel: (String) -> String = { raw ->
        val clean = sanitizeExploreDetailLabel(raw)
        if (clean.isNotBlank()) {
            translatedCategoryLabels[clean] ?: BookTitleTranslator.getCached(clean) ?: clean
        } else {
            clean
        }
    }
    var categoryLayoutMode by remember {
        mutableStateOf(ExploreShowLayoutMode.from(AppConfig.exploreCategoryLayoutMode))
    }
    val sectionRows = visibleSections.map { section ->
        section to calculateExploreDetailKindRows(section.items)
    }
    val controlRows = calculateExploreDetailKindRows(kindSections.controls)
    val gridState = rememberLazyGridState()
    val coverBitmapCache = remember { SearchCoverBitmapCache() }
    val currentPage by remember(gridState, state.bookPages, state.firstLoadedPage) {
        derivedStateOf {
            val visibleBooks = gridState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                if (item.index < gridState.firstVisibleItemIndex) return@mapNotNull null
                val key = item.key as? String
                val page = key?.removePrefix("list_")?.removePrefix("grid_")
                    ?.let(state.bookPages::get)
                page?.let { item.offset.y to it }
            }
            val firstRow = visibleBooks.firstOrNull()?.first
            visibleBooks.takeWhile { it.first == firstRow }.maxOfOrNull { it.second }
                ?: state.firstLoadedPage
        }
    }
    val lastBookPage = remember(state.bookPages) { state.bookPages.values.maxOrNull() ?: 0 }
    LaunchedEffect(currentPage) {
        onCurrentPageChanged(currentPage)
    }
    val hasActiveContentSource = state.selectedKind != null || state.isSearchMode
    val navigationAvailable = hasActiveContentSource && !state.isContentLoading &&
            !state.isKindsLoading && !state.isRefreshing
    val navigate: (Int) -> Unit = { page ->
        if (navigationAvailable && !gridState.isScrollInProgress && page > 0) {
            val bookIndex = state.books.indexOfFirst { state.bookPages[it.bookUrl] == page }
            if (bookIndex >= 0) {
                scope.launch {
                    gridState.scrollToItem(bookIndex)
                }
            } else {
                onJumpToPage(page)
            }
        }
    }
    val columns = layoutMode.columns
    var categoryPanelLeft by remember { mutableStateOf(0f) }
    val categoryCorner = NgTheme.shapes.mediumDp.dp
    val categoryShape = remember(categoryAnchorX, categoryPanelLeft, categoryCorner) {
        ExploreCategoryBubbleShape(categoryCorner, categoryAnchorX?.minus(categoryPanelLeft))
    }
    val categoryVisibility = remember { MutableTransitionState(false) }
    categoryVisibility.targetState = categoriesExpanded
    val categoryPanelVisible = categoryVisibility.currentState || categoryVisibility.targetState
    BackHandler(enabled = categoriesExpanded) { onCategoriesExpandedChange(false) }
    val selectKind: (ExploreKind) -> Unit = { kind ->
        onSelectKind(kind)
        onCategoriesExpandedChange(false)
    }
    val shouldLoadNext by remember(gridState, state.books, state.hasMore, state.isContentLoading, state.contentError) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            state.books.isNotEmpty() &&
                    state.hasMore &&
                    !state.isContentLoading && state.contentError == null &&
                    lastVisible >= gridState.layoutInfo.totalItemsCount - 4
        }
    }

    LaunchedEffect(shouldLoadNext) {
        if (shouldLoadNext) onLoadNext()
    }
    LaunchedEffect(state.selectionRevision) {
        if (state.selectionRevision > 0) {
            gridState.scrollToItem(0)
        }
    }

    Box(modifier = modifier) {
        NgGlassSurface(
            modifier = Modifier.fillMaxSize()
                .then(if (useNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier)
                .padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = cardBottomPadding),
            role = NgMaterialRole.CONTENT,
            liquidCornerRadius = NgTheme.shapes.mediumDp.dp,
            shape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp),
            style = NgGlassDefaults.bookDetailStyle(
                containerColor = colorResource(R.color.ng_surface_card)
            )
        ) {
            // Keep the header's space while the category overlay is visible.
            if (categoryPanelVisible) {
                Spacer(Modifier.fillMaxWidth().height(44.5.dp))
            } else {
                ExploreContentToolbar(
                    selectedKindLabel = state.headerLabelOverride ?: state.selectedKind?.title
                        ?.let(translateCategoryLabel)
                        .orEmpty(),
                    page = currentPage,
                    layoutMode = layoutMode,
                    onToggleCategories = { onCategoriesExpandedChange(!categoriesExpanded) },
                    onSelectPage = { onSelectPage(currentPage) },
                    navigationAvailable = navigationAvailable,
                    gridState = gridState,
                    canGoPrevious = currentPage > 1,
                    canGoNext = currentPage < Int.MAX_VALUE &&
                        (state.hasMore || currentPage < lastBookPage),
                    onPrevious = { navigate(currentPage - 1) },
                    onNext = { navigate(currentPage + 1) },
                    onLayoutModeChange = onLayoutModeChange
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    thickness = 0.5.dp,
                    color = colorResource(R.color.secondaryText).copy(alpha = 0.12f)
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    top = 8.dp,
                    end = 8.dp,
                    bottom = bottomContentPadding
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(
                    if (layoutMode == ExploreShowLayoutMode.LIST) 0.dp else 16.dp
                )
            ) {
                if (layoutMode == ExploreShowLayoutMode.LIST) {
                    items(
                        items = state.books,
                        key = { "list_${it.bookUrl}" },
                        span = { GridItemSpan(maxLineSpan) }
                    ) { book ->
                        val displayName = remember(book.bookUrl, book.name, state.translatedNames, cacheRevision, historyBooks) {
                            state.translatedNames[book.name]
                                ?: BookTitleTranslator.resolveBookDisplayName(book.bookUrl, book.name)
                        }
                        val displayBook = if (displayName != book.name) {
                            book.copy(name = displayName)
                        } else {
                            book
                        }
                        Column {
                            SearchResultCard(
                                book = displayBook,
                                inBookshelf = state.isBookInShelf(book),
                                originCount = 0,
                                onClick = { onOpenBook(book) },
                                onLongClick = { onOpenBook(book) },
                                outerHorizontalPadding = 0.dp,
                                outerVerticalPadding = 0.dp,
                                cardCornerRadius = 0.dp,
                                cardContentPadding = 8.dp,
                                cardBackgroundColorRes = android.R.color.transparent,
                                cardBorderWidth = 0.dp,
                                coverBitmapCache = coverBitmapCache,
                            )
                            if (book.bookUrl != state.books.lastOrNull()?.bookUrl) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    thickness = 0.5.dp,
                                    color = colorResource(R.color.secondaryText).copy(alpha = 0.12f)
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = state.books,
                        key = { "grid_${it.bookUrl}" }
                    ) { book ->
                        val displayName = remember(book.bookUrl, book.name, state.translatedNames, cacheRevision, historyBooks) {
                            state.translatedNames[book.name]
                                ?: BookTitleTranslator.resolveBookDisplayName(book.bookUrl, book.name)
                        }
                        ExploreBookGridCard(
                            book = book,
                            displayName = displayName,
                            inBookshelf = state.isBookInShelf(book),
                            coverBitmapCache = coverBitmapCache,
                            columns = columns,
                            onClick = { onOpenBook(book) }
                        )
                    }
                }

                when {
                    state.isContentLoading && !state.isLoadingPrevious -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ExploreStatusRow(text = stringResource(R.string.is_loading), loading = true)
                        }
                    }

                    state.contentError != null -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ExploreContentErrorRow(
                                error = state.contentError,
                                onRetry = onRetryContent,
                                onShowError = onShowError
                            )
                        }
                    }

                    !hasActiveContentSource && !state.isKindsLoading -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ExploreStatusRow(stringResource(R.string.explore_category_empty))
                        }
                    }

                    state.books.isEmpty() && hasActiveContentSource -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ExploreStatusRow(stringResource(R.string.empty))
                        }
                    }

                    !state.hasMore -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ExploreStatusRow(stringResource(R.string.explore_no_more))
                        }
                    }
                }
            }
        }
        if (categoryPanelVisible) {
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                detectTapGestures { onCategoriesExpandedChange(false) }
            })
        }
        androidx.compose.animation.AnimatedVisibility(visibleState = categoryVisibility) {
            NgGlassSurface(
                modifier = Modifier.fillMaxWidth()
                    .then(if (useNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier)
                    .padding(start = 14.dp, end = 14.dp, bottom = cardBottomPadding)
                    .onGloballyPositioned { categoryPanelLeft = it.boundsInRoot().left }
                    .pointerInput(Unit) { detectTapGestures { } },
                role = NgMaterialRole.CONTROL,
                liquidCornerRadius = NgTheme.shapes.mediumDp.dp,
                shape = categoryShape,
                contentPadding = PaddingValues(top = 4.dp),
                style = NgGlassDefaults.bookDetailStyle(
                    containerColor = colorResource(R.color.ng_surface_card)
                )
            ) {
                ExploreCategoryPanel(
                    state = state,
                    kindSections = kindSections,
                    visibleSections = visibleSections,
                    controlRows = controlRows,
                    activeSectionIndex = activeSectionIndex,
                    categoryLayoutMode = categoryLayoutMode,
                    onCategoryLayoutModeChange = { next ->
                        categoryLayoutMode = next
                        AppConfig.exploreCategoryLayoutMode = next.value
                    },
                    isTranslatingCategories = isTranslatingCategories,
                    onTranslateCategories = onTranslateCategories,
                    translateCategoryLabel = translateCategoryLabel,
                    onSelectKind = onSelectKind,
                    onOpenKind = selectKind,
                    onRefreshKinds = onRefreshKinds,
                    onShowError = onShowError
                )
            }
        }
    }
}

@Composable
private fun ExploreCategoryPanel(
    state: ExploreShowUiState,
    kindSections: ExploreKindSections,
    visibleSections: List<ExploreKindSection>,
    controlRows: List<List<Pair<ExploreKind, Int>>>,
    activeSectionIndex: Int,
    categoryLayoutMode: ExploreShowLayoutMode,
    onCategoryLayoutModeChange: (ExploreShowLayoutMode) -> Unit,
    isTranslatingCategories: Boolean,
    onTranslateCategories: () -> Unit,
    translateCategoryLabel: (String) -> String,
    onSelectKind: (ExploreKind) -> Unit,
    onOpenKind: (ExploreKind) -> Unit,
    onRefreshKinds: () -> Unit,
    onShowError: (String) -> Unit
) {
    val fallbackLabel = stringResource(R.string.explore_category_default)
    val ungroupedLabel = stringResource(R.string.no_group)
    val scrollState = rememberScrollState()
    val scrollThumbColor = colorResource(R.color.secondaryText).copy(alpha = 0.32f)
    val infoMap = remember(state.source?.bookSourceUrl) {
        val sourceUrl = state.source?.bookSourceUrl.orEmpty()
        ExploreInfoStore.infoMapList[sourceUrl] ?: InfoMap(sourceUrl).also {
            ExploreInfoStore.infoMapList.put(sourceUrl, it)
        }
    }
    val sourceJsExtensions = rememberExploreSourceJsExtensions(state.source, infoMap, onRefreshKinds)
    LaunchedEffect(activeSectionIndex, state.kinds) {
        scrollState.scrollTo(0)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 6.dp, end = 8.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (kindSections.useTopLevelGroups && kindSections.sections.size > 1) {
                val sectionLabels = kindSections.sections.map { section ->
                    section.header?.let { header ->
                        translateCategoryLabel(header.displaySectionLabel())
                            .ifBlank { fallbackLabel }
                    } ?: ungroupedLabel
                }
                ExploreSectionDropdown(
                    modifier = Modifier.weight(1f),
                    labels = sectionLabels,
                    selectedIndex = activeSectionIndex,
                    onSelectSection = { index ->
                        kindSections.kindForSectionSelection(index, state.selectedKind)
                            ?.takeIf { nextKind -> nextKind != state.selectedKind }
                            ?.let(onSelectKind)
                    }
                )
            } else {
                Text(
                    text = stringResource(R.string.explore_show_categories),
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    color = colorResource(R.color.secondaryText),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            NgToolbarTranslateButton(
                isTranslating = isTranslatingCategories,
                onClick = onTranslateCategories,
                contentDescription = "翻译分类",
                size = 32.dp,
                iconSize = 16.dp,
                cornerRadius = 9.dp,
                idleTint = colorResource(R.color.secondaryText),
                borderColor = colorResource(R.color.primaryText),
            )
            Spacer(Modifier.width(2.dp))
            ExploreLayoutModePopupButton(
                layoutMode = categoryLayoutMode,
                onLayoutModeChange = onCategoryLayoutModeChange,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .drawWithContent {
                    drawContent()
                    if (scrollState.maxValue > 0 && size.height > 0f) {
                        val thumbWidth = 3.dp.toPx()
                        val thumbHeight = 40.dp.toPx().coerceAtMost(size.height)
                        val progress = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
                        val offsetY = (size.height - thumbHeight) * progress
                        drawRoundRect(
                            color = scrollThumbColor,
                            topLeft = Offset(size.width - thumbWidth, offsetY),
                            size = Size(thumbWidth, thumbHeight),
                            cornerRadius = CornerRadius(thumbWidth / 2f)
                        )
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(ExploreCategoryRowSpacing)
            ) {
                controlRows.forEach { row ->
                    ExploreCategoryRow(
                        row = row,
                        source = state.source,
                        selectedKind = state.selectedKind,
                        translateCategoryLabel = translateCategoryLabel,
                        onSelectKind = onOpenKind,
                        onRefreshKinds = onRefreshKinds,
                        onShowError = onShowError
                    )
                }

                val columns = categoryLayoutMode.columns
                val isList = categoryLayoutMode == ExploreShowLayoutMode.LIST
                visibleSections.forEach { section ->
                    val urlKinds = section.items.filter { it.type == Type.url && !it.url.isNullOrBlank() }
                    val nonUrlKinds = section.items.filterNot { it.type == Type.url && !it.url.isNullOrBlank() }
                    if (nonUrlKinds.isNotEmpty()) {
                        calculateExploreDetailKindRows(nonUrlKinds).forEach { row ->
                            ExploreCategoryRow(
                                row = row,
                                source = state.source,
                                selectedKind = state.selectedKind,
                                translateCategoryLabel = translateCategoryLabel,
                                onSelectKind = onOpenKind,
                                onRefreshKinds = onRefreshKinds,
                                onShowError = onShowError,
                            )
                        }
                    }
                    if (isList || columns == 1) {
                        urlKinds.forEach { kind ->
                            ExploreCategoryTile(
                                kind = kind,
                                source = state.source,
                                infoMap = infoMap,
                                selected = kind == state.selectedKind,
                                wide = true,
                                columns = 1,
                                translateCategoryLabel = translateCategoryLabel,
                                onClick = { onOpenKind(kind) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        urlKinds.chunked(columns).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                rowItems.forEach { kind ->
                                    ExploreCategoryTile(
                                        kind = kind,
                                        source = state.source,
                                        infoMap = infoMap,
                                        selected = kind == state.selectedKind,
                                        wide = columns <= 2,
                                        columns = columns,
                                        translateCategoryLabel = translateCategoryLabel,
                                        onClick = { onOpenKind(kind) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (rowItems.size < columns) {
                                    Spacer(Modifier.weight((columns - rowItems.size).toFloat()))
                                }
                            }
                        }
                    }
                }

                if (state.isKindsLoading && state.kinds.isEmpty()) {
                    ExploreStatusRow(text = stringResource(R.string.is_loading), loading = true)
                }

                state.kindsError?.let { error ->
                    ExploreErrorRow(error = error, onShowError = onShowError)
                }
            }

        }
    }
}

private fun ExploreShowUiState.isBookInShelf(book: SearchBook): Boolean {
    return "${book.name}-${book.author}" in bookshelfKeys ||
            book.name in bookshelfKeys ||
            book.bookUrl in bookshelfKeys
}

@Composable
private fun ExploreShowTopBar(
    sourceName: String,
    isLoading: Boolean,
    isTranslating: Boolean,
    onBack: () -> Unit,
    onTranslateBooks: () -> Unit,
    onRefresh: () -> Unit,
    categoriesExpanded: Boolean,
    onToggleCategories: () -> Unit,
    onCategoryAnchorPosition: (Float) -> Unit
) {
    val contentColor = colorResource(R.color.ng_search_icon)
    // Keep the same geometry and material as BookSourceManageTopBar.
    NgGlassSurface(
        modifier = Modifier.fillMaxWidth()
            .padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 4.dp),
        role = NgMaterialRole.CONTROL,
        shape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp),
        style = NgGlassDefaults.bookDetailStyle(
            containerColor = colorResource(R.color.ng_bookshelf_manage_header_surface)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp)
                .padding(start = 0.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NgFloatingToolbarBackButton(onClick = onBack, width = 32.dp)
            Text(
                text = sourceName,
                modifier = Modifier.weight(1f).padding(start = 14.dp, end = 8.dp),
                color = colorResource(R.color.ng_search_content),
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start
            )
            NgToolbarTranslateButton(
                isTranslating = isTranslating,
                onClick = onTranslateBooks,
                contentDescription = "翻译当前列表书名",
                idleTint = contentColor,
                borderColor = colorResource(R.color.primaryText),
            )
            Spacer(Modifier.width(2.dp))
            ExploreToolbarButton(
                iconRes = R.drawable.ic_grid_menu,
                description = stringResource(if (categoriesExpanded)
                    R.string.explore_hide_categories else R.string.explore_show_categories),
                tint = if (categoriesExpanded) Color(NgTheme.colors.primary) else contentColor,
                onClick = onToggleCategories,
                modifier = Modifier.onGloballyPositioned {
                    onCategoryAnchorPosition(it.boundsInRoot().center.x)
                }.background(
                    if (categoriesExpanded) Color(NgTheme.colors.primary).copy(alpha = 0.13f)
                    else Color.Transparent, RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(2.dp))
            if (isLoading) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = contentColor)
                }
            } else {
                ExploreToolbarButton(
                    iconRes = R.drawable.ic_refresh_black_24dp,
                    description = stringResource(R.string.refresh_sort),
                    tint = contentColor,
                    onClick = onRefresh
                )
            }
        }
    }
}

@Composable
internal fun ExploreToolbarButton(
    iconRes: Int,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = tint
        )
    }
}

@Composable
private fun ExploreSectionDropdown(
    labels: List<String>,
    selectedIndex: Int,
    onSelectSection: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val menuState = remember { NgPopupToggleState() }
    val baseItems = remember(labels) {
        labels.mapIndexed { index, label ->
            NgExpandableActionMenuItem(
                itemId = index,
                titleRes = R.string.explore_category_default,
                iconRes = 0,
                title = label,
                selectionStyle = NgMenuSelectionStyle.TEXT_ONLY
            )
        }
    }
    val items = remember(baseItems, selectedIndex) {
        baseItems.map { it.copy(checked = it.itemId == selectedIndex) }
    }
    // Measure all labels without selection state, so switching groups never resizes the menu.
    val preferredMenuWidth = rememberNgExpandableActionMenuContentWidth(baseItems)
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box {
            Row(
                modifier = Modifier.widthIn(max = 200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = menuState::onAnchorClick)
                    .padding(horizontal = 8.dp)
                    .height(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = labels.getOrNull(selectedIndex).orEmpty(),
                    modifier = Modifier.weight(1f, fill = false),
                    color = Color(NgTheme.colors.primary),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = stringResource(R.string.group),
                    tint = Color(NgTheme.colors.primary),
                    modifier = Modifier.size(18.dp)
                )
            }
            NgExpandableActionMenu(
                expanded = menuState.expanded,
                onDismissRequest = menuState::onDismissRequest,
                items = items,
                modifier = exploreDropdownMenuBounds(),
                width = preferredMenuWidth,
                onItemClick = { item ->
                    menuState.close()
                    onSelectSection(item.itemId)
                }
            )
        }
    }
}

@Composable
private fun ExploreCategoryRow(
    row: List<Pair<ExploreKind, Int>>,
    source: BookSource?,
    selectedKind: ExploreKind?,
    translateCategoryLabel: (String) -> String = ::sanitizeExploreDetailLabel,
    onSelectKind: (ExploreKind) -> Unit,
    onRefreshKinds: () -> Unit,
    onShowError: (String) -> Unit
) {
    val fallbackLabel = stringResource(R.string.explore_category_default)
    val infoMap = remember(source?.bookSourceUrl) {
        val sourceUrl = source?.bookSourceUrl.orEmpty()
        ExploreInfoStore.infoMapList[sourceUrl] ?: InfoMap(sourceUrl).also {
            ExploreInfoStore.infoMapList.put(sourceUrl, it)
        }
    }
    val sourceJsExtensions = rememberExploreSourceJsExtensions(source, infoMap, onRefreshKinds)
    val usedSpan = row.sumOf { it.second }.coerceAtMost(EXPLORE_DETAIL_MAX_SPAN)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        row.forEach { (kind, span) ->
            if (kind.type == Type.url && !kind.url.isNullOrBlank()) {
                ExploreCategoryTile(
                    kind = kind,
                    source = source,
                    infoMap = infoMap,
                    selected = kind == selectedKind,
                    wide = span >= EXPLORE_DETAIL_MAX_SPAN,
                    translateCategoryLabel = translateCategoryLabel,
                    onClick = { onSelectKind(kind) },
                    modifier = Modifier.weight(span.toFloat())
                )
            } else {
                ExploreKindItem(
                    kind = kind,
                    source = source,
                    infoMap = infoMap,
                    sourceJsExtensions = sourceJsExtensions,
                    onOpenKind = onSelectKind,
                    onShowError = onShowError,
                    displayLabelTransform = { label ->
                        translateCategoryLabel(label).ifBlank { fallbackLabel }
                    },
                    modifier = Modifier.weight(span.toFloat())
                )
            }
        }
        if (usedSpan < EXPLORE_DETAIL_MAX_SPAN) {
            Spacer(Modifier.weight((EXPLORE_DETAIL_MAX_SPAN - usedSpan).toFloat()))
        }
    }
}

@Composable
private fun ExploreCategoryTile(
    kind: ExploreKind,
    source: BookSource?,
    infoMap: InfoMap,
    selected: Boolean,
    wide: Boolean,
    columns: Int = 4,
    translateCategoryLabel: (String) -> String = ::sanitizeExploreDetailLabel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sourceLabel by rememberExploreKindLabel(kind, source, infoMap)
    val fallbackLabel = stringResource(R.string.explore_category_default)
    val label = translateCategoryLabel(sourceLabel).ifBlank { fallbackLabel }
    val primary = Color(NgTheme.colors.primary)
    val tileColorInt = remember(source?.bookSourceUrl, kind.title) {
        sourceTileColor("${source?.bookSourceUrl}#${kind.title}")
    }
    val tileColor = Color(tileColorInt)
    val tileContentColor = remember(tileColorInt) { sourceTileContentColor(tileColorInt) }
    if (wide) {
        Row(
            modifier = modifier
                .height(if (columns == 1) 48.dp else 52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) primary.copy(alpha = 0.10f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = if (columns == 1) 10.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExploreCategoryGlyph(label, tileColor, tileContentColor, if (columns == 1) 32.dp else 30.dp)
            Text(
                text = label,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 4.dp),
                color = if (selected) primary else colorResource(R.color.primaryText),
                fontSize = if (columns == 1) 14.sp else 13.sp,
                lineHeight = 17.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        Column(
            modifier = modifier
                .height(ExploreCategoryTileHeight + 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) primary.copy(alpha = 0.10f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ExploreCategoryGlyph(label, tileColor, tileContentColor, if (columns <= 3) 34.dp else 32.dp)
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                color = if (selected) primary else colorResource(R.color.primaryText),
                fontSize = if (columns <= 3) 12.sp else 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
internal fun ExploreCategoryGlyph(
    label: String,
    background: Color,
    foreground: Color,
    size: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.firstOrNull()?.toString()?.uppercase() ?: "源",
            color = foreground,
            fontSize = when {
                size >= 48.dp -> 23.sp
                size >= 40.dp -> 20.sp
                else -> 18.sp
            },
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
internal fun ExploreContentToolbar(
    selectedKindLabel: String,
    page: Int = 1,
    layoutMode: ExploreShowLayoutMode,
    onToggleCategories: (() -> Unit)? = null,
    onSelectPage: () -> Unit = {},
    navigationAvailable: Boolean = false,
    gridState: LazyGridState? = null,
    canGoPrevious: Boolean = false,
    canGoNext: Boolean = false,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onLayoutModeChange: (ExploreShowLayoutMode) -> Unit,
    showPagination: Boolean = true,
    trailingSummary: String? = null,
) {
    val navigationEnabled = navigationAvailable && gridState?.isScrollInProgress != true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = selectedKindLabel.ifBlank {
                stringResource(R.string.explore_category_empty)
            },
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (onToggleCategories != null) {
                        Modifier.clickable(onClick = onToggleCategories)
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 4.dp),
            color = colorResource(R.color.primaryText),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (showPagination) {
            ExplorePageButton(
                iconRes = R.drawable.ic_chevron_left_20,
                description = stringResource(R.string.prev_page),
                enabled = navigationEnabled && canGoPrevious,
                onClick = onPrevious
            )
            Text(
                text = stringResource(R.string.menu_page, page),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = navigationEnabled, onClick = onSelectPage)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                color = colorResource(R.color.secondaryText),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ExplorePageButton(
                iconRes = R.drawable.ic_chevron_right_20,
                description = stringResource(R.string.next_page),
                enabled = navigationEnabled && canGoNext,
                onClick = onNext
            )
        } else if (!trailingSummary.isNullOrBlank()) {
            Text(
                text = trailingSummary,
                modifier = Modifier.padding(horizontal = 6.dp),
                color = colorResource(R.color.secondaryText),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        ExploreLayoutModePopupButton(
            layoutMode = layoutMode,
            onLayoutModeChange = onLayoutModeChange,
        )
    }
}

@Composable
internal fun ExploreLayoutModePopupButton(
    layoutMode: ExploreShowLayoutMode,
    onLayoutModeChange: (ExploreShowLayoutMode) -> Unit,
) {
    val menuState = remember { NgPopupToggleState() }
    var lastGridColumns by remember {
        mutableIntStateOf(if (layoutMode.isGrid) layoutMode.columns else 3)
    }
    LaunchedEffect(layoutMode) {
        if (layoutMode.isGrid) lastGridColumns = layoutMode.columns
    }
    val currentIconRes = when {
        !layoutMode.isGrid -> R.drawable.ic_chapter_list
        layoutMode.columns <= 2 -> R.drawable.ic_grid_menu
        else -> R.drawable.ic_view_quilt
    }
    val primary = Color(NgTheme.colors.primary)
    val primaryText = colorResource(R.color.primaryText)
    val secondaryText = colorResource(R.color.secondaryText)

    Box {
        ExploreLayoutButton(
            selected = menuState.expanded,
            iconRes = currentIconRes,
            description = stringResource(R.string.switchLayout),
            onClick = menuState::onAnchorClick,
        )
        if (menuState.expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, 96),
                onDismissRequest = menuState::onDismissRequest,
                properties = PopupProperties(focusable = true, clippingEnabled = false),
            ) {
                NgGlassSurface(
                    modifier = Modifier.width(214.dp),
                    role = NgMaterialRole.CONTROL,
                    shape = RoundedCornerShape(14.dp),
                    style = NgGlassDefaults.bookDetailStyle(
                        containerColor = colorResource(R.color.ng_surface_card)
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val isList = layoutMode == ExploreShowLayoutMode.LIST
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isList) primary.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    menuState.close()
                                    onLayoutModeChange(ExploreShowLayoutMode.LIST)
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chapter_list),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (isList) primary else secondaryText,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "列表视图",
                                modifier = Modifier.weight(1f),
                                color = if (isList) primary else primaryText,
                                fontSize = 14.sp,
                                fontWeight = if (isList) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (isList) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = primary,
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            thickness = 0.5.dp,
                            color = secondaryText.copy(alpha = 0.14f),
                        )

                        val isGrid = layoutMode.isGrid
                        val activeCols = if (isGrid) layoutMode.columns else lastGridColumns
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isGrid) primary.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    onLayoutModeChange(ExploreShowLayoutMode.gridForColumns(activeCols))
                                }
                                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_grid_menu),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (isGrid) primary else secondaryText,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "网格",
                                modifier = Modifier.weight(1f),
                                color = if (isGrid) primary else primaryText,
                                fontSize = 14.sp,
                                fontWeight = if (isGrid) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            val canMinus = !isGrid || activeCols > 1
                            val canPlus = !isGrid || activeCols < 4
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(primary.copy(alpha = if (canMinus) 0.10f else 0.04f))
                                    .clickable(enabled = canMinus) {
                                        val nextCols = if (isGrid) (activeCols - 1).coerceIn(1, 4) else activeCols
                                        lastGridColumns = nextCols
                                        onLayoutModeChange(ExploreShowLayoutMode.gridForColumns(nextCols))
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "−",
                                    color = if (canMinus) primary else secondaryText.copy(alpha = 0.35f),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                text = "${activeCols}列",
                                modifier = Modifier.width(34.dp),
                                color = if (isGrid) primary else primaryText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            )
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(primary.copy(alpha = if (canPlus) 0.10f else 0.04f))
                                    .clickable(enabled = canPlus) {
                                        val nextCols = if (isGrid) (activeCols + 1).coerceIn(1, 4) else activeCols
                                        lastGridColumns = nextCols
                                        onLayoutModeChange(ExploreShowLayoutMode.gridForColumns(nextCols))
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "+",
                                    color = if (canPlus) primary else secondaryText.copy(alpha = 0.35f),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExplorePageButton(
    iconRes: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(width = 36.dp, height = 36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = colorResource(R.color.primaryText).copy(alpha = if (enabled) 1f else 0.3f)
        )
    }
}

@Composable
internal fun ExploreLayoutButton(
    selected: Boolean,
    iconRes: Int,
    description: String,
    onClick: () -> Unit
) {
    val primary = Color(NgTheme.colors.primary)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (selected) primary.copy(alpha = 0.13f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = description,
                modifier = Modifier.size(16.dp),
                tint = if (selected) primary else colorResource(R.color.secondaryText)
            )
        }
    }
}

@Composable
private fun ExploreBookGridCard(
    book: SearchBook,
    displayName: String,
    inBookshelf: Boolean,
    coverBitmapCache: SearchCoverBitmapCache,
    columns: Int = 3,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
    ) {
        Box {
            SearchBookCover(
                book = book,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.74f),
                coverWidth = when (columns) {
                    1 -> 220.dp
                    2 -> 156.dp
                    4 -> 84.dp
                    else -> 108.dp
                },
                coverHeight = when (columns) {
                    1 -> 296.dp
                    2 -> 210.dp
                    4 -> 114.dp
                    else -> 146.dp
                },
                bitmapCache = coverBitmapCache,
            )
            if (inBookshelf) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(9.dp)
                        .background(colorResource(R.color.md_green_600), CircleShape)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = displayName,
            color = colorResource(R.color.primaryText),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp
        )
        Text(
            text = book.author,
            color = colorResource(R.color.secondaryText),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ExploreStatusRow(text: String, loading: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text = text, color = colorResource(R.color.secondaryText), fontSize = 14.sp)
    }
}

@Composable
internal fun ExploreErrorRow(error: String, onShowError: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colorResource(R.color.ng_surface_card).copy(alpha = 0.76f))
            .clickable { onShowError(error) }
            .padding(14.dp)
    ) {
        Text(
            text = stringResource(R.string.load_error_retry),
            color = colorResource(R.color.primaryText),
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ExploreContentErrorRow(
    error: String,
    onRetry: () -> Unit,
    onShowError: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colorResource(R.color.ng_surface_card).copy(alpha = 0.76f))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.load_error_retry),
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onRetry)
                .padding(10.dp),
            color = colorResource(R.color.primaryText),
            fontSize = 14.sp
        )
        Text(
            text = stringResource(R.string.explore_error_details),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onShowError(error) }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            color = Color(NgTheme.colors.primary),
            fontSize = 13.sp
        )
    }
}
