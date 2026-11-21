package io.legado.app.help.source

import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.CacheManager
import io.legado.app.utils.GSON
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

enum class BookSourceDedupeMode(val value: Int) {
    NONE(0),
    BY_URL(1),
    BY_NAME(2);

    companion object {
        fun from(value: Int): BookSourceDedupeMode =
            entries.firstOrNull { it.value == value } ?: NONE
    }
}

data class BookSourceDedupeNode(
    val groupKey: String,
    val primary: BookSourcePart,
    val allSources: List<BookSourcePart>,
) {
    val hasDuplicates: Boolean get() = allSources.size > 1
}

object BookSourceDedupe {

    private val preferredVersionCounter = AtomicLong(0L)
    val preferredVersion: Long get() = preferredVersionCounter.get()

    @Volatile
    private var cachedRuleScores: Map<String, Int> = emptyMap()

    fun getCachedRuleScores(): Map<String, Int> = cachedRuleScores

    fun updateCachedRuleScores(scores: Map<String, Int>) {
        cachedRuleScores = scores
    }

    private fun preferredCacheKey(mode: BookSourceDedupeMode): String =
        "bookSourceDedupePreferred_${mode.value}"

    fun loadPreferredPrimaries(mode: BookSourceDedupeMode): Map<String, String> {
        if (mode == BookSourceDedupeMode.NONE) return emptyMap()
        val raw = CacheManager.get(preferredCacheKey(mode)) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            GSON.fromJson<Map<String, String>>(raw, type).orEmpty()
        }.getOrDefault(emptyMap())
    }

    fun savePreferredPrimary(
        mode: BookSourceDedupeMode,
        groupKey: String,
        sourceUrl: String,
    ): Map<String, String> {
        if (mode == BookSourceDedupeMode.NONE) return emptyMap()
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

    private fun dedupeKey(source: BookSourcePart, mode: BookSourceDedupeMode): String = when (mode) {
        BookSourceDedupeMode.NONE -> source.bookSourceUrl
        BookSourceDedupeMode.BY_URL -> normalizeUrlKey(source.bookSourceUrl)
        BookSourceDedupeMode.BY_NAME -> normalizeNameKey(source.bookSourceName, source.bookSourceUrl)
    }

    private fun fallbackRuleScore(source: BookSourcePart): Int {
        var score = 0
        if (source.hasSearchUrl) score += 2
        if (source.hasExploreUrl) score += 2
        if (source.hasJs) score += 4
        return score
    }

    fun buildTree(
        sources: List<BookSourcePart>,
        mode: BookSourceDedupeMode,
        ruleScores: Map<String, Int> = emptyMap(),
        preferredPrimaries: Map<String, String> = emptyMap(),
    ): List<BookSourceDedupeNode> {
        if (sources.isEmpty()) return emptyList()
        if (mode == BookSourceDedupeMode.NONE) {
            return sources.map { source ->
                BookSourceDedupeNode(
                    groupKey = source.bookSourceUrl,
                    primary = source,
                    allSources = listOf(source),
                )
            }
        }
        val buckets = LinkedHashMap<String, MutableList<BookSourcePart>>(sources.size)
        for (source in sources) {
            val key = dedupeKey(source, mode)
            buckets.getOrPut(key) { arrayListOf() }.add(source)
        }
        return buckets.entries.map { (key, group) ->
            if (group.size == 1) {
                val only = group.first()
                BookSourceDedupeNode(
                    groupKey = key,
                    primary = only,
                    allSources = listOf(only),
                )
            } else {
                val preferredUrl = preferredPrimaries[key]
                val preferredIndex = if (!preferredUrl.isNullOrBlank()) {
                    group.indexOfFirst { it.bookSourceUrl == preferredUrl }
                } else {
                    -1
                }
                var bestIndex = if (preferredIndex >= 0) preferredIndex else 0
                if (preferredIndex < 0) {
                    var bestSource = group[0]
                    var bestScore = ruleScores[bestSource.bookSourceUrl] ?: fallbackRuleScore(bestSource)
                    for (i in 1 until group.size) {
                        val candidate = group[i]
                        val candidateScore = ruleScores[candidate.bookSourceUrl] ?: fallbackRuleScore(candidate)
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
                val orderedAll = ArrayList<BookSourcePart>(group.size)
                orderedAll.add(bestSource)
                for (i in group.indices) {
                    if (i != bestIndex) orderedAll.add(group[i])
                }
                BookSourceDedupeNode(
                    groupKey = key,
                    primary = bestSource,
                    allSources = orderedAll,
                )
            }
        }
    }

    fun dedupePrimarySources(
        sources: List<BookSourcePart>,
        mode: BookSourceDedupeMode,
        ruleScores: Map<String, Int> = emptyMap(),
        preferredPrimaries: Map<String, String> = emptyMap(),
    ): List<BookSourcePart> {
        if (mode == BookSourceDedupeMode.NONE || sources.size <= 1) return sources
        return buildTree(sources, mode, ruleScores, preferredPrimaries).map { it.primary }
    }
}
