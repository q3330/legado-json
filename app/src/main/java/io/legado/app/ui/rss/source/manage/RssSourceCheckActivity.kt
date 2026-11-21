package io.legado.app.ui.rss.source.manage

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.base.BaseActivity
import io.legado.app.base.ComposeActivityBinding
import io.legado.app.constant.BookSourceType
import io.legado.app.help.source.RssCheckItemStatus
import io.legado.app.help.source.RssCheckTaskState
import io.legado.app.help.source.RssCheckTaskStatus
import io.legado.app.help.source.RssSourceChecker
import io.legado.app.model.CheckSourceItemState
import io.legado.app.model.CheckSourceItemStatus
import io.legado.app.model.CheckSourceStage
import io.legado.app.model.CheckSourceTaskState
import io.legado.app.model.CheckSourceTaskStatus
import io.legado.app.ui.book.source.manage.BookSourceCheckScreen
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.utils.viewbindingdelegate.viewBinding

/** 订阅源批量校验进度与结果页，复用书源校验页面样式。 */
class RssSourceCheckActivity : BaseActivity<ComposeActivityBinding>() {

    override val binding by viewBinding(ComposeActivityBinding::inflate)
    override val bindNgToolbarMenu: Boolean = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.composeView.setContent {
            NgAppTheme {
                val rssState by RssSourceChecker.stateFlow.collectAsState()
                val mappedState = remember(rssState) { rssState.toBookCheckState() }
                LaunchedEffect(rssState.status) {
                    keepScreenOn(rssState.status == RssCheckTaskStatus.RUNNING)
                }
                DisposableEffect(Unit) {
                    onDispose { keepScreenOn(false) }
                }
                BookSourceCheckScreen(
                    state = mappedState,
                    titleText = "订阅源校验",
                    onBack = ::finish,
                    onCancel = { RssSourceChecker.stop() },
                    onHandleResults = ::openGroupedSourceManagement,
                )
            }
        }
    }

    private fun openGroupedSourceManagement() {
        startActivity(Intent(this, RssSourceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(RssSourceActivity.EXTRA_OPEN_GROUP_VIEW, true)
        })
        finish()
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private fun RssCheckTaskState.toBookCheckState(): CheckSourceTaskState {
    val mappedStatus = when (status) {
        RssCheckTaskStatus.IDLE -> CheckSourceTaskStatus.IDLE
        RssCheckTaskStatus.RUNNING -> CheckSourceTaskStatus.RUNNING
        RssCheckTaskStatus.COMPLETED -> CheckSourceTaskStatus.COMPLETED
        RssCheckTaskStatus.CANCELLED -> CheckSourceTaskStatus.CANCELLED
    }
    val mappedItems = items.map { item ->
        val itemStatus = when (item.status) {
            RssCheckItemStatus.WAITING -> CheckSourceItemStatus.WAITING
            RssCheckItemStatus.RUNNING -> CheckSourceItemStatus.RUNNING
            RssCheckItemStatus.PASSED -> CheckSourceItemStatus.PASSED
            RssCheckItemStatus.FAILED -> CheckSourceItemStatus.FAILED
        }
        CheckSourceItemState(
            origin = item.sourceUrl,
            sourceName = item.sourceName,
            sourceType = BookSourceType.default,
            status = itemStatus,
            stage = CheckSourceStage.DOMAIN,
            message = item.detail,
            durationMillis = item.elapsedMs,
            updatedAt = item.updatedAt,
        )
    }
    return CheckSourceTaskState(
        runId = runId,
        status = mappedStatus,
        items = mappedItems,
        currentSourceName = currentSourceName,
        currentStage = CheckSourceStage.DOMAIN,
        startedAtMillis = startedAtMillis,
        finishedAtMillis = finishedAtMillis,
        resultsAcknowledged = dismissed,
    )
}
