package io.legado.app.ui.video

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.TrafficStats
import android.os.Process
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.gsyVideo.VideoDownloadCache
import io.legado.app.help.gsyVideo.VideoDownloadProgress
import io.legado.app.help.gsyVideo.VideoDownloadStatus
import io.legado.app.help.gsyVideo.VideoPlaybackBridge
import io.legado.app.help.gsyVideo.VideoPlaybackState
import io.legado.app.help.translate.BookTitleTranslator
import io.legado.app.help.translate.TranslateHistoryStore
import io.legado.app.model.VideoPlay
import io.legado.app.ui.design.components.NgDialogVariant
import io.legado.app.ui.design.components.compose.*
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class VideoPresentation { WINDOW, IMMERSIVE, FULLSCREEN }

enum class VideoAspectMode(val label: String) {
    FIT("自适应"),
    FILL("铺满"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    STRETCH("拉伸");

    companion object {
        fun fromName(name: String?): VideoAspectMode =
            entries.firstOrNull { it.name == name } ?: FIT
    }
}

@Composable
fun VideoPlayerScreen(
    owner: Any,
    mode: VideoPresentation,
    revision: Int,
    intro: View,
    onMode: (VideoPresentation) -> Unit,
    onBack: () -> Unit,
    onAction: (Int) -> Unit,
    onEpisode: (Int, Int) -> Unit,
    onAddedToShelf: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by VideoPlaybackBridge.state.collectAsState()
    val downloadStates by VideoDownloadCache.downloadStates.collectAsState()
    val windowed = mode == VideoPresentation.WINDOW
    var controls by rememberSaveable { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf("") }
    var panelVolume by rememberSaveable { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(VideoPlay.lockCurScreen) }
    var danmaku by remember { mutableStateOf(VideoPlay.danmakuShow) }
    var aspectMode by remember { mutableStateOf(VideoAspectMode.fromName(VideoPlay.aspectMode)) }
    var expandedIntro by rememberSaveable { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    val bookUrl = remember(revision) { VideoPlay.book?.bookUrl }
    val cacheRevision by BookTitleTranslator.cacheRevision.collectAsState()
    val historyBooks by TranslateHistoryStore.booksFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val translatedMap = remember(bookUrl) { mutableStateMapOf<String, String>() }
    var isTranslating by remember(bookUrl) { mutableStateOf(false) }
    var translateJob by remember(bookUrl) { mutableStateOf<Job?>(null) }
    DisposableEffect(bookUrl) {
        onDispose {
            translateJob?.cancel()
            translateJob = null
        }
    }

    val rawTitle = remember(revision, state.title) {
        VideoPlay.book?.name ?: VideoPlay.videoTitle ?: state.title
    }
    val rawEpisodeTitle = remember(revision, state.title, state.mediaRevision) {
        VideoPlay.chapter?.title?.takeIf { it.isNotBlank() } ?: state.title
    }
    val episodes = remember(revision, state.mediaRevision) { VideoPlay.episodes.orEmpty() }
    val volumes = remember(revision) { VideoPlay.volumes.toList() }
    val catalog = remember(revision, VideoPlay.toc) {
        videoEpisodeGroups(VideoPlay.toc.orEmpty(), { it.isVolume }, {
            it.url.isNotBlank() && !it.url.startsWith(it.title)
        })
    }
    val hasCatalog = VideoPlay.book != null

    val translatedIntro = remember(bookUrl, revision, cacheRevision, historyBooks, translatedMap.size) {
        val rawIntro = VideoPlay.book?.getDisplayIntro().orEmpty()
        if (rawIntro.isBlank() ||
            rawIntro.startsWith("<useweb>") ||
            rawIntro.startsWith("<usehtml>") ||
            rawIntro.startsWith("<md>")
        ) {
            null
        } else {
            TranslateHistoryStore.findOfflineBookIntro(bookUrl, rawIntro)
                ?: translatedMap[rawIntro]
                ?: BookTitleTranslator.getCached(rawIntro)
        }
    }

    val translateText: (String) -> String = remember(bookUrl, cacheRevision, historyBooks, translatedMap.size) {
        { origin: String ->
            if (origin.isBlank()) {
                origin
            } else if (rawTitle != null && origin == rawTitle) {
                translatedMap[origin] ?: BookTitleTranslator.resolveBookDisplayName(bookUrl, origin)
            } else {
                translatedMap[origin] ?: BookTitleTranslator.getCached(origin) ?: origin
            }
        }
    }

    val displayTitle = remember(rawTitle, cacheRevision, historyBooks, translatedMap[rawTitle]) {
        rawTitle?.let(translateText).orEmpty()
    }
    val displayEpisodeTitle = remember(rawEpisodeTitle, cacheRevision, historyBooks, translatedMap[rawEpisodeTitle]) {
        translateText(rawEpisodeTitle)
    }

    fun translateVideoOnce() {
        if (translateJob?.isActive == true) {
            translateJob?.cancel()
            translateJob = null
            isTranslating = false
            return
        }
        val candidates = buildList {
            if (!rawTitle.isNullOrBlank()) add(rawTitle)
            if (rawEpisodeTitle.isNotBlank()) add(rawEpisodeTitle)
            volumes.forEach { if (it.title.isNotBlank()) add(it.title) }
            episodes.forEach { if (it.title.isNotBlank()) add(it.title) }
            catalog.forEach { g ->
                g.volume?.title?.takeIf { it.isNotBlank() }?.let(::add)
                g.items.forEach { if (it.chapter.title.isNotBlank()) add(it.chapter.title) }
            }
        }.distinct()
        candidates.forEach { text ->
            val cached = if (rawTitle != null && text == rawTitle) {
                BookTitleTranslator.resolveBookDisplayName(bookUrl, text).takeIf { it != text }
            } else {
                BookTitleTranslator.getCached(text)
            }
            if (cached != null) translatedMap[text] = cached
        }
        val uncached = candidates.filter {
            it !in translatedMap && BookTitleTranslator.needsTranslation(it)
        }
        val rawIntro = VideoPlay.book?.getDisplayIntro().orEmpty()
        val needIntro = rawIntro.isNotBlank() &&
            !rawIntro.startsWith("<useweb>") &&
            !rawIntro.startsWith("<usehtml>") &&
            !rawIntro.startsWith("<md>") &&
            TranslateHistoryStore.findOfflineBookIntro(bookUrl, rawIntro) == null &&
            BookTitleTranslator.getCached(rawIntro) == null &&
            BookTitleTranslator.needsTranslation(rawIntro)
        if (uncached.isEmpty() && !needIntro) {
            context.toastOnUi("当前内容已全部翻译")
            return
        }
        isTranslating = true
        translateJob = scope.launch {
            try {
                BookTitleTranslator.withTranslationSession {
                    uncached.chunked(25).forEach { batch ->
                        val result = withContext(IO) { BookTitleTranslator.translateBatch(batch) }
                        if (result.isNotEmpty()) {
                            translatedMap.putAll(result)
                            if (bookUrl != null && !rawTitle.isNullOrBlank()) {
                                result[rawTitle]?.let { tr ->
                                    TranslateHistoryStore.recordBookTitle(bookUrl, rawTitle, tr)
                                }
                            }
                        }
                    }
                    if (needIntro) {
                        val trIntro = withContext(IO) { BookTitleTranslator.translateText(rawIntro) }
                        if (!trIntro.isNullOrBlank()) {
                            translatedMap[rawIntro] = trIntro
                            if (bookUrl != null) {
                                TranslateHistoryStore.recordBookIntro(
                                    bookKey = bookUrl,
                                    originalTitle = rawTitle.orEmpty(),
                                    translatedTitle = translatedMap[rawTitle] ?: rawTitle,
                                    originalIntro = rawIntro,
                                    translatedIntro = trIntro,
                                )
                            }
                        }
                    }
                }
            } finally {
                isTranslating = false
            }
        }
    }

    fun openEpisodes(volume: Int = VideoPlay.durVolumeIndex) {
        panelVolume = volume
        panel = "episodes"
    }

    val colors = videoContentColors()
    val primary = colors.accent
    val ink = colors.content
    val surface = colors.surface
    val stablePlayback = state.playing && !state.preparing && !state.buffering && state.error == null && !state.networkConfirmation
    val isLoadingVideo = state.preparing || state.buffering
    val netSpeedText by rememberVideoLoadingNetSpeed(active = isLoadingVideo)

    LaunchedEffect(bookUrl, VideoPlay.toc, revision) {
        VideoDownloadCache.syncCachedChapters(VideoPlay.book, VideoPlay.toc.orEmpty())
    }

    val currentChapter = VideoPlay.chapter
        ?: episodes.getOrNull(VideoPlay.chapterInVolumeIndex)
        ?: volumes.getOrNull(VideoPlay.durVolumeIndex)
    val currentCacheKey = VideoDownloadCache.cacheKey(VideoPlay.book, currentChapter, VideoPlay.videoUrl)
    val isCurrentCached = VideoPlay.isPlayingCachedVideo ||
        downloadStates[currentCacheKey]?.status == VideoDownloadStatus.COMPLETED ||
        VideoDownloadCache.isCached(VideoPlay.book, currentChapter, VideoPlay.videoUrl)
    val currentDownloadProgress = downloadStates[currentCacheKey] ?: if (isCurrentCached) {
        VideoDownloadProgress(status = VideoDownloadStatus.COMPLETED)
    } else {
        VideoDownloadProgress(status = VideoDownloadStatus.IDLE)
    }

    fun triggerEpisodeDownload(targetChapter: BookChapter? = currentChapter) {
        val isTargetCurrent = targetChapter == null ||
            (targetChapter.url == currentChapter?.url && targetChapter.index == currentChapter.index)
        VideoDownloadCache.startOrCancelDownload(
            source = VideoPlay.source,
            book = VideoPlay.book,
            chapter = targetChapter,
            currentVideoUrl = if (isTargetCurrent) VideoPlay.videoUrl else null,
            onAddedToShelf = onAddedToShelf,
        )
    }

    LaunchedEffect(stablePlayback) { if (!stablePlayback) controls = true }
    LaunchedEffect(controls, stablePlayback, interaction, panel, more, hint, scrubbing) {
        if (controls && stablePlayback && panel.isEmpty() && !more && hint == null && !scrubbing) {
            delay(2500); controls = false
        }
    }

    val contentModifier = Modifier
        .fillMaxSize()
        .then(if (windowed) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
        .background(if (windowed) surface else Color.Black)

    BoxWithConstraints(contentModifier) {
        val containerWidth = maxWidth
        val playerHeight = if (windowed) (containerWidth / state.aspect).coerceAtMost(maxHeight * 0.46f) else maxHeight
        val fitWidth = minOf(containerWidth, playerHeight * state.aspect)
        Column(Modifier.fillMaxSize()) {
            // 窗口模式下：将视频名称放在视频上方作为标题栏，并与视频播放区域保持舒适间距
            if (windowed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 10.dp, start = 4.dp, end = 8.dp)
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                            modifier = Modifier.size(22.dp),
                            tint = ink,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                    ) {
                        Text(
                            text = displayTitle.ifBlank { displayEpisodeTitle },
                            color = ink,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (displayEpisodeTitle.isNotBlank() && displayEpisodeTitle != displayTitle) {
                            Text(
                                text = displayEpisodeTitle,
                                color = colors.muted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    NgToolbarTranslateButton(
                        isTranslating = isTranslating,
                        onClick = ::translateVideoOnce,
                        contentDescription = "翻译当前内容",
                        idleTint = colorResource(R.color.ng_search_icon),
                        borderColor = colorResource(R.color.primaryText),
                    )
                    if (VideoPlay.rssRecord != null || VideoPlay.rssStar != null) {
                        Spacer(Modifier.width(2.dp))
                        IconButton(
                            onClick = { onAction(R.id.menu_rss_star) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_star_border),
                                contentDescription = if (VideoPlay.rssStar != null) "已收藏" else "收藏",
                                modifier = Modifier.size(20.dp),
                                tint = if (VideoPlay.rssStar != null) primary else ink,
                            )
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                    Box {
                        IconButton(
                            onClick = { more = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vert),
                                contentDescription = "更多",
                                modifier = Modifier.size(20.dp),
                                tint = ink,
                            )
                        }
                        NgExpandableActionMenu(
                            expanded = more,
                            onDismissRequest = { more = false },
                            items = videoMoreItems(),
                            onItemClick = { item ->
                                more = false
                                if (item.itemId == R.id.menu_config_settings) panel = "settings"
                                else onAction(item.itemId)
                            },
                            properties = PopupProperties(focusable = true),
                        )
                    }
                }
            }

            // 视频播放区域
            Box(Modifier.fillMaxWidth().height(playerHeight).background(Color.Black).clipToBounds()) {
                val renderModifier = when (aspectMode) {
                    VideoAspectMode.FIT -> Modifier
                        .align(Alignment.Center)
                        .width(fitWidth)
                        .height(fitWidth / state.aspect)
                    VideoAspectMode.FILL -> {
                        val fillWidth = maxOf(containerWidth, playerHeight * state.aspect)
                        Modifier
                            .align(Alignment.Center)
                            .width(fillWidth)
                            .height(fillWidth / state.aspect)
                    }
                    VideoAspectMode.STRETCH -> Modifier.fillMaxSize()
                    VideoAspectMode.RATIO_16_9 -> {
                        val ratio = 16f / 9f
                        val w = minOf(containerWidth, playerHeight * ratio)
                        Modifier
                            .align(Alignment.Center)
                            .width(w)
                            .height(w / ratio)
                    }
                    VideoAspectMode.RATIO_4_3 -> {
                        val ratio = 4f / 3f
                        val w = minOf(containerWidth, playerHeight * ratio)
                        Modifier
                            .align(Alignment.Center)
                            .width(w)
                            .height(w / ratio)
                    }
                }
                VideoRenderHost(
                    owner,
                    state,
                    danmaku,
                    renderModifier,
                )
                // 全屏画布使用完整窗口，控件和手势单独避让安全区。
                Box(
                    Modifier
                        .matchParentSize()
                        .then(
                            if (windowed) Modifier
                            else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                        )
                ) {
                    VideoGestureLayer(
                        state = state,
                        mode = mode,
                        locked = locked,
                        enabled = panel.isEmpty() && !more,
                        onTap = { controls = !controls; interaction++ },
                        onHint = { hint = it },
                    )
                    if (controls) {
                        if (!windowed) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(96.dp)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Black.copy(alpha = .58f), Color.Transparent)
                                        )
                                    )
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!locked) VideoIcon(R.drawable.ic_arrow_back, "返回", onBack)
                                if (!locked) {
                                    Text(
                                        text = displayEpisodeTitle.ifBlank { displayTitle },
                                        modifier = Modifier.weight(1f),
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                if (!locked) {
                                    NgToolbarTranslateButton(
                                        isTranslating = isTranslating,
                                        onClick = ::translateVideoOnce,
                                        contentDescription = "翻译当前内容",
                                        idleTint = Color.White,
                                        borderColor = Color.White,
                                    )
                                }
                                if (mode == VideoPresentation.IMMERSIVE && !locked) {
                                    VideoIcon(
                                        R.drawable.ic_fullscreen,
                                        "窗口",
                                        { onMode(VideoPresentation.WINDOW) },
                                    )
                                }
                                if (!locked) {
                                    Box {
                                        VideoIcon(R.drawable.ic_more_vert, "更多", { more = true })
                                        NgExpandableActionMenu(
                                            expanded = more,
                                            onDismissRequest = { more = false },
                                            items = videoMoreItems(),
                                            onItemClick = { item ->
                                                more = false
                                                if (item.itemId == R.id.menu_config_settings) panel = "settings"
                                                else onAction(item.itemId)
                                            },
                                            properties = PopupProperties(focusable = true),
                                        )
                                    }
                                }
                            }
                        }

                        if (!windowed || locked) {
                            TextButton(
                                onClick = {
                                    locked = !locked
                                    VideoPlay.lockCurScreen = locked
                                    interaction++
                                },
                                modifier = Modifier.align(Alignment.CenterStart),
                            ) {
                                Text(if (locked) "解锁" else "锁定", color = Color.White, fontSize = 12.sp)
                            }
                        }

                        if (!locked) {
                            if (!windowed && !state.preparing && !state.buffering && state.error == null) {
                                VideoIcon(
                                    icon = if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp,
                                    label = if (state.playing) "暂停" else if (state.ended) "重播" else "播放",
                                    onClick = { VideoPlaybackBridge.toggle(); interaction++ },
                                    modifier = Modifier.align(Alignment.Center).size(64.dp),
                                )
                            }
                            Column(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Transparent, Color.Black.copy(alpha = .82f))
                                        )
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                if (mode == VideoPresentation.IMMERSIVE) {
                                    Text(
                                        text = displayTitle,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        lineHeight = 22.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (displayEpisodeTitle != displayTitle && displayEpisodeTitle.isNotBlank()) {
                                        Text(
                                            text = displayEpisodeTitle,
                                            color = Color.White.copy(alpha = .8f),
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    VideoIcon(
                                        icon = if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp,
                                        label = if (state.playing) "暂停" else if (state.ended) "重播" else "播放",
                                        onClick = { VideoPlaybackBridge.toggle(); interaction++ },
                                        modifier = Modifier.size(40.dp),
                                    )
                                    Box(Modifier.weight(1f)) {
                                        VideoProgress(state, { interaction++ }, { scrubbing = it })
                                    }
                                    if (windowed) {
                                        VideoIcon(
                                            icon = R.drawable.ic_fullscreen,
                                            label = "全屏",
                                            onClick = { onMode(VideoPresentation.FULLSCREEN) },
                                            modifier = Modifier.size(40.dp),
                                        )
                                    }
                                }
                                // 统一将倍速、竖屏（视频）、小窗等控制按钮放进视频播放控件底部操作栏中
                                val hasDanmaku = VideoPlay.danmakuFile != null || !VideoPlay.danmakuStr.isNullOrBlank()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    if (hasCatalog) {
                                        VideoTextAction("选集") { openEpisodes() }
                                    }
                                    if (episodes.size > 1) {
                                        VideoTextAction("下一集") { VideoPlaybackBridge.next(1) }
                                    }
                                    VideoTextAction(
                                        text = if (state.speed == 1f) "倍速" else "${state.speed}×",
                                        enabled = state.prepared,
                                    ) { panel = "speed" }
                                    VideoTextAction(
                                        text = if (aspectMode == VideoAspectMode.FIT) "比例" else aspectMode.label,
                                    ) { panel = "aspect" }
                                    VideoTextAction(
                                        text = if (mode == VideoPresentation.IMMERSIVE) "窗口" else "竖屏",
                                    ) {
                                        onMode(
                                            if (mode == VideoPresentation.IMMERSIVE) VideoPresentation.WINDOW
                                            else VideoPresentation.IMMERSIVE
                                        )
                                    }
                                    if (hasDanmaku) {
                                        VideoTextAction(if (danmaku) "关弹幕" else "开弹幕") {
                                            danmaku = !danmaku
                                            VideoPlay.danmakuShow = danmaku
                                        }
                                    }
                                    val playerDownloadLabel = when (currentDownloadProgress.status) {
                                        VideoDownloadStatus.DOWNLOADING -> {
                                            val pct = (currentDownloadProgress.progressFraction * 100).toInt()
                                            if (pct > 0) "下载${pct}%" else "下载中"
                                        }
                                        VideoDownloadStatus.COMPLETED -> "已下载"
                                        VideoDownloadStatus.FAILED -> "重试下载"
                                        VideoDownloadStatus.IDLE -> "下载"
                                    }
                                    val playerDownloadColor = when (currentDownloadProgress.status) {
                                        VideoDownloadStatus.DOWNLOADING,
                                        VideoDownloadStatus.COMPLETED -> Color(0xFF4ADE80)
                                        else -> Color.White
                                    }
                                    VideoTextAction(
                                        text = playerDownloadLabel,
                                        color = playerDownloadColor,
                                    ) {
                                        triggerEpisodeDownload(currentChapter)
                                    }
                                    VideoTextAction("小窗") { onAction(R.id.menu_float_window) }
                                    if (!windowed) {
                                        VideoIcon(
                                            icon = R.drawable.ic_fullscreen,
                                            label = if (mode == VideoPresentation.FULLSCREEN) "窗口" else "全屏",
                                            onClick = {
                                                onMode(
                                                    if (mode == VideoPresentation.FULLSCREEN) VideoPresentation.WINDOW
                                                    else VideoPresentation.FULLSCREEN
                                                )
                                            },
                                            modifier = Modifier.size(38.dp),
                                        )
                                    }
                                }
                                if (mode == VideoPresentation.IMMERSIVE && episodes.size > 1) {
                                    Text(
                                        text = "上滑下一集",
                                        modifier = Modifier
                                            .align(Alignment.CenterHorizontally)
                                            .padding(top = 4.dp),
                                        color = Color.White.copy(alpha = .7f),
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    } else if (!windowed && VideoPlay.fullBottomProgressBar) {
                        LinearProgressIndicator(
                            progress = {
                                if (state.duration > 0) state.position.toFloat() / state.duration else 0f
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(2.dp),
                            color = primary,
                        )
                    }

                    // 视频加载时在画面中央显示加载转圈与实时网速
                    if (isLoadingVideo) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(30.dp),
                                color = Color.White,
                                strokeWidth = 2.4.dp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = netSpeedText,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }

                    state.error?.let { error ->
                        Column(
                            Modifier
                                .align(Alignment.Center)
                                .widthIn(max = 360.dp)
                                .padding(horizontal = 20.dp)
                                .background(Color.Black.copy(alpha = .8f), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "暂时无法播放",
                                color = Color.White,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                error,
                                Modifier.padding(top = 6.dp),
                                color = Color.White.copy(alpha = .8f),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            VideoTextAction("重试") { VideoPlaybackBridge.retry() }
                        }
                    }
                    hint?.let {
                        Text(
                            it,
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 64.dp)
                                .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(8.dp))
                                .padding(12.dp, 6.dp),
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            // 窗口模式下方详情区：简介在上方，选集移到简介下方（并在目录中提供下载按钮与剧集绿色进度填充）
            if (windowed) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    VideoPlay.book?.getRealAuthor()?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            modifier = Modifier.padding(bottom = 8.dp),
                            color = colors.muted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    if (VideoPlay.book != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clickable { expandedIntro = !expandedIntro },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("简介", Modifier.weight(1f), color = ink, fontWeight = FontWeight.Medium)
                            Text(if (expandedIntro) "收起" else "展开", color = colors.muted, fontSize = 13.sp)
                        }
                        val rawIntroText = VideoPlay.book?.getDisplayIntro().orEmpty()
                        if (rawIntroText.startsWith("<useweb>") ||
                            rawIntroText.startsWith("<usehtml>") ||
                            rawIntroText.startsWith("<md>")
                        ) {
                            AndroidView(
                                factory = {
                                    (intro.parent as? android.view.ViewGroup)?.removeView(intro)
                                    intro
                                },
                                modifier = if (expandedIntro) {
                                    Modifier.fillMaxWidth()
                                } else {
                                    Modifier.fillMaxWidth().heightIn(max = 120.dp)
                                },
                            )
                        } else {
                            val displayIntroText = if (!translatedIntro.isNullOrBlank()) {
                                translatedIntro!!
                            } else {
                                rawIntroText
                            }
                            Text(
                                text = displayIntroText.ifBlank { "暂无简介" },
                                maxLines = if (expandedIntro) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                                color = colors.muted,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            )
                        }
                    }

                    // 简介下方：选集与目录下载入口
                    if (VideoPlay.book != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (episodes.isEmpty()) "选集" else "选集 · ${episodes.size}",
                                modifier = Modifier.weight(1f),
                                color = ink,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            val catalogDownloadLabel = when (currentDownloadProgress.status) {
                                VideoDownloadStatus.DOWNLOADING -> {
                                    val pct = (currentDownloadProgress.progressFraction * 100).toInt()
                                    if (pct > 0) "下载 ${pct}%" else "下载中"
                                }
                                VideoDownloadStatus.COMPLETED -> "已缓存"
                                VideoDownloadStatus.FAILED -> "重试下载"
                                VideoDownloadStatus.IDLE -> "下载"
                            }
                            val catalogDownloadColor = when (currentDownloadProgress.status) {
                                VideoDownloadStatus.DOWNLOADING,
                                VideoDownloadStatus.COMPLETED -> Color(0xFF16A34A)
                                else -> primary
                            }
                            TextButton(onClick = { triggerEpisodeDownload(currentChapter) }) {
                                Icon(
                                    painter = painterResource(
                                        if (currentDownloadProgress.status == VideoDownloadStatus.COMPLETED) {
                                            R.drawable.ic_check
                                        } else {
                                            R.drawable.ic_download_line
                                        }
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = catalogDownloadColor,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(catalogDownloadLabel, color = catalogDownloadColor)
                            }
                            TextButton(onClick = { openEpisodes() }) {
                                Text("全部", color = primary)
                            }
                        }
                        if (volumes.isNotEmpty()) {
                            ChapterStrip(
                                chapters = volumes,
                                selected = VideoPlay.durVolumeIndex,
                                onSelect = { openEpisodes(it) },
                                volume = true,
                                translateTitle = translateText,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        if (episodes.isNotEmpty()) {
                            ChapterStrip(
                                chapters = episodes,
                                selected = VideoPlay.chapterInVolumeIndex,
                                onSelect = { onEpisode(VideoPlay.durVolumeIndex, it) },
                                translateTitle = translateText,
                                downloadStates = downloadStates,
                                episodeCacheKey = { VideoDownloadCache.cacheKey(VideoPlay.book, it, null) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.networkConfirmation) Dialog({ VideoPlaybackBridge.confirmNetwork(false) }) {
        NgDialog(
            "提示",
            variant = NgDialogVariant.CLASSIC_CONFIRMATION,
            titleFontSize = 18.sp,
            titleFontWeight = FontWeight.Medium,
            actions = {
                NgDialogTextActionButton("取消", { VideoPlaybackBridge.confirmNetwork(false) })
                NgDialogTextActionButton("继续播放", { VideoPlaybackBridge.confirmNetwork(true) })
            },
        ) {
            Text(
                "当前非 Wi-Fi 网络，是否继续播放？",
                color = Color(NgTheme.colors.onSurface),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
    when (panel) {
        "speed" -> VideoSpeedSheet(
            state.speed,
            onSelect = { speed -> VideoPlaybackBridge.speed(speed); panel = "" },
            onDismiss = { panel = "" },
        )
        "aspect" -> VideoAspectSheet(
            current = aspectMode,
            onSelect = { selected ->
                aspectMode = selected
                VideoPlay.aspectMode = selected.name
                panel = ""
            },
            onDismiss = { panel = "" },
        )
        "episodes" -> VideoEpisodeSheet(
            groups = catalog,
            initialVolume = panelVolume,
            currentVolume = VideoPlay.durVolumeIndex,
            currentEpisode = VideoPlay.chapterInVolumeIndex,
            currentDuration = state.duration,
            onSelect = onEpisode,
            onDismiss = { panel = "" },
            translateTitle = translateText,
            isTranslating = isTranslating,
            onToggleTranslate = ::translateVideoOnce,
            downloadStates = downloadStates,
            episodeCacheKey = { VideoDownloadCache.cacheKey(VideoPlay.book, it, null) },
            onDownloadEpisode = ::triggerEpisodeDownload,
        )
        "settings" -> VideoSettings { panel = "" }
    }
}

@Composable
private fun VideoAspectSheet(
    current: VideoAspectMode,
    onSelect: (VideoAspectMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = videoContentColors()
    Dialog(onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "视频比例",
                color = colors.content,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            VideoAspectMode.entries.forEach { mode ->
                val active = mode == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) colors.selectedContainer else colors.cell)
                        .clickable(role = Role.RadioButton) { onSelect(mode) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = mode.label,
                        modifier = Modifier.weight(1f),
                        color = if (active) colors.onAccent else colors.content,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (active) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = colors.onAccent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberVideoLoadingNetSpeed(active: Boolean): State<String> {
    return produceState(initialValue = "0 KB/s", active) {
        if (!active) {
            value = "0 KB/s"
            return@produceState
        }
        val uid = Process.myUid()
        var lastBytes = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0L }
            ?: TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        var lastTime = System.currentTimeMillis()
        while (active) {
            delay(800L)
            val now = System.currentTimeMillis()
            val curBytes = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0L }
                ?: TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            val elapsedMs = (now - lastTime).coerceAtLeast(1L)
            val deltaBytes = (curBytes - lastBytes).coerceAtLeast(0L)
            val speedBytesPerSec = (deltaBytes * 1000L / elapsedMs)
                .coerceAtLeast(runCatching { VideoPlay.videoManager.netSpeed }.getOrDefault(0L))
            lastBytes = curBytes
            lastTime = now
            value = "${ConvertUtils.formatFileSize(speedBytesPerSec)}/s"
        }
    }
}

@Composable
internal fun VideoIcon(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick, modifier.size(48.dp)) {
        Icon(painterResource(icon), label, Modifier.size(24.dp), tint = Color.White)
    }
}

@Composable
private fun VideoTextAction(text: String, color: Color = Color.White, enabled: Boolean = true, action: () -> Unit) {
    TextButton(
        onClick = action,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        modifier = Modifier.height(34.dp),
    ) {
        Text(
            text = text,
            color = if (enabled) color else color.copy(alpha = .45f),
            fontSize = 13.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun VideoProgress(state: VideoPlaybackState, onTouch: () -> Unit, onScrubbing: (Boolean) -> Unit) {
    DisposableEffect(Unit) { onDispose { onScrubbing(false) } }
    var seeking by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(videoTime((seeking?.toLong() ?: state.position)), color = Color.White, fontSize = 11.sp)
        NgSlider(
            seeking ?: state.position.toFloat(),
            { seeking = it; onScrubbing(true); onTouch() },
            0f..state.duration.coerceAtLeast(1).toFloat(),
            Modifier.weight(1f).padding(horizontal = 10.dp),
            variant = NgSliderVariant.COMPACT,
            enabled = state.duration > 0,
            onValueChangeFinished = {
                seeking?.let { VideoPlaybackBridge.seekTo(it.toLong()) }
                seeking = null
                onScrubbing(false)
            },
        )
        Text(videoTime(state.duration), color = Color.White, fontSize = 11.sp)
    }
}

internal fun videoTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun ChapterStrip(
    chapters: List<BookChapter>,
    selected: Int,
    onSelect: (Int) -> Unit,
    volume: Boolean = false,
    translateTitle: (String) -> String = { it },
    downloadStates: Map<String, VideoDownloadProgress> = emptyMap(),
    episodeCacheKey: (BookChapter) -> String = { "${it.index}_${it.url}" },
) {
    val colors = videoContentColors()
    val listState = rememberLazyListState()
    LaunchedEffect(selected, chapters) {
        if (chapters.isNotEmpty()) listState.scrollToItem(selected.coerceIn(chapters.indices))
    }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(chapters, key = { _, chapter -> chapter.url + "#" + chapter.index }) { index, chapter ->
            val progress = if (volume) null else downloadStates[episodeCacheKey(chapter)]
            val fillFraction = progress?.visualFraction ?: 0f
            val hasDownloadFill = fillFraction > 0f
            val isCompleted = progress?.status == VideoDownloadStatus.COMPLETED
            val statusText = progress?.shortStatusText()
            val baseBg = if (selected == index && !hasDownloadFill) colors.selectedContainer else colors.cell
            val textColor = when {
                hasDownloadFill -> Color.White
                selected == index -> colors.onAccent
                else -> colors.content
            }
            Box(
                Modifier
                    .widthIn(min = if (volume) 80.dp else 100.dp, max = if (volume) 180.dp else 160.dp)
                    .height(if (volume) 48.dp else 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(baseBg)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                if (hasDownloadFill) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .fillMaxWidth(fillFraction.coerceIn(0f, 1f))
                            .background(
                                if (isCompleted) Color(0xFF22C55E).copy(alpha = 0.80f)
                                else Color(0xFF22C55E).copy(alpha = 0.62f)
                            )
                    )
                }
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = translateTitle(chapter.title),
                        color = textColor,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = if (volume || statusText != null) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            color = textColor.copy(alpha = 0.92f),
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun videoMoreItems() = buildList {
    fun item(id: Int, title: Int, icon: Int = R.drawable.ic_cfg_about) = add(NgExpandableActionMenuItem(id, title, icon))
    if ((VideoPlay.source as? BookSource)?.customButton == true) item(R.id.menu_custom_btn, R.string.custom_button, R.drawable.ic_custom)
    item(R.id.menu_config_settings, R.string.config_settings, R.drawable.ic_settings)
    if (!VideoPlay.source?.loginUrl.isNullOrBlank()) item(R.id.menu_login, R.string.login, R.drawable.ic_lock_outline)
    item(R.id.menu_copy_video_url, R.string.copy_play_url, R.drawable.ic_copy)
    item(R.id.menu_open_other_video_player, R.string.open_other_video_player)
    item(R.id.menu_edit_source, R.string.edit_book_source, R.drawable.ic_edit)
    item(R.id.menu_log, R.string.log, R.drawable.ic_bug_report)
    item(R.id.menu_network_log, R.string.network_request_log, R.drawable.ic_network_check)
}

@Composable
private fun VideoGestureLayer(
    state: VideoPlaybackState,
    mode: VideoPresentation,
    locked: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
    onHint: (String?) -> Unit,
) {
    val current by rememberUpdatedState(state)
    val context = LocalContext.current
    val canSwitchEpisode = VideoPlay.episodes.orEmpty().size > 1
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(locked, enabled) {
                if (enabled) detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { if (!locked) VideoPlaybackBridge.toggle() },
                    onLongPress = {
                        if (!locked && current.playing) {
                            VideoPlaybackBridge.speed(VideoPlay.longPressSpeed / 10f, temporary = true)
                            onHint("${VideoPlay.longPressSpeed / 10f}×")
                        }
                    },
                    onPress = {
                        try {
                            awaitRelease()
                        } finally {
                            VideoPlaybackBridge.restoreSpeed()
                            onHint(null)
                        }
                    },
                )
            }
            .pointerInput(mode, locked, enabled, canSwitchEpisode) {
                if (locked || !enabled) return@pointerInput
                var startX = 0f; var dx = 0f; var dy = 0f; var position = 0L; var axis = 0
                var volume = 0; var brightness = .5f
                val activity = context as? Activity
                val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                detectDragGestures(
                    onDragStart = {
                        startX = it.x; dx = 0f; dy = 0f; axis = 0; position = current.position
                        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        brightness = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: .5f
                    },
                    onDrag = { change, amount ->
                        change.consume(); dx += amount.x; dy += amount.y
                        if (axis == 0) axis = if (abs(dx) > abs(dy)) 1 else 2
                        if (axis == 1) {
                            onHint(
                                videoTime(
                                    (position + dx / size.width * current.duration)
                                        .toLong()
                                        .coerceIn(0, current.duration.coerceAtLeast(0))
                                )
                            )
                        } else if (mode == VideoPresentation.IMMERSIVE && canSwitchEpisode && startX in size.width * .2f..size.width * .8f) {
                            onHint(if (dy < 0) "下一集" else "上一集")
                        } else if (startX > size.width / 2) {
                            val target = (volume - dy / size.height * maxVolume).toInt().coerceIn(0, maxVolume)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            onHint("音量 ${(target * 100 / maxVolume.coerceAtLeast(1))}%")
                        } else {
                            val target = (brightness - dy / size.height).coerceIn(.01f, 1f)
                            activity?.window?.let { it.attributes = it.attributes.apply { screenBrightness = target } }
                            onHint("亮度 ${(target * 100).toInt()}%")
                        }
                    },
                    onDragCancel = { onHint(null) },
                    onDragEnd = {
                        if (axis == 1) {
                            VideoPlaybackBridge.seekTo((position + dx / size.width * current.duration).toLong())
                        } else if (mode == VideoPresentation.IMMERSIVE && canSwitchEpisode && startX in size.width * .2f..size.width * .8f && abs(dy) >= 64.dp.toPx()) {
                            VideoPlaybackBridge.next(if (dy < 0) 1 else -1)
                        }
                        onHint(null)
                    },
                )
            }
    )
}
