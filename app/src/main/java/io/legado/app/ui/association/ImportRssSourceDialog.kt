package io.legado.app.ui.association

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.design.components.NgButtonVariant
import io.legado.app.ui.design.components.NgFilterChipGroupVariant
import io.legado.app.ui.design.components.NgStatusTagStyle
import io.legado.app.ui.design.components.NgStatusTagVariant
import io.legado.app.ui.design.components.compose.NgBottomDrawerSurface
import io.legado.app.ui.design.components.compose.NgDrawerContentCardStyle
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckbox
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckboxVariant
import io.legado.app.ui.design.components.compose.NgFilterChipGroup
import io.legado.app.ui.design.components.compose.NgFilterChipItem
import io.legado.app.ui.design.components.compose.NgFlatActionRail
import io.legado.app.ui.design.components.compose.NgFlatActionRailItem
import io.legado.app.ui.design.components.compose.NgFlatActionRailVariant
import io.legado.app.ui.design.components.compose.NgFormActionButton
import io.legado.app.ui.design.components.compose.NgFormActionButtonAppearance
import io.legado.app.ui.design.components.compose.NgFormActionRow
import io.legado.app.ui.design.components.compose.NgFormField
import io.legado.app.ui.design.components.compose.NgFormGroup
import io.legado.app.ui.design.components.compose.NgFormGroupDivider
import io.legado.app.ui.design.components.compose.NgFormSwitchSettingRow
import io.legado.app.ui.design.components.compose.NgLazyListFastScroller
import io.legado.app.ui.design.components.compose.NgLazyListFastScrollerVariant
import io.legado.app.ui.design.components.compose.NgLongDrawerHeader
import io.legado.app.ui.design.components.compose.NgManagementDrawerPanel
import io.legado.app.ui.design.components.compose.NgManagementDrawerPanelVariant
import io.legado.app.ui.design.components.compose.NgStatusTag
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RSS_SOURCE_IMPORT_SHEET_HEIGHT_RATIO = 0.90f
private val RssSourceImportRowMinHeight = 54.dp

