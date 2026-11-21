package io.legado.app.service

import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.BookSourceDedupe
import io.legado.app.help.source.BookSourceR18Detector
import io.legado.app.help.source.BookSourceReachabilityStore
import io.legado.app.help.source.SourceContentProbe
import io.legado.app.help.source.WebsiteTitleProbeResult
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.SourceInteractionBlockedException
import io.legado.app.help.source.SourceInteractionKind
import io.legado.app.help.source.SourceInteractionPolicy
import io.legado.app.model.CheckSource
import io.legado.app.model.CheckSourceStage
import io.legado.app.model.CheckSourceResultKind
import io.legado.app.model.CheckSourceTaskStatus
import io.legado.app.model.CheckSourceTaskStore
import io.legado.app.model.Debug
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.jsSource.isJsSource
import io.legado.app.ui.book.source.manage.BookSourceCheckActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.htmlunit.corejs.javascript.WrappedException
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * 校验书源
 */
class CheckSourceService : BaseService() {
    private var threadCount = AppConfig.threadCount
    private var searchCoroutine =
        Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    private var notificationMsg = appCtx.getString(R.string.service_starting)
    private var checkJob: Job? = null
    private val titleProbeCache = ConcurrentHashMap<String, Deferred<WebsiteTitleProbeResult>>()
    private var originSize = 0
    private var finishCount = 0

