package io.legado.app.ui.main.explore

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.source.BookSourceReachability
import io.legado.app.help.source.BookSourceReachabilityStore
import io.legado.app.ui.book.explore.ExploreCategoryGlyph
import io.legado.app.ui.book.explore.ExplorePageJumpDialog
import io.legado.app.ui.book.explore.ExploreShowContentCard
import io.legado.app.ui.book.explore.ExploreShowLayoutMode
import io.legado.app.ui.book.explore.ExploreShowUiState
import io.legado.app.ui.design.components.compose.NgGlassDefaults
import io.legado.app.ui.design.components.compose.NgGlassSurface
import io.legado.app.ui.design.components.compose.NgMaterialRole
import io.legado.app.ui.design.components.compose.NgPullRefreshBox
import io.legado.app.ui.design.components.compose.NgSearchBar
import io.legado.app.ui.design.components.compose.NgSearchBarVariant
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.utils.firstDisplayGrapheme

internal data class ExploreModernUiState(
    val selectedSourceUrl: String? = null,
    val source: BookSource? = null,
    val sourceName: String = "",
    val kinds: List<ExploreKind> = emptyList(),
    val selectedKind: ExploreKind? = null,
    val books: List<SearchBook> = emptyList(),
    val bookPages: Map<String, Int> = emptyMap(),
    val firstLoadedPage: Int = 1,
    val lastLoadedPage: Int = 0,
    val selectionRevision: Long = 0L,
    val isTranslating: Boolean = false,
    val translatedNames: Map<String, String> = emptyMap(),
    val bookshelfKeys: Set<String> = emptySet(),
    val isKindsLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isContentLoading: Boolean = false,
    val isLoadingPrevious: Boolean = false,
    val hasMore: Boolean = true,
    val kindsError: String? = null,
    val contentError: String? = null,
    val layoutMode: ExploreShowLayoutMode = ExploreShowLayoutMode.LIST,
    val canSearch: Boolean = false,
    val searchInput: String = "",
    val activeSearchQuery: String = "",
    val isSearchMode: Boolean = false,
)

