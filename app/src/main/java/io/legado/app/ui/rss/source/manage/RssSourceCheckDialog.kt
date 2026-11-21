package io.legado.app.ui.rss.source.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.help.source.RssCheckOptions
import io.legado.app.ui.design.components.NgButtonVariant
import io.legado.app.ui.design.components.NgDialogVariant
import io.legado.app.ui.design.components.compose.NgDialog
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckbox
import io.legado.app.ui.design.components.compose.NgFileSelectionCheckboxVariant
import io.legado.app.ui.design.components.compose.NgFormActionButton
import io.legado.app.ui.design.components.compose.NgFormActionButtonAppearance
import io.legado.app.ui.design.components.compose.NgFormField
import io.legado.app.ui.design.components.compose.NgFormFieldVariant
import io.legado.app.ui.design.components.compose.NgFormSwitchSettingRow
import io.legado.app.ui.design.theme.NgTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RssSourceCheckDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (RssCheckOptions) -> Unit,
) {
    var timeoutText by remember { mutableStateOf("15") }
    var timeoutError by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(true) }
    var checkConnect by remember { mutableStateOf(true) }
    var checkList by remember { mutableStateOf(true) }
    var checkContent by remember { mutableStateOf(false) }
    var writeStatusGroup by remember { mutableStateOf(true) }
    var writeComment by remember { mutableStateOf(false) }

    fun confirm() {
        val timeout = timeoutText.toLongOrNull()
        if (timeout == null || timeout < 3L || timeout > 120L) {
            timeoutError = true
            advancedExpanded = true
            return
        }
        onConfirm(
            RssCheckOptions(
                timeoutSeconds = timeout,
                checkConnect = checkConnect,
                checkList = checkList,
                checkContent = checkContent,
                writeStatusGroup = writeStatusGroup,
                writeComment = writeComment,
            )
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NgDialog(
            title = "校验订阅源 ($selectedCount)",
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .widthIn(max = 520.dp),
            variant = NgDialogVariant.STANDARD,
            titleFontSize = 18.sp,
            titleFontWeight = FontWeight.Medium,
            actions = {
                NgFormActionButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    appearance = NgFormActionButtonAppearance.DIALOG,
                )
                NgFormActionButton(
                    text = stringResource(R.string.book_source_check_start),
                    onClick = ::confirm,
                    modifier = Modifier.weight(1f),
                    variant = NgButtonVariant.PRIMARY,
                    appearance = NgFormActionButtonAppearance.DIALOG,
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                NgFormField(
                    label = stringResource(R.string.check_source_timeout),
                    value = timeoutText,
                    onValueChange = { value ->
                        timeoutText = value.filter(Char::isDigit)
                        timeoutError = false
                    },
                    isError = timeoutError,
                    supportingText = if (timeoutError) "请输入 3~120 秒" else null,
                    variant = NgFormFieldVariant.PLAIN_UNDERLINE,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                )
                Spacer(Modifier.height(6.dp))
                NgFormSwitchSettingRow(
                    title = "写入状态分组",
                    summary = "按校验结果写入「通过」「域名失效」等分组标签",
                    checked = writeStatusGroup,
                    onCheckedChange = { writeStatusGroup = it },
                )
                NgFormSwitchSettingRow(
                    title = stringResource(R.string.write_source_comment),
                    checked = writeComment,
                    onCheckedChange = { writeComment = it },
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable { advancedExpanded = !advancedExpanded }
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.check_source_item),
                        modifier = Modifier.weight(1f),
                        color = Color(NgTheme.colors.primary),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right_20),
                        contentDescription = null,
                        tint = Color(NgTheme.colors.onSurfaceVariant),
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(if (advancedExpanded) 90f else 0f),
                    )
                }
                if (advancedExpanded) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        RssCheckItemChip(
                            text = "域名连通",
                            checked = checkConnect,
                            onToggle = {
                                checkConnect = !checkConnect
                                if (!checkConnect && !checkList) checkList = true
                            },
                        )
                        RssCheckItemChip(
                            text = "列表规则",
                            checked = checkList,
                            onToggle = {
                                checkList = !checkList
                                if (!checkList) checkContent = false
                                if (!checkConnect && !checkList) checkConnect = true
                            },
                        )
                        RssCheckItemChip(
                            text = "正文规则",
                            checked = checkContent,
                            onToggle = {
                                checkContent = !checkContent
                                if (checkContent) checkList = true
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RssCheckItemChip(
    text: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NgFileSelectionCheckbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            variant = NgFileSelectionCheckboxVariant.COMPACT,
        )
        Text(
            text = text,
            color = Color(NgTheme.colors.onSurface),
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}
