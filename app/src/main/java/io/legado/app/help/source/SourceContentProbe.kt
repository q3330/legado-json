package io.legado.app.help.source

import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import okhttp3.Dispatcher
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.text.StringEscapeUtils
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class WebsiteTitleProbeResult(
    val reachable: Boolean,
    val title: String?,
)

internal data class SourceProbeResult(
    val ok: Boolean,
    val statusCode: Int,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val error: String? = null,
) {
    fun formatBrief(): String {
        if (!error.isNullOrBlank()) return "失败 ($error)"
        val statusText = if (statusCode > 0) "HTTP $statusCode" else "无响应"
        val ct = contentType?.substringBefore(';')?.trim().orEmpty()
        val size = contentLength?.takeIf { it > 0L }?.let { formatBytes(it) }.orEmpty()
        val parts = listOf(statusText, ct, size).filter(String::isNotBlank)
        return (if (ok) "可达" else "失效") + " (" + parts.joinToString(" · ") + ")"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

internal object SourceContentProbe {

    private val HTTP_URL_REGEX = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)

    private val probeClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val titleClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 16
        }
        okHttpClient.newBuilder()
            .dispatcher(dispatcher)
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .callTimeout(3500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun extractFirstHttpUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val match = HTTP_URL_REGEX.find(trimmed) ?: return null
        return match.value.trimEnd('"', '\'', ')', ']', '>', ',', ';')
    }

    fun countVisibleTextChars(htmlOrText: String): Int {
        var count = 0
        var inTag = false
        for (ch in htmlOrText) {
            when {
                ch == '<' -> inTag = true
                ch == '>' -> inTag = false
                !inTag && !ch.isWhitespace() -> count++
            }
        }
        return count
    }

    fun isDirectVideoStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".flv")
    }

    fun probeUrl(
        source: BookSource,
        rawUrl: String,
        referer: String?,
        allowHtml: Boolean,
    ): SourceProbeResult {
        val cleanUrl = rawUrl.trim().substringBefore(",{").trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return SourceProbeResult(ok = false, statusCode = 0, error = "非 http(s) 链接")
        }
        fun buildRequest(useHead: Boolean): Request {
            val builder = Request.Builder().url(cleanUrl)
            var hasReferer = false
            runCatching {
                source.getHeaderMap(false).forEach { (k, v) ->
                    if (k.equals("Referer", ignoreCase = true)) hasReferer = true
                    runCatching { builder.header(k, v) }
                }
            }
            if (!hasReferer && !referer.isNullOrBlank() &&
                (referer.startsWith("http://", ignoreCase = true) ||
                    referer.startsWith("https://", ignoreCase = true))
            ) {
                runCatching { builder.header("Referer", referer.substringBefore(",{")) }
            }
            if (useHead) {
                builder.head()
            } else {
                builder.get().header("Range", "bytes=0-1023")
            }
            return builder.build()
        }

        fun evaluate(code: Int, contentType: String?, contentLength: Long?, hasBody: Boolean): SourceProbeResult {
            val ctLower = contentType?.lowercase().orEmpty()
            val ok = (code in 200..299 || code == 304) &&
                (allowHtml || !ctLower.contains("text/html")) &&
                hasBody
            return SourceProbeResult(
                ok = ok,
                statusCode = code,
                contentType = contentType,
                contentLength = contentLength,
            )
        }

        val headResult = runCatching {
            probeClient.newCall(buildRequest(useHead = true)).execute().use { resp ->
                val ct = resp.header("Content-Type")
                val cl = resp.header("Content-Length")?.toLongOrNull()
                when {
                    resp.code in 200..299 && cl != 0L -> evaluate(resp.code, ct, cl, true)
                    resp.code == 405 || resp.code == 404 || resp.code == 403 || resp.code == 400 || resp.code == 501 || cl == 0L -> null
                    else -> evaluate(resp.code, ct, cl, false)
                }
            }
        }.getOrNull()
        if (headResult != null) return headResult

        return runCatching {
            probeClient.newCall(buildRequest(useHead = false)).execute().use { resp ->
                val ct = resp.header("Content-Type")
                val cl = resp.header("Content-Length")?.toLongOrNull()
                val hasBytes = resp.body?.byteStream()?.use { stream ->
                    stream.read() != -1
                } ?: false
                evaluate(resp.code, ct, cl, hasBytes)
            }
        }.getOrElse { e ->
            SourceProbeResult(ok = false, statusCode = 0, error = e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    private val TITLE_REGEX = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val META_CHARSET_REGEX = Regex("""charset\s*=\s*["']?([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val INVALID_TITLE_PATTERNS = listOf(
        "404", "403", "502", "503", "500",
        "not found", "access denied", "forbidden",
        "verify yourself", "just a moment", "attention required",
        "browser verification", "cloudflare", "ddos",
        "redirecting", "welcome", "nginx", "apache", "iis",
        "error", "页面不存在", "无法访问", "安全验证", "人机验证",
    )

    private fun buildHomepageRequest(source: BookSource): Request? {
        val cleanUrl = source.bookSourceUrl.trim().substringBefore('#').substringBefore(",{").trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val builder = Request.Builder().url(cleanUrl).get()
        runCatching {
            source.getHeaderMap(false).forEach { (k, v) ->
                runCatching { builder.header(k, v) }
            }
        }
        return builder.build()
    }

    private fun extractTitleFromResponse(resp: Response): String? {
        if (resp.code !in 200..299) return null
        val headerCharset = resp.body?.contentType()?.charset()
        val rawBytes = resp.body?.byteStream()?.use { input ->
            val out = ByteArrayOutputStream(4096)
            val buf = ByteArray(2048)
            var total = 0
            while (total < 16384) {
                val n = input.read(buf, 0, minOf(buf.size, 16384 - total))
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
                val probeAscii = String(out.toByteArray(), Charsets.ISO_8859_1)
                if (probeAscii.contains("</title>", ignoreCase = true) ||
                    probeAscii.contains("</head>", ignoreCase = true)
                ) {
                    break
                }
            }
            out.toByteArray()
        } ?: return null
        if (rawBytes.isEmpty()) return null
        val asciiProbe = String(rawBytes, Charsets.ISO_8859_1)
        val charset = headerCharset ?: META_CHARSET_REGEX.find(asciiProbe)
            ?.groupValues?.getOrNull(1)
            ?.let { name ->
                runCatching {
                    val normalized = if (name.equals("gb2312", true) || name.equals("gbk", true)) "GB18030" else name
                    Charset.forName(normalized)
                }.getOrNull()
            }
            ?: Charsets.UTF_8
        val html = String(rawBytes, charset)
        val rawTitle = TITLE_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        return cleanWebsiteTitle(rawTitle)
    }

    /**
     * 非阻塞异步探测主站可达性并流式提取网站名称（命中 </title> 即停，支持协程超时立即中断）。
     */
    suspend fun probeWebsiteTitleAwait(source: BookSource): WebsiteTitleProbeResult {
        val request = buildHomepageRequest(source) ?: return WebsiteTitleProbeResult(
            reachable = false,
            title = null,
        )
        return runCatching {
            titleClient.newCall(request).await().use { resp ->
                WebsiteTitleProbeResult(
                    reachable = true,
                    title = extractTitleFromResponse(resp),
                )
            }
        }.getOrElse {
            WebsiteTitleProbeResult(reachable = false, title = null)
        }
    }

    /**
     * 请求书源主站首页并提取干净的网站名称，用于校验或手动获取源名称。
     * 命中反爬页、错误页或无法提取时返回 null，避免误覆盖原名称。
     */
    fun fetchWebsiteTitle(source: BookSource): String? {
        val request = buildHomepageRequest(source) ?: return null
        return runCatching {
            titleClient.newCall(request).execute().use { resp ->
                extractTitleFromResponse(resp)
            }
        }.getOrNull()
    }

    fun fetchWebsiteTitle(url: String, headers: Map<String, String> = emptyMap()): String? {
        val cleanUrl = url.trim().substringBefore('#').substringBefore(",{").trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val builder = Request.Builder().url(cleanUrl).get()
        headers.forEach { (k, v) ->
            runCatching { builder.header(k, v) }
        }
        return runCatching {
            titleClient.newCall(builder.build()).execute().use { resp ->
                extractTitleFromResponse(resp)
            }
        }.getOrNull()
    }

    private val checkedPageTitles = ConcurrentHashMap<String, String>()

    fun clearCheckedPageTitles() {
        checkedPageTitles.clear()
    }

    fun recordCheckedPageTitle(sourceUrl: String, html: String?) {
        if (sourceUrl.isBlank() || html.isNullOrBlank()) return
        if (checkedPageTitles.containsKey(sourceUrl)) return
        val head = if (html.length > 16384) html.substring(0, 16384) else html
        val rawTitle = TITLE_REGEX.find(head)?.groupValues?.getOrNull(1) ?: return
        val cleaned = cleanWebsiteTitle(rawTitle, preferLastSegment = true) ?: return
        checkedPageTitles.putIfAbsent(sourceUrl, cleaned)
    }

    fun consumeCheckedPageTitle(sourceUrl: String): String? {
        return checkedPageTitles.remove(sourceUrl)
    }

    internal fun cleanWebsiteTitle(rawTitle: String, preferLastSegment: Boolean = false): String? {
        val decoded = StringEscapeUtils.unescapeHtml4(rawTitle)
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (decoded.length < 2) return null
        val lower = decoded.lowercase()
        if (INVALID_TITLE_PATTERNS.any { lower.contains(it) }) return null
        val segments = decoded
            .split(Regex("""\s*[-_|—–·,，]\s*"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
        // 搜索/发现页标题通常为 "搜索词/分类 - 网站名"，优先取末段；首页通常为 "网站名 - 简介"，优先取首段
        val primary = when {
            segments.isEmpty() -> decoded
            preferLastSegment && segments.size >= 2 -> {
                segments.lastOrNull { seg ->
                    val segLower = seg.lowercase()
                    INVALID_TITLE_PATTERNS.none { segLower.contains(it) }
                } ?: segments.first()
            }
            else -> segments.first()
        }
        val cleaned = primary.take(28).trim()
        if (cleaned.length < 2) return null
        val cleanedLower = cleaned.lowercase()
        if (INVALID_TITLE_PATTERNS.any { cleanedLower.contains(it) }) return null
        return cleaned
    }
}
