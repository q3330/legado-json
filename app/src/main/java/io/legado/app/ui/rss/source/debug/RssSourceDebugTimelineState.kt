package io.legado.app.ui.rss.source.debug

import io.legado.app.ui.book.source.debug.BookSourceDebugTimelineState

/** 订阅源调试时间线状态构建器，复用书源调试卡片与状态模型。 */
class RssSourceDebugTimelineState {

    private data class RawLog(val state: Int, val message: String, val elapsedMs: Long?)

    private val logs = mutableListOf<RawLog>()
    private val responses = mutableMapOf<Int, String>()
    private var sourceName: String = ""
    var items: List<BookSourceDebugTimelineState.DebugItem.Phase> = emptyList()
        private set

    fun setSourceName(name: String): List<BookSourceDebugTimelineState.DebugItem.Phase> {
        sourceName = name
        return if (logs.isNotEmpty()) refresh() else items
    }

    fun addLog(state: Int, msg: String): List<BookSourceDebugTimelineState.DebugItem.Phase> {
        logs.add(RawLog(state, msg, parseElapsedMs(msg)))
        return refresh()
    }

    fun addResponse(state: Int, msg: String): List<BookSourceDebugTimelineState.DebugItem.Phase> {
        responses[state] = msg
        return if (logs.isNotEmpty()) refresh() else items
    }

    fun clearLogs(): List<BookSourceDebugTimelineState.DebugItem.Phase> {
        logs.clear()
        responses.clear()
        items = emptyList()
        return items
    }

    fun rawText(phaseId: String): String {
        val grouped = groupByPhase()
        val list = grouped[phaseId].orEmpty()
        val body = list.joinToString("\n") { it.message }
        val response = when (phaseId) {
            "list" -> responses[10]
            "content" -> responses[20]
            else -> null
        }
        return buildString {
            if (body.isNotBlank()) append(body)
            if (!response.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n--- 原始响应 ---\n")
                append(response)
            }
        }
    }

    private fun refresh(): List<BookSourceDebugTimelineState.DebugItem.Phase> {
        val grouped = groupByPhase()
        val orderedIds = listOf("list", "content").filter { !grouped[it].isNullOrEmpty() || hasResponse(it) }
        if (orderedIds.isEmpty()) {
            items = emptyList()
            return items
        }
        val totalDuration = logs.mapNotNull { it.elapsedMs }.maxOrNull()
        val hasError = logs.any { isFailure(it.state, it.message) }
        val terminal = logs.any { it.state == -1 || it.state == 1000 }

        items = orderedIds.mapIndexed { index, id ->
            val phaseLogs = grouped[id].orEmpty()
            val title = if (id == "list") "列表解析" else "正文解析"
            val failedHere = phaseLogs.any { isFailure(it.state, it.message) }
            val status = when {
                failedHere -> BookSourceDebugTimelineState.StepStatus.Error
                hasError && index > 0 -> BookSourceDebugTimelineState.StepStatus.Pending
                terminal || index < orderedIds.lastIndex -> BookSourceDebugTimelineState.StepStatus.Success
                else -> BookSourceDebugTimelineState.StepStatus.Running
            }
            val startMs = phaseLogs.mapNotNull { it.elapsedMs }.minOrNull()
            val endMs = phaseLogs.mapNotNull { it.elapsedMs }.maxOrNull()
            val durationMs = if (startMs != null && endMs != null && endMs >= startMs) endMs - startMs else null
            val lastLine = phaseLogs.lastOrNull()?.message.orEmpty().trim()
            val hasResp = hasResponse(id)
            val meta = buildString {
                append("日志 ${phaseLogs.size} 条")
                if (hasResp) append(" · 含原始源码")
                endMs?.let { append(" · 累计 ${formatMs(it)}") }
                if (index == orderedIds.lastIndex && totalDuration != null) {
                    append(" · 总耗时 ${formatMs(totalDuration)}")
                }
            }
            BookSourceDebugTimelineState.DebugItem.Phase(
                phaseId = id,
                title = title,
                status = status,
                summary = when (status) {
                    BookSourceDebugTimelineState.StepStatus.Success -> "已完成"
                    BookSourceDebugTimelineState.StepStatus.Error -> "执行失败"
                    BookSourceDebugTimelineState.StepStatus.Running -> "正在执行"
                    BookSourceDebugTimelineState.StepStatus.Pending -> "未开始"
                } + if (sourceName.isNotBlank() && index == 0) " · $sourceName" else "",
                detail = lastLine,
                hasRawText = phaseLogs.isNotEmpty() || hasResp,
                hasResponse = hasResp,
                index = index,
                isLast = index == orderedIds.lastIndex,
                durationText = durationMs?.let(::formatMs) ?: "--",
                meta = meta,
            )
        }
        return items
    }

    private fun hasResponse(phaseId: String): Boolean = when (phaseId) {
        "list" -> !responses[10].isNullOrBlank()
        "content" -> !responses[20].isNullOrBlank()
        else -> false
    }

    private fun groupByPhase(): Map<String, List<RawLog>> {
        val result = linkedMapOf<String, MutableList<RawLog>>()
        var currentPhase = "list"
        for (raw in logs) {
            val msg = raw.message
            if (msg.contains("正文") || msg.contains("开始获取内容") || msg.contains("ruleContent")) {
                currentPhase = "content"
            } else if (msg.contains("列表") || msg.contains("开始解析")) {
                currentPhase = "list"
            }
            result.getOrPut(currentPhase) { arrayListOf() }.add(raw)
        }
        return result
    }

    private fun isFailure(state: Int, msg: String): Boolean =
        state == -1 || msg.contains("ERROR", ignoreCase = true) || msg.contains("失败")

    private fun parseElapsedMs(msg: String): Long? {
        val match = Regex("""^\[(\d+(?:\.\d+)?)s]""").find(msg.trim())
        if (match != null) {
            return (match.groupValues[1].toDoubleOrNull()?.times(1000.0))?.toLong()
        }
        val msMatch = Regex("""^\[(\d+)ms]""").find(msg.trim())
        return msMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun formatMs(ms: Long): String =
        if (ms >= 1000L) String.format(java.util.Locale.US, "%.2fs", ms / 1000.0) else "${ms}ms"
}
