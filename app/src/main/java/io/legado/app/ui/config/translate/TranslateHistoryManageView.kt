package io.legado.app.ui.config.translate

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.help.translate.BookTitleTranslator
import io.legado.app.help.translate.TranslateHistoryStore
import io.legado.app.help.translate.TranslatedBookHistory
import io.legado.app.help.translate.TranslatedChapterHistory
import io.legado.app.ui.design.components.NgDialogVariant
import io.legado.app.ui.design.components.compose.NgDialog
import io.legado.app.ui.design.components.compose.NgDialogTextActionButton
import io.legado.app.ui.design.components.compose.NgExpandableActionMenu
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuItem
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuVariant
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckbox
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckboxVariant
import io.legado.app.ui.design.components.compose.NgFileSelectionTriStateCheckbox
import io.legado.app.ui.design.components.compose.NgFlatActionRail
import io.legado.app.ui.design.components.compose.NgFlatActionRailItem
import io.legado.app.ui.design.components.compose.NgFlatActionRailVariant
import io.legado.app.ui.design.components.compose.NgFloatingTitleToolbar
import io.legado.app.ui.design.components.compose.NgFloatingToolbarActionButton
import io.legado.app.ui.design.components.compose.NgFloatingToolbarBackButton
import io.legado.app.ui.design.components.compose.NgFormDensity
import io.legado.app.ui.design.components.compose.NgFormField
import io.legado.app.ui.design.components.compose.NgGlassDefaults
import io.legado.app.ui.design.components.compose.NgGlassSurface
import io.legado.app.ui.design.components.compose.NgMaterialRole
import io.legado.app.ui.design.components.compose.NgPopupToggleState
import io.legado.app.ui.design.components.compose.NgSearchBar
import io.legado.app.ui.design.components.compose.NgSearchBarVariant
import io.legado.app.ui.design.components.compose.ngSlideSelect
import io.legado.app.ui.design.components.compose.rememberNgLazySlideSelectState
import io.legado.app.ui.design.theme.NgTheme
import kotlin.math.roundToInt

private const val HIST_FILTER_ALL = 0x59000001
private const val HIST_FILTER_WITH_CHAPTERS = 0x59000002
private const val HIST_FILTER_TITLE_ONLY = 0x59000003
private const val HIST_FILTER_WITH_INTRO = 0x59000004

private const val HIST_SORT_PARENT = 0x59000100
private const val HIST_SORT_DESC = 0x59000101
private const val HIST_SORT_TIME = 0x59000102
private const val HIST_SORT_NAME = 0x59000103
private const val HIST_SORT_COUNT = 0x59000104

private const val HIST_MENU_CLEAR_CACHE = 0x59000201
private const val HIST_MENU_CLEAR_ALL = 0x59000202
private const val HIST_MENU_COPY_TRANSLATED = 0x59000203
private const val HIST_MENU_COPY_ORIGINAL = 0x59000204
private const val HIST_MENU_EDIT_BOOK = 0x59000205

private const val HIST_ROW_OPEN = 0x59000301
private const val HIST_ROW_COPY_TR = 0x59000302
private const val HIST_ROW_COPY_ORIG = 0x59000303
private const val HIST_ROW_DELETE = 0x59000304
private const val HIST_ROW_EDIT = 0x59000305

private const val HIST_DOCK_SELECT_ALL = 0x59000401
private const val HIST_DOCK_INVERT_SELECT = 0x59000402
private const val HIST_DOCK_CLEAR_SELECT = 0x59000403
private const val HIST_DOCK_CLEAR_CACHE = 0x59000404
private const val HIST_DOCK_CLEAR_ALL = 0x59000405

private enum class HistoryBookFilter {
    ALL,
    WITH_CHAPTERS,
    TITLE_ONLY,
    WITH_INTRO,
}

private enum class HistoryChapterFilter {
    ALL,
    WITH_BODY,
    TITLE_ONLY,
}

private enum class HistorySortMode {
    TIME,
    NAME,
    COUNT,
}

