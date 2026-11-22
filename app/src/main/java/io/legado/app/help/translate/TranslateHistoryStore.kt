package io.legado.app.help.translate

import com.google.gson.reflect.TypeToken
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class TranslatedParagraphPair(
    val original: String = "",
    val translated: String = "",
)

data class TranslatedChapterHistory(
    val chapterIndex: Int = 0,
    val originalChapterTitle: String = "",
    val translatedChapterTitle: String = "",
    val paragraphs: List<TranslatedParagraphPair> = emptyList(),
    val targetLang: String = "zh-CN",
    val engineId: String = "",
    val engineName: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val displayChapterTitle: String
        get() = translatedChapterTitle.ifBlank { originalChapterTitle }
}

data class TranslatedBookHistory(
    val bookKey: String = "",
    val originalTitle: String = "",
    val translatedTitle: String = "",
    val originalIntro: String = "",
    val translatedIntro: String = "",
    val targetLang: String = "zh-CN",
    val engineId: String = "",
    val engineName: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val chapters: List<TranslatedChapterHistory> = emptyList(),
) {
    val displayTitle: String
        get() = translatedTitle.ifBlank { originalTitle }
}

object TranslateHistoryStore {

    private const val MAX_BOOKS = 2000
    private val persistScope = CoroutineScope(SupervisorJob() + IO)
    private val historyFile by lazy { File(appCtx.filesDir, "translate_history.json") }
    private val lock = Any()
    private val loaded = AtomicBoolean(false)
    private val persistScheduled = AtomicBoolean(false)
    private val booksByKey = LinkedHashMap<String, TranslatedBookHistory>()

    private val _booksFlow = MutableStateFlow<List<TranslatedBookHistory>>(emptyList())
    val booksFlow: StateFlow<List<TranslatedBookHistory>> = _booksFlow.asStateFlow()

    private fun normalizeEngineId(engineId: String): String =
        engineId.trim().ifBlank { "default" }

    private fun entryKey(engineId: String, bookKey: String): String =
        "${normalizeEngineId(engineId)}\u0000$bookKey"

    private fun resolveEngineMeta(
        engineId: String,
        engineName: String,
        referenceText: String?,
        targetLang: String,
    ): Pair<String, String> {
        val engines = TranslateConfig.getEngines()
        if (engineId.isNotBlank()) {
            val resolvedName = engineName.ifBlank {
                engines.firstOrNull { it.id == engineId }?.let { it.name.ifBlank { it.type.label } }.orEmpty()
            }
            return engineId to resolvedName
        }
        val (trackedId, trackedName) = BookTitleTranslator.resolveEngineForText(referenceText, targetLang)
        val primary = BookTitleTranslator.currentPrimaryEngine()
        val finalId = if (trackedId != "default") trackedId else primary?.id ?: "default"
        val finalName = engineName
            .ifBlank { trackedName }
            .ifBlank { primary?.let { it.name.ifBlank { it.type.label } }.orEmpty() }
        return finalId to finalName
    }

    private fun ensureLoaded() {
        if (!loaded.compareAndSet(false, true)) return
        synchronized(lock) {
            runCatching {
                if (!historyFile.exists()) return
                val raw = historyFile.readText()
                if (raw.isBlank()) return
                val type = object : TypeToken<List<TranslatedBookHistory>>() {}.type
                val parsed: List<TranslatedBookHistory>? = GSON.fromJson(raw, type)
                val engines = TranslateConfig.getEngines()
                parsed?.forEach { item ->
                    if (item.bookKey.isNotBlank()) {
                        val resolvedId = item.engineId.takeIf { it.isNotBlank() }
                            ?: engines.firstOrNull {
                                item.engineName.isNotBlank() &&
                                    (it.name == item.engineName || it.type.label == item.engineName)
                            }?.id
                            ?: "preset_google"
                        val normalized = if (item.engineId == resolvedId) {
                            item
                        } else {
                            item.copy(
                                engineId = resolvedId,
                                chapters = item.chapters.map { ch ->
                                    if (ch.engineId.isBlank()) ch.copy(engineId = resolvedId) else ch
                                },
                            )
                        }
                        booksByKey[entryKey(resolvedId, normalized.bookKey)] = normalized
                    }
                }
            }
            publishSnapshotLocked()
        }
    }

    private fun publishSnapshotLocked() {
        _booksFlow.value = booksByKey.values.sortedByDescending { it.updatedAt }
    }

