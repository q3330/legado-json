package io.legado.app.web.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.HttpCaptureHelper
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.mcp.McpModuleSupport.bool
import io.legado.app.web.mcp.McpModuleSupport.field
import io.legado.app.web.mcp.McpModuleSupport.int
import io.legado.app.web.mcp.McpModuleSupport.result
import io.legado.app.web.mcp.McpModuleSupport.string
import io.legado.app.web.mcp.McpModuleSupport.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI

/** Native MCP tools for fetching real HTML, sniffing WebView streams, and evaluating Legado rules. */
internal object BookSourceCreatorMcpTools {

    private val CHARSET_HEADER_REGEX = Regex("""charset=([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE)
    private val META_CHARSET_REGEX = Regex(
        """<meta[^>]+charset\s*=\s*["']?([A-Za-z0-9_.-]+)""",
        RegexOption.IGNORE_CASE
    )
    private val LAZY_IMAGE_ATTRS = listOf("data-original", "data-src", "data-lazy", "data-echo", "data-webp")

    fun tools(): List<Map<String, Any>> = listOf(
        tool(
            "book_source_fetch_html",
            "Fetch raw webpage HTML or API response via Legado AnalyzeUrl with automatic charset and lazy-image detection. Supports Legado URL options like ,{\"method\":\"POST\",\"body\":\"...\",\"charset\":\"gbk\"}.",
            mapOf(
                "url" to field("string", "Target URL or Legado URL rule"),
                "tag" to field("string", "Optional saved BookSource.bookSourceUrl to reuse headers/cookies/jsLib"),
                "source_override" to field("object", "Optional temporary BookSource JSON object to reuse headers/cookies/jsLib"),
                "use_web_view" to field("boolean", "Optional: allow or force WebView rendering for this fetch"),
                "js" to field("string", "Optional JavaScript executed in WebView after page load"),
                "source_regex" to field("string", "Optional WebView resource sniff regex"),
                "offset" to field("integer", "UTF-16 character offset into body, default 0"),
                "max_chars" to field("integer", "Maximum returned characters, 500..80000, default 20000"),
                "timeout_seconds" to field("integer", "Timeout in seconds, 1..120, default 30")
            ),
            "url"
        ),
        tool(
            "book_source_webview_capture",
            "Render a page in background WebView (mode='webview') to execute JS / sniff video or audio URLs via source_regex, or capture and replay sub-requests (mode='capture_requests') to discover hidden APIs and streams.",
            mapOf(
                "url" to field("string", "Target http/https URL"),
                "tag" to field("string", "Optional saved BookSource.bookSourceUrl"),
                "source_override" to field("object", "Optional temporary BookSource JSON object"),
                "mode" to mapOf(
                    "type" to "string",
                    "enum" to listOf("webview", "capture_requests"),
                    "description" to "Execution mode, default webview"
                ),
                "js" to field("string", "Optional JavaScript executed after page load"),
                "source_regex" to field("string", "Optional media/resource sniff regex for webview mode"),
                "override_url_regex" to field("string", "Optional redirect intercept regex for webview mode"),
                "include_regex" to field("string", "Optional request URL include regex for capture_requests mode"),
                "exclude_regex" to field("string", "Optional request URL exclude regex for capture_requests mode"),
                "wait_ms" to field("integer", "Extra wait time after page load in ms, 0..30000, default 2500"),
                "max_requests" to field("integer", "Max captured requests in capture_requests mode, 1..200, default 50"),
                "replay_response" to field("boolean", "Replay matched GET/HEAD requests in capture_requests mode, default true"),
                "replay_only_matched" to field("boolean", "Only replay matched requests, default true"),
                "offset" to field("integer", "UTF-16 character offset for webview body, default 0"),
                "max_chars" to field("integer", "Maximum returned body chars, 500..80000, default 20000"),
                "timeout_seconds" to field("integer", "Timeout in seconds, 1..120, default 35")
            ),
            "url"
        ),
        tool(
            "book_source_eval_rule",
            "Evaluate Legado AnalyzeRule expressions (Default CSS, XPath, JSONPath, ##regex##, @js:) in memory against provided HTML/JSON or a fetched URL. Supports list_rule + batch field_rules sampling.",
            mapOf(
                "html" to field("string", "Optional HTML or JSON source text; if omitted, url must be provided"),
                "url" to field("string", "Optional base URL (or URL to fetch when html is omitted)"),
                "tag" to field("string", "Optional saved BookSource.bookSourceUrl"),
                "source_override" to field("object", "Optional temporary BookSource JSON object"),
                "list_rule" to field("string", "Optional list selector (e.g. bookList or chapterList)"),
                "rule" to field("string", "Optional single rule string to evaluate"),
                "field_rules" to field("object", "Optional map of field name to rule string for batch evaluation"),
                "is_url" to field("boolean", "Evaluate single rule as URL rule (resolving relative links), default false"),
                "sample_limit" to field("integer", "Max list elements to sample when list_rule is set, 1..20, default 5"),
                "timeout_seconds" to field("integer", "Fetch timeout if url is fetched, 1..120, default 30")
            )
        )
    )

    fun call(name: String, args: JsonObject): Map<String, Any?>? {
        if (tools().none { it["name"] == name }) return null
        return try {
            validate(name, args)
            val data: Any = when (name) {
                "book_source_fetch_html" -> fetchHtml(args)
                "book_source_webview_capture" -> webViewCapture(args)
                "book_source_eval_rule" -> evalRule(args)
                else -> error("Unknown tool: $name")
            }
            result(name, data)
        } catch (e: Exception) {
            result(name, null, e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    private fun validate(name: String, args: JsonObject) {
        val definition = tools().single { it["name"] == name }
        @Suppress("UNCHECKED_CAST")
        val schema = definition["inputSchema"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val properties = schema["properties"] as Map<String, Map<String, Any>>
        require(args.keySet().all { it in properties }) { "Unknown argument for $name" }
        @Suppress("UNCHECKED_CAST")
        val required = schema["required"] as List<String>
        required.forEach { key -> require(args.has(key)) { "$key is required" } }
        args.entrySet().forEach { (key, value) ->
            val type = properties.getValue(key)["type"]
            val valid = when (type) {
                "object" -> value.isJsonObject
                "array" -> value.isJsonArray
                "string" -> value.isJsonPrimitive && value.asJsonPrimitive.isString
                "boolean" -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
                "integer" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                    runCatching { value.asBigDecimal.intValueExact() }.isSuccess
                else -> false
            }
            require(valid) { "$key must be $type" }
        }
        if (args.has("offset")) args.int("offset", 0)
        if (args.has("max_chars")) args.int("max_chars", 20_000, 500, 80_000)
        if (args.has("timeout_seconds")) args.int("timeout_seconds", 30, 1, 120)
        if (args.has("wait_ms")) args.int("wait_ms", 2_500, 0, 30_000)
        if (args.has("max_requests")) args.int("max_requests", 50, 1, 200)
        if (args.has("sample_limit")) args.int("sample_limit", 5, 1, 20)
        if (args.has("mode")) {
            val mode = args.string("mode", true)!!
            require(mode == "webview" || mode == "capture_requests") {
                "mode must be webview or capture_requests"
            }
        }
    }

    private fun fetchHtml(args: JsonObject): Map<String, Any?> {
        val rawUrl = args.string("url", true)!!.trim()
        val timeoutSeconds = args.int("timeout_seconds", 30, 1, 120)
        val offset = args.int("offset", 0)
        val maxChars = args.int("max_chars", 20_000, 500, 80_000)
        val explicitUseWebView = args.bool("use_web_view")
        val js = args.string("js")?.takeIf { it.isNotBlank() }
        val sourceRegex = args.string("source_regex")?.takeIf { it.isNotBlank() }
        val effectiveUrl = if (explicitUseWebView == true && !rawUrl.contains("webView", ignoreCase = true)) {
            appendWebViewOption(rawUrl)
        } else {
            rawUrl
        }
        val source = resolveBookSource(args, effectiveUrl)
        val response = runBlocking(Dispatchers.IO) {
            withTimeout(timeoutSeconds * 1000L) {
                AnalyzeUrl(
                    mUrl = effectiveUrl,
                    source = source,
                    callTimeout = timeoutSeconds * 1000L,
                    coroutineContext = coroutineContext
                ).getStrResponseAwait(
                    jsStr = js,
                    sourceRegex = sourceRegex,
                    useWebView = explicitUseWebView ?: true,
                    isTest = true
                )
            }
        }
        val body = response.body.orEmpty()
        val contentType = runCatching { response.raw.header("Content-Type") }.getOrNull().orEmpty()
        val detectedCharset = detectCharset(contentType, body)
        val lazyAttrs = detectLazyAttributes(body)
        val hasCloudflare = body.contains("_cf_chl_opt") ||
            body.contains("challenges.cloudflare.com") ||
            body.contains("Just a moment...", ignoreCase = true)
        val window = mcpTextWindow(body, offset, maxChars)
        return linkedMapOf<String, Any?>(
            "url" to rawUrl,
            "final_url" to response.url,
            "status_code" to response.code(),
            "message" to response.message(),
            "call_time_ms" to response.callTime,
            "content_type" to contentType,
            "detected_charset" to detectedCharset,
            "has_cloudflare_challenge" to hasCloudflare,
            "lazy_img_attributes" to lazyAttrs
        ).apply {
            putAll(window)
        }
    }

    private fun webViewCapture(args: JsonObject): Map<String, Any?> {
        val url = args.string("url", true)!!.trim()
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "url must start with http:// or https://"
        }
        val mode = args.string("mode") ?: "webview"
        val timeoutSeconds = args.int("timeout_seconds", 35, 1, 120)
        val waitMs = args.int("wait_ms", 2_500, 0, 30_000).toLong()
        val maxChars = args.int("max_chars", 20_000, 500, 80_000)
        val source = resolveBookSource(args, url)
        if (mode == "capture_requests") {
            val captured = runBlocking(Dispatchers.IO) {
                withTimeout(timeoutSeconds * 1000L) {
                    HttpCaptureHelper.capture(
                        HttpCaptureHelper.Config(
                            url = url,
                            source = source,
                            waitMs = waitMs.coerceAtLeast(500L),
                            timeoutMs = (timeoutSeconds * 1000L).coerceIn(10_000L, 90_000L),
                            includeRegex = args.string("include_regex")?.takeIf { it.isNotBlank() },
                            excludeRegex = args.string("exclude_regex")?.takeIf { it.isNotBlank() },
                            maxRequests = args.int("max_requests", 50, 1, 200),
                            maxBodyChars = maxChars,
                            replayResponse = args.bool("replay_response", true)!!,
                            replayOnlyMatched = args.bool("replay_only_matched", true)!!,
                            js = args.string("js")?.takeIf { it.isNotBlank() },
                            coroutineContext = coroutineContext
                        )
                    )
                }
            }
            val parsed = JsonParser.parseString(captured.toString()).asJsonObject
            require(parsed.get("ok")?.asBoolean == true) {
                parsed.get("error")?.asString ?: "WebView request capture failed"
            }
            return linkedMapOf<String, Any?>(
                "mode" to mode,
                "capture" to parsed
            )
        }

        val offset = args.int("offset", 0)
        val response = runBlocking(Dispatchers.IO) {
            withTimeout(timeoutSeconds * 1000L) {
                BackstageWebView(
                    url = url,
                    tag = source.getKey(),
                    source = source,
                    headerMap = source.getHeaderMap(true),
                    sourceRegex = args.string("source_regex")?.takeIf { it.isNotBlank() },
                    overrideUrlRegex = args.string("override_url_regex")?.takeIf { it.isNotBlank() },
                    javaScript = args.string("js")?.takeIf { it.isNotBlank() },
                    delayTime = waitMs,
                    timeout = timeoutSeconds * 1000L
                ).getStrResponse()
            }
        }
        val body = response.body.orEmpty()
        return linkedMapOf<String, Any?>(
            "mode" to mode,
            "url" to url,
            "final_url" to response.url,
            "status_code" to response.code(),
            "lazy_img_attributes" to detectLazyAttributes(body)
        ).apply {
            putAll(mcpTextWindow(body, offset, maxChars))
        }
    }

    private fun evalRule(args: JsonObject): Map<String, Any?> {
        val rawHtml = args.string("html")?.takeIf { it.isNotEmpty() }
        val urlArg = args.string("url")?.trim()?.takeIf { it.isNotBlank() }
        require(rawHtml != null || urlArg != null) { "Either html or url is required" }
        val listRule = args.string("list_rule")?.takeIf { it.isNotBlank() }
        val singleRule = args.string("rule")?.takeIf { it.isNotBlank() }
        val fieldRulesObj = args.get("field_rules")?.takeIf { it.isJsonObject }?.asJsonObject
        require(listRule != null || singleRule != null || (fieldRulesObj != null && fieldRulesObj.size() > 0)) {
            "At least one of list_rule, rule, or field_rules is required"
        }

        val source = resolveBookSource(args, urlArg ?: "https://example.com/")
        var effectiveUrl = urlArg ?: source.bookSourceUrl
        val htmlText = if (rawHtml != null) {
            rawHtml
        } else {
            val timeoutSeconds = args.int("timeout_seconds", 30, 1, 120)
            val response = runBlocking(Dispatchers.IO) {
                withTimeout(timeoutSeconds * 1000L) {
                    AnalyzeUrl(
                        mUrl = urlArg!!,
                        source = source,
                        callTimeout = timeoutSeconds * 1000L,
                        coroutineContext = coroutineContext
                    ).getStrResponseAwait(useWebView = true, isTest = true)
                }
            }
            effectiveUrl = response.url.ifBlank { urlArg!! }
            response.body.orEmpty()
        }

        val book = Book().apply {
            origin = source.bookSourceUrl
            originName = source.bookSourceName
        }
        val rootAnalyze = AnalyzeRule(ruleData = book, source = source).apply {
            if (effectiveUrl.startsWith("http://", true) || effectiveUrl.startsWith("https://", true)) {
                setRedirectUrl(effectiveUrl)
            }
            setContent(htmlText, effectiveUrl)
        }

        val isUrl = args.bool("is_url", false)!!
        val sampleLimit = args.int("sample_limit", 5, 1, 20)

        if (listRule != null) {
            val elements = rootAnalyze.getElements(listRule)
            val samples = elements.take(sampleLimit).mapIndexed { index, element ->
                val itemBook = Book().apply {
                    origin = source.bookSourceUrl
                    originName = source.bookSourceName
                }
                val itemRule = AnalyzeRule(ruleData = itemBook, source = source).apply {
                    if (effectiveUrl.startsWith("http://", true) || effectiveUrl.startsWith("https://", true)) {
                        setRedirectUrl(effectiveUrl)
                    }
                    setContent(element, effectiveUrl)
                }
                val fieldsResult = linkedMapOf<String, Any?>()
                fieldRulesObj?.entrySet()?.forEach { (fieldName, ruleElement) ->
                    val expr = ruleElement.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    if (expr.isNotBlank()) {
                        val fieldIsUrl = shouldTreatAsUrlField(fieldName)
                        fieldsResult[fieldName] = runCatching {
                            itemRule.getString(expr, isUrl = fieldIsUrl)
                        }.getOrElse { "ERROR: ${it.localizedMessage}" }
                    }
                }
                val ruleResult = singleRule?.let { expr ->
                    runCatching { itemRule.getString(expr, isUrl = isUrl) }
                        .getOrElse { "ERROR: ${it.localizedMessage}" }
                }
                linkedMapOf<String, Any?>(
                    "index" to index,
                    "fields" to fieldsResult.takeIf { it.isNotEmpty() },
                    "rule_value" to ruleResult
                ).filterValues { it != null }
            }
            return mapOf(
                "base_url" to effectiveUrl,
                "html_chars" to htmlText.length,
                "list_rule" to listRule,
                "element_count" to elements.size,
                "sample_count" to samples.size,
                "samples" to samples
            )
        }

        val fieldsResult = linkedMapOf<String, Any?>()
        fieldRulesObj?.entrySet()?.forEach { (fieldName, ruleElement) ->
            val expr = ruleElement.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (expr.isNotBlank()) {
                val fieldIsUrl = shouldTreatAsUrlField(fieldName)
                fieldsResult[fieldName] = runCatching {
                    rootAnalyze.getString(expr, isUrl = fieldIsUrl)
                }.getOrElse { "ERROR: ${it.localizedMessage}" }
            }
        }
        val singleString = singleRule?.let { expr ->
            runCatching { rootAnalyze.getString(expr, isUrl = isUrl) }
                .getOrElse { "ERROR: ${it.localizedMessage}" }
        }
        val singleList = singleRule?.let { expr ->
            runCatching { rootAnalyze.getStringList(expr, isUrl = isUrl).orEmpty().take(sampleLimit) }
                .getOrElse { listOf("ERROR: ${it.localizedMessage}") }
        }
        return linkedMapOf<String, Any?>(
            "base_url" to effectiveUrl,
            "html_chars" to htmlText.length,
            "fields" to fieldsResult.takeIf { it.isNotEmpty() },
            "rule_value" to singleString,
            "rule_list_sample" to singleList
        ).filterValues { it != null }
    }

    internal fun resolveBookSource(args: JsonObject, fallbackUrl: String? = null): BookSource {
        val tag = args.string("tag")?.trim()?.takeIf { it.isNotBlank() }
        val overrideObj = args.get("source_override")?.takeIf { it.isJsonObject }?.asJsonObject
        val overrideUrl = overrideObj?.get("bookSourceUrl")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotBlank() }
        val stored = tag?.let(appDb.bookSourceDao::getBookSource)
            ?: overrideUrl?.let(appDb.bookSourceDao::getBookSource)
        if (overrideObj != null) {
            val base = stored ?: temporarySourceFor(overrideUrl ?: tag ?: fallbackUrl ?: "https://example.com/")
            return mergeBookSource(base, overrideObj)
        }
        if (stored != null) return stored
        return temporarySourceFor(tag ?: fallbackUrl ?: "https://example.com/")
    }

    internal fun mergeBookSource(base: BookSource, overrideObj: JsonObject): BookSource {
        val baseJson = GSON.toJsonTree(base).asJsonObject
        mergeJsonObject(baseJson, overrideObj)
        val parsed = GSON.fromJsonObject<BookSource>(baseJson.toString()).getOrNull()
            ?: throw IllegalArgumentException("Invalid BookSource JSON in source_override")
        if (parsed.bookSourceUrl.isBlank()) parsed.bookSourceUrl = base.bookSourceUrl
        if (parsed.bookSourceName.isBlank()) parsed.bookSourceName = base.bookSourceName.ifBlank { parsed.bookSourceUrl }
        return parsed
    }

    internal fun mergeJsonObject(target: JsonObject, patch: JsonObject) {
        patch.entrySet().forEach { (key, value) ->
            if (value.isJsonNull) {
                target.add(key, value)
                return@forEach
            }
            val existing = target.get(key)
            if (existing != null && existing.isJsonObject && value.isJsonObject) {
                mergeJsonObject(existing.asJsonObject, value.asJsonObject)
            } else {
                target.add(key, value.deepCopy())
            }
        }
    }

    private fun temporarySourceFor(rawUrl: String): BookSource {
        val clean = rawUrl.trim().substringBefore(",{").trim()
        val origin = runCatching {
            val uri = URI(clean)
            if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                "${uri.scheme}://${uri.host}"
            } else {
                clean
            }
        }.getOrDefault(clean)
        val host = runCatching { URI(origin).host.orEmpty() }.getOrDefault("")
        return BookSource(
            bookSourceUrl = origin.ifBlank { "https://example.com/" },
            bookSourceName = host.ifBlank { origin.ifBlank { "TempSource" } }
        )
    }

    private fun appendWebViewOption(rawUrl: String): String {
        val idx = rawUrl.indexOf(",{")
        if (idx < 0) {
            return "$rawUrl,{\"webView\":true}"
        }
        val base = rawUrl.substring(0, idx)
        val jsonPart = rawUrl.substring(idx + 1)
        val obj = runCatching { JsonParser.parseString(jsonPart).asJsonObject }.getOrNull()
            ?: return "$rawUrl,{\"webView\":true}"
        obj.addProperty("webView", true)
        return "$base,${GSON.toJson(obj)}"
    }

    private fun detectCharset(contentType: String, body: String): String {
        CHARSET_HEADER_REGEX.find(contentType)?.groupValues?.getOrNull(1)?.let {
            return it.uppercase()
        }
        META_CHARSET_REGEX.find(body.take(4096))?.groupValues?.getOrNull(1)?.let {
            return it.uppercase()
        }
        return "UTF-8"
    }

    private fun detectLazyAttributes(body: String): List<String> {
        val sample = body.take(120_000)
        return LAZY_IMAGE_ATTRS.filter { attr ->
            sample.contains("$attr=", ignoreCase = true)
        }
    }

    private fun shouldTreatAsUrlField(fieldName: String): Boolean {
        val lower = fieldName.lowercase()
        return lower == "bookurl" ||
            lower == "coverurl" ||
            lower == "tocurl" ||
            lower == "chapterurl" ||
            lower == "nexttocurl" ||
            lower == "nextcontenturl" ||
            lower == "downloadurls" ||
            lower.endsWith("url")
    }
}
