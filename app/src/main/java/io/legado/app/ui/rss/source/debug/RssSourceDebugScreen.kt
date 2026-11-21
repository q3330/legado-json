package io.legado.app.ui.rss.source.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.R
import io.legado.app.ui.book.source.debug.BookSourceDebugPhaseRow
import io.legado.app.ui.book.source.debug.BookSourceDebugTimelineState
import io.legado.app.ui.design.components.NgDialogVariant
import io.legado.app.ui.design.components.NgStatusTagStyle
import io.legado.app.ui.design.components.NgStatusTagVariant
import io.legado.app.ui.design.components.compose.NgActionChip
import io.legado.app.ui.design.components.compose.NgDialog
import io.legado.app.ui.design.components.compose.NgDialogTextActionButton
import io.legado.app.ui.design.components.compose.NgExpandableActionMenu
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuItem
import io.legado.app.ui.design.components.compose.NgFloatingToolbarBackButton
import io.legado.app.ui.design.components.compose.NgPopupToggleState
import io.legado.app.ui.design.components.compose.NgSearchBar
import io.legado.app.ui.design.components.compose.NgSearchBarActionButton
import io.legado.app.ui.design.components.compose.NgSearchBarVariant
import io.legado.app.ui.design.components.compose.NgStatusTag
import io.legado.app.ui.design.theme.NgTheme
import kotlinx.coroutines.delay

internal const val RSS_DEBUG_REFRESH_SORT_ID = 0x55200001

@Composable
internal fun RssSourceDebugScreen(
    query: String,
    helpVisible: Boolean,
    sourceType: Int,
    sortKinds: List<Pair<String, String>>,
    timelineItems: List<BookSourceDebugTimelineState.DebugItem.Phase>,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onAction: (Int) -> Unit,
    onPhaseClick: (BookSourceDebugTimelineState.DebugItem.Phase) -> Unit,
) {
    var showSortSelector by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val submit: (String) -> Unit = { value ->
        focusManager.clearFocus()
        onSearch(value)
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        RssSourceDebugTopBar(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = submit,
            onFocusChanged = onFocusChanged,
            focusRequester = focusRequester,
            onBack = onBack,
            onAction = onAction,
        )
        if (helpVisible) {
            RssSourceDebugHelp(
                sourceType = sourceType,
                sortKinds = sortKinds,
                onSearch = submit,
                onOpenSortSelector = { if (sortKinds.isNotEmpty()) showSortSelector = true },
            )
        } else if (timelineItems.isNotEmpty()) {
            val listState = rememberLazyListState()
            val lastItem = timelineItems.lastOrNull()
            LaunchedEffect(timelineItems.size, lastItem) {
                if (timelineItems.isNotEmpty()) {
                    delay(120L)
                    listState.animateScrollToItem(timelineItems.lastIndex)
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(
                    items = timelineItems,
                    key = { it.phaseId },
                ) { item ->
                    BookSourceDebugPhaseRow(item = item, onClick = { onPhaseClick(item) })
                }
            }
        }
    }
    if (showSortSelector) {
        Dialog(onDismissRequest = { showSortSelector = false }) {
            NgDialog(
                title = "选择分类调试",
                variant = NgDialogVariant.LONG_CONTENT,
                actions = {
                    NgDialogTextActionButton(
                        text = stringResource(R.string.cancel),
                        onClick = { showSortSelector = false },
                    )
                },
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    itemsIndexed(sortKinds) { _, (title, url) ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    showSortSelector = false
                                    submit("${title.ifBlank { "分类" }}::$url")
                                }
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = title.ifBlank { url },
                                color = Color(NgTheme.colors.onSurface),
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RssSourceDebugTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    onBack: () -> Unit,
    onAction: (Int) -> Unit,
) {
    val menuState = remember { NgPopupToggleState() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NgFloatingToolbarBackButton(onClick = onBack)
        NgSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            hint = "输入关键字或 分类::URL 调试",
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            variant = NgSearchBarVariant.TOOLBAR,
            hideHintOnFocus = true,
            onFocusChanged = onFocusChanged,
            onSearch = onSearch,
        )
        Spacer(Modifier.width(8.dp))
        Box {
            NgSearchBarActionButton(
                onClick = menuState::onAnchorClick,
                contentDescription = stringResource(R.string.menu),
            )
            NgExpandableActionMenu(
                expanded = menuState.expanded,
                onDismissRequest = menuState::onDismissRequest,
                items = listOf(
                    NgExpandableActionMenuItem(
                        R.id.menu_list_src,
                        R.string.list_src,
                        R.drawable.ic_code,
                    ),
                    NgExpandableActionMenuItem(
                        R.id.menu_content_src,
                        R.string.content_src,
                        R.drawable.ic_code,
                    ),
                    NgExpandableActionMenuItem(
                        R.id.menu_network_log,
                        R.string.network_request_log,
                        R.drawable.ic_network_check,
                        dividerBefore = true,
                    ),
                    NgExpandableActionMenuItem(
                        RSS_DEBUG_REFRESH_SORT_ID,
                        R.string.refresh_sort,
                        R.drawable.ic_refresh_black_24dp,
                    ),
                    NgExpandableActionMenuItem(
                        R.id.menu_help,
                        R.string.help,
                        R.drawable.ic_help,
                    ),
                ),
                onItemClick = { item ->
                    menuState.close()
                    onAction(item.itemId)
                },
            )
        }
    }
}

@Composable
private fun RssSourceDebugHelp(
    sourceType: Int,
    sortKinds: List<Pair<String, String>>,
    onSearch: (String) -> Unit,
    onOpenSortSelector: () -> Unit,
) {
    val typeLabel = when (sourceType) {
        1 -> "图片源"
        2 -> "视频源"
        else -> "网页源"
    }
    val typeHint = "点击下方快捷标签开始调试；长按分类标签可选择全部分类"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorResource(R.color.ng_surface_card))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NgStatusTag(
                text = typeLabel,
                variant = NgStatusTagVariant.PRIMARY,
                style = NgStatusTagStyle.REGULAR,
            )
            Text(
                text = typeHint,
                color = Color(NgTheme.colors.onSurfaceVariant),
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "调试搜索/预设入口>>",
                color = Color(NgTheme.colors.onSurfaceVariant),
                fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NgActionChip(text = "我的", onClick = { onSearch("我的") })
                NgActionChip(text = "系统", onClick = { onSearch("系统") })
            }
        }
        if (sortKinds.isNotEmpty()) {
            val first = sortKinds.first()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "调试分类列表>>（长按选择其他分类）",
                    color = Color(NgTheme.colors.onSurfaceVariant),
                    fontSize = 14.sp,
                )
                NgActionChip(
                    text = "${first.first.ifBlank { "分类" }}::${first.second}",
                    onClick = { onSearch("${first.first}::${first.second}") },
                    onLongClick = onOpenSortSelector,
                )
            }
        }
    }
}