/** 导入订阅源抽屉，与书源导入抽屉样式保持一致。 */
class ImportRssSourceDialog() : BottomSheetDialogFragment(), CodeDialog.Callback {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString(ARG_SOURCE, source)
            putBoolean(ARG_FINISH_ON_DISMISS, finishOnDismiss)
        }
    }

    private val viewModel by viewModels<ImportRssSourceViewModel>()
    private var sources by mutableStateOf<List<RssSource>>(
        emptyList(),
        referentialEqualityPolicy(),
    )
    private var selectedIndices by mutableStateOf<Set<Int>>(emptySet())
    private var loading by mutableStateOf(true)
    private var importing by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var keepName by mutableStateOf(AppConfig.importKeepName)
    private var keepGroup by mutableStateOf(AppConfig.importKeepGroup)
    private var keepEnable by mutableStateOf(AppConfig.importKeepEnable)
    private var showComment by mutableStateOf(AppConfig.importShowComment)
    private var customGroupName by mutableStateOf<String?>(null)
    private var addToExistingGroup by mutableStateOf(false)
    private var groupSuggestions by mutableStateOf<List<String>>(emptyList())

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setBackgroundColor(AndroidColor.TRANSPARENT)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        customGroupName = viewModel.groupName
        addToExistingGroup = viewModel.isAddGroup
        (view as ComposeView).setContent {
            NgAppTheme(updateSystemBars = false) {
                val localSources = viewModel.checkSources
                val newSourceCount = remember(sources, localSources) {
                    sources.indices.count { localSources.getOrNull(it) == null }
                }
                val updateSourceCount = remember(sources, localSources) {
                    sources.indices.count { index ->
                        val local = localSources.getOrNull(index)
                        local != null && sources[index].lastUpdateTime > local.lastUpdateTime
                    }
                }
                RssSourceImportDrawer(
                    sources = sources,
                    localSources = localSources,
                    selectedIndices = selectedIndices,
                    newSourceCount = newSourceCount,
                    updateSourceCount = updateSourceCount,
                    loading = loading,
                    importing = importing,
                    error = error,
                    keepName = keepName,
                    keepGroup = keepGroup,
                    keepEnable = keepEnable,
                    showComment = showComment,
                    customGroupName = customGroupName,
                    addToExistingGroup = addToExistingGroup,
                    groupSuggestions = groupSuggestions,
                    onToggle = ::toggleSelection,
                    onToggleAll = ::toggleAll,
                    onSelectNew = { selectMatching { index -> localSources.getOrNull(index) == null } },
                    onSelectUpdate = {
                        selectMatching { index ->
                            val local = localSources.getOrNull(index)
                            local != null && sources[index].lastUpdateTime > local.lastUpdateTime
                        }
                    },
                    onViewSource = ::editSource,
                    onKeepNameChange = {
                        keepName = it
                        putPrefBoolean(PreferKey.importKeepName, it)
                    },
                    onKeepGroupChange = {
                        keepGroup = it
                        putPrefBoolean(PreferKey.importKeepGroup, it)
                    },
                    onKeepEnableChange = {
                        keepEnable = it
                        AppConfig.importKeepEnable = it
                    },
                    onShowCommentChange = {
                        showComment = it
                        AppConfig.importShowComment = it
                    },
                    onApplyGroup = { name, add ->
                        customGroupName = name
                        addToExistingGroup = add
                        viewModel.groupName = name
                        viewModel.isAddGroup = add
                    },
                    onDismiss = { dismissAllowingStateLoss() },
                    onImport = ::importSelected,
                )
            }
        }
        viewModel.errorLiveData.observe(viewLifecycleOwner) {
            loading = false
            error = it
        }
        viewModel.successLiveData.observe(viewLifecycleOwner) { count ->
            loading = false
            if (count > 0) {
                error = null
                sources = viewModel.allSources.toList()
                selectedIndices = viewModel.selectStatus.indices
                    .filterTo(linkedSetOf()) { index -> viewModel.selectStatus[index] }
            } else {
                error = getString(R.string.wrong_format)
            }
        }
        loadGroupSuggestions()
        val source = arguments?.getString(ARG_SOURCE)
        when {
            source.isNullOrEmpty() -> dismissAllowingStateLoss()
            viewModel.successLiveData.value == null && viewModel.errorLiveData.value == null -> {
                viewModel.importSource(source)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            attributes = attributes.apply { dimAmount = 0.22f }
            decorView.setPadding(0, 0, 0, 0)
        }
        val sheet = dialog?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        sheet.setBackgroundColor(AndroidColor.TRANSPARENT)
        sheet.layoutParams = sheet.layoutParams.apply {
            height = (
                resources.displayMetrics.heightPixels * RSS_SOURCE_IMPORT_SHEET_HEIGHT_RATIO
            ).toInt()
        }
        BottomSheetBehavior.from(sheet).apply {
            skipCollapsed = true
            isFitToContents = true
            isDraggable = !importing
            isDraggableOnNestedScroll = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean(ARG_FINISH_ON_DISMISS) == true) activity?.finish()
    }

    override fun show(manager: FragmentManager, tag: String?) {
        runCatching { super.show(manager, tag) }
            .onFailure { AppLog.put("显示导入订阅源抽屉失败 tag:$tag", it) }
    }

    private fun loadGroupSuggestions() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(IO) { appDb.rssSourceDao.allGroups().toList() }
            }.onSuccess { groupSuggestions = it }
        }
    }

    private fun toggleSelection(index: Int) {
        if (index !in sources.indices) return
        selectedIndices = selectedIndices.toMutableSet().apply {
            if (!add(index)) remove(index)
        }
        viewModel.selectStatus[index] = index in selectedIndices
    }

    private fun toggleAll() {
        selectedIndices = if (selectedIndices.size == sources.size) {
            emptySet()
        } else {
            sources.indices.toSet()
        }
        viewModel.selectStatus.indices.forEach {
            viewModel.selectStatus[it] = it in selectedIndices
        }
    }

    private fun selectMatching(predicate: (Int) -> Boolean) {
        val matching = sources.indices.filter(predicate)
        if (matching.isEmpty()) return
        val shouldSelect = matching.any { it !in selectedIndices }
        selectedIndices = selectedIndices.toMutableSet().apply {
            matching.forEach { if (shouldSelect) add(it) else remove(it) }
        }
        viewModel.selectStatus.indices.forEach {
            viewModel.selectStatus[it] = it in selectedIndices
        }
    }

    private fun editSource(index: Int) {
        val source = sources.getOrNull(index) ?: return
        showDialogFragment(
            CodeDialog(
                GSON.toJson(source),
                disableEdit = false,
                requestId = index.toString(),
            )
        )
    }

    private fun importSelected() {
        if (importing || selectedIndices.isEmpty()) return
        importing = true
        viewModel.importSelect {
            importing = false
            dismissAllowingStateLoss()
        }
    }

    override fun onCodeSave(code: String, requestId: String?) {
        val index = requestId?.toIntOrNull() ?: return
        runCatching {
            GSON.fromJsonObject<RssSource>(code).getOrThrow()
        }.onSuccess { edited ->
            viewModel.allSources[index] = edited
            sources = sources.toMutableList().apply { set(index, edited) }
        }.onFailure {
            context?.toastOnUi(it.localizedMessage ?: "格式错误")
        }
    }

    private companion object {
        const val ARG_SOURCE = "source"
        const val ARG_FINISH_ON_DISMISS = "finishOnDismiss"
    }
}

