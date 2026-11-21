package io.legado.app.help.gsyVideo

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.removeType
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceInteractionPolicy
import io.legado.app.help.storage.BackupMediaStorage
import io.legado.app.help.translate.BookTitleTranslator
import io.legado.app.model.VideoPlay
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

enum class VideoDownloadStatus {
    IDLE,
    DOWNLOADING,
    COMPLETED,
    FAILED,
}

data class VideoDownloadProgress(
    val status: VideoDownloadStatus = VideoDownloadStatus.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val segmentCurrent: Int = 0,
    val segmentTotal: Int = 0,
    val error: String? = null,
) {
    val progressFraction: Float
        get() = when {
            status == VideoDownloadStatus.COMPLETED -> 1f
            segmentTotal > 0 -> (segmentCurrent.toFloat() / segmentTotal).coerceIn(0f, 1f)
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> 0f
        }

    /**
     * 用于目录剧集卡片的绿色进度填充比例（0f..1f）。
     * 下载刚启动或服务端未返回 Content-Length 时也会给出平滑推进的视觉进度。
     */
    val visualFraction: Float
        get() = when (status) {
            VideoDownloadStatus.COMPLETED -> 1f
            VideoDownloadStatus.DOWNLOADING -> when {
                segmentTotal > 0 -> (segmentCurrent.toFloat() / segmentTotal).coerceIn(0.08f, 0.99f)
                totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0.08f, 0.99f)
                downloadedBytes > 0L -> {
                    val est = 1f - 1f / (1f + downloadedBytes.toFloat() / (15f * 1024f * 1024f))
                    (0.08f + 0.88f * est).coerceIn(0.08f, 0.96f)
                }
                else -> 0.08f
            }
            VideoDownloadStatus.IDLE,
            VideoDownloadStatus.FAILED -> 0f
        }

    fun shortStatusText(): String? {
        return when (status) {
            VideoDownloadStatus.IDLE -> null
            VideoDownloadStatus.DOWNLOADING -> when {
                segmentTotal > 0 || totalBytes > 0L -> "${(progressFraction * 100).toInt()}%"
                downloadedBytes > 0L -> ConvertUtils.formatFileSize(downloadedBytes)
                else -> "0%"
            }
            VideoDownloadStatus.COMPLETED -> "已缓存"
            VideoDownloadStatus.FAILED -> "失败"
        }
    }

    fun formatLabel(): String {
        return when (status) {
            VideoDownloadStatus.IDLE -> "下载缓存视频"
            VideoDownloadStatus.DOWNLOADING -> {
                val sizeStr = ConvertUtils.formatFileSize(downloadedBytes)
                when {
                    segmentTotal > 0 -> {
                        val pct = (progressFraction * 100).toInt()
                        "正在缓存视频 $pct% ($segmentCurrent/$segmentTotal · $sizeStr)"
                    }
                    totalBytes > 0L -> {
                        val pct = (progressFraction * 100).toInt()
                        val totalStr = ConvertUtils.formatFileSize(totalBytes)
                        "正在缓存视频 $pct% ($sizeStr / $totalStr)"
                    }
                    else -> "正在缓存视频 ($sizeStr)"
                }
            }
            VideoDownloadStatus.COMPLETED -> "已缓存离线视频（默认全屏播放）"
            VideoDownloadStatus.FAILED -> "缓存失败，点击重试${error?.let { " ($it)" }.orEmpty()}"
        }
    }
}

object VideoDownloadCache {
    private val scope = CoroutineScope(SupervisorJob() + IO)
    private val mutableStates = MutableStateFlow<Map<String, VideoDownloadProgress>>(emptyMap())
    val downloadStates: StateFlow<Map<String, VideoDownloadProgress>> = mutableStates.asStateFlow()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private const val COMPLETED_MARKER = "completed.marker"
    private const val TARGET_NAME_FILE = "target.txt"
    private const val INDEX_PREF_NAME = "video_download_index"

    private val indexPrefs by lazy {
        appCtx.getSharedPreferences(INDEX_PREF_NAME, Context.MODE_PRIVATE)
    }