@Composable
internal fun TranslateHistoryManageTab(
    engineId: String,
    selectedBookKey: String?,
    selectedChapterIndex: Int?,
    onSelectBook: (String?) -> Unit,
    onSelectChapter: (Int?) -> Unit,
    onBackToRoot: () -> Unit,
    onToast: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allBooks by TranslateHistoryStore.booksFlow.collectAsState()
    val books = remember(allBooks, engineId) {
        allBooks.filter { it.engineId == engineId }
    }
    val selectedBook = remember(books, selectedBookKey) {
        selectedBookKey?.let { key -> books.firstOrNull { it.bookKey == key } }
    }
    val selectedChapter = remember(selectedBook, selectedChapterIndex) {
        selectedChapterIndex?.let { idx -> selectedBook?.chapters?.firstOrNull { it.chapterIndex == idx } }
    }

    when {
        selectedBook != null && selectedChapter != null -> {
            TranslateChapterRecordView(
                engineId = engineId,
                book = selectedBook,
                chapter = selectedChapter,
                onBack = { onSelectChapter(null) },
                onCopy = { text ->
                    onCopy(text)
                    onToast("已复制")
                },
                onToast = onToast,
                modifier = modifier,
            )
        }
        selectedBook != null -> {
            HistoryChapterManageScreen(
                engineId = engineId,
                book = selectedBook,
                onBack = { onSelectBook(null) },
                onClickChapter = { onSelectChapter(it.chapterIndex) },
                onCopy = { text ->
                    onCopy(text)
                    onToast("已复制")
                },
                onToast = onToast,
                modifier = modifier,
            )
        }
        else -> {
            HistoryBookManageScreen(
                engineId = engineId,
                books = books,
                onBack = onBackToRoot,
                onClickBook = { onSelectBook(it.bookKey) },
                onCopy = { text ->
                    onCopy(text)
                    onToast("已复制")
                },
                onToast = onToast,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun HistoryBookManageScreen(
    engineId: String,
    books: List<TranslatedBookHistory>,
    onBack: () -> Unit,
    onClickBook: (TranslatedBookHistory) -> Unit,
    onCopy: (String) -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(HistoryBookFilter.ALL) }
    var sortMode by remember { mutableStateOf(HistorySortMode.TIME) }
    var sortDescending by remember { mutableStateOf(true) }
    var selectedKeys by remember(engineId) { mutableStateOf<Set<String>>(emptySet()) }
    var editingBook by remember { mutableStateOf<TranslatedBookHistory?>(null) }

    val visibleBooks = remember(books, query, filter, sortMode, sortDescending) {
        val q = query.trim()
        val filtered = books.filter { book ->
            val matchFilter = when (filter) {
                HistoryBookFilter.ALL -> true
                HistoryBookFilter.WITH_CHAPTERS -> book.chapters.isNotEmpty()
                HistoryBookFilter.TITLE_ONLY -> book.chapters.isEmpty()
                HistoryBookFilter.WITH_INTRO -> book.translatedIntro.isNotBlank()
            }
            val matchQuery = q.isEmpty() ||
                book.displayTitle.contains(q, ignoreCase = true) ||
                book.originalTitle.contains(q, ignoreCase = true)
            matchFilter && matchQuery
        }
        val comparator = when (sortMode) {
            HistorySortMode.TIME -> compareBy<TranslatedBookHistory> { it.updatedAt }
            HistorySortMode.NAME -> compareBy<TranslatedBookHistory, String>(String.CASE_INSENSITIVE_ORDER) {
                it.displayTitle
            }
            HistorySortMode.COUNT -> compareBy<TranslatedBookHistory> { it.chapters.size }
                .thenBy { it.updatedAt }
        }
        val sorted = filtered.sortedWith(comparator)
        if (sortDescending) sorted.asReversed() else sorted
    }

    val visibleKeys = remember(visibleBooks) {
        visibleBooks.mapTo(LinkedHashSet()) { it.bookKey }
    }
    val effectiveSelectedKeys = remember(selectedKeys, visibleKeys) {
        selectedKeys.intersect(visibleKeys)
    }

    fun clearBookTitleCaches(targetBooks: Collection<TranslatedBookHistory>) {
        val titles = buildList(targetBooks.size * 2) {
            for (book in targetBooks) {
                add(book.originalTitle)
                add(book.originalIntro)
            }
        }
        BookTitleTranslator.clearCacheEntriesForEngine(engineId, titles)
    }

    fun deleteSingleBook(book: TranslatedBookHistory) {
        TranslateHistoryStore.deleteBook(book.bookKey, engineId = engineId)
        clearBookTitleCaches(listOf(book))
        selectedKeys = selectedKeys - book.bookKey
        onToast("已删除《${book.displayTitle}》的翻译记录")
    }

    fun deleteSelectedBooks() {
        val targets = visibleBooks.filter { it.bookKey in effectiveSelectedKeys }
        if (targets.isEmpty()) return
        TranslateHistoryStore.deleteBooks(targets.map { it.bookKey }, engineId = engineId)
        clearBookTitleCaches(targets)
        selectedKeys = selectedKeys - targets.mapTo(HashSet()) { it.bookKey }
        onToast("已删除 ${targets.size} 条翻译记录")
    }

    fun clearCacheOnly() {
        val count = BookTitleTranslator.cacheSizeForEngine(engineId)
        BookTitleTranslator.clearCacheForEngine(engineId)
        onToast(
            if (count > 0) "已清空本引擎 $count 条临时译名缓存"
            else "当前引擎暂无临时译名缓存"
        )
    }

    fun clearAllEngineHistory() {
        TranslateHistoryStore.clearForEngine(engineId)
        BookTitleTranslator.clearCacheForEngine(engineId)
        selectedKeys = emptySet()
        onToast("已清空本引擎全部翻译记录与缓存")
    }

    val filterItems = remember(filter) {
        listOf(
            NgExpandableActionMenuItem(
                itemId = HIST_FILTER_ALL,
                titleRes = 0,
                title = "全部记录",
                iconRes = R.drawable.ic_select_all,
                checked = filter == HistoryBookFilter.ALL,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_FILTER_WITH_CHAPTERS,
                titleRes = 0,
                title = "含正文章节译文",
                iconRes = R.drawable.ic_chapter_list,
                checked = filter == HistoryBookFilter.WITH_CHAPTERS,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_FILTER_WITH_INTRO,
                titleRes = 0,
                title = "含简介译文",
                iconRes = R.drawable.ic_translate,
                checked = filter == HistoryBookFilter.WITH_INTRO,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_FILTER_TITLE_ONLY,
                titleRes = 0,
                title = "仅书名/简介",
                iconRes = R.drawable.ic_check_circle_outline,
                checked = filter == HistoryBookFilter.TITLE_ONLY,
            ),
        )
    }

    val sortChildren = remember(sortMode, sortDescending) {
        listOf(
            NgExpandableActionMenuItem(
                itemId = HIST_SORT_DESC,
                titleRes = R.string.sort_desc,
                iconRes = R.drawable.ic_swap_vert,
                checked = sortDescending,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_SORT_TIME,
                titleRes = 0,
                title = "按最近更新",
                iconRes = R.drawable.ic_history,
                checked = sortMode == HistorySortMode.TIME,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_SORT_NAME,
                titleRes = R.string.sort_by_name,
                iconRes = R.drawable.ic_sort,
                checked = sortMode == HistorySortMode.NAME,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_SORT_COUNT,
                titleRes = 0,
                title = "按已译章节数",
                iconRes = R.drawable.ic_chapter_list,
                checked = sortMode == HistorySortMode.COUNT,
            ),
        )
    }

    val mainMenuItems = remember(sortChildren) {
        listOf(
            NgExpandableActionMenuItem(
                itemId = HIST_SORT_PARENT,
                titleRes = R.string.sort,
                iconRes = R.drawable.ic_sort,
                children = sortChildren,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_MENU_CLEAR_CACHE,
                titleRes = 0,
                title = "清空临时译名缓存",
                iconRes = R.drawable.ic_refresh_black_24dp,
                dividerBefore = true,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_MENU_CLEAR_ALL,
                titleRes = 0,
                title = "清空本引擎全部记录",
                iconRes = R.drawable.ic_book_info_delete,
                danger = true,
            ),
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        HistoryManageTopBar(
            query = query,
            hint = "搜索翻译后的书名或原名",
            filterActive = filter != HistoryBookFilter.ALL,
            filterItems = filterItems,
            menuItems = mainMenuItems,
            onQueryChange = { query = it },
            onBack = onBack,
            onFilterClick = { item ->
                filter = when (item.itemId) {
                    HIST_FILTER_WITH_CHAPTERS -> HistoryBookFilter.WITH_CHAPTERS
                    HIST_FILTER_WITH_INTRO -> HistoryBookFilter.WITH_INTRO
                    HIST_FILTER_TITLE_ONLY -> HistoryBookFilter.TITLE_ONLY
                    else -> HistoryBookFilter.ALL
                }
            },
            onMenuClick = { item ->
                when (item.itemId) {
                    HIST_SORT_DESC -> sortDescending = !sortDescending
                    HIST_SORT_TIME -> sortMode = HistorySortMode.TIME
                    HIST_SORT_NAME -> sortMode = HistorySortMode.NAME
                    HIST_SORT_COUNT -> sortMode = HistorySortMode.COUNT
                    HIST_MENU_CLEAR_CACHE -> clearCacheOnly()
                    HIST_MENU_CLEAR_ALL -> clearAllEngineHistory()
                }
            },
        )

        HistoryManageListPanel(
            isEmpty = visibleBooks.isEmpty(),
            emptyText = if (books.isEmpty()) {
                "当前引擎暂无离线翻译记录\n浏览发现页或开启正文翻译后会自动保存在这里"
            } else {
                "未找到匹配的翻译记录"
            },
            modifier = Modifier.weight(1f),
        ) {
            val listState = rememberLazyListState()
            val slideSelectState = rememberNgLazySlideSelectState(
                listState = listState,
                isSelected = { index ->
                    visibleBooks.getOrNull(index)?.bookKey?.let(effectiveSelectedKeys::contains) == true
                },
                onSelectionChange = { index, isSelected ->
                    visibleBooks.getOrNull(index)?.bookKey?.let { key ->
                        selectedKeys = if (isSelected) selectedKeys + key else selectedKeys - key
                    }
                },
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .ngSlideSelect(
                        state = slideSelectState,
                        enabled = visibleBooks.isNotEmpty(),
                        slideAreaStart = 0.dp,
                        slideAreaEnd = 36.dp,
                    ),
                state = listState,
            ) {
                itemsIndexed(
                    items = visibleBooks,
                    key = { _, book -> "${book.engineId}_${book.bookKey}" },
                ) { index, book ->
                    val rowMenuItems = remember(book.bookKey, book.originalTitle, book.displayTitle) {
                        buildList {
                            add(
                                NgExpandableActionMenuItem(
                                    itemId = HIST_ROW_OPEN,
                                    titleRes = 0,
                                    title = "查看章节记录",
                                    iconRes = R.drawable.ic_chapter_list,
                                )
                            )
                            add(
                                NgExpandableActionMenuItem(
                                    itemId = HIST_ROW_EDIT,
                                    titleRes = 0,
                                    title = "编辑译文",
                                    iconRes = R.drawable.ic_edit,
                                )
                            )
                            add(
                                NgExpandableActionMenuItem(
                                    itemId = HIST_ROW_COPY_TR,
                                    titleRes = 0,
                                    title = "复制译后书名",
                                    iconRes = R.drawable.ic_translate,
                                )
                            )
                            if (book.originalTitle.isNotBlank() && book.originalTitle != book.displayTitle) {
                                add(
                                    NgExpandableActionMenuItem(
                                        itemId = HIST_ROW_COPY_ORIG,
                                        titleRes = 0,
                                        title = "复制原书名",
                                        iconRes = R.drawable.ic_check,
                                    )
                                )
                            }
                            add(
                                NgExpandableActionMenuItem(
                                    itemId = HIST_ROW_DELETE,
                                    titleRes = R.string.delete,
                                    iconRes = R.drawable.ic_book_info_delete,
                                    dividerBefore = true,
                                    danger = true,
                                )
                            )
                        }
                    }
                    HistoryManageRow(
                        itemKey = "${book.engineId}_${book.bookKey}",
                        title = book.displayTitle,
                        subtitle = book.originalTitle.takeIf { it.isNotBlank() && it != book.displayTitle },
                        selected = book.bookKey in effectiveSelectedKeys,
                        showDivider = index < visibleBooks.lastIndex,
                        menuItems = rowMenuItems,
                        onSelectionChange = { checked ->
                            selectedKeys = if (checked) {
                                selectedKeys + book.bookKey
                            } else {
                                selectedKeys - book.bookKey
                            }
                        },
                        onClick = { onClickBook(book) },
                        onSwipeDelete = { deleteSingleBook(book) },
                        onMenuClick = { menuItem ->
                            when (menuItem.itemId) {
                                HIST_ROW_OPEN -> onClickBook(book)
                                HIST_ROW_EDIT -> editingBook = book
                                HIST_ROW_COPY_TR -> onCopy(book.displayTitle)
                                HIST_ROW_COPY_ORIG -> onCopy(book.originalTitle)
                                HIST_ROW_DELETE -> deleteSingleBook(book)
                            }
                        },
                        tags = {
                            if (book.chapters.isNotEmpty()) {
                                HistoryMiniTag(
                                    text = "${book.chapters.size}章",
                                    containerColor = Color(NgTheme.colors.primaryContainer),
                                    contentColor = Color(NgTheme.colors.primary),
                                )
                            }
                            if (book.translatedIntro.isNotBlank()) {
                                HistoryMiniTag(
                                    text = "简介",
                                    containerColor = colorResource(R.color.ng_info_container),
                                    contentColor = colorResource(R.color.ng_info),
                                )
                            }
                        },
                    )
                }
            }
        }

        val selectAllState = remember(visibleBooks, effectiveSelectedKeys) {
            when {
                visibleBooks.isEmpty() || effectiveSelectedKeys.isEmpty() -> ToggleableState.Off
                effectiveSelectedKeys.size == visibleBooks.size -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            }
        }
        HistoryManageBottomDock(
            selectedCount = effectiveSelectedKeys.size,
            totalCount = visibleBooks.size,
            selectAllState = selectAllState,
            moreMenuItems = listOf(
                NgExpandableActionMenuItem(
                    itemId = HIST_DOCK_SELECT_ALL,
                    titleRes = R.string.select_all,
                    iconRes = R.drawable.ic_select_all,
                ),
                NgExpandableActionMenuItem(
                    itemId = HIST_DOCK_INVERT_SELECT,
                    titleRes = 0,
                    title = "反选",
                    iconRes = R.drawable.ic_swap_vert,
                ),
                NgExpandableActionMenuItem(
                    itemId = HIST_DOCK_CLEAR_SELECT,
                    titleRes = 0,
                    title = "取消选择",
                    iconRes = R.drawable.ic_clear,
                ),
                NgExpandableActionMenuItem(
                    itemId = HIST_DOCK_CLEAR_CACHE,
                    titleRes = 0,
                    title = "清空临时译名缓存",
                    iconRes = R.drawable.ic_refresh_black_24dp,
                    dividerBefore = true,
                ),
                NgExpandableActionMenuItem(
                    itemId = HIST_DOCK_CLEAR_ALL,
                    titleRes = 0,
                    title = "清空本引擎全部记录",
                    iconRes = R.drawable.ic_book_info_delete,
                    danger = true,
                ),
            ),
            onToggleSelectAll = {
                selectedKeys = if (selectAllState == ToggleableState.On) {
                    selectedKeys - visibleKeys
                } else {
                    selectedKeys + visibleKeys
                }
            },
            onDeleteSelected = { deleteSelectedBooks() },
            onMoreItemClick = { item ->
                when (item.itemId) {
                    HIST_DOCK_SELECT_ALL -> selectedKeys = selectedKeys + visibleKeys
                    HIST_DOCK_INVERT_SELECT -> {
                        val flipped = visibleKeys - effectiveSelectedKeys
                        selectedKeys = (selectedKeys - visibleKeys) + flipped
                    }
                    HIST_DOCK_CLEAR_SELECT -> selectedKeys = selectedKeys - visibleKeys
                    HIST_DOCK_CLEAR_CACHE -> clearCacheOnly()
                    HIST_DOCK_CLEAR_ALL -> clearAllEngineHistory()
                }
            },
        )
    }

    editingBook?.let { target ->
        HistoryBookEditDialog(
            book = target,
            onDismiss = { editingBook = null },
            onCopyOriginal = { text -> onCopy(text) },
            onSave = { newTitle, newIntro ->
                TranslateHistoryStore.updateBookTranslation(
                    bookKey = target.bookKey,
                    engineId = engineId,
                    newTranslatedTitle = newTitle,
                    newTranslatedIntro = newIntro,
                )
                editingBook = null
                onToast("已保存译文修改")
            },
        )
    }
}

@Composable
private fun HistoryChapterManageScreen(
    engineId: String,
    book: TranslatedBookHistory,
    onBack: () -> Unit,
    onClickChapter: (TranslatedChapterHistory) -> Unit,
    onCopy: (String) -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(HistoryChapterFilter.ALL) }
    var sortMode by remember { mutableStateOf(HistorySortMode.COUNT) }
    var sortDescending by remember { mutableStateOf(false) }
    var selectedIndices by remember(book.bookKey, engineId) { mutableStateOf<Set<Int>>(emptySet()) }

    val visibleChapters = remember(book.chapters, query, filter, sortMode, sortDescending) {
        val q = query.trim()
        val filtered = book.chapters.filter { ch ->
            val matchFilter = when (filter) {
                HistoryChapterFilter.ALL -> true
                HistoryChapterFilter.WITH_BODY -> ch.paragraphs.isNotEmpty()
                HistoryChapterFilter.TITLE_ONLY -> ch.paragraphs.isEmpty()
            }
            val matchQuery = q.isEmpty() ||
                ch.displayChapterTitle.contains(q, ignoreCase = true) ||
                ch.originalChapterTitle.contains(q, ignoreCase = true)
            matchFilter && matchQuery
        }
        val comparator = when (sortMode) {
            HistorySortMode.TIME -> compareBy<TranslatedChapterHistory> { it.updatedAt }
            HistorySortMode.NAME -> compareBy<TranslatedChapterHistory, String>(String.CASE_INSENSITIVE_ORDER) {
                it.displayChapterTitle
            }
            HistorySortMode.COUNT -> compareBy<TranslatedChapterHistory> { it.chapterIndex }
        }
        val sorted = filtered.sortedWith(comparator)
        if (sortDescending) sorted.asReversed() else sorted
    }

    val visibleIndexSet = remember(visibleChapters) {
        visibleChapters.mapTo(LinkedHashSet()) { it.chapterIndex }
    }
    val effectiveSelected = remember(selectedIndices, visibleIndexSet) {
        selectedIndices.intersect(visibleIndexSet)
    }

    fun clearChapterTitleCache(chapters: Collection<TranslatedChapterHistory>) {
        BookTitleTranslator.clearCacheEntriesForEngine(
            engineId,
            chapters.map { it.originalChapterTitle },
        )
    }

    fun deleteSingleChapter(chapter: TranslatedChapterHistory) {
        TranslateHistoryStore.deleteChapter(book.bookKey, chapter.chapterIndex, engineId = engineId)
        clearChapterTitleCache(listOf(chapter))
        selectedIndices = selectedIndices - chapter.chapterIndex
        onToast("已删除章节翻译记录")
    }

    fun deleteSelectedChapters() {
        val targets = visibleChapters.filter { it.chapterIndex in effectiveSelected }
        if (targets.isEmpty()) return
        TranslateHistoryStore.deleteChapters(
            bookKey = book.bookKey,
            chapterIndices = targets.map { it.chapterIndex },
            engineId = engineId,
        )
        clearChapterTitleCache(targets)
        selectedIndices = selectedIndices - targets.mapTo(HashSet()) { it.chapterIndex }
        onToast("已删除 ${targets.size} 条章节记录")
    }

    val filterItems = remember(filter) {
        listOf(
            NgExpandableActionMenuItem(
                itemId = HIST_FILTER_ALL,
                titleRes = R.string.all,
                iconRes = R.drawable.ic_select_all,
                checked = filter == HistoryChapterFilter.ALL,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_FILTER_WITH_CHAPTERS,
                titleRes = 0,
                title = "含正文译文",
                iconRes = R.drawable.ic_chapter_list,
                checked = filter == HistoryChapterFilter.WITH_BODY,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_FILTER_TITLE_ONLY,
                titleRes = 0,
                title = "仅目录标题",
                iconRes = R.drawable.ic_translate,
                checked = filter == HistoryChapterFilter.TITLE_ONLY,
            ),
        )
    }

    val sortChildren = remember(sortMode, sortDescending) {
        listOf(
            NgExpandableActionMenuItem(
                itemId = HIST_SORT_DESC,
                titleRes = R.string.sort_desc,
                iconRes = R.drawable.ic_swap_vert,
                checked = sortDescending,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_SORT_COUNT,
                titleRes = 0,
                title = "按章节序号",
                iconRes = R.drawable.ic_chapter_list,
                checked = sortMode == HistorySortMode.COUNT,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_SORT_TIME,
                titleRes = 0,
                title = "按更新时间",
                iconRes = R.drawable.ic_history,
                checked = sortMode == HistorySortMode.TIME,
            ),
            NgExpandableActionMenuItem(
                itemId = HIST_SORT_NAME,
                titleRes = R.string.sort_by_name,
                iconRes = R.drawable.ic_sort,
                checked = sortMode == HistorySortMode.NAME,
            ),
        )
    }

    var editingBookInfo by remember(book.bookKey, engineId) { mutableStateOf(false) }
    var editingChapter by remember { mutableStateOf<TranslatedChapterHistory?>(null) }

    val menuItems = remember(sortChildren, book.originalTitle) {
        buildList {
            add(
                NgExpandableActionMenuItem(
                    itemId = HIST_SORT_PARENT,
                    titleRes = R.string.sort,
                    iconRes = R.drawable.ic_sort,
                    children = sortChildren,
                )
            )
            add(
                NgExpandableActionMenuItem(
                    itemId = HIST_MENU_EDIT_BOOK,
                    titleRes = 0,
                    title = "编辑书名与简介译文",
                    iconRes = R.drawable.ic_edit,
                    dividerBefore = true,
                )
            )
            add(
                NgExpandableActionMenuItem(
                    itemId = HIST_MENU_COPY_TRANSLATED,
                    titleRes = 0,
                    title = "复制译后书名",
                    iconRes = R.drawable.ic_translate,
                )
            )
            if (book.originalTitle.isNotBlank()) {
                add(
                    NgExpandableActionMenuItem(
                        itemId = HIST_MENU_COPY_ORIGINAL,
                        titleRes = 0,
                        title = "复制原书名",
                        iconRes = R.drawable.ic_check,
                    )
                )
            }
            add(
                NgExpandableActionMenuItem(
                    itemId = HIST_MENU_CLEAR_ALL,
                    titleRes = 0,
                    title = "删除本书全部记录",
                    iconRes = R.drawable.ic_book_info_delete,
                    dividerBefore = true,
                    danger = true,
                )
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        HistoryManageTopBar(
            query = query,
            hint = "在《${book.displayTitle}》中搜索章节",
            filterActive = filter != HistoryChapterFilter.ALL,
            filterItems = filterItems,
            menuItems = menuItems,
            onQueryChange = { query = it },
            onBack = onBack,
            onFilterClick = { item ->
                filter = when (item.itemId) {
                    HIST_FILTER_WITH_CHAPTERS -> HistoryChapterFilter.WITH_BODY
                    HIST_FILTER_TITLE_ONLY -> HistoryChapterFilter.TITLE_ONLY
                    else -> HistoryChapterFilter.ALL
                }
            },
            onMenuClick = { item ->
                when (item.itemId) {
                    HIST_SORT_DESC -> sortDescending = !sortDescending
                    HIST_SORT_COUNT -> sortMode = HistorySortMode.COUNT
                    HIST_SORT_TIME -> sortMode = HistorySortMode.TIME
                    HIST_SORT_NAME -> sortMode = HistorySortMode.NAME
                    HIST_MENU_EDIT_BOOK -> editingBookInfo = true
                    HIST_MENU_COPY_TRANSLATED -> onCopy(book.displayTitle)
                    HIST_MENU_COPY_ORIGINAL -> onCopy(book.originalTitle)
                    HIST_MENU_CLEAR_ALL -> {
                        TranslateHistoryStore.deleteBook(book.bookKey, engineId = engineId)
                        BookTitleTranslator.clearCacheEntriesForEngine(
                            engineId,
                            listOf(book.originalTitle, book.originalIntro) + book.chapters.map { it.originalChapterTitle },
                        )
                        onToast("已删除《${book.displayTitle}》的翻译记录")
                        onBack()
                    }
                }
            },
        )

        if (book.translatedIntro.isNotBlank()) {
            NgGlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 2.dp),
                role = NgMaterialRole.CONTENT,
                shape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp),
                style = NgGlassDefaults.bookDetailStyle(
                    containerColor = colorResource(R.color.ng_surface_card)
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCopy(book.translatedIntro) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "简介译文（点击复制）",
                            color = Color(NgTheme.colors.primary),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "编辑",
                            color = Color(NgTheme.colors.primary),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { editingBookInfo = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = book.translatedIntro,
                        color = Color(NgTheme.colors.onSurface),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        HistoryManageListPanel(
            isEmpty = visibleChapters.isEmpty(),
            emptyText = if (book.chapters.isEmpty()) {
                "本书暂无正文翻译目录\n阅读正文时点击顶栏翻译图标即可离线保存每章译文"
            } else {
                "未找到匹配的章节"
            },
            modifier = Modifier.weight(1f),
        ) {
            val listState = rememberLazyListState()
            val slideSelectState = rememberNgLazySlideSelectState(
                listState = listState,
                isSelected = { index ->
                    visibleChapters.getOrNull(index)?.chapterIndex?.let(effectiveSelected::contains) == true
                },
                onSelectionChange = { index, isSelected ->
                    visibleChapters.getOrNull(index)?.chapterIndex?.let { chIdx ->
                        selectedIndices = if (isSelected) selectedIndices + chIdx else selectedIndices - chIdx
                    }
                },
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .ngSlideSelect(
                        state = slideSelectState,
                        enabled = visibleChapters.isNotEmpty(),
                        slideAreaStart = 0.dp,
                        slideAreaEnd = 36.dp,
                    ),
                state = listState,
            ) {
                itemsIndexed(
                    items = visibleChapters,
                    key = { _, ch -> "ch_${book.bookKey}_${ch.chapterIndex}" },
                ) { index, chapter ->
                    val chTitle = chapter.displayChapterTitle.ifBlank { "第 ${chapter.chapterIndex + 1} 章" }
                    val rowMenuItems = remember(chapter.chapterIndex, chapter.originalChapterTitle, chTitle) {
                        buildList {
                            add(
                                NgExpandableActionMenuItem(
                                    itemId = HIST_ROW_OPEN,
                                    titleRes = 0,
                                    title = "查看双语对照",
                                    iconRes = R.drawable.ic_chapter_list,
                                )
                            )
                            add(
                                NgExpandableActionMenuItem(
                                    itemId = HIST_ROW_EDIT,
                                    titleRes = 0,
                                    title = "编辑章节译名",
                                    iconRes = R.drawable.ic_edit,
                                )
                            )
                            add(
                                NgExpandableActionMenuItem(
                                    itemId = HIST_ROW_COPY_TR,
                                    titleRes = 0,
                                    title = "复制章节译名",
                                    iconRes = R.drawable.ic_translate,
                                )
                            )
                            if (chapter.originalChapterTitle.isNotBlank() &&
                                chapter.originalChapterTitle != chTitle
                            ) {
                                add(
                                    NgExpandableActionMenuItem(
                                        itemId = HIST_ROW_COPY_ORIG,
                                        titleRes = 0,
                                        title = "复制原章节名",
                                        iconRes = R.drawable.ic_check,
                                    )
                                )
                            }
                            add(
                                NgExpandableActionMenuItem(
                                    itemId = HIST_ROW_DELETE,
                                    titleRes = R.string.delete,
                                    iconRes = R.drawable.ic_book_info_delete,
                                    dividerBefore = true,
                                    danger = true,
                                )
                            )
                        }
                    }
                    HistoryManageRow(
                        itemKey = "ch_${book.bookKey}_${chapter.chapterIndex}",
                        title = chTitle,
                        subtitle = chapter.originalChapterTitle.takeIf { it.isNotBlank() && it != chTitle },
                        selected = chapter.chapterIndex in effectiveSelected,
                        showDivider = index < visibleChapters.lastIndex,
                        menuItems = rowMenuItems,
                        onSelectionChange = { checked ->
                            selectedIndices = if (checked) {
                                selectedIndices + chapter.chapterIndex
                            } else {
                                selectedIndices - chapter.chapterIndex
                            }
                        },
                        onClick = { onClickChapter(chapter) },
                        onSwipeDelete = { deleteSingleChapter(chapter) },
                        onMenuClick = { menuItem ->
                            when (menuItem.itemId) {
                                HIST_ROW_OPEN -> onClickChapter(chapter)
                                HIST_ROW_EDIT -> editingChapter = chapter
                                HIST_ROW_COPY_TR -> onCopy(chTitle)
                                HIST_ROW_COPY_ORIG -> onCopy(chapter.originalChapterTitle)
                                HIST_ROW_DELETE -> deleteSingleChapter(chapter)
                            }
                        },
                        tags = {
                            if (chapter.paragraphs.isNotEmpty()) {
                                HistoryMiniTag(
                                    text = "${chapter.paragraphs.size}段",
                                    containerColor = Color(NgTheme.colors.primaryContainer),
                                    contentColor = Color(NgTheme.colors.primary),
                                )
                            } else {
                                HistoryMiniTag(
                                    text = "仅标题",
                                    containerColor = colorResource(R.color.ng_info_container),
                                    contentColor = colorResource(R.color.ng_info),
                                )
                            }
                        },
                    )
                }
            }
        }

        val selectAllState = remember(visibleChapters, effectiveSelected) {
            when {
                visibleChapters.isEmpty() || effectiveSelected.isEmpty() -> ToggleableState.Off
                effectiveSelected.size == visibleChapters.size -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            }
        }
        HistoryManageBottomDock(
            selectedCount = effectiveSelected.size,
            totalCount = visibleChapters.size,
            selectAllState = selectAllState,
            moreMenuItems = listOf(
                NgExpandableActionMenuItem(
                    itemId = HIST_DOCK_SELECT_ALL,
                    titleRes = R.string.select_all,
                    iconRes = R.drawable.ic_select_all,
                ),
                NgExpandableActionMenuItem(
                    itemId = HIST_DOCK_INVERT_SELECT,
                    titleRes = 0,
                    title = "反选",
                    iconRes = R.drawable.ic_swap_vert,
                ),
                NgExpandableActionMenuItem(
                    itemId = HIST_DOCK_CLEAR_SELECT,
                    titleRes = 0,
                    title = "取消选择",
                    iconRes = R.drawable.ic_clear,
                ),
            ),
            onToggleSelectAll = {
                selectedIndices = if (selectAllState == ToggleableState.On) {
                    selectedIndices - visibleIndexSet
                } else {
                    selectedIndices + visibleIndexSet
                }
            },
            onDeleteSelected = { deleteSelectedChapters() },
            onMoreItemClick = { item ->
                when (item.itemId) {
                    HIST_DOCK_SELECT_ALL -> selectedIndices = selectedIndices + visibleIndexSet
                    HIST_DOCK_INVERT_SELECT -> {
                        val flipped = visibleIndexSet - effectiveSelected
                        selectedIndices = (selectedIndices - visibleIndexSet) + flipped
                    }
                    HIST_DOCK_CLEAR_SELECT -> selectedIndices = selectedIndices - visibleIndexSet
                }
            },
        )
    }

    if (editingBookInfo) {
        HistoryBookEditDialog(
            book = book,
            onDismiss = { editingBookInfo = false },
            onCopyOriginal = { text -> onCopy(text) },
            onSave = { newTitle, newIntro ->
                TranslateHistoryStore.updateBookTranslation(
                    bookKey = book.bookKey,
                    engineId = engineId,
                    newTranslatedTitle = newTitle,
                    newTranslatedIntro = newIntro,
                )
                editingBookInfo = false
                onToast("已保存译文修改")
            },
        )
    }

    editingChapter?.let { ch ->
        HistorySingleTextEditDialog(
            title = "编辑章节译名",
            originalLabel = "原章节名（第 ${ch.chapterIndex + 1} 章）",
            originalText = ch.originalChapterTitle,
            initialTranslated = ch.translatedChapterTitle.ifBlank { ch.displayChapterTitle },
            singleLine = true,
            onDismiss = { editingChapter = null },
            onCopyOriginal = {
                if (ch.originalChapterTitle.isNotBlank()) onCopy(ch.originalChapterTitle)
            },
            onSave = { newTitle ->
                TranslateHistoryStore.updateChapterTitle(
                    bookKey = book.bookKey,
                    engineId = engineId,
                    chapterIndex = ch.chapterIndex,
                    newTranslatedChapterTitle = newTitle,
                )
                editingChapter = null
                onToast("已保存章节译名")
            },
        )
    }
}

@Composable
private fun HistoryManageTopBar(
    query: String,
    hint: String,
    filterActive: Boolean,
    filterItems: List<NgExpandableActionMenuItem>,
    menuItems: List<NgExpandableActionMenuItem>,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onFilterClick: (NgExpandableActionMenuItem) -> Unit,
    onMenuClick: (NgExpandableActionMenuItem) -> Unit,
) {
    val filterMenuState = remember { NgPopupToggleState() }
    val mainMenuState = remember { NgPopupToggleState() }
    val actionColor = colorResource(R.color.ng_search_icon)
    val activeActionColor = Color(NgTheme.colors.primary)

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
            NgFloatingToolbarBackButton(
                onClick = onBack,
                width = 32.dp,
            )
            Spacer(Modifier.width(12.dp))
            NgSearchBar(
                query = query,
                onQueryChange = onQueryChange,
                hint = hint,
                modifier = Modifier.weight(1f),
                variant = NgSearchBarVariant.TOOLBAR,
                containerColor = Color.Transparent,
                hideHintOnFocus = true,
            )
            Spacer(Modifier.width(8.dp))
            HistoryTopBarPopupButton(
                iconRes = R.drawable.ic_screen,
                iconSize = 14.dp,
                contentDescription = stringResource(R.string.screen),
                tint = if (filterActive) activeActionColor else actionColor,
                state = filterMenuState,
                items = filterItems,
                onItemClick = onFilterClick,
            )
            Spacer(Modifier.width(2.dp))
            HistoryTopBarPopupButton(
                iconRes = R.drawable.ic_grid_menu,
                iconSize = 20.dp,
                contentDescription = stringResource(R.string.menu),
                tint = actionColor,
                state = mainMenuState,
                items = menuItems,
                onItemClick = onMenuClick,
            )
        }
    }
}

@Composable
private fun HistoryTopBarPopupButton(
    iconRes: Int,
    iconSize: Dp,
    contentDescription: String,
    tint: Color,
    state: NgPopupToggleState,
    items: List<NgExpandableActionMenuItem>,
    onItemClick: (NgExpandableActionMenuItem) -> Unit,
) {
    Box {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = state::onAnchorClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
        NgExpandableActionMenu(
            expanded = state.expanded,
            onDismissRequest = state::onDismissRequest,
            items = items,
            variant = NgExpandableActionMenuVariant.SIDE_SLIDE,
            menuContainerColor = colorResource(R.color.ng_surface_card),
            properties = PopupProperties(focusable = true, clippingEnabled = false),
            onItemClick = { item ->
                state.close()
                onItemClick(item)
            },
        )
    }
}

@Composable
private fun HistoryManageListPanel(
    isEmpty: Boolean,
    emptyText: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val panelShape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp)
    val panelStyle = NgGlassDefaults.bookDetailStyle(
        containerColor = colorResource(R.color.ng_surface_card)
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 8.dp),
    ) {
        NgGlassSurface(
            modifier = Modifier.fillMaxSize(),
            role = NgMaterialRole.CONTENT,
            shape = panelShape,
            style = panelStyle,
            liquidCornerRadius = NgTheme.shapes.mediumDp.dp,
        ) {
            if (isEmpty) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emptyText,
                        color = Color(NgTheme.colors.onSurfaceVariant),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            } else {
                content()
            }
        }
    }
}

@Composable
private fun HistoryManageRow(
    itemKey: String,
    title: String,
    subtitle: String?,
    selected: Boolean,
    showDivider: Boolean,
    menuItems: List<NgExpandableActionMenuItem>,
    onSelectionChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onSwipeDelete: () -> Unit,
    onMenuClick: (NgExpandableActionMenuItem) -> Unit,
    tags: @Composable () -> Unit,
) {
    val menuState = remember(itemKey) { NgPopupToggleState() }
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var swipeOffsetPx by remember(itemKey) { mutableFloatStateOf(0f) }
    val swipeDeleteThreshold = rowWidthPx / 3f
    val reachedDeleteThreshold = rowWidthPx > 0 && -swipeOffsetPx >= swipeDeleteThreshold
    val dragState = rememberDraggableState { delta ->
        val maxSwipe = if (rowWidthPx > 0) rowWidthPx * 0.45f else 360f
        swipeOffsetPx = (swipeOffsetPx + delta).coerceIn(-maxSwipe, 0f)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (subtitle != null) 58.dp else 52.dp)
                .onSizeChanged { rowWidthPx = it.width }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        if (rowWidthPx > 0 && -swipeOffsetPx >= rowWidthPx / 3f) {
                            swipeOffsetPx = 0f
                            onSwipeDelete()
                        } else {
                            swipeOffsetPx = 0f
                        }
                    },
                ),
        ) {
            if (swipeOffsetPx < -1f) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            colorResource(R.color.ng_error).copy(
                                alpha = if (reachedDeleteThreshold) 1f else 0.78f
                            )
                        )
                        .padding(end = 18.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_book_info_delete),
                        contentDescription = stringResource(R.string.delete),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.delete),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .offset { IntOffset(swipeOffsetPx.roundToInt(), 0) }
                    .fillMaxSize()
                    .background(
                        if (swipeOffsetPx < -1f) {
                            colorResource(R.color.ng_surface_card)
                        } else {
                            Color.Transparent
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NgFileSelectionCheckbox(
                    checked = selected,
                    onCheckedChange = onSelectionChange,
                    variant = NgFileSelectionCheckboxVariant.COMPACT,
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(onClick = onClick)
                        .padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = title,
                            color = Color(NgTheme.colors.onSurface),
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = subtitle,
                                color = Color(NgTheme.colors.onSurfaceVariant),
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags()
                    }
                }
                Box {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (menuState.expanded) {
                                    Color(NgTheme.colors.selectedContainer)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { menuState.onAnchorClick() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.menu),
                            tint = if (menuState.expanded) {
                                Color(NgTheme.colors.primary)
                            } else {
                                Color(NgTheme.colors.onSurface)
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    NgExpandableActionMenu(
                        expanded = menuState.expanded,
                        onDismissRequest = menuState::onDismissRequest,
                        items = menuItems,
                        variant = NgExpandableActionMenuVariant.SIDE_SLIDE,
                        menuContainerColor = colorResource(R.color.ng_surface_card),
                        properties = PopupProperties(focusable = true, clippingEnabled = false),
                        onItemClick = { item ->
                            menuState.close()
                            onMenuClick(item)
                        },
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 48.dp, end = 12.dp),
                thickness = 0.6.dp,
                color = Color(NgTheme.colors.outlineVariant).copy(alpha = 0.24f),
            )
        }
    }
}

