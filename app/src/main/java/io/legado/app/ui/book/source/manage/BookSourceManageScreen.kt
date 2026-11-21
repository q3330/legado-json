package io.legado.app.ui.book.source.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlin.math.roundToInt
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.source.BookSourceDedupe
import io.legado.app.help.source.BookSourceDedupeMode
import io.legado.app.help.source.BookSourceDedupeNode
import io.legado.app.help.source.BookSourceReachability
import io.legado.app.help.source.BookSourceReachabilityStore
import io.legado.app.model.CheckSourceTaskState
import io.legado.app.model.CheckSourceTaskStatus
import io.legado.app.ui.design.components.compose.NgExpandableActionMenu
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuItem
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuVariant
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckbox
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckboxVariant
import io.legado.app.ui.design.components.compose.NgFileSelectionTriStateCheckbox
import io.legado.app.ui.design.components.compose.NgFloatingToolbarBackButton
import io.legado.app.ui.design.components.compose.NgGlassDefaults
import io.legado.app.ui.design.components.compose.NgGlassSurface
import io.legado.app.ui.design.components.compose.NgMaterialRole
import io.legado.app.ui.design.components.compose.NgPopupToggleState
import io.legado.app.ui.design.components.compose.NgSearchBar
import io.legado.app.ui.design.components.compose.NgSearchBarVariant
import io.legado.app.ui.design.components.compose.NgVisualSurface
import io.legado.app.ui.design.components.compose.ngSlideSelect
import io.legado.app.ui.design.components.compose.rememberNgLazySlideSelectState
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.utils.splitNotBlank
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val ADD_JS_ITEM_ID = 0x56000001
private const val FILTER_TYPE_PARENT_ITEM_ID = 0x56000080
private const val FILTER_TYPE_TEXT_ITEM_ID = 0x56000081
private const val FILTER_TYPE_IMAGE_ITEM_ID = 0x56000082
private const val FILTER_TYPE_VIDEO_ITEM_ID = 0x56000083
private const val FILTER_TYPE_AUDIO_ITEM_ID = 0x56000084
private const val FILTER_TYPE_FILE_ITEM_ID = 0x56000085
private const val FILTER_STATUS_PARENT_ITEM_ID = 0x56000100
private const val FILTER_ALL_ITEM_ID = 0x56000101
private const val FILTER_GROUP_PARENT_ITEM_ID = 0x56000200
private const val DEDUPE_PARENT_ITEM_ID = 0x56000300
private const val DEDUPE_NONE_ITEM_ID = 0x56000301
private const val DEDUPE_URL_ITEM_ID = 0x56000302
private const val DEDUPE_NAME_ITEM_ID = 0x56000303
private const val GROUP_ITEM_ID_BASE = 0x56001000
private const val SORT_DIRECTION_ITEM_ID = 0x56003000
private const val GROUP_QUERY_PREFIX = "group:"
private const val TYPE_QUERY_PREFIX = "type:"

internal enum class BookSourceViewMode { LIST, GROUP }

internal sealed interface BookSourceManageAction {
    data object Back : BookSourceManageAction
    data object AddDeclarative : BookSourceManageAction
    data object AddJavaScript : BookSourceManageAction
    data object ImportLocal : BookSourceManageAction
    data object ImportOnline : BookSourceManageAction
    data object ImportQr : BookSourceManageAction
    data object ManageGroups : BookSourceManageAction
    data object ToggleSortDirection : BookSourceManageAction
    data object SelectAll : BookSourceManageAction
    data object ClearSelection : BookSourceManageAction
    data object InvertSelection : BookSourceManageAction
    data object EnableSelection : BookSourceManageAction
    data object DisableSelection : BookSourceManageAction
    data object ConfigureSelectionCapabilities : BookSourceManageAction
    data object ConfigureSelectionType : BookSourceManageAction
    data object AddSelectionToGroup : BookSourceManageAction
    data object ClearSelectionGroups : BookSourceManageAction
    data object AutoGroupSelection : BookSourceManageAction
    data object CheckSelection : BookSourceManageAction
    data object OpenCheckTask : BookSourceManageAction
    data object DismissCheckTask : BookSourceManageAction
    data object CompleteSelectionInterval : BookSourceManageAction
    data object TopSelection : BookSourceManageAction
    data object BottomSelection : BookSourceManageAction
    data object ExportOrShareSelection : BookSourceManageAction
    data object DeleteSelection : BookSourceManageAction
    data class QueryChanged(val query: String) : BookSourceManageAction
    data class ViewModeChanged(val mode: BookSourceViewMode) : BookSourceManageAction
    data class SortChanged(val sort: BookSourceSort) : BookSourceManageAction
    data class DedupeModeChanged(val mode: BookSourceDedupeMode) : BookSourceManageAction
    data class ToggleDedupeNode(val groupKey: String) : BookSourceManageAction
    data class SetDedupePrimary(val groupKey: String, val source: BookSourcePart) : BookSourceManageAction
    data object ToggleAllDedupe : BookSourceManageAction
    data class SelectionChanged(val source: BookSourcePart, val selected: Boolean) : BookSourceManageAction
    data class SectionSelectionChanged(val sources: List<BookSourcePart>, val selected: Boolean) : BookSourceManageAction
    data class ConfigureCapabilities(val source: BookSourcePart) : BookSourceManageAction
    data class Edit(val source: BookSourcePart) : BookSourceManageAction
    data class Login(val source: BookSourcePart) : BookSourceManageAction
    data class Search(val source: BookSourcePart) : BookSourceManageAction
    data class Debug(val source: BookSourcePart) : BookSourceManageAction
    data class Delete(val source: BookSourcePart) : BookSourceManageAction
    data class SwipeDelete(val source: BookSourcePart) : BookSourceManageAction
    data class Top(val source: BookSourcePart) : BookSourceManageAction
    data class Bottom(val source: BookSourcePart) : BookSourceManageAction
    data class Reorder(val sources: List<BookSourcePart>) : BookSourceManageAction
    data class ToggleSection(val key: String) : BookSourceManageAction
    data class DeleteSection(
        val groupName: String?,
        val title: String,
        val sources: List<BookSourcePart>,
    ) : BookSourceManageAction
}

