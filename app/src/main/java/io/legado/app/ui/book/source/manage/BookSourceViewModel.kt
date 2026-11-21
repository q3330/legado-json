package io.legado.app.ui.book.source.manage

import android.app.Application
import android.text.TextUtils
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.toBookSource
import io.legado.app.help.source.BookSourceDedupe
import io.legado.app.help.source.BookSourceDedupeMode
import io.legado.app.help.source.BookSourceR18Detector
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.jsSource.isJsSource
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.outputStream
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import java.io.File
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 书源管理数据修改
 * 修改数据要copy,直接修改会导致界面不刷新
 */
class BookSourceViewModel(application: Application) : BaseViewModel(application) {

    fun topSource(vararg sources: BookSourcePart) {
        execute {
            sources.sortBy { it.customOrder }
            val minOrder = appDb.bookSourceDao.minOrder - 1
            val array = sources.mapIndexed { index, it ->
                it.copy(customOrder = minOrder - index)
            }
            appDb.bookSourceDao.upOrder(array)
        }
    }

    fun bottomSource(vararg sources: BookSourcePart) {
        execute {
            sources.sortBy { it.customOrder }
            val maxOrder = appDb.bookSourceDao.maxOrder + 1
            val array = sources.mapIndexed { index, it ->
                it.copy(customOrder = maxOrder + index)
            }
            appDb.bookSourceDao.upOrder(array)
        }
    }

    fun del(sources: List<BookSourcePart>) {
        execute {
            withContext(NonCancellable) {
                SourceHelp.deleteBookSourceParts(sources)
            }
        }
    }

    fun update(vararg bookSource: BookSource) {
        execute { appDb.bookSourceDao.update(*bookSource) }
    }

    fun upOrder(items: List<BookSourcePart>) {
        if (items.isEmpty()) return
        execute {
            appDb.bookSourceDao.upOrder(items)
        }
    }

    fun enable(enable: Boolean, items: List<BookSourcePart>) {
        execute {
            if (enable) {
                appDb.bookSourceDao.enable(true, items.filter(BookSourcePart::hasSearchUrl))
                appDb.bookSourceDao.enable(false, items.filterNot(BookSourcePart::hasSearchUrl))
            } else {
                appDb.bookSourceDao.enable(false, items)
            }
        }
    }

    fun enableExplore(enable: Boolean, items: List<BookSourcePart>) {
        execute {
            if (enable) {
                appDb.bookSourceDao.enableExplore(true, items.filter(BookSourcePart::hasExploreUrl))
                appDb.bookSourceDao.enableExplore(false, items.filterNot(BookSourcePart::hasExploreUrl))
            } else {
                appDb.bookSourceDao.enableExplore(false, items)
            }
        }
    }

    fun updateSourceSwitches(
        source: BookSourcePart,
        searchEnabled: Boolean,
        exploreEnabled: Boolean,
    ) {
        execute {
            appDb.bookSourceDao.enable(
                source.bookSourceUrl,
                if (source.hasSearchUrl) searchEnabled else false,
            )
            appDb.bookSourceDao.enableExplore(
                source.bookSourceUrl,
                if (source.hasExploreUrl) exploreEnabled else false,
            )
        }
    }

    fun updateSelectionCapabilities(
        sources: List<BookSourcePart>,
        searchEnabled: Boolean,
        exploreEnabled: Boolean,
    ) {
        execute {
            if (searchEnabled) {
                appDb.bookSourceDao.enable(true, sources.filter(BookSourcePart::hasSearchUrl))
                appDb.bookSourceDao.enable(false, sources.filterNot(BookSourcePart::hasSearchUrl))
            } else {
                appDb.bookSourceDao.enable(false, sources)
            }
            if (exploreEnabled) {
                appDb.bookSourceDao.enableExplore(true, sources.filter(BookSourcePart::hasExploreUrl))
                appDb.bookSourceDao.enableExplore(false, sources.filterNot(BookSourcePart::hasExploreUrl))
            } else {
                appDb.bookSourceDao.enableExplore(false, sources)
            }
        }
    }