    private fun schedulePersist() {
        if (!persistScheduled.compareAndSet(false, true)) return
        persistScope.launch {
            delay(1200)
            persistScheduled.set(false)
            val snapshot = synchronized(lock) {
                val sorted = booksByKey.values.sortedByDescending { it.updatedAt }
                if (sorted.size > MAX_BOOKS) {
                    val trimmed = sorted.take(MAX_BOOKS)
                    booksByKey.clear()
                    trimmed.forEach { booksByKey[entryKey(it.engineId, it.bookKey)] = it }
                    trimmed
                } else {
                    sorted
                }
            }
            runCatching {
                historyFile.writeText(GSON.toJson(snapshot))
            }
        }
    }

    fun getAllBooks(): List<TranslatedBookHistory> {
        ensureLoaded()
        return _booksFlow.value
    }

    fun getBooksForEngine(engineId: String): List<TranslatedBookHistory> {
        ensureLoaded()
        val norm = normalizeEngineId(engineId)
        return _booksFlow.value.filter { normalizeEngineId(it.engineId) == norm }
    }

    fun getBook(bookKey: String, engineId: String? = null): TranslatedBookHistory? {
        ensureLoaded()
        synchronized(lock) {
            return if (!engineId.isNullOrBlank()) {
                booksByKey[entryKey(engineId, bookKey)]
            } else {
                booksByKey.values.firstOrNull { it.bookKey == bookKey }
            }
        }
    }

    fun findOfflineTitle(
        originalTitle: String,
        targetLang: String = TranslateConfig.targetLang,
        engineId: String? = null,
    ): String? {
        val trimmed = originalTitle.trim()
        if (trimmed.isEmpty()) return null
        ensureLoaded()
        synchronized(lock) {
            if (!engineId.isNullOrBlank()) {
                val normEngine = normalizeEngineId(engineId)
                booksByKey.values.firstOrNull {
                    it.originalTitle == trimmed &&
                        it.targetLang == targetLang &&
                        normalizeEngineId(it.engineId) == normEngine &&
                        it.translatedTitle.isNotBlank() &&
                        it.translatedTitle != trimmed
                }?.translatedTitle?.let { return it }
            }
            return booksByKey.values.firstOrNull {
                it.originalTitle == trimmed &&
                    it.targetLang == targetLang &&
                    it.translatedTitle.isNotBlank() &&
                    it.translatedTitle != trimmed
            }?.translatedTitle
        }
    }

    fun findOfflineBookTitle(
        bookKey: String?,
        originalTitle: String,
        targetLang: String = TranslateConfig.targetLang,
        engineId: String? = BookTitleTranslator.currentPrimaryEngineId(),
    ): String? {
        val trimmedTitle = originalTitle.trim()
        val key = bookKey?.trim().orEmpty()
        ensureLoaded()
        synchronized(lock) {
            if (key.isNotEmpty()) {
                if (!engineId.isNullOrBlank()) {
                    booksByKey[entryKey(engineId, key)]
                        ?.takeIf { it.targetLang == targetLang && it.translatedTitle.isNotBlank() && it.translatedTitle != trimmedTitle }
                        ?.translatedTitle
                        ?.let { return it }
                }
                booksByKey.values.firstOrNull {
                    it.bookKey == key &&
                        it.targetLang == targetLang &&
                        it.translatedTitle.isNotBlank() &&
                        it.translatedTitle != trimmedTitle
                }?.translatedTitle?.let { return it }
            }
        }
        return findOfflineTitle(trimmedTitle, targetLang, engineId)
    }

    fun findOfflineBookIntro(
        bookKey: String?,
        originalIntro: String,
        targetLang: String = TranslateConfig.targetLang,
        engineId: String? = BookTitleTranslator.currentPrimaryEngineId(),
    ): String? {
        val key = bookKey?.trim().orEmpty()
        val trimmedIntro = originalIntro.trim()
        if (key.isEmpty() && trimmedIntro.isEmpty()) return null
        ensureLoaded()
        synchronized(lock) {
            fun matchesIntro(book: TranslatedBookHistory): Boolean {
                if (book.targetLang != targetLang || book.translatedIntro.isBlank()) return false
                if (trimmedIntro.isEmpty() || book.originalIntro.isBlank()) return true
                return book.originalIntro == trimmedIntro
            }
            if (key.isNotEmpty()) {
                if (!engineId.isNullOrBlank()) {
                    booksByKey[entryKey(engineId, key)]
                        ?.takeIf(::matchesIntro)
                        ?.translatedIntro
                        ?.let { return it }
                }
                booksByKey.values.firstOrNull { it.bookKey == key && matchesIntro(it) }
                    ?.translatedIntro
                    ?.let { return it }
            }
            if (trimmedIntro.isNotEmpty()) {
                booksByKey.values.firstOrNull {
                    it.targetLang == targetLang &&
                        it.originalIntro == trimmedIntro &&
                        it.translatedIntro.isNotBlank() &&
                        it.translatedIntro != trimmedIntro
                }?.translatedIntro?.let { return it }
            }
            return null
        }
    }

