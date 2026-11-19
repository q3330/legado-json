package io.legado.app.ui.association

import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.legado.app.data.entities.BookSource
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

private const val IMPORT_READ_BUFFER_CHARS = 128 * 1024
private const val IMPORT_STREAM_BUFFER_CHARS = 256 * 1024
private const val PARSE_BATCH_SIZE = 400

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
        for (i in 0 until count) {
            val c = charBuf[i]
            if (objDepth > 0) {
                itemBuf.append(c)
                if (lineComment) {
                    if (c == '\n' || c == '\r') lineComment = false
                } else if (blockComment) {
                    if (pendingStar && c == '/') {
                        blockComment = false
                        pendingStar = false
                    } else {
                        pendingStar = (c == '*')
                    }
                } else if (inString) {
                    if (escape) {
                        escape = false
                    } else if (c == '\\') {
                        escape = true
                    } else if (c == quoteChar) {
                        inString = false
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
                                batch.add(itemBuf.toString())
                                itemBuf.setLength(0)
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
                                batch.add(itemBuf.toString())
                                itemBuf.setLength(0)
                                if (batch.size >= PARSE_BATCH_SIZE) {
                                    dispatchBatch(batch)
                                    batch = ArrayList(PARSE_BATCH_SIZE)
                                }
                            }
                        }
                    }
                }
            } else {
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
                            itemBuf.setLength(0)
                            itemBuf.append('{')
                        }
                        else -> topLevelToken.append(c)
                    }
                }
            }
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
        val source = GSON.fromJson<BookSource>(jr, BookSource::class.java)
        if (jr.peek() != JsonToken.END_DOCUMENT) {
            throw JsonSyntaxException("书源 JSON 对象存在多余内容")
        }
        result.add(source)
    }
    return result
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
