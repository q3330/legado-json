package io.legado.app.help.translate

import io.legado.app.constant.AppLog
import java.io.ByteArrayOutputStream

internal object HyMtNative {
    var isLibraryLoaded: Boolean = false
        private set
    var loadError: String? = null
        private set

    init {
        runCatching {
            System.loadLibrary("hymt_jni")
        }.onSuccess {
            isLibraryLoaded = true
        }.onFailure { err ->
            loadError = err.localizedMessage ?: err.javaClass.simpleName
            AppLog.put("加载 hymt_jni 原生库失败：${loadError}", err)
        }
    }

    fun loadModel(modelPath: String, threads: Int, ctxSize: Int): Boolean {
        if (!isLibraryLoaded) return false
        return nativeLoadModel(modelPath, threads, ctxSize)
    }

    fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        rawPrompt: Boolean = false,
        stopSequences: List<String> = emptyList(),
    ): String {
        if (!isLibraryLoaded) return ""
        val stopBytes = if (stopSequences.isEmpty()) {
            null
        } else {
            val out = ByteArrayOutputStream()
            stopSequences.forEachIndexed { idx, s ->
                if (idx > 0) out.write(0)
                out.write(s.toByteArray(Charsets.UTF_8))
            }
            out.toByteArray()
        }
        val outBytes = nativeGenerateUtf8(
            prompt.toByteArray(Charsets.UTF_8),
            maxTokens,
            temperature,
            topP,
            repeatPenalty,
            rawPrompt,
            stopBytes,
        ) ?: return ""
        return outBytes.toString(Charsets.UTF_8)
    }

    fun unloadModel() {
        if (isLibraryLoaded) nativeUnloadModel()
    }

    fun isLoaded(): Boolean = isLibraryLoaded && nativeIsLoaded()

    fun modelArchitecture(): String {
        if (!isLibraryLoaded) return ""
        return nativeModelArch()?.toString(Charsets.UTF_8).orEmpty()
    }

    fun isPlamoTemplate(): Boolean = isLibraryLoaded && nativeIsPlamoTemplate()

    fun lastError(): String {
        if (!isLibraryLoaded) return loadError ?: "Native library not loaded"
        val bytes = nativeLastError() ?: return ""
        return bytes.toString(Charsets.UTF_8)
    }

    private external fun nativeLoadModel(
        modelPath: String,
        threads: Int,
        ctxSize: Int,
    ): Boolean

    private external fun nativeGenerateUtf8(
        promptUtf8: ByteArray,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        rawPrompt: Boolean,
        stopSeqsUtf8: ByteArray?,
    ): ByteArray?

    private external fun nativeUnloadModel()

    private external fun nativeIsLoaded(): Boolean

    private external fun nativeModelArch(): ByteArray?

    private external fun nativeIsPlamoTemplate(): Boolean

    private external fun nativeLastError(): ByteArray?
}