@Composable
internal fun BookSourceManageScreen(
    sources: List<BookSourcePart>,
    groups: List<String>,
    query: String,
    selectedUrls: Set<String>,
    viewMode: BookSourceViewMode,
    sort: BookSourceSort,
    sortAscending: Boolean,
    expandedSections: Set<String>,
    dedupeMode: BookSourceDedupeMode,
    ruleScores: Map<String, Int>,
    preferredPrimaries: Map<String, String>,
    expandedDedupeKeys: Set<String>,
    checkTaskState: CheckSourceTaskState,
    onAction: (BookSourceManageAction) -> Unit,
) {
    val enabledFilter = stringResource(R.string.enabled)
    val disabledFilter = stringResource(R.string.disabled)
    val loginFilter = stringResource(R.string.need_login)
    val noGroupFilter = stringResource(R.string.no_group)
    val enabledExploreFilter = stringResource(R.string.enabled_explore)
    val disabledExploreFilter = stringResource(R.string.disabled_explore)
    val specialQueries = remember(
        enabledFilter,
        disabledFilter,
        loginFilter,
        noGroupFilter,
        enabledExploreFilter,
        disabledExploreFilter,
    ) {
        setOf(
            enabledFilter,
            disabledFilter,
            loginFilter,
            noGroupFilter,
            enabledExploreFilter,
            disabledExploreFilter,
        )
    }
    val searchQuery = query.takeUnless {
        it in specialQueries || it.startsWith(GROUP_QUERY_PREFIX) || it.startsWith(TYPE_QUERY_PREFIX)
    }.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        BookSourceManageTopBar(
            query = searchQuery,
            activeQuery = query,
            groups = groups,
            sort = sort,
            sortAscending = sortAscending,
            viewMode = viewMode,
            dedupeMode = dedupeMode,
            onQueryChange = { onAction(BookSourceManageAction.QueryChanged(it)) },
            onBack = { onAction(BookSourceManageAction.Back) },
            onAction = onAction,
        )
        val dedupeTree = remember(sources, dedupeMode, ruleScores, preferredPrimaries) {
            if (dedupeMode == BookSourceDedupeMode.NONE) emptyList()
            else BookSourceDedupe.buildTree(sources, dedupeMode, ruleScores, preferredPrimaries)
        }
        BookSourceManagePanel(
            sources = sources,
            query = query,
            selectedUrls = selectedUrls,
            viewMode = viewMode,
            sort = sort,
            expandedSections = expandedSections,
            dedupeMode = dedupeMode,
            dedupeTree = dedupeTree,
            ruleScores = ruleScores,
            preferredPrimaries = preferredPrimaries,
            expandedDedupeKeys = expandedDedupeKeys,
            onAction = onAction,
            modifier = Modifier.weight(1f),
        )
        if (checkTaskState.showManageEntry) {
            BookSourceCheckTaskEntry(
                state = checkTaskState,
                onOpen = { onAction(BookSourceManageAction.OpenCheckTask) },
                onDismiss = { onAction(BookSourceManageAction.DismissCheckTask) },
            )
        }
        val expandableDedupeKeys = remember(dedupeTree) {
            dedupeTree.filter { it.hasDuplicates }.mapTo(hashSetOf()) { it.groupKey }
        }
        val allDedupeExpanded = expandableDedupeKeys.isNotEmpty() &&
            expandableDedupeKeys.all { it in expandedDedupeKeys }
        val selectableVisibleSources = remember(sources, dedupeMode, dedupeTree, expandedDedupeKeys) {
            if (dedupeMode == BookSourceDedupeMode.NONE) {
                sources.distinctBy(BookSourcePart::bookSourceUrl)
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
            selectionStateOf(selectableVisibleSources, selectedUrls)
        }
        val selectedSingleType = remember(sources, selectedUrls) {
            if (selectedUrls.isEmpty()) return@remember null
            var singleType: Int? = null
            for (source in sources) {
                if (source.bookSourceUrl !in selectedUrls) continue
                if (singleType == null) {
                    singleType = source.bookSourceType
                } else if (singleType != source.bookSourceType) {
                    return@remember null
                }
            }
            singleType
        }
        BookSourceManageBottomDock(
            selectedCount = selectedUrls.size,
            totalCount = totalCount,
            selectAllState = selectAllState,
            selectedSingleType = selectedSingleType,
            showDedupeExpandAll = dedupeMode != BookSourceDedupeMode.NONE,
            allDedupeExpanded = allDedupeExpanded,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
            onAction = onAction,
        )
    }
}

@Composable
private fun BookSourceCheckTaskEntry(
    state: CheckSourceTaskState,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = state.status == CheckSourceTaskStatus.RUNNING
    val text = if (running) {
        stringResource(
            R.string.book_source_check_manage_running,
            state.processedCount,
            state.totalCount,
            state.currentSourceName,
        )
    } else {
        stringResource(
            R.string.book_source_check_manage_completed,
            state.passedCount,
            state.failedCount,
            state.blockedCount,
        )
    }
    val actionText = stringResource(
        if (running) {
            R.string.book_source_check_return_to_task
        } else {
            R.string.book_source_check_view_results
        }
    )
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
                    .padding(start = 14.dp, end = 4.dp),
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
                Text(
                    text = actionText,
                    color = Color(NgTheme.colors.primary),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (!running) {
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
                } else {
                    Spacer(Modifier.width(10.dp))
                }
            }
        }
    }
}

