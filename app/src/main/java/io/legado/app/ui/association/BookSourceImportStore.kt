package io.legado.app.ui.association

import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.isEmptyConfiguration
import io.legado.app.utils.GSON
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile

/** 仅用于内存中的列表展示，不序列化；完整规则存于该次预览的私有临时文件。 */
internal data class BookSourceImportItem(
    val bookSourceUrl: String,
    val bookSourceName: String,
    val bookSourceComment: String?,
    val lastUpdateTime: Long,
    val emptyConfiguration: Boolean,
)

/** 单条追加/读取/编辑；默认内存直存免除重复 JSON 编解码，内存吃紧时自动惰性溢写到临时文件。 */
internal class BookSourceImportStore(private val cacheDir: File) : Closeable {
    private data class Record(val offset: Long, val size: Long)

    private val inMemorySources = arrayListOf<BookSource?>()
    private val spilledRecords = arrayListOf<Record?>()
    private var path: File? = null
    private var file: RandomAccessFile? = null
    private val writeBuffer by lazy(LazyThreadSafetyMode.NONE) { ByteArrayOutputStream(4096) }
    private var writeOffset = 0L
    private var closed = false

    @Synchronized
    fun append(source: BookSource): BookSourceImportItem {
        check(!closed)
        val index = inMemorySources.size
        if (index and 255 == 255 && isHeapTight()) {
            inMemorySources.add(null)
            spilledRecords.add(write(source))
        } else {
            inMemorySources.add(source)
            spilledRecords.add(null)
        }
        return source.previewItem()
    }

    @Synchronized
    fun replace(index: Int, source: BookSource): BookSourceImportItem {
        check(!closed)
        require(index in inMemorySources.indices)
        if (spilledRecords[index] != null || isHeapTight()) {
            inMemorySources[index] = null
            spilledRecords[index] = write(source)
        } else {
            inMemorySources[index] = source
        }
        return source.previewItem()
    }

    @Synchronized
    fun read(index: Int): BookSource {
        check(!closed)
        inMemorySources[index]?.let { return it }
        val record = spilledRecords[index] ?: error("导入预览配置读取失败")
        val raf = file ?: error("导入预览临时文件未打开")
        raf.seek(record.offset)
        val size = record.size.toInt()
        val bytes = ByteArray(size)
        raf.readFully(bytes)
        return InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8).use {
            GSON.fromJson(it, BookSource::class.java)
                ?: error("导入预览配置读取失败")
        }
    }

    @Synchronized
    fun recordSize(index: Int): Long {
        inMemorySources[index]?.let { return estimateSize(it) }
        return spilledRecords[index]?.size ?: 0L
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            file?.close()
        } finally {
            path?.delete()
            file = null
            path = null
            inMemorySources.clear()
            spilledRecords.clear()
        }
    }

    private fun isHeapTight(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMem = runtime.maxMemory()
        if (maxMem <= 0L || maxMem == Long.MAX_VALUE) return false
        val used = runtime.totalMemory() - runtime.freeMemory()
        return used * 100L / maxMem >= 75L
    }

    private fun ensureTempFile(): RandomAccessFile {
        file?.let { return it }
        val tempFile = File.createTempFile("book-source-import-", ".tmp", cacheDir)
        path = tempFile
        return RandomAccessFile(tempFile, "rw").also { file = it }
    }

    private fun write(source: BookSource): Record {
        check(!closed)
        val raf = ensureTempFile()
        writeBuffer.reset()
        OutputStreamWriter(writeBuffer, Charsets.UTF_8).use { GSON.toJson(source, it) }
        val size = writeBuffer.size().toLong()
        val start = writeOffset
        raf.seek(start)
        writeBuffer.writeTo(object : OutputStream() {
            override fun write(value: Int) = raf.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) = raf.write(bytes, offset, length)
        })
        writeOffset = start + size
        return Record(start, size)
    }

    private fun estimateSize(source: BookSource): Long {
        return 384L +
            source.bookSourceUrl.length +
            source.bookSourceName.length +
            (source.bookSourceComment?.length ?: 0) +
            (source.exploreUrl?.length ?: 0) +
            (source.searchUrl?.length ?: 0) +
            (source.mainJs?.length ?: 0) +
            (source.jsLib?.length ?: 0) +
            (source.header?.length ?: 0)
    }

    private fun BookSource.previewItem() = BookSourceImportItem(
        bookSourceUrl, bookSourceName, bookSourceComment, lastUpdateTime, isEmptyConfiguration(),
    )
}
