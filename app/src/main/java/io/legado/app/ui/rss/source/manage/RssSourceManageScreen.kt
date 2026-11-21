package io.legado.app.ui.rss.source.manage

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.RssCheckTaskState
import io.legado.app.help.source.RssCheckTaskStatus
import io.legado.app.help.source.RssSourceDedupe
import io.legado.app.help.source.RssSourceDedupeMode
import io.legado.app.help.source.RssSourceDedupeNode
import io.legado.app.help.source.RssSourceReachability
import io.legado.app.help.source.RssSourceReachabilityStore
import io.legado.app.ui.design.components.compose.NgExpandableActionMenu
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuItem
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuVariant
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckbox
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckboxVariant
import io.legado.app.ui.design.components.compose.NgFileSelectionTriStateCheckbox
import io.legado.app.ui.design.components.compose.NgFloatingToolbarBackButton
import io.legado.app.ui.design.components.compose.NgFormField
import io.legado.app.ui.design.components.compose.NgFormFieldVariant
import io.legado.app.ui.design.components.compose.NgGlassDefaults
import io.legado.app.ui.design.components.compose.NgGlassSurface
import io.legado.app.ui.design.components.compose.NgMaterialRole
import io.legado.app.ui.design.components.compose.NgPopupToggleState
import io.legado.app.ui.design.components.compose.NgSearchBar
import io.legado.app.ui.design.components.compose.NgSearchBarVariant
import io.legado.app.ui.design.components.compose.NgSwitchControl
import io.legado.app.ui.design.components.compose.NgSwitchControlVariant
import io.legado.app.ui.design.components.compose.NgVisualSurface
import io.legado.app.ui.design.components.compose.ngSlideSelect
import io.legado.app.ui.design.components.compose.rememberNgLazySlideSelectState
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.utils.splitNotBlank
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

private const val FILTER_ALL_ITEM_ID = 0x55000001
private const val FILTER_TYPE_PARENT_ITEM_ID = 0x55000080
private const val FILTER_TYPE_WEB_ITEM_ID = 0x55000081
private const val FILTER_TYPE_IMAGE_ITEM_ID = 0x55000082
private const val FILTER_TYPE_VIDEO_ITEM_ID = 0x55000083
private const val FILTER_TYPE_SINGLE_ITEM_ID = 0x55000084
private const val FILTER_STATUS_PARENT_ITEM_ID = 0x55000100
private const val FILTER_ENABLED_ITEM_ID = 0x55000101
private const val FILTER_DISABLED_ITEM_ID = 0x55000102
private const val FILTER_LOGIN_ITEM_ID = 0x55000103
private const val FILTER_GROUP_PARENT_ITEM_ID = 0x55000200
private const val FILTER_NO_GROUP_ITEM_ID = 0x55000201
private const val GROUP_ITEM_ID_BASE = 0x55001000
private const val IMPORT_PARENT_ITEM_ID = 0x55002000
private const val DEDUPE_PARENT_ITEM_ID = 0x55003000
private const val DEDUPE_NONE_ITEM_ID = 0x55003001
private const val DEDUPE_URL_ITEM_ID = 0x55003002
private const val DEDUPE_NAME_ITEM_ID = 0x55003003
private const val SORT_DIRECTION_ITEM_ID = 0x55004000

internal const val RSS_GROUP_QUERY_PREFIX = "group:"
internal const val RSS_TYPE_QUERY_PREFIX = "type:"
internal const val RSS_SINGLE_URL_QUERY = "singleUrl"

internal enum class RssSourceViewMode { LIST, GROUP }

internal enum class RssSourceSort(val value: Int) {
    Default(0),
    Name(1),
    Url(2),
    Update(3),
    Enable(4);

    companion object {
        fun from(value: Int): RssSourceSort =
            entries.firstOrNull { it.value == value } ?: Default
    }
}

internal sealed interface RssSourceManageAction {
    data object Back : RssSourceManageAction
    data object Add : RssSourceManageAction
    data object ImportLocal : RssSourceManageAction
    data object ImportOnline : RssSourceManageAction
    data object ImportQr : RssSourceManageAction
    data object ImportDefault : RssSourceManageAction
    data object ManageGroups : RssSourceManageAction
    data object Help : RssSourceManageAction
    data object ToggleSortDirection : RssSourceManageAction
    data object SelectAll : RssSourceManageAction
    data object ClearSelection : RssSourceManageAction
    data object InvertSelection : RssSourceManageAction
    data object EnableSelection : RssSourceManageAction
    data object DisableSelection : RssSourceManageAction
    data object ConfigureSelectionType : RssSourceManageAction
    data object AddSelectionToGroup : RssSourceManageAction
    data object RemoveSelectionFromGroup : RssSourceManageAction
    data object ClearSelectionGroups : RssSourceManageAction
    data object CheckSelection : RssSourceManageAction
    data object OpenCheckTask : RssSourceManageAction
    data object StopCheckTask : RssSourceManageAction
    data object DismissCheckTask : RssSourceManageAction
    data object CompleteSelectionInterval : RssSourceManageAction
    data object TopSelection : RssSourceManageAction
    data object BottomSelection : RssSourceManageAction
    data object ExportOrShareSelection : RssSourceManageAction
    data object DeleteSelection : RssSourceManageAction
    data object ToggleAllDedupe : RssSourceManageAction
    data class QueryChanged(val query: String) : RssSourceManageAction
    data class ViewModeChanged(val mode: RssSourceViewMode) : RssSourceManageAction
    data class SortChanged(val sort: RssSourceSort) : RssSourceManageAction
    data class DedupeModeChanged(val mode: RssSourceDedupeMode) : RssSourceManageAction
    data class ToggleDedupeNode(val groupKey: String) : RssSourceManageAction
    data class SetDedupePrimary(val groupKey: String, val source: RssSource) : RssSourceManageAction
    data class SelectionChanged(val source: RssSource, val selected: Boolean) : RssSourceManageAction
    data class SectionSelectionChanged(val sources: List<RssSource>, val selected: Boolean) : RssSourceManageAction
    data class ToggleEnabled(val source: RssSource, val enabled: Boolean) : RssSourceManageAction
    data class Edit(val source: RssSource) : RssSourceManageAction
    data class Debug(val source: RssSource) : RssSourceManageAction
    data class Login(val source: RssSource) : RssSourceManageAction
    data class Delete(val source: RssSource) : RssSourceManageAction
    data class SwipeDelete(val source: RssSource) : RssSourceManageAction
    data class Top(val source: RssSource) : RssSourceManageAction
    data class Bottom(val source: RssSource) : RssSourceManageAction
    data class Reorder(val sources: List<RssSource>) : RssSourceManageAction
    data class ToggleSection(val key: String) : RssSourceManageAction
    data class DeleteSection(
        val groupName: String?,
        val title: String,
        val sources: List<RssSource>,
    ) : RssSourceManageAction
}

