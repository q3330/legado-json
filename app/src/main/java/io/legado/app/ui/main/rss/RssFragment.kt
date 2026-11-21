package io.legado.app.ui.main.rss

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.RssSourceReachabilityStore
import io.legado.app.help.source.sortUrls
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.model.rss.Rss
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.favorites.RssFavoritesActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.rss.subscription.RuleSubActivity
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.startActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREF_RSS_MODERN_STYLE = "rssModernStyle"
private const val PREF_RSS_MODERN_SOURCE = "rssModernSourceUrl"

/**
 * 订阅界面
 */
class RssFragment() : Fragment(), MainFragmentInterface {

    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val viewModel by viewModels<RssViewModel>()
    private lateinit var composeView: ComposeView
    private var searchQuery by mutableStateOf("")
    private var groups by mutableStateOf<List<String>>(emptyList())
    private var selectedGroup by mutableStateOf<String?>(null)
    private var modernStyle by mutableStateOf(false)
    private var modernState by mutableStateOf(RssModernUiState())
    private var sources by mutableStateOf<List<RssSource>>(
        emptyList(),
        referentialEqualityPolicy()
    )
    private var bottomInsetPx by mutableStateOf(0)
    private var groupsFlowJob: Job? = null
    private var rssFlowJob: Job? = null
    private var modernLoadJob: Job? = null
    private var modernRequestId = 0L
    private var modernPage = 1
    private var modernNextUrl: String? = null
    private var modernOrder = System.currentTimeMillis()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        modernStyle = requireContext().getPrefBoolean(PREF_RSS_MODERN_STYLE, false)
        composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                NgAppTheme {
                    RssScreen(
                        sources = sources,
                        query = searchQuery,
                        groups = groups,
                        selectedGroup = selectedGroup,
                        modernStyle = modernStyle,
                        modernState = modernState,
                        bottomInsetPx = bottomInsetPx,
                        transparentTopBar = requireContext().transparentNavBar ||
                                requireContext().getPrefBoolean(PreferKey.tNavBar, false),
                        onToggleModernStyle = ::toggleModernStyle,
                        onModernSelectSource = { selectModernSource(it, forceReload = true) },
                        onModernSelectSort = ::selectModernSort,
                        onModernRefresh = ::refreshModernRss,
                        onModernLoadMore = ::loadMoreModernArticles,
                        onModernJumpToPage = ::jumpToModernRssPage,
                        onModernSelectLayoutStyle = ::setModernRssArticleStyle,
                        onModernOpenArticle = ::openModernArticle,
                        onQueryChange = ::updateSearchQuery,
                        onGroupSelected = ::selectGroup,
                        onOpenRuleSubscription = { startActivity<RuleSubActivity>() },
                        onOpenFavorites = { startActivity<RssFavoritesActivity>() },
                        onOpenSourceManage = { startActivity<RssSourceActivity>() },
                        onOpenSource = ::openRss,
                        onSourceAction = ::handleSourceAction
                    )
                }
            }
        }
        return composeView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initGroupData()
        upRssFlowJob()
    }

    private fun toggleModernStyle() {
        val next = !modernStyle
        modernStyle = next
        requireContext().putPrefBoolean(PREF_RSS_MODERN_STYLE, next)
        if (next) {
            ensureModernSourceSelected(sources, forceReload = false)
        }
    }

    private fun initGroupData() {
        groupsFlowJob?.cancel()
        groupsFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.rssSourceDao.flowEnabledGroups().catch {
                AppLog.put("订阅界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).conflate().collect { newGroups ->
                groups = newGroups
                val group = selectedGroup
                if (group != null && group !in newGroups) {
                    selectedGroup = null
                    upRssFlowJob()
                }
            }
        }
    }

    private fun updateSearchQuery(query: String) {
        if (searchQuery == query) return
        searchQuery = query
        if (query.isNotBlank()) selectedGroup = null
        upRssFlowJob()
    }

    private fun selectGroup(group: String?) {
        if (selectedGroup == group && searchQuery.isEmpty()) return
        selectedGroup = group
        searchQuery = ""
        upRssFlowJob()
    }

    private fun upRssFlowJob() {
        rssFlowJob?.cancel()
        val query = searchQuery.trim()
        val group = selectedGroup
        rssFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                query.isNotEmpty() -> appDb.rssSourceDao.flowEnabled(query)
                group != null -> appDb.rssSourceDao.flowEnabledByGroup(group)
                else -> appDb.rssSourceDao.flowEnabled()
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("订阅界面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                sources = it
                if (modernStyle) {
                    ensureModernSourceSelected(it, forceReload = false)
                }
            }
        }
    }

    private fun ensureModernSourceSelected(list: List<RssSource>, forceReload: Boolean) {
        if (list.isEmpty()) {
            modernLoadJob?.cancel()
            modernState = RssModernUiState()
            return
        }
        val currentUrl = modernState.selectedSourceUrl
        val keep = currentUrl?.let { url -> list.firstOrNull { it.sourceUrl == url } }
        if (keep != null) {
            if (forceReload || (!modernState.isWebMode && modernState.articles.isEmpty() && modernState.sorts.isEmpty() && !modernState.refreshing && !modernState.loadingMore && modernState.loadError == null)) {
                selectModernSource(keep, forceReload = true)
            }
            return
        }
        val rememberedUrl = context?.getPrefString(PREF_RSS_MODERN_SOURCE)
        val target = rememberedUrl?.let { url -> list.firstOrNull { it.sourceUrl == url } }
            ?: list.first()
        selectModernSource(target, forceReload = true)
    }

    private fun selectModernSource(source: RssSource, forceReload: Boolean) {
        val url = source.sourceUrl
        if (!forceReload && modernState.selectedSourceUrl == url &&
            (modernState.isWebMode || modernState.articles.isNotEmpty() || modernState.sorts.isNotEmpty())
        ) {
            return
        }
        context?.putPrefString(PREF_RSS_MODERN_SOURCE, url)
        modernLoadJob?.cancel()
        modernPage = 1
        modernNextUrl = null
        modernOrder = System.currentTimeMillis()
        val isWeb = source.singleUrl || source.ruleArticles.isNullOrBlank()
        modernState = RssModernUiState(
            selectedSourceUrl = url,
            page = 1,
            articleStyle = source.articleStyle,
            isWebMode = isWeb,
            refreshing = !isWeb,
            loadingMore = !isWeb,
        )
        if (isWeb) {
            loadModernWebSource(source)
        } else {
            loadModernArticleSource(source)
        }
    }

    private fun loadModernWebSource(source: RssSource) {
        val requestId = ++modernRequestId
        if (source.singleUrl) {
            viewModel.getSingleUrl(source) { resolvedUrl ->
                if (requestId != modernRequestId) return@getSingleUrl
                modernState = modernState.copy(
                    isWebMode = true,
                    webUrl = resolvedUrl,
                    webHtml = null,
                    webReloadToken = modernState.webReloadToken + 1,
                )
            }
        } else {
            viewModel.launchRssWithHtml(source, {
                if (requestId != modernRequestId) return@launchRssWithHtml
                modernState = modernState.copy(
                    isWebMode = true,
                    webUrl = source.sourceUrl,
                    webHtml = null,
                    webReloadToken = modernState.webReloadToken + 1,
                )
            }) { html ->
                if (requestId != modernRequestId) return@launchRssWithHtml
                modernState = modernState.copy(
                    isWebMode = true,
                    webUrl = source.sourceUrl,
                    webHtml = html,
                    webReloadToken = modernState.webReloadToken + 1,
                )
            }
        }
    }

    private fun loadModernArticleSource(source: RssSource) {
        val requestId = ++modernRequestId
        modernLoadJob?.cancel()
        modernLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val sorts = withContext(IO) {
                runCatching { source.sortUrls() }
                    .getOrElse { listOf(Pair("", source.sourceUrl)) }
                    .ifEmpty { listOf(Pair("", source.sourceUrl)) }
            }
            if (requestId != modernRequestId) return@launch
            val validIndex = sorts.indexOfFirst { it.second.isNotBlank() }.coerceAtLeast(0)
            modernState = modernState.copy(
                sorts = sorts,
                selectedSortIndex = validIndex,
            )
            val sort = sorts[validIndex]
            fetchModernArticles(source, sort.first, sort.second, page = 1, reset = true)
        }
    }

    private fun selectModernSort(index: Int) {
        val source = sources.firstOrNull { it.sourceUrl == modernState.selectedSourceUrl } ?: return
        val sort = modernState.sorts.getOrNull(index) ?: return
        if (sort.second.isBlank() || index == modernState.selectedSortIndex) return
        modernPage = 1
        modernNextUrl = null
        modernState = modernState.copy(
            selectedSortIndex = index,
            articles = emptyList(),
            page = 1,
            hasMore = true,
            loadError = null,
        )
        fetchModernArticles(source, sort.first, sort.second, page = 1, reset = true)
    }

    private fun refreshModernRss() {
        val source = sources.firstOrNull { it.sourceUrl == modernState.selectedSourceUrl }
            ?: sources.firstOrNull()
            ?: return
        if (modernState.isWebMode) {
            loadModernWebSource(source)
            return
        }
        val sort = modernState.sorts.getOrNull(modernState.selectedSortIndex)
        if (sort != null && sort.second.isNotBlank()) {
            fetchModernArticles(source, sort.first, sort.second, page = 1, reset = true, isPullRefresh = true)
        } else {
            loadModernArticleSource(source)
        }
    }

    private fun loadMoreModernArticles() {
        val source = sources.firstOrNull { it.sourceUrl == modernState.selectedSourceUrl } ?: return
        val sort = modernState.sorts.getOrNull(modernState.selectedSortIndex) ?: return
        val nextUrl = modernNextUrl?.takeIf(String::isNotBlank) ?: return
        if (modernState.loadingMore || modernState.refreshing || !modernState.hasMore) return
        fetchModernArticles(source, sort.first, nextUrl, page = modernPage + 1, reset = false)
    }

    private fun jumpToModernRssPage(page: Int) {
        val safePage = page.coerceAtLeast(1)
        val source = sources.firstOrNull { it.sourceUrl == modernState.selectedSourceUrl } ?: return
        val sort = modernState.sorts.getOrNull(modernState.selectedSortIndex) ?: return
        if (sort.second.isBlank() || modernState.loadingMore || modernState.refreshing) return
        if (safePage == modernPage + 1 && !modernNextUrl.isNullOrBlank()) {
            loadMoreModernArticles()
        } else {
            fetchModernArticles(source, sort.first, sort.second, page = safePage, reset = true)
        }
    }

    private fun setModernRssArticleStyle(style: Int) {
        if (modernState.articleStyle == style) return
        modernState = modernState.copy(articleStyle = style)
        val source = sources.firstOrNull { it.sourceUrl == modernState.selectedSourceUrl } ?: return
        if (source.articleStyle != style) {
            source.articleStyle = style
            viewLifecycleOwner.lifecycleScope.launch(IO) {
                runCatching { appDb.rssSourceDao.update(source) }
            }
        }
    }

    private fun fetchModernArticles(
        source: RssSource,
        sortName: String,
        targetUrl: String,
        page: Int,
        reset: Boolean,
        isPullRefresh: Boolean = false,
    ) {
        val requestId = ++modernRequestId
        modernLoadJob?.cancel()
        modernState = modernState.copy(
            refreshing = isPullRefresh,
            loadingMore = !isPullRefresh,
            loadError = null,
        )
        modernLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (articles, nextPageUrl) = withContext(IO) {
                    Rss.getArticlesAwait(sortName, targetUrl, source, page, null)
                }
                if (requestId != modernRequestId) return@launch
                RssSourceReachabilityStore.markReachable(source.sourceUrl)
                var nextOrder = if (reset) System.currentTimeMillis() else modernOrder
                val ordered = articles.map { it.copy(order = nextOrder--) }
                modernOrder = nextOrder
                withContext(IO) {
                    runCatching {
                        if (ordered.isNotEmpty()) {
                            appDb.rssArticleDao.insert(*ordered.toTypedArray())
                        }
                    }
                }
                val previous = if (reset) emptyList() else modernState.articles
                val existingLinks = previous.mapTo(hashSetOf()) { it.link }
                val fresh = ordered.filter { it.link !in existingLinks }
                val merged = if (reset) ordered.distinctBy { it.link } else (previous + fresh)
                modernPage = page
                modernNextUrl = nextPageUrl?.takeIf { it.isNotBlank() && it != targetUrl }
                val hasMore = !modernNextUrl.isNullOrBlank() && (reset || fresh.isNotEmpty())
                modernState = modernState.copy(
                    articles = merged,
                    page = page,
                    refreshing = false,
                    loadingMore = false,
                    hasMore = hasMore,
                    loadError = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (requestId != modernRequestId) return@launch
                if (reset) {
                    RssSourceReachabilityStore.markUnreachable(source.sourceUrl)
                }
                modernState = modernState.copy(
                    refreshing = false,
                    loadingMore = false,
                    loadError = e.localizedMessage ?: "加载失败",
                )
            }
        }
    }

    private fun openModernArticle(article: RssArticle) {
        ReadRssActivity.start(
            requireContext(),
            article.origin,
            article.title,
            article.link,
            article.sort,
        )
    }

    private fun openRss(rssSource: RssSource) {
        if (rssSource.singleUrl) {
            viewModel.getSingleUrl(rssSource) { url ->
                if (url.startsWith("http", true)) {
                    ReadRssActivity.start(
                        requireContext(),
                        true,
                        rssSource.sourceUrl,
                        rssSource.sourceName,
                        url
                    )
                } else {
                    context?.openUrl(url)
                }
            }
        } else {
            viewModel.launchRssWithHtml(rssSource, {
                startActivity<RssSortActivity> {
                    putExtra("sourceUrl", rssSource.sourceUrl)
                }
            }) { html ->
                ReadRssActivity.start(
                    requireContext(),
                    true,
                    rssSource.sourceUrl,
                    rssSource.sourceName,
                    startHtml = html
                )
            }
        }
    }

    private fun handleSourceAction(rssSource: RssSource, actionId: Int) {
        when (actionId) {
            R.id.menu_edit -> startActivity<RssSourceEditActivity> {
                putExtra("sourceUrl", rssSource.sourceUrl)
            }

            R.id.menu_top -> viewModel.topSource(rssSource)
            R.id.menu_login -> startActivity<SourceLoginActivity> {
                putExtra("type", "rssSource")
                putExtra("key", rssSource.sourceUrl)
            }

            R.id.menu_disable -> viewModel.disable(rssSource)
            R.id.menu_del -> deleteSource(rssSource)
        }
    }

    private fun deleteSource(rssSource: RssSource) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rssSource.sourceName)
            noButton()
            yesButton { viewModel.del(rssSource) }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.resolveFloatingBottomContentInset {
            bottomInsetPx = it
        }
    }

    override fun onPause() {
        if (this::composeView.isInitialized) composeView.clearFocus()
        super.onPause()
    }

    override fun onDestroyView() {
        modernLoadJob?.cancel()
        super.onDestroyView()
    }
}
