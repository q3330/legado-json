package io.legado.app.help.translate

import android.os.Handler
import android.os.Looper
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class EngineHealthState(
    val consecutiveFailures: Int = 0,
    val cooldownUntilMs: Long = 0L,
    val lastError: String = "",
)

object BookTitleTranslator {

    private const val MAX_CACHE_ENTRIES = 8000
    private const val BATCH_DELIMITER = "\n§§§\n"
    const val EXPLORE_TRANSLATE_BATCH_SIZE = 8
    const val EXPLORE_TRANSLATE_STEP_SIZE = 2

    private val mainHandler = Handler(Looper.getMainLooper())
    private val persistScope = CoroutineScope(SupervisorJob() + IO)
    private val cacheFile by lazy { File(appCtx.filesDir, "translate_cache.json") }
    private val cache = ConcurrentHashMap<String, String>()
    private val cacheLoaded = AtomicBoolean(false)
    private val persistScheduled = AtomicBoolean(false)

    private val inFlightMap = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val healthMap = ConcurrentHashMap<String, EngineHealthState>()
    private val textEngineMap = ConcurrentHashMap<String, Pair<String, String>>()
    private val lastSucceededEngine = AtomicReference<Pair<String, String>?>(null)
    private val activeTranslationSessions = AtomicInteger(0)
    private val modelLoadingCount = AtomicInteger(0)
    private val _isLoadingModel = MutableStateFlow(false)
    val isLoadingModel: StateFlow<Boolean> = _isLoadingModel.asStateFlow()
    private val modelLoadingListeners = CopyOnWriteArraySet<() -> Unit>()
    private val _healthRevision = MutableStateFlow(0)
    val healthRevision: StateFlow<Int> = _healthRevision.asStateFlow()
    private val _cacheRevision = MutableStateFlow(0)
    val cacheRevision: StateFlow<Int> = _cacheRevision.asStateFlow()

    fun addModelLoadingListener(listener: () -> Unit) {
        modelLoadingListeners.add(listener)
    }

    fun removeModelLoadingListener(listener: () -> Unit) {
        modelLoadingListeners.remove(listener)
    }

    private fun notifyModelLoadingChanged(loading: Boolean) {
        if (_isLoadingModel.value == loading) return
        _isLoadingModel.value = loading
        if (modelLoadingListeners.isNotEmpty()) {
            mainHandler.post {
                modelLoadingListeners.forEach { it.invoke() }
            }
        }
    }

    internal inline fun <T> withModelLoading(block: () -> T): T {
        beginModelLoading()
        return try {
            block()
        } finally {
            endModelLoading()
        }
    }

    internal fun beginModelLoading() {
        if (modelLoadingCount.incrementAndGet() > 0) {
            notifyModelLoadingChanged(true)
        }
    }

    internal fun endModelLoading() {
        val left = modelLoadingCount.decrementAndGet()
        if (left <= 0) {
            modelLoadingCount.compareAndSet(left, 0)
            notifyModelLoadingChanged(false)
        }
    }

    /**
     * 按页并按单次 5~10 本（默认 8 本）筛选发现页本次需要翻译的书名。
     * 优先取当前正在浏览页（[currentPage]）尚未翻译的书籍；若当前页已全部翻译，
     * 则顺延到从当前页往后第一个仍有未译书籍的已加载页（仅限单页，不跨页混合），每次最多取 [batchLimit] 本。
     */
    fun selectExplorePageBatchToTranslate(
        books: List<SearchBook>,
        bookPages: Map<String, Int>,
        currentPage: Int,
        firstLoadedPage: Int,
        translatedNames: Map<String, String>,
        batchLimit: Int = EXPLORE_TRANSLATE_BATCH_SIZE,
    ): List<String> {
        if (books.isEmpty()) return emptyList()
        val fallbackPage = firstLoadedPage.coerceAtLeast(1)
        val targetCurrentPage = currentPage.coerceAtLeast(fallbackPage)

        fun isUntranslated(book: SearchBook): Boolean {
            val name = book.name
            return name !in translatedNames &&
                resolveBookDisplayName(book.bookUrl, name) == name &&
                needsTranslation(name)
        }

        val currentPageNames = books.asSequence()
            .filter { (bookPages[it.bookUrl] ?: fallbackPage) == targetCurrentPage && isUntranslated(it) }
            .map { it.name }
            .distinct()
            .take(batchLimit)
            .toList()
        if (currentPageNames.isNotEmpty()) return currentPageNames

        val orderedPages = books.asSequence()
            .map { bookPages[it.bookUrl] ?: fallbackPage }
            .distinct()
            .toList()
        val nextPages = orderedPages.filter { it > targetCurrentPage } +
            orderedPages.filter { it < targetCurrentPage }
        for (page in nextPages) {
            val pageNames = books.asSequence()
                .filter { (bookPages[it.bookUrl] ?: fallbackPage) == page && isUntranslated(it) }
                .map { it.name }
                .distinct()
                .take(batchLimit)
                .toList()
            if (pageNames.isNotEmpty()) return pageNames
        }
        return emptyList()
    }