    fun cacheKey(book: Book?, chapter: BookChapter?, fallbackUrl: String?): String {
        val bookKey = book?.bookUrl ?: "single"
        val epKey = when {
            chapter != null -> "${chapter.index}_${chapter.url}"
            else -> fallbackUrl.orEmpty()
        }
        return "${MD5Utils.md5Encode(bookKey)}_${MD5Utils.md5Encode(epKey)}"
    }

    private fun legacyEpisodeDir(book: Book?, chapter: BookChapter?, fallbackUrl: String?): File {
        val bookFolder = MD5Utils.md5Encode(book?.bookUrl ?: "single")
        val epFolder = MD5Utils.md5Encode(
            when {
                chapter != null -> "${chapter.index}_${chapter.url}"
                else -> fallbackUrl.orEmpty()
            }
        )
        return File(appCtx.externalFiles, "video_cache/$bookFolder/$epFolder")
    }

    private fun translatedOrOriginal(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return BookTitleTranslator.getCached(text)?.takeIf { it.isNotBlank() } ?: text
    }

    private fun buildTranslatedVideoBaseName(book: Book?, chapter: BookChapter?, fallbackTitle: String?): String {
        val bookName = translatedOrOriginal(book?.name ?: fallbackTitle)
        val epName = translatedOrOriginal(chapter?.title)
        val combined = when {
            bookName.isNotBlank() && epName.isNotBlank() && epName != bookName -> "$bookName - $epName"
            epName.isNotBlank() -> epName
            bookName.isNotBlank() -> bookName
            else -> "video_${System.currentTimeMillis()}"
        }
        return BackupMediaStorage.sanitizeName(combined, "video")
    }

    fun getCachedVideoFile(book: Book?, chapter: BookChapter?, fallbackUrl: String?): File? {
        val key = cacheKey(book, chapter, fallbackUrl)
        indexPrefs.getString(key, null)?.let { savedPath ->
            val file = File(savedPath)
            if (file.isFile && file.length() > 0L) return file
        }
        val legacyDir = legacyEpisodeDir(book, chapter, fallbackUrl)
        val marker = File(legacyDir, COMPLETED_MARKER)
        val targetInfo = File(legacyDir, TARGET_NAME_FILE)
        if (marker.isFile && targetInfo.isFile) {
            val fileName = runCatching { targetInfo.readText().trim() }.getOrNull().orEmpty()
            if (fileName.isNotEmpty()) {
                val targetFile = File(legacyDir, fileName)
                if (targetFile.isFile && targetFile.length() > 0L) return targetFile
            }
        }
        return null
    }

    fun isCached(book: Book?, chapter: BookChapter?, fallbackUrl: String?): Boolean {
        return getCachedVideoFile(book, chapter, fallbackUrl) != null
    }

    fun syncCachedChapters(book: Book?, chapters: List<BookChapter>) {
        if (book == null || chapters.isEmpty()) return
        scope.launch {
            val cachedUpdates = HashMap<String, VideoDownloadProgress>()
            chapters.forEach { chapter ->
                if (!chapter.isVolume) {
                    val key = cacheKey(book, chapter, null)
                    val cur = mutableStates.value[key]
                    if (cur == null && isCached(book, chapter, null)) {
                        cachedUpdates[key] = VideoDownloadProgress(status = VideoDownloadStatus.COMPLETED)
                    }
                }
            }
            if (cachedUpdates.isNotEmpty()) {
                mutableStates.update { current -> current + cachedUpdates }
            }
        }
    }

