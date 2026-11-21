package io.legado.app.help.source

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.rss.Rss
import io.legado.app.ui.rss.source.manage.RssSourceCheckActivity
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class RssCheckTaskStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    CANCELLED,
}

enum class RssCheckItemStatus {
    WAITING,
    RUNNING,
    PASSED,
    FAILED,
}

data class RssCheckOptions(
    val timeoutSeconds: Long = 15L,
    val checkConnect: Boolean = true,
    val checkList: Boolean = true,
    val checkContent: Boolean = false,
    val writeStatusGroup: Boolean = true,
    val writeComment: Boolean = false,
)

data class RssCheckItemState(
    val sourceUrl: String,
    val sourceName: String,
    val status: RssCheckItemStatus = RssCheckItemStatus.WAITING,
    val detail: String = "",
    val elapsedMs: Long = 0L,
    val updatedAt: Long = 0L,
)

data class RssCheckTaskState(
    val runId: Long = 0L,
    val status: RssCheckTaskStatus = RssCheckTaskStatus.IDLE,
    val totalCount: Int = 0,
    val processedCount: Int = 0,
    val passedCount: Int = 0,
    val failedCount: Int = 0,
    val currentSourceName: String = "",
    val startedAtMillis: Long = 0L,
    val finishedAtMillis: Long = 0L,
    val items: List<RssCheckItemState> = emptyList(),
    val dismissed: Boolean = false,
) {
    val showManageEntry: Boolean
        get() = !dismissed && (status == RssCheckTaskStatus.RUNNING ||
            ((status == RssCheckTaskStatus.COMPLETED || status == RssCheckTaskStatus.CANCELLED) && totalCount > 0))

    val progressFraction: Float
        get() = if (totalCount <= 0) 0f else processedCount.toFloat() / totalCount.toFloat()
}

object RssSourceChecker {

