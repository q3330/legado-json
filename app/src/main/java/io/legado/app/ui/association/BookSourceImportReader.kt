package io.legado.app.ui.association

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.BookListRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.model.jsSource.JsSourceUpsert
import io.legado.app.utils.GSON
import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

private const val IMPORT_READ_BUFFER_CHARS = 512 * 1024
private const val IMPORT_STREAM_BUFFER_CHARS = 512 * 1024
private const val PARSE_BATCH_SIZE = 500

/** 文件/网络/粘贴共用；数组合集按对象切片并行反序列化，控制并发批次数以限制峰值内存。 */
internal suspend fun readBookSourceImport(
    input: Reader,
    allowSourceUrls: Boolean,
    onSource: (BookSource) -> Unit,
    onSourceUrl: suspend (String) -> Unit,
): Int {
    val reader = BufferedReader(input, IMPORT_STREAM_BUFFER_CHARS)
    var first: Int
    do {
        currentCoroutineContext().ensureActive()
        reader.mark(1)
        first = reader.read()
    } while (first != -1 && (first.toChar().isWhitespace() || first == 0xfeff))
    if (first == -1) throw NoStackTraceException("书源内容为空")
    reader.reset()
    if (first != '['.code && first != '{'.code) {
        val script = readBoundedSourceScript(reader)
        JsSourceUpsert.validatePayload(script)?.let {
            throw NoStackTraceException(
                if (it == JsSourceUpsert.PayloadIssue.EMPTY) "JS 书源内容为空"
                else "JS 书源不能超过 1 MiB"
            )
        }
        onSource(withTimeout(30_000L) { JsSourceConfig.extract(script, currentCoroutineContext()) })
        return 0
    }
    if (first == '['.code) {
        return readBookSourceArrayParallel(reader, onSource)
    }
    val json = JsonReader(reader).apply { strictness = Strictness.LENIENT }
    fun emit(source: BookSource?) {
        if (source == null) throw JsonSyntaxException("书源列表不能存在 null 元素")
        if (source.bookSourceUrl.isEmpty()) throw NoStackTraceException("不是书源")
        onSource(source)
    }
    if (allowSourceUrls) {
        // 此处最多一条源或一个链接包装对象，不构建整个书源数组的 JSON 树。
        val root = GSON.fromJson<JsonObject>(json, JsonObject::class.java)
        val urls = root.get("sourceUrls")
        if (urls?.isJsonArray == true) {
            for (url in urls.asJsonArray) {
                currentCoroutineContext().ensureActive()
                onSourceUrl(url.asString)
            }
        } else {
            emit(GSON.fromJson(root, BookSource::class.java))
        }
    } else {
        emit(GSON.fromJson<BookSource>(json, BookSource::class.java))
    }
    if (json.peek() != JsonToken.END_DOCUMENT) throw JsonSyntaxException("书源 JSON 尾部存在多余内容")
    return 0
}

