package io.legado.app.ui.main.bookshelf.style1

import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.help.config.BookshelfFloatingDockSearchPosition
import io.legado.app.help.config.BookshelfTopBarStyle
import io.legado.app.help.translate.BookTitleTranslator
import io.legado.app.help.translate.TranslateConfig
import io.legado.app.help.translate.TranslateHistoryStore
import io.legado.app.ui.design.components.compose.NgExpandableActionMenu
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuItem
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuVariant
import io.legado.app.ui.design.components.compose.NgMenuSelectionStyle
import io.legado.app.ui.design.components.compose.NgPopupToggleState
import io.legado.app.ui.design.components.compose.NgSearchBar
import io.legado.app.ui.design.components.compose.NgSearchBarActionButton
import io.legado.app.ui.design.components.compose.NgSearchBarTranslateButton
import io.legado.app.ui.design.components.compose.NgSearchBarVariant
import io.legado.app.ui.design.components.compose.rememberNgExpandableActionMenuContentWidth
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.ui.main.bookshelf.BookshelfDockGroup
import io.legado.app.ui.main.bookshelf.BookshelfMenuHost
import io.legado.app.ui.main.bookshelf.BookshelfSortMenuHost
import io.legado.app.ui.main.bookshelf.BookshelfToolbarMenuOffset
import io.legado.app.ui.main.bookshelf.builtInIconRes
import kotlin.math.roundToInt

private const val BOOKSHELF_GROUP_MENU_ID_BASE = 0x56000000

@Composable
internal fun BookshelfScreen(
    dockGroups: List<BookshelfDockGroup>,
    selectedGroupIndex: Int,
    groupGridMode: Boolean,
    configuredTopBarStyle: BookshelfTopBarStyle,
    dockTopDistancePx: Int,
    dockContentTopInsetPx: Int,
    dockTransparency: Int,
    dockSearchPosition: BookshelfFloatingDockSearchPosition,
    isTranslating: Boolean = false,
    onTranslateBooks: () -> Unit = {},
    onSearchClick: () -> Unit,
    onGroupClick: (Int) -> Unit,
    onGroupLongClick: (Int) -> Unit,
    onManageClick: () -> Unit,
    onOpenSortMenu: () -> List<NgExpandableActionMenuItem>,
    onSortMenuItemClick: (Int) -> Unit,
    onMenuItemClick: (Int) -> Unit,
    onFloatingDockBoundsChanged: (Rect) -> Unit,
) {
    val density = LocalDensity.current
    val hostView = LocalView.current
    val dockProgress = remember { Animatable(0f) }
    val topInset = with(density) { dockContentTopInsetPx.toDp() }
    val dockTranslation = with(density) { (-4).dp.toPx() }

    LaunchedEffect(Unit) {
        dockProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 220),
        )
    }

    val dockModifier = Modifier
        .graphicsLayer {
            alpha = dockProgress.value
            translationY = dockTranslation * (1f - dockProgress.value)
        }
        .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInRoot()
            val hostLocation = IntArray(2)
            hostView.getLocationOnScreen(hostLocation)
            onFloatingDockBoundsChanged(
                Rect(
                    hostLocation[0] + bounds.left.roundToInt(),
                    hostLocation[1] + bounds.top.roundToInt(),
                    hostLocation[0] + bounds.right.roundToInt(),
                    hostLocation[1] + bounds.bottom.roundToInt(),
                )
            )
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topInset),
    ) {
        BookshelfModernTopBar(
            groups = dockGroups,
            selectedGroupIndex = selectedGroupIndex,
            groupGridMode = groupGridMode,
            isTranslating = isTranslating,
            onTranslateBooks = onTranslateBooks,
            onSearchClick = onSearchClick,
            onGroupClick = onGroupClick,
            onOpenSortMenu = onOpenSortMenu,
            onSortMenuItemClick = onSortMenuItemClick,
            onMenuItemClick = onMenuItemClick,
            modifier = dockModifier,
        )
    }
}