private enum class RssSourceImportDrawerScreen {
    MAIN,
    SETTINGS,
    GROUP,
}

@Composable
private fun RssSourceImportDrawer(
    sources: List<RssSource>,
    localSources: List<RssSource?>,
    selectedIndices: Set<Int>,
    newSourceCount: Int,
    updateSourceCount: Int,
    loading: Boolean,
    importing: Boolean,
    error: String?,
    keepName: Boolean,
    keepGroup: Boolean,
    keepEnable: Boolean,
    showComment: Boolean,
    customGroupName: String?,
    addToExistingGroup: Boolean,
    groupSuggestions: List<String>,
    onToggle: (Int) -> Unit,
    onToggleAll: () -> Unit,
    onSelectNew: () -> Unit,
    onSelectUpdate: () -> Unit,
    onViewSource: (Int) -> Unit,
    onKeepNameChange: (Boolean) -> Unit,
    onKeepGroupChange: (Boolean) -> Unit,
    onKeepEnableChange: (Boolean) -> Unit,
    onShowCommentChange: (Boolean) -> Unit,
    onApplyGroup: (String?, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(RssSourceImportDrawerScreen.MAIN) }
    BackHandler(enabled = screen != RssSourceImportDrawerScreen.MAIN) {
        screen = when (screen) {
            RssSourceImportDrawerScreen.GROUP -> RssSourceImportDrawerScreen.SETTINGS
            else -> RssSourceImportDrawerScreen.MAIN
        }
    }

    NgBottomDrawerSurface(
        modifier = Modifier.fillMaxSize(),
        contentCardStyle = NgDrawerContentCardStyle.ADAPTIVE,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 12.dp),
        ) {
            when (screen) {
                RssSourceImportDrawerScreen.MAIN -> RssSourceImportMainContent(
                    sources = sources,
                    localSources = localSources,
                    selectedIndices = selectedIndices,
                    newSourceCount = newSourceCount,
                    updateSourceCount = updateSourceCount,
                    loading = loading,
                    importing = importing,
                    error = error,
                    showComment = showComment,
                    onSettings = { screen = RssSourceImportDrawerScreen.SETTINGS },
                    onToggle = onToggle,
                    onToggleAll = onToggleAll,
                    onViewSource = onViewSource,
                    onDismiss = onDismiss,
                    onImport = onImport,
                )

                RssSourceImportDrawerScreen.SETTINGS -> RssSourceImportSettingsContent(
                    newSourceCount = newSourceCount,
                    updateSourceCount = updateSourceCount,
                    keepName = keepName,
                    keepGroup = keepGroup,
                    keepEnable = keepEnable,
                    showComment = showComment,
                    customGroupName = customGroupName,
                    onBack = { screen = RssSourceImportDrawerScreen.MAIN },
                    onSelectNew = {
                        onSelectNew()
                        screen = RssSourceImportDrawerScreen.MAIN
                    },
                    onSelectUpdate = {
                        onSelectUpdate()
                        screen = RssSourceImportDrawerScreen.MAIN
                    },
                    onGroup = { screen = RssSourceImportDrawerScreen.GROUP },
                    onKeepNameChange = onKeepNameChange,
                    onKeepGroupChange = onKeepGroupChange,
                    onKeepEnableChange = onKeepEnableChange,
                    onShowCommentChange = onShowCommentChange,
                    onDone = { screen = RssSourceImportDrawerScreen.MAIN },
                )

                RssSourceImportDrawerScreen.GROUP -> RssSourceImportGroupContent(
                    initialName = customGroupName,
                    initialAdd = addToExistingGroup,
                    suggestions = groupSuggestions,
                    onBack = { screen = RssSourceImportDrawerScreen.SETTINGS },
                    onApply = { name, add ->
                        onApplyGroup(name, add)
                        screen = RssSourceImportDrawerScreen.SETTINGS
                    },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.RssSourceImportMainContent(
    sources: List<RssSource>,
    localSources: List<RssSource?>,
    selectedIndices: Set<Int>,
    newSourceCount: Int,
    updateSourceCount: Int,
    loading: Boolean,
    importing: Boolean,
    error: String?,
    showComment: Boolean,
    onSettings: () -> Unit,
    onToggle: (Int) -> Unit,
    onToggleAll: () -> Unit,
    onViewSource: (Int) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
) {
    val allSelected = remember(sources.size, selectedIndices) {
        sources.isNotEmpty() && selectedIndices.size == sources.size
    }
    NgLongDrawerHeader(
        title = stringResource(R.string.import_rss_source),
        actionIconRes = R.drawable.ic_settings,
        actionContentDescription = stringResource(R.string.book_source_import_settings),
        onActionClick = onSettings,
        centerTitle = true,
    )
    Spacer(Modifier.height(4.dp))
    NgManagementDrawerPanel(variant = NgManagementDrawerPanelVariant.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RssImportSummaryCell(
                text = stringResource(
                    R.string.book_source_import_selected_summary,
                    selectedIndices.size,
                    sources.size,
                ),
                emphasized = true,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(
                modifier = Modifier.height(18.dp),
                thickness = 0.6.dp,
                color = Color(NgTheme.colors.outlineVariant).copy(alpha = 0.32f),
            )
            RssImportSummaryCell(
                text = stringResource(R.string.book_source_import_new_summary, newSourceCount),
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(
                modifier = Modifier.height(18.dp),
                thickness = 0.6.dp,
                color = Color(NgTheme.colors.outlineVariant).copy(alpha = 0.32f),
            )
            RssImportSummaryCell(
                text = stringResource(R.string.book_source_import_update_summary, updateSourceCount),
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    val listState = rememberLazyListState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    ) {
        NgManagementDrawerPanel(
            modifier = Modifier.fillMaxSize(),
            variant = NgManagementDrawerPanelVariant.COMPACT,
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = Color(NgTheme.colors.primary),
                        strokeWidth = 2.5.dp,
                    )
                }

                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = error.orEmpty(),
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = Color(NgTheme.colors.onSurfaceVariant),
                        fontSize = 14.sp,
                    )
                }

                sources.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.empty),
                        color = Color(NgTheme.colors.onSurfaceVariant),
                        fontSize = 14.sp,
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(sources, key = { index, _ -> index }) { index, source ->
                        RssSourceImportRow(
                            source = source,
                            localSource = localSources.getOrNull(index),
                            selected = index in selectedIndices,
                            showComment = showComment,
                            onToggle = { onToggle(index) },
                            onViewSource = { onViewSource(index) },
                            showDivider = index != sources.lastIndex,
                        )
                    }
                }
            }
        }
        if (!loading && error == null && sources.isNotEmpty()) {
            NgLazyListFastScroller(
                state = listState,
                itemCount = sources.size,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp),
                variant = NgLazyListFastScrollerVariant.FLOATING_HANDLE,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val contentColor = Color(
            if (allSelected) NgTheme.colors.primary else NgTheme.colors.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .width(112.dp)
                .height(36.dp)
                .clickable(
                    enabled = sources.isNotEmpty() && !importing,
                    role = Role.Button,
                    onClick = onToggleAll,
                )
                .alpha(if (sources.isNotEmpty() && !importing) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_select_all),
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = contentColor,
                )
            }
            Text(
                text = stringResource(
                    if (allSelected) R.string.book_source_import_deselect_all
                    else R.string.book_source_import_select_all
                ),
                color = contentColor,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NgFormActionButton(
            text = stringResource(R.string.cancel),
            onClick = onDismiss,
            enabled = !importing,
            modifier = Modifier.weight(1f),
            appearance = NgFormActionButtonAppearance.SURFACE_CARD,
        )
        NgFormActionButton(
            text = if (importing) {
                stringResource(R.string.importing)
            } else {
                stringResource(R.string.book_source_import_action)
            },
            onClick = onImport,
            modifier = Modifier.weight(1f),
            enabled = selectedIndices.isNotEmpty() && !importing,
            variant = NgButtonVariant.PRIMARY,
        )
    }
}