    private val cancelPendingIntent by lazy {
        servicePendingIntent<CheckSourceService>(IntentAction.stop)
    }

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_check_source)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentTitle(getString(R.string.check_book_source))
            .setContentIntent(
                activityPendingIntent<BookSourceCheckActivity>("activity")
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    private fun buildNotificationViews(): RemoteViews {
        return RemoteViews(packageName, R.layout.notification_check_source).apply {
            setTextViewText(R.id.tv_title, getString(R.string.check_book_source))
            setTextViewText(R.id.tv_content, notificationMsg)
            setProgressBar(R.id.progress_bar, originSize, finishCount, originSize <= 0)
            setOnClickPendingIntent(R.id.btn_cancel, cancelPendingIntent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> IntentData.get<List<String>>("checkSourceSelectedIds")?.let {
                check(it)
            }

            IntentAction.resume -> upNotification()
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        Debug.finishChecking()
        if (CheckSourceTaskStore.state.value.status == CheckSourceTaskStatus.RUNNING) {
            CheckSourceTaskStore.finish(cancelled = true)
        }
        titleProbeCache.clear()
        SourceContentProbe.clearCheckedPageTitles()
        searchCoroutine.close()
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
        notificationManager.cancel(NotificationId.CheckSourceService)
    }

    private fun check(ids: List<String>) {
        if (checkJob?.isActive == true) {
            toastOnUi("已有书源在校验,等完成后再试")
            return
        }
        titleProbeCache.clear()
        SourceContentProbe.clearCheckedPageTitles()
        checkJob = lifecycleScope.launch(searchCoroutine) {
            val pendingUpdates = ArrayList<BookSource>(64)
            var lastFlushAt = SystemClock.elapsedRealtime()
            fun flushPendingUpdates(force: Boolean = false) {
                if (pendingUpdates.isEmpty()) return
                val now = SystemClock.elapsedRealtime()
                if (force || pendingUpdates.size >= 64 || now - lastFlushAt >= 1000L) {
                    appDb.bookSourceDao.update(*pendingUpdates.toTypedArray())
                    pendingUpdates.clear()
                    lastFlushAt = now
                }
            }
            flow {
                for (origin in ids) {
                    appDb.bookSourceDao.getBookSource(origin)?.let {
                        emit(it)
                    }
                }
            }.onStart {
                originSize = ids.size
                finishCount = 0
                notificationMsg = getString(R.string.progress_show, "", 0, originSize)
                upNotification()
            }.onEachParallel(threadCount) {
                checkSource(it)
            }.onEach {
                finishCount++
                notificationMsg = getString(
                    R.string.progress_show,
                    it.bookSourceName,
                    finishCount,
                    originSize
                )
                upNotification()
                pendingUpdates.add(it)
                flushPendingUpdates()
            }.onCompletion { cause ->
                flushPendingUpdates(force = true)
                CheckSourceTaskStore.finish(cancelled = cause != null)
                stopSelf()
            }.collect()
        }
    }

    private suspend fun checkSource(source: BookSource) {
        val startedAt = SystemClock.elapsedRealtime()
        CheckSourceTaskStore.markRunning(
            origin = source.bookSourceUrl,
            sourceName = source.bookSourceName,
            sourceType = source.bookSourceType,
        )
        try {
            val interactionPolicy = SourceInteractionPolicy(
                blockDialogs = CheckSource.blockSourceDialogs,
                blockMediaLaunches = CheckSource.blockSourceDialogs,
                throwOnBlocked = CheckSource.blockSourceDialogs,
            )
            val summary = withContext(interactionPolicy) {
                withTimeout(CheckSource.timeout) {
                    doCheckSource(source)
                }
            }
            interactionPolicy.blockedRequest?.let {
                throw SourceInteractionBlockedException(it)
            }
            val finalMsg = if (summary.isNullOrBlank()) "校验成功" else "校验成功:$summary"
            Debug.updateFinalMessage(source.bookSourceUrl, finalMsg)
            BookSourceReachabilityStore.markReachable(source.bookSourceUrl)
            CheckSourceTaskStore.markPassed(
                source.bookSourceUrl,
                SystemClock.elapsedRealtime() - startedAt,
                source.contentResultKind(),
                message = summary.orEmpty(),
                sourceName = source.bookSourceName,
            )
        } catch (throwable: Throwable) {
            currentCoroutineContext().ensureActive()
            val interaction = throwable.findSourceInteraction()
            if (interaction != null) {
                if (interaction.request.kind == SourceInteractionKind.VIDEO_PLAYER) {
                    source.addGroup("通过")
                    BookSourceReachabilityStore.markReachable(source.bookSourceUrl)
                    Debug.updateFinalMessage(source.bookSourceUrl, "校验成功:播放地址已解析")
                    CheckSourceTaskStore.markPassed(
                        origin = source.bookSourceUrl,
                        durationMillis = SystemClock.elapsedRealtime() - startedAt,
                        resultKind = CheckSourceResultKind.CONTENT_PARSED,
                        message = "播放地址已解析",
                    )
                    source.respondTime = Debug.getRespondTime(source.bookSourceUrl)
                    return
                }
                source.addGroup("已阻止弹窗")
                Debug.updateFinalMessage(
                    source.bookSourceUrl,
                    "校验已阻止:已阻止弹窗",
                )
                CheckSourceTaskStore.markBlocked(
                    origin = source.bookSourceUrl,
                    durationMillis = SystemClock.elapsedRealtime() - startedAt,
                )
                source.respondTime = Debug.getRespondTime(source.bookSourceUrl)
                return
            }
            source.removeGroup("通过")
            when (throwable) {
                is TimeoutCancellationException -> source.addGroup("校验超时")
                is ScriptException, is WrappedException -> source.addGroup("js失效")
                !is NoStackTraceException -> source.addGroup("网站失效")
            }
            if (CheckSource.wSourceComment) {
                source.addErrorComment(throwable)
            }
            val message = throwable.localizedMessage.orEmpty().ifBlank {
                throwable.javaClass.simpleName
            }
            Debug.updateFinalMessage(source.bookSourceUrl, "校验失败:$message")
            BookSourceReachabilityStore.markUnreachable(source.bookSourceUrl)
            CheckSourceTaskStore.markFailed(
                origin = source.bookSourceUrl,
                message = message,
                durationMillis = SystemClock.elapsedRealtime() - startedAt,
            )
        }
        source.respondTime = Debug.getRespondTime(source.bookSourceUrl)
    }

    private suspend fun isDomainReachable(domain: String): Boolean {
        return kotlin.runCatching {
            withTimeout(2000) {
                val url = URI(domain.substringBefore("#"))
                val port = url.port.takeIf { it > 0 }
                    ?: if (url.scheme.equals("https", ignoreCase = true)) 443 else 80
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(url.host, port), 1600)
                    true
                }
            }
        }.getOrDefault(false)
    }

    private suspend fun probeWebsiteTitleCached(source: BookSource): WebsiteTitleProbeResult {
        val cacheKey = BookSourceDedupe.normalizeUrlKey(source.bookSourceUrl)
            .ifBlank { source.bookSourceUrl.trim().lowercase() }
        val deferred = titleProbeCache.computeIfAbsent(cacheKey) {
            lifecycleScope.async(searchCoroutine) {
                kotlin.runCatching {
                    withTimeout(3500) {
                        SourceContentProbe.probeWebsiteTitleAwait(source)
                    }
                }.getOrElse {
                    WebsiteTitleProbeResult(reachable = false, title = null)
                }
            }
        }
        return deferred.await()
    }

    private suspend fun doCheckSource(source: BookSource): String? {
        Debug.startChecking(source)
        source.removeInvalidGroups()
        source.removeGroup("源名称获取失败")
        source.removeGroup("通过")
        if (CheckSource.wSourceComment) {
            source.removeErrorComment()
        }
        var verifiedSummary: String? = null
        var domainTitleProbe: WebsiteTitleProbeResult? = null
        //检测源地址可访问性
        if (CheckSource.checkDomain) {
            markStage(source, CheckSourceStage.DOMAIN)
            val domain = source.bookSourceUrl
            if (!domain.startsWith("http", ignoreCase = true)) {
                source.addGroup("非HTTP源地址")
                throw NoStackTraceException("源地址不是http链接")
            } else {
                source.removeGroup("非HTTP源地址")
                if (CheckSource.syncSiteName) {
                    val probe = probeWebsiteTitleCached(source)
                    domainTitleProbe = probe
                    if (probe.reachable) {
                        source.removeGroup("域名失效")
                    } else {
                        source.addGroup("域名失效")
                        throw NoStackTraceException("源地址不可访问")
                    }
                } else if (isDomainReachable(domain)) {
                    source.removeGroup("域名失效")
                } else {
                    source.addGroup("域名失效")
                    throw NoStackTraceException("源地址不可访问")
                }
            }
        }
        //校验搜索书籍
        if (CheckSource.checkSearch) {
            markStage(source, CheckSourceStage.SEARCH)
            val searchWord = source.getCheckKeyword(CheckSource.keyword)
            if (source.isJsSource() || !source.searchUrl.isNullOrBlank()) {
                source.removeGroup("搜索链接规则为空")
                val searchBooks = WebBook.searchBookAwait(source, searchWord)
                if (searchBooks.isEmpty()) {
                    source.addGroup("搜索失效")
                } else {
                    source.removeGroup("搜索失效")
                    verifiedSummary = checkBook(searchBooks.first().toBook(), source) ?: verifiedSummary
                }
            } else {
                source.addGroup("搜索链接规则为空")
            }
        }
        //校验发现书籍
        val exploreTitles = ArrayList<String>()
        if (CheckSource.checkDiscovery && !source.exploreUrl.isNullOrBlank()) {
            markStage(source, CheckSourceStage.DISCOVERY)
            val kinds = source.exploreKinds()
            kinds.forEach { kind ->
                if (kind.title.isNotBlank()) exploreTitles.add(kind.title)
            }
            val url = kinds.firstOrNull {
                !it.url.isNullOrBlank()
            }?.url
            if (url.isNullOrBlank()) {
                source.addGroup("发现规则为空")
            } else {
                source.removeGroup("发现规则为空")
                val exploreBooks = WebBook.exploreBookAwait(source, url)
                if (exploreBooks.isEmpty()) {
                    source.addGroup("发现失效")
                } else {
                    source.removeGroup("发现失效")
                    verifiedSummary = checkBook(exploreBooks.first().toBook(), source, false) ?: verifiedSummary
                }
            }
        }
        var detectedSiteTitle: String? = null
        val shouldSyncSiteName = CheckSource.syncSiteName &&
            (CheckSource.checkDomain || CheckSource.checkSearch || CheckSource.checkDiscovery)
        if (shouldSyncSiteName) {
            val capturedPageTitle = SourceContentProbe.consumeCheckedPageTitle(source.bookSourceUrl)
            val realSiteName = domainTitleProbe?.title
                ?: capturedPageTitle
                ?: if (source.bookSourceUrl.startsWith("http", ignoreCase = true)) {
                    probeWebsiteTitleCached(source).title
                } else {
                    null
                }
            detectedSiteTitle = realSiteName
            if (!realSiteName.isNullOrBlank()) {
                source.removeGroup("源名称获取失败")
                if (realSiteName != source.bookSourceName) {
                    source.bookSourceName = realSiteName
                    Debug.log(source.bookSourceUrl, "≡更新源名称:$realSiteName")
                }
            } else {
                source.addGroup("源名称获取失败")
                Debug.log(source.bookSourceUrl, "≡源名称获取失败")
            }
        }
        // 校验阶段联动识别 R18 书源并追加 R18 分组（保留原有分组）
        val r18ExtraTexts = buildList {
            detectedSiteTitle?.let(::add)
            addAll(exploreTitles)
        }
        if (BookSourceR18Detector.isR18(source, r18ExtraTexts)) {
            source.addGroup(BookSourceR18Detector.R18_GROUP_NAME)
        }
        val finalCheckMessage = source.getInvalidGroupNames()
        if (finalCheckMessage.isNotBlank()) {
            throw NoStackTraceException(finalCheckMessage)
        }
        source.addGroup("通过")
        return verifiedSummary
    }

    /**
     * 校验书源的详情、目录与按类型匹配的正文/资源
     */
    private suspend fun checkBook(
        book: Book,
        source: BookSource,
        isSearchBook: Boolean = true,
    ): String? {
        var summary: String? = null
        kotlin.runCatching {
            if (!CheckSource.checkInfo) {
                return null
            }
            //校验详情
            if (book.tocUrl.isBlank() || source.bookSourceType == BookSourceType.file) {
                markStage(source, CheckSourceStage.INFO)
                WebBook.getBookInfoAwait(source, book)
            }
            if (source.bookSourceType == BookSourceType.file) {
                if (!CheckSource.checkContent) return null
                markStage(source, CheckSourceStage.CONTENT)
                summary = verifyFileDownloadUrls(source, book)
                return@runCatching
            }
            if (!CheckSource.checkCategory) {
                return null
            }
            //校验目录
            markStage(source, CheckSourceStage.CATALOG)
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow().asSequence()
                .filter { !(it.isVolume && it.url.startsWith(it.title)) }
                .take(2)
                .toList()
            if (toc.isEmpty()) {
                throw TocEmptyException("目录为空")
            }
            val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
            if (!CheckSource.checkContent) {
                return null
            }
            //校验正文（按书源类型自动匹配）
            markStage(source, CheckSourceStage.CONTENT)
            val chapter = toc.first()
            val content = WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = chapter,
                nextChapterUrl = nextChapterUrl,
                needSave = false
            )
            summary = verifyContentByType(source, chapter, content)
        }.onFailure {
            val bookType = if (isSearchBook) "搜索" else "发现"
            when (it) {
                is ContentEmptyException -> source.addGroup("${bookType}正文失效")
                is TocEmptyException -> source.addGroup("${bookType}目录失效")
                else -> throw it
            }
        }.onSuccess {
            val bookType = if (isSearchBook) "搜索" else "发现"
            source.removeGroup("${bookType}目录失效")
            source.removeGroup("${bookType}正文失效")
        }
        return summary
    }

    private suspend fun verifyContentByType(
        source: BookSource,
        chapter: BookChapter,
        content: String,
    ): String {
        return when (source.bookSourceType) {
            BookSourceType.image -> verifyImageContent(source, chapter, content)
            BookSourceType.audio -> verifyAudioContent(source, chapter, content)
            BookSourceType.video -> verifyVideoContent(source, chapter, content)
            else -> verifyTextContent(content)
        }
    }

    private fun verifyTextContent(content: String): String {
        val plainLength = SourceContentProbe.countVisibleTextChars(content)
        if (plainLength == 0) {
            throw ContentEmptyException("文本正文为空")
        }
        val minLen = CheckSource.textMinLength
        if (minLen > 0 && plainLength < minLen) {
            throw ContentEmptyException("正文过短(${plainLength}字<${minLen}字)")
        }
        return "正文有效 (${plainLength} 字)"
    }

    private suspend fun verifyImageContent(
        source: BookSource,
        chapter: BookChapter,
        content: String,
    ): String {
        val images = BookHelp.flowImages(chapter, content)
            .distinctUntilChanged()
            .toList()
        if (images.isEmpty()) {
            throw ContentEmptyException("未解析到图片链接")
        }
        if (!CheckSource.checkImageReachable) {
            return "已解析 ${images.size} 张图片"
        }
        val sampleCount = CheckSource.imageSampleCount.coerceIn(1, 5)
        val samples = images.take(sampleCount)
        var okCount = 0
        for (imgUrl in samples) {
            currentCoroutineContext().ensureActive()
            if (SourceContentProbe.probeUrl(source, imgUrl, referer = chapter.url, allowHtml = false).ok) {
                okCount++
            }
        }
        if (okCount == 0) {
            throw NoStackTraceException("图片链接失效 (0/${samples.size})")
        }
        return "图片有效 (共 ${images.size} 张，抽样 $okCount/${samples.size})"
    }

    private suspend fun verifyAudioContent(
        source: BookSource,
        chapter: BookChapter,
        content: String,
    ): String {
        val audioUrl = SourceContentProbe.extractFirstHttpUrl(content)
            ?: throw ContentEmptyException("未解析到音频链接")
        if (!CheckSource.checkAudioReachable) {
            return "音频链接已解析"
        }
        if (!SourceContentProbe.probeUrl(source, audioUrl, referer = chapter.url, allowHtml = false).ok) {
            throw NoStackTraceException("音频链接失效")
        }
        return "音频流有效"
    }

    private suspend fun verifyVideoContent(
        source: BookSource,
        chapter: BookChapter,
        content: String,
    ): String {
        val videoUrl = SourceContentProbe.extractFirstHttpUrl(content)
            ?: throw ContentEmptyException("未解析到视频链接")
        if (!CheckSource.checkVideoReachable) {
            return "视频链接已解析"
        }
        val isDirectStream = SourceContentProbe.isDirectVideoStreamUrl(videoUrl)
        if (!SourceContentProbe.probeUrl(source, videoUrl, referer = chapter.url, allowHtml = !isDirectStream).ok) {
            throw NoStackTraceException("视频链接失效")
        }
        return "视频地址有效"
    }

    private suspend fun verifyFileDownloadUrls(
        source: BookSource,
        book: Book,
    ): String {
        val urls = book.downloadUrls
            .orEmpty()
            .mapNotNull(SourceContentProbe::extractFirstHttpUrl)
            .distinct()
        if (urls.isEmpty()) {
            throw ContentEmptyException("文件下载链接为空")
        }
        if (!CheckSource.checkFileDownloadReachable) {
            return "已解析 ${urls.size} 条下载链接"
        }
        if (!SourceContentProbe.probeUrl(source, urls.first(), referer = book.bookUrl, allowHtml = true).ok) {
            throw NoStackTraceException("文件下载链接失效")
        }
        return "下载链接有效 (${urls.size} 条)"
    }

    private fun upNotification() {
        val views = buildNotificationViews()
        notificationBuilder
            .setContentText(notificationMsg)
            .setCustomContentView(views)
            .setCustomBigContentView(views)
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        notificationManager.notify(NotificationId.CheckSourceService, notificationBuilder.build())
    }

    private fun markStage(source: BookSource, stage: CheckSourceStage) {
        CheckSourceTaskStore.markStage(
            origin = source.bookSourceUrl,
            sourceName = source.bookSourceName,
            stage = stage,
        )
    }

    private fun Throwable.findSourceInteraction(): SourceInteractionBlockedException? {
        val visited = hashSetOf<Throwable>()
        var current: Throwable? = this
        while (current != null && visited.add(current)) {
            if (current is SourceInteractionBlockedException) return current
            current = if (current is WrappedException) {
                current.wrappedException
            } else {
                current.cause
            }
        }
        return null
    }

    private fun BookSource.contentResultKind(): CheckSourceResultKind {
        return when (bookSourceType) {
            BookSourceType.image,
            BookSourceType.audio,
            BookSourceType.video -> CheckSourceResultKind.CONTENT_PARSED
            else -> CheckSourceResultKind.STANDARD
        }
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        val views = buildNotificationViews()
        notificationBuilder
            .setContentText(notificationMsg)
            .setCustomContentView(views)
            .setCustomBigContentView(views)
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        startForeground(NotificationId.CheckSourceService, notificationBuilder.build())
    }

}
