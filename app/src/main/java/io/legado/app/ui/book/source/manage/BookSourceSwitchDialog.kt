package io.legado.app.ui.book.source.manage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.design.components.NgButtonVariant
import io.legado.app.ui.design.components.NgDialogVariant
import io.legado.app.ui.design.components.compose.NgDialog
import io.legado.app.ui.design.components.compose.NgDialogDivider
import io.legado.app.ui.design.components.compose.NgFormActionButton
import io.legado.app.ui.design.components.compose.NgFormActionButtonAppearance
import io.legado.app.ui.design.components.compose.NgSwitchControl
import io.legado.app.ui.design.components.compose.NgSwitchControlVariant
import io.legado.app.ui.design.theme.NgTheme

@Composable
internal fun BookSourceSwitchDialog(
    source: BookSourcePart,
    onDismiss: () -> Unit,
    onConfirm: (searchEnabled: Boolean, exploreEnabled: Boolean) -> Unit,
) {
    var searchEnabled by remember(source.bookSourceUrl) {
        mutableStateOf(source.hasSearchUrl && source.enabled)
    }
    var exploreEnabled by remember(source.bookSourceUrl) {
        mutableStateOf(source.hasExploreUrl && source.enabledExplore)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NgDialog(
            title = source.bookSourceName,
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
                    appearance = NgFormActionButtonAppearance.DIALOG,
                )
                NgFormActionButton(
                    text = stringResource(R.string.ok),
                    onClick = {
                        onConfirm(
                            if (source.hasSearchUrl) searchEnabled else false,
                            if (source.hasExploreUrl) exploreEnabled else false,
                        )
                    },
                    variant = NgButtonVariant.PRIMARY,
                    appearance = NgFormActionButtonAppearance.DIALOG,
                )
            },
        ) {
            BookSourceSwitchRow(
                tag = stringResource(R.string.book_source_capability_search_short),
                tagContainerColor = colorResource(R.color.ng_success_container),
                tagContentColor = colorResource(R.color.ng_success),
                title = stringResource(R.string.book_source_capability_search),
                checked = if (source.hasSearchUrl) searchEnabled else false,
                enabled = source.hasSearchUrl,
                onCheckedChange = { searchEnabled = it },
            )
            NgDialogDivider()
            BookSourceSwitchRow(
                tag = stringResource(R.string.book_source_capability_explore_short),
                tagContainerColor = colorResource(R.color.ng_info_container),
                tagContentColor = colorResource(R.color.ng_info),
                title = stringResource(R.string.book_source_capability_explore),
                checked = if (source.hasExploreUrl) exploreEnabled else false,
                enabled = source.hasExploreUrl,
                onCheckedChange = { exploreEnabled = it },
            )
        }
    }
}

@Composable
internal fun BookSourceSelectionCapabilityDialog(
    sources: List<BookSourcePart>,
    onDismiss: () -> Unit,
    onConfirm: (searchEnabled: Boolean, exploreEnabled: Boolean) -> Unit,
) {
    val searchSources = remember(sources) { sources.filter(BookSourcePart::hasSearchUrl) }
    val exploreSources = remember(sources) { sources.filter(BookSourcePart::hasExploreUrl) }
    var searchEnabled by remember(searchSources) {
        mutableStateOf(searchSources.isNotEmpty() && searchSources.all(BookSourcePart::enabled))
    }
    var exploreEnabled by remember(exploreSources) {
        mutableStateOf(
            exploreSources.isNotEmpty() && exploreSources.all(BookSourcePart::enabledExplore)
        )
    }
    val hasSearchSources = searchSources.isNotEmpty()
    val hasExploreSources = exploreSources.isNotEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NgDialog(
            title = stringResource(R.string.book_source_capability_manage),
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
                    appearance = NgFormActionButtonAppearance.DIALOG,
                )
                NgFormActionButton(
                    text = stringResource(R.string.ok),
                    onClick = {
                        onConfirm(
                            if (hasSearchSources) searchEnabled else false,
                            if (hasExploreSources) exploreEnabled else false,
                        )
                    },
                    variant = NgButtonVariant.PRIMARY,
                    appearance = NgFormActionButtonAppearance.DIALOG,
                )
            },
        ) {
            BookSourceSwitchRow(
                tag = stringResource(R.string.book_source_capability_search_short),
                tagContainerColor = colorResource(R.color.ng_success_container),
                tagContentColor = colorResource(R.color.ng_success),
                title = stringResource(R.string.book_source_capability_search),
                checked = if (hasSearchSources) searchEnabled else false,
                enabled = hasSearchSources,
                onCheckedChange = { searchEnabled = it },
            )
            NgDialogDivider()
            BookSourceSwitchRow(
                tag = stringResource(R.string.book_source_capability_explore_short),
                tagContainerColor = colorResource(R.color.ng_info_container),
                tagContentColor = colorResource(R.color.ng_info),
                title = stringResource(R.string.book_source_capability_explore),
                checked = if (hasExploreSources) exploreEnabled else false,
                enabled = hasExploreSources,
                onCheckedChange = { exploreEnabled = it },
            )
        }
    }
}

