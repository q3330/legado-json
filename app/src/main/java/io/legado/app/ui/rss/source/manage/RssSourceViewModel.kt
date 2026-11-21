package io.legado.app.ui.rss.source.manage

import android.app.Application
import android.text.TextUtils
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.DefaultData
import io.legado.app.help.source.RssCheckOptions
import io.legado.app.help.source.RssSourceChecker
import io.legado.app.help.source.RssSourceDedupe
import io.legado.app.help.source.RssSourceDedupeMode
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import java.io.File
import java.util.Date
import java.util.Locale

/**
 * 订阅源管理数据修改
 * 修改数据要copy,直接修改会导致界面不刷新
 */
class RssSourceViewModel(application: Application) : BaseViewModel(application) {

    companion object {
        private const val SQL_CHUNK_SIZE = 500
    }

    fun topSource(vararg sources: RssSource) {
        execute {
            sources.sortBy { it.customOrder }
            val minOrder = appDb.rssSourceDao.minOrder - 1
            val array = Array(sources.size) {
                sources[it].copy(customOrder = minOrder - it)
            }
            appDb.rssSourceDao.update(*array)
        }
    }

    fun bottomSource(vararg sources: RssSource) {
        execute {
            sources.sortBy { it.customOrder }
            val maxOrder = appDb.rssSourceDao.maxOrder + 1
            val array = Array(sources.size) {
                sources[it].copy(customOrder = maxOrder + it)
            }
            appDb.rssSourceDao.update(*array)
        }
    }

    fun del(vararg rssSource: RssSource) {
        execute {
            SourceHelp.deleteRssSources(rssSource.toList())
        }
    }

    fun update(vararg rssSource: RssSource) {
        execute { appDb.rssSourceDao.update(*rssSource) }
    }

    fun upOrder() {
        execute {
            val sources = appDb.rssSourceDao.all
            for ((index: Int, source: RssSource) in sources.withIndex()) {
                source.customOrder = index + 1
            }
            appDb.rssSourceDao.update(*sources.toTypedArray())
        }
    }

    fun enableSelection(sources: List<RssSource>) {
        val urls = sources.asSequence().filter { !it.enabled }.map(RssSource::sourceUrl).distinct().toList()
        if (urls.isEmpty()) return
        execute {
            appDb.runInTransaction {
                urls.chunked(SQL_CHUNK_SIZE).forEach { chunk ->
                    appDb.rssSourceDao.enableByUrls(chunk, true)
                }
            }
        }
    }

    fun disableSelection(sources: List<RssSource>) {
        val urls = sources.asSequence().filter { it.enabled }.map(RssSource::sourceUrl).distinct().toList()
        if (urls.isEmpty()) return
        execute {
            appDb.runInTransaction {
                urls.chunked(SQL_CHUNK_SIZE).forEach { chunk ->
                    appDb.rssSourceDao.enableByUrls(chunk, false)
                }
            }
        }
    }

    fun updateSelectionType(sources: List<RssSource>, type: Int) {
        val urls = sources.asSequence().filter { it.type != type }.map(RssSource::sourceUrl).distinct().toList()
        if (urls.isEmpty()) return
        execute {
            appDb.runInTransaction {
                urls.chunked(SQL_CHUNK_SIZE).forEach { chunk ->
                    appDb.rssSourceDao.updateTypeByUrls(chunk, type)
                }
            }
        }
    }

    fun clearSelectionGroups(sources: List<RssSource>) {
        val urls = sources.asSequence()
            .filter { !it.sourceGroup.isNullOrBlank() }
            .map(RssSource::sourceUrl)
            .distinct()
            .toList()
        if (urls.isEmpty()) return
        execute {
            appDb.runInTransaction {
                urls.chunked(SQL_CHUNK_SIZE).forEach { chunk ->
                    appDb.rssSourceDao.clearGroupsByUrls(chunk)
                }
            }
        }
    }