@Composable
private fun RssImportSummaryCell(
    text: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color(if (emphasized) NgTheme.colors.primary else NgTheme.colors.onSurfaceVariant),
            fontSize = 12.sp,
            fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RssSourceImportRow(
    source: RssSource,
    localSource: RssSource?,
    selected: Boolean,
    showComment: Boolean,
    onToggle: () -> Unit,
    onViewSource: () -> Unit,
    showDivider: Boolean,
) {
    var commentExpanded by rememberSaveable(source.sourceUrl) { mutableStateOf(false) }
    val state = when {
        localSource == null -> RssImportState.NEW
        source.lastUpdateTime > localSource.lastUpdateTime -> RssImportState.UPDATE
        else -> RssImportState.EXISTING
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RssSourceImportRowMinHeight)
                .clickable(role = Role.Checkbox, onClick = onToggle)
                .padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NgFileSelectionCheckbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                variant = NgFileSelectionCheckboxVariant.COMPACT,
            )
            Text(
                text = source.sourceName,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 8.dp),
                color = Color(NgTheme.colors.onSurface),
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NgStatusTag(
                text = stringResource(state.labelRes),
                variant = state.variant,
                style = NgStatusTagStyle.INLINE,
            )
            TextButton(
                onClick = onViewSource,
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.book_source_import_view),
                    color = Color(NgTheme.colors.onSurfaceVariant),
                    fontSize = 13.sp,
                )
            }
        }
        if (showComment && !source.sourceComment.isNullOrBlank()) {
            Text(
                text = source.sourceComment.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { commentExpanded = !commentExpanded }
                    .padding(start = 48.dp, end = 12.dp, bottom = 8.dp),
                color = Color(NgTheme.colors.onSurfaceVariant),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = if (commentExpanded) 39 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 48.dp, end = 10.dp),
                thickness = 0.6.dp,
                color = Color(NgTheme.colors.outlineVariant).copy(alpha = 0.24f),
            )
        }
    }
}

