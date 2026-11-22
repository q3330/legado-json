package io.legado.app.ui.config.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.translate.LocalHyMtModelManager
import io.legado.app.help.translate.LocalHyMtModelSpec
import io.legado.app.help.translate.LocalHyMtTranslator
import io.legado.app.help.translate.LocalNmtModelManager
import io.legado.app.help.translate.LocalNmtModelSpec
import io.legado.app.help.translate.LocalNmtTranslator
import io.legado.app.help.translate.MlKitTranslator
import io.legado.app.help.translate.TranslateConfig
import io.legado.app.ui.design.components.NgButtonVariant
import io.legado.app.ui.design.components.NgStatusTagSpec
import io.legado.app.ui.design.components.NgStatusTagStyle
import io.legado.app.ui.design.components.NgStatusTagVariant
import io.legado.app.ui.design.components.compose.NgFormActionButton
import io.legado.app.ui.design.components.compose.NgFormActionButtonAppearance
import io.legado.app.ui.design.components.compose.NgFormDensity
import io.legado.app.ui.design.components.compose.NgFormSelectField
import io.legado.app.ui.design.components.compose.NgFormSelectOption
import io.legado.app.ui.design.components.compose.NgStatusTag
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.utils.ConvertUtils
import kotlinx.coroutines.launch