@Composable
private fun BookshelfModernTopBar(
    groups: List<BookshelfDockGroup>,
    selectedGroupIndex: Int,
    groupGridMode: Boolean,
    isTranslating: Boolean = false,
    onTranslateBooks: () -> Unit = {},
    onSearchClick: () -> Unit,
    onGroupClick: (Int) -> Unit,
    onOpenSortMenu: () -> List<NgExpandableActionMenuItem>,
    onSortMenuItemClick: (Int) -> Unit,
    onMenuItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedGroup = groups.getOrNull(selectedGroupIndex)
    val searchLabel = stringResource(R.string.search)
    val searchHint = if (!groupGridMode && selectedGroup != null && selectedGroup.name.isNotBlank()) {
        "$searchLabel · ${selectedGroup.name}"
    } else {
        searchLabel
    }
    val spacerWidth = 6.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .clickable(role = Role.Button, onClick = onSearchClick),
        ) {
            NgSearchBar(
                query = "",
                onQueryChange = {},
                hint = searchHint,
                enabled = false,
                variant = NgSearchBarVariant.TOOLBAR,
            )
        }
        Spacer(Modifier.width(spacerWidth))
        NgSearchBarTranslateButton(
            isTranslating = isTranslating,
            onClick = onTranslateBooks,
            contentDescription = "翻译当前列表书名",
        )
        if (!groupGridMode && groups.isNotEmpty()) {
            Spacer(Modifier.width(spacerWidth))
            BookshelfGroupMenuButton(
                groups = groups,
                selectedGroupIndex = selectedGroupIndex,
                onGroupClick = onGroupClick,
            )
        }
        Spacer(Modifier.width(spacerWidth))
        BookshelfSortMenuHost(
            onOpen = onOpenSortMenu,
            onItemClick = onSortMenuItemClick,
        ) { openMenu ->
            NgSearchBarActionButton(
                onClick = openMenu,
                contentDescription = stringResource(R.string.sort),
                icon = painterResource(R.drawable.ic_swap_vert),
            )
        }
        Spacer(Modifier.width(spacerWidth))
        BookshelfMenuHost(
            includeBrowseHistory = true,
            onMenuItemClick = onMenuItemClick,
        ) { openMenu ->
            NgSearchBarActionButton(
                onClick = openMenu,
                contentDescription = stringResource(R.string.more),
                icon = painterResource(R.drawable.ic_bookshelf_dock_more),
            )
        }
    }
}

@Composable
private fun BookshelfGroupMenuButton(
    groups: List<BookshelfDockGroup>,
    selectedGroupIndex: Int,
    onGroupClick: (Int) -> Unit,
) {
    val menuState = remember { NgPopupToggleState() }
    val menuItems = remember(groups, selectedGroupIndex) {
        groups.mapIndexed { index, group ->
            NgExpandableActionMenuItem(
                itemId = BOOKSHELF_GROUP_MENU_ID_BASE + index,
                titleRes = 0,
                iconRes = group.builtInIconRes() ?: R.drawable.ic_groups,
                title = group.name,
                checked = index == selectedGroupIndex,
                selectionStyle = NgMenuSelectionStyle.COMPACT_CHECK,
                leadingIconSize = 16.dp,
            )
        }
    }
    val menuWidth = rememberNgExpandableActionMenuContentWidth(menuItems).coerceAtMost(196.dp)

    Box {
        NgSearchBarActionButton(
            onClick = menuState::onAnchorClick,
            contentDescription = stringResource(R.string.group),
            icon = painterResource(R.drawable.ic_grid_menu),
            tint = if (menuState.expanded) Color(NgTheme.colors.primary) else null,
        )
        NgExpandableActionMenu(
            expanded = menuState.expanded,
            onDismissRequest = menuState::onDismissRequest,
            items = menuItems,
            width = menuWidth,
            offset = BookshelfToolbarMenuOffset,
            variant = NgExpandableActionMenuVariant.END_ANCHORED_DROPDOWN,
            onItemClick = { item ->
                menuState.close()
                val index = item.itemId - BOOKSHELF_GROUP_MENU_ID_BASE
                if (index in groups.indices) {
                    onGroupClick(index)
                }
            },
        )
    }
}

/** Resolve bookshelf display titles from saved translation records and cache only (never auto-runs inference). */
@Composable
internal fun rememberBookshelfBookDisplayNames(
    books: List<Book>,
): (Book) -> String {
    val translateRevision by TranslateConfig.revision.collectAsState()
    val cacheRevision by BookTitleTranslator.cacheRevision.collectAsState()
    val historyBooks by TranslateHistoryStore.booksFlow.collectAsState()
    val targetLang = remember(translateRevision) { TranslateConfig.targetLang }

    return remember(targetLang, cacheRevision, historyBooks, books) {
        { book: Book ->
            BookTitleTranslator.resolveBookDisplayName(book.bookUrl, book.name, targetLang)
        }
    }
}