@Composable
internal fun RssSourceManageScreen(
    sources: List<RssSource>,
    groups: List<String>,
    query: String,
    selectedUrls: Set<String>,
    viewMode: RssSourceViewMode,
    sort: RssSourceSort,
    sortAscending: Boolean,
    expandedSections: Set<String>,
    dedupeMode: RssSourceDedupeMode,
    preferredPrimaries: Map<String, String>,
    expandedDedupeKeys: Set<String>,
    checkTaskState: RssCheckTaskState,
    onAction: (RssSourceManageAction) -> Unit,
) {
    val enabledFilter = stringResource(R.string.enabled)
    val disabledFilter = stringResource(R.string.disabled)
    val loginFilter = stringResource(R.string.need_login)
    val noGroupFilter = stringResource(R.string.no_group)
    val specialQueries = remember(
        enabledFilter,
        disabledFilter,
        loginFilter,
        noGroupFilter,
    ) {
        setOf(
            enabledFilter,
            disabledFilter,
            loginFilter,
            noGroupFilter,
            RSS_SINGLE_URL_QUERY,
        )
    }
    val searchQuery = query.takeUnless {
        it in specialQueries ||
            it.startsWith(RSS_GROUP_QUERY_PREFIX) ||
            it.startsWith(RSS_TYPE_QUERY_PREFIX)
    }.orEmpty()

    val dedupeTree = remember(sources, dedupeMode, preferredPrimaries) {
        if (dedupeMode == RssSourceDedupeMode.NONE) emptyList()
        else RssSourceDedupe.buildTree(sources, dedupeMode, preferredPrimaries)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        RssSourceManageTopBar(
            query = searchQuery,
            activeQuery = query,
            groups = groups,
            sort = sort,
            sortAscending = sortAscending,
            viewMode = viewMode,
            dedupeMode = dedupeMode,
            onQueryChange = { onAction(RssSourceManageAction.QueryChanged(it)) },
            onBack = { onAction(RssSourceManageAction.Back) },
            onAction = onAction,
        )
        RssSourceManagePanel(
            sources = sources,
            query = query,
            selectedUrls = selectedUrls,
            viewMode = viewMode,
            sort = sort,
            expandedSections = expandedSections,
            dedupeMode = dedupeMode,
            dedupeTree = dedupeTree,
            preferredPrimaries = preferredPrimaries,
            expandedDedupeKeys = expandedDedupeKeys,
            onAction = onAction,
            modifier = Modifier.weight(1f),
        )
        if (checkTaskState.showManageEntry) {
            RssSourceCheckTaskEntry(
                state = checkTaskState,
                onOpen = { onAction(RssSourceManageAction.OpenCheckTask) },
                onStop = { onAction(RssSourceManageAction.StopCheckTask) },
                onDismiss = { onAction(RssSourceManageAction.DismissCheckTask) },
            )
        }
        val expandableDedupeKeys = remember(dedupeTree) {
            dedupeTree.filter { it.hasDuplicates }.mapTo(hashSetOf()) { it.groupKey }
        }
        val allDedupeExpanded = expandableDedupeKeys.isNotEmpty() &&
            expandableDedupeKeys.all { it in expandedDedupeKeys }
        val selectableVisibleSources = remember(sources, dedupeMode, dedupeTree, expandedDedupeKeys) {
            if (dedupeMode == RssSourceDedupeMode.NONE) {
                sources.distinctBy(RssSource::sourceUrl)
            } else {
                buildList(sources.size) {
                    dedupeTree.forEach { node ->
                        if (node.hasDuplicates && node.groupKey in expandedDedupeKeys) {
                            addAll(node.allSources)
                        } else {
                            add(node.primary)
                        }
                    }
                }
            }
        }
        val totalCount = selectableVisibleSources.size
        val selectAllState = remember(selectableVisibleSources, selectedUrls) {
            rssSelectionStateOf(selectableVisibleSources, selectedUrls)
        }
        val selectedAllEnabled = remember(sources, selectedUrls) {
            if (selectedUrls.isEmpty()) false
            else sources.filter { it.sourceUrl in selectedUrls }.let { list ->
                list.isNotEmpty() && list.all { it.enabled }
            }
        }
        RssSourceManageBottomDock(
            selectedCount = selectedUrls.size,
            totalCount = totalCount,
            selectAllState = selectAllState,
            selectedAllEnabled = selectedAllEnabled,
            showDedupeExpandAll = dedupeMode != RssSourceDedupeMode.NONE,
            allDedupeExpanded = allDedupeExpanded,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
            onAction = onAction,
        )
    }
}

