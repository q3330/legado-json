package io.legado.app.ui.rss.source.manage

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.RssSourceChecker
import io.legado.app.help.source.RssSourceDedupe
import io.legado.app.help.source.RssSourceDedupeMode
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.book.manage.BookSourceExportSheet
import io.legado.app.ui.book.manage.BookSourceUploadDialog
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.rss.RssComposeBinding
import io.legado.app.ui.rss.source.debug.RssSourceDebugActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.utils.ACache
import io.legado.app.utils.CreateFileContract
import io.legado.app.utils.SelectFileContract
import io.legado.app.utils.cnCompare
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.takePersistableReadPermission
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

private enum class SourceInputRequest { IMPORT_ONLINE, ADD_GROUP, REMOVE_GROUP }

private sealed interface SourceDeleteRequest {
    data class One(val source: RssSource) : SourceDeleteRequest
    data class Selection(val sources: List<RssSource>) : SourceDeleteRequest
    data class Section(
        val groupName: String?,
        val title: String,
        val sources: List<RssSource>,
    ) : SourceDeleteRequest
}

private const val PREF_RSS_VIEW_MODE = "rssSourceViewMode"
private const val PREF_RSS_SORT = "rssSourceSort"
private const val PREF_RSS_SORT_ASC = "rssSourceSortAsc"
private const val PREF_RSS_DEDUPE_MODE = "rssSourceDedupeMode"

