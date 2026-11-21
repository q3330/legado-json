package io.legado.app.ui.book.source.edit

import android.app.Application
import android.webkit.CookieManager
import android.content.Intent
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ConcurrentRateLimiter.Companion.concurrentRecordMap
import io.legado.app.help.RuleComplete
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.http.BookSourceCookieStore
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.model.jsSource.isJsSource
import io.legado.app.help.storage.ImportOldData
import io.legado.app.model.SharedJsScope
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.jsonPath
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.removeCookie
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers
import java.io.File


class BookSourceEditViewModel(application: Application) : BaseViewModel(application) {
    var autoComplete = false
    var bookSource: BookSource? = null

    fun initData(intent: Intent, onFinally: () -> Unit) {
        execute {
            val sourceUrl = intent.getStringExtra("sourceUrl")
            var source: BookSource? = null
            if (sourceUrl != null) {
                source = appDb.bookSourceDao.getBookSource(sourceUrl)
            }
            source?.let {
                bookSource = it
            }
        }.onFinally {
            onFinally()
        }
    }

    fun save(source: BookSource, success: ((BookSource) -> Unit)? = null) {
        execute {
            if (source.bookSourceUrl.isBlank() || source.bookSourceName.isBlank()) {
                throw NoStackTraceException(context.getString(R.string.non_null_name_url))
            }
            val hasSearchRule = !source.searchUrl.isNullOrBlank() || !source.mainJs.isNullOrBlank()
            val hasExploreRule = !source.exploreUrl.isNullOrBlank()
            if (!hasSearchRule) source.enabled = false
            if (!hasExploreRule) source.enabledExplore = false
            val oldSource = bookSource ?: BookSource()
            if (!source.equal(oldSource)) {
                source.lastUpdateTime = System.currentTimeMillis()
                if (oldSource.exploreUrl != source.exploreUrl) {
                    oldSource.clearExploreKindsCache()
                }
                if (oldSource.jsLib != source.jsLib) {
                    SharedJsScope.remove(oldSource.jsLib)
                }
            }
            bookSource?.let {
                if (it.bookSourceUrl != source.bookSourceUrl) {
                    SourceHelp.deleteBookSource(it.bookSourceUrl)
                } else {
                    SourceConfig.removeSource(it.bookSourceUrl)
                }
            }
            appDb.bookSourceDao.insert(source)
            bookSource = source
            concurrentRecordMap.remove(source.bookSourceUrl) //删除并发限制缓存
            source
        }.onSuccess {
            success?.invoke(it)
        }.onError {
            context.toastOnUi(it.localizedMessage)
            it.printOnDebug()
        }
    }

    fun pasteSource(onSuccess: (source: BookSource) -> Unit) {
        execute(context = Dispatchers.Main) {
            val text = context.getClipText()
            if (text.isNullOrBlank()) {
                throw NoStackTraceException("剪贴板为空")
            } else {
                importSource(text, onSuccess)
            }
        }.onError {
            context.toastOnUi(it.localizedMessage ?: "Error")
            it.printOnDebug()
        }
    }

    fun importSource(text: String, finally: (source: BookSource) -> Unit) {
        execute {
            importSource(text)
        }.onSuccess {
            finally.invoke(it)
        }.onError {
            context.toastOnUi(it.localizedMessage ?: "Error")
            it.printOnDebug()
        }
    }

    suspend fun importSource(text: String): BookSource {
        return when {
            text.isAbsUrl() -> {
                val text1 = okHttpClient.newCallStrResponse { url(text) }.body
                importSource(text1!!)
            }

            text.isJsonArray() -> {
                if (text.contains("ruleSearchUrl") || text.contains("ruleFindUrl")) {
                    val items: List<Map<String, Any>> = jsonPath.parse(text).read("$")
                    val jsonItem = jsonPath.parse(items[0])
                    ImportOldData.fromOldBookSource(jsonItem)
                } else {
                    GSON.fromJsonArray<BookSource>(text).getOrThrow()[0]
                }
            }

            text.isJsonObject() -> {
                if (text.contains("ruleSearchUrl") || text.contains("ruleFindUrl")) {
                    val jsonItem = jsonPath.parse(text)
                    ImportOldData.fromOldBookSource(jsonItem)
                } else {
                    GSON.fromJsonObject<BookSource>(text).getOrThrow()
                }
            }

            else -> throw NoStackTraceException("格式不对")
        }
    }

    fun clearCookie(url: String) {
        execute {
            BookSourceCookieStore(url).removeCookie(url)
            CookieManager.getInstance().removeCookie(url)
        }
    }

    fun saveToFile(source: BookSource, success: (file: File, name: String) -> Unit) {
        execute {
            val baseName = source.bookSourceName.ifBlank { "未命名书源" }.normalizeFileName()
            if (source.isJsSource()) {
                val outputName = "$baseName.js"
                val file = File(context.cacheDir, outputName)
                file.writeText(source.mainJs.orEmpty())
                file to outputName
            } else {
                val outputName = "bookSource_$baseName.json"
                val path = "${context.filesDir}/shareBookSource.json"
                FileUtils.delete(path)
                val file = FileUtils.createFileWithReplace(path)
                file.outputStream().buffered().use {
                    GSON.writeToOutputStream(it, listOf(source))
                }
                file to outputName
            }
        }.onSuccess {
            success.invoke(it.first, it.second)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    fun ruleComplete(rule: String?, preRule: String? = null, type: Int = 1): String? {
        if (autoComplete) {
            return RuleComplete.autoComplete(rule, preRule, type)
        }
        return rule
    }

}