@Composable
private fun RssSourceCheckTaskEntry(
    state: RssCheckTaskState,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = state.status == RssCheckTaskStatus.RUNNING
    val text = if (running) {
        "正在校验 (${state.processedCount}/${state.totalCount}) ${state.currentSourceName}"
    } else {
        "校验完成：通过 ${state.passedCount} · 失效 ${state.failedCount}"
    }
    NgGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 6.dp, end = 14.dp, bottom = 2.dp),
        role = NgMaterialRole.CONTROL,
        shape = RoundedCornerShape(12.dp),
        style = NgGlassDefaults.bookDetailStyle(
            containerColor = colorResource(R.color.ng_surface_card),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpen),
        ) {
            if (running) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(state.progressFraction.coerceIn(0f, 1f))
                        .background(Color(NgTheme.colors.primary).copy(alpha = 0.16f)),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check_source),
                    contentDescription = null,
                    tint = Color(NgTheme.colors.primary),
                    modifier = Modifier.size(19.dp),
                )
                Text(
                    text = text,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    color = Color(NgTheme.colors.onSurface),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (running) {
                    Text(
                        text = stringResource(R.string.stop),
                        color = colorResource(R.color.ng_error),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onStop)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_close),
                            contentDescription = stringResource(R.string.close),
                            tint = Color(NgTheme.colors.onSurfaceVariant),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RssSourceManageTopBar(
    query: String,
    activeQuery: String,
    groups: List<String>,
    sort: RssSourceSort,
    sortAscending: Boolean,
    viewMode: RssSourceViewMode,
    dedupeMode: RssSourceDedupeMode,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onAction: (RssSourceManageAction) -> Unit,
) {
    val filterMenuState = remember { NgPopupToggleState() }
    val mainMenuState = remember { NgPopupToggleState() }
    val enabledFilter = stringResource(R.string.enabled)
    val disabledFilter = stringResource(R.string.disabled)
    val loginFilter = stringResource(R.string.need_login)
    val noGroupFilter = stringResource(R.string.no_group)
    val statusFilterQueries = remember(enabledFilter, disabledFilter, loginFilter) {
        setOf(enabledFilter, disabledFilter, loginFilter)
    }

    val filterItems = remember(
        groups,
        activeQuery,
        enabledFilter,
        disabledFilter,
        loginFilter,
        noGroupFilter,
    ) {
        listOf(
            NgExpandableActionMenuItem(
                FILTER_ALL_ITEM_ID,
                R.string.all,
                R.drawable.ic_select_all,
                checked = activeQuery.isBlank(),
            ),
            NgExpandableActionMenuItem(
                FILTER_TYPE_PARENT_ITEM_ID,
                R.string.book_source_filter_type,
                R.drawable.ic_sort,
                children = listOf(
                    NgExpandableActionMenuItem(
                        FILTER_TYPE_WEB_ITEM_ID,
                        0,
                        R.drawable.ic_web_outline,
                        title = "网页",
                        checked = activeQuery == "${RSS_TYPE_QUERY_PREFIX}0",
                    ),
                    NgExpandableActionMenuItem(
                        FILTER_TYPE_IMAGE_ITEM_ID,
                        R.string.book_source_filter_type_image,
                        R.drawable.ic_image,
                        checked = activeQuery == "${RSS_TYPE_QUERY_PREFIX}1",
                    ),
                    NgExpandableActionMenuItem(
                        FILTER_TYPE_VIDEO_ITEM_ID,
                        R.string.book_source_filter_type_video,
                        R.drawable.ic_ai_capability_video,
                        checked = activeQuery == "${RSS_TYPE_QUERY_PREFIX}2",
                    ),
                    NgExpandableActionMenuItem(
                        FILTER_TYPE_SINGLE_ITEM_ID,
                        R.string.single_url,
                        R.drawable.ic_web_outline,
                        checked = activeQuery == RSS_SINGLE_URL_QUERY,
                    ),
                ),
            ),
            NgExpandableActionMenuItem(
                FILTER_STATUS_PARENT_ITEM_ID,
                R.string.book_source_filter_status,
                R.drawable.ic_check_circle_outline,
                children = listOf(
                    NgExpandableActionMenuItem(
                        FILTER_ENABLED_ITEM_ID,
                        R.string.enabled,
                        R.drawable.ic_check,
                        checked = activeQuery == enabledFilter,
                    ),
                    NgExpandableActionMenuItem(
                        FILTER_DISABLED_ITEM_ID,
                        R.string.disabled,
                        R.drawable.ic_block_outline,
                        checked = activeQuery == disabledFilter,
                    ),
                    NgExpandableActionMenuItem(
                        FILTER_LOGIN_ITEM_ID,
                        R.string.need_login,
                        R.drawable.ic_lock_outline,
                        checked = activeQuery == loginFilter,
                    ),
                ),
            ),
            NgExpandableActionMenuItem(
                FILTER_GROUP_PARENT_ITEM_ID,
                R.string.book_source_filter_group,
                R.drawable.ic_groups,
                children = buildList {
                    add(
                        NgExpandableActionMenuItem(
                            FILTER_NO_GROUP_ITEM_ID,
                            R.string.no_group,
                            R.drawable.ic_clear,
                            checked = activeQuery == noGroupFilter,
                        )
                    )
                    groups.forEachIndexed { index, group ->
                        add(
                            NgExpandableActionMenuItem(
                                GROUP_ITEM_ID_BASE + index,
                                0,
                                R.drawable.ic_groups,
                                title = group,
                                checked = activeQuery == "$RSS_GROUP_QUERY_PREFIX$group",
                            )
                        )
                    }
                },
            ),
        )
    }

    val sortItems = remember(sort, sortAscending) {
        listOf(
            NgExpandableActionMenuItem(
                SORT_DIRECTION_ITEM_ID,
                R.string.sort_desc,
                R.drawable.ic_swap_vert,
                checked = !sortAscending,
            ),
            NgExpandableActionMenuItem(
                R.id.menu_sort_manual,
                R.string.sort_manual,
                R.drawable.ic_drag_handle,
                checked = sort == RssSourceSort.Default,
            ),
            NgExpandableActionMenuItem(
                R.id.menu_sort_name,
                R.string.sort_by_name,
                R.drawable.ic_sort,
                checked = sort == RssSourceSort.Name,
            ),
            NgExpandableActionMenuItem(
                R.id.menu_sort_url,
                R.string.sort_by_url,
                R.drawable.ic_web_outline,
                checked = sort == RssSourceSort.Url,
            ),
            NgExpandableActionMenuItem(
                R.id.menu_sort_time,
                R.string.sort_by_lastUpdateTime,
                R.drawable.ic_history,
                checked = sort == RssSourceSort.Update,
            ),
            NgExpandableActionMenuItem(
                R.id.menu_sort_enable,
                R.string.is_enabled,
                R.drawable.ic_check_circle_outline,
                checked = sort == RssSourceSort.Enable,
            ),
        )
    }

    val dedupeItems = remember(dedupeMode) {
        listOf(
            NgExpandableActionMenuItem(
                DEDUPE_NONE_ITEM_ID,
                R.string.book_source_dedupe_none,
                R.drawable.ic_clear,
                checked = dedupeMode == RssSourceDedupeMode.NONE,
            ),
            NgExpandableActionMenuItem(
                DEDUPE_URL_ITEM_ID,
                R.string.book_source_dedupe_url,
                R.drawable.ic_web_outline,
                checked = dedupeMode == RssSourceDedupeMode.BY_URL,
            ),
            NgExpandableActionMenuItem(
                DEDUPE_NAME_ITEM_ID,
                R.string.book_source_dedupe_name,
                R.drawable.ic_sort,
                checked = dedupeMode == RssSourceDedupeMode.BY_NAME,
            ),
        )
    }

    val mainMenuItems = remember(sortItems, dedupeItems) {
        listOf(
            NgExpandableActionMenuItem(
                R.id.menu_add,
                R.string.add_rss_source,
                R.drawable.ic_add,
            ),
            NgExpandableActionMenuItem(
                IMPORT_PARENT_ITEM_ID,
                R.string.import_rss_source,
                R.drawable.ic_import,
                dividerBefore = true,
                children = listOf(
                    NgExpandableActionMenuItem(
                        R.id.menu_import_local,
                        R.string.import_local,
                        R.drawable.ic_folder_open,
                    ),
                    NgExpandableActionMenuItem(
                        R.id.menu_import_onLine,
                        R.string.import_on_line,
                        R.drawable.ic_outline_cloud_24,
                    ),
                    NgExpandableActionMenuItem(
                        R.id.menu_import_qr,
                        R.string.import_by_qr_code,
                        R.drawable.ic_scan,
                    ),
                    NgExpandableActionMenuItem(
                        R.id.menu_import_default,
                        R.string.import_default_rule,
                        R.drawable.ic_restore,
                    ),
                ),
            ),
            NgExpandableActionMenuItem(
                R.id.menu_group_manage,
                R.string.group_manage,
                R.drawable.ic_settings,
                dividerBefore = true,
            ),
            NgExpandableActionMenuItem(
                DEDUPE_PARENT_ITEM_ID,
                R.string.book_source_dedupe_manage,
                R.drawable.ic_groups,
                children = dedupeItems,
            ),
            NgExpandableActionMenuItem(
                R.id.action_sort,
                R.string.sort,
                R.drawable.ic_sort,
                dividerBefore = true,
                children = sortItems,
            ),
            NgExpandableActionMenuItem(
                R.id.menu_help,
                R.string.help,
                R.drawable.ic_help,
            ),
        )
    }

    val filterDefaultExpandedItemIds = remember(activeQuery, noGroupFilter, statusFilterQueries) {
        when {
            activeQuery.startsWith(RSS_TYPE_QUERY_PREFIX) || activeQuery == RSS_SINGLE_URL_QUERY ->
                setOf(FILTER_TYPE_PARENT_ITEM_ID)
            activeQuery in statusFilterQueries ->
                setOf(FILTER_STATUS_PARENT_ITEM_ID)
            activeQuery == noGroupFilter || activeQuery.startsWith(RSS_GROUP_QUERY_PREFIX) ->
                setOf(FILTER_GROUP_PARENT_ITEM_ID)
            else -> emptySet()
        }
    }

    val filterActive = activeQuery in statusFilterQueries ||
        activeQuery == noGroupFilter ||
        activeQuery == RSS_SINGLE_URL_QUERY ||
        activeQuery.startsWith(RSS_GROUP_QUERY_PREFIX) ||
        activeQuery.startsWith(RSS_TYPE_QUERY_PREFIX)
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
                hint = stringResource(R.string.search_rss_source),
                modifier = Modifier.weight(1f),
                variant = NgSearchBarVariant.TOOLBAR,
                containerColor = Color.Transparent,
                hideHintOnFocus = true,
            )
            Spacer(Modifier.width(8.dp))
            RssTopBarPopupButton(
                iconRes = R.drawable.ic_screen,
                iconSize = 14.dp,
                contentDescription = stringResource(R.string.screen),
                tint = if (filterActive) activeActionColor else actionColor,
                state = filterMenuState,
                items = filterItems,
                defaultExpandedItemIds = filterDefaultExpandedItemIds,
                onItemClick = { item ->
                    when (item.itemId) {
                        FILTER_ALL_ITEM_ID -> onAction(RssSourceManageAction.QueryChanged(""))
                        FILTER_TYPE_WEB_ITEM_ID -> onAction(RssSourceManageAction.QueryChanged("${RSS_TYPE_QUERY_PREFIX}0"))
                        FILTER_TYPE_IMAGE_ITEM_ID -> onAction(RssSourceManageAction.QueryChanged("${RSS_TYPE_QUERY_PREFIX}1"))
                        FILTER_TYPE_VIDEO_ITEM_ID -> onAction(RssSourceManageAction.QueryChanged("${RSS_TYPE_QUERY_PREFIX}2"))
                        FILTER_TYPE_SINGLE_ITEM_ID -> onAction(RssSourceManageAction.QueryChanged(RSS_SINGLE_URL_QUERY))
                        FILTER_ENABLED_ITEM_ID -> onAction(RssSourceManageAction.QueryChanged(enabledFilter))
                        FILTER_DISABLED_ITEM_ID -> onAction(RssSourceManageAction.QueryChanged(disabledFilter))
                        FILTER_LOGIN_ITEM_ID -> onAction(RssSourceManageAction.QueryChanged(loginFilter))
                        FILTER_NO_GROUP_ITEM_ID -> onAction(RssSourceManageAction.QueryChanged(noGroupFilter))
                        else -> groups.getOrNull(item.itemId - GROUP_ITEM_ID_BASE)?.let {
                            onAction(RssSourceManageAction.QueryChanged("$RSS_GROUP_QUERY_PREFIX$it"))
                        }
                    }
                },
            )
            Spacer(Modifier.width(2.dp))
            RssViewModeButton(
                viewMode = viewMode,
                tint = actionColor,
                onAction = onAction,
            )
            Spacer(Modifier.width(2.dp))
            RssTopBarPopupButton(
                iconRes = R.drawable.ic_grid_menu,
                contentDescription = stringResource(R.string.menu),
                tint = if (dedupeMode != RssSourceDedupeMode.NONE) activeActionColor else actionColor,
                state = mainMenuState,
                items = mainMenuItems,
                defaultExpandedItemIds = if (dedupeMode != RssSourceDedupeMode.NONE) {
                    setOf(DEDUPE_PARENT_ITEM_ID)
                } else {
                    emptySet()
                },
                onItemClick = { item ->
                    when (item.itemId) {
                        R.id.menu_add -> onAction(RssSourceManageAction.Add)
                        R.id.menu_import_local -> onAction(RssSourceManageAction.ImportLocal)
                        R.id.menu_import_onLine -> onAction(RssSourceManageAction.ImportOnline)
                        R.id.menu_import_qr -> onAction(RssSourceManageAction.ImportQr)
                        R.id.menu_import_default -> onAction(RssSourceManageAction.ImportDefault)
                        R.id.menu_group_manage -> onAction(RssSourceManageAction.ManageGroups)
                        R.id.menu_help -> onAction(RssSourceManageAction.Help)
                        SORT_DIRECTION_ITEM_ID -> onAction(RssSourceManageAction.ToggleSortDirection)
                        R.id.menu_sort_manual -> onAction(RssSourceManageAction.SortChanged(RssSourceSort.Default))
                        R.id.menu_sort_name -> onAction(RssSourceManageAction.SortChanged(RssSourceSort.Name))
                        R.id.menu_sort_url -> onAction(RssSourceManageAction.SortChanged(RssSourceSort.Url))
                        R.id.menu_sort_time -> onAction(RssSourceManageAction.SortChanged(RssSourceSort.Update))
                        R.id.menu_sort_enable -> onAction(RssSourceManageAction.SortChanged(RssSourceSort.Enable))
                        DEDUPE_NONE_ITEM_ID -> onAction(RssSourceManageAction.DedupeModeChanged(RssSourceDedupeMode.NONE))
                        DEDUPE_URL_ITEM_ID -> onAction(RssSourceManageAction.DedupeModeChanged(RssSourceDedupeMode.BY_URL))
                        DEDUPE_NAME_ITEM_ID -> onAction(RssSourceManageAction.DedupeModeChanged(RssSourceDedupeMode.BY_NAME))
                    }
                },
            )
        }
    }
}