    fun startOrCancelDownload(
        source: BaseSource?,
        book: Book?,
        chapter: BookChapter?,
        currentVideoUrl: String?,
        onAddedToShelf: () -> Unit = {},
    ) {
        val key = cacheKey(book, chapter, currentVideoUrl)
        val existingJob = activeJobs.remove(key)
        if (existingJob?.isActive == true) {
            existingJob.cancel()
            mutableStates.update { it - key }
            appCtx.toastOnUi("已取消视频缓存")
            return
        }

        // 缓存视频自动加入书架
        if (book != null) {
            scope.launch {
                ensureBookInShelf(book, VideoPlay.toc)
            }
            VideoPlay.inBookshelf = true
            onAddedToShelf()
        }

        updateProgress(
            key,
            VideoDownloadProgress(status = VideoDownloadStatus.DOWNLOADING),
        )
        appCtx.toastOnUi("开始缓存视频，已自动加入书架")

        val job = scope.launch {
            try {
                val rawUrl = resolveRawVideoUrl(source, book, chapter, currentVideoUrl)
                val analyzeUrl = AnalyzeUrl(
                    rawUrl,
                    source = source,
                    ruleData = book,
                    chapter = chapter,
                )
                val playUrl = analyzeUrl.url
                if (playUrl.startsWith("file://", ignoreCase = true)) {
                    updateProgress(
                        key,
                        VideoDownloadProgress(status = VideoDownloadStatus.COMPLETED),
                    )
                    return@launch
                }
                val headers = analyzeUrl.headerMap
                val videoRoot = BackupMediaStorage.videoRootDir()
                val baseName = buildTranslatedVideoBaseName(book, chapter, VideoPlay.videoTitle)

                val savedFile = if (isM3u8Url(playUrl)) {
                    val m3u8Dir = File(videoRoot, baseName)
                    FileUtils.delete(m3u8Dir)
                    m3u8Dir.mkdirs()
                    downloadM3u8Stream(key, playUrl, headers, m3u8Dir, baseName)
                } else {
                    downloadDirectVideo(key, playUrl, headers, videoRoot, baseName)
                }
                indexPrefs.edit { putString(key, savedFile.absolutePath) }
                updateProgress(
                    key,
                    VideoDownloadProgress(status = VideoDownloadStatus.COMPLETED),
                )
                appCtx.toastOnUi("视频缓存完成：${savedFile.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("缓存视频失败: ${e.localizedMessage}", e)
                updateProgress(
                    key,
                    VideoDownloadProgress(
                        status = VideoDownloadStatus.FAILED,
                        error = e.localizedMessage ?: "网络错误",
                    ),
                )
                appCtx.toastOnUi("缓存视频失败：${e.localizedMessage ?: "未知错误"}")
            } finally {
                activeJobs.remove(key)
            }
        }
        activeJobs[key] = job
    }

    private fun ensureBookInShelf(book: Book, toc: List<BookChapter>?) {
        runCatching {
            book.removeType(BookType.notShelf)
            val existing = appDb.bookDao.getBook(book.bookUrl)
            if (existing == null) {
                appDb.bookDao.insert(book)
            } else {
                existing.removeType(BookType.notShelf)
                appDb.bookDao.update(existing)
            }
            if (!toc.isNullOrEmpty()) {
                appDb.bookChapterDao.insert(*toc.toTypedArray())
            }
        }
    }

    private suspend fun resolveRawVideoUrl(
        source: BaseSource?,
        book: Book?,
        chapter: BookChapter?,
        currentVideoUrl: String?,
    ): String {
        val isCurrentPlayingChapter = chapter == null ||
            (VideoPlay.chapter?.url == chapter.url && VideoPlay.chapter?.index == chapter.index)
        val current = currentVideoUrl?.takeIf {
            isCurrentPlayingChapter && it.isNotBlank() && !it.startsWith("file://", ignoreCase = true)
        }
        if (current != null) return current
        if (book != null && chapter != null && source is BookSource) {
            val interactionPolicy = SourceInteractionPolicy(
                blockDialogs = false,
                blockMediaLaunches = true,
                throwOnBlocked = false,
                blockBrowserOnly = true,
            )
            val rawContent = kotlinx.coroutines.withContext(IO + interactionPolicy) {
                runCatching {
                    WebBook.getContentAwait(
                        bookSource = source,
                        book = book,
                        bookChapter = chapter,
                    )
                }.getOrDefault("")
            }
            val resolved = VideoPlay.resolvePlayableVideoUrl(
                rawContent = rawContent,
                capturedBrowserUrl = interactionPolicy.blockedRequest?.url,
            )
            if (resolved.isNotBlank()) return resolved
            if (chapter.url.startsWith("http://", true) || chapter.url.startsWith("https://", true)) {
                return chapter.url
            }
        }
        if (chapter != null && (chapter.url.startsWith("http://", true) || chapter.url.startsWith("https://", true))) {
            return chapter.url
        }
        throw IOException("未获取到视频播放地址")
    }

    private fun isM3u8Url(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".m3u8")
    }

