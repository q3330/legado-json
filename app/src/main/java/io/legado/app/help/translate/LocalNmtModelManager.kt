package io.legado.app.help.translate

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

enum class LocalNmtArchitecture {
    MARIAN_SPM,
    NLLB_TOKENIZER_JSON,
}

data class LocalNmtModelSpec(
    val id: String,
    val name: String,
    val summary: String,
    val repoId: String,
    val srcLang: String,
    val tgtLang: String,
    val approxSizeMb: Int,
    val architecture: LocalNmtArchitecture,
    val requiredFiles: List<String>,
)

data class LocalNmtDownloadState(
    val modelId: String,
    val downloading: Boolean = false,
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentFileName: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val error: String? = null,
)

/**
 * 开源端侧 NMT 模型（Opus-MT / NLLB-200 ONNX INT8）下载、导入与本地存储管理器。
 * 模型全部存放于外置私有目录，按需下载/导入，不打入 APK。
 */
object LocalNmtModelManager {

    private val scope = CoroutineScope(SupervisorJob() + IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _downloadStates = MutableStateFlow<Map<String, LocalNmtDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, LocalNmtDownloadState>> = _downloadStates.asStateFlow()

    private val _installedRevision = MutableStateFlow(0)
    val installedRevision: StateFlow<Int> = _installedRevision.asStateFlow()

    val catalog: List<LocalNmtModelSpec> = listOf(
        LocalNmtModelSpec(
            id = "opus-mt-en-zh",
            name = "Opus-MT 英→中 (INT8)",
            summary = "轻量化英译中开源模型，速度快、内存占用低",
            repoId = "Xenova/opus-mt-en-zh",
            srcLang = "en",
            tgtLang = "zh",
            approxSizeMb = 55,
            architecture = LocalNmtArchitecture.MARIAN_SPM,
            requiredFiles = listOf(
                "config.json",
                "vocab.json",
                "source.spm",
                "onnx/encoder_model_quantized.onnx",
                "onnx/decoder_model_quantized.onnx",
            ),
        ),
        LocalNmtModelSpec(
            id = "opus-mt-zh-en",
            name = "Opus-MT 中→英 (INT8)",
            summary = "轻量化中译英开源模型",
            repoId = "Xenova/opus-mt-zh-en",
            srcLang = "zh",
            tgtLang = "en",
            approxSizeMb = 55,
            architecture = LocalNmtArchitecture.MARIAN_SPM,
            requiredFiles = listOf(
                "config.json",
                "vocab.json",
                "source.spm",
                "onnx/encoder_model_quantized.onnx",
                "onnx/decoder_model_quantized.onnx",
            ),
        ),
        LocalNmtModelSpec(
            id = "opus-mt-ja-en",
            name = "Opus-MT 日→英 (INT8)",
            summary = "配合「英→中」可离线接力完成日→中翻译",
            repoId = "Xenova/opus-mt-ja-en",
            srcLang = "ja",
            tgtLang = "en",
            approxSizeMb = 55,
            architecture = LocalNmtArchitecture.MARIAN_SPM,
            requiredFiles = listOf(
                "config.json",
                "vocab.json",
                "source.spm",
                "onnx/encoder_model_quantized.onnx",
                "onnx/decoder_model_quantized.onnx",
            ),
        ),
        LocalNmtModelSpec(
            id = "opus-mt-ko-en",
            name = "Opus-MT 韩→英 (INT8)",
            summary = "配合「英→中」可离线接力完成韩→中翻译",
            repoId = "Xenova/opus-mt-ko-en",
            srcLang = "ko",
            tgtLang = "en",
            approxSizeMb = 55,
            architecture = LocalNmtArchitecture.MARIAN_SPM,
            requiredFiles = listOf(
                "config.json",
                "vocab.json",
                "source.spm",
                "onnx/encoder_model_quantized.onnx",
                "onnx/decoder_model_quantized.onnx",
            ),
        ),
        LocalNmtModelSpec(
            id = "nllb-200-distilled-600M",
            name = "NLLB-200 多语种直译 (INT8)",
            summary = "覆盖英/日/韩/俄/法/德/西等 200 语种直译，免接力",
            repoId = "Xenova/nllb-200-distilled-600M",
            srcLang = "*",
            tgtLang = "*",
            approxSizeMb = 890,
            architecture = LocalNmtArchitecture.NLLB_TOKENIZER_JSON,
            requiredFiles = listOf(
                "config.json",
                "tokenizer.json",
                "onnx/encoder_model_quantized.onnx",
                "onnx/decoder_model_quantized.onnx",
            ),
        ),
    )

    val modelsRoot: File by lazy {
        val base = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        File(base, "translate_models").apply { mkdirs() }
    }

    fun findSpec(modelId: String): LocalNmtModelSpec? =
        catalog.firstOrNull { it.id == modelId }

    fun modelDir(modelId: String): File = File(modelsRoot, modelId)

    fun isInstalled(modelId: String): Boolean {
        val spec = findSpec(modelId) ?: return false
        val dir = modelDir(modelId)
        if (!dir.exists()) return false
        return spec.requiredFiles.all { rel ->
            val file = File(dir, rel)
            file.isFile && file.length() > 0L
        }
    }

    fun installedSizeBytes(modelId: String): Long {
        val dir = modelDir(modelId)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun startDownload(spec: LocalNmtModelSpec) {
        if (jobs[spec.id]?.isActive == true) return
        val job = scope.launch {
            val dir = modelDir(spec.id).apply { mkdirs() }
            val mirror = TranslateConfig.nmtMirrorBaseUrl.trimEnd('/')
            val totalFiles = spec.requiredFiles.size
            try {
                spec.requiredFiles.forEachIndexed { index, relPath ->
                    val targetFile = File(dir, relPath)
                    targetFile.parentFile?.mkdirs()
                    if (targetFile.isFile && targetFile.length() > 0L) {
                        updateState(
                            LocalNmtDownloadState(
                                modelId = spec.id,
                                downloading = true,
                                currentFileIndex = index + 1,
                                totalFiles = totalFiles,
                                currentFileName = relPath.substringAfterLast('/'),
                                downloadedBytes = targetFile.length(),
                                totalBytes = targetFile.length(),
                            )
                        )
                        return@forEachIndexed
                    }

                    val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
                    if (tmpFile.exists()) tmpFile.delete()

                    val url = "$mirror/${spec.repoId}/resolve/main/$relPath"
                    updateState(
                        LocalNmtDownloadState(
                            modelId = spec.id,
                            downloading = true,
                            currentFileIndex = index + 1,
                            totalFiles = totalFiles,
                            currentFileName = relPath.substringAfterLast('/'),
                            downloadedBytes = 0L,
                            totalBytes = -1L,
                        )
                    )

                    val request = Request.Builder().url(url).build()
                    okHttpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            error("下载 $relPath 失败 (HTTP ${resp.code})")
                        }
                        val body = resp.body ?: error("响应体为空: $relPath")
                        val contentLength = body.contentLength()
                        var downloaded = 0L
                        var lastUiAt = 0L
                        body.byteStream().use { input ->
                            tmpFile.outputStream().use { output ->
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    val read = input.read(buf)
                                    if (read <= 0) break
                                    output.write(buf, 0, read)
                                    downloaded += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastUiAt >= 200L) {
                                        lastUiAt = now
                                        updateState(
                                            LocalNmtDownloadState(
                                                modelId = spec.id,
                                                downloading = true,
                                                currentFileIndex = index + 1,
                                                totalFiles = totalFiles,
                                                currentFileName = relPath.substringAfterLast('/'),
                                                downloadedBytes = downloaded,
                                                totalBytes = contentLength,
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        if (tmpFile.length() <= 0L) {
                            tmpFile.delete()
                            error("文件为空: $relPath")
                        }
                        if (targetFile.exists()) targetFile.delete()
                        if (!tmpFile.renameTo(targetFile)) {
                            tmpFile.copyTo(targetFile, overwrite = true)
                            tmpFile.delete()
                        }
                    }
                }
                _downloadStates.update { it - spec.id }
                _installedRevision.value += 1
            } catch (e: CancellationException) {
                cleanupTempFiles(dir)
                _downloadStates.update { it - spec.id }
            } catch (e: Throwable) {
                cleanupTempFiles(dir)
                AppLog.put("下载离线 NMT 模型失败(${spec.id})：${e.localizedMessage}", e)
                updateState(
                    LocalNmtDownloadState(
                        modelId = spec.id,
                        downloading = false,
                        error = e.localizedMessage ?: e.javaClass.simpleName,
                    )
                )
            } finally {
                jobs.remove(spec.id)
            }
        }
        jobs[spec.id] = job
    }

    fun cancelDownload(modelId: String) {
        jobs.remove(modelId)?.cancel()
        cleanupTempFiles(modelDir(modelId))
        _downloadStates.update { it - modelId }
    }

    fun deleteModel(modelId: String) {
        cancelDownload(modelId)
        LocalNmtTranslator.unloadModel(modelId)
        modelDir(modelId).deleteRecursively()
        _installedRevision.value += 1
    }

    fun importModelZip(context: Context, uri: Uri, spec: LocalNmtModelSpec): Result<Unit> = runCatching {
        LocalNmtTranslator.unloadModel(spec.id)
        val dir = modelDir(spec.id).apply { mkdirs() }
        val dirCanonical = dir.canonicalPath + File.separator
        val requiredSet = spec.requiredFiles.associateBy { it.substringAfterLast('/') }
        var matchedFiles = 0

        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                        val targetRel = spec.requiredFiles.firstOrNull { req ->
                            normalizedName == req || normalizedName.endsWith("/$req")
                        } ?: requiredSet[normalizedName.substringAfterLast('/')]
                        if (targetRel != null) {
                            val outFile = File(dir, targetRel)
                            if (!outFile.canonicalPath.startsWith(dirCanonical)) {
                                error("压缩包包含非法路径：${entry.name}")
                            }
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                while (true) {
                                    val len = zip.read(buf)
                                    if (len <= 0) break
                                    out.write(buf, 0, len)
                                }
                            }
                            matchedFiles++
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("无法打开所选 ZIP 文件")

        if (matchedFiles == 0 || !isInstalled(spec.id)) {
            error("ZIP 包缺少必需的模型文件，请确认包含 ${spec.requiredFiles.joinToString()}")
        }
        _installedRevision.value += 1
    }

    private fun updateState(state: LocalNmtDownloadState) {
        _downloadStates.update { it + (state.modelId to state) }
    }

    private fun cleanupTempFiles(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.forEach {
            runCatching { it.delete() }
        }
    }

    init {
        appCtx.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                    LocalNmtTranslator.unloadAll()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                LocalNmtTranslator.unloadAll()
            }
        })
    }
}