@Composable
private fun ColumnScope.RssSourceImportSettingsContent(
    newSourceCount: Int,
    updateSourceCount: Int,
    keepName: Boolean,
    keepGroup: Boolean,
    keepEnable: Boolean,
    showComment: Boolean,
    customGroupName: String?,
    onBack: () -> Unit,
    onSelectNew: () -> Unit,
    onSelectUpdate: () -> Unit,
    onGroup: () -> Unit,
    onKeepNameChange: (Boolean) -> Unit,
    onKeepGroupChange: (Boolean) -> Unit,
    onKeepEnableChange: (Boolean) -> Unit,
    onShowCommentChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    NgLongDrawerHeader(
        title = stringResource(R.string.book_source_import_settings),
        navigationIconRes = R.drawable.ic_arrow_back,
        navigationContentDescription = stringResource(R.string.back),
        onNavigationClick = onBack,
        centerTitle = true,
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            NgFormGroup(title = stringResource(R.string.book_source_import_selection_scope)) {
                RssImportSettingsRow(
                    title = stringResource(R.string.select_new_source),
                    trailingText = stringResource(R.string.book_source_import_source_count, newSourceCount),
                    enabled = newSourceCount > 0,
                    onClick = onSelectNew,
                )
                NgFormGroupDivider()
                RssImportSettingsRow(
                    title = stringResource(R.string.select_update_source),
                    trailingText = stringResource(R.string.book_source_import_source_count, updateSourceCount),
                    enabled = updateSourceCount > 0,
                    onClick = onSelectUpdate,
                )
            }
        }
        item {
            NgFormGroup(title = stringResource(R.string.book_source_import_group_section)) {
                RssImportSettingsRow(
                    title = stringResource(R.string.diy_source_group),
                    trailingText = customGroupName?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.book_source_import_group_unset),
                    onClick = onGroup,
                )
            }
        }
        item {
            NgFormGroup(title = stringResource(R.string.book_source_import_keep_section)) {
                NgFormSwitchSettingRow(
                    title = stringResource(R.string.keep_original_name),
                    checked = keepName,
                    onCheckedChange = onKeepNameChange,
                )
                NgFormGroupDivider()
                NgFormSwitchSettingRow(
                    title = stringResource(R.string.keep_group),
                    checked = keepGroup,
                    onCheckedChange = onKeepGroupChange,
                )
                NgFormGroupDivider()
                NgFormSwitchSettingRow(
                    title = stringResource(R.string.keep_enable),
                    checked = keepEnable,
                    onCheckedChange = onKeepEnableChange,
                )
                NgFormGroupDivider()
                NgFormSwitchSettingRow(
                    title = stringResource(R.string.show_source_comment),
                    checked = showComment,
                    onCheckedChange = onShowCommentChange,
                )
            }
        }
    }
    NgFormActionButton(
        text = stringResource(R.string.book_source_import_done),
        onClick = onDone,
        modifier = Modifier.fillMaxWidth(),
        variant = NgButtonVariant.PRIMARY,
    )
}

