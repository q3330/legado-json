package io.legado.app.ui.main.rss

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.RssSourceReachability
import io.legado.app.help.source.RssSourceReachabilityStore
import io.legado.app.ui.book.explore.ExploreCategoryGlyph
import io.legado.app.ui.design.components.compose.NgGlassDefaults
import io.legado.app.ui.design.components.compose.NgGlassSurface
import io.legado.app.ui.design.components.compose.NgMaterialRole
import io.legado.app.ui.design.components.compose.NgPullRefreshBox
import io.legado.app.ui.design.components.compose.NgSearchBar
import io.legado.app.ui.design.components.compose.NgSearchBarVariant
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.ui.main.explore.sourceTileColor
import io.legado.app.ui.main.explore.sourceTileContentColor
import io.legado.app.ui.rss.RssEmptyState
import io.legado.app.ui.rss.article.RssShowContentCard
import io.legado.app.utils.firstDisplayGrapheme

internal data class RssModernUiState(
    val selectedSourceUrl: String? = null,
    val sorts: List<Pair<String, String>> = emptyList(),
    val selectedSortIndex: Int = 0,
    val articles: List<RssArticle> = emptyList(),
    val page: Int = 1,
    val articleStyle: Int = 0,
    val isWebMode: Boolean = false,
    val webUrl: String? = null,
    val webHtml: String? = null,
    val webReloadToken: Long = 0L,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val loadError: String? = null,
    val canSearch: Boolean = false,
    val searchInput: String = "",
    val activeSearchQuery: String = "",
    val isSearchMode: Boolean = false,
)

@Composable
internal fun RssModernView(
    sources: List<RssSource>,
    state: RssModernUiState,
    bottomInset: Dp,
    categoriesExpanded: Boolean,
    onCategoriesExpandedChange: (Boolean) -> Unit,
    categoryAnchorX: Float?,
    sourcePickerVisible: Boolean,
    onDismissSourcePicker: () -> Unit,
    onSelectSource: (RssSource) -> Unit,
    onSelectSort: (Int) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onJumpToPage: (Int) -> Unit,
    onSelectLayoutStyle: (Int) -> Unit,
    onOpenArticle: (RssArticle) -> Unit,
    onOpenFullscreen: (RssSource) -> Unit,
    onSourceAction: (RssSource, Int) -> Unit,
) {
    val selectedSource = remember(sources, state.selectedSourceUrl) {
        sources.firstOrNull { it.sourceUrl == state.selectedSourceUrl } ?: sources.firstOrNull()
    }

    if (sources.isEmpty()) {
        RssEmptyState(
            text = stringResource(R.string.rss_source_empty),
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    NgPullRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        enabled = !state.isWebMode,
        showIndicator = false,
    ) {
        RssShowContentCard(
            sourceKey = selectedSource?.sourceName.orEmpty(),
            sorts = state.sorts,
            selectedSort = if (state.isSearchMode) -1 else state.selectedSortIndex,
            articles = state.articles,
            articleStyle = state.articleStyle,
            currentPage = state.page,
            refreshing = state.refreshing,
            loadingMore = state.loadingMore,
            hasMore = state.hasMore,
            loadError = state.loadError,
            categoriesExpanded = categoriesExpanded,
            onCategoriesExpandedChange = onCategoriesExpandedChange,
            categoryAnchorX = categoryAnchorX,
            onSortSelected = onSelectSort,
            onRefresh = onRefresh,
            onLoadMore = onLoadMore,
            onJumpToPage = onJumpToPage,
            onRetry = onRefresh,
            onOpenArticle = onOpenArticle,
            onSelectLayoutStyle = if (state.isWebMode) null else onSelectLayoutStyle,
            onOpenFullscreen = if (state.isWebMode && selectedSource != null) {
                { onOpenFullscreen(selectedSource) }
            } else {
                null
            },
            customContent = if (state.isWebMode && selectedSource != null) {
                {
                    RssInlineWebView(
                        source = selectedSource,
                        webUrl = state.webUrl,
                        webHtml = state.webHtml,
                        reloadToken = state.webReloadToken,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                null
            },
            modifier = Modifier.fillMaxSize(),
            cardBottomPadding = (bottomInset + 4.dp).coerceAtLeast(8.dp),
            bottomContentPadding = 16.dp,
            useNavigationBarsPadding = false,
            headerLabelOverride = if (state.isSearchMode) stringResource(R.string.search) else null,
        )
    }

    if (sourcePickerVisible) {
        RssSourcePickerDialog(
            sources = sources,
            selectedSourceUrl = selectedSource?.sourceUrl,
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
internal fun RssSourcePickerDialog(
    sources: List<RssSource>,
    selectedSourceUrl: String?,
    onDismiss: () -> Unit,
    onSelectSource: (RssSource) -> Unit,
    onSourceAction: (RssSource, Int) -> Unit,
) {
    var filterQuery by remember { mutableStateOf("") }
    val reachabilityMap by RssSourceReachabilityStore.statusFlow.collectAsState()
    val filteredSources = remember(sources, filterQuery) {
        val q = filterQuery.trim()
        if (q.isEmpty()) {
            sources
        } else {
            sources.filter {
                it.sourceName.contains(q, ignoreCase = true) ||
                    it.sourceGroup?.contains(q, ignoreCase = true) == true
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedSourceUrl, filteredSources.size) {
        val idx = filteredSources.indexOfFirst { it.sourceUrl == selectedSourceUrl }
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
                        text = "切换订阅源 (${filteredSources.size})",
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
                    hint = stringResource(R.string.search_rss_source),
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
                    items(filteredSources, key = { it.sourceUrl }) { source ->
                        val selected = source.sourceUrl == selectedSourceUrl
                        val reachability = RssSourceReachabilityStore.resolve(source, reachabilityMap)
                        val tileColorInt = remember(source.sourceUrl) {
                            sourceTileColor(source.sourceUrl)
                        }
                        val tileContentColor = remember(tileColorInt) {
                            sourceTileContentColor(tileColorInt)
                        }
                        val glyphLabel = remember(source.sourceName) {
                            source.sourceName.trim().firstDisplayGrapheme().uppercase().ifEmpty { "订" }
                        }
                        var menuExpanded by remember(source.sourceUrl) { mutableStateOf(false) }

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
                                        text = source.sourceName,
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
                                    val group = source.sourceGroup?.takeIf(String::isNotBlank)
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
                                if (reachability != RssSourceReachability.UNKNOWN) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (reachability == RssSourceReachability.REACHABLE) {
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
                                    RssSourceMenu(
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RssInlineWebView(
    source: RssSource,
    webUrl: String?,
    webHtml: String?,
    reloadToken: Long,
    modifier: Modifier = Modifier,
) {
    val loadKey = "${source.sourceUrl}|${webUrl.orEmpty()}|${webHtml?.hashCode() ?: 0}|$reloadToken"
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = source.enableJs
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                }
            },
            update = { webView ->
                webView.settings.javaScriptEnabled = source.enableJs
                if (webView.tag != loadKey) {
                    webView.tag = loadKey
                    webView.stopLoading()
                    if (!webHtml.isNullOrBlank()) {
                        webView.loadDataWithBaseURL(
                            source.sourceUrl,
                            webHtml,
                            "text/html",
                            "utf-8",
                            source.sourceUrl,
                        )
                    } else if (!webUrl.isNullOrBlank()) {
                        webView.loadUrl(webUrl)
                    }
                }
            },
        )
    }
}
