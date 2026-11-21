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
    val engineName: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val chapters: List<TranslatedChapterHistory> = emptyList(),
) {
    val displayTitle: String
        get() = translatedTitle.ifBlank { originalTitle }
}

object TranslateHistoryStore {

    private const val MAX_BOOKS = 1000
    private val persistScope = CoroutineScope(SupervisorJob() + IO)
    private val historyFile by lazy { File(appCtx.filesDir, "translate_history.json") }
    private val lock = Any()
    private val loaded = AtomicBoolean(false)
    private val persistScheduled = AtomicBoolean(false)
    private val booksByKey = LinkedHashMap<String, TranslatedBookHistory>()

    private val _booksFlow = MutableStateFlow<List<TranslatedBookHistory>>(emptyList())
    val booksFlow: StateFlow<List<TranslatedBookHistory>> = _booksFlow.asStateFlow()

    private fun ensureLoaded() {
        if (!loaded.compareAndSet(false, true)) return
        synchronized(lock) {
            runCatching {
                if (!historyFile.exists()) return
                val raw = historyFile.readText()
                if (raw.isBlank()) return
                val type = object : TypeToken<List<TranslatedBookHistory>>() {}.type
                val parsed: List<TranslatedBookHistory>? = GSON.fromJson(raw, type)
                parsed?.forEach { item ->
                    if (item.bookKey.isNotBlank()) {
                        booksByKey[item.bookKey] = item
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
                    trimmed.forEach { booksByKey[it.bookKey] = it }
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

    fun getBook(bookKey: String): TranslatedBookHistory? {
        ensureLoaded()
        synchronized(lock) {
            return booksByKey[bookKey]
        }
    }

    fun findOfflineTitle(originalTitle: String, targetLang: String = TranslateConfig.targetLang): String? {
        val trimmed = originalTitle.trim()
        if (trimmed.isEmpty()) return null
        ensureLoaded()
        synchronized(lock) {
            return booksByKey.values.firstOrNull {
                it.originalTitle == trimmed &&
                    it.targetLang == targetLang &&
                    it.translatedTitle.isNotBlank() &&
                    it.translatedTitle != trimmed
            }?.translatedTitle
        }
    }

    fun findOfflineChapter(
        bookKey: String,
        chapterIndex: Int,
        targetLang: String = TranslateConfig.targetLang,
    ): TranslatedChapterHistory? {
        ensureLoaded()
        synchronized(lock) {
            val book = booksByKey[bookKey] ?: return null
            return book.chapters.firstOrNull {
                it.chapterIndex == chapterIndex && it.targetLang == targetLang && it.paragraphs.isNotEmpty()
            }
        }
    }

    fun recordBookTitle(
        bookKey: String,
        originalTitle: String,
        translatedTitle: String,
        targetLang: String = TranslateConfig.targetLang,
        engineName: String = TranslateConfig.getEnabledEngines().firstOrNull()?.let { it.name.ifBlank { it.type.label } }.orEmpty(),
    ) {
        val key = bookKey.trim().ifEmpty { originalTitle.trim() }
        val orig = originalTitle.trim()
        val trans = translatedTitle.trim()
        if (key.isEmpty() || orig.isEmpty() || trans.isEmpty() || orig == trans) return
        ensureLoaded()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val existing = booksByKey[key]
            val updated = if (existing != null) {
                existing.copy(
                    originalTitle = orig,
                    translatedTitle = trans,
                    targetLang = targetLang,
                    engineName = engineName.ifBlank { existing.engineName },
                    updatedAt = now,
                )
            } else {
                TranslatedBookHistory(
                    bookKey = key,
                    originalTitle = orig,
                    translatedTitle = trans,
                    targetLang = targetLang,
                    engineName = engineName,
                    updatedAt = now,
                )
            }
            booksByKey[key] = updated
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
        engineName: String = TranslateConfig.getEnabledEngines().firstOrNull()?.let { it.name.ifBlank { it.type.label } }.orEmpty(),
    ) {
        val key = bookKey.trim().ifEmpty { originalTitle.trim() }
        val origIntro = originalIntro.trim()
        val transIntro = translatedIntro.trim()
        if (key.isEmpty() || origIntro.isEmpty() || transIntro.isEmpty() || origIntro == transIntro) return
        ensureLoaded()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val existing = booksByKey[key]
            val resolvedTitle = translatedTitle?.trim().takeUnless { it.isNullOrBlank() }
                ?: existing?.translatedTitle.orEmpty()
            val updated = if (existing != null) {
                existing.copy(
                    originalTitle = originalTitle.trim().ifEmpty { existing.originalTitle },
                    translatedTitle = resolvedTitle,
                    originalIntro = origIntro,
                    translatedIntro = transIntro,
                    targetLang = targetLang,
                    engineName = engineName.ifBlank { existing.engineName },
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
                    engineName = engineName,
                    updatedAt = now,
                )
            }
            booksByKey[key] = updated
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
        engineName: String = TranslateConfig.getEnabledEngines().firstOrNull()?.let { it.name.ifBlank { it.type.label } }.orEmpty(),
    ) {
        val key = bookKey.trim().ifEmpty { originalTitle.trim() }
        if (key.isEmpty() || paragraphs.isEmpty()) return
        ensureLoaded()
        val now = System.currentTimeMillis()
        val chapterRecord = TranslatedChapterHistory(
            chapterIndex = chapterIndex,
            originalChapterTitle = originalChapterTitle.trim(),
            translatedChapterTitle = translatedChapterTitle.trim(),
            paragraphs = paragraphs,
            targetLang = targetLang,
            engineName = engineName,
            updatedAt = now,
        )
        synchronized(lock) {
            val existing = booksByKey[key]
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
                    engineName = engineName.ifBlank { existing.engineName },
                    updatedAt = now,
                    chapters = mergedChapters,
                )
            } else {
                TranslatedBookHistory(
                    bookKey = key,
                    originalTitle = originalTitle.trim(),
                    translatedTitle = resolvedTitle,
                    targetLang = targetLang,
                    engineName = engineName,
                    updatedAt = now,
                    chapters = mergedChapters,
                )
            }
            booksByKey[key] = updated
            publishSnapshotLocked()
        }
        schedulePersist()
    }

    fun deleteBook(bookKey: String) {
        ensureLoaded()
        synchronized(lock) {
            if (booksByKey.remove(bookKey) != null) {
                publishSnapshotLocked()
            }
        }
        schedulePersist()
    }

    fun deleteChapter(bookKey: String, chapterIndex: Int) {
        ensureLoaded()
        synchronized(lock) {
            val existing = booksByKey[bookKey] ?: return
            val nextChapters = existing.chapters.filterNot { it.chapterIndex == chapterIndex }
            booksByKey[bookKey] = existing.copy(
                chapters = nextChapters,
                updatedAt = System.currentTimeMillis(),
            )
            publishSnapshotLocked()
        }
        schedulePersist()
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
