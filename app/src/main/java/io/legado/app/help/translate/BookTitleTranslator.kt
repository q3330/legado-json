package io.legado.app.help.translate

import androidx.core.text.HtmlCompat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import splitties.init.appCtx
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object BookTitleTranslator {

    private const val MAX_CACHE_ENTRIES = 8000
    private const val BING_TOKEN_TTL_MS = 8 * 60 * 1000L
    private const val BATCH_DELIMITER = "\n§§§\n"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val persistScope = CoroutineScope(SupervisorJob() + IO)
    private val cacheFile by lazy { File(appCtx.filesDir, "translate_cache.json") }
    private val cache = ConcurrentHashMap<String, String>()
    private val cacheLoaded = AtomicBoolean(false)
    private val persistScheduled = AtomicBoolean(false)

    private val bingTokenMutex = Mutex()
    @Volatile
    private var bingToken: String? = null
    @Volatile
    private var bingTokenAt: Long = 0L

    private fun ensureCacheLoaded() {
        if (!cacheLoaded.compareAndSet(false, true)) return
        runCatching {
            if (!cacheFile.exists()) return
            val raw = cacheFile.readText()
            if (raw.isBlank()) return
            val type = object : TypeToken<Map<String, String>>() {}.type
            val loaded: Map<String, String>? = GSON.fromJson(raw, type)
            loaded?.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) cache[k] = v
            }
        }
    }

    private fun schedulePersist() {
        if (!persistScheduled.compareAndSet(false, true)) return
        persistScope.launch {
            delay(1500)
            persistScheduled.set(false)
            runCatching {
                val snapshot = LinkedHashMap<String, String>()
                val entries = cache.entries.toList()
                val trimmed = if (entries.size > MAX_CACHE_ENTRIES) {
                    entries.takeLast(MAX_CACHE_ENTRIES)
                } else {
                    entries
                }
                trimmed.forEach { snapshot[it.key] = it.value }
                cacheFile.writeText(GSON.toJson(snapshot))
            }
        }
    }

    private fun cacheKey(text: String, targetLang: String): String = "$targetLang\u0000$text"

    fun cacheSize(): Int {
        ensureCacheLoaded()
        return cache.size
    }

    fun clearCache() {
        cache.clear()
        cacheLoaded.set(true)
        persistScope.launch {
            runCatching { if (cacheFile.exists()) cacheFile.delete() }
        }
    }

    fun getCached(text: String?, targetLang: String = TranslateConfig.targetLang): String? {
        val normalized = text?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        ensureCacheLoaded()
        return cache[cacheKey(normalized, targetLang)]?.takeIf { it.isNotBlank() && it != normalized }
            ?: TranslateHistoryStore.findOfflineTitle(normalized, targetLang)
    }

    fun needsTranslation(text: String?, targetLang: String = TranslateConfig.targetLang): Boolean {
        val s = text?.trim().orEmpty()
        if (s.length < 2) return false
        var cjkCount = 0
        var kanaOrHangulCount = 0
        var latinOrOtherLetterCount = 0
        for (ch in s) {
            val block = Character.UnicodeBlock.of(ch)
            when {
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS -> cjkCount++
                block == Character.UnicodeBlock.HIRAGANA ||
                    block == Character.UnicodeBlock.KATAKANA ||
                    block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                    block == Character.UnicodeBlock.HANGUL_JAMO -> kanaOrHangulCount++
                ch.isLetter() -> latinOrOtherLetterCount++
            }
        }
        val totalLetters = cjkCount + kanaOrHangulCount + latinOrOtherLetterCount
        if (totalLetters == 0) return false
        return if (targetLang.startsWith("zh", ignoreCase = true)) {
            if (kanaOrHangulCount > 0) true
            else latinOrOtherLetterCount >= 2 && cjkCount * 100 / totalLetters < 55
        } else {
            true
        }
    }

    suspend fun translateText(
        text: String,
        targetLang: String = TranslateConfig.targetLang,
    ): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        getCached(trimmed, targetLang)?.let { return it }
        if (!needsTranslation(trimmed, targetLang)) return null
        return translateBatch(listOf(trimmed), targetLang)[trimmed]
    }

    suspend fun testEngine(
        engine: TranslateEngineItem,
        sample: String,
        targetLang: String = TranslateConfig.targetLang,
    ): Result<String> = withContext(IO) {
        runCatching {
            val trimmed = sample.trim()
            if (trimmed.isEmpty()) error("测试文本不能为空")
            val resultMap = executeEngine(
                engine = engine,
                texts = listOf(trimmed),
                lang = TranslateConfig.targetLangSpec(targetLang),
            )
            resultMap[trimmed]?.takeIf { it.isNotBlank() } ?: error("未返回译文")
        }
    }

    suspend fun translateBatch(
        texts: List<String>,
        targetLang: String = TranslateConfig.targetLang,
    ): Map<String, String> = withContext(IO) {
        ensureCacheLoaded()
        val result = LinkedHashMap<String, String>()
        val pending = LinkedHashSet<String>()
        for (raw in texts) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val cached = cache[cacheKey(trimmed, targetLang)]
            if (!cached.isNullOrBlank()) {
                if (cached != trimmed) result[trimmed] = cached
            } else if (needsTranslation(trimmed, targetLang)) {
                pending.add(trimmed)
            }
        }
        if (pending.isEmpty()) return@withContext result

        val enabledEngines = TranslateConfig.getEnabledEngines()
        if (enabledEngines.isEmpty()) return@withContext result

        val lang = TranslateConfig.targetLangSpec(targetLang)
        val chunks = chunkTexts(pending.toList(), maxItems = 15, maxChars = 2200)
        var cacheDirty = false

        for (chunk in chunks) {
            var translated: Map<String, String> = emptyMap()
            for (engine in enabledEngines) {
                val attempt = runCatching {
                    executeEngine(engine, chunk, lang)
                }
                val map = attempt.getOrNull()
                if (!map.isNullOrEmpty()) {
                    translated = map
                    break
                } else {
                    attempt.exceptionOrNull()?.let { err ->
                        AppLog.put("翻译引擎(${engine.name.ifBlank { engine.type.label }})失败：${err.localizedMessage}", err)
                    }
                }
            }

            translated.forEach { (origin, dst) ->
                val cleanDst = dst.trim()
                if (cleanDst.isNotEmpty()) {
                    cache[cacheKey(origin, targetLang)] = cleanDst
                    cacheDirty = true
                    if (cleanDst != origin) {
                        result[origin] = cleanDst
                    }
                }
            }
        }

        if (cacheDirty) schedulePersist()
        result
    }

    private fun chunkTexts(
        items: List<String>,
        maxItems: Int,
        maxChars: Int,
    ): List<List<String>> {
        val chunks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var chars = 0
        for (item in items) {
            if (current.isNotEmpty() && (current.size >= maxItems || chars + item.length > maxChars)) {
                chunks.add(current)
                current = ArrayList()
                chars = 0
            }
            current.add(item)
            chars += item.length
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    private suspend fun executeEngine(
        engine: TranslateEngineItem,
        texts: List<String>,
        lang: TranslateTargetLang,
    ): Map<String, String> {
        val normalized = engine.normalized()
        val effectiveLang = normalized.targetLang.takeIf { it.isNotBlank() }
            ?.let { TranslateConfig.targetLangSpec(it) }
            ?: lang
        val endpoint = normalized.endpoint
        if (endpoint.contains("{{text}}")) {
            return translateWithTemplateUrl(normalized, texts, effectiveLang)
        }
        return when (normalized.type) {
            TranslateProvider.BING -> translateWithBing(normalized, texts, effectiveLang)
            TranslateProvider.GOOGLE -> translateWithGoogle(normalized, texts, effectiveLang)
            TranslateProvider.YOUDAO -> translateWithYoudao(normalized, texts, effectiveLang)
            TranslateProvider.BAIDU -> translateWithBaidu(normalized, texts, effectiveLang)
            TranslateProvider.DEEPL -> translateWithDeepL(normalized, texts, effectiveLang)
            TranslateProvider.DICT_RULE -> translateWithDictRule(normalized, texts)
        }
    }

    private suspend fun translateWithTemplateUrl(
        engine: TranslateEngineItem,
        texts: List<String>,
        lang: TranslateTargetLang,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (item in texts) {
            val url = engine.endpoint
                .replace("{{text}}", URLEncoder.encode(item, "UTF-8"))
                .replace("{{to}}", URLEncoder.encode(lang.code, "UTF-8"))
            val resp = okHttpClient.newCallStrResponse {
                url(url)
                if (engine.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${engine.apiKey}")
                }
            }
            if (!resp.isSuccessful()) error("自定义翻译请求失败(HTTP ${resp.code()})")
            val body = resp.body?.trim().orEmpty()
            val dst = runCatching {
                val json = JsonParser.parseString(body).asJsonObject
                json.get("data")?.asString
                    ?: json.get("translatedText")?.asString
                    ?: json.get("result")?.asString
                    ?: json.getAsJsonArray("translations")?.firstOrNull()?.asJsonObject?.get("text")?.asString
            }.getOrNull()?.trim().orEmpty().ifBlank { body }
            if (dst.isNotBlank()) out[item] = dst
        }
        return out
    }

    private suspend fun getBingAuthToken(): String {
        val now = System.currentTimeMillis()
        val existing = bingToken
        if (!existing.isNullOrBlank() && now - bingTokenAt < BING_TOKEN_TTL_MS) {
            return existing
        }
        return bingTokenMutex.withLock {
            val now2 = System.currentTimeMillis()
            val cached = bingToken
            if (!cached.isNullOrBlank() && now2 - bingTokenAt < BING_TOKEN_TTL_MS) {
                return@withLock cached
            }
            val resp = okHttpClient.newCallStrResponse {
                url("https://edge.microsoft.com/translate/auth")
                header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0"
                )
            }
            val token = resp.body?.trim().orEmpty()
            if (token.isEmpty() || !resp.isSuccessful()) {
                error("获取必应翻译令牌失败(HTTP ${resp.code()})")
            }
            bingToken = token
            bingTokenAt = System.currentTimeMillis()
            token
        }
    }

    private suspend fun translateWithBing(
        engine: TranslateEngineItem,
        texts: List<String>,
        lang: TranslateTargetLang,
    ): Map<String, String> {
        val token = getBingAuthToken()
        val payload = JsonArray().apply {
            texts.forEach { text ->
                add(JsonObject().apply { addProperty("Text", text) })
            }
        }.toString()
        val base = engine.endpoint.ifBlank { TranslateProvider.BING.defaultEndpoint }.substringBefore('?')
        val url = "$base?from=&to=${URLEncoder.encode(lang.bingCode, "UTF-8")}&api-version=3.0&includeSentenceLength=true"
        val resp = okHttpClient.newCallStrResponse {
            url(url)
            header("Authorization", "Bearer $token")
            header("Content-Type", "application/json")
            post(payload.toRequestBody(jsonMediaType))
        }
        if (!resp.isSuccessful()) {
            if (resp.code() == 401) bingToken = null
            error("必应翻译请求失败(HTTP ${resp.code()})")
        }
        val body = resp.body?.trim().orEmpty()
        val array = JsonParser.parseString(body).asJsonArray
        val out = LinkedHashMap<String, String>()
        for (i in texts.indices) {
            if (i >= array.size()) break
            val translations = array[i].asJsonObject.getAsJsonArray("translations")
            val translated = translations?.firstOrNull()?.asJsonObject?.get("text")?.asString
            if (!translated.isNullOrBlank()) {
                out[texts[i]] = translated.trim()
            }
        }
        return out
    }

    private suspend fun translateWithGoogle(
        engine: TranslateEngineItem,
        texts: List<String>,
        lang: TranslateTargetLang,
    ): Map<String, String> {
        val base = engine.endpoint.ifBlank { TranslateProvider.GOOGLE.defaultEndpoint }.substringBefore('?')
        if (texts.size == 1) {
            val single = translateSingleGoogle(base, texts.first(), lang)
            return if (single.isNotBlank()) mapOf(texts.first() to single) else emptyMap()
        }
        val joined = texts.joinToString(BATCH_DELIMITER)
        val mergedResult = translateSingleGoogle(base, joined, lang)
        val parts = mergedResult.split(Regex("""\s*§§§\s*"""))
        if (parts.size == texts.size) {
            val out = LinkedHashMap<String, String>()
            for (i in texts.indices) {
                val t = parts[i].trim()
                if (t.isNotEmpty()) out[texts[i]] = t
            }
            return out
        }
        val out = LinkedHashMap<String, String>()
        for (item in texts) {
            val translated = runCatching { translateSingleGoogle(base, item, lang) }.getOrNull()
            if (!translated.isNullOrBlank()) out[item] = translated
        }
        return out
    }

    private suspend fun translateSingleGoogle(baseUrl: String, text: String, lang: TranslateTargetLang): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = "$baseUrl?client=gtx&sl=auto&tl=${URLEncoder.encode(lang.code, "UTF-8")}&dt=t&q=$encoded"
        val resp = okHttpClient.newCallStrResponse {
            url(url)
            header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            )
        }
        if (!resp.isSuccessful()) {
            error("Google 翻译请求失败(HTTP ${resp.code()})")
        }
        val body = resp.body?.trim().orEmpty()
        val root = JsonParser.parseString(body).asJsonArray
        val sentences = root[0].asJsonArray
        val sb = StringBuilder()
        for (el in sentences) {
            if (el.isJsonArray) {
                val seg = el.asJsonArray[0]
                if (!seg.isJsonNull) sb.append(seg.asString)
            }
        }
        return sb.toString().trim()
    }

    private suspend fun translateWithBaidu(
        engine: TranslateEngineItem,
        texts: List<String>,
        lang: TranslateTargetLang,
    ): Map<String, String> {
        val appId = engine.apiKey.trim()
        val secret = engine.apiSecret.trim()
        if (appId.isBlank() || secret.isBlank()) {
            error("请先配置百度翻译 AppID 与密钥")
        }
        val sanitized = texts.map { it.replace('\n', ' ').replace('\r', ' ') }
        val q = sanitized.joinToString("\n")
        val salt = System.currentTimeMillis().toString()
        val sign = MD5Utils.md5Encode(appId + q + salt + secret) ?: ""
        val form = FormBody.Builder()
            .add("q", q)
            .add("from", "auto")
            .add("to", lang.baiduCode)
            .add("appid", appId)
            .add("salt", salt)
            .add("sign", sign)
            .build()
        val resp = okHttpClient.newCallStrResponse {
            url(engine.endpoint.ifBlank { TranslateProvider.BAIDU.defaultEndpoint })
            post(form)
        }
        val body = resp.body?.trim().orEmpty()
        val json = JsonParser.parseString(body).asJsonObject
        json.get("error_code")?.asString?.let { code ->
            val msg = json.get("error_msg")?.asString.orEmpty()
            error("百度翻译错误($code): $msg")
        }
        val list = json.getAsJsonArray("trans_result") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (i in texts.indices) {
            if (i >= list.size()) break
            val dst = list[i].asJsonObject.get("dst")?.asString?.trim()
            if (!dst.isNullOrEmpty()) out[texts[i]] = dst
        }
        return out
    }

    private suspend fun translateWithYoudao(
        engine: TranslateEngineItem,
        texts: List<String>,
        lang: TranslateTargetLang,
    ): Map<String, String> {
        val appKey = engine.apiKey.trim()
        val appSecret = engine.apiSecret.trim()
        if (appKey.isBlank() || appSecret.isBlank()) {
            error("请先配置有道翻译 AppKey 与应用密钥")
        }
        val out = LinkedHashMap<String, String>()
        for (item in texts) {
            val salt = UUID.randomUUID().toString()
            val curtime = (System.currentTimeMillis() / 1000L).toString()
            val input = if (item.length <= 20) {
                item
            } else {
                item.take(10) + item.length + item.takeLast(10)
            }
            val sign = sha256Hex(appKey + input + salt + curtime + appSecret)
            val form = FormBody.Builder()
                .add("q", item)
                .add("from", "auto")
                .add("to", lang.youdaoCode)
                .add("appKey", appKey)
                .add("salt", salt)
                .add("sign", sign)
                .add("signType", "v3")
                .add("curtime", curtime)
                .build()
            val resp = okHttpClient.newCallStrResponse {
                url(engine.endpoint.ifBlank { TranslateProvider.YOUDAO.defaultEndpoint })
                post(form)
            }
            val body = resp.body?.trim().orEmpty()
            val json = JsonParser.parseString(body).asJsonObject
            val errorCode = json.get("errorCode")?.asString
            if (errorCode != "0") {
                error("有道翻译错误($errorCode)")
            }
            val translation = json.getAsJsonArray("translation")?.firstOrNull()?.asString?.trim()
            if (!translation.isNullOrEmpty()) {
                out[item] = translation
            }
        }
        return out
    }

    private suspend fun translateWithDeepL(
        engine: TranslateEngineItem,
        texts: List<String>,
        lang: TranslateTargetLang,
    ): Map<String, String> {
        val customEndpoint = engine.endpoint.trim()
        val apiKey = engine.apiKey.trim()
        val isOfficialDeepL = customEndpoint.isBlank() ||
            customEndpoint.contains("api-free.deepl.com", ignoreCase = true) ||
            customEndpoint.contains("api.deepl.com", ignoreCase = true)
        if (!isOfficialDeepL) {
            val out = LinkedHashMap<String, String>()
            for (item in texts) {
                val payload = JsonObject().apply {
                    addProperty("text", item)
                    addProperty("source_lang", "auto")
                    addProperty("target_lang", lang.deeplCode.substringBefore('-'))
                }.toString()
                val resp = okHttpClient.newCallStrResponse {
                    url(customEndpoint)
                    if (apiKey.isNotBlank()) {
                        header("Authorization", "Bearer $apiKey")
                    }
                    post(payload.toRequestBody(jsonMediaType))
                }
                if (!resp.isSuccessful()) {
                    error("自定义翻译接口请求失败(HTTP ${resp.code()})")
                }
                val json = JsonParser.parseString(resp.body?.trim().orEmpty()).asJsonObject
                val dst = json.get("data")?.asString?.trim()
                    ?: json.get("translatedText")?.asString?.trim()
                    ?: json.get("result")?.asString?.trim()
                    ?: json.getAsJsonArray("translations")?.firstOrNull()?.asJsonObject?.get("text")?.asString?.trim()
                if (!dst.isNullOrEmpty()) out[item] = dst
            }
            return out
        }
        if (apiKey.isBlank()) {
            error("请先配置 DeepL Auth Key 或自定义 DeepLX 接口地址")
        }
        val endpoint = customEndpoint.ifBlank {
            if (apiKey.endsWith(":fx")) "https://api-free.deepl.com/v2/translate"
            else "https://api.deepl.com/v2/translate"
        }
        val payload = JsonObject().apply {
            add("text", JsonArray().apply { texts.forEach(::add) })
            addProperty("target_lang", lang.deeplCode)
        }.toString()
        val resp = okHttpClient.newCallStrResponse {
            url(endpoint)
            header("Authorization", "DeepL-Auth-Key $apiKey")
            post(payload.toRequestBody(jsonMediaType))
        }
        if (!resp.isSuccessful()) {
            error("DeepL 请求失败(HTTP ${resp.code()})")
        }
        val json = JsonParser.parseString(resp.body?.trim().orEmpty()).asJsonObject
        val translations = json.getAsJsonArray("translations") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (i in texts.indices) {
            if (i >= translations.size()) break
            val dst = translations[i].asJsonObject.get("text")?.asString?.trim()
            if (!dst.isNullOrEmpty()) out[texts[i]] = dst
        }
        return out
    }

    private suspend fun translateWithDictRule(
        engine: TranslateEngineItem,
        texts: List<String>,
    ): Map<String, String> {
        val preferredName = engine.dictRuleName.trim()
        val rule = if (preferredName.isNotBlank()) {
            appDb.dictRuleDao.getByName(preferredName)
        } else {
            appDb.dictRuleDao.enabled.firstOrNull()
        } ?: error("未找到可用的字典规则，请先在「字典规则」中启用规则")

        val out = LinkedHashMap<String, String>()
        for (item in texts) {
            val raw = runCatching { rule.search(item) }.getOrNull().orEmpty()
            val plain = HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_COMPACT)
                .toString()
                .lines()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            if (plain.isNotEmpty()) {
                out[item] = plain
            }
        }
        return out
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
