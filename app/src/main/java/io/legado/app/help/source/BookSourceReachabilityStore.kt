package io.legado.app.help.source

import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class BookSourceReachability {
    UNKNOWN,
    REACHABLE,
    UNREACHABLE,
}

object BookSourceReachabilityStore {

    private val persistScope = CoroutineScope(SupervisorJob() + IO)
    private val storeFile by lazy { File(appCtx.filesDir, "book_source_reachability.json") }
    private val map = ConcurrentHashMap<String, Boolean>()
    private val loaded = AtomicBoolean(false)
    private val persistScheduled = AtomicBoolean(false)

    private val _statusFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val statusFlow: StateFlow<Map<String, Boolean>>
        get() {
            ensureLoaded()
            return _statusFlow.asStateFlow()
        }

    private val unreachableGroupKeywords = listOf(
        "域名失效",
        "网站失效",
        "非HTTP源地址",
        "校验超时",
        "源地址不可访问",
        "发现失效",
    )

    private fun ensureLoaded() {
        if (!loaded.compareAndSet(false, true)) return
        runCatching {
            if (!storeFile.exists()) return
            val raw = storeFile.readText()
            if (raw.isBlank()) return
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            val parsed: Map<String, Boolean>? = GSON.fromJson(raw, type)
            parsed?.forEach { (k, v) ->
                if (k.isNotBlank()) map[k] = v
            }
        }
        _statusFlow.value = HashMap(map)
    }

    private fun schedulePersist() {
        if (!persistScheduled.compareAndSet(false, true)) return
        persistScope.launch {
            delay(1000)
            persistScheduled.set(false)
            runCatching {
                storeFile.writeText(GSON.toJson(HashMap(map)))
            }
        }
    }

    fun markReachable(sourceUrl: String?) {
        update(sourceUrl, true)
    }

    fun markUnreachable(sourceUrl: String?) {
        update(sourceUrl, false)
    }

    private fun update(sourceUrl: String?, reachable: Boolean) {
        val key = sourceUrl?.trim().orEmpty()
        if (key.isEmpty()) return
        ensureLoaded()
        val prev = map.put(key, reachable)
        if (prev != reachable) {
            _statusFlow.value = HashMap(map)
            schedulePersist()
        }
    }

    fun resolve(
        source: BookSourcePart,
        snapshot: Map<String, Boolean>,
    ): BookSourceReachability {
        val explicit = snapshot[source.bookSourceUrl]
        if (explicit != null) {
            return if (explicit) BookSourceReachability.REACHABLE else BookSourceReachability.UNREACHABLE
        }
        val group = source.bookSourceGroup.orEmpty()
        if (group.isNotBlank()) {
            if (unreachableGroupKeywords.any { group.contains(it) }) {
                return BookSourceReachability.UNREACHABLE
            }
            if (group.split(',', '，', ';', '；').any { it.trim() == "通过" }) {
                return BookSourceReachability.REACHABLE
            }
        }
        return BookSourceReachability.UNKNOWN
    }
}