    fun updateSelectionType(
        sources: List<BookSourcePart>,
        @BookSourceType.Type bookSourceType: Int,
    ) {
        if (sources.isEmpty()) return
        execute {
            appDb.bookSourceDao.upType(bookSourceType, sources)
        }
    }

    /**
     * 去重模式下批量禁用所有非唯一重复书源的搜索与发现，避免重复访问同一源URL。
     */
    fun disableNonPrimaryDuplicates(
        mode: BookSourceDedupeMode,
        preferredPrimaries: Map<String, String>,
    ) {
        if (mode == BookSourceDedupeMode.NONE) return
        execute {
            val allParts = appDb.bookSourceDao.allPart
            if (allParts.size <= 1) return@execute
            val scores = BookSourceDedupe.getCachedRuleScores().ifEmpty {
                appDb.bookSourceDao.allRuleScores()
                    .associate { it.bookSourceUrl to it.score }
                    .also(BookSourceDedupe::updateCachedRuleScores)
            }
            val tree = BookSourceDedupe.buildTree(allParts, mode, scores, preferredPrimaries)
            val nonPrimaries = ArrayList<BookSourcePart>()
            for (node in tree) {
                if (!node.hasDuplicates) continue
                val primaryUrl = node.primary.bookSourceUrl
                for (child in node.allSources) {
                    if (child.bookSourceUrl != primaryUrl && (child.enabled || child.enabledExplore)) {
                        nonPrimaries.add(child)
                    }
                }
            }
            if (nonPrimaries.isNotEmpty()) {
                appDb.bookSourceDao.enable(false, nonPrimaries)
                appDb.bookSourceDao.enableExplore(false, nonPrimaries)
            }
        }
    }

    /**
     * 切换重复组唯一主书源时：按能力启用新主书源，并禁用同组其他重复书源的搜索与发现。
     */
    fun switchDedupePrimaryEnabled(
        primary: BookSourcePart,
        groupSources: List<BookSourcePart>,
    ) {
        execute {
            appDb.bookSourceDao.enable(primary.bookSourceUrl, primary.hasSearchUrl)
            appDb.bookSourceDao.enableExplore(primary.bookSourceUrl, primary.hasExploreUrl)
            val others = groupSources.filter { it.bookSourceUrl != primary.bookSourceUrl }
            if (others.isNotEmpty()) {
                appDb.bookSourceDao.enable(false, others)
                appDb.bookSourceDao.enableExplore(false, others)
            }
        }
    }

    fun selectionAddToGroups(sources: List<BookSourcePart>, groups: String) {
        if (sources.isEmpty() || groups.isBlank()) return
        execute {
            val updates = LinkedHashMap<String, MutableList<String>>()
            for (source in sources) {
                val before = source.bookSourceGroup.orEmpty()
                val updated = source.copy().apply { addGroup(groups) }.bookSourceGroup.orEmpty()
                if (updated != before) {
                    updates.getOrPut(updated) { arrayListOf() }.add(source.bookSourceUrl)
                }
            }
            if (updates.isNotEmpty()) {
                appDb.bookSourceDao.upGroupBatch(updates)
            }
        }
    }

    fun selectionClearGroups(sources: List<BookSourcePart>) {
        if (sources.isEmpty()) return
        execute {
            val urls = sources
                .asSequence()
                .filter { !it.bookSourceGroup.isNullOrEmpty() }
                .map { it.bookSourceUrl }
                .distinct()
                .toList()
            if (urls.isNotEmpty()) {
                appDb.bookSourceDao.upGroupBatch(mapOf("" to urls))
            }
        }
    }