@Composable
private fun RssTopBarActionButton(
    iconRes: Int,
    iconSize: Dp = 20.dp,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun RssTopBarPopupButton(
    iconRes: Int,
    iconSize: Dp = 20.dp,
    contentDescription: String,
    tint: Color,
    state: NgPopupToggleState,
    items: List<NgExpandableActionMenuItem>,
    defaultExpandedItemIds: Set<Int> = emptySet(),
    onItemClick: (NgExpandableActionMenuItem) -> Unit,
) {
    Box {
        RssTopBarActionButton(
            iconRes = iconRes,
            iconSize = iconSize,
            contentDescription = contentDescription,
            tint = tint,
            onClick = state::onAnchorClick,
        )
        NgExpandableActionMenu(
            expanded = state.expanded,
            onDismissRequest = state::onDismissRequest,
            items = items,
            defaultExpandedItemIds = defaultExpandedItemIds,
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
private fun RssViewModeButton(
    viewMode: RssSourceViewMode,
    tint: Color,
    onAction: (RssSourceManageAction) -> Unit,
) {
    val nextMode = when (viewMode) {
        RssSourceViewMode.LIST -> RssSourceViewMode.GROUP
        RssSourceViewMode.GROUP -> RssSourceViewMode.LIST
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onAction(RssSourceManageAction.ViewModeChanged(nextMode)) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                when (viewMode) {
                    RssSourceViewMode.LIST -> R.drawable.ic_chapter_list
                    RssSourceViewMode.GROUP -> R.drawable.ic_groups
                }
            ),
            contentDescription = stringResource(
                when (nextMode) {
                    RssSourceViewMode.LIST -> R.string.book_source_view_list_title
                    RssSourceViewMode.GROUP -> R.string.book_source_view_group_title
                }
            ),
            tint = tint,
            modifier = Modifier.size(
                when (viewMode) {
                    RssSourceViewMode.LIST -> 20.dp
                    RssSourceViewMode.GROUP -> 17.dp
                }
            ),
        )
    }
}

@Composable
private fun RssSourceManagePanel(
    sources: List<RssSource>,
    query: String,
    selectedUrls: Set<String>,
    viewMode: RssSourceViewMode,
    sort: RssSourceSort,
    expandedSections: Set<String>,
    dedupeMode: RssSourceDedupeMode,
    dedupeTree: List<RssSourceDedupeNode>,
    preferredPrimaries: Map<String, String>,
    expandedDedupeKeys: Set<String>,
    onAction: (RssSourceManageAction) -> Unit,
    modifier: Modifier = Modifier,
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
        when {
            sources.isEmpty() -> NgGlassSurface(
                modifier = Modifier.fillMaxSize(),
                role = NgMaterialRole.CONTENT,
                shape = panelShape,
                style = panelStyle,
                liquidCornerRadius = NgTheme.shapes.mediumDp.dp,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.empty),
                        color = Color(NgTheme.colors.onSurfaceVariant),
                        fontSize = 14.sp,
                    )
                }
            }

            viewMode == RssSourceViewMode.GROUP -> RssSourceGroupedList(
                sources = sources,
                selectedUrls = selectedUrls,
                expandedSections = expandedSections,
                dedupeMode = dedupeMode,
                preferredPrimaries = preferredPrimaries,
                expandedDedupeKeys = expandedDedupeKeys,
                onAction = onAction,
            )

            else -> NgGlassSurface(
                modifier = Modifier.fillMaxSize(),
                role = NgMaterialRole.CONTENT,
                shape = panelShape,
                style = panelStyle,
                liquidCornerRadius = NgTheme.shapes.mediumDp.dp,
            ) {
                if (dedupeMode == RssSourceDedupeMode.NONE) {
                    RssSourceFlatList(
                        sources = sources,
                        selectedUrls = selectedUrls,
                        canReorder = query.isBlank() &&
                            viewMode == RssSourceViewMode.LIST &&
                            sort == RssSourceSort.Default,
                        onAction = onAction,
                    )
                } else {
                    RssSourceDedupeTreeList(
                        nodes = dedupeTree,
                        selectedUrls = selectedUrls,
                        expandedDedupeKeys = expandedDedupeKeys,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun RssSourceFlatList(
    sources: List<RssSource>,
    selectedUrls: Set<String>,
    canReorder: Boolean,
    onAction: (RssSourceManageAction) -> Unit,
) {
    val sourceVersions = sources.map { System.identityHashCode(it) }
    var orderedSources by remember(sourceVersions) { mutableStateOf(sources) }
    val listState = rememberLazyListState()
    var isReordering by remember { mutableStateOf(false) }
    val currentOnDragStopped by rememberUpdatedState {
        isReordering = false
        onAction(RssSourceManageAction.Reorder(orderedSources))
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (canReorder && from.index in orderedSources.indices &&
            to.index in orderedSources.indices
        ) {
            orderedSources = orderedSources.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        }
    }
    val slideSelectState = rememberNgLazySlideSelectState(
        listState = listState,
        isSelected = { index ->
            orderedSources.getOrNull(index)?.sourceUrl?.let(selectedUrls::contains) == true
        },
        onSelectionChange = { index, selected ->
            orderedSources.getOrNull(index)?.let {
                onAction(RssSourceManageAction.SelectionChanged(it, selected))
            }
        },
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .ngSlideSelect(
                state = slideSelectState,
                enabled = orderedSources.isNotEmpty() && !isReordering,
                slideAreaStart = 0.dp,
                slideAreaEnd = 36.dp,
            ),
        state = listState,
    ) {
        itemsIndexed(
            items = orderedSources,
            key = { _, source -> source.sourceUrl },
        ) { index, source ->
            ReorderableItem(
                state = reorderState,
                key = source.sourceUrl,
            ) { _ ->
                val sortDescription = stringResource(R.string.sort)
                RssSourceManageRow(
                    source = source,
                    selected = source.sourceUrl in selectedUrls,
                    showDivider = index < orderedSources.lastIndex,
                    manualSort = canReorder,
                    onAction = onAction,
                    modifier = Modifier,
                    bodyDragModifier = if (canReorder) {
                        Modifier
                            .longPressDraggableHandle(
                                onDragStarted = { isReordering = true },
                                onDragStopped = { currentOnDragStopped() },
                            )
                            .semantics { contentDescription = sortDescription }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private sealed interface RssSourceTreeRowItem {
    val key: String

    data class Primary(
        val node: RssSourceDedupeNode,
        val expanded: Boolean,
    ) : RssSourceTreeRowItem {
        override val key: String = "p:${node.groupKey}:${node.primary.sourceUrl}"
    }

    data class Duplicate(
        val parentGroupKey: String,
        val source: RssSource,
        val isPrimary: Boolean,
    ) : RssSourceTreeRowItem {
        override val key: String = "d:$parentGroupKey:${source.sourceUrl}"
    }
}

@Composable
private fun RssSourceDedupeTreeList(
    nodes: List<RssSourceDedupeNode>,
    selectedUrls: Set<String>,
    expandedDedupeKeys: Set<String>,
    onAction: (RssSourceManageAction) -> Unit,
) {
    val rows = remember(nodes, expandedDedupeKeys) {
        buildList(nodes.size) {
            nodes.forEach { node ->
                val expanded = node.hasDuplicates && node.groupKey in expandedDedupeKeys
                add(RssSourceTreeRowItem.Primary(node = node, expanded = expanded))
                if (expanded) {
                    val primaryUrl = node.primary.sourceUrl
                    node.allSources.forEach { child ->
                        add(
                            RssSourceTreeRowItem.Duplicate(
                                parentGroupKey = node.groupKey,
                                source = child,
                                isPrimary = child.sourceUrl == primaryUrl,
                            )
                        )
                    }
                }
            }
        }
    }
    val listState = rememberLazyListState()
    val slideSelectState = rememberNgLazySlideSelectState(
        listState = listState,
        isSelected = { index ->
            when (val item = rows.getOrNull(index)) {
                is RssSourceTreeRowItem.Primary -> {
                    if (item.expanded) {
                        rssSelectionStateOf(item.node.allSources, selectedUrls) == ToggleableState.On
                    } else {
                        item.node.primary.sourceUrl in selectedUrls
                    }
                }
                is RssSourceTreeRowItem.Duplicate -> item.source.sourceUrl in selectedUrls
                null -> false
            }
        },
        onSelectionChange = { index, selected ->
            when (val item = rows.getOrNull(index)) {
                is RssSourceTreeRowItem.Primary -> {
                    if (item.expanded) {
                        onAction(RssSourceManageAction.SectionSelectionChanged(item.node.allSources, selected))
                    } else {
                        onAction(RssSourceManageAction.SelectionChanged(item.node.primary, selected))
                    }
                }
                is RssSourceTreeRowItem.Duplicate -> {
                    onAction(RssSourceManageAction.SelectionChanged(item.source, selected))
                }
                null -> Unit
            }
        },
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .ngSlideSelect(
                state = slideSelectState,
                enabled = rows.isNotEmpty(),
                slideAreaStart = 28.dp,
                slideAreaEnd = 64.dp,
            ),
        state = listState,
    ) {
        itemsIndexed(
            items = rows,
            key = { _, item -> item.key },
        ) { index, item ->
            val showDivider = index < rows.lastIndex
            when (item) {
                is RssSourceTreeRowItem.Primary -> {
                    val branchSelectionState = if (item.expanded) {
                        rssSelectionStateOf(item.node.allSources, selectedUrls)
                    } else {
                        null
                    }
                    RssSourceManageRow(
                        source = item.node.primary,
                        selected = item.node.primary.sourceUrl in selectedUrls,
                        showDivider = showDivider,
                        manualSort = false,
                        onAction = onAction,
                        modifier = Modifier,
                        bodyDragModifier = null,
                        showDedupeToggle = true,
                        hasDedupeChildren = item.node.hasDuplicates,
                        dedupeExpanded = item.expanded,
                        dedupeDuplicateCount = if (item.node.hasDuplicates) item.node.allSources.size else 0,
                        selectionState = branchSelectionState,
                        onTriStateSelectionToggle = if (branchSelectionState != null) {
                            {
                                val selectAll = branchSelectionState != ToggleableState.On
                                onAction(
                                    RssSourceManageAction.SectionSelectionChanged(
                                        item.node.allSources,
                                        selectAll,
                                    )
                                )
                            }
                        } else {
                            null
                        },
                        onToggleDedupe = {
                            onAction(RssSourceManageAction.ToggleDedupeNode(item.node.groupKey))
                        },
                    )
                }

                is RssSourceTreeRowItem.Duplicate -> {
                    RssSourceManageRow(
                        source = item.source,
                        selected = item.source.sourceUrl in selectedUrls,
                        showDivider = showDivider,
                        manualSort = false,
                        onAction = onAction,
                        modifier = Modifier.background(Color(NgTheme.colors.surfaceContainerLow)),
                        bodyDragModifier = null,
                        isDedupeChild = true,
                        isDedupeChildPrimary = item.isPrimary,
                        onPromoteDedupePrimary = {
                            onAction(
                                RssSourceManageAction.SetDedupePrimary(
                                    groupKey = item.parentGroupKey,
                                    source = item.source,
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}

private data class RssSourceSectionKey(val key: String, val title: String)

private data class RssSourceSectionUi(
    val key: String,
    val title: String,
    val sources: List<RssSource>,
)

@Composable
private fun RssSourceGroupedList(
    sources: List<RssSource>,
    selectedUrls: Set<String>,
    expandedSections: Set<String>,
    dedupeMode: RssSourceDedupeMode,
    preferredPrimaries: Map<String, String>,
    expandedDedupeKeys: Set<String>,
    onAction: (RssSourceManageAction) -> Unit,
) {
    val noGroup = stringResource(R.string.no_group)
    val sectionVersion = sources.map {
        Triple(it.sourceUrl, it.sourceName, it.sourceGroup)
    }
    val sections = remember(sectionVersion, noGroup) {
        sources
            .flatMap { source ->
                val sourceGroups = source.sourceGroup
                    ?.splitNotBlank(AppPattern.splitGroupRegex)
                    .orEmpty()
                if (sourceGroups.isEmpty()) {
                    listOf(RssSourceSectionKey("group:", noGroup) to source)
                } else {
                    sourceGroups.map { group ->
                        RssSourceSectionKey("group:$group", group) to source
                    }
                }
            }
            .groupBy(
                keySelector = { it.first },
                valueTransform = { it.second },
            )
            .map { (section, groupedSources) ->
                RssSourceSectionUi(
                    key = section.key,
                    title = section.title,
                    sources = groupedSources.distinctBy(RssSource::sourceUrl),
                )
            }
            .sortedWith(compareBy<RssSourceSectionUi> { it.key != "group:" }.thenBy { it.title })
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        sections.forEach { section ->
            item(key = "section:${section.key}") {
                RssSourceSectionHeader(
                    section = section,
                    expanded = section.key in expandedSections,
                    selectionState = rssSelectionStateOf(section.sources, selectedUrls),
                    onAction = onAction,
                )
            }
            if (section.key in expandedSections) {
                if (dedupeMode == RssSourceDedupeMode.NONE) {
                    items(
                        items = section.sources,
                        key = { source -> "${section.key}:${source.sourceUrl}" },
                    ) { source ->
                        RssSourceManageRow(
                            source = source,
                            selected = source.sourceUrl in selectedUrls,
                            showDivider = true,
                            manualSort = false,
                            onAction = onAction,
                            modifier = Modifier.background(colorResource(R.color.ng_surface_card)),
                            bodyDragModifier = null,
                        )
                    }
                } else {
                    val sectionTree = RssSourceDedupe.buildTree(
                        section.sources,
                        dedupeMode,
                        preferredPrimaries,
                    )
                    val sectionRows = buildList(section.sources.size) {
                        sectionTree.forEach { node ->
                            val expanded = node.hasDuplicates && node.groupKey in expandedDedupeKeys
                            add(RssSourceTreeRowItem.Primary(node = node, expanded = expanded))
                            if (expanded) {
                                val primaryUrl = node.primary.sourceUrl
                                node.allSources.forEach { child ->
                                    add(
                                        RssSourceTreeRowItem.Duplicate(
                                            parentGroupKey = node.groupKey,
                                            source = child,
                                            isPrimary = child.sourceUrl == primaryUrl,
                                        )
                                    )
                                }
                            }
                        }
                    }
                    items(
                        items = sectionRows,
                        key = { item -> "${section.key}:${item.key}" },
                    ) { item ->
                        when (item) {
                            is RssSourceTreeRowItem.Primary -> {
                                val branchSelectionState = if (item.expanded) {
                                    rssSelectionStateOf(item.node.allSources, selectedUrls)
                                } else {
                                    null
                                }
                                RssSourceManageRow(
                                    source = item.node.primary,
                                    selected = item.node.primary.sourceUrl in selectedUrls,
                                    showDivider = true,
                                    manualSort = false,
                                    onAction = onAction,
                                    modifier = Modifier.background(colorResource(R.color.ng_surface_card)),
                                    bodyDragModifier = null,
                                    showDedupeToggle = true,
                                    hasDedupeChildren = item.node.hasDuplicates,
                                    dedupeExpanded = item.expanded,
                                    dedupeDuplicateCount = if (item.node.hasDuplicates) item.node.allSources.size else 0,
                                    selectionState = branchSelectionState,
                                    onTriStateSelectionToggle = if (branchSelectionState != null) {
                                        {
                                            val selectAll = branchSelectionState != ToggleableState.On
                                            onAction(
                                                RssSourceManageAction.SectionSelectionChanged(
                                                    item.node.allSources,
                                                    selectAll,
                                                )
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onToggleDedupe = {
                                        onAction(RssSourceManageAction.ToggleDedupeNode(item.node.groupKey))
                                    },
                                )
                            }

                            is RssSourceTreeRowItem.Duplicate -> {
                                RssSourceManageRow(
                                    source = item.source,
                                    selected = item.source.sourceUrl in selectedUrls,
                                    showDivider = true,
                                    manualSort = false,
                                    onAction = onAction,
                                    modifier = Modifier.background(Color(NgTheme.colors.surfaceContainerLow)),
                                    bodyDragModifier = null,
                                    isDedupeChild = true,
                                    isDedupeChildPrimary = item.isPrimary,
                                    onPromoteDedupePrimary = {
                                        onAction(
                                            RssSourceManageAction.SetDedupePrimary(
                                                groupKey = item.parentGroupKey,
                                                source = item.source,
                                            )
                                        )
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

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun RssSourceSectionHeader(
    section: RssSourceSectionUi,
    expanded: Boolean,
    selectionState: ToggleableState,
    onAction: (RssSourceManageAction) -> Unit,
) {
    val cardShape = RoundedCornerShape(10.dp)
    val cardStyle = NgGlassDefaults.bookDetailStyle(
        containerColor = colorResource(R.color.ng_surface_card)
    )
    NgVisualSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        role = NgMaterialRole.CONTROL,
        cornerRadius = 10.dp,
        shape = cardShape,
        style = cardStyle,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .combinedClickable(
                    onClick = { onAction(RssSourceManageAction.ToggleSection(section.key)) },
                    onLongClickLabel = stringResource(R.string.delete),
                    onLongClick = {
                        onAction(
                            RssSourceManageAction.DeleteSection(
                                groupName = section.key
                                    .removePrefix(RSS_GROUP_QUERY_PREFIX)
                                    .takeIf(String::isNotBlank),
                                title = section.title,
                                sources = section.sources,
                            )
                        )
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NgFileSelectionTriStateCheckbox(
                state = selectionState,
                onClick = {
                    val selectAll = selectionState != ToggleableState.On
                    onAction(RssSourceManageAction.SectionSelectionChanged(section.sources, selectAll))
                },
                variant = NgFileSelectionCheckboxVariant.COMPACT,
            )
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        colorResource(
                            if (section.title.isNormalRssSection()) R.color.ng_success
                            else R.color.ng_error
                        ).copy(alpha = 0.9f)
                    )
            )
            Text(
                text = section.title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                color = Color(NgTheme.colors.onSurface).copy(alpha = 0.9f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .widthIn(min = 28.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(NgTheme.colors.surfaceContainerLow)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = section.sources.size.toString(),
                    modifier = Modifier.padding(horizontal = 7.dp),
                    color = Color(NgTheme.colors.onSurfaceVariant).copy(alpha = 0.82f),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onAction(RssSourceManageAction.ToggleSection(section.key)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right_20),
                    contentDescription = null,
                    tint = Color(NgTheme.colors.onSurfaceVariant).copy(alpha = 0.82f),
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (expanded) 90f else 0f),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun RssSourceManageRow(
    source: RssSource,
    selected: Boolean,
    showDivider: Boolean,
    manualSort: Boolean,
    onAction: (RssSourceManageAction) -> Unit,
    modifier: Modifier,
    bodyDragModifier: Modifier?,
    showDedupeToggle: Boolean = false,
    hasDedupeChildren: Boolean = false,
    dedupeExpanded: Boolean = false,
    dedupeDuplicateCount: Int = 0,
    isDedupeChild: Boolean = false,
    isDedupeChildPrimary: Boolean = false,
    selectionState: ToggleableState? = null,
    onTriStateSelectionToggle: (() -> Unit)? = null,
    onToggleDedupe: (() -> Unit)? = null,
    onPromoteDedupePrimary: (() -> Unit)? = null,
) {
    val menuState = remember(source.sourceUrl) { NgPopupToggleState() }
    val titleColor = Color(NgTheme.colors.onSurface).copy(
        alpha = if (source.enabled) 1f else 0.52f,
    )
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var swipeOffsetPx by remember(source.sourceUrl) { mutableFloatStateOf(0f) }
    val swipeDeleteThreshold = rowWidthPx / 3f
    val reachedDeleteThreshold = rowWidthPx > 0 && -swipeOffsetPx >= swipeDeleteThreshold
    val dragState = rememberDraggableState { delta ->
        val maxSwipe = if (rowWidthPx > 0) rowWidthPx * 0.45f else 360f
        swipeOffsetPx = (swipeOffsetPx + delta).coerceIn(-maxSwipe, 0f)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .onSizeChanged { rowWidthPx = it.width }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        if (rowWidthPx > 0 && -swipeOffsetPx >= rowWidthPx / 3f) {
                            swipeOffsetPx = 0f
                            onAction(RssSourceManageAction.SwipeDelete(source))
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
                if (isDedupeChild) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = onPromoteDedupePrimary != null && !isDedupeChildPrimary) {
                                    onPromoteDedupePrimary?.invoke()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isDedupeChildPrimary) R.drawable.ic_star_filled_20
                                    else R.drawable.ic_star_outline_20
                                ),
                                contentDescription = null,
                                tint = if (isDedupeChildPrimary) {
                                    Color(NgTheme.colors.primary)
                                } else {
                                    Color(NgTheme.colors.onSurfaceVariant).copy(alpha = 0.65f)
                                },
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                } else if (showDedupeToggle) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (hasDedupeChildren) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (dedupeExpanded) {
                                            Color(NgTheme.colors.primary)
                                        } else {
                                            Color(NgTheme.colors.surfaceVariant)
                                        }
                                    )
                                    .clickable { onToggleDedupe?.invoke() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_chevron_right_20),
                                    contentDescription = null,
                                    tint = if (dedupeExpanded) {
                                        Color(NgTheme.colors.onPrimary)
                                    } else {
                                        Color(NgTheme.colors.onSurface)
                                    },
                                    modifier = Modifier
                                        .size(15.dp)
                                        .rotate(if (dedupeExpanded) 90f else 0f),
                                )
                            }
                        }
                    }
                }
                if (selectionState != null && onTriStateSelectionToggle != null) {
                    NgFileSelectionTriStateCheckbox(
                        state = selectionState,
                        onClick = onTriStateSelectionToggle,
                        variant = NgFileSelectionCheckboxVariant.COMPACT,
                    )
                } else {
                    NgFileSelectionCheckbox(
                        checked = selected,
                        onCheckedChange = {
                            onAction(RssSourceManageAction.SelectionChanged(source, it))
                        },
                        variant = NgFileSelectionCheckboxVariant.COMPACT,
                    )
                }
                val reachabilityMap by RssSourceReachabilityStore.statusFlow.collectAsState()
                val reachability = RssSourceReachabilityStore.resolve(source, reachabilityMap)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onAction(RssSourceManageAction.Edit(source)) }
                        .then(bodyDragModifier ?: Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(start = 2.dp, end = 6.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (reachability) {
                                    RssSourceReachability.REACHABLE -> Color(0xFF22C55E)
                                    RssSourceReachability.UNREACHABLE -> Color(0xFFEF4444)
                                    RssSourceReachability.UNKNOWN -> Color(NgTheme.colors.onSurfaceVariant).copy(alpha = 0.22f)
                                }
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = 4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = source.sourceName,
                            color = titleColor,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (showDedupeToggle && dedupeDuplicateCount > 1) {
                            RssSourceMiniTag(
                                text = "×$dedupeDuplicateCount",
                                containerColor = Color(NgTheme.colors.primaryContainer),
                                contentColor = Color(NgTheme.colors.primary),
                            )
                        }
                        RssSourceMiniTag(
                            text = source.rssTypeTagLabel(),
                            containerColor = Color(NgTheme.colors.primaryContainer),
                            contentColor = Color(NgTheme.colors.primary),
                        )
                        if (source.singleUrl) {
                            RssSourceMiniTag(
                                text = "单页",
                                containerColor = colorResource(R.color.ng_info_container),
                                contentColor = colorResource(R.color.ng_info),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                NgSwitchControl(
                    checked = source.enabled,
                    onCheckedChange = {
                        onAction(RssSourceManageAction.ToggleEnabled(source, it))
                    },
                    modifier = Modifier.padding(horizontal = 2.dp),
                    variant = NgSwitchControlVariant.COMPACT,
                )
                Box {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
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
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    NgExpandableActionMenu(
                        expanded = menuState.expanded,
                        onDismissRequest = menuState::onDismissRequest,
                        items = rssRowMenuItems(source, manualSort),
                        onItemClick = {
                            menuState.close()
                            onAction(
                                when (it.itemId) {
                                    R.id.menu_debug_source -> RssSourceManageAction.Debug(source)
                                    R.id.menu_login -> RssSourceManageAction.Login(source)
                                    R.id.menu_top -> RssSourceManageAction.Top(source)
                                    R.id.menu_bottom -> RssSourceManageAction.Bottom(source)
                                    else -> RssSourceManageAction.Delete(source)
                                }
                            )
                        },
                        width = 152.dp,
                    )
                }
                Spacer(Modifier.width(2.dp))
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp, end = 12.dp),
                thickness = 0.6.dp,
                color = Color(NgTheme.colors.outlineVariant).copy(alpha = 0.24f),
            )
        }
    }
}

@Composable
private fun rssRowMenuItems(
    source: RssSource,
    manualSort: Boolean,
): List<NgExpandableActionMenuItem> = buildList {
    add(NgExpandableActionMenuItem(R.id.menu_debug_source, R.string.debug, R.drawable.ic_bug_report))
    if (!source.loginUrl.isNullOrBlank()) {
        add(NgExpandableActionMenuItem(R.id.menu_login, R.string.login, R.drawable.ic_lock_outline))
    }
    if (manualSort) {
        add(
            NgExpandableActionMenuItem(
                R.id.menu_top,
                R.string.to_top,
                R.drawable.ic_arrow_drop_up,
                dividerBefore = true,
            )
        )
        add(NgExpandableActionMenuItem(R.id.menu_bottom, R.string.to_bottom, R.drawable.ic_arrow_down))
    }
    add(
        NgExpandableActionMenuItem(
            R.id.menu_del,
            R.string.delete,
            R.drawable.ic_book_info_delete,
            dividerBefore = true,
            danger = true,
        )
    )
}

@Composable
private fun RssSourceMiniTag(
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

private fun RssSource.rssTypeTagLabel(): String = when (type) {
    1 -> "图片"
    2 -> "视频"
    else -> "网页"
}

private fun String.isNormalRssSection(): Boolean {
    if (this == "校验超时" || this == "非HTTP源地址") return false
    return listOf("失效", "异常", "错误", "无效").none(::contains)
}

private fun rssSelectionStateOf(
    sources: List<RssSource>,
    selectedUrls: Set<String>,
): ToggleableState {
    if (sources.isEmpty()) return ToggleableState.Off
    var selectedCount = 0
    for (source in sources) {
        if (source.sourceUrl in selectedUrls) {
            selectedCount += 1
        }
    }
    return when (selectedCount) {
        0 -> ToggleableState.Off
        sources.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

@Composable
private fun SourceFilterChip(text: String, onClick: (String) -> Unit) {
    Surface(
        modifier = Modifier.clickable { onClick(text) },
        shape = RoundedCornerShape(14.dp),
        color = Color(NgTheme.colors.surfaceContainerLow),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = Color(NgTheme.colors.onSurface),
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

@Composable
internal fun RssSourceTextDialog(
    title: String,
    initialValue: String = "",
    placeholder: String? = null,
    suggestions: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NgFormField(
                    label = title,
                    value = value,
                    onValueChange = { value = it },
                    placeholder = placeholder ?: title,
                    variant = NgFormFieldVariant.PLAIN_UNDERLINE,
                )
                if (suggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.forEach { suggestion ->
                            SourceFilterChip(suggestion) { value = it }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun RssSourceConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun RssSourceTypeDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onSelectType: (Int) -> Unit,
) {
    val options = listOf(
        0 to "网页",
        1 to "图片",
        2 to "视频",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_source_type_manage)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "将已选的 $selectedCount 个订阅源批量设置为：",
                    color = Color(NgTheme.colors.onSurfaceVariant),
                    fontSize = 13.sp,
                )
                options.forEach { (typeValue, label) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectType(typeValue) },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(NgTheme.colors.surfaceContainerLow),
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            color = Color(NgTheme.colors.onSurface),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
