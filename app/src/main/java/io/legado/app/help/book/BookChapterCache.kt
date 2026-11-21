package io.legado.app.help.book

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

data class ChapterImageCacheProgress(
    val cached: Int = 0,
    val total: Int = 0,
    val downloading: Boolean = false,
) {
    val fraction: Float
        get() = when {
            total <= 0 -> 0f
            cached >= total -> 1f
            else -> (cached.toFloat() / total).coerceIn(0f, 1f)
        }

    val visualFraction: Float
        get() = when {
            total <= 0 -> if (downloading) 0.08f else 0f
            cached >= total -> 1f
            cached > 0 -> (cached.toFloat() / total).coerceIn(0.08f, 0.99f)
            downloading -> 0.08f
            else -> 0f
        }

    val isCompleted: Boolean
        get() = total > 0 && cached >= total
}

object MangaChapterCacheTracker {
    private val scope = CoroutineScope(SupervisorJob() + IO)
    private val mutableStates = MutableStateFlow<Map<String, ChapterImageCacheProgress>>(emptyMap())
    val states: StateFlow<Map<String, ChapterImageCacheProgress>> = mutableStates.asStateFlow()

    fun key(bookUrl: String, chapterIndex: Int): String = "$bookUrl|$chapterIndex"

    fun update(bookUrl: String, chapterIndex: Int, cached: Int, total: Int, downloading: Boolean) {
        val k = key(bookUrl, chapterIndex)
        mutableStates.update { cur ->
            cur + (k to ChapterImageCacheProgress(cached = cached.coerceAtLeast(0), total = total.coerceAtLeast(0), downloading = downloading))
        }
    }

    fun syncBookChapters(book: Book, chapters: List<BookChapter>) {
        if (!book.isImage || chapters.isEmpty()) return
        scope.launch {
            val updates = HashMap<String, ChapterImageCacheProgress>()
            chapters.forEach { chapter ->
                if (chapter.isVolume) return@forEach
                val k = key(book.bookUrl, chapter.index)
                if (mutableStates.value[k]?.downloading == true) return@forEach
                val content = BookHelp.getContent(book, chapter) ?: return@forEach
                val matcher = AppPattern.imgPattern.matcher(content)
                var total = 0
                var cached = 0
                while (matcher.find()) {
                    val src = matcher.group(1) ?: continue
                    val absUrl = NetworkUtils.getAbsoluteURL(chapter.url, src)
                    BookHelp.registerImageChapter(book, chapter, absUrl)
                    total++
                    if (BookHelp.isImageExist(book, absUrl, chapter)) {
                        cached++
                    }
                }
                if (total > 0 && cached > 0) {
                    updates[k] = ChapterImageCacheProgress(cached = cached, total = total, downloading = false)
                }
            }
            if (updates.isNotEmpty()) {
                mutableStates.update { cur -> cur + updates }
            }
        }
    }
}

/** 漫画的旧正文没有图片时，需要重新获取正文，而不是直接跳过或只补图。 */
internal fun hasUsableChapterCacheContent(book: Book, content: String?): Boolean {
    if (content.isNullOrEmpty()) return false
    return !book.isImage || AppPattern.imgPattern.matcher(content).find()
}

/** 只读检查，与下载和统计使用相同的绝对图片地址及有效性判定。 */
internal fun hasCompleteChapterImages(
    book: Book,
    chapter: BookChapter,
    content: String?,
): Boolean {
    if (!hasUsableChapterCacheContent(book, content)) return false
    val matcher = AppPattern.imgPattern.matcher(content!!)
    while (matcher.find()) {
        val src = NetworkUtils.getAbsoluteURL(chapter.url, matcher.group(1)!!)
        BookHelp.registerImageChapter(book, chapter, src)
        if (getCachedBookImage(book, src, chapter) == null) return false
    }
    return true
}

/** 仅供后台缓存队列使用；普通阅读保存正文的时序及图片容错保持不变。 */
internal suspend fun completeChapterCache(
    bookSource: BookSource,
    book: Book,
    chapter: BookChapter,
    content: String,
) {
    currentCoroutineContext().ensureActive()
    if (!hasUsableChapterCacheContent(book, content)) {
        throw IOException(if (book.isImage) "漫画正文没有可缓存的图片" else "章节正文为空")
    }
    val urls = buildList {
        val matcher = AppPattern.imgPattern.matcher(content)
        while (matcher.find()) {
            val raw = matcher.group(1) ?: continue
            val absUrl = NetworkUtils.getAbsoluteURL(chapter.url, raw)
            BookHelp.registerImageChapter(book, chapter, absUrl)
            add(absUrl)
        }
    }
    val total = urls.size
    var cachedCount = if (book.isImage && total > 0) {
        urls.count { getCachedBookImage(book, it, chapter) != null }
    } else 0
    if (book.isImage && total > 0) {
        MangaChapterCacheTracker.update(book.bookUrl, chapter.index, cachedCount, total, downloading = true)
    }
    try {
        // 一章内顺序补图，沿用缓存队列原有的图片并发度。
        for (src in urls) {
            currentCoroutineContext().ensureActive()
            val alreadyCached = book.isImage && getCachedBookImage(book, src, chapter) != null
            BookHelp.saveImageForCache(bookSource, book, src, chapter)
            if (book.isImage && total > 0 && !alreadyCached) {
                cachedCount++
                MangaChapterCacheTracker.update(
                    book.bookUrl,
                    chapter.index,
                    cachedCount.coerceAtMost(total),
                    total,
                    downloading = cachedCount < total,
                )
            }
        }
        currentCoroutineContext().ensureActive()
        val file = File(File(BookHelp.cachePath, book.getFolderName()), chapter.getFileName())
        if (!file.isFile || file.readText() != content) {
            throw IOException("章节正文未完整保存")
        }
        if (!hasCompleteChapterImages(book, chapter, content)) {
            throw IOException("章节图片缓存不完整")
        }
        if (book.isImage && total > 0) {
            MangaChapterCacheTracker.update(book.bookUrl, chapter.index, total, total, downloading = false)
        }
        currentCoroutineContext().ensureActive()
    } catch (e: Throwable) {
        if (book.isImage && total > 0) {
            MangaChapterCacheTracker.update(book.bookUrl, chapter.index, cachedCount, total, downloading = false)
        }
        throw e
    }
}