    private const val UI_EMIT_INTERVAL_MS = 160L
    private const val ACTION_STOP_RSS_CHECK = "io.legado.app.action.STOP_RSS_CHECK"
    private val scope = CoroutineScope(SupervisorJob() + IO)
    private val runIdCounter = AtomicLong(0L)
    private var receiverRegistered = false

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP_RSS_CHECK) {
                stop()
            }
        }
    }

    private fun ensureReceiverRegistered() {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            val filter = IntentFilter(ACTION_STOP_RSS_CHECK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appCtx.registerReceiver(stopReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    private val openActivityPendingIntent: PendingIntent by lazy {
        val intent = Intent(appCtx, RssSourceCheckActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        PendingIntent.getActivity(appCtx, NotificationId.CheckRssSource, intent, flags)
    }

    private val cancelPendingIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_STOP_RSS_CHECK).setPackage(appCtx.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        PendingIntent.getBroadcast(appCtx, NotificationId.CheckRssSource, intent, flags)
    }

    private fun updateNotification(processedCount: Int, totalCount: Int, currentName: String) {
        runCatching {
            ensureReceiverRegistered()
            val msg = appCtx.getString(R.string.progress_show, currentName, processedCount, totalCount)
            val views = RemoteViews(appCtx.packageName, R.layout.notification_check_source).apply {
                setTextViewText(R.id.tv_title, "校验订阅源")
                setTextViewText(R.id.tv_content, msg)
                setProgressBar(R.id.progress_bar, totalCount, processedCount, totalCount <= 0)
                setOnClickPendingIntent(R.id.btn_cancel, cancelPendingIntent)
            }
            val notification = NotificationCompat.Builder(appCtx, AppConst.channelIdReadAloud)
                .setSmallIcon(R.drawable.ic_check_source)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setContentTitle("校验订阅源")
                .setContentText(msg)
                .setCustomContentView(views)
                .setCustomBigContentView(views)
                .setContentIntent(openActivityPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            notificationManager.notify(NotificationId.CheckRssSource, notification)
        }
    }

    private fun cancelNotification() {
        runCatching { notificationManager.cancel(NotificationId.CheckRssSource) }
    }

    private val _stateFlow = MutableStateFlow(RssCheckTaskState())
    val stateFlow: StateFlow<RssCheckTaskState> = _stateFlow.asStateFlow()

    private var activeJob: Job? = null

    fun start(sources: List<RssSource>, options: RssCheckOptions = RssCheckOptions()) {
        if (sources.isEmpty()) return
        activeJob?.cancel()
        val runId = runIdCounter.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        val total = sources.size
        val concurrency = AppConfig.threadCount.coerceIn(1, AppConst.MAX_THREAD)
        val timeoutSec = options.timeoutSeconds.coerceIn(3L, 120L)
        val ioTimeoutSec = minOf(timeoutSec, 8L)
        val client = okHttpClient.newBuilder()
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
            .connectTimeout(ioTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(ioTimeoutSec, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val processed = AtomicInteger(0)
        val passed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val lastEmitMs = AtomicLong(0L)
        val failedHostCache = ConcurrentHashMap<String, String>()
        val itemsArray = Array(total) { index ->
            val src = sources[index]
            RssCheckItemState(
                sourceUrl = src.sourceUrl,
                sourceName = src.sourceName,
                status = RssCheckItemStatus.WAITING,
            )
        }

        fun emitState(
            status: RssCheckTaskStatus,
            currentName: String,
            force: Boolean = false,
            finishedAt: Long = 0L,
        ) {
            val now = System.currentTimeMillis()
            if (!force) {
                val prev = lastEmitMs.get()
                if (now - prev < UI_EMIT_INTERVAL_MS || !lastEmitMs.compareAndSet(prev, now)) {
                    return
                }
            } else {
                lastEmitMs.set(now)
            }
            val snapshot = synchronized(itemsArray) { itemsArray.toList() }
            val done = processed.get()
            if (status == RssCheckTaskStatus.RUNNING) {
                updateNotification(done, total, currentName)
            } else {
                cancelNotification()
            }
            _stateFlow.value = RssCheckTaskState(
                runId = runId,
                status = status,
                totalCount = total,
                processedCount = processed.get(),
                passedCount = passed.get(),
                failedCount = failed.get(),
                currentSourceName = currentName,
                startedAtMillis = startedAt,
                finishedAtMillis = finishedAt,
                items = snapshot,
                dismissed = false,
            )
        }

        emitState(
            status = RssCheckTaskStatus.RUNNING,
            currentName = sources.first().sourceName,
            force = true,
        )

        activeJob = scope.launch {
            val updatedSources = ConcurrentHashMap<String, RssSource>()
            try {
                val semaphore = Semaphore(concurrency)
                sources.mapIndexed { index, source ->
                    async {
                        semaphore.withPermit {
                            val startMs = System.currentTimeMillis()
                            synchronized(itemsArray) {
                                itemsArray[index] = itemsArray[index].copy(
                                    status = RssCheckItemStatus.RUNNING,
                                    updatedAt = startMs,
                                )
                            }
                            val (ok, detail, statusTag) = checkSingle(
                                source = source,
                                options = options,
                                client = client,
                                failedHostCache = failedHostCache,
                            )
                            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                            if (ok) {
                                RssSourceReachabilityStore.markReachable(source.sourceUrl)
                                passed.incrementAndGet()
                            } else {
                                RssSourceReachabilityStore.markUnreachable(source.sourceUrl)
                                failed.incrementAndGet()
                            }
                            if (options.writeStatusGroup || options.writeComment) {
                                val nextGroup = if (options.writeStatusGroup) {
                                    mergeStatusGroup(source.sourceGroup, statusTag)
                                } else {
                                    source.sourceGroup
                                }
                                val nextComment = if (options.writeComment && !ok) {
                                    "校验失败：$detail"
                                } else {
                                    source.sourceComment
                                }
                                if (nextGroup != source.sourceGroup || nextComment != source.sourceComment) {
                                    updatedSources[source.sourceUrl] = source.copy(
                                        sourceGroup = nextGroup,
                                        sourceComment = nextComment,
                                    )
                                }
                            }
                            synchronized(itemsArray) {
                                itemsArray[index] = RssCheckItemState(
                                    sourceUrl = source.sourceUrl,
                                    sourceName = source.sourceName,
                                    status = if (ok) RssCheckItemStatus.PASSED else RssCheckItemStatus.FAILED,
                                    detail = detail,
                                    elapsedMs = elapsed,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            }
                            val done = processed.incrementAndGet()
                            emitState(
                                status = RssCheckTaskStatus.RUNNING,
                                currentName = source.sourceName,
                                force = done == total,
                            )
                        }
                    }
                }.awaitAll()
                if (updatedSources.isNotEmpty()) {
                    runCatching {
                        appDb.runInTransaction {
                            updatedSources.values.chunked(200).forEach { chunk ->
                                appDb.rssSourceDao.update(*chunk.toTypedArray())
                            }
                        }
                    }
                }
                emitState(
                    status = RssCheckTaskStatus.COMPLETED,
                    currentName = "",
                    force = true,
                    finishedAt = System.currentTimeMillis(),
                )
            } catch (e: CancellationException) {
                if (updatedSources.isNotEmpty()) {
                    runCatching {
                        appDb.runInTransaction {
                            updatedSources.values.chunked(200).forEach { chunk ->
                                appDb.rssSourceDao.update(*chunk.toTypedArray())
                            }
                        }
                    }
                }
                throw e
            } catch (_: Throwable) {
                emitState(
                    status = RssCheckTaskStatus.COMPLETED,
                    currentName = "",
                    force = true,
                    finishedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        cancelNotification()
        val cur = _stateFlow.value
        if (cur.status == RssCheckTaskStatus.RUNNING) {
            _stateFlow.value = cur.copy(
                status = RssCheckTaskStatus.CANCELLED,
                currentSourceName = "",
                finishedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    fun dismiss() {
        val cur = _stateFlow.value
        if (cur.status == RssCheckTaskStatus.RUNNING) {
            stop()
        }
        _stateFlow.value = _stateFlow.value.copy(dismissed = true)
    }

    private fun mergeStatusGroup(existing: String?, tag: String): String {
        val statusTags = setOf("通过", "域名失效", "非HTTP源地址", "列表规则失效", "正文规则失效", "校验超时")
        val cleaned = existing.orEmpty()
            .split(',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in statusTags }
            .toMutableList()
        cleaned.add(0, tag)
        return cleaned.distinct().joinToString(",")
    }

    private fun resolveTargetUrl(source: RssSource): String? {
        val primary = source.sourceUrl.trim()
        if (primary.startsWith("http://", ignoreCase = true) ||
            primary.startsWith("https://", ignoreCase = true)
        ) {
            return primary.substringBefore(",")
        }
        val sortRaw = source.sortUrl?.trim().orEmpty()
        if (sortRaw.isNotEmpty() && !sortRaw.startsWith("<js>", true) && !sortRaw.startsWith("@js:", true)) {
            val firstLine = sortRaw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            val candidate = if (firstLine.contains("::")) {
                firstLine.substringAfter("::").trim()
            } else {
                firstLine.trim()
            }
            if (candidate.startsWith("http://", ignoreCase = true) ||
                candidate.startsWith("https://", ignoreCase = true)
            ) {
                return candidate.substringBefore(",")
            }
        }
        return null
    }

    private fun extractHost(url: String): String? =
        runCatching { URI(url).host?.lowercase()?.takeIf(String::isNotBlank) }.getOrNull()

    private suspend fun probeHttpHeadOrRange(
        client: OkHttpClient,
        targetUrl: String,
        headers: Map<String, String>,
    ): Int {
        fun buildRequest(head: Boolean): Request {
            val builder = Request.Builder().url(targetUrl)
            if (head) {
                builder.head()
            } else {
                builder.get().header("Range", "bytes=0-0")
            }
            headers.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) {
                    runCatching { builder.header(k, v) }
                }
            }
            return builder.build()
        }

        val headCode = client.newCall(buildRequest(head = true)).await().use { it.code }
        if (headCode in 200..399) return headCode
        if (headCode == 405 || headCode == 403 || headCode == 501) {
            return client.newCall(buildRequest(head = false)).await().use { it.code }
        }
        return headCode
    }

    private suspend fun checkSingle(
        source: RssSource,
        options: RssCheckOptions,
        client: OkHttpClient,
        failedHostCache: ConcurrentHashMap<String, String>,
    ): Triple<Boolean, String, String> {
        val timeoutMs = options.timeoutSeconds.coerceIn(3L, 120L) * 1000L
        val targetUrl = resolveTargetUrl(source)
            ?: return Triple(false, "非 HTTP 源地址", "非HTTP源地址")
        val host = extractHost(targetUrl)
        if (host != null) {
            failedHostCache[host]?.let { cachedReason ->
                return Triple(false, cachedReason, if (cachedReason.contains("超时")) "校验超时" else "域名失效")
            }
        }
        val willCheckList = options.checkList && !source.singleUrl && !source.ruleArticles.isNullOrBlank()
        return try {
            withTimeout(timeoutMs) {
                // 若即将执行列表规则请求，可直接通过列表请求验证连通性，避免重复请求同一网址
                if (options.checkConnect && !willCheckList) {
                    val headers = runCatching { source.getHeaderMap(false) }.getOrDefault(emptyMap())
                    val code = probeHttpHeadOrRange(client, targetUrl, headers)
                    if (code !in 200..399) {
                        return@withTimeout Triple(false, "HTTP $code", "域名失效")
                    }
                }
                if (willCheckList) {
                    val sorts = runCatching { source.sortUrls() }.getOrDefault(emptyList())
                    val firstSort = sorts.firstOrNull { it.second.isNotBlank() }
                        ?: ("" to targetUrl)
                    val (articles, _) = Rss.getArticlesAwait(
                        firstSort.first,
                        firstSort.second,
                        source,
                        1,
                        null,
                    )
                    if (articles.isEmpty()) {
                        return@withTimeout Triple(false, "列表解析为空", "列表规则失效")
                    }
                    if (options.checkContent && !source.ruleContent.isNullOrBlank()) {
                        val firstArticle = articles.first()
                        val content = Rss.getContentAwait(firstArticle, source.ruleContent!!, source)
                        if (content.isBlank()) {
                            return@withTimeout Triple(false, "正文解析为空", "正文规则失效")
                        }
                    }
                }
                Triple(true, "校验通过", "通过")
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            val reason = "请求超时 (${options.timeoutSeconds}s)"
            if (host != null) failedHostCache.putIfAbsent(host, reason)
            Triple(false, reason, "校验超时")
        } catch (e: Throwable) {
            val reason = e.localizedMessage ?: e.javaClass.simpleName
            if (host != null && (e is UnknownHostException || e is ConnectException || e is SocketTimeoutException)) {
                failedHostCache.putIfAbsent(host, reason)
            }
            Triple(false, reason, "域名失效")
        }
    }
}
