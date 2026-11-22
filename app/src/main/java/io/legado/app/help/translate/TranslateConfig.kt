package io.legado.app.help.translate

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.JsonParser
import io.legado.app.utils.GSON
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import splitties.init.appCtx
import java.util.UUID

enum class TranslateProvider(
    val id: String,
    val label: String,
    val summary: String,
    val defaultEndpoint: String,
) {
    HY_MT_OFFLINE(
        "hy_mt_offline",
        "端侧大模型 (GGUF)",
        "通用本地 GGUF 翻译大模型，内置混元 Hy-MT2 与 PLaMo-3，支持多模型切换对比",
        "hymt://offline",
    ),
    LOCAL_NMT(
        "local_nmt",
        "开源端侧 NMT",
        "离线 ONNX 神经翻译（Opus-MT / NLLB），按需下载模型",
        "nmt://auto",
    ),
    MLKIT_OFFLINE(
        "mlkit_offline",
        "Google ML Kit 离线",
        "内置独立端侧翻译 SDK，按需下载约 30MB 语言包",
        "mlkit://offline",
    ),
    GOOGLE(
        "google",
        "Google 翻译",
        "免 Key，支持自定义镜像域名与批量翻译",
        "https://translate.googleapis.com/translate_a/single",
    );

    companion object {
        fun fromId(id: String?): TranslateProvider {
            return entries.firstOrNull { it.id == id } ?: GOOGLE
        }

        fun inferFromUrl(url: String?, fallback: TranslateProvider = GOOGLE): TranslateProvider {
            val u = url?.trim()?.lowercase().orEmpty()
            if (u.isEmpty()) return fallback
            return when {
                u.startsWith("hymt://") -> HY_MT_OFFLINE
                u.startsWith("nmt://") -> LOCAL_NMT
                u.startsWith("mlkit://") -> MLKIT_OFFLINE
                fallback in setOf(HY_MT_OFFLINE, LOCAL_NMT, MLKIT_OFFLINE) -> fallback
                u.contains("googleapis.com") || u.contains("translate.google") -> GOOGLE
                else -> fallback
            }
        }
    }
}

data class TranslateEngineItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: TranslateProvider = TranslateProvider.GOOGLE,
    val enabled: Boolean = false,
    val endpoint: String = type.defaultEndpoint,
    val targetLang: String = "",
    val localModelId: String = "",
    val mlkitWifiOnly: Boolean = false,
) {
    @Suppress("USELESS_ELVIS")
    fun normalized(): TranslateEngineItem {
        val safeType: TranslateProvider? = type
        val rawEndpoint = endpoint.orEmpty().trim()
        val resolvedType = safeType ?: TranslateProvider.inferFromUrl(rawEndpoint)
        val resolvedEndpoint = rawEndpoint.ifBlank { resolvedType.defaultEndpoint }
        return copy(
            name = name.orEmpty().trim().ifBlank { resolvedType.label },
            type = resolvedType,
            endpoint = resolvedEndpoint,
            targetLang = targetLang.orEmpty().trim(),
            localModelId = localModelId.orEmpty().trim(),
        )
    }
}

data class TranslateTargetLang(
    val code: String,
    val label: String,
    val mlkitCode: String = code.substringBefore('-'),
    val nllbCode: String = "zho_Hans",
)

object TranslateConfig {
    private const val PREF_NAME = "translate_config"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TRANSLATE_INTRO = "translate_intro"
    private const val KEY_TRANSLATE_CATALOG = "translate_catalog"
    private const val KEY_TRANSLATE_CONTENT_DEFAULT = "translate_content_default"
    private const val KEY_TARGET_LANG = "target_lang"
    private const val KEY_NMT_MIRROR = "nmt_mirror_base_url"
    private const val KEY_HYMT_MIRROR = "hymt_mirror_base_url"
    private const val KEY_ENGINES_JSON = "engines_json"
    private const val KEY_BOOK_TRANSLATE_PREFIX = "book_tr_"
    private const val KEY_BOOK_CATALOG_TRANSLATE_PREFIX = "book_tr_catalog_"
    private const val KEY_BOOK_CONTENT_TRANSLATE_PREFIX = "book_tr_content_"

    private val builtInIds = setOf("preset_hymt", "preset_local_nmt", "preset_mlkit", "preset_google")