    suspend fun <T> withTranslationSession(block: suspend () -> T): T {
        activeTranslationSessions.incrementAndGet()
        return try {
            block()
        } finally {
            val remaining = activeTranslationSessions.decrementAndGet()
            if (remaining <= 0) {
                activeTranslationSessions.compareAndSet(remaining, 0)
                releaseLocalModelsAsync()
            }
        }
    }

    fun releaseLocalModelsAsync() {
        persistScope.launch {
            if (activeTranslationSessions.get() <= 0) {
                LocalHyMtTranslator.unloadAsync()
                LocalNmtTranslator.unloadAllAsync()
                MlKitTranslator.closeAllClientsAsync()
            }
        }
    }

    fun currentPrimaryEngine(): TranslateEngineItem? =
        TranslateConfig.getEnabledEngines().firstOrNull()

    fun currentPrimaryEngineId(): String =
        currentPrimaryEngine()?.id ?: "default"

    fun resolveEngineForText(
        text: String?,
        targetLang: String = TranslateConfig.targetLang,
    ): Pair<String, String> {
        val normalized = text?.trim().orEmpty()
        if (normalized.isNotEmpty()) {
            textEngineMap[engineTrackKey(normalized, targetLang)]?.let { return it }
        }
        lastSucceededEngine.get()?.let { return it }
        val primary = currentPrimaryEngine()
        return if (primary != null) {
            primary.id to primary.name.ifBlank { primary.type.label }
        } else {
            "default" to ""
        }
    }

    fun getEngineHealth(engineId: String): EngineHealthState? = healthMap[engineId]

    fun isEngineInCooldown(engineId: String, now: Long = System.currentTimeMillis()): Boolean =
        (healthMap[engineId]?.cooldownUntilMs ?: 0L) > now

    fun cooldownRemainingSeconds(engineId: String): Long {
        val remain = (healthMap[engineId]?.cooldownUntilMs ?: 0L) - System.currentTimeMillis()
        return if (remain <= 0L) 0L else (remain + 999L) / 1000L
    }

    fun resetEngineCooldown(engineId: String) {
        if (healthMap.remove(engineId) != null) {
            _healthRevision.value += 1
        }
    }

    fun resetAllCooldowns() {
        if (healthMap.isNotEmpty()) {
            healthMap.clear()
            _healthRevision.value += 1
        }
    }

    private fun recordEngineSuccess(engineId: String) {
        if (healthMap.remove(engineId) != null) {
            _healthRevision.value += 1
        }
    }

