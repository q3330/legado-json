package io.legado.app.help.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * 开源端侧 NMT 推理引擎（ONNX Runtime + Opus-MT / NLLB-200 INT8）。
 * 支持按需加载、双跳接力（例如 日→英→中）、多语种直译，并在空闲 3 分钟或低内存时自动卸载模型释放内存。
 */
object LocalNmtTranslator {

    private const val IDLE_UNLOAD_DELAY_MS = 3 * 60 * 1000L

    private class LoadedModel(
        val spec: LocalNmtModelSpec,
        val config: LocalNmtConfig,
        val tokenizer: LocalNmtTokenizer,
        val encoderSession: OrtSession,
        val decoderSession: OrtSession,
    ) {
        fun close() {
            runCatching { encoderSession.close() }
            runCatching { decoderSession.close() }
        }
    }

    private sealed interface NmtRoute {
        data class Direct(val model: LocalNmtModelSpec) : NmtRoute
        data class Pivot(val first: LocalNmtModelSpec, val second: LocalNmtModelSpec) : NmtRoute
    }

    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val mutex = Mutex()
    private val loadedModels = ConcurrentHashMap<String, LoadedModel>()
    private val unloadScope = CoroutineScope(SupervisorJob() + IO)
    private var idleUnloadJob: Job? = null

    fun loadedModelIds(): Set<String> = loadedModels.keys.toSet()

    fun unloadModel(modelId: String) {
        loadedModels.remove(modelId)?.close()
    }

    fun unloadAll() {
        val keys = loadedModels.keys.toList()
        keys.forEach { key ->
            loadedModels.remove(key)?.close()
        }
    }

    fun unloadAllAsync() {
        idleUnloadJob?.cancel()
        unloadScope.launch {
            mutex.withLock {
                if (loadedModels.isNotEmpty()) {
                    unloadAll()
                }
            }
        }
    }

    suspend fun translate(
        engine: TranslateEngineItem,
        texts: List<String>,
        lang: TranslateTargetLang,
        onItemTranslated: ((String, String) -> Unit)? = null,
    ): Map<String, String> {
        val tgtBase = if (lang.code.startsWith("zh", ignoreCase = true)) {
            lang.code
        } else {
            lang.code.substringBefore('-')
        }
        val tgtFamily = if (tgtBase.startsWith("zh", ignoreCase = true)) "zh" else tgtBase.lowercase()
        val out = LinkedHashMap<String, String>()

        mutex.withLock {
            for (item in texts) {
                val srcLang = LocalLanguageDetector.detect(item)
                if (srcLang == tgtFamily) {
                    out[item] = item
                    onItemTranslated?.invoke(item, item)
                    continue
                }
                val route = resolveRoute(engine.localModelId, srcLang, tgtFamily)
                    ?: error("未安装适配 ($srcLang→$tgtFamily) 的开源 NMT 模型，请先在「开源端侧 NMT」配置页下载或导入")
                val translated = when (route) {
                    is NmtRoute.Direct -> {
                        val loaded = getOrLoadLocked(route.model)
                        runInference(loaded, item, srcLang, tgtBase)
                    }
                    is NmtRoute.Pivot -> {
                        val mid = runInference(getOrLoadLocked(route.first), item, srcLang, route.first.tgtLang)
                        if (mid.isBlank()) "" else runInference(getOrLoadLocked(route.second), mid, route.first.tgtLang, tgtBase)
                    }
                }
                if (translated.isNotBlank()) {
                    out[item] = translated
                    onItemTranslated?.invoke(item, translated)
                }
            }
            scheduleIdleUnloadLocked()
        }
        return out
    }

    private fun resolveRoute(
        preferredModelId: String,
        srcLang: String,
        tgtFamily: String,
    ): NmtRoute? {
        if (preferredModelId.isNotBlank()) {
            val spec = LocalNmtModelManager.findSpec(preferredModelId)
            if (spec != null && LocalNmtModelManager.isInstalled(spec.id)) {
                return NmtRoute.Direct(spec)
            }
        }
        val directOpus = LocalNmtModelManager.catalog.firstOrNull { spec ->
            spec.srcLang == srcLang && spec.tgtLang == tgtFamily && LocalNmtModelManager.isInstalled(spec.id)
        }
        if (directOpus != null) return NmtRoute.Direct(directOpus)

        val nllb = LocalNmtModelManager.findSpec("nllb-200-distilled-600M")
        if (nllb != null && LocalNmtModelManager.isInstalled(nllb.id)) {
            return NmtRoute.Direct(nllb)
        }

        if (srcLang != "en" && tgtFamily != "en") {
            val firstHop = LocalNmtModelManager.catalog.firstOrNull {
                it.srcLang == srcLang && it.tgtLang == "en" && LocalNmtModelManager.isInstalled(it.id)
            }
            val secondHop = LocalNmtModelManager.catalog.firstOrNull {
                it.srcLang == "en" && it.tgtLang == tgtFamily && LocalNmtModelManager.isInstalled(it.id)
            }
            if (firstHop != null && secondHop != null) {
                return NmtRoute.Pivot(firstHop, secondHop)
            }
        }
        return null
    }