@Composable
internal fun NmtOfflineModelSection(
    arrowIcon: Painter,
    onImportNmtZip: (LocalNmtModelSpec) -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloadStates by LocalNmtModelManager.downloadStates.collectAsState()
    val installedRevision by LocalNmtModelManager.installedRevision.collectAsState()
    var mirrorUrl by remember { mutableStateOf(TranslateConfig.nmtMirrorBaseUrl) }
    var loadedIds by remember(installedRevision) { mutableStateOf(LocalNmtTranslator.loadedModelIds()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TranslateSectionCard("NMT 模型下载源与内存控制") {
            NgFormSelectField(
                label = "模型下载镜像",
                selectedValue = mirrorUrl,
                options = listOf(
                    NgFormSelectOption(label = "国内镜像 (hf-mirror.com)", value = "https://hf-mirror.com"),
                    NgFormSelectOption(label = "HuggingFace 官方源", value = "https://huggingface.co"),
                ),
                onValueChange = {
                    mirrorUrl = it
                    TranslateConfig.nmtMirrorBaseUrl = it
                },
                arrowIcon = arrowIcon,
                density = NgFormDensity.COMPACT,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "存储路径：${LocalNmtModelManager.modelsRoot.absolutePath}\n提示：仅安装「Opus-MT 日→英」与「Opus-MT 英→中」即可离线接力日译中，或直接下载「NLLB-200」覆盖 200 语种直译。",
                color = Color(NgTheme.colors.onSurfaceVariant),
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            NgFormActionButton(
                text = if (loadedIds.isEmpty()) "当前无常驻 NMT 模型" else "释放已加载模型内存 (${loadedIds.size})",
                onClick = {
                    LocalNmtTranslator.unloadAll()
                    loadedIds = emptySet()
                    onToast("已释放端侧 NMT 模型内存")
                },
                enabled = loadedIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                variant = NgButtonVariant.OUTLINE,
                appearance = NgFormActionButtonAppearance.SURFACE_CARD,
            )
        }

        LocalNmtModelManager.catalog.forEach { spec ->
            val installed = remember(installedRevision, spec.id) {
                LocalNmtModelManager.isInstalled(spec.id)
            }
            val sizeBytes = remember(installedRevision, spec.id) {
                if (installed) LocalNmtModelManager.installedSizeBytes(spec.id) else 0L
            }
            val dlState = downloadStates[spec.id]
            val isLoaded = spec.id in loadedIds

            TranslateSectionCard(spec.name) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NgStatusTag(
                        spec = when {
                            dlState?.downloading == true -> NgStatusTagSpec("下载中", NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT)
                            installed -> NgStatusTagSpec("已安装", NgStatusTagVariant.SUCCESS, NgStatusTagStyle.COMPACT)
                            else -> NgStatusTagSpec("未安装", NgStatusTagVariant.WARNING, NgStatusTagStyle.COMPACT)
                        },
                    )
                    NgStatusTag(
                        spec = NgStatusTagSpec(
                            text = if (installed && sizeBytes > 0L) ConvertUtils.formatFileSize(sizeBytes) else "约 ${spec.approxSizeMb} MB",
                            variant = NgStatusTagVariant.INFO,
                            style = NgStatusTagStyle.COMPACT,
                        ),
                    )
                    if (isLoaded) {
                        NgStatusTag(
                            spec = NgStatusTagSpec("内存中", NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = spec.summary,
                    color = Color(NgTheme.colors.onSurfaceVariant),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                if (dlState != null) {
                    Spacer(Modifier.height(8.dp))
                    if (dlState.downloading) {
                        val fileStep = "${dlState.currentFileIndex}/${dlState.totalFiles} · ${dlState.currentFileName}"
                        val progressText = if (dlState.totalBytes > 0L) {
                            val pct = ((dlState.downloadedBytes * 100f) / dlState.totalBytes).toInt().coerceIn(0, 100)
                            "$fileStep ($pct%)"
                        } else {
                            "$fileStep (${ConvertUtils.formatFileSize(dlState.downloadedBytes)})"
                        }
                        Text(
                            text = progressText,
                            color = Color(NgTheme.colors.primary),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        if (dlState.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { (dlState.downloadedBytes.toFloat() / dlState.totalBytes.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    } else if (!dlState.error.isNullOrBlank()) {
                        Text(
                            text = "下载失败：${dlState.error}",
                            color = colorResource(R.color.ng_error),
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (dlState?.downloading == true) {
                        NgFormActionButton(
                            text = "取消下载",
                            onClick = { LocalNmtModelManager.cancelDownload(spec.id) },
                            modifier = Modifier.weight(1f),
                            variant = NgButtonVariant.OUTLINE,
                            appearance = NgFormActionButtonAppearance.SURFACE_CARD,
                        )
                    } else {
                        NgFormActionButton(
                            text = if (installed) "重新校验" else "下载模型",
                            onClick = {
                                LocalNmtModelManager.startDownload(spec)
                                onToast("已开始下载 ${spec.name}")
                            },
                            modifier = Modifier.weight(1f),
                            variant = if (installed) NgButtonVariant.OUTLINE else NgButtonVariant.PRIMARY,
                            appearance = if (installed) NgFormActionButtonAppearance.SURFACE_CARD else NgFormActionButtonAppearance.DEFAULT,
                        )
                        NgFormActionButton(
                            text = "导入 ZIP",
                            onClick = { onImportNmtZip(spec) },
                            modifier = Modifier.weight(1f),
                            variant = NgButtonVariant.OUTLINE,
                            appearance = NgFormActionButtonAppearance.SURFACE_CARD,
                        )
                    }
                    if (installed) {
                        NgFormActionButton(
                            text = "删除",
                            onClick = {
                                LocalNmtModelManager.deleteModel(spec.id)
                                loadedIds = LocalNmtTranslator.loadedModelIds()
                                onToast("已删除 ${spec.name}")
                            },
                            modifier = Modifier.width(72.dp),
                            variant = NgButtonVariant.OUTLINE,
                            appearance = NgFormActionButtonAppearance.SURFACE_CARD,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MlKitOfflineModelSection(
    wifiOnly: Boolean,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val downloaded by MlKitTranslator.downloadedLanguagesFlow.collectAsState()
    val downloading by MlKitTranslator.downloadingLanguagesFlow.collectAsState()

    LaunchedEffect(Unit) {
        MlKitTranslator.refreshDownloadedModels()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        TranslateSectionCard("Google ML Kit 离线语言包 (${downloaded.size}/${MlKitTranslator.supportedLanguages.size})") {
            Text(
                text = "应用已内置独立 Google ML Kit 离线翻译 SDK，无需设备安装 Google Play 服务即可进行本地推理。每种语言包约 30MB；首次下载需能连接 Google 模型分发网络。",
                color = Color(NgTheme.colors.onSurfaceVariant),
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            NgFormActionButton(
                text = "刷新已安装语言包 (${downloaded.size})",
                onClick = {
                    scope.launch {
                        val refreshed = MlKitTranslator.refreshDownloadedModels()
                        onToast("当前已下载 ${refreshed.size} 个语言包")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                variant = NgButtonVariant.OUTLINE,
                appearance = NgFormActionButtonAppearance.SURFACE_CARD,
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MlKitTranslator.supportedLanguages.forEach { option ->
                    val isDownloaded = option.code in downloaded
                    val isDownloading = option.code in downloading
                    val rowShape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(rowShape)
                            .background(Color(NgTheme.colors.background))
                            .border(0.6.dp, colorResource(R.color.ng_translucent_management_card_stroke), rowShape)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = option.label,
                                color = Color(NgTheme.colors.onSurface),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            NgStatusTag(
                                spec = when {
                                    isDownloading -> NgStatusTagSpec("下载中...", NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT)
                                    isDownloaded -> NgStatusTagSpec("已下载", NgStatusTagVariant.SUCCESS, NgStatusTagStyle.COMPACT)
                                    else -> NgStatusTagSpec("未下载", NgStatusTagVariant.WARNING, NgStatusTagStyle.COMPACT)
                                },
                            )
                        }
                        if (isDownloaded) {
                            NgFormActionButton(
                                text = "删除",
                                onClick = {
                                    scope.launch {
                                        MlKitTranslator.deleteLanguage(option.code).fold(
                                            onSuccess = { onToast("已删除 ${option.label}") },
                                            onFailure = { onToast("删除失败：${it.localizedMessage ?: "未知错误"}") },
                                        )
                                    }
                                },
                                enabled = !isDownloading,
                                variant = NgButtonVariant.OUTLINE,
                                appearance = NgFormActionButtonAppearance.SURFACE_CARD,
                            )
                        } else {
                            NgFormActionButton(
                                text = if (isDownloading) "下载中..." else "下载",
                                onClick = {
                                    scope.launch {
                                        onToast("开始下载 ${option.label}...")
                                        MlKitTranslator.downloadLanguage(option.code, wifiOnly = wifiOnly).fold(
                                            onSuccess = { onToast("已下载 ${option.label}") },
                                            onFailure = { onToast("下载失败：${it.localizedMessage ?: "网络不可达"}") },
                                        )
                                    }
                                },
                                enabled = !isDownloading,
                                variant = NgButtonVariant.PRIMARY,
                                appearance = NgFormActionButtonAppearance.DEFAULT,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HyMtOfflineModelSection(
    arrowIcon: Painter,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    onImportHyMtGguf: (LocalHyMtModelSpec) -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloadStates by LocalHyMtModelManager.downloadStates.collectAsState()
    val installedRevision by LocalHyMtModelManager.installedRevision.collectAsState()
    var mirrorUrl by remember { mutableStateOf(TranslateConfig.hyMtMirrorBaseUrl) }
    val loadedId by LocalHyMtTranslator.loadedModelIdFlow.collectAsState()
    val loadedLabel = remember(loadedId) {
        loadedId?.let { id -> LocalHyMtModelManager.findSpec(id)?.badgeLabel ?: id }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TranslateSectionCard("端侧大模型 (GGUF) 下载源与内存控制") {
            NgFormSelectField(
                label = "模型下载镜像",
                selectedValue = mirrorUrl,
                options = listOf(
                    NgFormSelectOption(label = "国内镜像 (hf-mirror.com)", value = "https://hf-mirror.com"),
                    NgFormSelectOption(label = "HuggingFace 官方源", value = "https://huggingface.co"),
                ),
                onValueChange = {
                    mirrorUrl = it
                    TranslateConfig.hyMtMirrorBaseUrl = it
                },
                arrowIcon = arrowIcon,
                density = NgFormDensity.COMPACT,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "存储路径：${LocalHyMtModelManager.modelsRoot.absolutePath}\n支持腾讯 Hy-MT2 与 PFN PLaMo-3 等通用 GGUF 模型，单次翻译完成后自动卸载释放内存；可在引擎列表右上角「+」新增多个端侧引擎绑定不同模型以对比译文。",
                color = Color(NgTheme.colors.onSurfaceVariant),
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            NgFormActionButton(
                text = if (loadedLabel == null) "当前未加载端侧 GGUF 模型" else "释放已加载模型 ($loadedLabel)",
                onClick = {
                    LocalHyMtTranslator.unloadAsync()
                    onToast("已释放端侧大模型内存")
                },
                enabled = loadedId != null,
                modifier = Modifier.fillMaxWidth(),
                variant = NgButtonVariant.OUTLINE,
                appearance = NgFormActionButtonAppearance.SURFACE_CARD,
            )
        }

        LocalHyMtModelManager.catalog.forEach { spec ->
            val installed = remember(installedRevision, spec.id) {
                LocalHyMtModelManager.isInstalled(spec.id)
            }
            val sizeBytes = remember(installedRevision, spec.id) {
                if (installed) LocalHyMtModelManager.installedSizeBytes(spec.id) else 0L
            }
            val dlState = downloadStates[spec.id]
            val isLoaded = spec.id == loadedId
            val isSelected = spec.id == selectedModelId

            TranslateSectionCard(spec.name) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    NgStatusTag(
                        spec = when {
                            dlState?.downloading == true -> NgStatusTagSpec("下载中", NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT)
                            installed -> NgStatusTagSpec("已安装", NgStatusTagVariant.SUCCESS, NgStatusTagStyle.COMPACT)
                            else -> NgStatusTagSpec("未安装", NgStatusTagVariant.WARNING, NgStatusTagStyle.COMPACT)
                        },
                    )
                    NgStatusTag(
                        spec = NgStatusTagSpec(
                            text = spec.promptFormat.label,
                            variant = NgStatusTagVariant.INFO,
                            style = NgStatusTagStyle.COMPACT,
                        ),
                    )
                    if (installed && sizeBytes > 0L) {
                        NgStatusTag(
                            spec = NgStatusTagSpec(
                                text = ConvertUtils.formatFileSize(sizeBytes),
                                variant = NgStatusTagVariant.INFO,
                                style = NgStatusTagStyle.COMPACT,
                            ),
                        )
                    } else if (spec.approxSizeMb > 0) {
                        NgStatusTag(
                            spec = NgStatusTagSpec(
                                text = "约 ${spec.approxSizeMb} MB",
                                variant = NgStatusTagVariant.INFO,
                                style = NgStatusTagStyle.COMPACT,
                            ),
                        )
                    }
                    if (isSelected) {
                        NgStatusTag(
                            spec = NgStatusTagSpec("当前已选", NgStatusTagVariant.SUCCESS, NgStatusTagStyle.COMPACT),
                        )
                    }
                    if (isLoaded) {
                        NgStatusTag(
                            spec = NgStatusTagSpec("内存中", NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = spec.summary,
                    color = Color(NgTheme.colors.onSurfaceVariant),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                if (dlState != null) {
                    Spacer(Modifier.height(8.dp))
                    if (dlState.downloading) {
                        val progressText = if (dlState.totalBytes > 0L) {
                            val pct = ((dlState.downloadedBytes * 100f) / dlState.totalBytes).toInt().coerceIn(0, 100)
                            "${ConvertUtils.formatFileSize(dlState.downloadedBytes)} / ${ConvertUtils.formatFileSize(dlState.totalBytes)} ($pct%)"
                        } else {
                            ConvertUtils.formatFileSize(dlState.downloadedBytes)
                        }
                        Text(
                            text = progressText,
                            color = Color(NgTheme.colors.primary),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        if (dlState.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { (dlState.downloadedBytes.toFloat() / dlState.totalBytes.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    } else if (!dlState.error.isNullOrBlank()) {
                        Text(
                            text = "下载失败：${dlState.error}",
                            color = colorResource(R.color.ng_error),
                            fontSize = 12.sp,
                        )
                    }
                }
                if (installed && !isSelected && dlState?.downloading != true) {
                    Spacer(Modifier.height(10.dp))
                    NgFormActionButton(
                        text = "选用此模型",
                        onClick = {
                            onSelectModel(spec.id)
                            onToast("已选用 ${spec.name}，请点击上方保存生效")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        variant = NgButtonVariant.PRIMARY,
                        appearance = NgFormActionButtonAppearance.DEFAULT,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (dlState?.downloading == true) {
                        NgFormActionButton(
                            text = "取消下载",
                            onClick = { LocalHyMtModelManager.cancelDownload(spec.id) },
                            modifier = Modifier.weight(1f),
                            variant = NgButtonVariant.OUTLINE,
                            appearance = NgFormActionButtonAppearance.SURFACE_CARD,
                        )
                    } else {
                        if (spec.downloadable) {
                            NgFormActionButton(
                                text = if (installed) "重新下载" else "下载模型",
                                onClick = {
                                    LocalHyMtModelManager.startDownload(spec)
                                    onToast("已开始下载 ${spec.name}")
                                },
                                modifier = Modifier.weight(1f),
                                variant = if (installed) NgButtonVariant.OUTLINE else NgButtonVariant.PRIMARY,
                                appearance = if (installed) NgFormActionButtonAppearance.SURFACE_CARD else NgFormActionButtonAppearance.DEFAULT,
                            )
                        }
                        NgFormActionButton(
                            text = "导入 GGUF",
                            onClick = { onImportHyMtGguf(spec) },
                            modifier = Modifier.weight(1f),
                            variant = if (!spec.downloadable && !installed) NgButtonVariant.PRIMARY else NgButtonVariant.OUTLINE,
                            appearance = if (!spec.downloadable && !installed) NgFormActionButtonAppearance.DEFAULT else NgFormActionButtonAppearance.SURFACE_CARD,
                        )
                    }
                    if (installed) {
                        NgFormActionButton(
                            text = "删除",
                            onClick = {
                                LocalHyMtModelManager.deleteModel(spec.id)
                                onToast("已删除 ${spec.name}")
                            },
                            modifier = Modifier.width(72.dp),
                            variant = NgButtonVariant.OUTLINE,
                            appearance = NgFormActionButtonAppearance.SURFACE_CARD,
                        )
                    }
                }
            }
        }
    }
}
