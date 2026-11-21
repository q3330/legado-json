package io.legado.app.ui.book.source.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.ComposeActivityBinding
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookSourceType
import io.legado.app.help.ai.AiSkillRegistry
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.ui.about.NetworkLogDialog
import io.legado.app.ui.config.AiChatActivity
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.launch
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

class BookSourceDebugActivity :
    VMBaseActivity<ComposeActivityBinding, BookSourceDebugModel>() {

    override val binding by viewBinding(ComposeActivityBinding::inflate)
    override val viewModel by viewModels<BookSourceDebugModel>()

    private val timelineState = BookSourceDebugTimelineState()
    private var query by mutableStateOf("")
    private var helpVisible by mutableStateOf(true)
    private var searchExample by mutableStateOf("我的")
    private var exploreExample by mutableStateOf("系统::http://xxx")
    private var exploreOptions by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var timelineItems by mutableStateOf<List<BookSourceDebugTimelineState.DebugItem>>(
        emptyList()
    )
    private var sourceType by mutableStateOf(BookSourceType.default)
    private var sourceName = ""

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it?.let(::startSearch)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeView.setContent {
            NgAppTheme {
                BookSourceDebugScreen(
                    query = query,
                    helpVisible = helpVisible,
                    sourceType = sourceType,
                    searchExample = searchExample,
                    exploreExample = exploreExample,
                    exploreOptions = exploreOptions,
                    timelineItems = timelineItems,
                    onQueryChange = { query = it },
                    onSearch = ::startSearch,
                    onFocusChanged = { helpVisible = it },
                    onBack = ::finish,
                    onAction = ::onMenuAction,
                    onExploreClick = {
                        if (!exploreExample.startsWith("ERROR:")) {
                            startSearch(exploreExample)
                        }
                    },
                    onExploreSelected = { title, url ->
                        exploreExample = "$title::$url"
                        startSearch(exploreExample)
                    },
                    onInfoClick = {
                        query.takeIf(String::isNotBlank)?.let(::startSearch)
                    },
                    onTocClick = { prefixAutoComplete("++") },
                    onContentClick = { prefixAutoComplete("--") },
                    onPhaseClick = ::openRawLog,
                    onAiRepair = ::launchAiRepair,
                )
            }
        }
        viewModel.init(intent.getStringExtra("key")) {
            sourceType = viewModel.bookSource?.bookSourceType ?: BookSourceType.default
            sourceName = viewModel.bookSource?.getDisPlayNameGroup().orEmpty()
            timelineItems = timelineState.setSourceName(sourceName)
            viewModel.bookSource?.ruleSearch?.checkKeyWord
                ?.takeIf(String::isNotBlank)
                ?.let { searchExample = it }
            initExploreKinds()
        }
        viewModel.observe { state, message ->
            lifecycleScope.launch {
                timelineItems = if (state in listOf(10, 20, 30, 40)) {
                    timelineState.addResponse(state, message)
                } else {
                    timelineState.addLog(state, message)
                }
            }
        }
    }

    private fun initExploreKinds() {
        lifecycleScope.launch {
            val kinds = viewModel.bookSource?.exploreKinds()
                ?.filter { !it.url.isNullOrBlank() }
                .orEmpty()
            exploreOptions = kinds.map { it.title to it.url.orEmpty() }
            exploreOptions.firstOrNull()?.let { (title, url) ->
                exploreExample = "$title::$url"
                if (title.startsWith("ERROR:")) {
                    timelineItems = timelineState.addLog(-1, "获取发现出错\n$url")
                    helpVisible = false
                }
            }
        }
    }

    private fun prefixAutoComplete(prefix: String) {
        when {
            query.isBlank() || query.length <= 2 -> query = prefix
            !query.startsWith(prefix) -> startSearch("$prefix$query")
            else -> startSearch(query)
        }
    }

    private fun startSearch(key: String) {
        query = key
        helpVisible = false
        timelineItems = timelineState.clearLogs()
        timelineItems = timelineState.setSourceName(sourceName)
        viewModel.startDebug(
            key,
            error = { toastOnUi("未获取到书源") },
        )
    }

    private fun openRawLog(item: BookSourceDebugTimelineState.DebugItem.Phase) {
        if (!item.hasRawText) return
        val rawText = timelineState.rawText(item.phaseId)
        if (rawText.isBlank()) return
        showDialogFragment(
            CodeDialog(
                code = rawText,
                title = item.title,
                highlightMode = CodeDialog.HighlightMode.DebugLog,
            )
        )
    }

    private fun onMenuAction(itemId: Int) {
        when (itemId) {
            R.id.menu_scan -> qrCodeResult.launch()
            R.id.menu_search_src -> showDialogFragment(
                CodeDialog(viewModel.searchSrc.orEmpty(), title = "html")
            )
            R.id.menu_book_src -> showDialogFragment(
                CodeDialog(viewModel.bookSrc.orEmpty(), title = "html")
            )
            R.id.menu_toc_src -> showDialogFragment(
                CodeDialog(viewModel.tocSrc.orEmpty(), title = "html")
            )
            R.id.menu_content_src -> showDialogFragment(
                CodeDialog(viewModel.contentSrc.orEmpty(), title = "html")
            )
            R.id.menu_ai_repair -> launchAiRepair()
            R.id.menu_network_log -> showDialogFragment(NetworkLogDialog())
            R.id.menu_refresh_explore -> lifecycleScope.launch {
                viewModel.bookSource?.clearExploreKindsCache()
                timelineItems = timelineState.clearLogs()
                helpVisible = true
                initExploreKinds()
            }
            R.id.menu_help -> showHelp("debugHelp")
        }
    }

    private fun launchAiRepair() {
        val source = viewModel.bookSource
        if (source == null) {
            toastOnUi("未获取到书源")
            return
        }
        val snapshot = timelineState.diagnosticSnapshot()
        val typeLabel = when (source.bookSourceType) {
            BookSourceType.audio -> "音频类 (1)"
            BookSourceType.image -> "图片/漫画类 (2)"
            BookSourceType.file -> "文件类 (3)"
            BookSourceType.video -> "视频类 (4)"
            else -> "文本类 (0)"
        }
        val sourceJson = runCatching { GSON.toJson(source) }.getOrDefault("")
            .let { if (it.length > 6_000) it.take(6_000) + "\n...(已截断，请用 book_source_get 读取完整书源)" else it }
        val prompt = buildString {
            appendLine("请帮我解析并修复以下正在调试的 Legado 书源规则：")
            appendLine()
            appendLine("### 书源信息")
            appendLine("- 名称：${source.bookSourceName}")
            appendLine("- 地址 (tag)：${source.bookSourceUrl}")
            appendLine("- 类型：$typeLabel")
            appendLine("- 分组：${source.bookSourceGroup.orEmpty().ifBlank { "无" }}")
            if (query.isNotBlank()) {
                appendLine("- 本次调试输入：$query")
            }
            if (snapshot.hasLogs) {
                appendLine()
                appendLine("### 调试阶段概览")
                snapshot.phaseStatusLines.forEach { appendLine(it) }
                if (snapshot.hasError) {
                    appendLine()
                    appendLine("### 报错步骤")
                    appendLine("- 失败阶段：${snapshot.failedPhaseTitle.orEmpty()}")
                    snapshot.failedPhaseSummary?.takeIf { it.isNotBlank() }?.let {
                        appendLine("- 错误摘要：$it")
                    }
                    snapshot.failedPhaseDetail?.takeIf { it.isNotBlank() }?.let {
                        appendLine("- 错误详情：\n$it")
                    }
                }
                appendLine()
                appendLine("### 调试日志")
                appendLine("```text")
                appendLine(snapshot.allLogsText)
                appendLine("```")
            } else {
                appendLine()
                appendLine("（当前尚未运行调试，请先根据书源配置进行抓取与调试分析。）")
            }
            appendLine()
            appendLine("### 当前书源 JSON")
            appendLine("```json")
            appendLine(sourceJson)
            appendLine("```")
            appendLine()
            appendLine("请使用已加载的书源制作与调试技能，通过 MCP 工具抓取真实网页、定位出错规则、用 `book_source_eval_rule` 和 `book_source_debug`（带 `source_override`）验证修复通过后，再调用 `book_source_save` 保存修复后的书源。")
        }
        val intent = Intent(this, AiChatActivity::class.java).apply {
            putStringArrayListExtra(
                AiChatActivity.EXTRA_LOADED_SKILL_IDS,
                arrayListOf(AiSkillRegistry.SKILL_BOOK_SOURCE_CREATOR)
            )
            putExtra(AiChatActivity.EXTRA_INITIAL_INPUT, prompt)
        }
        startActivity(intent)
    }
}
