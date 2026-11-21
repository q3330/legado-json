package io.legado.app.model

import android.annotation.SuppressLint
import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isWebFile
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceContentProbe
import io.legado.app.help.source.sortUrls
import io.legado.app.model.rss.Rss
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.SSLException

object Debug {
    var callback: Callback? = null
    private var debugSource: String? = null
    private val tasks: CompositeCoroutine = CompositeCoroutine()
    val debugMessageMap = HashMap<String, String>()
    private val debugTimeMap = HashMap<String, Long>()
    var isChecking: Boolean = false

    @SuppressLint("ConstantLocale")
    private val debugTimeFormat = SimpleDateFormat("[mm:ss.SSS]", Locale.getDefault())
    private var startTime: Long = System.currentTimeMillis()

    @Synchronized
    fun log(
        sourceUrl: String?,
        msg: String = "",
        print: Boolean = true,
        isHtml: Boolean = false,
        showTime: Boolean = true,
        state: Int = 1
    ) {
        if (BuildConfig.DEBUG) {
            Log.d("sourceDebug", msg)
        }
        //调试信息始终要执行
        callback?.let {
            if ((debugSource != sourceUrl || !print)) return
            var printMsg = msg
            if (isHtml) {
                printMsg = HtmlFormatter.format(msg)
            }
            if (showTime) {
                val time = debugTimeFormat.format(Date(System.currentTimeMillis() - startTime))
                printMsg = "$time $printMsg"
            }
            it.printLog(state, printMsg)
        }
        if (isChecking && sourceUrl != null && (msg).length < 30) {
            var printMsg = msg
            if (isHtml) {
                printMsg = HtmlFormatter.format(msg)
            }
            if (showTime && debugTimeMap[sourceUrl] != null) {
                val time =
                    debugTimeFormat.format(Date(System.currentTimeMillis() - debugTimeMap[sourceUrl]!!))
                printMsg = printMsg.replace(AppPattern.debugMessageSymbolRegex, "")

                debugMessageMap[sourceUrl] = "$time $printMsg"
            }
        }
    }

    @Synchronized
    fun log(msg: String?) {
        log(debugSource, msg ?: "", true)
    }

    fun cancelDebug(destroy: Boolean = false) {
        tasks.clear()

        if (destroy) {
            debugSource = null
            callback = null
        }
    }

    fun isDebugging(sourceUrl: String): Boolean {
        return callback != null && debugSource == sourceUrl
    }

    fun startChecking(source: BookSource) {
        isChecking = true
        debugTimeMap[source.bookSourceUrl] = System.currentTimeMillis()
        debugMessageMap[source.bookSourceUrl] = "${debugTimeFormat.format(Date(0))} 开始校验"
    }

    fun finishChecking() {
        isChecking = false
    }

    fun getRespondTime(sourceUrl: String): Long {
        return debugTimeMap[sourceUrl] ?: CheckSource.timeout
    }

    fun updateFinalMessage(sourceUrl: String, state: String) {
        if (debugTimeMap[sourceUrl] != null && debugMessageMap[sourceUrl] != null) {
            val spendingTime = System.currentTimeMillis() - debugTimeMap[sourceUrl]!!
            debugTimeMap[sourceUrl] =
                if (state == "校验成功") spendingTime else CheckSource.timeout + spendingTime
            val printTime = debugTimeFormat.format(Date(spendingTime))
            debugMessageMap[sourceUrl] = "$printTime $state"
        }
    }