    internal fun selectionAutoGroup(
        sources: List<BookSourcePart>,
        selectedRuleTypes: Set<BookSourceAutoGroupRuleType>,
    ) {
        if (sources.isEmpty() || selectedRuleTypes.isEmpty()) return
        execute {
            val detectR18 = BookSourceAutoGroupRuleType.R18_DETECTION in selectedRuleTypes
            val otherRules = selectedRuleTypes - BookSourceAutoGroupRuleType.R18_DETECTION
            val r18Urls = if (detectR18) {
                val keys = sources.map(BookSourcePart::bookSourceUrl).distinct()
                val matched = HashSet<String>()
                keys.chunked(900).forEach { chunk ->
                    appDb.bookSourceDao.getR18ScanEntriesByKeys(chunk).forEach { entry ->
                        if (BookSourceR18Detector.isR18(
                                name = entry.bookSourceName,
                                url = entry.bookSourceUrl,
                                group = entry.bookSourceGroup,
                                comment = entry.bookSourceComment,
                                exploreUrl = entry.exploreUrl,
                                searchUrl = entry.searchUrl,
                            )
                        ) {
                            matched.add(entry.bookSourceUrl)
                        }
                    }
                }
                matched
            } else {
                emptySet()
            }

            // 仅勾选 R18 识别时：采用追加分组模式，保留书源原有分组
            if (otherRules.isEmpty()) {
                val updates = LinkedHashMap<String, MutableList<String>>()
                for (source in sources) {
                    if (source.bookSourceUrl !in r18Urls) continue
                    val before = source.bookSourceGroup.orEmpty()
                    val updated = source.copy()
                        .apply { addGroup(BookSourceR18Detector.R18_GROUP_NAME) }
                        .bookSourceGroup.orEmpty()
                    if (updated != before) {
                        updates.getOrPut(updated) { arrayListOf() }.add(source.bookSourceUrl)
                    }
                }
                if (updates.isNotEmpty()) {
                    appDb.bookSourceDao.upGroupBatch(updates)
                }
                context.toastOnUi("R18 识别完成：命中 ${r18Urls.size} 个书源")
                return@execute
            }

            val sharedBaseUrlGroups = if (
                BookSourceAutoGroupRuleType.URL_CATEGORY in otherRules
            ) {
                BookSourceAutoGroup.sharedBaseUrlGroups(
                    appDb.bookSourceDao.allPart.map { it.bookSourceUrl }
                )
            } else {
                emptyMap()
            }
            val needsDebugSource = BookSourceAutoGroupRuleType.DEBUG_FEATURES in otherRules
            val updates = LinkedHashMap<String, MutableList<String>>()
            for (source in sources) {
                val fullSource = if (needsDebugSource) {
                    appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
                } else {
                    null
                }
                val newGroups = autoGroupNames(
                    source = source,
                    fullSource = fullSource,
                    sharedBaseUrlGroup = sharedBaseUrlGroups[source.bookSourceUrl],
                    selectedRuleTypes = otherRules,
                ).toMutableList()
                if (detectR18 && source.bookSourceUrl in r18Urls &&
                    BookSourceR18Detector.R18_GROUP_NAME !in newGroups
                ) {
                    newGroups.add(BookSourceR18Detector.R18_GROUP_NAME)
                }
                val newGroupStr = newGroups.joinToString(",")
                if (newGroupStr != source.bookSourceGroup.orEmpty()) {
                    updates.getOrPut(newGroupStr) { arrayListOf() }.add(source.bookSourceUrl)
                }
            }
            if (updates.isNotEmpty()) {
                appDb.bookSourceDao.upGroupBatch(updates)
            }
            if (detectR18) {
                context.toastOnUi("自动分组完成，其中识别 R18 书源 ${r18Urls.size} 个")
            }
        }
    }

