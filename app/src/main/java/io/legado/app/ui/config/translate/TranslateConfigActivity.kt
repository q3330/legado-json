package io.legado.app.ui.config.translate

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.google.gson.GsonBuilder
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.ComposeActivityBinding
import io.legado.app.help.translate.BookTitleTranslator
import io.legado.app.help.translate.LocalHyMtModelManager
import io.legado.app.help.translate.LocalHyMtModelSpec
import io.legado.app.help.translate.LocalNmtModelManager
import io.legado.app.help.translate.LocalNmtModelSpec
import io.legado.app.help.translate.TranslateConfig
import io.legado.app.help.translate.TranslateEngineItem
import io.legado.app.help.translate.TranslateHistoryStore
import io.legado.app.help.translate.TranslateProvider
import io.legado.app.help.translate.TranslatedBookHistory
import io.legado.app.help.translate.TranslatedChapterHistory
import io.legado.app.ui.design.components.NgButtonVariant
import io.legado.app.ui.design.components.NgManagementListCardVariant
import io.legado.app.ui.design.components.NgStatusTagSpec
import io.legado.app.ui.design.components.NgStatusTagStyle
import io.legado.app.ui.design.components.NgStatusTagVariant
import io.legado.app.ui.design.components.compose.NgExpandableActionMenu
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuItem
import io.legado.app.ui.design.components.compose.NgExpandableActionMenuVariant
import io.legado.app.ui.design.components.compose.NgFloatingSearchToolbar
import io.legado.app.ui.design.components.compose.NgFloatingTabBar
import io.legado.app.ui.design.components.compose.NgFloatingTabSpec
import io.legado.app.ui.design.components.compose.NgFloatingTitleToolbar
import io.legado.app.ui.design.components.compose.NgFloatingToolbarActionButton
import io.legado.app.ui.design.components.compose.NgFormActionButton
import io.legado.app.ui.design.components.compose.NgFormActionButtonAppearance
import io.legado.app.ui.design.components.compose.NgFormDensity
import io.legado.app.ui.design.components.compose.NgFormField
import io.legado.app.ui.design.components.compose.NgFormSelectField
import io.legado.app.ui.design.components.compose.NgFormSelectOption
import io.legado.app.ui.design.components.compose.NgFormSwitchRow
import io.legado.app.ui.design.components.compose.NgManagementLeadingIcon
import io.legado.app.ui.design.components.compose.NgManagementListCard
import io.legado.app.ui.design.components.compose.NgPopupToggleState
import io.legado.app.ui.design.components.compose.NgSwitchControl
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.utils.SelectFileContract
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MENU_ADD_ENGINE = 0x58000001
private const val MENU_RESET_ENGINES = 0x58000002
private const val MENU_RESET_COOLDOWN = 0x58000004

private val prettyGson by lazy {
    GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
}

class TranslateConfigActivity : BaseActivity<ComposeActivityBinding>(imageBg = false) {

    override val binding by viewBinding(ComposeActivityBinding::inflate)

    private var pendingImportModelSpec: LocalNmtModelSpec? = null
    private val importNmtZipLauncher = registerForActivityResult(SelectFileContract()) { uri ->
        val spec = pendingImportModelSpec
        pendingImportModelSpec = null
        if (uri == null || spec == null) return@registerForActivityResult
        lifecycleScope.launch {
            toastOnUi("正在导入 ${spec.name}...")
            val result = withContext(IO) {
                LocalNmtModelManager.importModelZip(this@TranslateConfigActivity, uri, spec)
            }
            result.fold(
                onSuccess = { toastOnUi("已成功导入 ${spec.name}") },
                onFailure = { err -> toastOnUi("导入失败：${err.localizedMessage ?: err.javaClass.simpleName}") },
            )
        }
    }

    private var pendingImportHyMtSpec: LocalHyMtModelSpec? = null
    private val importHyMtGgufLauncher = registerForActivityResult(SelectFileContract()) { uri ->
        val spec = pendingImportHyMtSpec
        pendingImportHyMtSpec = null
        if (uri == null || spec == null) return@registerForActivityResult
        lifecycleScope.launch {
            toastOnUi("正在导入 ${spec.name}...")
            val result = withContext(IO) {
                LocalHyMtModelManager.importGgufFile(this@TranslateConfigActivity, uri, spec)
            }
            result.fold(
                onSuccess = { toastOnUi("已成功导入 ${spec.name}") },
                onFailure = { err -> toastOnUi("导入失败：${err.localizedMessage ?: err.javaClass.simpleName}") },
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeView.setContent {
            NgAppTheme {
                TranslateConfigRoot(
                    onFinish = ::finish,
                    onToast = ::toastOnUi,
                    onCopy = { text -> sendToClip(text) },
                    onReadClip = { getClipText() },
                    onImportNmtZip = { spec ->
                        pendingImportModelSpec = spec
                        importNmtZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    onImportHyMtGguf = { spec ->
                        pendingImportHyMtSpec = spec
                        importHyMtGgufLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                )
            }
        }
    }
}

@Composable
private fun LeftSwipeToDelete(
    onDeleteRequested: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val currentDelete by rememberUpdatedState(onDeleteRequested)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.EndToStart -> true
                SwipeToDismissBoxValue.StartToEnd -> false
                SwipeToDismissBoxValue.Settled -> true
            }
        },
        positionalThreshold = { distance -> distance / 3f },
    )
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            currentDelete()
            dismissState.reset()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val shape = RoundedCornerShape(NgTheme.shapes.largeDp.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(colorResource(R.color.ng_error).copy(alpha = 0.88f))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_book_info_delete),
                        contentDescription = stringResource(R.string.delete),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.delete),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        content = content,
    )
}