    fun findOfflineChapterTitle(
        bookKey: String?,
        chapterIndex: Int?,
        originalChapterTitle: String,
        targetLang: String = TranslateConfig.targetLang,
        engineId: String? = BookTitleTranslator.currentPrimaryEngineId(),
    ): String? {
        val key = bookKey?.trim().orEmpty()
        val trimmedTitle = originalChapterTitle.trim()
        if (key.isEmpty() && trimmedTitle.isEmpty()) return null
        ensureLoaded()
        synchronized(lock) {
            fun matchInBook(book: TranslatedBookHistory): String? {
                val matched = when {
                    chapterIndex != null -> book.chapters.firstOrNull {
                        it.chapterIndex == chapterIndex &&
                            it.targetLang == targetLang &&
                            it.translatedChapterTitle.isNotBlank() &&
                            it.translatedChapterTitle != it.originalChapterTitle
                    }
                    else -> null
                } ?: if (trimmedTitle.isNotEmpty()) {
                    book.chapters.firstOrNull {
                        it.originalChapterTitle == trimmedTitle &&
                            it.targetLang == targetLang &&
                            it.translatedChapterTitle.isNotBlank() &&
                            it.translatedChapterTitle != trimmedTitle
                    }
                } else null
                return matched?.translatedChapterTitle
            }
            if (key.isNotEmpty()) {
                if (!engineId.isNullOrBlank()) {
                    booksByKey[entryKey(engineId, key)]?.let(::matchInBook)?.let { return it }
                }
                booksByKey.values.asSequence()
                    .filter { it.bookKey == key }
                    .mapNotNull(::matchInBook)
                    .firstOrNull()
                    ?.let { return it }
            }
            if (trimmedTitle.isNotEmpty()) {
                return booksByKey.values.asSequence()
                    .flatMap { it.chapters.asSequence() }
                    .firstOrNull {
                        it.targetLang == targetLang &&
                            it.originalChapterTitle == trimmedTitle &&
                            it.translatedChapterTitle.isNotBlank() &&
                            it.translatedChapterTitle != trimmedTitle
                    }?.translatedChapterTitle
            }
            return null
        }
    }

    fun findOfflineChapter(
        bookKey: String,
        chapterIndex: Int,
        targetLang: String = TranslateConfig.targetLang,
        engineId: String? = BookTitleTranslator.currentPrimaryEngineId(),
    ): TranslatedChapterHistory? {
        ensureLoaded()
        synchronized(lock) {
            if (!engineId.isNullOrBlank()) {
                booksByKey[entryKey(engineId, bookKey)]?.chapters?.firstOrNull {
                    it.chapterIndex == chapterIndex && it.targetLang == targetLang && it.paragraphs.isNotEmpty()
                }?.let { return it }
            }
            return booksByKey.values
                .asSequence()
                .filter { it.bookKey == bookKey }
                .flatMap { it.chapters.asSequence() }
                .firstOrNull {
                    it.chapterIndex == chapterIndex && it.targetLang == targetLang && it.paragraphs.isNotEmpty()
                }
        }
    }