    private fun autoGroupNames(
        source: BookSourcePart,
        fullSource: BookSource?,
        sharedBaseUrlGroup: String?,
        selectedRuleTypes: Set<BookSourceAutoGroupRuleType>,
    ): List<String> {
        return buildList {
            if (BookSourceAutoGroupRuleType.SOURCE_CATEGORY in selectedRuleTypes) {
                add(
                    when (source.bookSourceType) {
                        BookSourceType.image -> "漫画"
                        BookSourceType.audio -> "音频"
                        BookSourceType.video -> "视频"
                        BookSourceType.file -> "其它"
                        else -> "小说"
                    }
                )
            }
            if (BookSourceAutoGroupRuleType.DEBUG_FEATURES in selectedRuleTypes) {
                if (source.hasLoginUrl) add("有登录入口")
                if (!source.hasSearchUrl) add("无搜索")
                if (source.hasExploreUrl) add("有发现")
                if (source.eventListener) add("事件监听")
                if (usesWebView(fullSource)) add("WebView")
                if (usesVerificationCode(fullSource)) add("有验证码")
            }
            if (BookSourceAutoGroupRuleType.URL_CATEGORY in selectedRuleTypes) {
                sharedBaseUrlGroup?.let(::add)
            }
        }
    }

    private fun usesWebView(source: BookSource?): Boolean {
        return autoGroupRuleTexts(source).any { webViewRuleRegex.containsMatchIn(it) }
    }

    private fun usesVerificationCode(source: BookSource?): Boolean {
        return source.hasVerificationCodeText() &&
                verificationCodeRuleTexts(source).any { hasActiveVerificationCodeCall(it) }
    }

    private fun autoGroupRuleTexts(source: BookSource?): List<String> {
        source ?: return emptyList()
        return listOfNotNull(
            source.bookUrlPattern,
            source.jsLib,
            source.header,
            source.loginUrl,
            source.loginUi,
            source.loginCheckJs,
            source.coverDecodeJs,
            source.exploreUrl,
            source.exploreScreen,
            source.searchUrl,
            source.ruleExplore?.let { GSON.toJson(it) },
            source.ruleSearch?.let { GSON.toJson(it) },
            source.ruleBookInfo?.let { GSON.toJson(it) },
            source.ruleToc?.let { GSON.toJson(it) },
            source.ruleContent?.let { GSON.toJson(it) },
            source.ruleReview?.let { GSON.toJson(it) },
            source.mainJs
        )
    }

    private fun verificationCodeRuleTexts(source: BookSource?): List<String> {
        source ?: return emptyList()
        return listOfNotNull(
            source.ruleSearch?.let { GSON.toJson(it) },
            source.ruleToc?.let { GSON.toJson(it) },
            source.ruleContent?.let { GSON.toJson(it) },
            source.mainJs,
        )
    }

    private fun BookSource?.hasVerificationCodeText(): Boolean {
        this ?: return false
        return listOfNotNull(
            bookSourceName,
            bookSourceComment,
            variableComment,
            searchUrl,
            mainJs,
            ruleSearch?.let { GSON.toJson(it) },
            ruleToc?.let { GSON.toJson(it) },
            ruleContent?.let { GSON.toJson(it) }
        ).any { it.contains("验证码") }
    }

    private fun hasActiveVerificationCodeCall(text: String): Boolean {
        return verificationCodeRuleRegex.findAll(text).any {
            !isInBlockComment(text, it.range.first) && !isInLineComment(text, it.range.first)
        }
    }

    private fun isInBlockComment(text: String, index: Int): Boolean {
        val openIndex = text.lastIndexOf("/*", index)
        if (openIndex < 0) return false
        val closeIndex = text.lastIndexOf("*/", index)
        return closeIndex < openIndex
    }