@Composable
private fun TranslateConfigRoot(
    onFinish: () -> Unit,
    onToast: (String) -> Unit,
    onCopy: (String) -> Unit,
    onReadClip: () -> String?,
    onImportNmtZip: (LocalNmtModelSpec) -> Unit,
    onImportHyMtGguf: (LocalHyMtModelSpec) -> Unit,
) {
    var engines by remember { mutableStateOf(TranslateConfig.getEngines()) }
    var activeEngineId by remember { mutableStateOf<String?>(null) }
    var isCreatingEngine by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        TranslateHistoryStore.getAllBooks()
    }

    fun reloadEngines() {
        engines = TranslateConfig.getEngines()
    }

    val activeEngine = remember(engines, activeEngineId, isCreatingEngine) {
        if (isCreatingEngine) {
            TranslateEngineItem(
                name = "自定义 Google 翻译",
                type = TranslateProvider.GOOGLE,
                enabled = true,
                endpoint = TranslateProvider.GOOGLE.defaultEndpoint,
            )
        } else {
            activeEngineId?.let { id -> engines.firstOrNull { it.id == id } }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(NgTheme.colors.background))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        when {
            activeEngine == null -> {
                TranslateEngineListView(
                    engines = engines,
                    onBack = onFinish,
                    onClickEngine = { item ->
                        isCreatingEngine = false
                        activeEngineId = item.id
                    },
                    onToggleEngine = { item, checked ->
                        TranslateConfig.setEngineEnabled(item.id, checked)
                        reloadEngines()
                    },
                    onDeleteEngine = { item ->
                        TranslateConfig.deleteEngine(item.id)
                        reloadEngines()
                        onToast("已删除 ${item.name.ifBlank { item.type.label }}")
                    },
                    onAddEngine = {
                        isCreatingEngine = true
                        activeEngineId = null
                    },
                    onResetCooldowns = {
                        BookTitleTranslator.resetAllCooldowns()
                        onToast("已重置全部引擎冷却状态")
                    },
                    onResetDefault = {
                        TranslateConfig.resetDefaultEngines()
                        reloadEngines()
                        onToast("已恢复默认翻译引擎")
                    },
                )
            }
            else -> {
                TranslateEngineDetailView(
                    engine = activeEngine,
                    isNew = isCreatingEngine,
                    onBack = {
                        isCreatingEngine = false
                        activeEngineId = null
                    },
                    onSaveEngine = { updated ->
                        TranslateConfig.upsertEngine(updated)
                        reloadEngines()
                        isCreatingEngine = false
                        activeEngineId = updated.id
                        onToast("已保存 ${updated.name}")
                    },
                    onDeleteEngine = if (isCreatingEngine || TranslateConfig.isBuiltInEngine(activeEngine.id)) null else {
                        {
                            TranslateConfig.deleteEngine(activeEngine.id)
                            reloadEngines()
                            activeEngineId = null
                            onToast("已删除 ${activeEngine.name.ifBlank { activeEngine.type.label }}")
                        }
                    },
                    onImportNmtZip = onImportNmtZip,
                    onImportHyMtGguf = onImportHyMtGguf,
                    onToast = onToast,
                    onCopy = onCopy,
                    onReadClip = onReadClip,
                )
            }
        }
    }
}