private suspend fun readBookSourceArrayParallel(
    reader: Reader,
    onSource: (BookSource) -> Unit,
): Int = coroutineScope {
    val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    val charBuf = CharArray(IMPORT_READ_BUFFER_CHARS)
    val itemBuf = StringBuilder(2048)
    val topLevelToken = StringBuilder()
    var batch = ArrayList<String>(PARSE_BATCH_SIZE)
    val tasks = ArrayDeque<Deferred<List<BookSource?>>>(workers)

    var arrayStarted = false
    var arrayEnded = false
    var objDepth = 0
    var inString = false
    var quoteChar = '"'
    var escape = false
    var pendingSlash = false
    var lineComment = false
    var blockComment = false
    var pendingStar = false
    var spanStart = -1

    var firstBatchEmitted = false
    var accepted = 0
    var skipped = 0

    fun handleParsed(parsed: List<BookSource?>) {
        for (source in parsed) {
            if (source == null) throw JsonSyntaxException("书源列表不能存在 null 元素")
            if (source.bookSourceUrl.isEmpty()) {
                skipped++
            } else {
                onSource(source)
                accepted++
            }
        }
    }

    suspend fun dispatchBatch(items: List<String>) {
        if (items.isEmpty()) return
        if (!firstBatchEmitted && items.size <= 16) {
            firstBatchEmitted = true
            handleParsed(parseBookSourceSliceBatch(items))
            return
        }
        firstBatchEmitted = true
        val deferred = async(Dispatchers.Default) { parseBookSourceSliceBatch(items) }
        tasks.addLast(deferred)
        if (tasks.size >= workers) {
            handleParsed(tasks.removeFirst().await())
        }
    }

    fun throwInvalidTopLevelToken(): Nothing {
        if (topLevelToken.toString() == "null") {
            throw JsonSyntaxException("书源列表不能存在 null 元素")
        }
        throw JsonSyntaxException("书源 JSON 数组元素格式错误")
    }

    while (true) {
        ensureActive()
        val count = reader.read(charBuf)
        if (count == -1) break
        if (objDepth > 0) spanStart = 0
        var i = 0
        while (i < count) {
            if (objDepth > 0) {
                if (inString) {
                    while (i < count) {
                        val c = charBuf[i++]
                        if (escape) {
                            escape = false
                        } else if (c == '\\') {
                            escape = true
                        } else if (c == quoteChar) {
                            inString = false
                            break
                        }
                    }
                    continue
                }
                val c = charBuf[i]
                if (lineComment) {
                    if (c == '\n' || c == '\r') lineComment = false
                } else if (blockComment) {
                    if (pendingStar && c == '/') {
                        blockComment = false
                        pendingStar = false
                    } else {
                        pendingStar = (c == '*')
                    }
                } else if (pendingSlash) {
                    pendingSlash = false
                    when (c) {
                        '/' -> lineComment = true
                        '*' -> {
                            blockComment = true
                            pendingStar = false
                        }
                        '"', '\'' -> {
                            inString = true
                            quoteChar = c
                        }
                        '{' -> objDepth++
                        '}' -> {
                            objDepth--
                            if (objDepth == 0) {
                                val raw = if (itemBuf.isEmpty()) {
                                    String(charBuf, spanStart, i - spanStart + 1)
                                } else {
                                    itemBuf.append(charBuf, spanStart, i - spanStart + 1)
                                    val built = itemBuf.toString()
                                    itemBuf.setLength(0)
                                    built
                                }
                                spanStart = -1
                                batch.add(raw)
                                if (batch.size >= PARSE_BATCH_SIZE) {
                                    dispatchBatch(batch)
                                    batch = ArrayList(PARSE_BATCH_SIZE)
                                }
                            }
                        }
                    }
                } else {
                    when (c) {
                        '"', '\'' -> {
                            inString = true
                            quoteChar = c
                        }
                        '/' -> pendingSlash = true
                        '{' -> objDepth++
                        '}' -> {
                            objDepth--
                            if (objDepth == 0) {
                                val raw = if (itemBuf.isEmpty()) {
                                    String(charBuf, spanStart, i - spanStart + 1)
                                } else {
                                    itemBuf.append(charBuf, spanStart, i - spanStart + 1)
                                    val built = itemBuf.toString()
                                    itemBuf.setLength(0)
                                    built
                                }
                                spanStart = -1
                                batch.add(raw)
                                if (batch.size >= PARSE_BATCH_SIZE) {
                                    dispatchBatch(batch)
                                    batch = ArrayList(PARSE_BATCH_SIZE)
                                }
                            }
                        }
                    }
                }
                i++
            } else {
                val c = charBuf[i++]
                if (lineComment) {
                    if (c == '\n' || c == '\r') lineComment = false
                    continue
                }
                if (blockComment) {
                    if (pendingStar && c == '/') {
                        blockComment = false
                        pendingStar = false
                    } else {
                        pendingStar = (c == '*')
                    }
                    continue
                }
                if (pendingSlash) {
                    pendingSlash = false
                    if (c == '/') {
                        lineComment = true
                        continue
                    } else if (c == '*') {
                        blockComment = true
                        pendingStar = false
                        continue
                    } else if (arrayEnded) {
                        throw JsonSyntaxException("书源 JSON 尾部存在多余内容")
                    } else {
                        throw JsonSyntaxException("书源 JSON 数组格式错误")
                    }
                }
                if (arrayEnded) {
                    when {
                        c.isWhitespace() -> Unit
                        c == '/' -> pendingSlash = true
                        else -> throw JsonSyntaxException("书源 JSON 尾部存在多余内容")
                    }
                } else if (!arrayStarted) {
                    when {
                        c.isWhitespace() -> Unit
                        c == '/' -> pendingSlash = true
                        c == '[' -> arrayStarted = true
                        else -> throw JsonSyntaxException("书源 JSON 数组格式错误")
                    }
                } else if (topLevelToken.isNotEmpty()) {
                    if (c == ',' || c == ']' || c.isWhitespace()) {
                        throwInvalidTopLevelToken()
                    } else {
                        topLevelToken.append(c)
                        if (topLevelToken.length > 16) throwInvalidTopLevelToken()
                    }
                } else {
                    when {
                        c.isWhitespace() || c == ',' -> Unit
                        c == '/' -> pendingSlash = true
                        c == ']' -> arrayEnded = true
                        c == '{' -> {
                            objDepth = 1
                            spanStart = i - 1
                        }
                        else -> topLevelToken.append(c)
                    }
                }
            }
        }
        if (objDepth > 0 && spanStart in 0 until count) {
            itemBuf.append(charBuf, spanStart, count - spanStart)
            spanStart = -1
        }
        // 首块小数据就地回调，避免多消费下一块导致流式测试提前读至 EOF
        if (!firstBatchEmitted && batch.isNotEmpty()) {
            dispatchBatch(batch)
            batch = ArrayList(PARSE_BATCH_SIZE)
        }
    }

    if (pendingSlash) {
        if (arrayEnded) throw JsonSyntaxException("书源 JSON 尾部存在多余内容")
        throw JsonSyntaxException("书源 JSON 数组格式错误")
    }
    if (topLevelToken.isNotEmpty()) throwInvalidTopLevelToken()
    if (!arrayEnded || objDepth != 0) throw JsonSyntaxException("书源 JSON 数组未正常结束")

    if (batch.isNotEmpty()) {
        dispatchBatch(batch)
    }
    while (tasks.isNotEmpty()) {
        ensureActive()
        handleParsed(tasks.removeFirst().await())
    }
    if (accepted == 0 && skipped > 0) throw NoStackTraceException("不是书源：所有记录的书源地址均为空")
    skipped
}

