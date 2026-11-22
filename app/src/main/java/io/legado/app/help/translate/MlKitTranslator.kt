package io.legado.app.help.translate

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import io.legado.app.constant.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class MlKitLangOption(
    val code: String,
    val label: String,
)

/**
 * Google ML Kit 端侧离线翻译备选引擎（内置独立 SDK，无需设备上预装 Google 服务即可本地推理；
 * 首次拉取 ~30MB 语种模型需可访问 Google 模型分发网络）。
 */
object MlKitTranslator {

    private const val IDLE_CLOSE_DELAY_MS = 3 * 60 * 1000L

    val supportedLanguages: List<MlKitLangOption> = listOf(
        MlKitLangOption(TranslateLanguage.CHINESE, "中文 (zh)"),
        MlKitLangOption(TranslateLanguage.ENGLISH, "英语 (en)"),
        MlKitLangOption(TranslateLanguage.JAPANESE, "日语 (ja)"),
        MlKitLangOption(TranslateLanguage.KOREAN, "韩语 (ko)"),
        MlKitLangOption(TranslateLanguage.RUSSIAN, "俄语 (ru)"),
        MlKitLangOption(TranslateLanguage.FRENCH, "法语 (fr)"),
        MlKitLangOption(TranslateLanguage.GERMAN, "德语 (de)"),
        MlKitLangOption(TranslateLanguage.SPANISH, "西班牙语 (es)"),
    )

    private val mutex = Mutex()
    private val clients = ConcurrentHashMap<String, Translator>()
    private val scope = CoroutineScope(SupervisorJob() + IO)
    private var idleCloseJob: Job? = null

    private val _downloadedLanguages = MutableStateFlow<Set<String>>(emptySet())
    val downloadedLanguagesFlow: StateFlow<Set<String>> = _downloadedLanguages.asStateFlow()

    private val _downloadingLanguages = MutableStateFlow<Set<String>>(emptySet())
    val downloadingLanguagesFlow: StateFlow<Set<String>> = _downloadingLanguages.asStateFlow()

    suspend fun refreshDownloadedModels(): Set<String> {
        val codes = runCatching {
            val models = RemoteModelManager.getInstance()
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .awaitResult()
            models.mapTo(HashSet()) { it.language }
        }.getOrDefault(emptySet())
        _downloadedLanguages.value = codes
        return codes
    }

    suspend fun downloadLanguage(langCode: String, wifiOnly: Boolean = false): Result<Unit> = runCatching {
        val mlkitCode = toMlKitLangCode(langCode)
        _downloadingLanguages.update { it + mlkitCode }
        try {
            val model = TranslateRemoteModel.Builder(mlkitCode).build()
            val conditions = DownloadConditions.Builder()
                .apply { if (wifiOnly) requireWifi() }
                .build()
            RemoteModelManager.getInstance().download(model, conditions).awaitResult()
            refreshDownloadedModels()
        } catch (e: Throwable) {
            AppLog.put("下载 ML Kit 语言包失败($mlkitCode)：${e.localizedMessage}", e)
            throw e
        } finally {
            _downloadingLanguages.update { it - mlkitCode }
        }
    }

    suspend fun deleteLanguage(langCode: String): Result<Unit> = runCatching {
        val mlkitCode = toMlKitLangCode(langCode)
        closeAllClients()
        val model = TranslateRemoteModel.Builder(mlkitCode).build()
        RemoteModelManager.getInstance().deleteDownloadedModel(model).awaitResult()
        refreshDownloadedModels()
    }

    suspend fun translate(
        engine: TranslateEngineItem,
        texts: List<String>,
        lang: TranslateTargetLang,
        onItemTranslated: ((String, String) -> Unit)? = null,
    ): Map<String, String> {
        val tgtCode = toMlKitLangCode(lang.mlkitCode)
        val conditions = DownloadConditions.Builder()
            .apply { if (engine.mlkitWifiOnly) requireWifi() }
            .build()
        val out = LinkedHashMap<String, String>()

        mutex.withLock {
            for (item in texts) {
                val detected = LocalLanguageDetector.detect(item)
                val srcCode = toMlKitLangCode(detected)
                if (srcCode == tgtCode) {
                    out[item] = item
                    onItemTranslated?.invoke(item, item)
                    continue
                }
                val isColdClient = !clients.containsKey("$srcCode->$tgtCode")
                val client = if (isColdClient) {
                    BookTitleTranslator.withModelLoading {
                        getOrCreateClientLocked(srcCode, tgtCode).also {
                            it.downloadModelIfNeeded(conditions).awaitResult()
                        }
                    }
                } else {
                    getOrCreateClientLocked(srcCode, tgtCode).also {
                        it.downloadModelIfNeeded(conditions).awaitResult()
                    }
                }
                val translated = client.translate(item).awaitResult().trim()
                if (translated.isNotEmpty()) {
                    out[item] = translated
                    onItemTranslated?.invoke(item, translated)
                }
            }
            scheduleIdleCloseLocked()
        }
        scope.launch { refreshDownloadedModels() }
        return out
    }

    fun closeAllClients() {
        val keys = clients.keys.toList()
        keys.forEach { key ->
            clients.remove(key)?.let { client ->
                runCatching { client.close() }
            }
        }
    }

    fun closeAllClientsAsync() {
        idleCloseJob?.cancel()
        scope.launch {
            mutex.withLock {
                if (clients.isNotEmpty()) {
                    closeAllClients()
                }
            }
        }
    }

    private fun getOrCreateClientLocked(srcCode: String, tgtCode: String): Translator {
        val key = "$srcCode->$tgtCode"
        return clients.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(srcCode)
                .setTargetLanguage(tgtCode)
                .build()
            Translation.getClient(options)
        }
    }

    private fun toMlKitLangCode(code: String): String {
        val norm = code.trim().lowercase()
        return when {
            norm.startsWith("zh") -> TranslateLanguage.CHINESE
            norm.startsWith("ja") || norm == "jp" -> TranslateLanguage.JAPANESE
            norm.startsWith("ko") || norm == "kor" -> TranslateLanguage.KOREAN
            norm.startsWith("ru") -> TranslateLanguage.RUSSIAN
            norm.startsWith("fr") -> TranslateLanguage.FRENCH
            norm.startsWith("de") -> TranslateLanguage.GERMAN
            norm.startsWith("es") -> TranslateLanguage.SPANISH
            else -> TranslateLanguage.ENGLISH
        }
    }

    private fun scheduleIdleCloseLocked() {
        idleCloseJob?.cancel()
        idleCloseJob = scope.launch {
            delay(IDLE_CLOSE_DELAY_MS)
            mutex.withLock {
                closeAllClients()
            }
        }
    }

    init {
        appCtx.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                    closeAllClients()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                closeAllClients()
            }
        })
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { res ->
        if (cont.isActive) cont.resume(res)
    }
    addOnFailureListener { err ->
        if (cont.isActive) cont.resumeWithException(err)
    }
    addOnCanceledListener {
        if (cont.isActive) cont.cancel()
    }
}