@Composable
private fun TranslateEngineListView(
    engines: List<TranslateEngineItem>,
    onBack: () -> Unit,
    onClickEngine: (TranslateEngineItem) -> Unit,
    onToggleEngine: (TranslateEngineItem, Boolean) -> Unit,
    onDeleteEngine: (TranslateEngineItem) -> Unit,
    onAddEngine: () -> Unit,
    onResetCooldowns: () -> Unit,
    onResetDefault: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val menuState = remember { NgPopupToggleState() }
    val historyBooks by TranslateHistoryStore.booksFlow.collectAsState()
    val healthRevision by BookTitleTranslator.healthRevision.collectAsState()
    val visibleEngines = remember(engines, query) {
        val q = query.trim()
        if (q.isEmpty()) engines
        else engines.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.endpoint.contains(q, ignoreCase = true) ||
                it.type.label.contains(q, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        NgFloatingSearchToolbar(
            query = query,
            onQueryChange = { query = it },
            hint = "搜索翻译引擎",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp),
            onSearch = {},
        ) {
            Box {
                NgFloatingToolbarActionButton(
                    iconRes = R.drawable.ic_grid_menu,
                    contentDescription = stringResource(R.string.menu),
                    onClick = menuState::onAnchorClick,
                )
                NgExpandableActionMenu(
                    expanded = menuState.expanded,
                    onDismissRequest = menuState::onDismissRequest,
                    items = listOf(
                        NgExpandableActionMenuItem(
                            itemId = MENU_ADD_ENGINE,
                            titleRes = R.string.add,
                            iconRes = R.drawable.ic_add,
                        ),
                        NgExpandableActionMenuItem(
                            itemId = MENU_RESET_COOLDOWN,
                            titleRes = 0,
                            title = "重置引擎冷却",
                            iconRes = R.drawable.ic_refresh_black_24dp,
                        ),
                        NgExpandableActionMenuItem(
                            itemId = MENU_RESET_ENGINES,
                            titleRes = R.string.ai_provider_reset,
                            iconRes = R.drawable.ic_restore,
                            dividerBefore = true,
                        ),
                    ),
                    variant = NgExpandableActionMenuVariant.SIDE_SLIDE,
                    menuContainerColor = colorResource(R.color.ng_surface_card),
                    properties = PopupProperties(focusable = true, clippingEnabled = false),
                    onItemClick = { item ->
                        menuState.close()
                        when (item.itemId) {
                            MENU_ADD_ENGINE -> onAddEngine()
                            MENU_RESET_COOLDOWN -> onResetCooldowns()
                            MENU_RESET_ENGINES -> onResetDefault()
                        }
                    },
                )
            }
        }

        if (visibleEngines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (engines.isEmpty()) "暂无翻译引擎" else "未找到匹配的翻译引擎",
                        color = Color(NgTheme.colors.onSurfaceVariant),
                        fontSize = 14.sp,
                    )
                    if (engines.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        NgFormActionButton(
                            text = "恢复默认引擎",
                            onClick = onResetDefault,
                            variant = NgButtonVariant.PRIMARY,
                            appearance = NgFormActionButtonAppearance.DEFAULT,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = visibleEngines,
                    key = TranslateEngineItem::id,
                ) { engine ->
                    val inCooldown = remember(healthRevision, engine.id) {
                        BookTitleTranslator.isEngineInCooldown(engine.id)
                    }
                    val cooldownSecs = remember(healthRevision, engine.id, inCooldown) {
                        if (inCooldown) BookTitleTranslator.cooldownRemainingSeconds(engine.id) else 0L
                    }
                    val engineHistoryCount = remember(historyBooks, engine.id) {
                        historyBooks.count { it.engineId == engine.id }
                    }
                    val keyTag = if (inCooldown) {
                        NgStatusTagSpec("冷却 ${cooldownSecs}s", NgStatusTagVariant.ERROR, NgStatusTagStyle.COMPACT)
                    } else {
                        when (engine.type) {
                            TranslateProvider.HY_MT_OFFLINE -> {
                                val badge = LocalHyMtModelManager.findSpec(engine.localModelId)?.badgeLabel ?: "本地 GGUF"
                                NgStatusTagSpec(badge, NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT)
                            }
                            TranslateProvider.LOCAL_NMT ->
                                NgStatusTagSpec("离线 NMT", NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT)
                            TranslateProvider.MLKIT_OFFLINE ->
                                NgStatusTagSpec("ML Kit 离线", NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT)
                            TranslateProvider.GOOGLE ->
                                NgStatusTagSpec("免 Key", NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT)
                        }
                    }
                    val statusTags = listOf(
                        NgStatusTagSpec(
                            text = stringResource(if (engine.enabled) R.string.enabled else R.string.disabled),
                            variant = if (engine.enabled) NgStatusTagVariant.SUCCESS else NgStatusTagVariant.WARNING,
                            style = NgStatusTagStyle.COMPACT,
                        ),
                        keyTag,
                    )

                    val hostLabel = remember(engine.endpoint, engine.type, engine.localModelId) {
                        when (engine.type) {
                            TranslateProvider.HY_MT_OFFLINE -> {
                                val resolvedSpec = LocalHyMtModelManager.findSpec(engine.localModelId)
                                resolvedSpec?.name ?: engine.localModelId.ifBlank {
                                    LocalHyMtModelManager.catalog.firstOrNull()?.name ?: "端侧 GGUF 模型"
                                }
                            }
                            TranslateProvider.LOCAL_NMT -> engine.localModelId.ifBlank { "自动按语种/接力路由" }
                            TranslateProvider.MLKIT_OFFLINE -> "内置独立离线推理"
                            TranslateProvider.GOOGLE -> {
                                val target = engine.endpoint.trim()
                                runCatching {
                                    java.net.URI(target.substringBefore(',')).host.orEmpty().ifBlank { target }
                                }.getOrDefault(target)
                            }
                        }
                    }
                    val summaryLabel = remember(hostLabel, engine.type.label, engineHistoryCount, engine.enabled, inCooldown) {
                        val base = hostLabel.ifBlank { engine.type.label }
                        when {
                            inCooldown -> "$base · 熔断冷却中"
                            engineHistoryCount > 0 -> "$base · ${engineHistoryCount}条记录"
                            else -> base
                        }
                    }

                    val isBuiltIn = TranslateConfig.isBuiltInEngine(engine.id)
                    val engineCard: @Composable () -> Unit = {
                        NgManagementListCard(
                            title = engine.name.ifBlank { engine.type.label },
                            variant = NgManagementListCardVariant.PROVIDER_TWO_ROW,
                            summaryTag = NgStatusTagSpec(
                                text = summaryLabel,
                                variant = NgStatusTagVariant.INFO,
                                style = NgStatusTagStyle.COMPACT,
                            ),
                            containerColor = colorResource(R.color.ng_translucent_management_card_surface),
                            borderColor = colorResource(R.color.ng_translucent_management_card_stroke),
                            borderWidth = 0.6.dp,
                            headerTags = statusTags,
                            trailingContent = {
                                NgSwitchControl(
                                    checked = engine.enabled,
                                    onCheckedChange = { onToggleEngine(engine, it) },
                                )
                            },
                            onClick = { onClickEngine(engine) },
                            leading = {
                                NgManagementLeadingIcon(
                                    iconRes = R.drawable.ic_translate,
                                    contentDescription = null,
                                    tint = Color(NgTheme.colors.primary),
                                    containerColor = colorResource(R.color.ng_translucent_management_avatar_surface),
                                )
                            },
                        )
                    }
                    if (isBuiltIn) {
                        engineCard()
                    } else {
                        LeftSwipeToDelete(
                            onDeleteRequested = { onDeleteEngine(engine) },
                        ) {
                            engineCard()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslateEngineDetailView(
    engine: TranslateEngineItem,
    isNew: Boolean,
    onBack: () -> Unit,
    onSaveEngine: (TranslateEngineItem) -> Unit,
    onDeleteEngine: (() -> Unit)?,
    onImportNmtZip: (LocalNmtModelSpec) -> Unit,
    onImportHyMtGguf: (LocalHyMtModelSpec) -> Unit,
    onToast: (String) -> Unit,
    onCopy: (String) -> Unit,
    onReadClip: () -> String?,
) {
    var selectedTab by remember(engine.id) { mutableIntStateOf(0) }
    var selectedHistoryBookKey by remember(engine.id) { mutableStateOf<String?>(null) }
    var selectedHistoryChapterIndex by remember(engine.id) { mutableStateOf<Int?>(null) }

    BackHandler {
        when {
            selectedTab == 1 && selectedHistoryChapterIndex != null -> selectedHistoryChapterIndex = null
            selectedTab == 1 && selectedHistoryBookKey != null -> selectedHistoryBookKey = null
            else -> onBack()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when (selectedTab) {
            0 -> TranslateEngineConfigTab(
                engine = engine,
                isNew = isNew,
                onBack = onBack,
                onSaveEngine = onSaveEngine,
                onDeleteEngine = onDeleteEngine,
                onImportNmtZip = onImportNmtZip,
                onImportHyMtGguf = onImportHyMtGguf,
                onToast = onToast,
                onCopy = onCopy,
                onReadClip = onReadClip,
                modifier = Modifier.weight(1f),
            )
            else -> TranslateHistoryManageTab(
                engineId = engine.id,
                selectedBookKey = selectedHistoryBookKey,
                selectedChapterIndex = selectedHistoryChapterIndex,
                onSelectBook = {
                    selectedHistoryBookKey = it
                    selectedHistoryChapterIndex = null
                },
                onSelectChapter = { selectedHistoryChapterIndex = it },
                onBackToRoot = onBack,
                onToast = onToast,
                onCopy = onCopy,
                modifier = Modifier.weight(1f),
            )
        }
        NgFloatingTabBar(
            items = listOf(
                NgFloatingTabSpec(
                    text = "翻译配置",
                    iconRes = R.drawable.ic_ai_tab_config,
                    contentDescription = "翻译配置",
                ),
                NgFloatingTabSpec(
                    text = "翻译记录",
                    iconRes = R.drawable.ic_chapter_list,
                    contentDescription = "翻译记录",
                ),
            ),
            selectedIndex = selectedTab,
            onTabSelected = { selectedTab = it },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun TranslateEngineConfigTab(
    engine: TranslateEngineItem,
    isNew: Boolean,
    onBack: () -> Unit,
    onSaveEngine: (TranslateEngineItem) -> Unit,
    onDeleteEngine: (() -> Unit)?,
    onImportNmtZip: (LocalNmtModelSpec) -> Unit,
    onImportHyMtGguf: (LocalHyMtModelSpec) -> Unit,
    onToast: (String) -> Unit,
    onCopy: (String) -> Unit,
    onReadClip: () -> String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val arrow = painterResource(R.drawable.ic_arrow_drop_down)
    val scrollState = rememberScrollState()

    val isBuiltIn = remember(engine.id, isNew) { !isNew && TranslateConfig.isBuiltInEngine(engine.id) }
    var jsonMode by remember(engine.id) { mutableStateOf(false) }
    var selectedType by remember(engine.id) { mutableStateOf(engine.type) }
    var name by remember(engine.id) { mutableStateOf(engine.name) }
    var endpoint by remember(engine.id) { mutableStateOf(engine.endpoint.ifBlank { engine.type.defaultEndpoint }) }
    var engineEnabled by remember(engine.id) { mutableStateOf(engine.enabled) }
    var localModelId by remember(engine.id) { mutableStateOf(engine.localModelId) }
    var mlkitWifiOnly by remember(engine.id) { mutableStateOf(engine.mlkitWifiOnly) }
    var targetLang by remember(engine.id) {
        mutableStateOf(engine.targetLang.ifBlank { TranslateConfig.targetLang })
    }

    var jsonText by remember(engine.id) { mutableStateOf("") }
    var jsonError by remember(engine.id) { mutableStateOf<String?>(null) }

    var globalEnabled by remember { mutableStateOf(TranslateConfig.enabled) }
    var globalIntro by remember { mutableStateOf(TranslateConfig.translateIntro) }
    var globalCatalog by remember { mutableStateOf(TranslateConfig.translateCatalog) }
    var globalContentDefault by remember { mutableStateOf(TranslateConfig.translateContentDefault) }

    var testInput by remember { mutableStateOf("New Action Movie & Free Videos") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val healthRevision by BookTitleTranslator.healthRevision.collectAsState()
    val nmtInstalledRevision by LocalNmtModelManager.installedRevision.collectAsState()
    val hymtInstalledRevision by LocalHyMtModelManager.installedRevision.collectAsState()
    val inCooldown = remember(healthRevision, engine.id) {
        BookTitleTranslator.isEngineInCooldown(engine.id)
    }

    fun buildFormDraft(): TranslateEngineItem = engine.copy(
        name = name.trim().ifBlank { selectedType.label },
        type = selectedType,
        enabled = engineEnabled,
        endpoint = endpoint.trim().ifBlank { selectedType.defaultEndpoint },
        targetLang = targetLang,
        localModelId = localModelId.trim(),
        mlkitWifiOnly = mlkitWifiOnly,
    ).normalized()

    fun parseJsonDraft(raw: String): Result<TranslateEngineItem> = runCatching {
        val parsed = prettyGson.fromJson(raw, TranslateEngineItem::class.java)
            ?: error("JSON 内容为空")
        @Suppress("SENSELESS_ELVIS")
        val parsedType: TranslateProvider? = parsed.type
        if (parsedType == null) {
            throw IllegalArgumentException("不支持的引擎类型，仅支持 HY_MT_OFFLINE / LOCAL_NMT / MLKIT_OFFLINE / GOOGLE")
        }
        val resolvedId = if (isBuiltIn) engine.id else parsed.id.ifBlank { engine.id }
        val resolvedType = if (isBuiltIn) engine.type else parsedType
        parsed.copy(id = resolvedId, type = resolvedType).normalized()
    }

    fun applyDraftToForm(item: TranslateEngineItem) {
        selectedType = item.type
        name = item.name
        endpoint = item.endpoint
        engineEnabled = item.enabled
        localModelId = item.localModelId
        mlkitWifiOnly = item.mlkitWifiOnly
        if (item.targetLang.isNotBlank()) {
            targetLang = item.targetLang
            TranslateConfig.targetLang = item.targetLang
        }
    }

    fun toggleJsonMode() {
        if (!jsonMode) {
            jsonText = prettyGson.toJson(buildFormDraft())
            jsonError = null
            jsonMode = true
        } else {
            parseJsonDraft(jsonText).fold(
                onSuccess = { parsed ->
                    applyDraftToForm(parsed)
                    jsonError = null
                    jsonMode = false
                },
                onFailure = { err ->
                    jsonError = "JSON 格式有误：${err.localizedMessage ?: err.javaClass.simpleName}"
                },
            )
        }
    }

    fun currentDraftOrError(): TranslateEngineItem? {
        return if (jsonMode) {
            parseJsonDraft(jsonText).fold(
                onSuccess = { parsed ->
                    applyDraftToForm(parsed)
                    jsonError = null
                    parsed
                },
                onFailure = { err ->
                    jsonError = "JSON 格式有误：${err.localizedMessage ?: err.javaClass.simpleName}"
                    null
                },
            )
        } else {
            buildFormDraft()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        NgFloatingTitleToolbar(
            title = if (isNew) "新增翻译引擎" else name.ifBlank { selectedType.label },
            onBack = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            titleLeadingContent = {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(colorResource(R.color.ng_icon_container))
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_translate),
                        contentDescription = null,
                        tint = Color(NgTheme.colors.primary),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (jsonMode) Color(NgTheme.colors.primary).copy(alpha = 0.14f)
                                else Color.Transparent
                            ),
                    ) {
                        NgFloatingToolbarActionButton(
                            iconRes = R.drawable.ic_code,
                            contentDescription = if (jsonMode) "切换表单模式" else "切换 JSON 编辑模式",
                            onClick = ::toggleJsonMode,
                        )
                    }
                    if (onDeleteEngine != null) {
                        NgFloatingToolbarActionButton(
                            iconRes = R.drawable.ic_book_info_delete,
                            contentDescription = stringResource(R.string.delete),
                            onClick = onDeleteEngine,
                        )
                    }
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (jsonMode) {
                TranslateSectionCard("JSON 编辑器模式") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NgFormActionButton(
                            text = "复制 JSON",
                            onClick = {
                                onCopy(jsonText)
                                onToast("已复制翻译引擎 JSON")
                            },
                            modifier = Modifier.weight(1f),
                            variant = NgButtonVariant.OUTLINE,
                            appearance = NgFormActionButtonAppearance.SURFACE_CARD,
                        )
                        NgFormActionButton(
                            text = "粘贴导入",
                            onClick = {
                                val clip = onReadClip()?.trim().orEmpty()
                                if (clip.isEmpty()) {
                                    onToast("剪贴板为空")
                                } else {
                                    jsonText = clip
                                    parseJsonDraft(clip).fold(
                                        onSuccess = { parsed ->
                                            jsonText = prettyGson.toJson(parsed)
                                            applyDraftToForm(parsed)
                                            jsonError = null
                                            onToast("已导入并解析配置，点击保存生效")
                                        },
                                        onFailure = { err ->
                                            jsonError = "JSON 解析失败：${err.localizedMessage ?: err.javaClass.simpleName}"
                                        },
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            variant = NgButtonVariant.OUTLINE,
                            appearance = NgFormActionButtonAppearance.SURFACE_CARD,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    val editorShape = RoundedCornerShape(12.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp)
                            .clip(editorShape)
                            .background(Color(NgTheme.colors.background))
                            .border(0.8.dp, colorResource(R.color.ng_translucent_management_card_stroke), editorShape)
                            .padding(12.dp),
                    ) {
                        BasicTextField(
                            value = jsonText,
                            onValueChange = {
                                jsonText = it
                                jsonError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                color = Color(NgTheme.colors.onSurface),
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            cursorBrush = SolidColor(Color(NgTheme.colors.primary)),
                        )
                    }
                    jsonError?.let { err ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = err,
                            color = colorResource(R.color.ng_error),
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NgFormActionButton(
                            text = if (testing) "测试中..." else "测试连接",
                            onClick = {
                                val draft = currentDraftOrError() ?: return@NgFormActionButton
                                testing = true
                                testResult = null
                                scope.launch {
                                    val res = BookTitleTranslator.testEngine(
                                        engine = draft,
                                        sample = testInput,
                                        targetLang = draft.targetLang.ifBlank { targetLang },
                                    )
                                    testing = false
                                    testResult = res.fold(
                                        onSuccess = { "译文：$it" },
                                        onFailure = { "失败：${it.localizedMessage ?: it.javaClass.simpleName}" },
                                    )
                                }
                            },
                            enabled = !testing,
                            modifier = Modifier.weight(1f),
                            variant = NgButtonVariant.OUTLINE,
                            appearance = NgFormActionButtonAppearance.SURFACE_CARD,
                        )
                        NgFormActionButton(
                            text = stringResource(R.string.action_save),
                            onClick = {
                                val draft = currentDraftOrError() ?: return@NgFormActionButton
                                onSaveEngine(draft)
                            },
                            modifier = Modifier.weight(1f),
                            variant = NgButtonVariant.PRIMARY,
                            appearance = NgFormActionButtonAppearance.DEFAULT,
                        )
                    }
                    testResult?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = msg,
                            color = Color(NgTheme.colors.onSurface),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            } else {
                TranslateSectionCard("引擎接入配置") {
                    if (inCooldown) {
                        NgFormActionButton(
                            text = "重置本引擎熔断冷却 (${BookTitleTranslator.cooldownRemainingSeconds(engine.id)}s)",
                            onClick = {
                                BookTitleTranslator.resetEngineCooldown(engine.id)
                                onToast("已解除冷却")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            variant = NgButtonVariant.OUTLINE,
                            appearance = NgFormActionButtonAppearance.SURFACE_CARD,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (!isBuiltIn) {
                        NgFormSelectField(
                            label = "引擎类型",
                            selectedValue = selectedType.id,
                            options = TranslateProvider.entries.map {
                                NgFormSelectOption(label = it.label, value = it.id)
                            },
                            onValueChange = { id ->
                                val oldType = selectedType
                                val nextType = TranslateProvider.fromId(id)
                                selectedType = nextType
                                if (name.isBlank() || name == oldType.label || name == "自定义 Google 翻译") {
                                    name = nextType.label
                                }
                                if (endpoint.isBlank() || endpoint == oldType.defaultEndpoint) {
                                    endpoint = nextType.defaultEndpoint
                                }
                                if (nextType == TranslateProvider.HY_MT_OFFLINE && localModelId.isBlank()) {
                                    localModelId = LocalHyMtModelManager.catalog
                                        .firstOrNull { LocalHyMtModelManager.isInstalled(it.id) }?.id
                                        ?: LocalHyMtModelManager.catalog.firstOrNull()?.id.orEmpty()
                                } else if (oldType == TranslateProvider.HY_MT_OFFLINE && nextType != TranslateProvider.HY_MT_OFFLINE) {
                                    localModelId = ""
                                }
                            },
                            arrowIcon = arrow,
                            density = NgFormDensity.COMPACT,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    NgFormField(
                        label = "引擎名称",
                        value = name,
                        onValueChange = { name = it },
                        density = NgFormDensity.COMPACT,
                    )
                    if (selectedType == TranslateProvider.GOOGLE) {
                        Spacer(Modifier.height(8.dp))
                        NgFormField(
                            label = "接入 API URL",
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            placeholder = selectedType.defaultEndpoint,
                            density = NgFormDensity.COMPACT,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    NgFormSelectField(
                        label = "目标语言",
                        selectedValue = targetLang,
                        options = TranslateConfig.supportedTargetLangs.map {
                            NgFormSelectOption(label = it.label, value = it.code)
                        },
                        onValueChange = {
                            targetLang = it
                            TranslateConfig.targetLang = it
                        },
                        arrowIcon = arrow,
                        density = NgFormDensity.COMPACT,
                    )
                    Spacer(Modifier.height(8.dp))
                    when (selectedType) {
                        TranslateProvider.HY_MT_OFFLINE -> {
                            val hymtOptions = remember(hymtInstalledRevision) {
                                LocalHyMtModelManager.catalog
                                    .filter { it.downloadable || LocalHyMtModelManager.isInstalled(it.id) }
                                    .ifEmpty { LocalHyMtModelManager.catalog }
                                    .map { spec ->
                                        val ok = LocalHyMtModelManager.isInstalled(spec.id)
                                        NgFormSelectOption(
                                            label = if (ok) "${spec.name} [已安装]" else "${spec.name} [未下载]",
                                            value = spec.id,
                                        )
                                    }
                            }
                            NgFormSelectField(
                                label = "端侧推理模型",
                                selectedValue = localModelId.ifBlank {
                                    LocalHyMtModelManager.catalog.firstOrNull()?.id.orEmpty()
                                },
                                options = hymtOptions,
                                onValueChange = { localModelId = it },
                                arrowIcon = arrow,
                                density = NgFormDensity.COMPACT,
                            )
                        }
                        TranslateProvider.LOCAL_NMT -> {
                            val nmtOptions = remember(nmtInstalledRevision) {
                                listOf(NgFormSelectOption(label = "自动按语种/接力路由", value = "")) +
                                    LocalNmtModelManager.catalog.map { spec ->
                                        val ok = LocalNmtModelManager.isInstalled(spec.id)
                                        NgFormSelectOption(
                                            label = if (ok) "${spec.name} [已安装]" else "${spec.name} [未下载]",
                                            value = spec.id,
                                        )
                                    }
                            }
                            NgFormSelectField(
                                label = "开源 NMT 模型",
                                selectedValue = localModelId,
                                options = nmtOptions,
                                onValueChange = { localModelId = it },
                                arrowIcon = arrow,
                                density = NgFormDensity.COMPACT,
                            )
                        }
                        TranslateProvider.MLKIT_OFFLINE -> {
                            NgFormSwitchRow(
                                title = "仅在 Wi-Fi 下自动拉取语言包",
                                checked = mlkitWifiOnly,
                                onCheckedChange = { mlkitWifiOnly = it },
                                density = NgFormDensity.COMPACT,
                            )
                        }
                        TranslateProvider.GOOGLE -> {
                            Text(
                                text = selectedType.summary,
                                color = Color(NgTheme.colors.onSurfaceVariant),
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    NgFormField(
                        label = "测试文本",
                        value = testInput,
                        onValueChange = { testInput = it },
                        density = NgFormDensity.COMPACT,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NgFormActionButton(
                            text = if (testing) "测试中..." else "测试连接",
                            onClick = {
                                val draft = buildFormDraft()
                                testing = true
                                testResult = null
                                scope.launch {
                                    val res = BookTitleTranslator.testEngine(
                                        engine = draft,
                                        sample = testInput,
                                        targetLang = targetLang,
                                    )
                                    testing = false
                                    testResult = res.fold(
                                        onSuccess = { "译文：$it" },
                                        onFailure = { "失败：${it.localizedMessage ?: it.javaClass.simpleName}" },
                                    )
                                }
                            },
                            enabled = !testing,
                            modifier = Modifier.weight(1f),
                            variant = NgButtonVariant.OUTLINE,
                            appearance = NgFormActionButtonAppearance.SURFACE_CARD,
                        )
                        NgFormActionButton(
                            text = stringResource(R.string.action_save),
                            onClick = { onSaveEngine(buildFormDraft()) },
                            modifier = Modifier.weight(1f),
                            variant = NgButtonVariant.PRIMARY,
                            appearance = NgFormActionButtonAppearance.DEFAULT,
                        )
                    }
                    if (testing) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "正在测试 ${name.ifBlank { selectedType.label }}...",
                                color = Color(NgTheme.colors.onSurfaceVariant),
                                fontSize = 12.sp,
                            )
                        }
                    }
                    testResult?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = msg,
                            color = Color(NgTheme.colors.onSurface),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
                when (selectedType) {
                    TranslateProvider.HY_MT_OFFLINE -> HyMtOfflineModelSection(
                        arrowIcon = arrow,
                        selectedModelId = localModelId.ifBlank {
                            LocalHyMtModelManager.catalog.firstOrNull()?.id.orEmpty()
                        },
                        onSelectModel = { localModelId = it },
                        onImportHyMtGguf = onImportHyMtGguf,
                        onToast = onToast,
                    )
                    TranslateProvider.LOCAL_NMT -> NmtOfflineModelSection(
                        arrowIcon = arrow,
                        onImportNmtZip = onImportNmtZip,
                        onToast = onToast,
                    )
                    TranslateProvider.MLKIT_OFFLINE -> MlKitOfflineModelSection(
                        wifiOnly = mlkitWifiOnly,
                        onToast = onToast,
                    )
                    TranslateProvider.GOOGLE -> Unit
                }
            }

            TranslateSectionCard("全局翻译设置") {
                NgFormSwitchRow(
                    title = "默认启用书籍列表与详情翻译",
                    checked = globalEnabled,
                    onCheckedChange = {
                        globalEnabled = it
                        TranslateConfig.enabled = it
                    },
                    density = NgFormDensity.COMPACT,
                )
                Spacer(Modifier.height(6.dp))
                NgFormSwitchRow(
                    title = "同时翻译详情页简介",
                    checked = globalIntro,
                    onCheckedChange = {
                        globalIntro = it
                        TranslateConfig.translateIntro = it
                    },
                    density = NgFormDensity.COMPACT,
                )
                Spacer(Modifier.height(6.dp))
                NgFormSwitchRow(
                    title = "默认启用目录与章节名翻译",
                    checked = globalCatalog,
                    onCheckedChange = {
                        globalCatalog = it
                        TranslateConfig.translateCatalog = it
                    },
                    density = NgFormDensity.COMPACT,
                )
                Spacer(Modifier.height(6.dp))
                NgFormSwitchRow(
                    title = "默认启用正文自动翻译",
                    checked = globalContentDefault,
                    onCheckedChange = {
                        globalContentDefault = it
                        TranslateConfig.translateContentDefault = it
                    },
                    density = NgFormDensity.COMPACT,
                )
            }
        }
    }
}

@Composable
internal fun TranslateSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(NgTheme.shapes.largeDp.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colorResource(R.color.ng_translucent_management_card_surface))
            .border(0.6.dp, colorResource(R.color.ng_translucent_management_card_stroke), shape)
            .padding(14.dp),
    ) {
        Text(
            text = title,
            color = Color(NgTheme.colors.primary),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}