private fun parseBookSourceSliceBatch(items: List<String>): List<BookSource?> {
    val result = ArrayList<BookSource?>(items.size)
    for (raw in items) {
        val jr = JsonReader(StringReader(raw)).apply { strictness = Strictness.LENIENT }
        val source = readBookSourceFast(jr)
        if (jr.peek() != JsonToken.END_DOCUMENT) {
            throw JsonSyntaxException("书源 JSON 对象存在多余内容")
        }
        result.add(source)
    }
    return result
}

private fun readBookSourceFast(jr: JsonReader): BookSource? {
    if (jr.peek() == JsonToken.NULL) {
        jr.nextNull()
        return null
    }
    if (jr.peek() != JsonToken.BEGIN_OBJECT) {
        throw JsonSyntaxException("Expected BEGIN_OBJECT but was ${jr.peek()}")
    }
    val source = BookSource()
    jr.beginObject()
    while (jr.hasNext()) {
        when (jr.nextName()) {
            "bookSourceUrl" -> readNullableString(jr)?.let { source.bookSourceUrl = it }
            "bookSourceName" -> readNullableString(jr)?.let { source.bookSourceName = it }
            "bookSourceGroup" -> source.bookSourceGroup = readNullableString(jr)
            "bookSourceType" -> source.bookSourceType = readIntOrDefault(jr, source.bookSourceType)
            "bookUrlPattern" -> source.bookUrlPattern = readNullableString(jr)
            "customOrder" -> source.customOrder = readIntOrDefault(jr, source.customOrder)
            "enabled" -> source.enabled = readBooleanOrDefault(jr, source.enabled)
            "enabledExplore" -> source.enabledExplore = readBooleanOrDefault(jr, source.enabledExplore)
            "jsLib" -> source.jsLib = readNullableString(jr)
            "enabledCookieJar" -> source.enabledCookieJar = readNullableBoolean(jr)
            "concurrentRate" -> source.concurrentRate = readNullableString(jr)
            "header" -> source.header = readNullableString(jr)
            "loginUrl" -> source.loginUrl = readNullableString(jr)
            "loginUi" -> source.loginUi = readNullableString(jr)
            "loginCheckJs" -> source.loginCheckJs = readNullableString(jr)
            "coverDecodeJs" -> source.coverDecodeJs = readNullableString(jr)
            "bookSourceComment" -> source.bookSourceComment = readNullableString(jr)
            "variableComment" -> source.variableComment = readNullableString(jr)
            "lastUpdateTime" -> source.lastUpdateTime = readLongOrDefault(jr, source.lastUpdateTime)
            "respondTime" -> source.respondTime = readLongOrDefault(jr, source.respondTime)
            "weight" -> source.weight = readIntOrDefault(jr, source.weight)
            "exploreUrl" -> source.exploreUrl = readNullableString(jr)
            "exploreScreen" -> source.exploreScreen = readNullableString(jr)
            "ruleExplore" -> source.ruleExplore = readRuleObjectOrString(jr, ::readExploreRuleFast)
            "searchUrl" -> source.searchUrl = readNullableString(jr)
            "ruleSearch" -> source.ruleSearch = readRuleObjectOrString(jr, ::readSearchRuleFast)
            "ruleBookInfo" -> source.ruleBookInfo = readRuleObjectOrString(jr, ::readBookInfoRuleFast)
            "ruleToc" -> source.ruleToc = readRuleObjectOrString(jr, ::readTocRuleFast)
            "ruleContent" -> source.ruleContent = readRuleObjectOrString(jr, ::readContentRuleFast)
            "ruleReview" -> source.ruleReview = readRuleObjectOrString(jr, ::readReviewRuleFast)
            "mainJs" -> source.mainJs = readNullableString(jr)
            "eventListener" -> source.eventListener = readBooleanOrDefault(jr, source.eventListener)
            "customButton" -> source.customButton = readBooleanOrDefault(jr, source.customButton)
            else -> jr.skipValue()
        }
    }
    jr.endObject()
    return source
}