    fun applyDedupeAutoDisable(
        mode: RssSourceDedupeMode,
        preferredPrimaries: Map<String, String>,
        promotedPrimaryUrl: String? = null,
        onComplete: ((disabledCount: Int) -> Unit)? = null,
    ) {
        if (mode == RssSourceDedupeMode.NONE) return
        execute {
            val allSources = appDb.rssSourceDao.all
            if (allSources.size <= 1) return@execute 0
            val tree = RssSourceDedupe.buildTree(allSources, mode, preferredPrimaries)
            val urlsToDisable = ArrayList<String>()
            var shouldEnablePromoted = false
            for (node in tree) {
                if (!node.hasDuplicates) continue
                val primary = node.primary
                if (promotedPrimaryUrl != null && primary.sourceUrl == promotedPrimaryUrl && !primary.enabled) {
                    shouldEnablePromoted = true
                }
                for (candidate in node.allSources) {
                    if (candidate.sourceUrl == primary.sourceUrl) continue
                    if (candidate.enabled) {
                        urlsToDisable.add(candidate.sourceUrl)
                    }
                }
            }
            if (shouldEnablePromoted && promotedPrimaryUrl != null) {
                appDb.rssSourceDao.enable(promotedPrimaryUrl, true)
            }
            if (urlsToDisable.isNotEmpty()) {
                urlsToDisable.chunked(SQL_CHUNK_SIZE).forEach { chunk ->
                    appDb.rssSourceDao.enableByUrls(chunk, false)
                }
            }
            urlsToDisable.size
        }.onSuccess { disabledCount ->
            onComplete?.invoke(disabledCount)
        }
    }

    fun checkSelection(sources: List<RssSource>, options: RssCheckOptions) {
        if (sources.isEmpty()) return
        RssSourceChecker.start(sources, options)
    }

    fun stopCheck() {
        RssSourceChecker.stop()
    }

    fun dismissCheck() {
        RssSourceChecker.dismiss()
    }

    fun saveToFile(sources: List<RssSource>, success: (file: File, name: String) -> Unit) {
        execute {
            val name = if (sources.size == 1) {
                "rssSource_${sources.first().sourceName.normalizeFileName()}.json"
            } else {
                val timestamp = java.text.SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date())
                "rssSource_$timestamp.json"
            }
            val path = "${context.filesDir}/shareRssSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            file.writeText(GSON.toJson(sources))
            Pair(file, name)
        }.onSuccess {
            success.invoke(it.first, it.second)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    fun selectionAddToGroups(sources: List<RssSource>, groups: String) {
        if (sources.isEmpty()) return
        execute {
            val array = Array(sources.size) {
                sources[it].copy().addGroup(groups)
            }
            appDb.rssSourceDao.update(*array)
        }
    }

    fun selectionRemoveFromGroups(sources: List<RssSource>, groups: String) {
        if (sources.isEmpty()) return
        execute {
            val array = Array(sources.size) {
                sources[it].copy().removeGroup(groups)
            }
            appDb.rssSourceDao.update(*array)
        }
    }

    fun addGroup(group: String) {
        execute {
            val sources = appDb.rssSourceDao.noGroup
            sources.forEach { source ->
                source.sourceGroup = group
            }
            appDb.rssSourceDao.update(*sources.toTypedArray())
        }
    }

    fun upGroup(oldGroup: String, newGroup: String?) {
        execute {
            val sources = appDb.rssSourceDao.getByGroup(oldGroup)
            sources.forEach { source ->
                source.sourceGroup?.splitNotBlank(",")?.toHashSet()?.let {
                    it.remove(oldGroup)
                    if (!newGroup.isNullOrEmpty())
                        it.add(newGroup)
                    source.sourceGroup = TextUtils.join(",", it)
                }
            }
            appDb.rssSourceDao.update(*sources.toTypedArray())
        }
    }

    fun delGroup(group: String) {
        execute {
            val sources = appDb.rssSourceDao.getByGroup(group)
            sources.forEach { source ->
                source.sourceGroup?.splitNotBlank(",")?.toHashSet()?.let {
                    it.remove(group)
                    source.sourceGroup = TextUtils.join(",", it).takeIf(String::isNotBlank)
                }
            }
            appDb.rssSourceDao.update(*sources.toTypedArray())
        }
    }

    fun importDefault() {
        execute {
            DefaultData.importDefaultRssSources()
        }
    }

    fun disable(rssSource: RssSource) {
        execute {
            appDb.rssSourceDao.enable(rssSource.sourceUrl, false)
        }
    }

}
