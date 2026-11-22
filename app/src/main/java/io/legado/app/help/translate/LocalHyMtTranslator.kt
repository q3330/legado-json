package io.legado.app.help.translate

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx

/**
 * 端侧翻译大模型（GGUF / llama.cpp：Hy-MT2 / PLaMo-3 / 通用 GGUF）离线推理引擎。
 */
object LocalHyMtTranslator {

    private const val IDLE_UNLOAD_DELAY_MS = 3 * 60 * 1000L
    private const val CONTEXT_SIZE = 2048
    private val PLAMO_TAG_REGEX = Regex("<\\|plamo:[^|>]*\\|>")
    private val PLAMO_STOP_SEQS = listOf("<|plamo:tag|>", "<|plamo:bos|>")
    private val GENERIC_CHAT_STOP_SEQS = listOf("<|im_end|>", "<end_of_turn>", "<|eot_id|>")

    private val mutex = Mutex()
    private val unloadScope = CoroutineScope(SupervisorJob() + IO)
    private var idleUnloadJob: Job? = null

    @Volatile
    private var activeModelId: String? = null

    private val _loadedModelIdFlow = MutableStateFlow<String?>(null)
    val loadedModelIdFlow: StateFlow<String?> = _loadedModelIdFlow.asStateFlow()

    fun loadedModelId(): String? = activeModelId?.takeIf { HyMtNative.isLoaded() }

    fun unload() {
        HyMtNative.unloadModel()
        activeModelId = null
        _loadedModelIdFlow.value = null
    }

    fun unloadAsync() {
        idleUnloadJob?.cancel()
        unloadScope.launch {
            mutex.withLock {
                unload()
            }
        }
    }

    suspend fun translate(
        engine: TranslateEngineItem,
        texts: List<String>,
        lang: TranslateTargetLang,
        onItemTranslated: ((String, String) -> Unit)? = null,
    ): Map<String, String> {
        if (!HyMtNative.isLibraryLoaded) {
            error("端侧 GGUF 原生推理库未加载：${HyMtNative.loadError ?: "仅支持 arm64-v8a 设备"}")
        }
        val spec = resolveModelSpec(engine.localModelId)
            ?: error("未安装端侧翻译大模型，请先在「端侧大模型 (GGUF)」配置页下载或导入 GGUF")

        val tgtFamily = if (lang.code.startsWith("zh", ignoreCase = true)) {
            "zh"
        } else {
            lang.code.substringBefore('-').lowercase()
        }
        val out = LinkedHashMap<String, String>()

        mutex.withLock {
            ensureModelLoadedLocked(spec)
            val format = resolveEffectivePromptFormatLocked(spec)
            for (item in texts) {
                val srcLang = LocalLanguageDetector.detect(item)
                if (srcLang == tgtFamily) {
                    out[item] = item
                    onItemTranslated?.invoke(item, item)
                    continue
                }
                val maxTokens = (item.length * 3 + 48).coerceIn(64, 768)
                val raw = when (format) {
                    LocalLlmPromptFormat.PLAMO3_PFML3 -> {
                        val prompt = buildPlamo3Prompt(item, lang.code)
                        HyMtNative.generate(
                            prompt = prompt,
                            maxTokens = maxTokens,
                            temperature = 0.0f,
                            topP = 1.0f,
                            repeatPenalty = 1.0f,
                            rawPrompt = true,
                            stopSequences = PLAMO_STOP_SEQS,
                        )
                    }
                    LocalLlmPromptFormat.HY_MT -> {
                        val prompt = buildPrompt(item, srcLang, lang.code)
                        HyMtNative.generate(
                            prompt = prompt,
                            maxTokens = maxTokens,
                            temperature = 0.0f,
                            topP = 0.6f,
                            repeatPenalty = 1.05f,
                            rawPrompt = false,
                            stopSequences = emptyList(),
                        )
                    }
                    LocalLlmPromptFormat.GENERIC_CHAT,
                    LocalLlmPromptFormat.AUTO -> {
                        val prompt = buildPrompt(item, srcLang, lang.code)
                        HyMtNative.generate(
                            prompt = prompt,
                            maxTokens = maxTokens,
                            temperature = 0.0f,
                            topP = 0.6f,
                            repeatPenalty = 1.05f,
                            rawPrompt = false,
                            stopSequences = GENERIC_CHAT_STOP_SEQS,
                        )
                    }
                }
                val cleaned = cleanOutput(raw, format)
                if (cleaned.isEmpty() && raw.isEmpty()) {
                    val nativeErr = HyMtNative.lastError()
                    if (nativeErr.isNotBlank()) {
                        error("${spec.name} 推理失败：$nativeErr")
                    }
                }
                if (cleaned.isNotEmpty()) {
                    out[item] = cleaned
                    onItemTranslated?.invoke(item, cleaned)
                }
            }
            scheduleIdleUnloadLocked()
        }
        return out
    }

    private fun resolveModelSpec(preferredModelId: String): LocalHyMtModelSpec? {
        if (preferredModelId.isNotBlank()) {
            val preferred = LocalHyMtModelManager.findSpec(preferredModelId)
            if (preferred != null) {
                if (LocalHyMtModelManager.isInstalled(preferred.id)) {
                    return preferred
                }
                error("所选模型「${preferred.name}」尚未安装，请先在引擎配置页下载或导入 GGUF")
            }
        }
        return LocalHyMtModelManager.catalog.firstOrNull { LocalHyMtModelManager.isInstalled(it.id) }
    }

