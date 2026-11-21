package io.legado.app.ui.book.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.menuCapabilities
import io.legado.app.ui.design.components.compose.NgExpandableActionMenu
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuItem
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuVariant
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckbox
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckboxVariant
import io.legado.app.ui.design.components.compose.NgPopupToggleState
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.utils.ConvertUtils
import kotlin.math.roundToInt

private const val ROW_ACTION_COVER = 0x57000001
private const val ROW_ACTION_DOWNLOAD = 0x57000002
private const val ROW_ACTION_GROUP = 0x57000003
private const val ROW_ACTION_EXPORT = 0x57000004

internal fun formatBookshelfCacheSize(bytes: Long): String =
    if (bytes == 0L) "0 B" else ConvertUtils.formatFileSize(bytes)

private fun Book.typeTagLabelRes(): Int = when {
    isImage -> R.string.book_source_filter_type_image
    isVideo -> R.string.book_source_filter_type_video
    isAudio -> R.string.book_source_filter_type_audio
    isLocal -> R.string.book_source_filter_type_file
    else -> R.string.book_source_filter_type_text
}

@Composable
internal fun BookshelfManageBookRow(
    book: Book,
    selected: Boolean,
    groupNames: String,
    cachedChapterCount: Int?,
    cacheChapterTotal: Int?,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
    bodyDragModifier: Modifier = Modifier,
    onToggleSelected: () -> Unit,
    onOpenDetails: () -> Unit,
    onSwipeDelete: () -> Unit,
    onDownloadBook: () -> Unit,
    onExportBook: () -> Unit,
    onGroupBook: () -> Unit,
    onFetchCover: () -> Unit,
) {
    val menuState = remember(book.bookUrl) { NgPopupToggleState() }
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var swipeOffsetPx by remember(book.bookUrl) { mutableFloatStateOf(0f) }
    val swipeDeleteThreshold = rowWidthPx / 3f
    val reachedDeleteThreshold = rowWidthPx > 0 && -swipeOffsetPx >= swipeDeleteThreshold
    val dragState = rememberDraggableState { delta ->
        val maxSwipe = if (rowWidthPx > 0) rowWidthPx * 0.45f else 360f
        swipeOffsetPx = (swipeOffsetPx + delta).coerceIn(-maxSwipe, 0f)
    }
    val totalChapters = (cacheChapterTotal ?: book.totalChapterNum).coerceAtLeast(0)
    val chapterText = when {
        book.isLocal -> "${totalChapters}章"
        cachedChapterCount != null -> "${cachedChapterCount}/${totalChapters}章"
        else -> "${totalChapters}章"
    }
    val sourceText = if (book.isLocal) {
        stringResource(R.string.local)
    } else {
        book.originName.ifBlank { "未知来源" }
    }
    val subtitleText = "$chapterText · $sourceText"
    val canDownload = !book.isLocal && book.menuCapabilities.download
    val canExport = book.menuCapabilities.export
    val rowMenuItems = remember(book.bookUrl, canDownload, canExport) {
        listOf(
            NgExpandableActionMenuItem(
                itemId = ROW_ACTION_COVER,
                titleRes = 0,
                title = "封面",
                iconRes = R.drawable.ic_image,
            ),
            NgExpandableActionMenuItem(
                itemId = ROW_ACTION_DOWNLOAD,
                titleRes = 0,
                title = "下载",
                iconRes = R.drawable.ic_download_line,
                enabled = canDownload,
            ),
            NgExpandableActionMenuItem(
                itemId = ROW_ACTION_GROUP,
                titleRes = R.string.group,
                iconRes = R.drawable.ic_bookshelf_manage_group,
            ),
            NgExpandableActionMenuItem(
                itemId = ROW_ACTION_EXPORT,
                titleRes = R.string.export,
                iconRes = R.drawable.ic_bookshelf_manage_export_content,
                enabled = canExport,
            ),
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
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
                    )
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NgFileSelectionCheckbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelected() },
                    variant = NgFileSelectionCheckboxVariant.COMPACT,
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(onClick = onOpenDetails)
                        .then(bodyDragModifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = book.name,
                            color = Color(NgTheme.colors.onSurface),
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitleText,
                            color = Color(NgTheme.colors.onSurfaceVariant),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BookshelfManageMiniTag(
                            text = groupNames.ifBlank { stringResource(R.string.no_group) },
                            containerColor = Color(NgTheme.colors.surfaceVariant),
                            contentColor = Color(NgTheme.colors.onSurfaceVariant),
                            modifier = Modifier.widthIn(max = 68.dp),
                        )
                        BookshelfManageMiniTag(
                            text = stringResource(book.typeTagLabelRes()),
                            containerColor = Color(NgTheme.colors.primaryContainer),
                            contentColor = Color(NgTheme.colors.primary),
                        )
                    }
                }
                Box {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
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
                            contentDescription = stringResource(R.string.more),
                            tint = if (menuState.expanded) {
                                Color(NgTheme.colors.primary)
                            } else {
                                Color(NgTheme.colors.onSurfaceVariant)
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    NgExpandableActionMenu(
                        expanded = menuState.expanded,
                        onDismissRequest = menuState::onDismissRequest,
                        items = rowMenuItems,
                        variant = NgExpandableActionMenuVariant.END_ANCHORED_DROPDOWN,
                        width = 128.dp,
                        menuContainerColor = colorResource(R.color.ng_surface_card),
                        onItemClick = { item ->
                            menuState.close()
                            when (item.itemId) {
                                ROW_ACTION_COVER -> onFetchCover()
                                ROW_ACTION_DOWNLOAD -> onDownloadBook()
                                ROW_ACTION_GROUP -> onGroupBook()
                                ROW_ACTION_EXPORT -> onExportBook()
                            }
                        },
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                thickness = 0.5.dp,
                color = Color(NgTheme.colors.outlineVariant).copy(alpha = 0.35f),
            )
        }
    }
}

@Composable
private fun BookshelfManageMiniTag(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            color = contentColor,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
