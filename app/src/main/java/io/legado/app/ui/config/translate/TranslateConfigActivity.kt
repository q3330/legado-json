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
import io.legado.app.data.appDb
import io.legado.app.help.translate.BookTitleTranslator
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
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MENU_ADD_ENGINE = 0x58000001
private const val MENU_RESET_ENGINES = 0x58000002

private val prettyGson by lazy {
    GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
}

class TranslateConfigActivity : BaseActivity<ComposeActivityBinding>(imageBg = false) {

    override val binding by viewBinding(ComposeActivityBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeView.setContent {
            NgAppTheme {
                TranslateConfigRoot(
                    onFinish = ::finish,
                    onToast = ::toastOnUi,
                    onCopy = { text -> sendToClip(text) },
                    onReadClip = { getClipText() },
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
) {
    var engines by remember { mutableStateOf(TranslateConfig.getEngines()) }
    var activeEngineId by remember { mutableStateOf<String?>(null) }
    var isCreatingEngine by remember { mutableStateOf(false) }
    var dictRuleNames by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        dictRuleNames = withContext(IO) {
            appDb.dictRuleDao.enabled.map { it.name }
        }
        TranslateHistoryStore.getAllBooks()
    }

    fun reloadEngines() {
        engines = TranslateConfig.getEngines()
    }

    val activeEngine = remember(engines, activeEngineId, isCreatingEngine) {
        if (isCreatingEngine) {
            TranslateEngineItem(
                name = "自定义翻译",
                type = TranslateProvider.BING,
                enabled = true,
                endpoint = TranslateProvider.BING.defaultEndpoint,
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
        if (activeEngine == null) {
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
                onResetDefault = {
                    TranslateConfig.resetDefaultEngines()
                    reloadEngines()
                    onToast("已恢复默认翻译引擎")
                },
            )
        } else {
            TranslateEngineDetailView(
                engine = activeEngine,
                isNew = isCreatingEngine,
                dictRuleNames = dictRuleNames,
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
                onDeleteEngine = if (isCreatingEngine) null else {
                    {
                        TranslateConfig.deleteEngine(activeEngine.id)
                        reloadEngines()
                        activeEngineId = null
                        onToast("已删除 ${activeEngine.name.ifBlank { activeEngine.type.label }}")
                    }
                },
                onToast = onToast,
                onCopy = onCopy,
                onReadClip = onReadClip,
            )
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
    onResetDefault: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val menuState = remember { NgPopupToggleState() }
    val historyBooks by TranslateHistoryStore.booksFlow.collectAsState()
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
                    val keyTag = when (engine.type) {
                        TranslateProvider.BING, TranslateProvider.GOOGLE ->
                            NgStatusTagSpec("免 Key", NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT)
                        TranslateProvider.YOUDAO, TranslateProvider.BAIDU ->
                            if (engine.apiKey.isNotBlank() && engine.apiSecret.isNotBlank()) {
                                NgStatusTagSpec("已配置密钥", NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT)
                            } else {
                                NgStatusTagSpec("未配置密钥", NgStatusTagVariant.WARNING, NgStatusTagStyle.COMPACT)
                            }
                        TranslateProvider.DEEPL ->
                            when {
                                !engine.endpoint.contains("deepl.com", ignoreCase = true) && engine.endpoint.isNotBlank() ->
                                    NgStatusTagSpec("自定义 API", NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT)
                                engine.apiKey.isNotBlank() ->
                                    NgStatusTagSpec("已配置 Key", NgStatusTagVariant.INFO, NgStatusTagStyle.COMPACT)
                                else ->
                                    NgStatusTagSpec("未配置 Key", NgStatusTagVariant.WARNING, NgStatusTagStyle.COMPACT)
                            }
                        TranslateProvider.DICT_RULE ->
                            NgStatusTagSpec(
                                engine.dictRuleName.ifBlank { "自动选规则" },
                                NgStatusTagVariant.INFO,
                                NgStatusTagStyle.COMPACT,
                            )
                    }
                    val statusTags = listOf(
                        NgStatusTagSpec(
                            text = stringResource(if (engine.enabled) R.string.enabled else R.string.disabled),
                            variant = if (engine.enabled) NgStatusTagVariant.SUCCESS else NgStatusTagVariant.WARNING,
                            style = NgStatusTagStyle.COMPACT,
                        ),
                        keyTag,
                    )

                    val hostLabel = remember(engine.endpoint) {
                        runCatching {
                            val u = engine.endpoint.trim()
                            if (u.startsWith("dict://")) u
                            else java.net.URI(u).host.orEmpty().ifBlank { u }
                        }.getOrDefault(engine.endpoint)
                    }
                    val summaryLabel = remember(hostLabel, engine.type.label, historyBooks.size, engine.enabled) {
                        val base = hostLabel.ifBlank { engine.type.label }
                        if (historyBooks.isNotEmpty() && engine.enabled) "$base · ${historyBooks.size}条记录"
                        else base
                    }

                    LeftSwipeToDelete(
                        onDeleteRequested = { onDeleteEngine(engine) },
                    ) {
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
                }
            }
        }
    }
}

@Composable
private fun TranslateEngineDetailView(
    engine: TranslateEngineItem,
    isNew: Boolean,
    dictRuleNames: List<String>,
    onBack: () -> Unit,
    onSaveEngine: (TranslateEngineItem) -> Unit,
    onDeleteEngine: (() -> Unit)?,
    onToast: (String) -> Unit,
    onCopy: (String) -> Unit,
    onReadClip: () -> String?,
) {
    var selectedTab by remember(engine.id) { mutableIntStateOf(0) }
    var selectedHistoryBookKey by remember { mutableStateOf<String?>(null) }
    var selectedHistoryChapterIndex by remember { mutableStateOf<Int?>(null) }

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
                dictRuleNames = dictRuleNames,
                onBack = onBack,
                onSaveEngine = onSaveEngine,
                onDeleteEngine = onDeleteEngine,
                onToast = onToast,
                onCopy = onCopy,
                onReadClip = onReadClip,
                modifier = Modifier.weight(1f),
            )
            else -> TranslateHistoryTab(
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
    dictRuleNames: List<String>,
    onBack: () -> Unit,
    onSaveEngine: (TranslateEngineItem) -> Unit,
    onDeleteEngine: (() -> Unit)?,
    onToast: (String) -> Unit,
    onCopy: (String) -> Unit,
    onReadClip: () -> String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val arrow = painterResource(R.drawable.ic_arrow_drop_down)
    val scrollState = rememberScrollState()

    var jsonMode by remember(engine.id) { mutableStateOf(false) }
    var name by remember(engine.id) { mutableStateOf(engine.name) }
    var endpoint by remember(engine.id) { mutableStateOf(engine.endpoint.ifBlank { engine.type.defaultEndpoint }) }
    var engineEnabled by remember(engine.id) { mutableStateOf(engine.enabled) }
    var apiKey by remember(engine.id) { mutableStateOf(engine.apiKey) }
    var apiSecret by remember(engine.id) { mutableStateOf(engine.apiSecret) }
    var dictRuleName by remember(engine.id) {
        mutableStateOf(engine.dictRuleName.ifBlank { dictRuleNames.firstOrNull().orEmpty() })
    }
    var targetLang by remember(engine.id) {
        mutableStateOf(engine.targetLang.ifBlank { TranslateConfig.targetLang })
    }

    var jsonText by remember(engine.id) { mutableStateOf("") }
    var jsonError by remember(engine.id) { mutableStateOf<String?>(null) }

    var globalEnabled by remember { mutableStateOf(TranslateConfig.enabled) }
    var globalIntro by remember { mutableStateOf(TranslateConfig.translateIntro) }
    var globalCatalog by remember { mutableStateOf(TranslateConfig.translateCatalog) }
    var globalContentDefault by remember { mutableStateOf(TranslateConfig.translateContentDefault) }
    var cacheCount by remember { mutableIntStateOf(BookTitleTranslator.cacheSize()) }

    var testInput by remember { mutableStateOf("New Action Movie & Free Videos") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val inferredType = remember(endpoint, engine.type) {
        TranslateProvider.inferFromUrl(endpoint, engine.type)
    }

    fun buildFormDraft(): TranslateEngineItem = engine.copy(
        name = name.trim().ifBlank { inferredType.label },
        type = inferredType,
        enabled = engineEnabled,
        endpoint = endpoint.trim().ifBlank { inferredType.defaultEndpoint },
        targetLang = targetLang,
        apiKey = apiKey.trim(),
        apiSecret = apiSecret.trim(),
        dictRuleName = dictRuleName.trim(),
    ).normalized()

    fun parseJsonDraft(raw: String): Result<TranslateEngineItem> = runCatching {
        val parsed = prettyGson.fromJson(raw, TranslateEngineItem::class.java)
            ?: error("JSON 内容为空")
        val resolvedId = parsed.id.ifBlank { engine.id }
        parsed.copy(id = resolvedId).normalized()
    }

    fun applyDraftToForm(item: TranslateEngineItem) {
        name = item.name
        endpoint = item.endpoint
        engineEnabled = item.enabled
        apiKey = item.apiKey
        apiSecret = item.apiSecret
        dictRuleName = item.dictRuleName
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
            title = if (isNew) "新增翻译引擎" else name.ifBlank { inferredType.label },
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
                    NgFormSwitchRow(
                        title = "启用此翻译引擎",
                        checked = engineEnabled,
                        onCheckedChange = { engineEnabled = it },
                        density = NgFormDensity.COMPACT,
                    )
                    Spacer(Modifier.height(8.dp))
                    NgFormField(
                        label = "引擎名称",
                        value = name,
                        onValueChange = { name = it },
                        density = NgFormDensity.COMPACT,
                    )
                    Spacer(Modifier.height(8.dp))
                    NgFormField(
                        label = "接入 API URL",
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        placeholder = "https://... 或 dict://rule",
                        density = NgFormDensity.COMPACT,
                    )
                    Spacer(Modifier.height(8.dp))
                    NgFormSelectField(
                        label = "目标语言",
                        selectedValue = targetLang,
                        options = TranslateConfig.supportedTargetLangs.map {
                            NgFormSelectOption(it.code, it.label)
                        },
                        onValueChange = {
                            targetLang = it
                            TranslateConfig.targetLang = it
                        },
                        arrowIcon = arrow,
                        density = NgFormDensity.COMPACT,
                    )
                    Spacer(Modifier.height(8.dp))
                    when (inferredType) {
                        TranslateProvider.YOUDAO -> {
                            NgFormField(
                                label = "AppKey (应用 ID)",
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                density = NgFormDensity.COMPACT,
                            )
                            Spacer(Modifier.height(8.dp))
                            NgFormField(
                                label = "应用密钥 (AppSecret)",
                                value = apiSecret,
                                onValueChange = { apiSecret = it },
                                density = NgFormDensity.COMPACT,
                            )
                        }
                        TranslateProvider.BAIDU -> {
                            NgFormField(
                                label = "AppID",
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                density = NgFormDensity.COMPACT,
                            )
                            Spacer(Modifier.height(8.dp))
                            NgFormField(
                                label = "密钥 (SecretKey)",
                                value = apiSecret,
                                onValueChange = { apiSecret = it },
                                density = NgFormDensity.COMPACT,
                            )
                        }
                        TranslateProvider.DEEPL -> {
                            NgFormField(
                                label = "API Key / Auth Key（无鉴权接口可留空）",
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                density = NgFormDensity.COMPACT,
                            )
                        }
                        TranslateProvider.DICT_RULE -> {
                            if (dictRuleNames.isEmpty()) {
                                Text(
                                    text = "当前没有已启用的字典规则，请先到「规则管理 -> 字典规则」启用规则。",
                                    color = Color(NgTheme.colors.onSurfaceVariant),
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                )
                            } else {
                                NgFormSelectField(
                                    label = "使用的字典规则",
                                    selectedValue = dictRuleName,
                                    options = dictRuleNames.map { NgFormSelectOption(it, it) },
                                    onValueChange = { dictRuleName = it },
                                    arrowIcon = arrow,
                                    density = NgFormDensity.COMPACT,
                                )
                            }
                        }
                        else -> {
                            Text(
                                text = inferredType.summary,
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
                                text = "正在测试 ${name.ifBlank { inferredType.label }}...",
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
                Spacer(Modifier.height(10.dp))
                NgFormActionButton(
                    text = "清空临时译名缓存 ($cacheCount)",
                    onClick = {
                        BookTitleTranslator.clearCache()
                        cacheCount = 0
                        onToast("已清空临时译名缓存（离线翻译记录仍保留）")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = NgButtonVariant.OUTLINE,
                    appearance = NgFormActionButtonAppearance.SURFACE_CARD,
                )
            }
        }
    }
}

@Composable
private fun TranslateHistoryTab(
    selectedBookKey: String?,
    selectedChapterIndex: Int?,
    onSelectBook: (String?) -> Unit,
    onSelectChapter: (Int?) -> Unit,
    onBackToRoot: () -> Unit,
    onToast: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val books by TranslateHistoryStore.booksFlow.collectAsState()
    val selectedBook = remember(books, selectedBookKey) {
        selectedBookKey?.let { key -> books.firstOrNull { it.bookKey == key } }
    }
    val selectedChapter = remember(selectedBook, selectedChapterIndex) {
        selectedChapterIndex?.let { idx -> selectedBook?.chapters?.firstOrNull { it.chapterIndex == idx } }
    }

    when {
        selectedBook != null && selectedChapter != null -> {
            TranslateChapterRecordView(
                book = selectedBook,
                chapter = selectedChapter,
                onBack = { onSelectChapter(null) },
                onCopy = { text ->
                    onCopy(text)
                    onToast("已复制")
                },
                modifier = modifier,
            )
        }
        selectedBook != null -> {
            TranslateBookTocHistoryView(
                book = selectedBook,
                onBack = { onSelectBook(null) },
                onClickChapter = { onSelectChapter(it.chapterIndex) },
                onDeleteChapter = { chapter ->
                    TranslateHistoryStore.deleteChapter(selectedBook.bookKey, chapter.chapterIndex)
                    onToast("已删除章节翻译记录")
                },
                onCopy = { text ->
                    onCopy(text)
                    onToast("已复制")
                },
                modifier = modifier,
            )
        }
        else -> {
            TranslateBookListHistoryView(
                books = books,
                onBack = onBackToRoot,
                onClickBook = { onSelectBook(it.bookKey) },
                onDeleteBook = { book ->
                    TranslateHistoryStore.deleteBook(book.bookKey)
                    onToast("已删除《${book.displayTitle}》的翻译记录")
                },
                onClearAll = {
                    TranslateHistoryStore.clearAll()
                    onToast("已清空全部翻译记录")
                },
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun TranslateBookListHistoryView(
    books: List<TranslatedBookHistory>,
    onBack: () -> Unit,
    onClickBook: (TranslatedBookHistory) -> Unit,
    onDeleteBook: (TranslatedBookHistory) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(books, query) {
        val q = query.trim()
        if (q.isEmpty()) books
        else books.filter {
            it.displayTitle.contains(q, ignoreCase = true) ||
                it.originalTitle.contains(q, ignoreCase = true)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        NgFloatingSearchToolbar(
            query = query,
            onQueryChange = { query = it },
            hint = "搜索翻译后的书名或原名",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp),
            onSearch = {},
        ) {
            if (books.isNotEmpty()) {
                NgFloatingToolbarActionButton(
                    iconRes = R.drawable.ic_book_info_delete,
                    contentDescription = "清空全部翻译记录",
                    onClick = onClearAll,
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (books.isEmpty()) "暂无离线翻译记录\n浏览发现页或开启正文翻译后会自动保存在这里" else "未找到匹配的书籍记录",
                    color = Color(NgTheme.colors.onSurfaceVariant),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = filtered,
                    key = TranslatedBookHistory::bookKey,
                ) { book ->
                    val shape = RoundedCornerShape(NgTheme.shapes.largeDp.dp)
                    val cardColor = colorResource(R.color.ng_translucent_management_card_surface)
                    val strokeColor = colorResource(R.color.ng_translucent_management_card_stroke)
                    LeftSwipeToDelete(
                        onDeleteRequested = { onDeleteBook(book) },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(cardColor)
                                .border(0.6.dp, strokeColor, shape)
                                .clickable { onClickBook(book) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = book.displayTitle,
                                color = Color(NgTheme.colors.onSurface),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (book.originalTitle.isNotBlank() && book.originalTitle != book.displayTitle) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "原名：${book.originalTitle}",
                                    color = Color(NgTheme.colors.onSurfaceVariant),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "已译章节：${book.chapters.size} 章",
                                    color = Color(NgTheme.colors.primary),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (book.translatedIntro.isNotBlank()) {
                                    Text(
                                        text = "· 含简介译文",
                                        color = Color(NgTheme.colors.onSurfaceVariant),
                                        fontSize = 11.sp,
                                    )
                                }
                                if (book.engineName.isNotBlank()) {
                                    Text(
                                        text = "· ${book.engineName}",
                                        color = Color(NgTheme.colors.onSurfaceVariant),
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslateBookTocHistoryView(
    book: TranslatedBookHistory,
    onBack: () -> Unit,
    onClickChapter: (TranslatedChapterHistory) -> Unit,
    onDeleteChapter: (TranslatedChapterHistory) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val chapters = remember(book.chapters, query) {
        val q = query.trim()
        if (q.isEmpty()) book.chapters
        else book.chapters.filter {
            it.displayChapterTitle.contains(q, ignoreCase = true) ||
                it.originalChapterTitle.contains(q, ignoreCase = true)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        NgFloatingTitleToolbar(
            title = book.displayTitle,
            onBack = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "book_meta") {
                TranslateSectionCard("书籍译名与简介记录") {
                    Text(
                        text = "译名：${book.displayTitle}",
                        color = Color(NgTheme.colors.onSurface),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { onCopy(book.displayTitle) },
                    )
                    if (book.originalTitle.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "原名：${book.originalTitle}",
                            color = Color(NgTheme.colors.onSurfaceVariant),
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { onCopy(book.originalTitle) },
                        )
                    }
                    if (book.translatedIntro.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "简介译文：",
                            color = Color(NgTheme.colors.primary),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = book.translatedIntro,
                            color = Color(NgTheme.colors.onSurface),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            modifier = Modifier.clickable { onCopy(book.translatedIntro) },
                        )
                    }
                }
            }

            if (book.chapters.isNotEmpty()) {
                item(key = "chapter_search") {
                    NgFormField(
                        label = "筛选目录章节 (${book.chapters.size} 章)",
                        value = query,
                        onValueChange = { query = it },
                        density = NgFormDensity.COMPACT,
                    )
                }
            }

            if (chapters.isEmpty()) {
                item(key = "empty_chapters") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (book.chapters.isEmpty()) {
                                "本书暂无正文翻译目录\n阅读正文时点击顶栏翻译图标即可离线保存每章译文"
                            } else {
                                "未找到匹配的章节"
                            },
                            color = Color(NgTheme.colors.onSurfaceVariant),
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                    }
                }
            } else {
                items(
                    items = chapters,
                    key = { "ch_${it.chapterIndex}" },
                ) { chapter ->
                    val shape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp)
                    val cardColor = colorResource(R.color.ng_translucent_management_card_surface)
                    val strokeColor = colorResource(R.color.ng_translucent_management_card_stroke)
                    LeftSwipeToDelete(
                        onDeleteRequested = { onDeleteChapter(chapter) },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(cardColor)
                                .border(0.6.dp, strokeColor, shape)
                                .clickable { onClickChapter(chapter) }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            Text(
                                text = chapter.displayChapterTitle.ifBlank { "第 ${chapter.chapterIndex + 1} 章" },
                                color = Color(NgTheme.colors.onSurface),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (chapter.originalChapterTitle.isNotBlank() &&
                                chapter.originalChapterTitle != chapter.displayChapterTitle
                            ) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = chapter.originalChapterTitle,
                                    color = Color(NgTheme.colors.onSurfaceVariant),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "已保存 ${chapter.paragraphs.size} 段离线译文",
                                color = Color(NgTheme.colors.primary),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslateChapterRecordView(
    book: TranslatedBookHistory,
    chapter: TranslatedChapterHistory,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bilingual by remember { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxWidth()) {
        NgFloatingTitleToolbar(
            title = chapter.displayChapterTitle.ifBlank { "第 ${chapter.chapterIndex + 1} 章" },
            onBack = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            trailingContent = {
                NgFloatingToolbarActionButton(
                    iconRes = R.drawable.ic_translate,
                    contentDescription = if (bilingual) "仅看译文" else "双语对照",
                    onClick = { bilingual = !bilingual },
                )
            },
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "ch_header") {
                Text(
                    text = "${book.displayTitle} · ${if (bilingual) "双语对照（点段落可复制）" else "仅译文（点段落可复制）"}",
                    color = Color(NgTheme.colors.onSurfaceVariant),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(
                items = chapter.paragraphs,
            ) { pair ->
                val shape = RoundedCornerShape(10.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(colorResource(R.color.ng_translucent_management_card_surface))
                        .clickable { onCopy(pair.translated) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = pair.translated,
                        color = Color(NgTheme.colors.onSurface),
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                    if (bilingual && pair.original.isNotBlank() && pair.original != pair.translated) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = pair.original,
                            color = Color(NgTheme.colors.onSurfaceVariant),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslateSectionCard(
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