    private suspend fun downloadDirectVideo(
        key: String,
        playUrl: String,
        headers: Map<String, String>,
        videoRoot: File,
        baseName: String,
    ): File {
        val requestBuilder = Request.Builder().url(playUrl)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.contains("mpegurl") || contentType.contains("m3u8")) {
                val bodyText = response.body.string()
                val m3u8Dir = File(videoRoot, baseName).apply {
                    FileUtils.delete(this)
                    mkdirs()
                }
                return downloadM3u8Content(key, playUrl, bodyText, headers, m3u8Dir, baseName)
            }
            val body = response.body
            val total = body.contentLength()
            val ext = guessVideoExtension(playUrl, contentType)
            val targetName = "$baseName.$ext"
            val partFile = File(videoRoot, ".$targetName.part")
            val targetFile = File(videoRoot, targetName)
            var downloaded = 0L
            var lastEmit = 0L

            body.byteStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                val firstRead = input.read(buffer)
                if (firstRead <= 0) {
                    throw IOException("下载内容为空")
                }
                val prefixSample = String(buffer, 0, minOf(firstRead, 256), Charsets.UTF_8).trimStart()
                if (prefixSample.startsWith("#EXTM3U", ignoreCase = true)) {
                    val out = java.io.ByteArrayOutputStream()
                    out.write(buffer, 0, firstRead)
                    while (true) {
                        coroutineContext.ensureActive()
                        val r = input.read(buffer)
                        if (r == -1) break
                        out.write(buffer, 0, r)
                    }
                    val playlistText = out.toString(Charsets.UTF_8.name())
                    val m3u8Dir = File(videoRoot, baseName).apply {
                        FileUtils.delete(this)
                        mkdirs()
                    }
                    return downloadM3u8Content(key, playUrl, playlistText, headers, m3u8Dir, baseName)
                }

