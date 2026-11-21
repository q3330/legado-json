package io.legado.app.model

import android.content.Context
import io.legado.app.constant.IntentAction
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.service.CheckSourceService
import io.legado.app.utils.startService

object CheckSource {
    var keyword = "我的"

    //校验设置
    var timeout = CacheManager.getLong("checkSourceTimeout") ?: 180000L
    var wSourceComment = CacheManager.get("wSourceComment")?.toBoolean() ?: true
    var checkDomain = CacheManager.get("checkDomain")?.toBoolean() ?: false
    var syncSiteName = CacheManager.get("checkSourceSyncSiteName")?.toBoolean() ?: false
    var checkSearch = CacheManager.get("checkSearch")?.toBoolean() ?: true
    var checkDiscovery = CacheManager.get("checkDiscovery")?.toBoolean() ?: true
    var checkInfo = CacheManager.get("checkInfo")?.toBoolean() ?: true
    var checkCategory = CacheManager.get("checkCategory")?.toBoolean() ?: true
    var checkContent = CacheManager.get("checkContent")?.toBoolean() ?: true
    var blockSourceDialogs =
        CacheManager.get("checkSourceBlockDialogs")?.toBoolean() ?: true
    var textMinLength = CacheManager.getInt("checkSourceTextMinLength") ?: 50
    var checkImageReachable =
        CacheManager.get("checkSourceImageReachable")?.toBoolean() ?: true
    var imageSampleCount = CacheManager.getInt("checkSourceImageSampleCount") ?: 2
    var checkAudioReachable =
        CacheManager.get("checkSourceAudioReachable")?.toBoolean() ?: true
    var checkVideoReachable =
        CacheManager.get("checkSourceVideoReachable")?.toBoolean() ?: true
    var checkFileDownloadReachable =
        CacheManager.get("checkSourceFileDownloadReachable")?.toBoolean() ?: true

    fun start(context: Context, sources: List<BookSourcePart>) {
        CheckSourceTaskStore.begin(sources)
        Debug.isChecking = true
        val selectedIds = sources.map {
            it.bookSourceUrl
        }
        IntentData.put("checkSourceSelectedIds", selectedIds)
        context.startService<CheckSourceService> {
            action = IntentAction.start
        }
    }

    fun stop(context: Context) {
        context.startService<CheckSourceService> {
            action = IntentAction.stop
        }
    }

    fun resume(context: Context) {
        context.startService<CheckSourceService> {
            action = IntentAction.resume
        }
    }

    fun putConfig() {
        CacheManager.put("checkSourceTimeout", timeout)
        CacheManager.put("wSourceComment", wSourceComment)
        CacheManager.put("checkDomain", checkDomain)
        CacheManager.put("checkSourceSyncSiteName", syncSiteName)
        CacheManager.put("checkSearch", checkSearch)
        CacheManager.put("checkDiscovery", checkDiscovery)
        CacheManager.put("checkInfo", checkInfo)
        CacheManager.put("checkCategory", checkCategory)
        CacheManager.put("checkContent", checkContent)
        CacheManager.put("checkSourceBlockDialogs", blockSourceDialogs)
        CacheManager.put("checkSourceTextMinLength", textMinLength)
        CacheManager.put("checkSourceImageReachable", checkImageReachable)
        CacheManager.put("checkSourceImageSampleCount", imageSampleCount)
        CacheManager.put("checkSourceAudioReachable", checkAudioReachable)
        CacheManager.put("checkSourceVideoReachable", checkVideoReachable)
        CacheManager.put("checkSourceFileDownloadReachable", checkFileDownloadReachable)
    }

}