@Composable
private fun BookSourceManageTopBar(
    query: String,
    activeQuery: String,
    groups: List<String>,
    sort: BookSourceSort,
    sortAscending: Boolean,
    viewMode: BookSourceViewMode,
    dedupeMode: BookSourceDedupeMode,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onAction: (BookSourceManageAction) -> Unit,
) {
    val filterMenuState = remember { NgPopupToggleState() }
    val mainMenuState = remember { NgPopupToggleState() }
    val enabledFilter = stringResource(R.string.enabled)
    val disabledFilter = stringResource(R.string.disabled)
    val loginFilter = stringResource(R.string.need_login)
    val noGroupFilter = stringResource(R.string.no_group)
    val enabledExploreFilter = stringResource(R.string.enabled_explore)
    val disabledExploreFilter = stringResource(R.string.disabled_explore)
    val statusFilterQueries = remember(
        enabledFilter,
        disabledFilter,
        loginFilter,
        enabledExploreFilter,
        disabledExploreFilter,
    ) {
        setOf(
            enabledFilter,
            disabledFilter,
            loginFilter,
            enabledExploreFilter,
            disabledExploreFilter,
        )
    }
    val filterItems = remember(
        groups,
        activeQuery,
        enabledFilter,
        disabledFilter,
        loginFilter,
        noGroupFilter,
        enabledExploreFilter,
        disabledExploreFilter,
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
                        FILTER_TYPE_TEXT_ITEM_ID,
                        R.string.book_source_filter_type_text,
                        R.drawable.ic_ai_capability_text,
                        checked = activeQuery == "$TYPE_QUERY_PREFIX${BookSourceType.default}",
                    ),
                    NgExpandableActionMenuItem(
                        FILTER_TYPE_IMAGE_ITEM_ID,
                        R.string.book_source_filter_type_image,
                        R.drawable.ic_image,
                        checked = activeQuery == "$TYPE_QUERY_PREFIX${BookSourceType.image}",
                    ),
                    NgExpandableActionMenuItem(
                        FILTER_TYPE_VIDEO_ITEM_ID,
                        R.string.book_source_filter_type_video,
                        R.drawable.ic_ai_capability_video,
                        checked = activeQuery == "$TYPE_QUERY_PREFIX${BookSourceType.video}",
                    ),
                    NgExpandableActionMenuItem(
                        FILTER_TYPE_AUDIO_ITEM_ID,
                        R.string.book_source_filter_type_audio,
                        R.drawable.ic_tts_headphones,
                        checked = activeQuery == "$TYPE_QUERY_PREFIX${BookSourceType.audio}",
                    ),
                    NgExpandableActionMenuItem(
                        FILTER_TYPE_FILE_ITEM_ID,
                        R.string.book_source_filter_type_file,
                        R.drawable.ic_folder_open,
                        checked = activeQuery == "$TYPE_QUERY_PREFIX${BookSourceType.file}",
                    ),
                ),
            ),
            NgExpandableActionMenuItem(
                FILTER_STATUS_PARENT_ITEM_ID,
                R.string.book_source_filter_status,
                R.drawable.ic_check_circle_outline,
                children = listOf(
                    NgExpandableActionMenuItem(R.id.menu_enabled_group, R.string.enabled, R.drawable.ic_check, checked = activeQuery == enabledFilter),
                    NgExpandableActionMenuItem(R.id.menu_disabled_group, R.string.disabled, R.drawable.ic_block_outline, checked = activeQuery == disabledFilter),
                    NgExpandableActionMenuItem(R.id.menu_group_login, R.string.need_login, R.drawable.ic_lock_outline, checked = activeQuery == loginFilter),
                    NgExpandableActionMenuItem(R.id.menu_enabled_explore_group, R.string.enabled_explore, R.drawable.ic_bottom_explore_e, checked = activeQuery == enabledExploreFilter),
                    NgExpandableActionMenuItem(R.id.menu_disabled_explore_group, R.string.disabled_explore, R.drawable.ic_bottom_explore_e, checked = activeQuery == disabledExploreFilter),
                ),
            ),
            NgExpandableActionMenuItem(
                FILTER_GROUP_PARENT_ITEM_ID,
                R.string.book_source_filter_group,
                R.drawable.ic_groups,
                children = buildList {
                    add(
                        NgExpandableActionMenuItem(
                            R.id.menu_group_null,
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
                                checked = activeQuery == "$GROUP_QUERY_PREFIX$group",
                            )
                        )
                    }
                },
            ),
        )
    }
    val sortItems = remember(sort, sortAscending) {
        listOf(
            NgExpandableActionMenuItem(SORT_DIRECTION_ITEM_ID, R.string.sort_desc, R.drawable.ic_swap_vert, checked = !sortAscending),
            NgExpandableActionMenuItem(R.id.menu_sort_manual, R.string.sort_manual, R.drawable.ic_drag_handle, checked = sort == BookSourceSort.Default),
            NgExpandableActionMenuItem(R.id.menu_sort_auto, R.string.sort_auto, R.drawable.ic_sort, checked = sort == BookSourceSort.Weight),
            NgExpandableActionMenuItem(R.id.menu_sort_name, R.string.sort_by_name, R.drawable.ic_sort, checked = sort == BookSourceSort.Name),
            NgExpandableActionMenuItem(R.id.menu_sort_url, R.string.sort_by_url, R.drawable.ic_web_outline, checked = sort == BookSourceSort.Url),
            NgExpandableActionMenuItem(R.id.menu_sort_time, R.string.sort_by_lastUpdateTime, R.drawable.ic_history, checked = sort == BookSourceSort.Update),
            NgExpandableActionMenuItem(R.id.menu_sort_respondTime, R.string.sort_by_respondTime, R.drawable.ic_network_check, checked = sort == BookSourceSort.Respond),
            NgExpandableActionMenuItem(R.id.menu_sort_enable, R.string.is_enabled, R.drawable.ic_check_circle_outline, checked = sort == BookSourceSort.Enable),
        )
    }
    val dedupeItems = remember(dedupeMode) {
        listOf(
            NgExpandableActionMenuItem(
                DEDUPE_NONE_ITEM_ID,
                R.string.book_source_dedupe_none,
                R.drawable.ic_clear,
                checked = dedupeMode == BookSourceDedupeMode.NONE,
            ),
            NgExpandableActionMenuItem(
                DEDUPE_URL_ITEM_ID,
                R.string.book_source_dedupe_url,
                R.drawable.ic_web_outline,
                checked = dedupeMode == BookSourceDedupeMode.BY_URL,
            ),
            NgExpandableActionMenuItem(
                DEDUPE_NAME_ITEM_ID,
                R.string.book_source_dedupe_name,
                R.drawable.ic_sort,
                checked = dedupeMode == BookSourceDedupeMode.BY_NAME,
            ),
        )
    }
    val mainMenuItems = remember(sortItems, dedupeItems) {
        listOf(
            NgExpandableActionMenuItem(
                R.id.menu_add_book_source,
                R.string.add_book_source,
                R.drawable.ic_add,
            ),
            NgExpandableActionMenuItem(
                ADD_JS_ITEM_ID,
                R.string.book_source_add_javascript,
                R.drawable.ic_code,
            ),
            NgExpandableActionMenuItem(
                R.id.menu_import_local,
                R.string.import_local,
                R.drawable.ic_folder_open,
                dividerBefore = true,
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
        )
    }
    val filterDefaultExpandedItemIds = remember(
        activeQuery,
        noGroupFilter,
        statusFilterQueries,
    ) {
        when {
            activeQuery.startsWith(TYPE_QUERY_PREFIX) -> setOf(FILTER_TYPE_PARENT_ITEM_ID)
            activeQuery in statusFilterQueries -> setOf(FILTER_STATUS_PARENT_ITEM_ID)
            activeQuery == noGroupFilter || activeQuery.startsWith(GROUP_QUERY_PREFIX) -> {
                setOf(FILTER_GROUP_PARENT_ITEM_ID)
            }
            else -> emptySet()
        }
    }
    val filterActive = activeQuery in statusFilterQueries ||
        activeQuery == noGroupFilter ||
        activeQuery.startsWith(GROUP_QUERY_PREFIX) ||
        activeQuery.startsWith(TYPE_QUERY_PREFIX)
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
                hint = stringResource(R.string.search_book_source),
                modifier = Modifier.weight(1f),
                variant = NgSearchBarVariant.TOOLBAR,
                containerColor = Color.Transparent,
                hideHintOnFocus = true,
            )
            Spacer(Modifier.width(8.dp))
            BookSourceTopBarPopupButton(
                iconRes = R.drawable.ic_screen,
                iconSize = 14.dp,
                contentDescription = stringResource(R.string.screen),
                tint = if (filterActive) activeActionColor else actionColor,
                state = filterMenuState,
                items = filterItems,
                defaultExpandedItemIds = filterDefaultExpandedItemIds,
                onItemClick = { item ->
                    when (item.itemId) {
                        FILTER_ALL_ITEM_ID -> onAction(BookSourceManageAction.QueryChanged(""))
                        FILTER_TYPE_TEXT_ITEM_ID -> onAction(BookSourceManageAction.QueryChanged("$TYPE_QUERY_PREFIX${BookSourceType.default}"))
                        FILTER_TYPE_IMAGE_ITEM_ID -> onAction(BookSourceManageAction.QueryChanged("$TYPE_QUERY_PREFIX${BookSourceType.image}"))
                        FILTER_TYPE_VIDEO_ITEM_ID -> onAction(BookSourceManageAction.QueryChanged("$TYPE_QUERY_PREFIX${BookSourceType.video}"))
                        FILTER_TYPE_AUDIO_ITEM_ID -> onAction(BookSourceManageAction.QueryChanged("$TYPE_QUERY_PREFIX${BookSourceType.audio}"))
                        FILTER_TYPE_FILE_ITEM_ID -> onAction(BookSourceManageAction.QueryChanged("$TYPE_QUERY_PREFIX${BookSourceType.file}"))
                        R.id.menu_enabled_group -> onAction(BookSourceManageAction.QueryChanged(enabledFilter))
                        R.id.menu_disabled_group -> onAction(BookSourceManageAction.QueryChanged(disabledFilter))
                        R.id.menu_group_login -> onAction(BookSourceManageAction.QueryChanged(loginFilter))
                        R.id.menu_group_null -> onAction(BookSourceManageAction.QueryChanged(noGroupFilter))
                        R.id.menu_enabled_explore_group -> onAction(BookSourceManageAction.QueryChanged(enabledExploreFilter))
                        R.id.menu_disabled_explore_group -> onAction(BookSourceManageAction.QueryChanged(disabledExploreFilter))
                        else -> groups.getOrNull(item.itemId - GROUP_ITEM_ID_BASE)?.let {
                            onAction(BookSourceManageAction.QueryChanged("$GROUP_QUERY_PREFIX$it"))
                        }
                    }
                },
            )
            Spacer(Modifier.width(2.dp))
            BookSourceViewModeButton(
                viewMode = viewMode,
                tint = actionColor,
                onAction = onAction,
            )
            Spacer(Modifier.width(2.dp))
            BookSourceTopBarPopupButton(
                iconRes = R.drawable.ic_grid_menu,
                contentDescription = stringResource(R.string.menu),
                tint = if (dedupeMode != BookSourceDedupeMode.NONE) activeActionColor else actionColor,
                state = mainMenuState,
                items = mainMenuItems,
                defaultExpandedItemIds = if (dedupeMode != BookSourceDedupeMode.NONE) {
                    setOf(DEDUPE_PARENT_ITEM_ID)
                } else {
                    emptySet()
                },
                onItemClick = { item ->
                    when (item.itemId) {
                        R.id.menu_add_book_source -> onAction(BookSourceManageAction.AddDeclarative)
                        ADD_JS_ITEM_ID -> onAction(BookSourceManageAction.AddJavaScript)
                        R.id.menu_import_local -> onAction(BookSourceManageAction.ImportLocal)
                        R.id.menu_import_onLine -> onAction(BookSourceManageAction.ImportOnline)
                        R.id.menu_import_qr -> onAction(BookSourceManageAction.ImportQr)
                        SORT_DIRECTION_ITEM_ID -> onAction(BookSourceManageAction.ToggleSortDirection)
                        R.id.menu_sort_manual -> onAction(BookSourceManageAction.SortChanged(BookSourceSort.Default))
                        R.id.menu_sort_auto -> onAction(BookSourceManageAction.SortChanged(BookSourceSort.Weight))
                        R.id.menu_sort_name -> onAction(BookSourceManageAction.SortChanged(BookSourceSort.Name))
                        R.id.menu_sort_url -> onAction(BookSourceManageAction.SortChanged(BookSourceSort.Url))
                        R.id.menu_sort_time -> onAction(BookSourceManageAction.SortChanged(BookSourceSort.Update))
                        R.id.menu_sort_respondTime -> onAction(BookSourceManageAction.SortChanged(BookSourceSort.Respond))
                        R.id.menu_sort_enable -> onAction(BookSourceManageAction.SortChanged(BookSourceSort.Enable))
                        R.id.menu_group_manage -> onAction(BookSourceManageAction.ManageGroups)
                        DEDUPE_NONE_ITEM_ID -> onAction(BookSourceManageAction.DedupeModeChanged(BookSourceDedupeMode.NONE))
                        DEDUPE_URL_ITEM_ID -> onAction(BookSourceManageAction.DedupeModeChanged(BookSourceDedupeMode.BY_URL))
                        DEDUPE_NAME_ITEM_ID -> onAction(BookSourceManageAction.DedupeModeChanged(BookSourceDedupeMode.BY_NAME))
                    }
                },
            )
        }
    }
}