private inline fun <T> readRuleObjectOrString(
    jr: JsonReader,
    crossinline parseFields: (JsonReader) -> T,
): T? {
    return when (jr.peek()) {
        JsonToken.BEGIN_OBJECT -> parseFields(jr)
        JsonToken.STRING -> {
            val raw = jr.nextString()
            if (raw.isBlank()) {
                null
            } else {
                val inner = JsonReader(StringReader(raw)).apply { strictness = Strictness.LENIENT }
                when (inner.peek()) {
                    JsonToken.BEGIN_OBJECT -> parseFields(inner)
                    else -> null
                }
            }
        }
        JsonToken.NULL -> {
            jr.nextNull()
            null
        }
        else -> {
            jr.skipValue()
            null
        }
    }
}

private fun readBookListRuleField(jr: JsonReader, name: String, target: BookListRule): Boolean {
    when (name) {
        "bookList" -> target.bookList = readNullableString(jr)
        "name" -> target.name = readNullableString(jr)
        "author" -> target.author = readNullableString(jr)
        "intro" -> target.intro = readNullableString(jr)
        "kind" -> target.kind = readNullableString(jr)
        "lastChapter" -> target.lastChapter = readNullableString(jr)
        "updateTime" -> target.updateTime = readNullableString(jr)
        "bookUrl" -> target.bookUrl = readNullableString(jr)
        "coverUrl" -> target.coverUrl = readNullableString(jr)
        "wordCount" -> target.wordCount = readNullableString(jr)
        else -> return false
    }
    return true
}

private fun readExploreRuleFast(jr: JsonReader): ExploreRule {
    val rule = ExploreRule()
    jr.beginObject()
    while (jr.hasNext()) {
        val name = jr.nextName()
        if (!readBookListRuleField(jr, name, rule)) jr.skipValue()
    }
    jr.endObject()
    return rule
}