    fun isBuiltInEngine(id: String): Boolean = id in builtInIds

    val supportedTargetLangs = listOf(
        TranslateTargetLang("zh-CN", "简体中文", "zh", "zho_Hans"),
        TranslateTargetLang("zh-TW", "繁体中文", "zh", "zho_Hant"),
        TranslateTargetLang("en", "English", "en", "eng_Latn"),
        TranslateTargetLang("ja", "日本語", "ja", "jpn_Jpan"),
        TranslateTargetLang("ko", "한국어", "ko", "kor_Hang"),
    )

    private val prefs: SharedPreferences by lazy {
        appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun notifyChanged() {
        _revision.value = _revision.value + 1
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit { putBoolean(KEY_ENABLED, value) }
            notifyChanged()
        }

    var translateIntro: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATE_INTRO, true)
        set(value) {
            prefs.edit { putBoolean(KEY_TRANSLATE_INTRO, value) }
            notifyChanged()
        }

    var translateCatalog: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATE_CATALOG, false)
        set(value) {
            prefs.edit { putBoolean(KEY_TRANSLATE_CATALOG, value) }
            notifyChanged()
        }

    var translateContentDefault: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATE_CONTENT_DEFAULT, false)
        set(value) {
            prefs.edit { putBoolean(KEY_TRANSLATE_CONTENT_DEFAULT, value) }
            notifyChanged()
        }

    var targetLang: String
        get() {
            val raw = prefs.getString(KEY_TARGET_LANG, "zh-CN")
            val normalized = normalizeTargetLang(raw)
            if (raw != normalized) {
                prefs.edit { putString(KEY_TARGET_LANG, normalized) }
            }
            return normalized
        }
        set(value) {
            prefs.edit { putString(KEY_TARGET_LANG, normalizeTargetLang(value)) }
            notifyChanged()
        }

    var nmtMirrorBaseUrl: String
        get() {
            val raw = prefs.getString(KEY_NMT_MIRROR, "https://hf-mirror.com")
            val normalized = normalizeMirrorUrl(raw)
            if (raw != normalized) {
                prefs.edit { putString(KEY_NMT_MIRROR, normalized) }
            }
            return normalized
        }
        set(value) {
            prefs.edit { putString(KEY_NMT_MIRROR, normalizeMirrorUrl(value)) }
            notifyChanged()
        }

    var hyMtMirrorBaseUrl: String
        get() {
            val raw = prefs.getString(KEY_HYMT_MIRROR, "https://hf-mirror.com")
            val normalized = normalizeMirrorUrl(raw)
            if (raw != normalized) {
                prefs.edit { putString(KEY_HYMT_MIRROR, normalized) }
            }
            return normalized
        }
        set(value) {
            prefs.edit { putString(KEY_HYMT_MIRROR, normalizeMirrorUrl(value)) }
            notifyChanged()
        }

    private fun normalizeTargetLang(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return "zh-CN"
        return supportedTargetLangs.firstOrNull {
            it.code.equals(trimmed, ignoreCase = true) || it.label.equals(trimmed, ignoreCase = true)
        }?.code ?: "zh-CN"
    }

    private fun normalizeMirrorUrl(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return "https://hf-mirror.com"
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        return if (trimmed.contains("huggingface", ignoreCase = true) &&
            !trimmed.contains("hf-mirror", ignoreCase = true)
        ) {
            "https://huggingface.co"
        } else {
            "https://hf-mirror.com"
        }
    }

    private fun normalizeLocalModelId(type: TranslateProvider, raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return if (type == TranslateProvider.HY_MT_OFFLINE) "hymt2-1.8b-1.25bit" else ""
        }
        val base = trimmed
            .removeSuffix("[已安装]")
            .removeSuffix("[未下载]")
            .trim()
        return when (type) {
            TranslateProvider.HY_MT_OFFLINE -> {
                LocalHyMtModelManager.findSpec(base)?.id
                    ?: LocalHyMtModelManager.catalog.firstOrNull { it.name == base }?.id
                    ?: "hymt2-1.8b-1.25bit"
            }
            TranslateProvider.LOCAL_NMT -> {
                if (base == "自动按语种/接力路由") {
                    ""
                } else {
                    LocalNmtModelManager.findSpec(base)?.id
                        ?: LocalNmtModelManager.catalog.firstOrNull { it.name == base }?.id
                        ?: ""
                }
            }
            else -> ""
        }
    }

    fun targetLangSpec(code: String = targetLang): TranslateTargetLang {
        val normalizedCode = normalizeTargetLang(code)
        return supportedTargetLangs.firstOrNull { it.code == normalizedCode } ?: supportedTargetLangs.first()
    }

    fun defaultEngines(): List<TranslateEngineItem> = listOf(
        TranslateEngineItem(
            id = "preset_hymt",
            name = "端侧大模型 (GGUF)",
            type = TranslateProvider.HY_MT_OFFLINE,
            enabled = false,
            endpoint = TranslateProvider.HY_MT_OFFLINE.defaultEndpoint,
            localModelId = "hymt2-1.8b-1.25bit",
        ),
        TranslateEngineItem(
            id = "preset_local_nmt",
            name = "开源端侧 NMT",
            type = TranslateProvider.LOCAL_NMT,
            enabled = false,
            endpoint = TranslateProvider.LOCAL_NMT.defaultEndpoint,
        ),
        TranslateEngineItem(
            id = "preset_mlkit",
            name = "Google ML Kit 离线",
            type = TranslateProvider.MLKIT_OFFLINE,
            enabled = false,
            endpoint = TranslateProvider.MLKIT_OFFLINE.defaultEndpoint,
        ),
        TranslateEngineItem(
            id = "preset_google",
            name = "Google 翻译",
            type = TranslateProvider.GOOGLE,
            enabled = true,
            endpoint = TranslateProvider.GOOGLE.defaultEndpoint,
        ),
    )

    fun getEngines(): List<TranslateEngineItem> {
        val raw = prefs.getString(KEY_ENGINES_JSON, null)
        if (raw.isNullOrBlank()) {
            val defaults = defaultEngines()
            saveEngines(defaults)
            return defaults
        }
        return runCatching {
            val validTypeNames = TranslateProvider.entries.mapTo(HashSet()) { it.name }
            val array = JsonParser.parseString(raw).asJsonArray
            val surviving = ArrayList<TranslateEngineItem>()
            var droppedAny = false

            for (el in array) {
                if (!el.isJsonObject) {
                    droppedAny = true
                    continue
                }
                val typeName = el.asJsonObject.get("type")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                if (typeName !in validTypeNames) {
                    droppedAny = true
                    continue
                }
                val parsed = GSON.fromJson(el, TranslateEngineItem::class.java)?.normalized()
                if (parsed == null) {
                    droppedAny = true
                } else {
                    val cleanLang = if (parsed.targetLang.isBlank()) "" else normalizeTargetLang(parsed.targetLang)
                    val cleanModelId = normalizeLocalModelId(parsed.type, parsed.localModelId)
                    if (cleanLang != parsed.targetLang || cleanModelId != parsed.localModelId) {
                        droppedAny = true
                    }
                    surviving.add(parsed.copy(targetLang = cleanLang, localModelId = cleanModelId))
                }
            }

            val defaults = defaultEngines()
            val survivingById = surviving.associateBy { it.id }
            var needsPersist = droppedAny
            val builtIns = defaults.map { preset ->
                val existing = survivingById[preset.id]
                if (existing == null) {
                    needsPersist = true
                    preset
                } else if (existing.id == "preset_hymt" &&
                    (existing.name == "腾讯混元 Hy-MT2" || existing.name == "腾讯混元 HY-MT1.5" || existing.localModelId == "hymt15-1.8b-1.25bit")
                ) {
                    needsPersist = true
                    existing.copy(
                        type = preset.type,
                        name = if (existing.name == "腾讯混元 Hy-MT2" || existing.name == "腾讯混元 HY-MT1.5") {
                            preset.name
                        } else {
                            existing.name
                        },
                        localModelId = if (existing.localModelId == "hymt15-1.8b-1.25bit" || existing.localModelId.isBlank()) {
                            preset.localModelId
                        } else {
                            normalizeLocalModelId(preset.type, existing.localModelId)
                        },
                    ).normalized()
                } else if (existing.type != preset.type) {
                    needsPersist = true
                    existing.copy(
                        type = preset.type,
                        localModelId = normalizeLocalModelId(preset.type, existing.localModelId),
                    ).normalized()
                } else {
                    existing
                }
            }
            val customEngines = surviving.filterNot { isBuiltInEngine(it.id) }
            val merged = builtIns + customEngines
            val finalList = if (droppedAny && merged.none { it.enabled }) {
                needsPersist = true
                merged.map {
                    if (it.id == "preset_google") it.copy(enabled = true) else it
                }
            } else {
                merged
            }
            if (needsPersist) {
                prefs.edit { putString(KEY_ENGINES_JSON, GSON.toJson(finalList.map { it.normalized() })) }
            }
            finalList
        }.getOrElse {
            val defaults = defaultEngines()
            prefs.edit { putString(KEY_ENGINES_JSON, GSON.toJson(defaults)) }
            defaults
        }
    }

    fun getEnabledEngines(): List<TranslateEngineItem> {
        return getEngines().filter { it.enabled }
    }

    fun saveEngines(list: List<TranslateEngineItem>) {
        prefs.edit { putString(KEY_ENGINES_JSON, GSON.toJson(list.map { it.normalized() })) }
        notifyChanged()
    }

    fun upsertEngine(item: TranslateEngineItem) {
        val fixedType = defaultEngines().firstOrNull { it.id == item.id }?.type ?: item.type
        val base = item.copy(type = fixedType).normalized()
        val normalized = base.copy(
            targetLang = if (base.targetLang.isBlank()) "" else normalizeTargetLang(base.targetLang),
            localModelId = normalizeLocalModelId(fixedType, base.localModelId),
        )
        val current = getEngines().toMutableList()
        val index = current.indexOfFirst { it.id == normalized.id }
        if (index >= 0) {
            current[index] = normalized
        } else {
            current.add(normalized)
        }
        saveEngines(current)
    }

    fun deleteEngine(id: String) {
        if (isBuiltInEngine(id)) return
        val next = getEngines().filterNot { it.id == id }
        saveEngines(next)
    }

    fun setEngineEnabled(id: String, enabled: Boolean) {
        val next = getEngines().map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        saveEngines(next)
    }

    fun resetDefaultEngines() {
        saveEngines(defaultEngines())
    }

    private fun bookKeyHash(bookUrl: String): String {
        return bookUrl.hashCode().toUInt().toString(16)
    }

    fun isBookTranslateEnabled(bookUrl: String?): Boolean {
        if (bookUrl.isNullOrBlank()) return enabled
        val key = KEY_BOOK_TRANSLATE_PREFIX + bookKeyHash(bookUrl)
        return if (prefs.contains(key)) prefs.getBoolean(key, enabled) else enabled
    }

    fun setBookTranslateEnabled(bookUrl: String, value: Boolean) {
        val key = KEY_BOOK_TRANSLATE_PREFIX + bookKeyHash(bookUrl)
        prefs.edit { putBoolean(key, value) }
        notifyChanged()
    }

    fun isBookCatalogTranslateEnabled(bookUrl: String?): Boolean {
        if (bookUrl.isNullOrBlank()) return translateCatalog
        val key = KEY_BOOK_CATALOG_TRANSLATE_PREFIX + bookKeyHash(bookUrl)
        return if (prefs.contains(key)) prefs.getBoolean(key, translateCatalog) else translateCatalog
    }

    fun setBookCatalogTranslateEnabled(bookUrl: String, value: Boolean) {
        val key = KEY_BOOK_CATALOG_TRANSLATE_PREFIX + bookKeyHash(bookUrl)
        prefs.edit { putBoolean(key, value) }
        notifyChanged()
    }

    fun isBookContentTranslateEnabled(bookUrl: String?): Boolean {
        if (bookUrl.isNullOrBlank()) return translateContentDefault
        val key = KEY_BOOK_CONTENT_TRANSLATE_PREFIX + bookKeyHash(bookUrl)
        return if (prefs.contains(key)) prefs.getBoolean(key, translateContentDefault) else translateContentDefault
    }

    fun setBookContentTranslateEnabled(bookUrl: String, value: Boolean) {
        val key = KEY_BOOK_CONTENT_TRANSLATE_PREFIX + bookKeyHash(bookUrl)
        prefs.edit { putBoolean(key, value) }
        notifyChanged()
    }
}