@Composable
internal fun ExploreModernView(
    sources: List<BookSourcePart>,
    state: ExploreModernUiState,
    bottomInset: Dp,
    categoriesExpanded: Boolean,
    onCategoriesExpandedChange: (Boolean) -> Unit,
    categoryAnchorX: Float?,
    sourcePickerVisible: Boolean,
    onDismissSourcePicker: () -> Unit,
    onSelectSource: (BookSourcePart) -> Unit,
    onSelectKind: (ExploreKind) -> Unit,
    onRefresh: () -> Unit,
    onRefreshKinds: () -> Unit,
    onLoadNext: () -> Unit,
    onJumpToPage: (Int) -> Unit,
    onRetryContent: () -> Unit,
    onLayoutModeChange: (ExploreShowLayoutMode) -> Unit,
    onOpenBook: (SearchBook) -> Unit,
    onShowError: (String) -> Unit,
    onSourceAction: (BookSourcePart, Int) -> Unit,
    onCurrentPageChanged: (Int) -> Unit = {},
) {
    var pagePickerPage by rememberSaveable { mutableStateOf<Int?>(null) }
    val searchHeaderLabel = stringResource(R.string.search)
    val showUiState = remember(state, searchHeaderLabel) {
        ExploreShowUiState(
            source = state.source,
            sourceName = state.sourceName,
            kinds = state.kinds,
            selectedKind = state.selectedKind,
            books = state.books,
            translatedNames = state.translatedNames,
            isTranslating = state.isTranslating,
            bookPages = state.bookPages,
            bookshelfKeys = state.bookshelfKeys,
            isKindsLoading = state.isKindsLoading,
            isRefreshing = state.isRefreshing,
            isContentLoading = state.isContentLoading,
            isLoadingPrevious = state.isLoadingPrevious,
            kindsError = state.kindsError,
            contentError = state.contentError,
            firstLoadedPage = state.firstLoadedPage,
            lastLoadedPage = state.lastLoadedPage,
            hasMore = state.hasMore,
            selectionRevision = state.selectionRevision,
            isSearchMode = state.isSearchMode,
            headerLabelOverride = if (state.isSearchMode) searchHeaderLabel else null,
        )
    }

    LaunchedEffect(state.kindsError) {
        if (state.kindsError != null) {
            onCategoriesExpandedChange(true)
        }
    }

    NgPullRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        enabled = !state.isKindsLoading,
        showIndicator = false,
    ) {
        ExploreShowContentCard(
            state = showUiState,
            layoutMode = state.layoutMode,
            categoriesExpanded = categoriesExpanded,
            onCategoriesExpandedChange = onCategoriesExpandedChange,
            categoryAnchorX = categoryAnchorX,
            onRefreshKinds = onRefreshKinds,
            onSelectKind = onSelectKind,
            onLayoutModeChange = onLayoutModeChange,
            onSelectPage = { pagePickerPage = it },
            onJumpToPage = onJumpToPage,
            onLoadNext = onLoadNext,
            onRetryContent = onRetryContent,
            onOpenBook = onOpenBook,
            onShowError = onShowError,
            onCurrentPageChanged = onCurrentPageChanged,
            modifier = Modifier.fillMaxSize(),
            cardBottomPadding = (bottomInset + 4.dp).coerceAtLeast(8.dp),
            bottomContentPadding = 16.dp,
            useNavigationBarsPadding = false,
        )
    }

    pagePickerPage?.let { currentPage ->
        ExplorePageJumpDialog(
            currentPage = currentPage,
            onDismiss = { pagePickerPage = null },
            onJump = { page ->
                pagePickerPage = null
                if (page != currentPage) onJumpToPage(page)
            },
        )
    }

    if (sourcePickerVisible) {
        ExploreSourcePickerDialog(
            sources = sources,
            selectedSourceUrl = state.selectedSourceUrl,
            onDismiss = onDismissSourcePicker,
            onSelectSource = { source ->
                onDismissSourcePicker()
                onSelectSource(source)
            },
            onSourceAction = onSourceAction,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExploreSourcePickerDialog(
    sources: List<BookSourcePart>,
    selectedSourceUrl: String?,
    onDismiss: () -> Unit,
    onSelectSource: (BookSourcePart) -> Unit,
    onSourceAction: (BookSourcePart, Int) -> Unit,
) {
    var filterQuery by remember { mutableStateOf("") }
    val reachabilityMap by BookSourceReachabilityStore.statusFlow.collectAsState()
    val filteredSources = remember(sources, filterQuery) {
        val q = filterQuery.trim()
        if (q.isEmpty()) {
            sources
        } else {
            sources.filter {
                it.bookSourceName.contains(q, ignoreCase = true) ||
                    it.bookSourceGroup?.contains(q, ignoreCase = true) == true
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedSourceUrl, filteredSources.size) {
        val idx = filteredSources.indexOfFirst { it.bookSourceUrl == selectedSourceUrl }
        if (idx >= 0) {
            runCatching { listState.scrollToItem((idx - 2).coerceAtLeast(0)) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NgGlassSurface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.76f),
            role = NgMaterialRole.CONTENT,
            liquidCornerRadius = NgTheme.shapes.mediumDp.dp,
            shape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp),
            style = NgGlassDefaults.bookDetailStyle(
                containerColor = colorResource(R.color.ng_surface_card)
            ),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "切换书源 (${filteredSources.size})",
                        modifier = Modifier.weight(1f),
                        color = colorResource(R.color.primaryText),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_close),
                            contentDescription = stringResource(R.string.close),
                            modifier = Modifier.size(18.dp),
                            tint = colorResource(R.color.secondaryText),
                        )
                    }
                }
                NgSearchBar(
                    query = filterQuery,
                    onQueryChange = { filterQuery = it },
                    hint = stringResource(R.string.screen_find),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    variant = NgSearchBarVariant.TOOLBAR,
                    hideHintOnFocus = true,
                )
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    thickness = 0.5.dp,
                    color = colorResource(R.color.secondaryText).copy(alpha = 0.12f),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    items(filteredSources, key = { it.bookSourceUrl }) { source ->
                        val selected = source.bookSourceUrl == selectedSourceUrl
                        val reachability = BookSourceReachabilityStore.resolve(source, reachabilityMap)
                        val tileColorInt = remember(source.bookSourceUrl) {
                            sourceTileColor(source.bookSourceUrl)
                        }
                        val tileContentColor = remember(tileColorInt) {
                            sourceTileContentColor(tileColorInt)
                        }
                        val glyphLabel = remember(source.bookSourceName) {
                            source.bookSourceName.trim().firstDisplayGrapheme().uppercase().ifEmpty { "源" }
                        }
                        var menuExpanded by remember(source.bookSourceUrl) { mutableStateOf(false) }

                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onSelectSource(source) },
                                        onLongClick = { menuExpanded = true },
                                    )
                                    .background(
                                        if (selected) {
                                            Color(NgTheme.colors.primary).copy(alpha = 0.10f)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ExploreCategoryGlyph(
                                    label = glyphLabel,
                                    background = Color(tileColorInt),
                                    foreground = tileContentColor,
                                    size = 34.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = source.bookSourceName,
                                        color = if (selected) {
                                            Color(NgTheme.colors.primary)
                                        } else {
                                            colorResource(R.color.primaryText)
                                        },
                                        fontSize = 14.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val group = source.bookSourceGroup?.takeIf(String::isNotBlank)
                                    if (group != null) {
                                        Text(
                                            text = group,
                                            color = colorResource(R.color.secondaryText),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (reachability != BookSourceReachability.UNKNOWN) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (reachability == BookSourceReachability.REACHABLE) {
                                                    Color(0xFF22C55E)
                                                } else {
                                                    Color(0xFFEF4444)
                                                }
                                            )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { menuExpanded = true },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_more),
                                            contentDescription = stringResource(R.string.more),
                                            modifier = Modifier.size(16.dp),
                                            tint = colorResource(R.color.secondaryText),
                                        )
                                    }
                                    ExploreSourceMenu(
                                        source = source,
                                        expanded = menuExpanded,
                                        onDismiss = { menuExpanded = false },
                                        onAction = { actionId ->
                                            menuExpanded = false
                                            onSourceAction(source, actionId)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
