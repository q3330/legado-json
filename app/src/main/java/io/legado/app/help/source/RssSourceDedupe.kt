package io.legado.app.help.source

import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.RssSource
import io.legado.app.help.CacheManager
import io.legado.app.utils.GSON
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

enum class RssSourceDedupeMode(val value: Int) {
    NONE(0),
    BY_URL(1),
    BY_NAME(2);

    companion object {
        fun from(value: Int): RssSourceDedupeMode =
            entries.firstOrNull { it.value == value } ?: NONE
    }
}

data class RssSourceDedupeNode(
    val groupKey: String,
    val primary: RssSource,
    val allSources: List<RssSource>,
) {
    val hasDuplicates: Boolean get() = allSources.size > 1
}

object RssSourceDedupe {

    private val preferredVersionCounter = AtomicLong(0L)
    val preferredVersion: Long get() = preferredVersionCounter.get()

    private fun preferredCacheKey(mode: RssSourceDedupeMode): String =
        "rssSourceDedupePreferred_${mode.value}"

    fun loadPreferredPrimaries(mode: RssSourceDedupeMode): Map<String, String> {
        if (mode == RssSourceDedupeMode.NONE) return emptyMap()
        val raw = CacheManager.get(preferredCacheKey(mode)) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            GSON.fromJson<Map<String, String>>(raw, type).orEmpty()
        }.getOrDefault(emptyMap())
    }

    fun savePreferredPrimary(
        mode: RssSourceDedupeMode,
        groupKey: String,
        sourceUrl: String,
    ): Map<String, String> {
        if (mode == RssSourceDedupeMode.NONE) return emptyMap()
        val updated = loadPreferredPrimaries(mode).toMutableMap()
        updated[groupKey] = sourceUrl
        CacheManager.put(preferredCacheKey(mode), GSON.toJson(updated))
        preferredVersionCounter.incrementAndGet()
        return updated
    }

    fun normalizeUrlKey(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        val withoutFragment = trimmed.substringBefore('#').trim()
        if (withoutFragment.isEmpty()) return trimmed.lowercase()
        val parsed = runCatching { URI(withoutFragment) }.getOrNull()
        val host = parsed?.host?.lowercase()
        if (!host.isNullOrBlank()) {
            val port = parsed.port.takeIf { it > 0 && it != 80 && it != 443 }
                ?.let { ":$it" }
                .orEmpty()
            val path = parsed.rawPath.orEmpty().trimEnd('/')
            return "$host$port$path"
        }
        return withoutFragment
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .lowercase()
    }

    fun normalizeNameKey(rawName: String, fallbackUrl: String): String {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return "url:${normalizeUrlKey(fallbackUrl)}"
        return trimmed.lowercase()
    }

    private fun dedupeKey(source: RssSource, mode: RssSourceDedupeMode): String = when (mode) {
        RssSourceDedupeMode.NONE -> source.sourceUrl
        RssSourceDedupeMode.BY_URL -> normalizeUrlKey(source.sourceUrl)
        RssSourceDedupeMode.BY_NAME -> normalizeNameKey(source.sourceName, source.sourceUrl)
    }

    private fun ruleScore(source: RssSource): Int {
        var score = 0
        if (!source.ruleArticles.isNullOrBlank()) score += 3
        if (!source.ruleTitle.isNullOrBlank()) score += 2
        if (!source.ruleLink.isNullOrBlank()) score += 2
        if (!source.ruleContent.isNullOrBlank()) score += 2
        if (!source.sortUrl.isNullOrBlank()) score += 1
        if (source.sourceIcon.isNotBlank()) score += 1
        return score
    }

    fun buildTree(
        sources: List<RssSource>,
        mode: RssSourceDedupeMode,
        preferredPrimaries: Map<String, String> = emptyMap(),
    ): List<RssSourceDedupeNode> {
        if (sources.isEmpty()) return emptyList()
        if (mode == RssSourceDedupeMode.NONE) {
            return sources.map { source ->
                RssSourceDedupeNode(
                    groupKey = source.sourceUrl,
                    primary = source,
                    allSources = listOf(source),
                )
            }
        }
        val buckets = LinkedHashMap<String, MutableList<RssSource>>(sources.size)
        for (source in sources) {
            val key = dedupeKey(source, mode)
            buckets.getOrPut(key) { arrayListOf() }.add(source)
        }
        return buckets.entries.map { (key, group) ->
            if (group.size == 1) {
                val only = group.first()
                RssSourceDedupeNode(
                    groupKey = key,
                    primary = only,
                    allSources = listOf(only),
                )
            } else {
                val preferredUrl = preferredPrimaries[key]
                val preferredIndex = if (!preferredUrl.isNullOrBlank()) {
                    group.indexOfFirst { it.sourceUrl == preferredUrl }
                } else {
                    -1
                }
                var bestIndex = if (preferredIndex >= 0) preferredIndex else 0
                if (preferredIndex < 0) {
                    var bestSource = group[0]
                    var bestScore = ruleScore(bestSource)
                    for (i in 1 until group.size) {
                        val candidate = group[i]
                        val candidateScore = ruleScore(candidate)
                        val isBetter = when {
                            candidateScore != bestScore -> candidateScore > bestScore
                            candidate.enabled != bestSource.enabled -> candidate.enabled
                            candidate.lastUpdateTime != bestSource.lastUpdateTime ->
                                candidate.lastUpdateTime > bestSource.lastUpdateTime
                            else -> candidate.customOrder < bestSource.customOrder
                        }
                        if (isBetter) {
                            bestIndex = i
                            bestSource = candidate
                            bestScore = candidateScore
                        }
                    }
                }
                val bestSource = group[bestIndex]
                val orderedAll = ArrayList<RssSource>(group.size)
                orderedAll.add(bestSource)
                for (i in group.indices) {
                    if (i != bestIndex) orderedAll.add(group[i])
                }
                RssSourceDedupeNode(
                    groupKey = key,
                    primary = bestSource,
                    allSources = orderedAll,
                )
            }
        }
    }
}