private data class BookSourceTypeOption(
    @BookSourceType.Type val type: Int,
    val labelRes: Int,
    val iconRes: Int,
)

private val bookSourceTypeOptions = listOf(
    BookSourceTypeOption(
        type = BookSourceType.default,
        labelRes = R.string.book_source_filter_type_text,
        iconRes = R.drawable.ic_ai_capability_text,
    ),
    BookSourceTypeOption(
        type = BookSourceType.image,
        labelRes = R.string.book_source_filter_type_image,
        iconRes = R.drawable.ic_image,
    ),
    BookSourceTypeOption(
        type = BookSourceType.video,
        labelRes = R.string.book_source_filter_type_video,
        iconRes = R.drawable.ic_ai_capability_video,
    ),
    BookSourceTypeOption(
        type = BookSourceType.audio,
        labelRes = R.string.book_source_filter_type_audio,
        iconRes = R.drawable.ic_tts_headphones,
    ),
    BookSourceTypeOption(
        type = BookSourceType.file,
        labelRes = R.string.book_source_filter_type_file,
        iconRes = R.drawable.ic_folder_open,
    ),
)

@Composable
internal fun BookSourceSelectionTypeDialog(
    sources: List<BookSourcePart>,
    onDismiss: () -> Unit,
    onSelectType: (Int) -> Unit,
) {
    val currentType = remember(sources) {
        if (sources.isEmpty()) null
        else {
            val firstType = sources.first().bookSourceType
            if (sources.all { it.bookSourceType == firstType }) firstType else null
        }
    }
    val primaryColor = Color(NgTheme.colors.primary)
    val surfaceVariantColor = Color(NgTheme.colors.surfaceVariant)
    val outlineVariantColor = Color(NgTheme.colors.outlineVariant)
    val onSurfaceColor = Color(NgTheme.colors.onSurface)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NgDialog(
            title = stringResource(R.string.book_source_type_manage),
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
                    appearance = NgFormActionButtonAppearance.DIALOG,
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                bookSourceTypeOptions.forEach { option ->
                    val selected = currentType == option.type
                    val containerColor = if (selected) {
                        primaryColor.copy(alpha = 0.14f)
                    } else {
                        surfaceVariantColor.copy(alpha = 0.35f)
                    }
                    val borderColor = if (selected) {
                        primaryColor
                    } else {
                        outlineVariantColor.copy(alpha = 0.45f)
                    }
                    val contentColor = if (selected) primaryColor else onSurfaceColor
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp),
                        color = containerColor,
                        border = BorderStroke(if (selected) 1.25.dp else 0.75.dp, borderColor),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clickable(
                                    role = Role.RadioButton,
                                    onClick = { onSelectType(option.type) },
                                )
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(option.iconRes),
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(option.labelRes),
                                modifier = Modifier.weight(1f),
                                color = contentColor,
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (selected) {
                                Icon(
                                    painter = painterResource(R.drawable.ng_ic_popup_compact_check),
                                    contentDescription = null,
                                    tint = primaryColor,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookSourceSwitchRow(
    tag: String,
    tagContainerColor: Color,
    tagContentColor: Color,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(5.dp),
            color = tagContainerColor,
        ) {
            Text(
                text = tag,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                color = tagContentColor.copy(alpha = if (enabled) 1f else 0.45f),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
            )
        }
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            color = Color(NgTheme.colors.onSurface).copy(alpha = if (enabled) 1f else 0.45f),
            fontSize = 15.sp,
            lineHeight = 19.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        NgSwitchControl(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            variant = NgSwitchControlVariant.COMPACT,
        )
    }
}