@Composable
private fun BookSourceTopBarActionButton(
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
private fun BookSourceTopBarPopupButton(
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
        BookSourceTopBarActionButton(
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
private fun BookSourceViewModeButton(
    viewMode: BookSourceViewMode,
    tint: Color,
    onAction: (BookSourceManageAction) -> Unit,
) {
    val nextMode = when (viewMode) {
        BookSourceViewMode.LIST -> BookSourceViewMode.GROUP
        BookSourceViewMode.GROUP -> BookSourceViewMode.LIST
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable {
                onAction(BookSourceManageAction.ViewModeChanged(nextMode))
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                when (viewMode) {
                    BookSourceViewMode.LIST -> R.drawable.ic_chapter_list
                    BookSourceViewMode.GROUP -> R.drawable.ic_groups
                }
            ),
            contentDescription = stringResource(
                when (nextMode) {
                    BookSourceViewMode.LIST -> R.string.book_source_view_list_title
                    BookSourceViewMode.GROUP -> R.string.book_source_view_group_title
                }
            ),
            tint = tint,
            modifier = Modifier.size(
                when (viewMode) {
                    BookSourceViewMode.LIST -> 20.dp
                    BookSourceViewMode.GROUP -> 17.dp
                }
            ),
        )
    }
}

@Composable
private fun BookSourceManagePanel(
    sources: List<BookSourcePart>,
    query: String,
    selectedUrls: Set<String>,
    viewMode: BookSourceViewMode,
    sort: BookSourceSort,
    expandedSections: Set<String>,
    dedupeMode: BookSourceDedupeMode,
    dedupeTree: List<BookSourceDedupeNode>,
    ruleScores: Map<String, Int>,
    preferredPrimaries: Map<String, String>,
    expandedDedupeKeys: Set<String>,
    onAction: (BookSourceManageAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelShape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp)
    val panelStyle = NgGlassDefaults.bookDetailStyle(
        containerColor = colorResource(R.color.ng_surface_card)
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 8.dp)
    ) {
        when {
            sources.isEmpty() -> NgGlassSurface(
                modifier = Modifier.fillMaxSize(),
                role = NgMaterialRole.CONTENT,
                shape = panelShape,
                style = panelStyle,
                liquidCornerRadius = NgTheme.shapes.mediumDp.dp,
            ) {
                BookSourceEmptyState()
            }
            viewMode == BookSourceViewMode.GROUP -> BookSourceGroupedList(
                sources = sources,
                selectedUrls = selectedUrls,
                expandedSections = expandedSections,
                dedupeMode = dedupeMode,
                ruleScores = ruleScores,
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
                if (dedupeMode == BookSourceDedupeMode.NONE) {
                    BookSourceFlatList(
                        sources = sources,
                        selectedUrls = selectedUrls,
                        canReorder = query.isBlank() && viewMode == BookSourceViewMode.LIST &&
                            sort == BookSourceSort.Default,
                        onAction = onAction,
                    )
                } else {
                    BookSourceDedupeTreeList(
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
private fun BookSourceEmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.empty),
            color = Color(NgTheme.colors.onSurfaceVariant),
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun BookSourceFlatList(
    sources: List<BookSourcePart>,
    selectedUrls: Set<String>,
    canReorder: Boolean,
    onAction: (BookSourceManageAction) -> Unit,
) {
    val sourceVersions = sources.map { System.identityHashCode(it) }
    var orderedSources by remember(sourceVersions) { mutableStateOf(sources) }
    val listState = rememberLazyListState()
    var isReordering by remember { mutableStateOf(false) }
    // Drag handles retain callbacks; source refreshes can replace the local list state.
    val currentOnDragStopped by rememberUpdatedState {
        isReordering = false
        onAction(BookSourceManageAction.Reorder(orderedSources))
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
            orderedSources.getOrNull(index)?.bookSourceUrl?.let(selectedUrls::contains) == true
        },
        onSelectionChange = { index, selected ->
            orderedSources.getOrNull(index)?.let {
                onAction(BookSourceManageAction.SelectionChanged(it, selected))
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
                slideAreaEnd = 32.dp,
            ),
        state = listState,
    ) {
        itemsIndexed(
            items = orderedSources,
            key = { _, source -> source.bookSourceUrl },
        ) { index, source ->
            ReorderableItem(
                state = reorderState,
                key = source.bookSourceUrl,
            ) { _ ->
                val sortDescription = stringResource(R.string.sort)
                BookSourceManageRow(
                    source = source,
                    selected = source.bookSourceUrl in selectedUrls,
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

private fun selectionStateOf(
    sources: List<BookSourcePart>,
    selectedUrls: Set<String>,
): ToggleableState {
    if (sources.isEmpty()) return ToggleableState.Off
    var selectedCount = 0
    for (source in sources) {
        if (source.bookSourceUrl in selectedUrls) selectedCount++
    }
    return when {
        selectedCount == 0 -> ToggleableState.Off
        selectedCount == sources.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

private sealed interface BookSourceTreeRowItem {
    val key: String
    val source: BookSourcePart

    data class Primary(
        val node: BookSourceDedupeNode,
        val expanded: Boolean,
    ) : BookSourceTreeRowItem {
        override val key: String get() = "primary:${node.groupKey}"
        override val source: BookSourcePart get() = node.primary
    }

    data class Duplicate(
        val parentGroupKey: String,
        override val source: BookSourcePart,
        val isPrimary: Boolean,
    ) : BookSourceTreeRowItem {
        override val key: String get() = "dup:${parentGroupKey}:${source.bookSourceUrl}"
    }
}

@Composable
private fun BookSourceDedupeTreeList(
    nodes: List<BookSourceDedupeNode>,
    selectedUrls: Set<String>,
    expandedDedupeKeys: Set<String>,
    onAction: (BookSourceManageAction) -> Unit,
) {
    val visibleItems = remember(nodes, expandedDedupeKeys) {
        buildList(nodes.size) {
            nodes.forEach { node ->
                val expanded = node.hasDuplicates && node.groupKey in expandedDedupeKeys
                add(BookSourceTreeRowItem.Primary(node = node, expanded = expanded))
                if (expanded) {
                    val primaryUrl = node.primary.bookSourceUrl
                    node.allSources.forEach { child ->
                        add(
                            BookSourceTreeRowItem.Duplicate(
                                parentGroupKey = node.groupKey,
                                source = child,
                                isPrimary = child.bookSourceUrl == primaryUrl,
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
            when (val item = visibleItems.getOrNull(index)) {
                is BookSourceTreeRowItem.Primary -> {
                    if (item.expanded) {
                        selectionStateOf(item.node.allSources, selectedUrls) == ToggleableState.On
                    } else {
                        item.node.primary.bookSourceUrl in selectedUrls
                    }
                }
                is BookSourceTreeRowItem.Duplicate -> item.source.bookSourceUrl in selectedUrls
                null -> false
            }
        },
        onSelectionChange = { index, selected ->
            when (val item = visibleItems.getOrNull(index)) {
                is BookSourceTreeRowItem.Primary -> {
                    if (item.expanded) {
                        onAction(BookSourceManageAction.SectionSelectionChanged(item.node.allSources, selected))
                    } else {
                        onAction(BookSourceManageAction.SelectionChanged(item.node.primary, selected))
                    }
                }
                is BookSourceTreeRowItem.Duplicate -> {
                    onAction(BookSourceManageAction.SelectionChanged(item.source, selected))
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
                enabled = visibleItems.isNotEmpty(),
                slideAreaStart = 28.dp,
                slideAreaEnd = 60.dp,
            ),
        state = listState,
    ) {
        itemsIndexed(
            items = visibleItems,
            key = { _, item -> item.key },
        ) { index, item ->
            when (item) {
                is BookSourceTreeRowItem.Primary -> {
                    val branchSelectionState = if (item.expanded) {
                        selectionStateOf(item.node.allSources, selectedUrls)
                    } else {
                        null
                    }
                    BookSourceManageRow(
                        source = item.node.primary,
                        selected = item.node.primary.bookSourceUrl in selectedUrls,
                        showDivider = index < visibleItems.lastIndex,
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
                                    BookSourceManageAction.SectionSelectionChanged(
                                        item.node.allSources,
                                        selectAll,
                                    )
                                )
                            }
                        } else {
                            null
                        },
                        onToggleDedupe = {
                            onAction(BookSourceManageAction.ToggleDedupeNode(item.node.groupKey))
                        },
                    )
                }
                is BookSourceTreeRowItem.Duplicate -> {
                    BookSourceManageRow(
                        source = item.source,
                        selected = item.source.bookSourceUrl in selectedUrls,
                        showDivider = index < visibleItems.lastIndex,
                        manualSort = false,
                        onAction = onAction,
                        modifier = Modifier.background(
                            Color(NgTheme.colors.surfaceContainerLow).copy(alpha = 0.55f)
                        ),
                        bodyDragModifier = null,
                        isDedupeChild = true,
                        isDedupeChildPrimary = item.isPrimary,
                        onPromoteDedupePrimary = {
                            onAction(
                                BookSourceManageAction.SetDedupePrimary(
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

private data class BookSourceSectionUi(
    val key: String,
    val title: String,
    val sources: List<BookSourcePart>,
)

private data class BookSourceSectionKey(val key: String, val title: String)

@Composable
private fun BookSourceGroupedList(
    sources: List<BookSourcePart>,
    selectedUrls: Set<String>,
    expandedSections: Set<String>,
    dedupeMode: BookSourceDedupeMode,
    ruleScores: Map<String, Int>,
    preferredPrimaries: Map<String, String>,
    expandedDedupeKeys: Set<String>,
    onAction: (BookSourceManageAction) -> Unit,
) {
    val noGroup = stringResource(R.string.no_group)
    val sectionVersion = sources.map {
        Triple(it.bookSourceUrl, it.bookSourceName, it.bookSourceGroup)
    }
    val sections = remember(sectionVersion, noGroup) {
        sources
            .flatMap { source ->
                val sourceGroups = source.bookSourceGroup
                    ?.splitNotBlank(AppPattern.splitGroupRegex)
                    .orEmpty()
                if (sourceGroups.isEmpty()) {
                    listOf(BookSourceSectionKey("group:", noGroup) to source)
                } else {
                    sourceGroups.map { group ->
                        BookSourceSectionKey("group:$group", group) to source
                    }
                }
            }
            .groupBy(
                keySelector = { it.first },
                valueTransform = { it.second },
            )
            .map { (section, groupedSources) ->
                BookSourceSectionUi(
                    key = section.key,
                    title = section.title,
                    sources = groupedSources.distinctBy(BookSourcePart::bookSourceUrl),
                )
            }
            .sortedWith(compareBy<BookSourceSectionUi> { it.key != "group:" }.thenBy { it.title })
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        sections.forEach { section ->
            item(key = "section:${section.key}") {
                BookSourceSectionHeader(
                    section = section,
                    expanded = section.key in expandedSections,
                    selectionState = selectionStateOf(section.sources, selectedUrls),
                    onAction = onAction,
                )
            }
            if (section.key in expandedSections) {
                if (dedupeMode == BookSourceDedupeMode.NONE) {
                    items(
                        items = section.sources,
                        key = { source -> "${section.key}:${source.bookSourceUrl}" },
                    ) { source ->
                        BookSourceManageRow(
                            source = source,
                            selected = source.bookSourceUrl in selectedUrls,
                            showDivider = true,
                            manualSort = false,
                            onAction = onAction,
                            modifier = Modifier
                                .background(colorResource(R.color.ng_surface_card)),
                            bodyDragModifier = null,
                        )
                    }
                } else {
                    val sectionTree = BookSourceDedupe.buildTree(
                        section.sources,
                        dedupeMode,
                        ruleScores,
                        preferredPrimaries,
                    )
                    val sectionRows = buildList(section.sources.size) {
                        sectionTree.forEach { node ->
                            val expanded = node.hasDuplicates && node.groupKey in expandedDedupeKeys
                            add(BookSourceTreeRowItem.Primary(node = node, expanded = expanded))
                            if (expanded) {
                                val primaryUrl = node.primary.bookSourceUrl
                                node.allSources.forEach { child ->
                                    add(
                                        BookSourceTreeRowItem.Duplicate(
                                            parentGroupKey = node.groupKey,
                                            source = child,
                                            isPrimary = child.bookSourceUrl == primaryUrl,
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
                            is BookSourceTreeRowItem.Primary -> {
                                val branchSelectionState = if (item.expanded) {
                                    selectionStateOf(item.node.allSources, selectedUrls)
                                } else {
                                    null
                                }
                                BookSourceManageRow(
                                    source = item.node.primary,
                                    selected = item.node.primary.bookSourceUrl in selectedUrls,
                                    showDivider = true,
                                    manualSort = false,
                                    onAction = onAction,
                                    modifier = Modifier
                                        .background(colorResource(R.color.ng_surface_card)),
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
                                                BookSourceManageAction.SectionSelectionChanged(
                                                    item.node.allSources,
                                                    selectAll,
                                                )
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onToggleDedupe = {
                                        onAction(BookSourceManageAction.ToggleDedupeNode(item.node.groupKey))
                                    },
                                )
                            }
                            is BookSourceTreeRowItem.Duplicate -> {
                                BookSourceManageRow(
                                    source = item.source,
                                    selected = item.source.bookSourceUrl in selectedUrls,
                                    showDivider = true,
                                    manualSort = false,
                                    onAction = onAction,
                                    modifier = Modifier
                                        .background(Color(NgTheme.colors.surfaceContainerLow)),
                                    bodyDragModifier = null,
                                    isDedupeChild = true,
                                    isDedupeChildPrimary = item.isPrimary,
                                    onPromoteDedupePrimary = {
                                        onAction(
                                            BookSourceManageAction.SetDedupePrimary(
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
private fun BookSourceSectionHeader(
    section: BookSourceSectionUi,
    expanded: Boolean,
    selectionState: ToggleableState,
    onAction: (BookSourceManageAction) -> Unit,
) {
    val cardShape = RoundedCornerShape(10.dp)
    val cardStyle = NgGlassDefaults.bookDetailStyle(
        containerColor = colorResource(R.color.ng_surface_card)
    )
    Column {
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
                        onClick = {
                            onAction(BookSourceManageAction.ToggleSection(section.key))
                        },
                        onLongClickLabel = stringResource(R.string.delete),
                        onLongClick = {
                            onAction(
                                BookSourceManageAction.DeleteSection(
                                    groupName = section.key
                                        .removePrefix(GROUP_QUERY_PREFIX)
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
                        onAction(BookSourceManageAction.SectionSelectionChanged(section.sources, selectAll))
                    },
                    variant = NgFileSelectionCheckboxVariant.COMPACT,
                )
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            colorResource(
                                if (section.title.isNormalSourceSection()) R.color.ng_success
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
                        .clickable {
                            onAction(BookSourceManageAction.ToggleSection(section.key))
                        },
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
}

@Composable
private fun BookSourceManageRow(
    source: BookSourcePart,
    selected: Boolean,
    showDivider: Boolean,
    manualSort: Boolean,
    onAction: (BookSourceManageAction) -> Unit,
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
    val menuState = remember(source.bookSourceUrl) { NgPopupToggleState() }
    val titleColor = Color(NgTheme.colors.onSurface).copy(
        alpha = if (source.enabled) 1f else 0.52f,
    )
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var swipeOffsetPx by remember(source.bookSourceUrl) { mutableFloatStateOf(0f) }
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
                            onAction(BookSourceManageAction.SwipeDelete(source))
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
                            onAction(BookSourceManageAction.SelectionChanged(source, it))
                        },
                        variant = NgFileSelectionCheckboxVariant.COMPACT,
                    )
                }
                val reachabilityMap by BookSourceReachabilityStore.statusFlow.collectAsState()
                val reachability = BookSourceReachabilityStore.resolve(source, reachabilityMap)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            onAction(BookSourceManageAction.Edit(source))
                        }
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
                                    BookSourceReachability.REACHABLE -> Color(0xFF22C55E)
                                    BookSourceReachability.UNREACHABLE -> Color(0xFFEF4444)
                                    BookSourceReachability.UNKNOWN -> Color(NgTheme.colors.onSurfaceVariant).copy(alpha = 0.22f)
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
                            text = source.bookSourceName,
                            color = titleColor,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (showDedupeToggle && dedupeDuplicateCount > 1) {
                            BookSourceMiniTag(
                                text = "×$dedupeDuplicateCount",
                                containerColor = Color(NgTheme.colors.primaryContainer),
                                contentColor = Color(NgTheme.colors.primary),
                            )
                        }
                        source.formatTagLabel()?.let { labelRes ->
                            BookSourceMiniTag(
                                text = stringResource(labelRes),
                                containerColor = Color(NgTheme.colors.primaryContainer),
                                contentColor = Color(NgTheme.colors.primary),
                            )
                        }
                        BookSourceMiniTag(
                            text = stringResource(source.typeTagLabel()),
                            containerColor = Color(NgTheme.colors.primaryContainer),
                            contentColor = Color(NgTheme.colors.primary),
                        )
                        if (source.hasSearchUrl) {
                            BookSourceMiniTag(
                                text = stringResource(R.string.book_source_capability_search_short),
                                containerColor = colorResource(
                                    if (source.enabled) R.color.ng_success_container else R.color.ng_error_container
                                ),
                                contentColor = colorResource(
                                    if (source.enabled) R.color.ng_success else R.color.ng_error
                                ),
                            )
                        }
                        if (source.hasExploreUrl) {
                            BookSourceMiniTag(
                                text = stringResource(R.string.book_source_capability_explore_short),
                                containerColor = colorResource(
                                    if (source.enabledExplore) R.color.ng_info_container else R.color.ng_error_container
                                ),
                                contentColor = colorResource(
                                    if (source.enabledExplore) R.color.ng_info else R.color.ng_error
                                ),
                            )
                        }
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
                        items = sourceMenuItems(source, manualSort),
                        onItemClick = {
                            menuState.close()
                            onAction(
                                when (it.itemId) {
                                    R.id.menu_top -> BookSourceManageAction.Top(source)
                                    R.id.menu_bottom -> BookSourceManageAction.Bottom(source)
                                    R.id.menu_login -> BookSourceManageAction.Login(source)
                                    R.id.menu_search -> BookSourceManageAction.Search(source)
                                    else -> BookSourceManageAction.Debug(source)
                                }
                            )
                        },
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 60.dp, end = 12.dp),
                thickness = 0.6.dp,
                color = Color(NgTheme.colors.outlineVariant).copy(alpha = 0.24f),
            )
        }
    }
}

@Composable
private fun sourceMenuItems(
    source: BookSourcePart,
    manualSort: Boolean,
): List<NgExpandableActionMenuItem> = buildList {
    if (source.hasSearchUrl) {
        add(NgExpandableActionMenuItem(R.id.menu_search, R.string.search, R.drawable.ic_search))
    }
    add(NgExpandableActionMenuItem(R.id.menu_debug_source, R.string.debug, R.drawable.ic_bug_report))
    if (source.hasLoginUrl) {
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
}

@Composable
private fun BookSourceMiniTag(
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

private fun BookSourcePart.formatTagLabel(): Int? =
    R.string.book_source_format_js.takeIf { hasJs }

private fun BookSourcePart.typeTagLabel(): Int = when (bookSourceType) {
    BookSourceType.image -> R.string.book_source_tag_type_image
    BookSourceType.audio -> R.string.book_source_tag_type_audio
    BookSourceType.file -> R.string.book_source_tag_type_file
    BookSourceType.video -> R.string.book_source_tag_type_video
    else -> R.string.book_source_tag_type_text
}

private fun String.isNormalSourceSection(): Boolean {
    if (this == "校验超时" || this == "源名称获取失败" || this == "非HTTP源地址") return false
    return listOf("失效", "异常", "错误", "无效", "规则为空").none(::contains)
}
