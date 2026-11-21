package io.legado.app.help.translate

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.reflect.TypeToken
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
    BING(
        "bing",
        "必应翻译",
        "免 Key，支持批量翻译",
        "https://api-edge.cognitive.microsofttranslator.com/translate",
    ),
    GOOGLE(
        "google",
        "Google 翻译",
        "免 Key，支持批量翻译",
        "https://translate.googleapis.com/translate_a/single",
    ),
    YOUDAO(
        "youdao",
        "有道翻译",
        "需配置有道智云 AppKey 与应用密钥",
        "https://openapi.youdao.com/api",
    ),
    BAIDU(
        "baidu",
        "百度翻译",
        "需配置百度翻译开放平台 AppID 与密钥",
        "https://fanyi-api.baidu.com/api/trans/vip/translate",
    ),
    DEEPL(
        "deepl",
        "DeepL",
        "支持官方 Auth Key 或 DeepLX 接口地址",
        "https://api-free.deepl.com/v2/translate",
    ),
    DICT_RULE(
        "dict_rule",
        "字典规则",
        "复用「规则管理 -> 字典规则」中的已启用规则",
        "dict://rule",
    );

    companion object {
        fun fromId(id: String?): TranslateProvider {
            return entries.firstOrNull { it.id == id } ?: BING
        }

        fun inferFromUrl(url: String?, fallback: TranslateProvider = BING): TranslateProvider {
            val u = url?.trim()?.lowercase().orEmpty()
            if (u.isEmpty()) return fallback
            return when {
                u.startsWith("dict://") -> DICT_RULE
                u.contains("microsofttranslator.com") || u.contains("bing.com") -> BING
                u.contains("googleapis.com") || u.contains("translate.google") -> GOOGLE
                u.contains("youdao.com") -> YOUDAO
                u.contains("baidu.com") -> BAIDU
                u.contains("deepl") -> DEEPL
                else -> DEEPL
            }
        }
    }
}

data class TranslateEngineItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: TranslateProvider = TranslateProvider.BING,
    val enabled: Boolean = false,
    val endpoint: String = type.defaultEndpoint,
    val targetLang: String = "",
    val apiKey: String = "",
    val apiSecret: String = "",
    val dictRuleName: String = "",
) {
    fun normalized(): TranslateEngineItem {
        val resolvedEndpoint = endpoint.trim().ifBlank { type.defaultEndpoint }
        val resolvedType = TranslateProvider.inferFromUrl(resolvedEndpoint, type)
        return copy(
            name = name.trim().ifBlank { resolvedType.label },
            type = resolvedType,
            endpoint = resolvedEndpoint,
            targetLang = targetLang.trim(),
            apiKey = apiKey.trim(),
            apiSecret = apiSecret.trim(),
            dictRuleName = dictRuleName.trim(),
        )
    }
}

data class TranslateTargetLang(
    val code: String,
    val label: String,
    val bingCode: String,
    val baiduCode: String,
    val youdaoCode: String,
    val deeplCode: String,
)

object TranslateConfig {
    private const val PREF_NAME = "translate_config"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TRANSLATE_INTRO = "translate_intro"
    private const val KEY_TRANSLATE_CATALOG = "translate_catalog"
    private const val KEY_TRANSLATE_CONTENT_DEFAULT = "translate_content_default"
    private const val KEY_TARGET_LANG = "target_lang"
    private const val KEY_ENGINES_JSON = "engines_json"
    private const val KEY_BOOK_TRANSLATE_PREFIX = "book_tr_"
    private const val KEY_BOOK_CATALOG_TRANSLATE_PREFIX = "book_tr_catalog_"
    private const val KEY_BOOK_CONTENT_TRANSLATE_PREFIX = "book_tr_content_"

    val supportedTargetLangs = listOf(
        TranslateTargetLang("zh-CN", "简体中文", "zh-Hans", "zh", "zh-CHS", "ZH-HANS"),
        TranslateTargetLang("zh-TW", "繁体中文", "zh-Hant", "cht", "zh-CHT", "ZH-HANT"),
        TranslateTargetLang("en", "English", "en", "en", "en", "EN"),
        TranslateTargetLang("ja", "日本語", "ja", "jp", "ja", "JA"),
        TranslateTargetLang("ko", "한국어", "ko", "kor", "ko", "KO"),
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
        get() = prefs.getString(KEY_TARGET_LANG, "zh-CN") ?: "zh-CN"
        set(value) {
            prefs.edit { putString(KEY_TARGET_LANG, value) }
            notifyChanged()
        }

    fun targetLangSpec(code: String = targetLang): TranslateTargetLang {
        return supportedTargetLangs.firstOrNull { it.code == code } ?: supportedTargetLangs.first()
    }

    fun defaultEngines(): List<TranslateEngineItem> = listOf(
        TranslateEngineItem(
            id = "preset_bing",
            name = "必应翻译",
            type = TranslateProvider.BING,
            enabled = true,
            endpoint = TranslateProvider.BING.defaultEndpoint,
        ),
        TranslateEngineItem(
            id = "preset_google",
            name = "Google 翻译",
            type = TranslateProvider.GOOGLE,
            enabled = false,
            endpoint = TranslateProvider.GOOGLE.defaultEndpoint,
        ),
        TranslateEngineItem(
            id = "preset_youdao",
            name = "有道翻译",
            type = TranslateProvider.YOUDAO,
            enabled = false,
            endpoint = TranslateProvider.YOUDAO.defaultEndpoint,
        ),
        TranslateEngineItem(
            id = "preset_baidu",
            name = "百度翻译",
            type = TranslateProvider.BAIDU,
            enabled = false,
            endpoint = TranslateProvider.BAIDU.defaultEndpoint,
        ),
        TranslateEngineItem(
            id = "preset_deepl",
            name = "DeepL",
            type = TranslateProvider.DEEPL,
            enabled = false,
            endpoint = TranslateProvider.DEEPL.defaultEndpoint,
        ),
        TranslateEngineItem(
            id = "preset_dict",
            name = "字典规则",
            type = TranslateProvider.DICT_RULE,
            enabled = false,
            endpoint = TranslateProvider.DICT_RULE.defaultEndpoint,
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
            val type = object : TypeToken<List<TranslateEngineItem>>() {}.type
            val parsed: List<TranslateEngineItem>? = GSON.fromJson(raw, type)
            parsed?.map { it.normalized() } ?: defaultEngines()
        }.getOrElse { defaultEngines() }
    }

    fun getEnabledEngines(): List<TranslateEngineItem> {
        return getEngines().filter { it.enabled }
    }

    fun saveEngines(list: List<TranslateEngineItem>) {
        prefs.edit { putString(KEY_ENGINES_JSON, GSON.toJson(list.map { it.normalized() })) }
        notifyChanged()
    }

    fun upsertEngine(item: TranslateEngineItem) {
        val normalized = item.normalized()
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