    fun recordBookTitle(
        bookKey: String,
        originalTitle: String,
        translatedTitle: String,
        targetLang: String = TranslateConfig.targetLang,
        engineId: String = "",
        engineName: String = "",
    ) {
        val key = bookKey.trim().ifEmpty { originalTitle.trim() }
        val orig = originalTitle.trim()
        val trans = translatedTitle.trim()
        if (key.isEmpty() || orig.isEmpty() || trans.isEmpty() || orig == trans) return
        ensureLoaded()
        val (resolvedEngineId, resolvedEngineName) = resolveEngineMeta(engineId, engineName, orig, targetLang)
        val compositeKey = entryKey(resolvedEngineId, key)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val existing = booksByKey[compositeKey]
            val updated = if (existing != null) {
                existing.copy(
                    originalTitle = orig,
                    translatedTitle = trans,
                    targetLang = targetLang,
                    engineId = resolvedEngineId,
                    engineName = resolvedEngineName.ifBlank { existing.engineName },
                    updatedAt = now,
                )
            } else {
                TranslatedBookHistory(
                    bookKey = key,
                    originalTitle = orig,
                    translatedTitle = trans,
                    targetLang = targetLang,
                    engineId = resolvedEngineId,
                    engineName = resolvedEngineName,
                    updatedAt = now,
                )
            }
            booksByKey[compositeKey] = updated
            publishSnapshotLocked()
        }
        schedulePersist()
    }

    fun recordBookIntro(
        bookKey: String,
        originalTitle: String,
        translatedTitle: String?,
        originalIntro: String,
        translatedIntro: String,
        targetLang: String = TranslateConfig.targetLang,
        engineId: String = "",
        engineName: String = "",
    ) {
        val key = bookKey.trim().ifEmpty { originalTitle.trim() }
        val origIntro = originalIntro.trim()
        val transIntro = translatedIntro.trim()
        if (key.isEmpty() || origIntro.isEmpty() || transIntro.isEmpty() || origIntro == transIntro) return
        ensureLoaded()
        val (resolvedEngineId, resolvedEngineName) = resolveEngineMeta(engineId, engineName, origIntro, targetLang)
        val compositeKey = entryKey(resolvedEngineId, key)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val existing = booksByKey[compositeKey]
            val resolvedTitle = translatedTitle?.trim().takeUnless { it.isNullOrBlank() }
                ?: existing?.translatedTitle.orEmpty()
            val updated = if (existing != null) {
                existing.copy(
                    originalTitle = originalTitle.trim().ifEmpty { existing.originalTitle },
                    translatedTitle = resolvedTitle,
                    originalIntro = origIntro,
                    translatedIntro = transIntro,
                    targetLang = targetLang,
                    engineId = resolvedEngineId,
                    engineName = resolvedEngineName.ifBlank { existing.engineName },
                    updatedAt = now,
                )
            } else {
                TranslatedBookHistory(
                    bookKey = key,
                    originalTitle = originalTitle.trim(),
                    translatedTitle = resolvedTitle,
                    originalIntro = origIntro,
                    translatedIntro = transIntro,
                    targetLang = targetLang,
                    engineId = resolvedEngineId,
                    engineName = resolvedEngineName,
                    updatedAt = now,
                )
            }
            booksByKey[compositeKey] = updated
            publishSnapshotLocked()
        }
        schedulePersist()
    }

    fun recordChapter(
        bookKey: String,
        originalTitle: String,
        translatedTitle: String?,
        chapterIndex: Int,
        originalChapterTitle: String,
        translatedChapterTitle: String,
        paragraphs: List<TranslatedParagraphPair>,
        targetLang: String = TranslateConfig.targetLang,
        engineId: String = "",
        engineName: String = "",
    ) {
        val key = bookKey.trim().ifEmpty { originalTitle.trim() }
        if (key.isEmpty() || paragraphs.isEmpty()) return
        ensureLoaded()
        val sampleText = paragraphs.firstOrNull { it.original.isNotBlank() && it.original != it.translated }?.original
            ?: originalChapterTitle
        val (resolvedEngineId, resolvedEngineName) = resolveEngineMeta(engineId, engineName, sampleText, targetLang)
        val compositeKey = entryKey(resolvedEngineId, key)
        val now = System.currentTimeMillis()
        val chapterRecord = TranslatedChapterHistory(
            chapterIndex = chapterIndex,
            originalChapterTitle = originalChapterTitle.trim(),
            translatedChapterTitle = translatedChapterTitle.trim(),
            paragraphs = paragraphs,
            targetLang = targetLang,
            engineId = resolvedEngineId,
            engineName = resolvedEngineName,
            updatedAt = now,
        )
        synchronized(lock) {
            val existing = booksByKey[compositeKey]
            val mergedChapters = existing?.chapters
                ?.filterNot { it.chapterIndex == chapterIndex }
                .orEmpty()
                .plus(chapterRecord)
                .sortedBy { it.chapterIndex }
            val resolvedTitle = translatedTitle?.trim().takeUnless { it.isNullOrBlank() }
                ?: existing?.translatedTitle.orEmpty()
            val updated = if (existing != null) {
                existing.copy(
                    originalTitle = originalTitle.trim().ifEmpty { existing.originalTitle },
                    translatedTitle = resolvedTitle,
                    targetLang = targetLang,
                    engineId = resolvedEngineId,
                    engineName = resolvedEngineName.ifBlank { existing.engineName },
                    updatedAt = now,
                    chapters = mergedChapters,
                )
            } else {
                TranslatedBookHistory(
                    bookKey = key,
                    originalTitle = originalTitle.trim(),
                    translatedTitle = resolvedTitle,
                    targetLang = targetLang,
                    engineId = resolvedEngineId,
                    engineName = resolvedEngineName,
                    updatedAt = now,
                    chapters = mergedChapters,
                )
            }
            booksByKey[compositeKey] = updated
            publishSnapshotLocked()
        }
        schedulePersist()
    }

    fun deleteBook(bookKey: String, engineId: String? = null) {
        ensureLoaded()
        var changed = false
        synchronized(lock) {
            if (!engineId.isNullOrBlank()) {
                changed = booksByKey.remove(entryKey(engineId, bookKey)) != null
            } else {
                val iter = booksByKey.entries.iterator()
                while (iter.hasNext()) {
                    if (iter.next().value.bookKey == bookKey) {
                        iter.remove()
                        changed = true
                    }
                }
            }
            if (changed) {
                publishSnapshotLocked()
            }
        }
        if (changed) {
            schedulePersist()
        }
    }

    fun deleteBooks(bookKeys: Collection<String>, engineId: String) {
        if (bookKeys.isEmpty()) return
        ensureLoaded()
        var changed = false
        synchronized(lock) {
            for (bookKey in bookKeys) {
                if (booksByKey.remove(entryKey(engineId, bookKey)) != null) {
                    changed = true
                }
            }
            if (changed) {
                publishSnapshotLocked()
            }
        }
        if (changed) {
            schedulePersist()
        }
    }

    fun deleteChapter(bookKey: String, chapterIndex: Int, engineId: String? = null) {
        ensureLoaded()
        var changed = false
        synchronized(lock) {
            val targets = if (!engineId.isNullOrBlank()) {
                listOf(entryKey(engineId, bookKey))
            } else {
                booksByKey.entries.filter { it.value.bookKey == bookKey }.map { it.key }
            }
            val now = System.currentTimeMillis()
            for (k in targets) {
                val existing = booksByKey[k] ?: continue
                val nextChapters = existing.chapters.filterNot { it.chapterIndex == chapterIndex }
                if (nextChapters.size != existing.chapters.size) {
                    booksByKey[k] = existing.copy(
                        chapters = nextChapters,
                        updatedAt = now,
                    )
                    changed = true
                }
            }
            if (changed) {
                publishSnapshotLocked()
            }
        }
        if (changed) {
            schedulePersist()
        }
    }

    fun deleteChapters(bookKey: String, chapterIndices: Collection<Int>, engineId: String) {
        val indexSet = chapterIndices.toHashSet()
        if (indexSet.isEmpty()) return
        ensureLoaded()
        var changed = false
        synchronized(lock) {
            val k = entryKey(engineId, bookKey)
            val existing = booksByKey[k]
            if (existing != null) {
                val nextChapters = existing.chapters.filterNot { it.chapterIndex in indexSet }
                if (nextChapters.size != existing.chapters.size) {
                    booksByKey[k] = existing.copy(
                        chapters = nextChapters,
                        updatedAt = System.currentTimeMillis(),
                    )
                    changed = true
                    publishSnapshotLocked()
                }
            }
        }
        if (changed) {
            schedulePersist()
        }
    }

    fun updateBookTranslation(
        bookKey: String,
        engineId: String,
        newTranslatedTitle: String,
        newTranslatedIntro: String? = null,
    ): Boolean {
        ensureLoaded()
        val k = entryKey(engineId, bookKey)
        val cleanTitle = newTranslatedTitle.trim()
        val cleanIntro = newTranslatedIntro?.trim()
        val now = System.currentTimeMillis()
        var origTitle = ""
        var origIntro = ""
        var lang = TranslateConfig.targetLang
        var updatedBook: TranslatedBookHistory? = null
        synchronized(lock) {
            val existing = booksByKey[k] ?: return false
            origTitle = existing.originalTitle
            origIntro = existing.originalIntro
            lang = existing.targetLang
            updatedBook = existing.copy(
                translatedTitle = cleanTitle,
                translatedIntro = cleanIntro ?: existing.translatedIntro,
                updatedAt = now,
            )
            booksByKey[k] = updatedBook!!
            publishSnapshotLocked()
        }
        val finalBook = updatedBook ?: return false
        if (origTitle.isNotBlank()) {
            BookTitleTranslator.updateCacheEntry(engineId, origTitle, finalBook.translatedTitle, lang)
        }
        if (cleanIntro != null && origIntro.isNotBlank()) {
            BookTitleTranslator.updateCacheEntry(engineId, origIntro, finalBook.translatedIntro, lang)
        }
        schedulePersist()
        return true
    }

    fun updateChapterTitle(
        bookKey: String,
        engineId: String,
        chapterIndex: Int,
        newTranslatedChapterTitle: String,
    ): Boolean {
        ensureLoaded()
        val k = entryKey(engineId, bookKey)
        val cleanTitle = newTranslatedChapterTitle.trim()
        val now = System.currentTimeMillis()
        var origTitle = ""
        var lang = TranslateConfig.targetLang
        var updated = false
        synchronized(lock) {
            val existing = booksByKey[k] ?: return false
            val targetIdx = existing.chapters.indexOfFirst { it.chapterIndex == chapterIndex }
            if (targetIdx < 0) return false
            val oldCh = existing.chapters[targetIdx]
            origTitle = oldCh.originalChapterTitle
            lang = oldCh.targetLang
            val nextChapters = existing.chapters.toMutableList()
            nextChapters[targetIdx] = oldCh.copy(
                translatedChapterTitle = cleanTitle,
                updatedAt = now,
            )
            booksByKey[k] = existing.copy(
                chapters = nextChapters,
                updatedAt = now,
            )
            updated = true
            publishSnapshotLocked()
        }
        if (updated) {
            if (origTitle.isNotBlank()) {
                BookTitleTranslator.updateCacheEntry(engineId, origTitle, cleanTitle, lang)
            }
            schedulePersist()
        }
        return updated
    }

    fun updateChapterParagraph(
        bookKey: String,
        engineId: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        newTranslatedText: String,
    ): Boolean {
        ensureLoaded()
        val k = entryKey(engineId, bookKey)
        val cleanText = newTranslatedText.trim()
        val now = System.currentTimeMillis()
        var origText = ""
        var lang = TranslateConfig.targetLang
        var updated = false
        synchronized(lock) {
            val existing = booksByKey[k] ?: return false
            val chIdx = existing.chapters.indexOfFirst { it.chapterIndex == chapterIndex }
            if (chIdx < 0) return false
            val oldCh = existing.chapters[chIdx]
            if (paragraphIndex !in oldCh.paragraphs.indices) return false
            val oldPair = oldCh.paragraphs[paragraphIndex]
            origText = oldPair.original
            lang = oldCh.targetLang
            val nextParagraphs = oldCh.paragraphs.toMutableList()
            nextParagraphs[paragraphIndex] = oldPair.copy(translated = cleanText)
            val nextChapters = existing.chapters.toMutableList()
            nextChapters[chIdx] = oldCh.copy(
                paragraphs = nextParagraphs,
                updatedAt = now,
            )
            booksByKey[k] = existing.copy(
                chapters = nextChapters,
                updatedAt = now,
            )
            updated = true
            publishSnapshotLocked()
        }
        if (updated) {
            if (origText.isNotBlank()) {
                BookTitleTranslator.updateCacheEntry(engineId, origText, cleanText, lang)
            }
            schedulePersist()
        }
        return updated
    }

    fun clearForEngine(engineId: String) {
        ensureLoaded()
        val norm = normalizeEngineId(engineId)
        var changed = false
        synchronized(lock) {
            val iter = booksByKey.entries.iterator()
            while (iter.hasNext()) {
                if (normalizeEngineId(iter.next().value.engineId) == norm) {
                    iter.remove()
                    changed = true
                }
            }
            if (changed) {
                publishSnapshotLocked()
            }
        }
        if (changed) {
            schedulePersist()
        }
    }

    fun clearAll() {
        ensureLoaded()
        synchronized(lock) {
            booksByKey.clear()
            publishSnapshotLocked()
        }
        persistScope.launch {
            runCatching { if (historyFile.exists()) historyFile.delete() }
        }
    }
}
