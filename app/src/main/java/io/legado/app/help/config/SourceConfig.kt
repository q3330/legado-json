package io.legado.app.help.config

import android.content.Context.MODE_PRIVATE
import androidx.core.content.edit
import io.legado.app.data.appDb
import splitties.init.appCtx

object SourceConfig {
    private val sp = appCtx.getSharedPreferences("SourceConfig", MODE_PRIVATE)
    fun setBookScore(origin: String, name: String, author: String, score: Int) {
        sp.edit {
            val preScore = getBookScore(origin, name, author)
            var newScore = score
            if (preScore != 0) {
                newScore = score - preScore
            }

            putInt(origin, getSourceScore(origin) + newScore)

            putInt("${origin}_${name}_${author}", score)
        }
    }

    fun getBookScore(origin: String, name: String, author: String): Int {
        return sp.getInt("${origin}_${name}_${author}", 0)
    }

    fun getSourceScore(origin: String): Int {
        return sp.getInt(origin, 0)
    }


    fun removeSource(origin: String) {
        removeSources(listOf(origin))
    }

    fun removeSources(origins: Collection<String>) {
        if (origins.isEmpty()) return
        val allKeys = sp.all.keys
        if (allKeys.isEmpty()) return
        val candidateKeys = matchingSourceConfigKeys(allKeys, origins, emptyList())
        if (candidateKeys.isEmpty()) return
        val keys = matchingSourceConfigKeys(candidateKeys, origins, appDb.cacheDao.sourceKeys())
        if (keys.isEmpty()) return
        sp.edit {
            keys.forEach { key ->
                remove(key)
            }
        }
    }


}

internal fun matchingSourceConfigKeys(
    keys: Collection<String>,
    origins: Collection<String>,
    retainedOrigins: Collection<String>,
): List<String> {
    if (keys.isEmpty() || origins.isEmpty()) return emptyList()
    val originSet = origins as? Set<String> ?: origins.toHashSet()
    fun belongsToSet(key: String, set: Set<String>): Boolean {
        if (key in set) return true
        var idx = key.indexOf('_')
        while (idx >= 0) {
            if (key.substring(0, idx) in set) return true
            idx = key.indexOf('_', idx + 1)
        }
        return false
    }
    val candidateKeys = keys.filter { key -> belongsToSet(key, originSet) }
    if (candidateKeys.isEmpty() || retainedOrigins.isEmpty()) return candidateKeys
    val retainedSet = retainedOrigins as? Set<String> ?: retainedOrigins.toHashSet()
    return candidateKeys.filterNot { key -> belongsToSet(key, retainedSet) }
}
