package io.legado.app.help.translate

import android.content.Context
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
import kotlin.coroutines.cancellation.CancellationException

enum class LocalLlmPromptFormat(val label: String) {
    HY_MT("混元协议"),
    PLAMO3_PFML3("PLaMo3 PFML3"),
    GENERIC_CHAT("通用对话模板"),
    AUTO("自动识别"),
}

data class LocalHyMtModelSpec(
    val id: String,
    val name: String,
    val summary: String,
    val repoId: String,
    val fileName: String,
    val approxSizeMb: Int,
    val downloadable: Boolean = true,
    val promptFormat: LocalLlmPromptFormat = LocalLlmPromptFormat.HY_MT,
    val badgeLabel: String = "本地 GGUF",
)

data class LocalHyMtDownloadState(
    val modelId: String,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val error: String? = null,
)

/**
 * 端侧翻译大模型（GGUF：腾讯混元 Hy-MT2 / PFN PLaMo-3 / 自定义 GGUF）下载、导入与存储管理器。
 */
object LocalHyMtModelManager {

    private val scope = CoroutineScope(SupervisorJob() + IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _downloadStates = MutableStateFlow<Map<String, LocalHyMtDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, LocalHyMtDownloadState>> = _downloadStates.asStateFlow()

    private val _installedRevision = MutableStateFlow(0)
    val installedRevision: StateFlow<Int> = _installedRevision.asStateFlow()

    val catalog: List<LocalHyMtModelSpec> = listOf(
        LocalHyMtModelSpec(
            id = "hymt2-1.8b-1.25bit",
            name = "Hy-MT2-1.8B (1.25Bit · 极速版)",
            summary = "腾讯混元 2.0 极速 1.25-bit 量化，33 语种直译，体积最小、速度最快（运行内存约 1GB）",
            repoId = "tencent/Hy-MT2-1.8B-1.25Bit-GGUF",
            fileName = "Hy-MT2-1.8B-1.25Bit.gguf",
            approxSizeMb = 441,
            downloadable = true,
            promptFormat = LocalLlmPromptFormat.HY_MT,
            badgeLabel = "混元 1.25bit",
        ),
        LocalHyMtModelSpec(
            id = "hymt2-1.8b-q4km",
            name = "Hy-MT2-1.8B (Q4_K_M · 高精度版)",
            summary = "腾讯混元 2.0 官方 4-bit 量化，译文精度高于 1.25Bit，适合质量对比（约 1.1GB）",
            repoId = "tencent/Hy-MT2-1.8B-GGUF",
            fileName = "Hy-MT2-1.8B-Q4_K_M.gguf",
            approxSizeMb = 1133,
            downloadable = true,
            promptFormat = LocalLlmPromptFormat.HY_MT,
            badgeLabel = "混元 Q4",
        ),
        LocalHyMtModelSpec(
            id = "plamo3-translate-0.8b-q4km",
            name = "PLaMo-3-Translate-0.8B (Q4_K_M · 轻量版)",
            summary = "PFN × NICT 53 语种专用翻译模型，手机端轻量快速，日/英/中互译表现优秀（约 0.57GB）",
            repoId = "pfnet/plamo-3-translate-0.8b-GGUF",
            fileName = "plamo-3-translate-0.8b-Q4_K_M.gguf",
            approxSizeMb = 584,
            downloadable = true,
            promptFormat = LocalLlmPromptFormat.PLAMO3_PFML3,
            badgeLabel = "PLaMo3 0.8B",
        ),
        LocalHyMtModelSpec(
            id = "plamo3-translate-2b-q40",
            name = "PLaMo-3-Translate-2B (Q4_0 · 手机加速版)",
            summary = "官方手机 Arm CPU 优化量化版，骁龙等移动设备推理更快，53 语种高精度互译（约 1.70GB）",
            repoId = "pfnet/plamo-3-translate-2b-GGUF",
            fileName = "plamo-3-translate-2b-Q4_0.gguf",
            approxSizeMb = 1741,
            downloadable = true,
            promptFormat = LocalLlmPromptFormat.PLAMO3_PFML3,
            badgeLabel = "PLaMo3 2B",
        ),
        LocalHyMtModelSpec(
            id = "plamo3-translate-2b-q4km",
            name = "PLaMo-3-Translate-2B (Q4_K_M · 推荐高精度)",
            summary = "PFN 官方默认推荐量化版，质量最接近原始 BF16，适合中日英多语种高质量对比（约 1.81GB）",
            repoId = "pfnet/plamo-3-translate-2b-GGUF",
            fileName = "plamo-3-translate-2b-Q4_K_M.gguf",
            approxSizeMb = 1853,
            downloadable = true,
            promptFormat = LocalLlmPromptFormat.PLAMO3_PFML3,
            badgeLabel = "PLaMo3 2B",
        ),
        LocalHyMtModelSpec(
            id = "plamo3-translate-2b-q3km",
            name = "PLaMo-3-Translate-2B (Q3_K_M · 紧凑版)",
            summary = "较小体积的 2B 量化版，精度明显优于 Q2_K，兼顾存储空间与翻译质量（约 1.50GB）",
            repoId = "pfnet/plamo-3-translate-2b-GGUF",
            fileName = "plamo-3-translate-2b-Q3_K_M.gguf",
            approxSizeMb = 1536,
            downloadable = true,
            promptFormat = LocalLlmPromptFormat.PLAMO3_PFML3,
            badgeLabel = "PLaMo3 2B",
        ),
        LocalHyMtModelSpec(
            id = "hymt-custom-gguf",
            name = "自定义本地 GGUF 模型",
            summary = "支持手动导入任意 llama.cpp 兼容 GGUF（自动识别 PLaMo-3 / Hy-MT / Qwen / Gemma 等模板）",
            repoId = "",
            fileName = "custom_hymt.gguf",
            approxSizeMb = 0,
            downloadable = false,
            promptFormat = LocalLlmPromptFormat.AUTO,
            badgeLabel = "自定义 GGUF",
        ),
    )

    val modelsRoot: File by lazy {
        val base = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        File(base, "translate_hymt_models").apply { mkdirs() }
    }

    fun findSpec(modelId: String): LocalHyMtModelSpec? =
        catalog.firstOrNull { it.id == modelId }

    fun modelFile(modelId: String): File {
        val spec = findSpec(modelId)
        val name = spec?.fileName ?: "$modelId.gguf"
        return File(modelsRoot, name)
    }

    fun isInstalled(modelId: String): Boolean {
        val file = modelFile(modelId)
        return file.isFile && file.length() > 1024L
    }

    fun installedSizeBytes(modelId: String): Long {
        val file = modelFile(modelId)
        return if (file.isFile) file.length() else 0L
    }

    fun startDownload(spec: LocalHyMtModelSpec) {
        if (!spec.downloadable || spec.repoId.isBlank()) return
        if (jobs[spec.id]?.isActive == true) return
        val job = scope.launch {
            modelsRoot.mkdirs()
            val targetFile = modelFile(spec.id)
            val tmpFile = File(modelsRoot, "${targetFile.name}.tmp")
            if (tmpFile.exists()) tmpFile.delete()
            val mirror = TranslateConfig.hyMtMirrorBaseUrl.trimEnd('/')
            val url = "$mirror/${spec.repoId}/resolve/main/${spec.fileName}"
            try {
                updateState(
                    LocalHyMtDownloadState(
                        modelId = spec.id,
                        downloading = true,
                        downloadedBytes = 0L,
                        totalBytes = -1L,
                    )
                )
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("下载 ${spec.fileName} 失败 (HTTP ${resp.code})")
                    }
                    val body = resp.body ?: error("响应体为空")
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
                                        LocalHyMtDownloadState(
                                            modelId = spec.id,
                                            downloading = true,
                                            downloadedBytes = downloaded,
                                            totalBytes = contentLength,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                if (!hasGgufMagic(tmpFile)) {
                    tmpFile.delete()
                    error("下载文件校验失败：非有效 GGUF 格式")
                }
                replaceFile(tmpFile, targetFile)
                _downloadStates.update { it - spec.id }
                _installedRevision.value += 1
            } catch (e: CancellationException) {
                tmpFile.delete()
                _downloadStates.update { it - spec.id }
            } catch (e: Throwable) {
                tmpFile.delete()
                AppLog.put("下载 Hy-MT2 模型失败(${spec.id})：${e.localizedMessage}", e)
                updateState(
                    LocalHyMtDownloadState(
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
        File(modelsRoot, "${modelFile(modelId).name}.tmp").delete()
        _downloadStates.update { it - modelId }
    }

    fun importGgufFile(context: Context, uri: Uri, spec: LocalHyMtModelSpec): Result<Unit> = runCatching {
        modelsRoot.mkdirs()
        val targetFile = modelFile(spec.id)
        val tmpFile = File(modelsRoot, "${targetFile.name}.import.tmp")
        if (tmpFile.exists()) tmpFile.delete()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val magic = ByteArray(4)
                var headerRead = 0
                while (headerRead < 4) {
                    val r = input.read(magic, headerRead, 4 - headerRead)
                    if (r < 0) break
                    headerRead += r
                }
                if (headerRead < 4 ||
                    magic[0] != 'G'.code.toByte() ||
                    magic[1] != 'G'.code.toByte() ||
                    magic[2] != 'U'.code.toByte() ||
                    magic[3] != 'F'.code.toByte()
                ) {
                    error("所选文件不是有效的 GGUF 模型文件")
                }
                tmpFile.outputStream().use { output ->
                    output.write(magic, 0, headerRead)
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            } ?: error("无法读取所选 GGUF 文件")

            if (LocalHyMtTranslator.loadedModelId() == spec.id) {
                LocalHyMtTranslator.unload()
            }
            replaceFile(tmpFile, targetFile)
            _installedRevision.value += 1
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    fun deleteModel(modelId: String) {
        cancelDownload(modelId)
        if (LocalHyMtTranslator.loadedModelId() == modelId) {
            LocalHyMtTranslator.unload()
        }
        val file = modelFile(modelId)
        if (file.exists()) file.delete()
        _installedRevision.value += 1
    }

    private fun hasGgufMagic(file: File): Boolean {
        if (!file.isFile || file.length() < 16L) return false
        return runCatching {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 &&
                    magic[0] == 'G'.code.toByte() &&
                    magic[1] == 'G'.code.toByte() &&
                    magic[2] == 'U'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    private fun replaceFile(tmpFile: File, targetFile: File) {
        if (targetFile.exists()) targetFile.delete()
        if (!tmpFile.renameTo(targetFile)) {
            tmpFile.copyTo(targetFile, overwrite = true)
            tmpFile.delete()
        }
    }

    private fun updateState(state: LocalHyMtDownloadState) {
        _downloadStates.update { it + (state.modelId to state) }
    }
}
