package io.legado.app.ui.rss.source.debug

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.help.source.removeSortCache
import io.legado.app.help.source.sortUrls
import io.legado.app.ui.about.NetworkLogDialog
import io.legado.app.ui.book.source.debug.BookSourceDebugTimelineState
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.ui.rss.RssComposeBinding
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

class RssSourceDebugActivity :
    VMBaseActivity<RssComposeBinding, RssSourceDebugModel>() {

    override val binding by viewBinding(RssComposeBinding::inflate)
    override val viewModel by viewModels<RssSourceDebugModel>()

    private val timelineState = RssSourceDebugTimelineState()
    private var query by mutableStateOf("")
    private var helpVisible by mutableStateOf(true)
    private var sourceType by mutableIntStateOf(0)
    private var sortKinds by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var timelineItems by mutableStateOf<List<BookSourceDebugTimelineState.DebugItem.Phase>>(
        emptyList()
    )

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.root.setContent {
            NgAppTheme {
                RssSourceDebugScreen(
                    query = query,
                    helpVisible = helpVisible,
                    sourceType = sourceType,
                    sortKinds = sortKinds,
                    timelineItems = timelineItems,
                    onQueryChange = { query = it },
                    onSearch = ::startSearch,
                    onFocusChanged = { helpVisible = it },
                    onBack = ::finish,
                    onAction = ::onMenuAction,
                    onPhaseClick = ::openRawLog,
                )
            }
        }
        viewModel.initData(intent.getStringExtra("key")) {
            sourceType = viewModel.rssSource?.type ?: 0
            timelineItems = timelineState.setSourceName(viewModel.rssSource?.sourceName.orEmpty())
            loadSortKinds()
        }
        viewModel.observe { state, message ->
            lifecycleScope.launch {
                timelineItems = if (state == 10 || state == 20) {
                    timelineState.addResponse(state, message)
                } else {
                    timelineState.addLog(state, message)
                }
            }
        }
    }

    private fun loadSortKinds() {
        lifecycleScope.launch {
            sortKinds = viewModel.rssSource?.sortUrls()
                ?.filter { it.second.isNotBlank() }
                .orEmpty()
            sortKinds.firstOrNull { it.first.startsWith("ERROR:") }?.let {
                timelineItems = timelineState.addLog(-1, "获取分类出错\n${it.second}")
                helpVisible = false
            }
        }
    }

    private fun startSearch(value: String) {
        val normalized = value.ifBlank { "我的" }
        query = normalized
        helpVisible = false
        timelineItems = timelineState.clearLogs()
        timelineItems = timelineState.setSourceName(viewModel.rssSource?.sourceName.orEmpty())
        viewModel.startDebug(
            normalized,
            error = { toastOnUi("未获取到订阅源") },
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
            R.id.menu_list_src -> showDialogFragment(
                CodeDialog(viewModel.listSrc.orEmpty(), title = getString(R.string.list_src))
            )
            R.id.menu_content_src -> showDialogFragment(
                CodeDialog(viewModel.contentSrc.orEmpty(), title = getString(R.string.content_src))
            )
            R.id.menu_network_log -> showDialogFragment(NetworkLogDialog())
            RSS_DEBUG_REFRESH_SORT_ID -> lifecycleScope.launch {
                viewModel.rssSource?.removeSortCache()
                timelineItems = timelineState.clearLogs()
                helpVisible = true
                loadSortKinds()
            }
            R.id.menu_help -> showHelp("rssRuleHelp")
        }
    }
}