    private fun isInLineComment(text: String, index: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }
        var commentIndex = text.indexOf("//", lineStart)
        while (commentIndex in 0..<index) {
            if (commentIndex == 0 || text[commentIndex - 1] != ':') {
                return true
            }
            commentIndex = text.indexOf("//", commentIndex + 2)
        }
        return false
    }

    companion object {
        private val webViewRuleRegex = Regex(
            """@webjs:|["']?webView["']?\s*[:=]\s*(true|1|"true"|'true')|java\.webView(?:GetSource|GetOverrideUrl)?\s*\(|\bwebView(?:Await|GetSourceAwait|GetOverrideUrlAwait)?\s*\(""",
            RegexOption.IGNORE_CASE
        )
        private val verificationCodeRuleRegex = Regex("""java\.getVerificationCode\s*\(""")
    }

    private fun saveToFile(sources: List<BookSource>, name: String, success: (file: File, name: String) -> Unit) {
        execute {
            val single = sources.singleOrNull()
            if (single != null && single.isJsSource()) {
                val outputName = "${single.bookSourceName.normalizeFileName()}.js"
                val file = File(context.cacheDir, outputName)
                file.writeText(single.mainJs.orEmpty())
                file to outputName
            } else {
                val path = "${context.filesDir}/shareBookSource.json"
                FileUtils.delete(path)
                val file = FileUtils.createFileWithReplace(path)
                file.outputStream().buffered().use {
                    GSON.writeToOutputStream(it, sources)
                }
                file to name
            }
        }.onSuccess {
            success.invoke(it.first, it.second)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    fun saveToFile(
        selection: List<BookSourcePart>,
        success: (file: File, name: String) -> Unit
    ) {
        execute {
            selection.toBookSource()
        }.onSuccess { sources ->
            val name = if (selection.size == 1) {
                "bookSource_${selection.first().bookSourceName.normalizeFileName()}.json"
            } else {
                val timestamp = java.text.SimpleDateFormat(
                    "yyyyMMddHHmm",
                    Locale.getDefault()
                ).format(Date())
                "bookSource_$timestamp.json"
            }
            saveToFile(sources, name, success)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    fun addGroup(group: String) {
        execute {
            appDb.bookSourceDao.assignGroupToNoGroup(group)
        }
    }

    fun upGroup(oldGroup: String, newGroup: String?) {
        execute {
            val entries = appDb.bookSourceDao.getGroupEntriesByGroup(oldGroup)
            val updates = LinkedHashMap<String, MutableList<String>>()
            for (entry in entries) {
                val rawGroup = entry.bookSourceGroup ?: continue
                val set = rawGroup.splitNotBlank(AppPattern.splitGroupRegex).toCollection(linkedSetOf())
                if (!set.remove(oldGroup)) continue
                if (!newGroup.isNullOrEmpty()) {
                    set.add(newGroup)
                }
                val updated = TextUtils.join(",", set)
                if (updated != rawGroup) {
                    updates.getOrPut(updated) { arrayListOf() }.add(entry.bookSourceUrl)
                }
            }
            if (updates.isNotEmpty()) {
                appDb.bookSourceDao.upGroupBatch(updates)
            }
        }
    }

    fun delGroup(group: String) {
        execute {
            val entries = appDb.bookSourceDao.getExactGroupEntriesByGroup(group)
            val updates = LinkedHashMap<String, MutableList<String>>()
            for (entry in entries) {
                val rawGroup = entry.bookSourceGroup ?: continue
                val set = rawGroup.splitNotBlank(AppPattern.splitGroupRegex).toCollection(linkedSetOf())
                if (!set.remove(group)) continue
                val updated = TextUtils.join(",", set)
                if (updated != rawGroup) {
                    updates.getOrPut(updated) { arrayListOf() }.add(entry.bookSourceUrl)
                }
            }
            if (updates.isNotEmpty()) {
                appDb.bookSourceDao.upGroupBatch(updates)
            }
        }
    }

    fun delGroupAndSources(group: String) {
        execute {
            SourceHelp.deleteBookSources(appDb.bookSourceDao.getExactByGroup(group))
        }
    }

}