    suspend fun startDebug(scope: CoroutineScope, rssSource: RssSource) {
        cancelDebug()
        debugSource = rssSource.sourceUrl
        log(debugSource, "︾开始解析")
        val sort = rssSource.sortUrls().first()
        Rss.getArticles(scope, sort.first, sort.second, rssSource, 1)
            .onSuccess {
                if (it.first.isEmpty()) {
                    log(debugSource, "⇒列表页解析成功，为空")
                    log(debugSource, "︽解析完成", state = 1000)
                } else {
                    val ruleContent = rssSource.ruleContent
                    if (!rssSource.ruleArticles.isNullOrBlank() && rssSource.ruleDescription.isNullOrBlank()) {
                        log(debugSource, "︽列表页解析完成")
                        log(debugSource, showTime = false)
                        if (ruleContent.isNullOrEmpty()) {
                            log(debugSource, "⇒内容规则为空，默认获取整个网页", state = 1000)
                        } else {
                            rssContentDebug(scope, it.first[0], ruleContent, rssSource)
                        }
                    } else {
                        log(debugSource, "⇒存在描述规则，不解析内容页")
                        log(debugSource, "︽解析完成", state = 1000)
                    }
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
    }

    fun startDebug(scope: CoroutineScope, rssSource: RssSource, key: String) {
        cancelDebug()
        debugSource = rssSource.sourceUrl
        startTime = System.currentTimeMillis()
        when {
            key.contains("::") -> {
                val name = key.substringBefore("::")
                val url = key.substringAfter("::")
                log(debugSource, "⇒开始访问分类页:$url")
                log(debugSource, "︾开始解析分类页")
                sortDebug(scope, rssSource, name, url)
            }

            key.isAbsUrl() -> {
                val ruleContent = rssSource.ruleContent
                if (!rssSource.ruleArticles.isNullOrBlank() && rssSource.ruleDescription.isNullOrBlank()) {
                    if (ruleContent.isNullOrEmpty()) {
                        log(debugSource, "⇒内容规则为空，默认获取整个网页", state = 1000)
                    } else {
                        val rssArticle = RssArticle()
                        rssArticle.origin = rssSource.sourceUrl
                        rssArticle.link = key
                        log(debugSource, "⇒开始访问内容页:$key")
                        rssContentDebug(scope, rssArticle, ruleContent, rssSource)
                    }
                } else {
                    log(debugSource, "⇒存在描述规则，不解析内容页")
                    log(debugSource, "︽解析完成", state = 1000)
                }
            }

            else -> {
                val searchUrl = rssSource.searchUrl
                if (searchUrl.isNullOrEmpty()) {
                    log(debugSource, "⇒搜索URL为空", state = -1)
                    return
                }
                log(debugSource, "⇒开始搜索关键字:$key")
                log(debugSource, "︾开始解析搜索页")
                sortDebug(scope, rssSource, "搜索", searchUrl, key)
            }
        }
    }

    private fun sortDebug(scope: CoroutineScope, rssSource: RssSource, name: String, url: String, key: String? = null) {
        Rss.getArticles(scope, name, url, rssSource, 1, key)
            .onSuccess {
                if (it.first.isEmpty()) {
                    log(debugSource, "⇒列表页解析成功，为空")
                    log(debugSource, "︽解析完成", state = 1000)
                } else {
                    val ruleContent = rssSource.ruleContent
                    if (!rssSource.ruleArticles.isNullOrBlank() && rssSource.ruleDescription.isNullOrBlank()) {
                        log(debugSource, "︽列表页解析完成")
                        log(debugSource, showTime = false)
                        if (ruleContent.isNullOrEmpty()) {
                            log(debugSource, "⇒内容规则为空，默认获取整个网页", state = 1000)
                        } else {
                            rssContentDebug(scope, it.first[0], ruleContent, rssSource)
                        }
                    } else {
                        log(debugSource, "⇒存在描述规则，不解析内容页")
                        log(debugSource, "︽解析完成", state = 1000)
                    }
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
    }

    private fun rssContentDebug(
        scope: CoroutineScope,
        rssArticle: RssArticle,
        ruleContent: String,
        rssSource: RssSource
    ) {
        log(debugSource, "︾开始解析内容页")
        Rss.getContent(scope, rssArticle, ruleContent, rssSource)
            .onSuccess {
                log(debugSource, it)
                log(debugSource, "︽内容页解析完成", state = 1000)
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
    }

    fun startDebug(scope: CoroutineScope, bookSource: BookSource, key: String) {
        cancelDebug()
        debugSource = bookSource.bookSourceUrl
        startTime = System.currentTimeMillis()
        log(debugSource, "≡当前书源类型:${sourceTypeLabel(bookSource.bookSourceType)}")
        when {
            key.isAbsUrl() -> {
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                book.bookUrl = key
                log(debugSource, "⇒开始访问详情页:$key")
                infoDebug(scope, bookSource, book)
            }

            key.contains("::") -> {
                val url = key.substringAfter("::")
                log(debugSource, "⇒开始访问发现页:$url")
                exploreDebug(scope, bookSource, url)
            }

            key.startsWith("++") -> {
                val url = key.substring(2)
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                book.tocUrl = url
                log(debugSource, "⇒开始访目录页:$url")
                tocDebug(scope, bookSource, book)
            }

            key.startsWith("--") -> {
                val url = key.substring(2)
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                log(debugSource, "⇒开始访正文页:$url")
                val chapter = BookChapter()
                chapter.title = "调试"
                chapter.url = url
                contentDebug(scope, bookSource, book, chapter, null)
            }

            else -> {
                log(debugSource, "⇒开始搜索关键字:$key")
                searchDebug(scope, bookSource, key)
            }
        }
    }

    private fun exploreDebug(scope: CoroutineScope, bookSource: BookSource, url: String) {
        log(debugSource, "︾开始解析发现页")
        val explore = WebBook.exploreBook(scope, bookSource, url, 1)
            .onSuccess { exploreBooks ->
                if (exploreBooks.isNotEmpty()) {
                    log(debugSource, "︽发现页解析完成")
                    log(debugSource, showTime = false)
                    infoDebug(scope, bookSource, exploreBooks[0].toBook())
                } else {
                    log(debugSource, "︽未获取到书籍", state = -1)
                }
            }
            .onError {
                logError(debugSource, it)
            }
        tasks.add(explore)
    }

    private fun searchDebug(scope: CoroutineScope, bookSource: BookSource, key: String) {
        log(debugSource, "︾开始解析搜索页")
        val search = WebBook.searchBook(scope, bookSource, key, 1)
            .onSuccess { searchBooks ->
                if (searchBooks.isNotEmpty()) {
                    log(debugSource, "︽搜索页解析完成")
                    log(debugSource, showTime = false)
                    infoDebug(scope, bookSource, searchBooks[0].toBook())
                } else {
                    log(debugSource, "︽未获取到书籍", state = -1)
                }
            }
            .onError {
                logError(debugSource, it)
            }
        tasks.add(search)
    }

    private fun logError(sourceUrl: String?, throwable: Throwable) {
        diagnoseNetworkError(throwable)?.let { hint ->
            log(sourceUrl, "≡网络诊断:$hint")
        }
        log(sourceUrl, throwable.stackTraceStr, state = -1)
    }

    private fun diagnoseNetworkError(throwable: Throwable): String? {
        val root = generateSequence(throwable) { it.cause }.last()
        val stack = throwable.stackTraceStr
        return when {
            root is SocketException && root.message?.contains("Connection reset", ignoreCase = true) == true -> {
                if (stack.contains("connectTls") || stack.contains("doHandshake") || stack.contains("Conscrypt")) {
                    "TLS握手阶段连接被重置（目标域名可能被当前网络/运营商SNI阻断，请尝试开启代理/VPN或更换可用域名）"
                } else {
                    "连接被远端重置（服务器主动断开或中间网络中断，请检查网络或尝试代理）"
                }
            }
            root is SSLException -> "TLS/SSL安全连接失败（证书异常或握手被拦截）"
            root is UnknownHostException -> "域名无法解析（请检查网络、DNS或确认域名是否已失效）"
            root is SocketTimeoutException -> "网络请求超时（目标站点响应过慢或当前网络不可达）"
            root is ConnectException -> "无法连接到目标服务器（端口关闭、站点宕机或网络受限）"
            else -> null
        }
    }

    private fun infoDebug(scope: CoroutineScope, bookSource: BookSource, book: Book) {
        if (book.tocUrl.isNotBlank()) {
            log(debugSource, "≡已获取目录链接,跳过详情页")
            log(debugSource, showTime = false)
            tocDebug(scope, bookSource, book)
            return
        }
        log(debugSource, "︾开始解析详情页")
        val info = WebBook.getBookInfo(scope, bookSource, book)
            .onSuccess {
                log(debugSource, "︽详情页解析完成")
                log(debugSource, showTime = false)
                if (!book.isWebFile) {
                    tocDebug(scope, bookSource, book)
                } else {
                    log(debugSource, "≡文件类书源跳过解析目录")
                    fileDownloadDebug(scope, bookSource, book)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(info)
    }

    private fun tocDebug(scope: CoroutineScope, bookSource: BookSource, book: Book) {
        log(debugSource, "︾开始解析目录页")
        val chapterList = WebBook.getChapterList(scope, bookSource, book)
            .onSuccess { chapters ->
                log(debugSource, "︽目录页解析完成")
                log(debugSource, showTime = false)
                val toc = chapters.filter { !(it.isVolume && it.url.startsWith(it.title)) }
                if (toc.isEmpty()) {
                    log(debugSource, "≡没有正文章节")
                    return@onSuccess
                }
                val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
                contentDebug(scope, bookSource, book, toc.first(), nextChapterUrl)
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(chapterList)
    }

    private fun contentDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String?
    ) {
        log(debugSource, "︾开始解析正文页")
        val content = WebBook.getContent(
            scope = scope,
            bookSource = bookSource,
            book = book,
            bookChapter = bookChapter,
            nextChapterUrl = nextChapterUrl,
            needSave = false
        ).onSuccess { contentStr ->
            debugTypedContent(scope, bookSource, bookChapter, contentStr)
        }.onError {
            log(debugSource, it.stackTraceStr, state = -1)
        }
        tasks.add(content)
    }

    private fun fileDownloadDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
    ) {
        val task = Coroutine.async(scope, Dispatchers.IO) {
            val urls = book.downloadUrls
                .orEmpty()
                .mapNotNull(SourceContentProbe::extractFirstHttpUrl)
                .distinct()
            log(debugSource, "┌文件下载链接校验 (共 ${urls.size} 条)")
            if (urls.isEmpty()) {
                log(debugSource, "└⚠ 未提取到有效下载链接")
                log(debugSource, "︽文件类调试完成", state = -1)
                return@async
            }
            urls.take(3).forEachIndexed { idx, url ->
                log(debugSource, "├[${idx + 1}] $url")
            }
            val probe = SourceContentProbe.probeUrl(
                source = bookSource,
                rawUrl = urls.first(),
                referer = book.bookUrl,
                allowHtml = true,
            )
            log(debugSource, "└首条下载链接探活: ${probe.formatBrief()}")
            log(debugSource, "︽文件类调试完成", state = if (probe.ok) 1000 else -1)
        }
        tasks.add(task)
    }

    private fun debugTypedContent(
        scope: CoroutineScope,
        bookSource: BookSource,
        bookChapter: BookChapter,
        contentStr: String,
    ) {
        when (bookSource.bookSourceType) {
            BookSourceType.image -> {
                val task = Coroutine.async(scope, Dispatchers.IO) {
                    val images = BookHelp.flowImages(bookChapter, contentStr)
                        .distinctUntilChanged()
                        .toList()
                    log(debugSource, "┌图片正文校验 (共 ${images.size} 张)")
                    if (images.isEmpty()) {
                        log(debugSource, "└⚠ 正文中未解析到 <img> 图片链接")
                        log(debugSource, "︽图片正文调试完成", state = -1)
                        return@async
                    }
                    images.take(3).forEachIndexed { idx, url ->
                        log(debugSource, "├图片[${idx + 1}]: $url")
                    }
                    val samples = images.take(2)
                    var okCount = 0
                    samples.forEachIndexed { idx, url ->
                        val probe = SourceContentProbe.probeUrl(
                            source = bookSource,
                            rawUrl = url,
                            referer = bookChapter.url,
                            allowHtml = false,
                        )
                        if (probe.ok) okCount++
                        log(debugSource, "├探活[${idx + 1}/${samples.size}]: ${probe.formatBrief()}")
                    }
                    log(debugSource, "└图片抽样探活结果: $okCount/${samples.size} 可达")
                    log(debugSource, "︽正文页解析完成", state = if (okCount > 0) 1000 else -1)
                }
                tasks.add(task)
            }

            BookSourceType.audio -> {
                val task = Coroutine.async(scope, Dispatchers.IO) {
                    val audioUrl = SourceContentProbe.extractFirstHttpUrl(contentStr)
                    log(debugSource, "┌音频流校验")
                    if (audioUrl.isNullOrBlank()) {
                        log(debugSource, "└⚠ 未从正文解析到有效音频 URL")
                        log(debugSource, "︽音频正文调试完成", state = -1)
                        return@async
                    }
                    log(debugSource, "├音频链接: $audioUrl")
                    val probe = SourceContentProbe.probeUrl(
                        source = bookSource,
                        rawUrl = audioUrl,
                        referer = bookChapter.url,
                        allowHtml = false,
                    )
                    log(debugSource, "└音频流探活: ${probe.formatBrief()}")
                    log(debugSource, "︽正文页解析完成", state = if (probe.ok) 1000 else -1)
                }
                tasks.add(task)
            }

            BookSourceType.video -> {
                val task = Coroutine.async(scope, Dispatchers.IO) {
                    val videoUrl = SourceContentProbe.extractFirstHttpUrl(contentStr)
                    log(debugSource, "┌视频播放链接校验")
                    if (videoUrl.isNullOrBlank()) {
                        log(debugSource, "└⚠ 未从正文解析到有效视频链接")
                        log(debugSource, "︽视频正文调试完成", state = -1)
                        return@async
                    }
                    log(debugSource, "├视频链接: $videoUrl")
                    val isDirect = SourceContentProbe.isDirectVideoStreamUrl(videoUrl)
                    val probe = SourceContentProbe.probeUrl(
                        source = bookSource,
                        rawUrl = videoUrl,
                        referer = bookChapter.url,
                        allowHtml = !isDirect,
                    )
                    log(debugSource, "└视频链接探活: ${probe.formatBrief()}")
                    log(debugSource, "︽正文页解析完成", state = if (probe.ok) 1000 else -1)
                }
                tasks.add(task)
            }

            else -> {
                val plainLen = SourceContentProbe.countVisibleTextChars(contentStr)
                log(debugSource, "┌文本正文校验")
                if (plainLen < 50) {
                    log(debugSource, "└⚠ 有效字数仅 $plainLen 字，可能命中反爬验证页或规则提取不全")
                } else {
                    log(debugSource, "└有效字数: $plainLen 字")
                }
                log(debugSource, "︽正文页解析完成", state = 1000)
            }
        }
    }

    private fun sourceTypeLabel(type: Int): String = when (type) {
        BookSourceType.audio -> "音频类"
        BookSourceType.image -> "图片类"
        BookSourceType.file -> "文件类"
        BookSourceType.video -> "视频类"
        else -> "文本类"
    }

    interface Callback {
        fun printLog(state: Int, msg: String)
    }
}