@Composable
private fun RssImportSettingsRow(
    title: String,
    trailingText: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = Color(NgTheme.colors.onSurface),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = trailingText,
            color = Color(NgTheme.colors.onSurfaceVariant),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right_20),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Color(NgTheme.colors.onSurfaceVariant),
        )
    }
}

@Composable
private fun ColumnScope.RssSourceImportGroupContent(
    initialName: String?,
    initialAdd: Boolean,
    suggestions: List<String>,
    onBack: () -> Unit,
    onApply: (String?, Boolean) -> Unit,
) {
    var groupName by rememberSaveable(initialName) { mutableStateOf(initialName.orEmpty()) }
    var addToExisting by rememberSaveable(initialName, initialAdd) { mutableStateOf(initialAdd) }
    val suggestionItems = remember(suggestions) {
        suggestions.map { NgFilterChipItem(key = it, label = it) }
    }
    NgLongDrawerHeader(
        title = stringResource(R.string.diy_source_group),
        navigationIconRes = R.drawable.ic_arrow_back,
        navigationContentDescription = stringResource(R.string.back),
        onNavigationClick = onBack,
        centerTitle = true,
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                NgFormField(
                    label = stringResource(R.string.group_name),
                    value = groupName,
                    onValueChange = { groupName = it },
                    placeholder = stringResource(R.string.book_source_import_group_unset),
                )
                if (suggestionItems.isNotEmpty()) {
                    NgFilterChipGroup(
                        items = suggestionItems,
                        selectedKeys = groupName.takeIf(suggestions::contains)?.let(::setOf).orEmpty(),
                        onToggle = { groupName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        variant = NgFilterChipGroupVariant.TWO_ROW_RAIL,
                    )
                }
            }
        }
        item {
            NgFormGroup(title = stringResource(R.string.book_source_import_group_mode)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    NgFlatActionRail(
                        items = listOf(
                            NgFlatActionRailItem(
                                label = stringResource(R.string.book_source_import_replace_group),
                                emphasized = !addToExisting,
                            ),
                            NgFlatActionRailItem(
                                label = stringResource(R.string.book_source_import_add_group),
                                emphasized = addToExisting,
                            ),
                        ),
                        onItemClick = { index -> addToExisting = index == 1 },
                        variant = NgFlatActionRailVariant.TEXT_MODE_PICKER,
                    )
                    Text(
                        text = stringResource(
                            if (addToExisting) R.string.book_source_import_add_group_summary
                            else R.string.book_source_import_replace_group_summary
                        ),
                        modifier = Modifier.padding(start = 4.dp, top = 10.dp, end = 4.dp),
                        color = Color(NgTheme.colors.onSurfaceVariant),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
    NgFormActionRow {
        NgFormActionButton(
            text = stringResource(R.string.cancel),
            onClick = onBack,
            modifier = Modifier.weight(1f),
        )
        NgFormActionButton(
            text = stringResource(R.string.book_source_import_apply),
            onClick = { onApply(groupName.trim().takeIf(String::isNotBlank), addToExisting) },
            modifier = Modifier.weight(1f),
            variant = NgButtonVariant.PRIMARY,
        )
    }
}

private enum class RssImportState(
    val labelRes: Int,
    val variant: NgStatusTagVariant,
) {
    NEW(R.string.book_source_import_state_new, NgStatusTagVariant.SUCCESS),
    UPDATE(R.string.book_source_import_state_update, NgStatusTagVariant.WARNING),
    EXISTING(R.string.book_source_import_state_existing, NgStatusTagVariant.NEUTRAL),
}