    private fun getOrLoadLocked(spec: LocalNmtModelSpec): LoadedModel {
        loadedModels[spec.id]?.let { return it }
        val dir = LocalNmtModelManager.modelDir(spec.id)
        if (!LocalNmtModelManager.isInstalled(spec.id)) {
            error("离线 NMT 模型尚未安装：${spec.name}")
        }
        return BookTitleTranslator.withModelLoading {
            // 单模型常驻，防止多个 ONNX Session 叠加导致内存溢出
            unloadAll()

            val config = parseLocalNmtConfig(File(dir, "config.json"))
            val tokenizer = buildLocalNmtTokenizer(spec, dir, config)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val encoderSession = options.use { opt ->
                env.createSession(File(dir, "onnx/encoder_model_quantized.onnx").absolutePath, opt)
            }
            val decoderOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val decoderSession = decoderOptions.use { opt ->
                env.createSession(File(dir, "onnx/decoder_model_quantized.onnx").absolutePath, opt)
            }
            LoadedModel(spec, config, tokenizer, encoderSession, decoderSession).also {
                loadedModels[spec.id] = it
            }
        }
    }

    private fun runInference(
        loaded: LoadedModel,
        text: String,
        srcLang: String,
        tgtLang: String,
    ): String {
        val inputIds = loaded.tokenizer.encode(text, srcLang)
        if (inputIds.isEmpty()) return ""
        val seqLen = inputIds.size
        val attentionMask = LongArray(seqLen) { 1L }

        var encInputTensor: OnnxTensor? = null
        var encMaskTensor: OnnxTensor? = null
        var encoderResult: OrtSession.Result? = null

        try {
            val shape = longArrayOf(1L, seqLen.toLong())
            encInputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape)
            encMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape)
            val encInputs = buildMap<String, OnnxTensor> {
                put("input_ids", encInputTensor)
                if ("attention_mask" in loaded.encoderSession.inputNames) {
                    put("attention_mask", encMaskTensor)
                }
            }
            encoderResult = loaded.encoderSession.run(encInputs)
            val encoderHiddenTensor = encoderResult[0] as OnnxTensor
            val decInputNames = loaded.decoderSession.inputNames

            val decoderPrefix = loaded.tokenizer.initialDecoderIds(tgtLang)
            val generated = decoderPrefix.toMutableList()
            val maxNewTokens = (seqLen * 3 + 24).coerceIn(32, 160)
            var lastToken = -1L
            var repeatCount = 0

            for (step in 0 until maxNewTokens) {
                val decArr = generated.toLongArray()
                var decIdsTensor: OnnxTensor? = null
                var decResult: OrtSession.Result? = null
                try {
                    decIdsTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(decArr),
                        longArrayOf(1L, decArr.size.toLong()),
                    )
                    val decInputs = HashMap<String, OnnxTensor>(3)
                    decInputs["input_ids"] = decIdsTensor
                    if ("encoder_hidden_states" in decInputNames) {
                        decInputs["encoder_hidden_states"] = encoderHiddenTensor
                    }
                    if ("encoder_attention_mask" in decInputNames) {
                        decInputs["encoder_attention_mask"] = encMaskTensor
                    }
                    decResult = loaded.decoderSession.run(decInputs)
                    val logitsTensor = decResult[0] as OnnxTensor
                    val logitsShape = logitsTensor.info.shape
                    val curLen = logitsShape[1].toInt()
                    val vocabSize = logitsShape[2].toInt()
                    val offset = (curLen - 1) * vocabSize
                    val fb = logitsTensor.floatBuffer

                    var bestId = 0L
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (v in 0 until vocabSize) {
                        val score = fb.get(offset + v)
                        if (score > bestScore) {
                            bestScore = score
                            bestId = v.toLong()
                        }
                    }

                    if (bestId == loaded.config.eosTokenId) break
                    if (bestId == lastToken) {
                        repeatCount++
                        if (repeatCount >= 5) break
                    } else {
                        lastToken = bestId
                        repeatCount = 0
                    }
                    generated.add(bestId)
                } finally {
                    decResult?.close()
                    decIdsTensor?.close()
                }
            }

            val bodyIds = if (generated.size > decoderPrefix.size) {
                generated.subList(decoderPrefix.size, generated.size)
            } else {
                emptyList()
            }
            return loaded.tokenizer.decode(bodyIds)
        } finally {
            encoderResult?.close()
            encMaskTensor?.close()
            encInputTensor?.close()
        }
    }

    private fun scheduleIdleUnloadLocked() {
        idleUnloadJob?.cancel()
        idleUnloadJob = unloadScope.launch {
            delay(IDLE_UNLOAD_DELAY_MS)
            mutex.withLock {
                if (loadedModels.isNotEmpty()) {
                    unloadAll()
                }
            }
        }
    }
}