@Composable
private fun HistoryManageBottomDock(
    selectedCount: Int,
    totalCount: Int,
    selectAllState: ToggleableState,
    moreMenuItems: List<NgExpandableActionMenuItem>,
    onToggleSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onMoreItemClick: (NgExpandableActionMenuItem) -> Unit,
) {
    val menuState = remember { NgPopupToggleState() }
    NgGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 6.dp),
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
            NgFileSelectionTriStateCheckbox(
                state = selectAllState,
                onClick = onToggleSelectAll,
                enabled = totalCount > 0,
                variant = NgFileSelectionCheckboxVariant.COMPACT,
            )
            Text(
                text = "已选 $selectedCount / $totalCount",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                color = Color(NgTheme.colors.onSurface),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(modifier = Modifier.width(156.dp)) {
                NgFlatActionRail(
                    items = listOf(
                        NgFlatActionRailItem(
                            iconRes = R.drawable.ic_book_info_delete,
                            label = stringResource(R.string.delete),
                            enabled = selectedCount > 0,
                            dangerIcon = true,
                            dangerLabel = true,
                        ),
                        NgFlatActionRailItem(
                            iconRes = R.drawable.ic_more_horiz,
                            label = stringResource(R.string.more),
                            enabled = true,
                            emphasized = menuState.expanded,
                        ),
                    ),
                    onItemClick = { index ->
                        when (index) {
                            0 -> if (selectedCount > 0) onDeleteSelected()
                            else -> menuState.onAnchorClick()
                        }
                    },
                    variant = NgFlatActionRailVariant.INLINE_DIVIDED,
                    trailingOverlay = {
                        NgExpandableActionMenu(
                            expanded = menuState.expanded,
                            onDismissRequest = menuState::onDismissRequest,
                            items = moreMenuItems,
                            onItemClick = { item ->
                                menuState.close()
                                onMoreItemClick(item)
                            },
                            width = 178.dp,
                            rowMinHeight = 36.dp,
                            bottomPointerHeight = 8.dp,
                            bottomPointerWidth = 18.dp,
                            bottomPointerEndOffset = 34.dp,
                            menuContainerColor = colorResource(R.color.ng_surface_card),
                            offset = DpOffset(x = (-18).dp, y = (-12).dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun HistoryMiniTag(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(shape = RoundedCornerShape(5.dp), color = containerColor) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            color = contentColor,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranslateChapterRecordView(
    engineId: String,
    book: TranslatedBookHistory,
    chapter: TranslatedChapterHistory,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bilingual by remember { mutableStateOf(true) }
    var editingChapterTitle by remember { mutableStateOf(false) }
    var editingParagraphIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        NgFloatingTitleToolbar(
            title = chapter.displayChapterTitle.ifBlank { "第 ${chapter.chapterIndex + 1} 章" },
            onBack = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    NgFloatingToolbarActionButton(
                        iconRes = R.drawable.ic_edit,
                        contentDescription = "编辑章节译名",
                        onClick = { editingChapterTitle = true },
                    )
                    NgFloatingToolbarActionButton(
                        iconRes = R.drawable.ic_translate,
                        contentDescription = if (bilingual) "仅看译文" else "双语对照",
                        onClick = { bilingual = !bilingual },
                    )
                }
            },
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "ch_header") {
                Text(
                    text = "${book.displayTitle} · ${if (bilingual) "双语对照" else "仅译文"}（点击段落编辑，长按复制）",
                    color = Color(NgTheme.colors.onSurfaceVariant),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            itemsIndexed(
                items = chapter.paragraphs,
                key = { idx, _ -> "para_${chapter.chapterIndex}_$idx" },
            ) { index, pair ->
                val shape = RoundedCornerShape(10.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(colorResource(R.color.ng_translucent_management_card_surface))
                        .combinedClickable(
                            onClick = { editingParagraphIndex = index },
                            onLongClick = { onCopy(pair.translated) },
                        )
                        .padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pair.translated,
                            color = Color(NgTheme.colors.onSurface),
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                        )
                        if (bilingual && pair.original.isNotBlank() && pair.original != pair.translated) {
                            Spacer(Modifier.height(5.dp))
                            Text(
                                text = pair.original,
                                color = Color(NgTheme.colors.onSurfaceVariant),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = "编辑段落译文",
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(16.dp),
                        tint = Color(NgTheme.colors.onSurfaceVariant),
                    )
                }
            }
        }
    }

    if (editingChapterTitle) {
        HistorySingleTextEditDialog(
            title = "编辑章节译名",
            originalLabel = "原章节名（第 ${chapter.chapterIndex + 1} 章）",
            originalText = chapter.originalChapterTitle,
            initialTranslated = chapter.translatedChapterTitle.ifBlank { chapter.displayChapterTitle },
            singleLine = true,
            onDismiss = { editingChapterTitle = false },
            onCopyOriginal = {
                if (chapter.originalChapterTitle.isNotBlank()) onCopy(chapter.originalChapterTitle)
            },
            onSave = { newTitle ->
                TranslateHistoryStore.updateChapterTitle(
                    bookKey = book.bookKey,
                    engineId = engineId,
                    chapterIndex = chapter.chapterIndex,
                    newTranslatedChapterTitle = newTitle,
                )
                editingChapterTitle = false
                onToast("已保存章节译名")
            },
        )
    }

    editingParagraphIndex?.let { targetIndex ->
        val targetPair = chapter.paragraphs.getOrNull(targetIndex)
        if (targetPair != null) {
            HistorySingleTextEditDialog(
                title = "编辑段落译文",
                originalLabel = "原文（第 ${targetIndex + 1} 段）",
                originalText = targetPair.original,
                initialTranslated = targetPair.translated,
                singleLine = false,
                onDismiss = { editingParagraphIndex = null },
                onCopyOriginal = {
                    if (targetPair.original.isNotBlank()) onCopy(targetPair.original)
                },
                onSave = { newTranslated ->
                    TranslateHistoryStore.updateChapterParagraph(
                        bookKey = book.bookKey,
                        engineId = engineId,
                        chapterIndex = chapter.chapterIndex,
                        paragraphIndex = targetIndex,
                        newTranslatedText = newTranslated,
                    )
                    editingParagraphIndex = null
                    onToast("已保存段落译文")
                },
            )
        }
    }
}

@Composable
private fun HistoryBookEditDialog(
    book: TranslatedBookHistory,
    onDismiss: () -> Unit,
    onCopyOriginal: (String) -> Unit,
    onSave: (newTitle: String, newIntro: String?) -> Unit,
) {
    var titleText by remember(book.bookKey, book.translatedTitle) {
        mutableStateOf(book.translatedTitle.ifBlank { book.displayTitle })
    }
    val hasIntro = book.originalIntro.isNotBlank() || book.translatedIntro.isNotBlank()
    var introText by remember(book.bookKey, book.translatedIntro) {
        mutableStateOf(book.translatedIntro)
    }
    val canSave = titleText.isNotBlank() || (hasIntro && introText.isNotBlank())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NgDialog(
            title = "编辑书籍译文",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            variant = NgDialogVariant.FORM_EDITOR,
            actions = {
                NgDialogTextActionButton(
                    text = stringResource(R.string.cancel),
                    secondary = true,
                    onClick = onDismiss,
                )
                NgDialogTextActionButton(
                    text = stringResource(R.string.action_save),
                    enabled = canSave,
                    onClick = {
                        onSave(
                            titleText.trim(),
                            if (hasIntro) introText.trim() else null,
                        )
                    },
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (book.originalTitle.isNotBlank()) {
                    Text(
                        text = "原书名：${book.originalTitle}（点击复制）",
                        color = Color(NgTheme.colors.onSurfaceVariant),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onCopyOriginal(book.originalTitle) }
                            .padding(vertical = 2.dp),
                    )
                }
                NgFormField(
                    label = "译后书名",
                    value = titleText,
                    onValueChange = { titleText = it },
                    density = NgFormDensity.COMPACT,
                )
                if (hasIntro) {
                    if (book.originalIntro.isNotBlank()) {
                        Text(
                            text = "原简介（点击复制）：${book.originalIntro}",
                            color = Color(NgTheme.colors.onSurfaceVariant),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onCopyOriginal(book.originalIntro) }
                                .padding(vertical = 2.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "简介译文",
                            color = Color(NgTheme.colors.primary),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        HistoryMultilineEditorBox(
                            value = introText,
                            onValueChange = { introText = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySingleTextEditDialog(
    title: String,
    originalLabel: String,
    originalText: String,
    initialTranslated: String,
    singleLine: Boolean,
    onDismiss: () -> Unit,
    onCopyOriginal: () -> Unit,
    onSave: (String) -> Unit,
) {
    var translatedText by remember(originalText, initialTranslated) {
        mutableStateOf(initialTranslated)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NgDialog(
            title = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            variant = NgDialogVariant.FORM_EDITOR,
            actions = {
                NgDialogTextActionButton(
                    text = stringResource(R.string.cancel),
                    secondary = true,
                    onClick = onDismiss,
                )
                NgDialogTextActionButton(
                    text = stringResource(R.string.action_save),
                    enabled = translatedText.isNotBlank(),
                    onClick = { onSave(translatedText.trim()) },
                )
            },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (originalText.isNotBlank()) {
                    Text(
                        text = "$originalLabel：$originalText",
                        color = Color(NgTheme.colors.onSurfaceVariant),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = if (singleLine) 3 else 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onCopyOriginal)
                            .padding(vertical = 2.dp),
                    )
                }
                if (singleLine) {
                    NgFormField(
                        label = "译文",
                        value = translatedText,
                        onValueChange = { translatedText = it },
                        density = NgFormDensity.COMPACT,
                        autoFocus = true,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "译文内容",
                            color = Color(NgTheme.colors.primary),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        HistoryMultilineEditorBox(
                            value = translatedText,
                            onValueChange = { translatedText = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryMultilineEditorBox(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val editorShape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp, max = 200.dp)
            .clip(editorShape)
            .background(colorResource(R.color.ng_translucent_management_card_surface))
            .border(0.8.dp, Color(NgTheme.colors.outline), editorShape)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                fontFamily = NgTheme.fontFamily,
                color = Color(NgTheme.colors.onSurface),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            cursorBrush = SolidColor(Color(NgTheme.colors.primary)),
        )
    }
}