private fun readSearchRuleFast(jr: JsonReader): SearchRule {
    val rule = SearchRule()
    jr.beginObject()
    while (jr.hasNext()) {
        val name = jr.nextName()
        if (name == "checkKeyWord") {
            rule.checkKeyWord = readNullableString(jr)
        } else if (!readBookListRuleField(jr, name, rule)) {
            jr.skipValue()
        }
    }
    jr.endObject()
    return rule
}

private fun readBookInfoRuleFast(jr: JsonReader): BookInfoRule {
    val rule = BookInfoRule()
    jr.beginObject()
    while (jr.hasNext()) {
        when (jr.nextName()) {
            "init" -> rule.init = readNullableString(jr)
            "name" -> rule.name = readNullableString(jr)
            "author" -> rule.author = readNullableString(jr)
            "intro" -> rule.intro = readNullableString(jr)
            "kind" -> rule.kind = readNullableString(jr)
            "lastChapter" -> rule.lastChapter = readNullableString(jr)
            "updateTime" -> rule.updateTime = readNullableString(jr)
            "coverUrl" -> rule.coverUrl = readNullableString(jr)
            "tocUrl" -> rule.tocUrl = readNullableString(jr)
            "wordCount" -> rule.wordCount = readNullableString(jr)
            "canReName" -> rule.canReName = readNullableString(jr)
            "downloadUrls" -> rule.downloadUrls = readNullableString(jr)
            else -> jr.skipValue()
        }
    }
    jr.endObject()
    return rule
}

private fun readTocRuleFast(jr: JsonReader): TocRule {
    val rule = TocRule()
    jr.beginObject()
    while (jr.hasNext()) {
        when (jr.nextName()) {
            "preUpdateJs" -> rule.preUpdateJs = readNullableString(jr)
            "chapterList" -> rule.chapterList = readNullableString(jr)
            "chapterName" -> rule.chapterName = readNullableString(jr)
            "chapterUrl" -> rule.chapterUrl = readNullableString(jr)
            "formatJs" -> rule.formatJs = readNullableString(jr)
            "isVolume" -> rule.isVolume = readNullableString(jr)
            "isVip" -> rule.isVip = readNullableString(jr)
            "isPay" -> rule.isPay = readNullableString(jr)
            "updateTime" -> rule.updateTime = readNullableString(jr)
            "nextTocUrl" -> rule.nextTocUrl = readNullableString(jr)
            else -> jr.skipValue()
        }
    }
    jr.endObject()
    return rule
}

private fun readContentRuleFast(jr: JsonReader): ContentRule {
    val rule = ContentRule()
    jr.beginObject()
    while (jr.hasNext()) {
        when (jr.nextName()) {
            "content" -> rule.content = readNullableString(jr)
            "subContent" -> rule.subContent = readNullableString(jr)
            "title" -> rule.title = readNullableString(jr)
            "nextContentUrl" -> rule.nextContentUrl = readNullableString(jr)
            "webJs" -> rule.webJs = readNullableString(jr)
            "sourceRegex" -> rule.sourceRegex = readNullableString(jr)
            "replaceRegex" -> rule.replaceRegex = readNullableString(jr)
            "imageStyle" -> rule.imageStyle = readNullableString(jr)
            "imageDecode" -> rule.imageDecode = readNullableString(jr)
            "payAction" -> rule.payAction = readNullableString(jr)
            "callBackJs" -> rule.callBackJs = readNullableString(jr)
            else -> jr.skipValue()
        }
    }
    jr.endObject()
    return rule
}

private fun readReviewRuleFast(jr: JsonReader): ReviewRule {
    val rule = ReviewRule()
    jr.beginObject()
    while (jr.hasNext()) {
        when (jr.nextName()) {
            "reviewUrl" -> rule.reviewUrl = readNullableString(jr)
            "avatarRule" -> rule.avatarRule = readNullableString(jr)
            "contentRule" -> rule.contentRule = readNullableString(jr)
            "postTimeRule" -> rule.postTimeRule = readNullableString(jr)
            "reviewQuoteUrl" -> rule.reviewQuoteUrl = readNullableString(jr)
            "voteUpUrl" -> rule.voteUpUrl = readNullableString(jr)
            "voteDownUrl" -> rule.voteDownUrl = readNullableString(jr)
            "postReviewUrl" -> rule.postReviewUrl = readNullableString(jr)
            "postQuoteUrl" -> rule.postQuoteUrl = readNullableString(jr)
            "deleteUrl" -> rule.deleteUrl = readNullableString(jr)
            else -> jr.skipValue()
        }
    }
    jr.endObject()
    return rule
}