                FileOutputStream(partFile).use { output ->
                    output.write(buffer, 0, firstRead)
                    downloaded += firstRead
                    updateProgress(
                        key,
                        VideoDownloadProgress(
                            status = VideoDownloadStatus.DOWNLOADING,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                        ),
                    )
                    lastEmit = System.currentTimeMillis()
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 160L) {
                            lastEmit = now
                            updateProgress(
                                key,
                                VideoDownloadProgress(
                                    status = VideoDownloadStatus.DOWNLOADING,
                                    downloadedBytes = downloaded,
                                    totalBytes = total,
                                ),
                            )
                        }
                    }
                    output.flush()
                }
            }
            if (downloaded <= 0L) {
                partFile.delete()
                throw IOException("下载内容为空")
            }
            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                throw IOException("保存缓存文件失败")
            }
            BackupMediaStorage.mirrorToSafBackupIfNeeded(
                sourceFile = targetFile,
                subDirs = listOf(BackupMediaStorage.VIDEO_FOLDER),
                fileName = targetName,
            )
            return targetFile
        }
    }

    private suspend fun downloadM3u8Stream(
        key: String,
        playlistUrl: String,
        headers: Map<String, String>,
        dir: File,
        baseName: String,
    ): File {
        val text = fetchText(playlistUrl, headers)
        return downloadM3u8Content(key, playlistUrl, text, headers, dir, baseName)
    }

    private suspend fun downloadM3u8Content(
        key: String,
        playlistUrl: String,
        playlistText: String,
        headers: Map<String, String>,
        dir: File,
        baseName: String,
    ): File {
        var baseUrl = playlistUrl
        var lines = playlistText.lines()

        // 如果是 Master Playlist (#EXT-X-STREAM-INF)，解析子 M3U8
        if (lines.any { it.trim().startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
            val variantLine = lines
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: throw IOException("未找到有效 M3U8 子流")
            baseUrl = NetworkUtils.getAbsoluteURL(playlistUrl, variantLine)
            lines = fetchText(baseUrl, headers).lines()
        }

        val segmentUrls = lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (segmentUrls.isEmpty()) {
            throw IOException("M3U8 分片列表为空")
        }

        var downloadedBytes = 0L
        var segIndex = 0
        val totalSegments = segmentUrls.size
        updateProgress(
            key,
            VideoDownloadProgress(
                status = VideoDownloadStatus.DOWNLOADING,
                downloadedBytes = 0L,
                segmentCurrent = 0,
                segmentTotal = totalSegments,
            ),
        )
        val rewrittenLines = ArrayList<String>(lines.size)
        val uriRegex = Regex("""URI="([^"]+)"""")

        for (rawLine in lines) {
            coroutineContext.ensureActive()
            val trimmed = rawLine.trim()
            when {
                trimmed.isEmpty() -> rewrittenLines.add(rawLine)
                trimmed.startsWith("#") -> {
                    val match = uriRegex.find(trimmed)
                    if (match != null) {
                        val refUrl = NetworkUtils.getAbsoluteURL(baseUrl, match.groupValues[1])
                        val keyFileName = "key_${MD5Utils.md5Encode(refUrl)}.bin"
                        val keyFile = File(dir, keyFileName)
                        if (!keyFile.exists()) {
                            downloadedBytes += downloadSmallFile(refUrl, headers, keyFile)
                        }
                        val localUri = Uri.fromFile(keyFile).toString()
                        rewrittenLines.add(trimmed.replaceRange(match.range, """URI="$localUri""""))
                    } else {
                        rewrittenLines.add(trimmed)
                    }
                }
                else -> {
                    val segUrl = NetworkUtils.getAbsoluteURL(baseUrl, trimmed)
                    val segFileName = "seg_%05d.ts".format(segIndex)
                    val segFile = File(dir, segFileName)
                    downloadedBytes += downloadSmallFile(segUrl, headers, segFile)
                    segIndex++
                    updateProgress(
                        key,
                        VideoDownloadProgress(
                            status = VideoDownloadStatus.DOWNLOADING,
                            downloadedBytes = downloadedBytes,
                            segmentCurrent = segIndex,
                            segmentTotal = totalSegments,
                        ),
                    )
                    rewrittenLines.add(Uri.fromFile(segFile).toString())
                }
            }
        }

        val indexFileName = "$baseName.m3u8"
        val indexFile = File(dir, indexFileName)
        indexFile.writeText(rewrittenLines.joinToString("\n"))
        File(dir, TARGET_NAME_FILE).writeText(indexFileName)
        File(dir, COMPLETED_MARKER).writeText(System.currentTimeMillis().toString())
        BackupMediaStorage.mirrorToSafBackupIfNeeded(
            sourceFile = indexFile,
            subDirs = listOf(BackupMediaStorage.VIDEO_FOLDER, baseName),
            fileName = indexFileName,
        )
        return indexFile
    }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body.string()
        }
    }

    private fun downloadSmallFile(url: String, headers: Map<String, String>, target: File): Long {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("分片下载失败 HTTP ${response.code}")
            var size = 0L
            response.body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        size += read
                    }
                    output.flush()
                }
            }
            return size
        }
    }

    private fun guessVideoExtension(url: String, contentType: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".mkv") || contentType.contains("matroska") -> "mkv"
            path.endsWith(".webm") || contentType.contains("webm") -> "webm"
            path.endsWith(".flv") || contentType.contains("flv") -> "flv"
            path.endsWith(".mov") || contentType.contains("quicktime") -> "mov"
            path.endsWith(".ts") -> "ts"
            else -> "mp4"
        }
    }

    private fun updateProgress(key: String, progress: VideoDownloadProgress) {
        mutableStates.update { current ->
            current + (key to progress)
        }
    }
}