/** 订阅源管理。支持列表/分组视图、多维筛选与排序、去重树与自动禁用非主源、左滑删除与可达性校验。 */
class RssSourceActivity :
    VMBaseActivity<RssComposeBinding, RssSourceViewModel>() {

    companion object {
        const val EXTRA_OPEN_GROUP_VIEW = "openGroupView"
    }

    override val binding by viewBinding(RssComposeBinding::inflate)
    override val viewModel by viewModels<RssSourceViewModel>()

    private val importRecordKey = "rssSourceRecordKey"
    private var rawSources: List<RssSource> = emptyList()
    private var sources by mutableStateOf<List<RssSource>>(
        emptyList(),
        referentialEqualityPolicy(),
    )
    private var groups by mutableStateOf<List<String>>(emptyList())
    private var query by mutableStateOf("")
    private var selectedUrls by mutableStateOf<Set<String>>(emptySet())
    private var viewMode by mutableStateOf(RssSourceViewMode.LIST)
    private var sort by mutableStateOf(RssSourceSort.Default)
    private var sortAscending by mutableStateOf(true)
    private var expandedSections by mutableStateOf<Set<String>>(emptySet())
    private var dedupeMode by mutableStateOf(RssSourceDedupeMode.NONE)
    private var preferredPrimaries by mutableStateOf<Map<String, String>>(emptyMap())
    private var expandedDedupeKeys by mutableStateOf<Set<String>>(emptySet())
    private var inputRequest by mutableStateOf<SourceInputRequest?>(null)
    private var deleteRequest by mutableStateOf<SourceDeleteRequest?>(null)
    private var typeDialogVisible by mutableStateOf(false)
    private var clearGroupsConfirm by mutableStateOf(false)
    private var checkDialogVisible by mutableStateOf(false)
    private var sourceFlowJob: Job? = null

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportRssSourceDialog(it))
    }
    private val importDoc = registerForActivityResult(SelectFileContract()) { uri ->
        uri?.let {
            it.takePersistableReadPermission()
            showDialogFragment(ImportRssSourceDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(CreateFileContract()) {
        it.save(this, this) { toastOnUi(R.string.export_success) }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewMode = if (intent.getBooleanExtra(EXTRA_OPEN_GROUP_VIEW, false) ||
            getPrefInt(PREF_RSS_VIEW_MODE, 0) == 1
        ) {
            RssSourceViewMode.GROUP
        } else {
            RssSourceViewMode.LIST
        }
        sort = RssSourceSort.from(getPrefInt(PREF_RSS_SORT, 0))
        sortAscending = getPrefBoolean(PREF_RSS_SORT_ASC, true)
        dedupeMode = RssSourceDedupeMode.from(getPrefInt(PREF_RSS_DEDUPE_MODE, 0))
        preferredPrimaries = RssSourceDedupe.loadPreferredPrimaries(dedupeMode)
        if (dedupeMode != RssSourceDedupeMode.NONE) {
            viewModel.applyDedupeAutoDisable(dedupeMode, preferredPrimaries)
        }

        binding.root.setContent {
            NgAppTheme {
                val checkTaskState by RssSourceChecker.stateFlow.collectAsState()
                RssSourceManageScreen(
                    sources = sources,
                    groups = groups,
                    query = query,
                    selectedUrls = selectedUrls,
                    viewMode = viewMode,
                    sort = sort,
                    sortAscending = sortAscending,
                    expandedSections = expandedSections,
                    dedupeMode = dedupeMode,
                    preferredPrimaries = preferredPrimaries,
                    expandedDedupeKeys = expandedDedupeKeys,
                    checkTaskState = checkTaskState,
                    onAction = ::handleAction,
                )
                inputRequest?.let { request ->
                    val title = when (request) {
                        SourceInputRequest.IMPORT_ONLINE -> getString(R.string.import_on_line)
                        SourceInputRequest.ADD_GROUP -> getString(R.string.add_group)
                        SourceInputRequest.REMOVE_GROUP -> getString(R.string.remove_group)
                    }
                    RssSourceTextDialog(
                        title = title,
                        placeholder = if (request == SourceInputRequest.IMPORT_ONLINE) {
                            getString(R.string.rss_source_import_url_hint)
                        } else {
                            title
                        },
                        suggestions = if (request == SourceInputRequest.IMPORT_ONLINE) {
                            importHistory()
                        } else {
                            groups
                        },
                        onDismiss = { inputRequest = null },
                        onConfirm = { handleInput(request, it) },
                    )
                }
                if (typeDialogVisible) {
                    val selection = selectedSources()
                    RssSourceTypeDialog(
                        selectedCount = selection.size,
                        onDismiss = { typeDialogVisible = false },
                        onSelectType = { typeValue ->
                            viewModel.updateSelectionType(selection, typeValue)
                            typeDialogVisible = false
                        },
                    )
                }
                if (clearGroupsConfirm) {
                    val selection = selectedSources()
                    RssSourceConfirmDialog(
                        title = getString(R.string.clear_group),
                        message = "确定要清除已选 ${selection.size} 个订阅源的分组吗？",
                        onDismiss = { clearGroupsConfirm = false },
                        onConfirm = {
                            viewModel.clearSelectionGroups(selection)
                            clearGroupsConfirm = false
                        },
                    )
                }
                if (checkDialogVisible) {
                    val selection = selectedSources()
                    RssSourceCheckDialog(
                        selectedCount = selection.size,
                        onDismiss = { checkDialogVisible = false },
                        onConfirm = { options ->
                            checkDialogVisible = false
                            viewModel.checkSelection(selection, options)
                            startActivity<RssSourceCheckActivity>()
                        },
                    )
                }
                deleteRequest?.let { request ->
                    val message = when (request) {
                        is SourceDeleteRequest.One -> {
                            "${getString(R.string.sure_del)}\n${request.source.sourceName}"
                        }
                        is SourceDeleteRequest.Selection -> {
                            "${getString(R.string.sure_del)}\n${request.sources.size}"
                        }
                        is SourceDeleteRequest.Section -> {
                            "${getString(R.string.sure_del)}分组「${request.title}」内的 ${request.sources.size} 个订阅源？"
                        }
                    }
                    RssSourceConfirmDialog(
                        title = getString(R.string.draw),
                        message = message,
                        onDismiss = { deleteRequest = null },
                        onConfirm = {
                            when (request) {
                                is SourceDeleteRequest.One -> viewModel.del(request.source)
                                is SourceDeleteRequest.Selection -> {
                                    viewModel.del(*request.sources.toTypedArray())
                                    selectedUrls = emptySet()
                                }
                                is SourceDeleteRequest.Section -> {
                                    viewModel.del(*request.sources.toTypedArray())
                                    selectedUrls = selectedUrls - request.sources.map(RssSource::sourceUrl).toSet()
                                }
                            }
                            deleteRequest = null
                        },
                    )
                }
            }
        }
        lifecycleScope.launch {
            appDb.rssSourceDao.flowGroups().conflate().collect { groups = it }
        }
        updateSourceFlow()
    }

    private fun handleAction(action: RssSourceManageAction) {
        val selection = selectedSources()
        when (action) {
            RssSourceManageAction.Back -> finish()
            RssSourceManageAction.Add -> startActivity<RssSourceEditActivity>()
            RssSourceManageAction.ImportLocal -> {
                importDoc.launch(arrayOf("text/*", "application/json"))
            }
            RssSourceManageAction.ImportOnline -> inputRequest = SourceInputRequest.IMPORT_ONLINE
            RssSourceManageAction.ImportQr -> qrCodeResult.launch()
            RssSourceManageAction.ImportDefault -> viewModel.importDefault()
            RssSourceManageAction.ManageGroups -> showDialogFragment<GroupManageDialog>()
            RssSourceManageAction.Help -> showHelp("SourceMRssHelp")
            RssSourceManageAction.ToggleSortDirection -> {
                sortAscending = !sortAscending
                putPrefBoolean(PREF_RSS_SORT_ASC, sortAscending)
                applySort()
            }
            RssSourceManageAction.DeleteSelection -> {
                if (selection.isNotEmpty()) deleteRequest = SourceDeleteRequest.Selection(selection)
            }
            RssSourceManageAction.EnableSelection -> viewModel.enableSelection(selection)
            RssSourceManageAction.DisableSelection -> viewModel.disableSelection(selection)
            RssSourceManageAction.ConfigureSelectionType -> {
                if (selection.isNotEmpty()) typeDialogVisible = true
            }
            RssSourceManageAction.AddSelectionToGroup -> {
                if (selection.isNotEmpty()) inputRequest = SourceInputRequest.ADD_GROUP
            }
            RssSourceManageAction.RemoveSelectionFromGroup -> {
                if (selection.isNotEmpty()) inputRequest = SourceInputRequest.REMOVE_GROUP
            }
            RssSourceManageAction.ClearSelectionGroups -> {
                if (selection.isNotEmpty()) clearGroupsConfirm = true
            }
            RssSourceManageAction.CheckSelection -> {
                if (selection.isNotEmpty()) checkDialogVisible = true
            }
            RssSourceManageAction.OpenCheckTask -> startActivity<RssSourceCheckActivity>()
            RssSourceManageAction.StopCheckTask -> viewModel.stopCheck()
            RssSourceManageAction.DismissCheckTask -> viewModel.dismissCheck()
            RssSourceManageAction.TopSelection -> viewModel.topSource(*selection.toTypedArray())
            RssSourceManageAction.BottomSelection -> viewModel.bottomSource(*selection.toTypedArray())
            RssSourceManageAction.ExportOrShareSelection -> showExportSheet(selection)
            RssSourceManageAction.CompleteSelectionInterval -> completeSelectionInterval()
            RssSourceManageAction.SelectAll -> {
                selectedUrls = selectableVisibleSources().mapTo(linkedSetOf(), RssSource::sourceUrl)
            }
            RssSourceManageAction.ClearSelection -> {
                selectedUrls = emptySet()
            }
            RssSourceManageAction.InvertSelection -> {
                val visible = selectableVisibleSources()
                selectedUrls = visible.mapNotNullTo(linkedSetOf()) {
                    it.sourceUrl.takeUnless(selectedUrls::contains)
                }
            }
            RssSourceManageAction.ToggleAllDedupe -> toggleAllDedupe()
            is RssSourceManageAction.QueryChanged -> {
                query = action.query
                selectedUrls = emptySet()
                updateSourceFlow(action.query)
            }
            is RssSourceManageAction.ViewModeChanged -> {
                viewMode = action.mode
                putPrefInt(PREF_RSS_VIEW_MODE, if (action.mode == RssSourceViewMode.GROUP) 1 else 0)
            }
            is RssSourceManageAction.SortChanged -> {
                sort = action.sort
                putPrefInt(PREF_RSS_SORT, action.sort.value)
                applySort()
            }
            is RssSourceManageAction.DedupeModeChanged -> {
                if (dedupeMode != action.mode) {
                    dedupeMode = action.mode
                    putPrefInt(PREF_RSS_DEDUPE_MODE, action.mode.value)
                    preferredPrimaries = RssSourceDedupe.loadPreferredPrimaries(action.mode)
                    expandedDedupeKeys = emptySet()
                    if (action.mode != RssSourceDedupeMode.NONE) {
                        viewModel.applyDedupeAutoDisable(action.mode, preferredPrimaries) { disabledCount ->
                            if (disabledCount > 0) {
                                toastOnUi("已自动禁用 $disabledCount 个重复订阅源")
                            }
                        }
                    }
                }
            }
            is RssSourceManageAction.ToggleDedupeNode -> {
                expandedDedupeKeys = expandedDedupeKeys.toMutableSet().apply {
                    if (!add(action.groupKey)) remove(action.groupKey)
                }
            }
            is RssSourceManageAction.SetDedupePrimary -> {
                preferredPrimaries = RssSourceDedupe.savePreferredPrimary(
                    dedupeMode,
                    action.groupKey,
                    action.source.sourceUrl,
                )
                viewModel.applyDedupeAutoDisable(
                    mode = dedupeMode,
                    preferredPrimaries = preferredPrimaries,
                    promotedPrimaryUrl = action.source.sourceUrl,
                )
            }
            is RssSourceManageAction.SelectionChanged -> {
                selectedUrls = selectedUrls.toMutableSet().apply {
                    if (action.selected) {
                        add(action.source.sourceUrl)
                    } else {
                        remove(action.source.sourceUrl)
                    }
                }
            }
            is RssSourceManageAction.SectionSelectionChanged -> {
                val urls = action.sources.map(RssSource::sourceUrl)
                selectedUrls = if (action.selected) {
                    selectedUrls + urls
                } else {
                    selectedUrls - urls.toSet()
                }
            }
            is RssSourceManageAction.ToggleEnabled -> {
                viewModel.update(action.source.copy(enabled = action.enabled))
            }
            is RssSourceManageAction.Edit -> startActivity<RssSourceEditActivity> {
                putExtra("sourceUrl", action.source.sourceUrl)
            }
            is RssSourceManageAction.Debug -> startActivity<RssSourceDebugActivity> {
                putExtra("key", action.source.sourceUrl)
            }
            is RssSourceManageAction.Login -> startActivity<SourceLoginActivity> {
                putExtra("type", "rssSource")
                putExtra("key", action.source.sourceUrl)
            }
            is RssSourceManageAction.Delete -> {
                selectedUrls = selectedUrls - action.source.sourceUrl
                deleteRequest = SourceDeleteRequest.One(action.source)
            }
            is RssSourceManageAction.SwipeDelete -> {
                selectedUrls = selectedUrls - action.source.sourceUrl
                viewModel.del(action.source)
            }
            is RssSourceManageAction.Top -> viewModel.topSource(action.source)
            is RssSourceManageAction.Bottom -> viewModel.bottomSource(action.source)
            is RssSourceManageAction.Reorder -> {
                val reordered = action.sources.mapIndexed { index, source ->
                    source.copy(customOrder = index + 1)
                }
                viewModel.update(*reordered.toTypedArray())
            }
            is RssSourceManageAction.ToggleSection -> {
                expandedSections = expandedSections.toMutableSet().apply {
                    if (!add(action.key)) remove(action.key)
                }
            }
            is RssSourceManageAction.DeleteSection -> {
                deleteRequest = SourceDeleteRequest.Section(
                    groupName = action.groupName,
                    title = action.title,
                    sources = action.sources,
                )
            }
        }
    }

    private fun toggleAllDedupe() {
        if (dedupeMode == RssSourceDedupeMode.NONE) return
        val tree = RssSourceDedupe.buildTree(sources, dedupeMode, preferredPrimaries)
        val allExpandable = tree.filter { it.hasDuplicates }.mapTo(hashSetOf()) { it.groupKey }
        expandedDedupeKeys = if (allExpandable.isNotEmpty() && allExpandable.all { it in expandedDedupeKeys }) {
            emptySet()
        } else {
            allExpandable
        }
    }

    private fun selectableVisibleSources(): List<RssSource> {
        if (dedupeMode == RssSourceDedupeMode.NONE) {
            return sources.distinctBy(RssSource::sourceUrl)
        }
        val tree = RssSourceDedupe.buildTree(sources, dedupeMode, preferredPrimaries)
        return buildList(sources.size) {
            tree.forEach { node ->
                if (node.hasDuplicates && node.groupKey in expandedDedupeKeys) {
                    addAll(node.allSources)
                } else {
                    add(node.primary)
                }
            }
        }
    }

    private fun showExportSheet(selection: List<RssSource>) {
        if (selection.isEmpty()) return
        BookSourceExportSheet(
            context = this,
            titleRes = R.string.export_rss_source,
            onShare = {
                viewModel.saveToFile(selection) { file, _ -> share(file) }
            },
            onSaveLocally = {
                viewModel.saveToFile(selection) { file, name ->
                    exportResult.launch(CreateFileContract.FileData(name, file, "application/json"))
                }
            },
            onUpload = {
                viewModel.saveToFile(selection) { file, name ->
                    showDialogFragment(BookSourceUploadDialog.create(file, name))
                }
            },
        ).show()
    }

    private fun updateSourceFlow(searchKey: String? = query) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> appDb.rssSourceDao.flowAll()
                searchKey == getString(R.string.enabled) -> appDb.rssSourceDao.flowEnabled()
                searchKey == getString(R.string.disabled) -> appDb.rssSourceDao.flowDisabled()
                searchKey == getString(R.string.need_login) -> appDb.rssSourceDao.flowLogin()
                searchKey == getString(R.string.no_group) -> appDb.rssSourceDao.flowNoGroup()
                searchKey == RSS_SINGLE_URL_QUERY -> appDb.rssSourceDao.flowSingleUrl()
                searchKey.startsWith(RSS_TYPE_QUERY_PREFIX) -> {
                    val typeInt = searchKey.substringAfter(RSS_TYPE_QUERY_PREFIX).toIntOrNull() ?: 0
                    appDb.rssSourceDao.flowByType(typeInt)
                }
                searchKey.startsWith(RSS_GROUP_QUERY_PREFIX) -> {
                    appDb.rssSourceDao.flowGroupSearch(searchKey.substringAfter(RSS_GROUP_QUERY_PREFIX))
                }
                else -> appDb.rssSourceDao.flowSearch(searchKey)
            }.catch {
                AppLog.put("订阅源管理界面更新数据出错", it)
            }.flowOn(IO).conflate().collect { list ->
                rawSources = list
                applySort()
                selectedUrls = selectedUrls.intersect(list.mapTo(hashSetOf(), RssSource::sourceUrl))
                delay(100)
            }
        }
    }

    private fun applySort() {
        val sorted = when (sort) {
            RssSourceSort.Default -> if (sortAscending) rawSources else rawSources.reversed()
            RssSourceSort.Name -> rawSources.sortedWith { a, b ->
                val cmp = a.sourceName.cnCompare(b.sourceName)
                if (sortAscending) cmp else -cmp
            }
            RssSourceSort.Url -> rawSources.sortedWith { a, b ->
                val cmp = a.sourceUrl.compareTo(b.sourceUrl, ignoreCase = true)
                if (sortAscending) cmp else -cmp
            }
            RssSourceSort.Update -> rawSources.sortedWith { a, b ->
                val cmp = a.lastUpdateTime.compareTo(b.lastUpdateTime)
                if (sortAscending) cmp else -cmp
            }
            RssSourceSort.Enable -> rawSources.sortedWith { a, b ->
                val cmp = b.enabled.compareTo(a.enabled)
                if (sortAscending) cmp else -cmp
            }
        }
        sources = sorted
    }

    private fun selectedSources(): List<RssSource> {
        return sources.filter { it.sourceUrl in selectedUrls }
    }

    private fun completeSelectionInterval() {
        val visible = selectableVisibleSources()
        val selectedIndices = visible.indices.filter { visible[it].sourceUrl in selectedUrls }
        val min = selectedIndices.minOrNull() ?: return
        val max = selectedIndices.maxOrNull() ?: return
        selectedUrls = selectedUrls + visible.subList(min, max + 1).map(RssSource::sourceUrl)
    }

    private fun handleInput(request: SourceInputRequest, value: String) {
        val text = value.trim()
        when (request) {
            SourceInputRequest.IMPORT_ONLINE -> if (text.isNotBlank()) {
                val history = importHistory().toMutableList()
                if (text.isAbsUrl() && text !in history) {
                    history.add(0, text)
                    ACache.get(cacheDir = false).put(importRecordKey, history.joinToString(","))
                }
                showDialogFragment(ImportRssSourceDialog(text))
            }
            SourceInputRequest.ADD_GROUP -> if (text.isNotBlank()) {
                viewModel.selectionAddToGroups(selectedSources(), text)
            }
            SourceInputRequest.REMOVE_GROUP -> if (text.isNotBlank()) {
                viewModel.selectionRemoveFromGroups(selectedSources(), text)
            }
        }
        inputRequest = null
    }

    private fun importHistory(): List<String> {
        return ACache.get(cacheDir = false)
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toList()
            .orEmpty()
    }
}