    private fun recordEngineFailure(engineId: String, error: Throwable) {
        val msg = error.localizedMessage ?: error.javaClass.simpleName
        val isHardThrottle = msg.contains("429") ||
            msg.contains("403") ||
            msg.contains("rate limit", ignoreCase = true) ||
            msg.contains("too many", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("timed out", ignoreCase = true) ||
            msg.contains("超时") ||
            msg.contains("验证") ||
            msg.contains("captcha", ignoreCase = true)
        val prev = healthMap[engineId] ?: EngineHealthState()
        val failures = prev.consecutiveFailures + 1
        val shouldCooldown = isHardThrottle || failures >= 2
        val cooldownMs = if (shouldCooldown) {
            when (failures) {
                1 -> 2 * 60_000L
                2 -> 5 * 60_000L
                3 -> 10 * 60_000L
                else -> 15 * 60_000L
            }
        } else {
            prev.cooldownUntilMs
        }
        val until = if (shouldCooldown) System.currentTimeMillis() + cooldownMs else 0L
        healthMap[engineId] = EngineHealthState(
            consecutiveFailures = failures,
            cooldownUntilMs = until,
            lastError = msg,
        )
        _healthRevision.value += 1
    }

    private fun ensureCacheLoaded() {
        if (!cacheLoaded.compareAndSet(false, true)) return
        runCatching {
            if (!cacheFile.exists()) return
            val raw = cacheFile.readText()
            if (raw.isBlank()) return
            val type = object : TypeToken<Map<String, String>>() {}.type
            val loaded: Map<String, String>? = GSON.fromJson(raw, type)
            loaded?.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) {
                    val parts = k.split('\u0000')
                    val migratedKey = if (parts.size == 2) {
                        "preset_google\u0000$k"
                    } else {
                        k
                    }
                    cache[migratedKey] = v
                }
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

    private fun engineTrackKey(text: String, targetLang: String): String = "$targetLang\u0000$text"

    private fun cacheKey(
        text: String,
        targetLang: String,
        engineId: String = currentPrimaryEngineId(),
    ): String = "$engineId\u0000$targetLang\u0000$text"

    fun cacheSize(): Int {
        ensureCacheLoaded()
        return cache.size
    }

    fun cacheSizeForEngine(engineId: String): Int {
        ensureCacheLoaded()
        val prefix = "$engineId\u0000"
        return cache.keys.count { it.startsWith(prefix) }
    }

    fun clearCache() {
        cache.clear()
        textEngineMap.clear()
        cacheLoaded.set(true)
        _cacheRevision.value += 1
        persistScope.launch {
            runCatching { if (cacheFile.exists()) cacheFile.delete() }
        }
    }

    fun clearCacheForEngine(engineId: String) {
        ensureCacheLoaded()
        val prefix = "$engineId\u0000"
        val removed = cache.keys.filter { it.startsWith(prefix) }
        removed.forEach { cache.remove(it) }
        textEngineMap.entries.removeIf { it.value.first == engineId }
        if (lastSucceededEngine.get()?.first == engineId) {
            lastSucceededEngine.set(null)
        }
        if (removed.isNotEmpty()) {
            _cacheRevision.value += 1
            schedulePersist()
        }
    }

    fun clearCacheEntriesForEngine(engineId: String, texts: Collection<String>) {
        val targetTexts = texts.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toHashSet()
        if (targetTexts.isEmpty()) return
        ensureCacheLoaded()
        val prefix = "$engineId\u0000"
        val removedKeys = cache.keys.filter { key ->
            key.startsWith(prefix) && key.substringAfterLast('\u0000') in targetTexts
        }
        removedKeys.forEach { cache.remove(it) }
        textEngineMap.entries.removeIf { entry ->
            entry.value.first == engineId && entry.key.substringAfter('\u0000') in targetTexts
        }
        if (removedKeys.isNotEmpty()) {
            _cacheRevision.value += 1
            schedulePersist()
        }
    }

    fun updateCacheEntry(
        engineId: String,
        originalText: String,
        translatedText: String,
        targetLang: String = TranslateConfig.targetLang,
    ) {
        val orig = originalText.trim()
        if (orig.isEmpty()) return
        ensureCacheLoaded()
        val clean = translatedText.trim()
        val key = cacheKey(orig, targetLang, engineId)
        val primaryId = currentPrimaryEngineId()
        if (clean.isEmpty() || clean == orig) {
            cache.remove(key)
            if (primaryId != engineId) {
                cache.remove(cacheKey(orig, targetLang, primaryId))
            }
        } else {
            cache[key] = clean
            if (primaryId != engineId) {
                cache[cacheKey(orig, targetLang, primaryId)] = clean
            }
        }
        _cacheRevision.value += 1
        schedulePersist()
    }

    fun getCached(text: String?, targetLang: String = TranslateConfig.targetLang): String? {
        val normalized = text?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        ensureCacheLoaded()
        val primaryId = currentPrimaryEngineId()
        return cache[cacheKey(normalized, targetLang, primaryId)]?.takeIf { it.isNotBlank() && it != normalized }
            ?: TranslateHistoryStore.findOfflineTitle(normalized, targetLang, engineId = primaryId)
    }

    fun resolveBookDisplayName(
        bookKey: String?,
        originalName: String,
        targetLang: String = TranslateConfig.targetLang,
    ): String {
        val trimmed = originalName.trim()
        if (trimmed.isEmpty()) return originalName
        return TranslateHistoryStore.findOfflineBookTitle(bookKey, trimmed, targetLang)
            ?.takeIf { it.isNotBlank() }
            ?: getCached(trimmed, targetLang)?.takeIf { it.isNotBlank() }
            ?: originalName
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
        return withTranslationSession {
            translateBatch(listOf(trimmed), targetLang)[trimmed]
        }
    }

    suspend fun testEngine(
        engine: TranslateEngineItem,
        sample: String,
        targetLang: String = TranslateConfig.targetLang,
    ): Result<String> = withContext(IO) {
        withTranslationSession {
            runCatching {
                val trimmed = sample.trim()
                if (trimmed.isEmpty()) error("测试文本不能为空")
                val resultMap = executeEngine(
                    engine = engine,
                    texts = listOf(trimmed),
                    lang = TranslateConfig.targetLangSpec(targetLang),
                )
                val dst = resultMap[trimmed]?.takeIf { it.isNotBlank() } ?: error("未返回译文")
                recordEngineSuccess(engine.id)
                dst
            }
        }
    }

    suspend fun translateBatch(
        texts: List<String>,
        targetLang: String = TranslateConfig.targetLang,
        onItemTranslated: ((String, String) -> Unit)? = null,
    ): Map<String, String> = withContext(IO) {
        ensureCacheLoaded()
        val primaryId = currentPrimaryEngineId()
        val result = LinkedHashMap<String, String>()
        val owned = LinkedHashMap<String, CompletableDeferred<String?>>()
        val awaited = LinkedHashMap<String, CompletableDeferred<String?>>()

        for (raw in texts) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val key = cacheKey(trimmed, targetLang, primaryId)
            val cached = cache[key]
            if (!cached.isNullOrBlank()) {
                if (cached != trimmed) {
                    result[trimmed] = cached
                    onItemTranslated?.invoke(trimmed, cached)
                }
                continue
            }
            if (!needsTranslation(trimmed, targetLang)) continue
            if (trimmed in owned || trimmed in awaited) continue

            val fresh = CompletableDeferred<String?>()
            val existing = inFlightMap.putIfAbsent(key, fresh)
            if (existing != null) {
                awaited[trimmed] = existing
            } else {
                owned[trimmed] = fresh
            }
        }

        val ownedResults = HashMap<String, String>(owned.size)
        if (owned.isNotEmpty()) {
            withTranslationSession {
                try {
                    val enabledEngines = TranslateConfig.getEnabledEngines()
                    if (enabledEngines.isNotEmpty()) {
                        val lang = TranslateConfig.targetLangSpec(targetLang)
                        val chunks = chunkTexts(owned.keys.toList(), maxItems = 15, maxChars = 2200)
                        var cacheDirty = false

                        for (chunk in chunks) {
                            val now = System.currentTimeMillis()
                            val candidates = enabledEngines
                                .filterNot { isEngineInCooldown(it.id, now) }
                                .ifEmpty {
                                    enabledEngines.sortedBy { getEngineHealth(it.id)?.cooldownUntilMs ?: 0L }
                                }
                            var translated: Map<String, String> = emptyMap()
                            var succeededEngine: TranslateEngineItem? = null
                            val streamedInCurrentAttempt = HashSet<String>()
                            for (engine in candidates) {
                                streamedInCurrentAttempt.clear()
                                val enginePairCandidate = engine.id to engine.name.ifBlank { engine.type.label }
                                val attempt = runCatching {
                                    executeEngine(engine, chunk, lang) { origin, dst ->
                                        val cleanDst = dst.trim()
                                        if (cleanDst.isNotEmpty() && streamedInCurrentAttempt.add(origin)) {
                                            cache[cacheKey(origin, targetLang, primaryId)] = cleanDst
                                            if (enginePairCandidate.first != primaryId) {
                                                cache[cacheKey(origin, targetLang, enginePairCandidate.first)] = cleanDst
                                            }
                                            textEngineMap[engineTrackKey(origin, targetLang)] = enginePairCandidate
                                            cacheDirty = true
                                            ownedResults[origin] = cleanDst
                                            _cacheRevision.value += 1
                                            if (cleanDst != origin) {
                                                result[origin] = cleanDst
                                                onItemTranslated?.invoke(origin, cleanDst)
                                            }
                                        }
                                    }
                                }
                                val map = attempt.getOrNull()
                                if (!map.isNullOrEmpty()) {
                                    recordEngineSuccess(engine.id)
                                    translated = map
                                    succeededEngine = engine
                                    break
                                } else {
                                    attempt.exceptionOrNull()?.let { err ->
                                        recordEngineFailure(engine.id, err)
                                        AppLog.put(
                                            "翻译引擎(${engine.name.ifBlank { engine.type.label }})失败：${err.localizedMessage}",
                                            err,
                                        )
                                    }
                                }
                            }

                            val enginePair = succeededEngine?.let {
                                it.id to it.name.ifBlank { it.type.label }
                            }
                            if (enginePair != null) {
                                lastSucceededEngine.set(enginePair)
                            }

                            translated.forEach { (origin, dst) ->
                                val cleanDst = dst.trim()
                                if (cleanDst.isNotEmpty()) {
                                    val alreadyStreamed = origin in streamedInCurrentAttempt
                                    cache[cacheKey(origin, targetLang, primaryId)] = cleanDst
                                    if (enginePair != null) {
                                        if (enginePair.first != primaryId) {
                                            cache[cacheKey(origin, targetLang, enginePair.first)] = cleanDst
                                        }
                                        textEngineMap[engineTrackKey(origin, targetLang)] = enginePair
                                    }
                                    cacheDirty = true
                                    ownedResults[origin] = cleanDst
                                    if (cleanDst != origin) {
                                        result[origin] = cleanDst
                                        if (!alreadyStreamed) {
                                            onItemTranslated?.invoke(origin, cleanDst)
                                        }
                                    }
                                }
                            }
                        }
                        if (cacheDirty) {
                            _cacheRevision.value += 1
                            schedulePersist()
                        }
                    }
                } finally {
                    owned.forEach { (origin, deferred) ->
                        inFlightMap.remove(cacheKey(origin, targetLang, primaryId), deferred)
                        deferred.complete(ownedResults[origin])
                    }
                }
            }
        }

        awaited.forEach { (origin, deferred) ->
            val translated = runCatching { deferred.await() }.getOrNull()?.trim()
            if (!translated.isNullOrEmpty() && translated != origin) {
                result[origin] = translated
                onItemTranslated?.invoke(origin, translated)
            }
        }
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
        onItemTranslated: ((String, String) -> Unit)? = null,
    ): Map<String, String> {
        val normalized = engine.normalized()
        val effectiveLang = normalized.targetLang.takeIf { it.isNotBlank() }
            ?.let { TranslateConfig.targetLangSpec(it) }
            ?: lang
        val endpoint = normalized.endpoint
        if (normalized.type == TranslateProvider.GOOGLE && endpoint.contains("{{text}}")) {
            return translateWithTemplateUrl(normalized, texts, effectiveLang, onItemTranslated)
        }
        return when (normalized.type) {
            TranslateProvider.HY_MT_OFFLINE -> LocalHyMtTranslator.translate(normalized, texts, effectiveLang, onItemTranslated)
            TranslateProvider.LOCAL_NMT -> LocalNmtTranslator.translate(normalized, texts, effectiveLang, onItemTranslated)
            TranslateProvider.MLKIT_OFFLINE -> MlKitTranslator.translate(normalized, texts, effectiveLang, onItemTranslated)
            TranslateProvider.GOOGLE -> translateWithGoogle(normalized, texts, effectiveLang, onItemTranslated)
        }
    }

    private suspend fun translateWithTemplateUrl(
        engine: TranslateEngineItem,
        texts: List<String>,
        lang: TranslateTargetLang,
        onItemTranslated: ((String, String) -> Unit)? = null,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (item in texts) {
            val url = engine.endpoint
                .replace("{{text}}", URLEncoder.encode(item, "UTF-8"))
                .replace("{{to}}", URLEncoder.encode(lang.code, "UTF-8"))
            val resp = okHttpClient.newCallStrResponse {
                url(url)
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
            if (dst.isNotBlank()) {
                out[item] = dst
                onItemTranslated?.invoke(item, dst)
            }
        }
        return out
    }

    private suspend fun translateWithGoogle(
        engine: TranslateEngineItem,
        texts: List<String>,
        lang: TranslateTargetLang,
        onItemTranslated: ((String, String) -> Unit)? = null,
    ): Map<String, String> {
        val base = engine.endpoint.ifBlank { TranslateProvider.GOOGLE.defaultEndpoint }.substringBefore('?')
        if (texts.size == 1) {
            val single = translateSingleGoogle(base, texts.first(), lang)
            return if (single.isNotBlank()) {
                onItemTranslated?.invoke(texts.first(), single)
                mapOf(texts.first() to single)
            } else {
                emptyMap()
            }
        }
        val joined = texts.joinToString(BATCH_DELIMITER)
        val mergedResult = translateSingleGoogle(base, joined, lang)
        val parts = mergedResult.split(Regex("""\s*§§§\s*"""))
        if (parts.size == texts.size) {
            val out = LinkedHashMap<String, String>()
            for (i in texts.indices) {
                val t = parts[i].trim()
                if (t.isNotEmpty()) {
                    out[texts[i]] = t
                    onItemTranslated?.invoke(texts[i], t)
                }
            }
            return out
        }
        val out = LinkedHashMap<String, String>()
        for (item in texts) {
            val translated = runCatching { translateSingleGoogle(base, item, lang) }.getOrNull()
            if (!translated.isNullOrBlank()) {
                out[item] = translated
                onItemTranslated?.invoke(item, translated)
            }
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
}