    private fun ensureModelLoadedLocked(spec: LocalHyMtModelSpec) {
        if (activeModelId == spec.id && HyMtNative.isLoaded()) return
        val file = LocalHyMtModelManager.modelFile(spec.id)
        if (!file.isFile || file.length() <= 1024L) {
            error("模型文件不存在：${spec.name}")
        }
        BookTitleTranslator.withModelLoading {
            unload()
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val ok = HyMtNative.loadModel(file.absolutePath, threads, CONTEXT_SIZE)
            if (!ok) {
                activeModelId = null
                _loadedModelIdFlow.value = null
                error("加载 ${spec.name} 模型失败：${HyMtNative.lastError().ifBlank { file.name }}")
            }
            activeModelId = spec.id
            _loadedModelIdFlow.value = spec.id
        }
    }

    private fun resolveEffectivePromptFormatLocked(spec: LocalHyMtModelSpec): LocalLlmPromptFormat {
        if (spec.promptFormat != LocalLlmPromptFormat.AUTO) return spec.promptFormat
        val arch = HyMtNative.modelArchitecture().trim().lowercase()
        return when {
            arch.startsWith("plamo") || HyMtNative.isPlamoTemplate() -> LocalLlmPromptFormat.PLAMO3_PFML3
            arch.startsWith("hunyuan") -> LocalLlmPromptFormat.HY_MT
            else -> LocalLlmPromptFormat.GENERIC_CHAT
        }
    }

    private fun buildPlamo3Prompt(text: String, targetCode: String): String {
        val safeText = text.replace("<|plamo:", "<|plamo ")
        val plamoTarget = plamoTargetCode(targetCode)
        return "<|plamo:bos|><|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input<|plamo:msg|>$safeText<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>$plamoTarget<|plamo:msg|>"
    }

    private fun plamoTargetCode(code: String): String = when (code.lowercase()) {
        "zh-tw", "zh-hant" -> "zh-hant"
        "zh-cn", "zh-hans", "zh" -> "zh-hans"
        "en" -> "en"
        "ja", "jp" -> "ja"
        "ko", "kor" -> "ko"
        else -> "zh-hans"
    }

    private fun buildPrompt(text: String, srcLang: String, targetCode: String): String {
        val involvesChinese = targetCode.startsWith("zh", ignoreCase = true) || srcLang == "zh"
        return if (involvesChinese) {
            "将以下文本翻译为${zhTargetName(targetCode)}，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"
        } else {
            "Translate the following text into ${enTargetName(targetCode)}. Note that you should only output the translated result without any additional explanation:\n\n$text"
        }
    }

    private fun zhTargetName(code: String): String = when (code.lowercase()) {
        "zh-tw", "zh-hant" -> "繁体中文"
        "zh-cn", "zh-hans", "zh" -> "中文"
        "en" -> "英语"
        "ja", "jp" -> "日语"
        "ko", "kor" -> "韩语"
        else -> "中文"
    }

    private fun enTargetName(code: String): String = when {
        code.startsWith("en", ignoreCase = true) -> "English"
        code.startsWith("ja", ignoreCase = true) -> "Japanese"
        code.startsWith("ko", ignoreCase = true) -> "Korean"
        code.equals("zh-TW", ignoreCase = true) -> "Traditional Chinese"
        code.startsWith("zh", ignoreCase = true) -> "Chinese"
        else -> "English"
    }

    private fun cleanOutput(raw: String, format: LocalLlmPromptFormat): String {
        if (format == LocalLlmPromptFormat.PLAMO3_PFML3) {
            val stopIdx = raw.indexOfAny(listOf("<|plamo:tag|>", "<|plamo:bos|>", "<|plamo:eos|>"))
            val cut = if (stopIdx >= 0) raw.substring(0, stopIdx) else raw
            return PLAMO_TAG_REGEX.replace(cut, "").trim()
        }
        return raw
            .replace("<|eos|>", "")
            .replace("<|endoftext|>", "")
            .replace("<|extra_0|>", "")
            .replace("<|startoftext|>", "")
            .replace("<|im_end|>", "")
            .replace("<end_of_turn>", "")
            .replace("<|eot_id|>", "")
            .replace("<｜hy_place▁holder▁no▁2｜>", "")
            .replace("<｜hy_place▁holder▁no▁8｜>", "")
            .replace("<｜hy_end▁of▁sentence｜>", "")
            .replace("<｜hy_EOT｜>", "")
            .replace("<｜hy_User｜>", "")
            .replace("<｜hy_Assistant｜>", "")
            .replace("<｜hy_begin▁of▁sentence｜>", "")
            .trim()
    }

    private fun scheduleIdleUnloadLocked() {
        idleUnloadJob?.cancel()
        idleUnloadJob = unloadScope.launch {
            delay(IDLE_UNLOAD_DELAY_MS)
            mutex.withLock {
                if (activeModelId != null) {
                    unload()
                }
            }
        }
    }

    init {
        appCtx.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                    unloadAsync()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                unloadAsync()
            }
        })
    }
}
