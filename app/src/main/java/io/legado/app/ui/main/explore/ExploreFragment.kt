package io.legado.app.ui.main.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.ExploreKind.Type
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.BookSourceDedupe
import io.legado.app.help.source.BookSourceDedupeMode
import io.legado.app.help.source.BookSourceReachabilityStore
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.isOpenableExploreCategory
import io.legado.app.help.translate.BookTitleTranslator
import io.legado.app.help.translate.TranslateConfig
import io.legado.app.help.translate.TranslateHistoryStore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchGroupVisibility
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val PREF_EXPLORE_MODERN_STYLE = "exploreModernStyle"
private const val PREF_EXPLORE_MODERN_GRID = "exploreModernBookGrid"
private const val PREF_EXPLORE_MODERN_SOURCE = "exploreModernSourceUrl"

/**
 * 发现界面
 */
class ExploreFragment() : Fragment(), MainFragmentInterface {

    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val viewModel by viewModels<ExploreViewModel>()
    private lateinit var composeView: ComposeView
    private var searchQuery by mutableStateOf("")
    private var groups by mutableStateOf<List<String>>(emptyList())
    private var selectedGroup by mutableStateOf<String?>(null)
    private var selectedType by mutableStateOf<Int?>(null)
    private var exploreLayoutMode by mutableStateOf(
        ExploreLayoutMode.from(AppConfig.exploreLayoutMode)
    )
    private var modernStyle by mutableStateOf(false)
    private var modernState by mutableStateOf(ExploreModernUiState())
    private var sources by mutableStateOf<List<BookSourcePart>>(emptyList())
    private var expandedSourceUrl by mutableStateOf<String?>(null)
    private val kindsBySource = mutableStateMapOf<String, List<ExploreKind>>()
    private val loadingSources = mutableStateMapOf<String, Boolean>()
    private var bottomInsetPx by mutableStateOf(0)
    private var scrollToTopToken by mutableStateOf(0L)
    private var exploreFlowJob: Job? = null
    private var groupsFlowJob: Job? = null
    private var bookshelfFlowJob: Job? = null
    private var modernSourceJob: Job? = null
    private var modernBooksJob: Job? = null
    private var modernTranslateJob: Job? = null
    private var modernFullSource: BookSource? = null
    private var modernRequestId = 0L
    private data class ModernFailedPage(val page: Int, val reset: Boolean, val kindUrl: String)
    private var modernFailedPage: ModernFailedPage? = null
    private var appliedDedupeMode: Int = AppConfig.bookSourceDedupeMode
    private val kindJobs = mutableMapOf<String, Job>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        modernStyle = requireContext().getPrefBoolean(PREF_EXPLORE_MODERN_STYLE, false)
        modernState = modernState.copy(
            layoutMode = io.legado.app.ui.book.explore.ExploreShowLayoutMode.from(AppConfig.exploreShowLayoutMode),
            autoTranslateEnabled = TranslateConfig.enabled,
        )
        composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                NgAppTheme {
                    ExploreScreen(
                        sources = sources,
                        query = searchQuery,
                        groups = groups,
                        selectedGroup = selectedGroup,
                        selectedType = selectedType,
                        layoutMode = exploreLayoutMode,
                        modernStyle = modernStyle,
                        modernState = modernState,
                        expandedSourceUrl = expandedSourceUrl,
                        kindsBySource = kindsBySource,
                        loadingSourceUrls = loadingSources.keys.toSet(),
                        bottomInsetPx = bottomInsetPx,
                        scrollToTopToken = scrollToTopToken,
                        transparentTopBar = requireContext().transparentNavBar ||
                                requireContext().getPrefBoolean(PreferKey.tNavBar, false),
                        onToggleModernStyle = ::toggleModernStyle,
                        onModernSelectSource = { selectModernSource(it, forceReload = true) },
                        onModernSelectKind = ::selectModernKind,
                        onModernRefresh = ::refreshModernContent,
                        onModernRefreshKinds = ::reloadModernKinds,
                        onModernLoadMore = ::loadMoreModernBooks,
                        onModernJumpToPage = ::jumpToModernPage,
                        onModernRetryContent = ::retryModernContent,
                        onModernToggleAutoTranslate = ::toggleModernAutoTranslate,
                        onModernLayoutModeChange = ::setModernLayoutMode,
                        onModernOpenBook = ::openBookInfo,
                        onQueryChange = { query ->
                            searchQuery = query
                            if (query.isNotBlank()) {
                                selectedGroup = null
                                selectedType = null
                            }
                            upExploreData()
                        },
                        onGroupSelected = { group ->
                            if (modernStyle || exploreLayoutMode != ExploreLayoutMode.GROUP_GRID || group == null) {
                                selectedGroup = group
                                selectedType = null
                                searchQuery = ""
                                upExploreData()
                            }
                        },
                        onTypeSelected = { type ->
                            selectedType = type
                            selectedGroup = null
                            searchQuery = ""
                            upExploreData()
                        },
                        onLayoutModeChange = { mode ->
                            if (modernStyle) {
                                modernStyle = false
                                requireContext().putPrefBoolean(PREF_EXPLORE_MODERN_STYLE, false)
                            }
                            if (exploreLayoutMode != mode) {
                                val resetGroup = mode == ExploreLayoutMode.GROUP_GRID &&
                                        selectedGroup != null
                                exploreLayoutMode = mode
                                expandedSourceUrl = null
                                AppConfig.exploreLayoutMode = mode.value
                                scrollToTopToken++
                                if (resetGroup) {
                                    selectedGroup = null
                                    upExploreData()
                                }
                            }
                        },
                        onManageSources = { startActivity<BookSourceActivity>() },
                        onToggleSource = ::toggleSource,
                        onOpenSource = ::openSourceDefault,
                        onOpenKind = ::openExploreKind,
                        onShowError = { showDialogFragment(TextDialog("ERROR", it)) },
                        onSourceAction = ::handleSourceAction,
                        onRefreshSource = ::refreshSource
                    )
                }
            }
        }
        return composeView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initGroupData()
        initBookshelfData()
        upExploreData()
    }

    private fun toggleModernStyle() {
        val next = !modernStyle
        modernStyle = next
        requireContext().putPrefBoolean(PREF_EXPLORE_MODERN_STYLE, next)
        if (next) {
            modernState = modernState.copy(autoTranslateEnabled = TranslateConfig.enabled)
            ensureModernSourceSelected(sources, forceReload = false)
        }
    }

    private fun setModernLayoutMode(mode: io.legado.app.ui.book.explore.ExploreShowLayoutMode) {
        if (modernState.layoutMode == mode) return
        modernState = modernState.copy(layoutMode = mode)
        AppConfig.exploreShowLayoutMode = mode.value
    }

    private fun toggleModernAutoTranslate() {
        val next = !TranslateConfig.enabled
        TranslateConfig.enabled = next
        modernState = modernState.copy(autoTranslateEnabled = next)
        if (next) {
            translateModernBooks(modernState.books)
        } else {
            modernTranslateJob?.cancel()
        }
    }

    private fun initBookshelfData() {
        bookshelfFlowJob?.cancel()
        bookshelfFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowAll()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_TABLE_NAME,
                )
                .map { books ->
                    buildSet {
                        books.filterNot { it.isNotShelf }.forEach { book ->
                            add(book.bookUrl)
                            add(book.name)
                            add("${book.name}-${book.author}")
                        }
                    }
                }
                .catch { AppLog.put("发现界面获取书架数据失败", it) }
                .conflate()
                .flowOn(IO)
                .collect { keys ->
                    modernState = modernState.copy(bookshelfKeys = keys)
                }
        }
    }

    private fun initGroupData() {
        groupsFlowJob?.cancel()
        groupsFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExploreGroups()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect {
                    groups = SearchGroupVisibility.visibleGroups(it)
                    delay(80)
                }
        }
    }

    private fun upExploreData() {
        if (!this::composeView.isInitialized) return
        exploreFlowJob?.cancel()
        val sourceName = searchQuery.trim()
        val group = selectedGroup
        val type = selectedType
        val dedupeMode = BookSourceDedupeMode.from(AppConfig.bookSourceDedupeMode)
        appliedDedupeMode = dedupeMode.value
        exploreFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                sourceName.isNotEmpty() -> appDb.bookSourceDao.flowExploreByName(sourceName)
                type != null -> appDb.bookSourceDao.flowTypeExplore(type)
                group != null -> appDb.bookSourceDao.flowGroupExplore(group)
                else -> appDb.bookSourceDao.flowExplore()
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).map { list ->
                if (dedupeMode == BookSourceDedupeMode.NONE || list.size <= 1) {
                    list
                } else {
                    val scores = BookSourceDedupe.getCachedRuleScores().ifEmpty {
                        appDb.bookSourceDao.allRuleScores()
                            .associate { it.bookSourceUrl to it.score }
                            .also(BookSourceDedupe::updateCachedRuleScores)
                    }
                    val preferred = BookSourceDedupe.loadPreferredPrimaries(dedupeMode)
                    BookSourceDedupe.dedupePrimarySources(list, dedupeMode, scores, preferred)
                }
            }.catch {
                AppLog.put("发现界面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                sources = it
                if (it.isEmpty()) {
                    kindsBySource.clear()
                }
                if (expandedSourceUrl != null && it.none { source ->
                        source.bookSourceUrl == expandedSourceUrl
                    }
                ) {
                    expandedSourceUrl = null
                }
                if (modernStyle) {
                    ensureModernSourceSelected(it, forceReload = false)
                }
                delay(80)
            }
        }
    }

    private fun ensureModernSourceSelected(
        list: List<BookSourcePart>,
        forceReload: Boolean,
    ) {
        if (list.isEmpty()) {
            modernSourceJob?.cancel()
            modernBooksJob?.cancel()
            modernFullSource = null
            modernState = modernState.copy(
                selectedSourceUrl = null,
                source = null,
                sourceName = "",
                kinds = emptyList(),
                selectedKind = null,
                books = emptyList(),
                bookPages = emptyMap(),
                isKindsLoading = false,
                isRefreshing = false,
                isContentLoading = false,
                kindsError = null,
                contentError = null,
            )
            return
        }
        val currentUrl = modernState.selectedSourceUrl
        val keep = currentUrl?.let { url -> list.firstOrNull { it.bookSourceUrl == url } }
        if (keep != null) {
            if (forceReload || (modernState.kinds.isEmpty() && !modernState.isKindsLoading && modernState.kindsError == null)) {
                selectModernSource(keep, forceReload = true)
            }
            return
        }
        val rememberedUrl = context?.getPrefString(PREF_EXPLORE_MODERN_SOURCE)
        val target = rememberedUrl?.let { url -> list.firstOrNull { it.bookSourceUrl == url } }
            ?: list.first()
        selectModernSource(target, forceReload = true)
    }

    private fun selectModernSource(source: BookSourcePart, forceReload: Boolean) {
        val url = source.bookSourceUrl
        if (!forceReload && modernState.selectedSourceUrl == url && modernState.kinds.isNotEmpty()) {
            return
        }
        context?.putPrefString(PREF_EXPLORE_MODERN_SOURCE, url)
        modernSourceJob?.cancel()
        modernBooksJob?.cancel()
        modernTranslateJob?.cancel()
        modernFailedPage = null
        modernFullSource = null
        modernState = modernState.copy(
            selectedSourceUrl = url,
            source = null,
            sourceName = source.bookSourceName,
            kinds = emptyList(),
            selectedKind = null,
            books = emptyList(),
            bookPages = emptyMap(),
            firstLoadedPage = 1,
            lastLoadedPage = 0,
            selectionRevision = modernState.selectionRevision + 1,
            isKindsLoading = true,
            isRefreshing = false,
            isContentLoading = false,
            isLoadingPrevious = false,
            hasMore = true,
            kindsError = null,
            contentError = null,
        )
        modernSourceJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fullSource = withContext(IO) {
                    appDb.bookSourceDao.getBookSource(url)
                }
                if (fullSource == null) {
                    modernState = modernState.copy(
                        isKindsLoading = false,
                        kindsError = "未找到该书源",
                    )
                    return@launch
                }
                modernFullSource = fullSource
                val kinds = withContext(IO) { source.exploreKinds() }
                kindsBySource[url] = kinds
                val errorKind = kinds.firstOrNull { it.title.startsWith("ERROR:") }
                if (errorKind != null) {
                    BookSourceReachabilityStore.markUnreachable(url)
                    modernState = modernState.copy(
                        source = fullSource,
                        sourceName = fullSource.bookSourceName,
                        kinds = kinds,
                        isKindsLoading = false,
                        kindsError = errorKind.url.orEmpty().ifBlank { errorKind.title },
                    )
                    return@launch
                }
                val firstKind = kinds.firstOrNull { it.isOpenableExploreCategory() && it.title.isNotBlank() }
                    ?: kinds.firstOrNull { it.isOpenableExploreCategory() }
                modernState = modernState.copy(
                    source = fullSource,
                    sourceName = fullSource.bookSourceName,
                    kinds = kinds,
                    selectedKind = firstKind,
                    isKindsLoading = false,
                    kindsError = null,
                )
                if (firstKind != null && !firstKind.url.isNullOrBlank()) {
                    loadModernBooksPage(fullSource, firstKind.url!!, page = 1, reset = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                BookSourceReachabilityStore.markUnreachable(url)
                modernState = modernState.copy(
                    isKindsLoading = false,
                    kindsError = e.localizedMessage ?: getString(R.string.can_not_open),
                )
            }
        }
    }

    private fun reloadModernKinds() {
        val fullSource = modernFullSource ?: return
        if (modernState.isRefreshing) return
        modernSourceJob?.cancel()
        modernSourceJob = viewLifecycleOwner.lifecycleScope.launch {
            modernState = modernState.copy(isKindsLoading = true, kindsError = null)
            try {
                withContext(IO) { fullSource.clearExploreKindsCache() }
                val kinds = withContext(IO) { fullSource.exploreKinds() }
                kindsBySource[fullSource.bookSourceUrl] = kinds
                val errorKind = kinds.firstOrNull { it.title.startsWith("ERROR:") }
                if (errorKind != null) {
                    BookSourceReachabilityStore.markUnreachable(fullSource.bookSourceUrl)
                }
                val current = modernState.selectedKind
                val selected = current?.url?.let { url -> kinds.firstOrNull { it.url == url } }
                    ?: kinds.firstOrNull { it.isOpenableExploreCategory() }
                val selectionChanged = selected?.url != current?.url
                modernState = modernState.copy(
                    kinds = kinds,
                    selectedKind = selected,
                    isKindsLoading = false,
                    kindsError = errorKind?.url,
                    selectionRevision = if (selectionChanged) modernState.selectionRevision + 1 else modernState.selectionRevision,
                )
                if (errorKind == null && selectionChanged && selected != null && !selected.url.isNullOrBlank()) {
                    loadModernBooksPage(fullSource, selected.url!!, page = 1, reset = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                modernState = modernState.copy(
                    isKindsLoading = false,
                    kindsError = e.localizedMessage ?: "加载失败",
                )
            }
        }
    }

    private fun selectModernKind(kind: ExploreKind) {
        if (!kind.isOpenableExploreCategory()) return
        val kindUrl = kind.url?.takeIf(String::isNotBlank) ?: return
        val fullSource = modernFullSource ?: return
        modernBooksJob?.cancel()
        modernRequestId++
        modernState = modernState.copy(
            selectedKind = kind,
            books = emptyList(),
            bookPages = emptyMap(),
            firstLoadedPage = 1,
            lastLoadedPage = 0,
            hasMore = true,
            isContentLoading = false,
            contentError = null,
            selectionRevision = modernState.selectionRevision + 1,
        )
        loadModernBooksPage(fullSource, kindUrl, page = 1, reset = true)
    }

    private fun refreshModernContent() {
        val fullSource = modernFullSource
        val kindUrl = modernState.selectedKind?.url
        if (fullSource != null && !kindUrl.isNullOrBlank()) {
            loadModernBooksPage(fullSource, kindUrl, page = 1, reset = true, isPullRefresh = true)
        } else {
            val current = sources.firstOrNull { it.bookSourceUrl == modernState.selectedSourceUrl }
                ?: sources.firstOrNull()
            if (current != null) {
                selectModernSource(current, forceReload = true)
            }
        }
    }

    private fun loadMoreModernBooks() {
        val fullSource = modernFullSource ?: return
        val kindUrl = modernState.selectedKind?.url?.takeIf(String::isNotBlank) ?: return
        if (modernState.isContentLoading || modernState.isRefreshing || !modernState.hasMore) return
        val nextPage = (modernState.lastLoadedPage + 1).coerceAtLeast(1)
        loadModernBooksPage(
            source = fullSource,
            kindUrl = kindUrl,
            page = nextPage,
            reset = modernState.books.isEmpty(),
        )
    }

    private fun jumpToModernPage(page: Int) {
        if (page <= 0) return
        val fullSource = modernFullSource ?: return
        val kindUrl = modernState.selectedKind?.url?.takeIf(String::isNotBlank) ?: return
        if (modernState.isContentLoading || modernState.isKindsLoading || modernState.isRefreshing) return
        loadModernBooksPage(fullSource, kindUrl, page = page, reset = true)
    }

    private fun retryModernContent() {
        val fullSource = modernFullSource ?: return
        val failed = modernFailedPage ?: return
        if (modernState.isContentLoading || modernState.selectedKind?.url != failed.kindUrl) return
        loadModernBooksPage(fullSource, failed.kindUrl, page = failed.page, reset = failed.reset)
    }

    private fun loadModernBooksPage(
        source: BookSource,
        kindUrl: String,
        page: Int,
        reset: Boolean,
        isPullRefresh: Boolean = false,
    ) {
        val requestId = ++modernRequestId
        modernFailedPage = null
        modernBooksJob?.cancel()
        modernState = modernState.copy(
            isRefreshing = isPullRefresh,
            isContentLoading = true,
            contentError = null,
        )
        modernBooksJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val incoming = withContext(IO) {
                    withTimeout(45_000L) {
                        WebBook.exploreBookAwait(source, kindUrl, page)
                    }
                }
                if (requestId != modernRequestId) return@launch
                if (incoming.isNotEmpty()) {
                    BookSourceReachabilityStore.markReachable(source.bookSourceUrl)
                    withContext(IO) {
                        runCatching { appDb.searchBookDao.insert(*incoming.toTypedArray()) }
                    }
                }
                val previous = if (reset) emptyList() else modernState.books
                val existingUrls = previous.mapTo(hashSetOf()) { it.bookUrl }
                val fresh = incoming.filter { it.bookUrl !in existingUrls }
                val merged = if (reset) incoming.distinctBy { it.bookUrl } else (previous + fresh)
                val hasMore = incoming.isNotEmpty() && (reset || fresh.isNotEmpty())
                val updatedBookPages = buildMap {
                    if (!reset) putAll(modernState.bookPages)
                    incoming.forEach { book ->
                        if (book.bookUrl !in this) {
                            put(book.bookUrl, page)
                        }
                    }
                }
                modernState = modernState.copy(
                    books = merged,
                    bookPages = updatedBookPages,
                    firstLoadedPage = if (reset) page else modernState.firstLoadedPage,
                    lastLoadedPage = if (hasMore || reset) page else modernState.lastLoadedPage,
                    isRefreshing = false,
                    isContentLoading = false,
                    hasMore = hasMore,
                    contentError = null,
                    selectionRevision = if (reset) modernState.selectionRevision + 1 else modernState.selectionRevision,
                )
                if (TranslateConfig.enabled && merged.isNotEmpty()) {
                    translateModernBooks(merged)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (requestId != modernRequestId) return@launch
                if (reset) {
                    BookSourceReachabilityStore.markUnreachable(source.bookSourceUrl)
                }
                modernFailedPage = ModernFailedPage(page, reset, kindUrl)
                modernState = modernState.copy(
                    isRefreshing = false,
                    isContentLoading = false,
                    contentError = e.localizedMessage ?: "加载失败",
                )
            }
        }
    }

    private fun translateModernBooks(books: List<SearchBook>) {
        if (!TranslateConfig.enabled || books.isEmpty()) return
        val cachedByName = buildMap {
            books.forEach { book ->
                BookTitleTranslator.getCached(book.name)?.let { put(book.name, it) }
            }
        }
        if (cachedByName.isNotEmpty()) {
            modernState = modernState.copy(
                translatedNames = modernState.translatedNames + cachedByName,
            )
        }
        val namesToTranslate = books.map { it.name }.distinct().filter { name ->
            name !in cachedByName && BookTitleTranslator.needsTranslation(name)
        }
        if (namesToTranslate.isEmpty()) return
        modernTranslateJob?.cancel()
        modernTranslateJob = viewLifecycleOwner.lifecycleScope.launch(IO) {
            val translated = BookTitleTranslator.translateBatch(namesToTranslate)
            if (translated.isNotEmpty()) {
                books.forEach { book ->
                    translated[book.name]?.let { translatedTitle ->
                        TranslateHistoryStore.recordBookTitle(
                            bookKey = book.bookUrl,
                            originalTitle = book.name,
                            translatedTitle = translatedTitle,
                        )
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    modernState = modernState.copy(
                        translatedNames = modernState.translatedNames + translated,
                    )
                }
            }
        }
    }

    private fun openBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    private fun toggleSource(source: BookSourcePart) {
        if (expandedSourceUrl == source.bookSourceUrl) {
            expandedSourceUrl = null
            return
        }
        expandedSourceUrl = source.bookSourceUrl
        loadKinds(source)
    }

    private fun openSourceDefault(source: BookSourcePart) {
        val cachedKinds = kindsBySource[source.bookSourceUrl]
        if (cachedKinds != null) {
            openDefaultKind(source, cachedKinds)
        } else {
            loadKinds(source) { openDefaultKind(source, it) }
        }
    }

    private fun loadKinds(
        source: BookSourcePart,
        onLoaded: ((List<ExploreKind>) -> Unit)? = null
    ) {
        val sourceUrl = source.bookSourceUrl
        kindsBySource[sourceUrl]?.let {
            onLoaded?.invoke(it)
            return
        }
        if (loadingSources[sourceUrl] == true) return
        kindJobs[sourceUrl]?.cancel()
        loadingSources[sourceUrl] = true
        kindJobs[sourceUrl] = viewLifecycleOwner.lifecycleScope.launch {
            runCatching { source.exploreKinds() }
                .onSuccess {
                    if (it.any { kind -> kind.title.startsWith("ERROR:") }) {
                        BookSourceReachabilityStore.markUnreachable(sourceUrl)
                    }
                    kindsBySource[sourceUrl] = it
                    onLoaded?.invoke(it)
                }
                .onFailure {
                    BookSourceReachabilityStore.markUnreachable(sourceUrl)
                    AppLog.put("加载发现分类失败", it)
                    toastOnUi(it.localizedMessage ?: getString(R.string.can_not_open))
                }
            loadingSources.remove(sourceUrl)
            kindJobs.remove(sourceUrl)
        }
    }

    private fun openDefaultKind(source: BookSourcePart, kinds: List<ExploreKind>) {
        val errorKind = kinds.firstOrNull { it.title.startsWith("ERROR:") }
        val hasDetailContent = kinds.any {
            it.isOpenableExploreCategory() ||
                    it.type == Type.button ||
                    it.type == Type.text ||
                    it.type == Type.toggle ||
                    it.type == Type.select
        }
        when {
            errorKind != null -> {
                showDialogFragment(TextDialog("ERROR", errorKind.url.orEmpty()))
            }

            hasDetailContent -> openExplore(
                sourceUrl = source.bookSourceUrl,
                sourceName = source.bookSourceName
            )

            else -> toastOnUi(R.string.explore_direct_entry_empty)
        }
    }

    private fun openExploreKind(source: BookSourcePart, kind: ExploreKind) {
        openExplore(
            sourceUrl = source.bookSourceUrl,
            sourceName = source.bookSourceName,
            exploreName = kind.title,
            exploreUrl = kind.url
        )
    }

    private fun openExplore(
        sourceUrl: String,
        sourceName: String,
        exploreName: String? = null,
        exploreUrl: String? = null
    ) {
        startActivity<ExploreShowActivity> {
            putExtra(ExploreShowActivity.EXTRA_SOURCE_URL, sourceUrl)
            putExtra(ExploreShowActivity.EXTRA_SOURCE_NAME, sourceName)
            putExtra(ExploreShowActivity.EXTRA_EXPLORE_NAME, exploreName)
            putExtra(ExploreShowActivity.EXTRA_EXPLORE_URL, exploreUrl)
        }
    }

    private fun handleSourceAction(source: BookSourcePart, actionId: Int) {
        when (actionId) {
            R.id.menu_edit -> startActivity<BookSourceEditActivity> {
                putExtra("sourceUrl", source.bookSourceUrl)
            }

            R.id.menu_top -> viewModel.topSource(source)
            R.id.menu_search -> SearchActivity.start(requireContext(), source)
            R.id.menu_login -> startActivity<SourceLoginActivity> {
                putExtra("type", "bookSource")
                putExtra("key", source.bookSourceUrl)
            }

            R.id.menu_refresh -> refreshSource(source)
            R.id.menu_del -> deleteSource(source)
        }
    }

    private fun refreshSource(source: BookSourcePart) {
        val sourceUrl = source.bookSourceUrl
        kindJobs[sourceUrl]?.cancel()
        loadingSources[sourceUrl] = true
        kindJobs[sourceUrl] = viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                source.clearExploreKindsCache()
                kindsBySource.remove(sourceUrl)
                if (expandedSourceUrl == sourceUrl) source.exploreKinds() else null
            }.onSuccess { kinds ->
                if (kinds != null) kindsBySource[sourceUrl] = kinds
                if (modernStyle && modernState.selectedSourceUrl == sourceUrl) {
                    selectModernSource(source, forceReload = true)
                }
            }.onFailure {
                AppLog.put("刷新发现分类失败", it)
                toastOnUi(it.localizedMessage ?: getString(R.string.can_not_open))
            }
            loadingSources.remove(sourceUrl)
            kindJobs.remove(sourceUrl)
        }
    }

    private fun deleteSource(source: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton { viewModel.deleteSource(source) }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.resolveFloatingBottomContentInset {
            bottomInsetPx = it
        }
        val currentDedupeMode = AppConfig.bookSourceDedupeMode
        if (currentDedupeMode != appliedDedupeMode) {
            appliedDedupeMode = currentDedupeMode
            upExploreData()
        }
    }

    override fun onPause() {
        if (this::composeView.isInitialized) composeView.clearFocus()
        saveExploreInfoMaps()
        super.onPause()
    }

    private fun saveExploreInfoMaps() {
        val infoMaps = ExploreInfoStore.infoMapList.snapshot()
            .filter { (_, infoMap) -> infoMap.needSave }
        viewLifecycleOwner.lifecycleScope.launch {
            infoMaps.map { (_, infoMap) ->
                launch(IO) { infoMap.saveNow() }
            }.joinAll()
        }
    }

    override fun onDestroyView() {
        kindJobs.values.forEach { it.cancel() }
        kindJobs.clear()
        modernSourceJob?.cancel()
        modernBooksJob?.cancel()
        modernTranslateJob?.cancel()
        super.onDestroyView()
    }

    fun compressExplore() {
        if (expandedSourceUrl != null) {
            expandedSourceUrl = null
        } else {
            scrollToTopToken++
        }
    }
}