private fun readNullableString(jr: JsonReader): String? {
    return when (jr.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> jr.nextString()
        JsonToken.BOOLEAN -> jr.nextBoolean().toString()
        JsonToken.NULL -> {
            jr.nextNull()
            null
        }
        else -> JsonParser.parseReader(jr).toString()
    }
}

private fun readIntOrDefault(jr: JsonReader, defaultValue: Int): Int {
    return when (jr.peek()) {
        JsonToken.NUMBER -> {
            val raw = jr.nextString()
            raw.toIntOrNull() ?: runCatching { raw.toBigDecimal().toInt() }.getOrDefault(defaultValue)
        }
        JsonToken.NULL -> {
            jr.nextNull()
            defaultValue
        }
        else -> {
            jr.skipValue()
            defaultValue
        }
    }
}

private fun readLongOrDefault(jr: JsonReader, defaultValue: Long): Long {
    return when (jr.peek()) {
        JsonToken.NUMBER -> {
            val raw = jr.nextString()
            raw.toLongOrNull() ?: runCatching { raw.toBigDecimal().toLong() }.getOrDefault(defaultValue)
        }
        JsonToken.NULL -> {
            jr.nextNull()
            defaultValue
        }
        else -> {
            jr.skipValue()
            defaultValue
        }
    }
}

private fun readBooleanOrDefault(jr: JsonReader, defaultValue: Boolean): Boolean {
    return when (jr.peek()) {
        JsonToken.BOOLEAN -> jr.nextBoolean()
        JsonToken.STRING -> jr.nextString().toBoolean()
        JsonToken.NULL -> {
            jr.nextNull()
            defaultValue
        }
        else -> throw JsonSyntaxException("Expected boolean but was ${jr.peek()}")
    }
}

private fun readNullableBoolean(jr: JsonReader): Boolean? {
    return when (jr.peek()) {
        JsonToken.BOOLEAN -> jr.nextBoolean()
        JsonToken.STRING -> jr.nextString().toBoolean()
        JsonToken.NULL -> {
            jr.nextNull()
            null
        }
        else -> throw JsonSyntaxException("Expected boolean but was ${jr.peek()}")
    }
}

/** 保留原先 trim 后的 JS 大小规则；超长尾部空白不占据无限缓冲。 */
private suspend fun readBoundedSourceScript(reader: Reader): String {
    val limit = JsSourceUpsert.MAX_SOURCE_BYTES
    val text = StringBuilder()
    val buffer = CharArray(8192)
    var position = 0L
    var end = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = reader.read(buffer)
        if (count == -1) break
        for (index in 0 until count) {
            val char = buffer[index]
            if (position < limit) text.append(char)
            if (!char.isWhitespace()) {
                if (position >= limit) throw NoStackTraceException("JS 书源不能超过 1 MiB")
                end = text.length
            }
            position++
        }
    }
    return text.substring(0, end)
}

/** 文件分流只需要第一项的字段名；skipValue 不保留规则/脚本字符串或整个数组。 */
internal fun firstImportObjectKeys(input: Reader): Set<String> {
    val json = JsonReader(input).apply { strictness = Strictness.LENIENT }
    if (json.peek() == JsonToken.BEGIN_ARRAY) {
        json.beginArray()
        if (!json.hasNext()) return emptySet()
    }
    if (json.peek() != JsonToken.BEGIN_OBJECT) return emptySet()
    val keys = linkedSetOf<String>()
    json.beginObject()
    while (json.hasNext()) {
        keys.add(json.nextName())
        json.skipValue()
    }
    json.endObject()
    return keys
}
