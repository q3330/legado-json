package io.legado.app.ui.design.components.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.translate.BookTitleTranslator

/**
 * 翻译执行中用于图标外框的旋转描边效果（支持正反旋转方向区分模型启动加载与推理翻译阶段）。
 */
fun Modifier.ngTranslateRotatingBorder(
    active: Boolean,
    cornerRadius: Dp = 10.dp,
    borderColor: Color,
    strokeWidth: Dp = 1.6.dp,
    reverse: Boolean = false,
): Modifier {
    if (!active) return this
    return composed {
        val transition = rememberInfiniteTransition(label = "translate_border_transition")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "translate_border_progress",
        )
        val outlinePath = remember { Path() }
        val segmentPath = remember { Path() }
        val pathMeasure = remember { PathMeasure() }

        drawWithContent {
            drawContent()
            val strokePx = strokeWidth.toPx()
            val inset = strokePx / 2f
            val width = size.width - strokePx
            val height = size.height - strokePx
            if (width <= 0f || height <= 0f) return@drawWithContent

            val cr = (cornerRadius.toPx() - inset).coerceAtLeast(0f)
            outlinePath.reset()
            outlinePath.addRoundRect(
                RoundRect(
                    left = inset,
                    top = inset,
                    right = size.width - inset,
                    bottom = size.height - inset,
                    cornerRadius = CornerRadius(cr, cr),
                )
            )
            drawPath(
                path = outlinePath,
                color = borderColor.copy(alpha = 0.22f),
                style = Stroke(width = strokePx),
            )

            pathMeasure.setPath(outlinePath, false)
            val totalLen = pathMeasure.length
            if (totalLen <= 0f) return@drawWithContent

            val segLen = totalLen * 0.36f
            val normalizedProgress = if (reverse) (1f - progress) else progress
            val start = (normalizedProgress * totalLen) % totalLen
            val end = start + segLen
            val activeStroke = Stroke(
                width = strokePx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )

            segmentPath.reset()
            if (end <= totalLen) {
                pathMeasure.getSegment(start, end, segmentPath, startWithMoveTo = true)
            } else {
                pathMeasure.getSegment(start, totalLen, segmentPath, startWithMoveTo = true)
                drawPath(path = segmentPath, color = borderColor, style = activeStroke)
                segmentPath.reset()
                pathMeasure.getSegment(0f, end - totalLen, segmentPath, startWithMoveTo = true)
            }
            drawPath(path = segmentPath, color = borderColor, style = activeStroke)
        }
    }
}

/**
 * 与顶栏搜索框并排的 36dp 单次翻译动作按钮。
 * - 模型启动加载时：图标显示绿色，外框为反向（逆时针）旋转加载边框。
 * - 开始推理翻译时：图标显示绿色，外框为正向（顺时针）旋转加载边框。
 */
@Composable
fun NgSearchBarTranslateButton(
    isTranslating: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    borderColor: Color = colorResource(R.color.primaryText),
) {
    val isModelLoading by BookTitleTranslator.isLoadingModel.collectAsState()
    val cornerRadius = 10.dp
    val shape = RoundedCornerShape(cornerRadius)
    val containerColor = colorResource(R.color.ng_search_surface)
    val contentColor = if (isTranslating) {
        colorResource(R.color.ng_success)
    } else {
        colorResource(R.color.ng_search_icon)
    }
    val surfaceStyle = rememberNgSearchSurfaceStyle(
        containerColor = containerColor,
        strokeColor = Color.Transparent,
        contentColor = contentColor,
        isToolbar = true,
    )
    NgVisualSurface(
        modifier = modifier
            .size(36.dp)
            .ngTranslateRotatingBorder(
                active = isTranslating,
                cornerRadius = cornerRadius,
                borderColor = borderColor,
                reverse = isModelLoading,
            )
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        role = NgMaterialRole.TOP_NAVIGATION,
        cornerRadius = cornerRadius,
        shape = shape,
        style = surfaceStyle,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_translate),
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
        }
    }
}

/**
 * 工具栏与弹窗内的紧凑翻译动作按钮；模型启动加载时外框逆时针旋转，模型加载完成后推理翻译时外框顺时针旋转，图标统一保持绿色。
 */
@Composable
fun NgToolbarTranslateButton(
    isTranslating: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    iconSize: Dp = 18.dp,
    cornerRadius: Dp = 10.dp,
    idleTint: Color = colorResource(R.color.ng_search_icon),
    borderColor: Color = colorResource(R.color.primaryText),
) {
    val isModelLoading by BookTitleTranslator.isLoadingModel.collectAsState()
    val shape = RoundedCornerShape(cornerRadius)
    val iconTint = if (isTranslating) {
        colorResource(R.color.ng_success)
    } else {
        idleTint
    }
    Box(
        modifier = modifier
            .size(size)
            .ngTranslateRotatingBorder(
                active = isTranslating,
                cornerRadius = cornerRadius,
                borderColor = borderColor,
                reverse = isModelLoading,
            )
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_translate),
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = iconTint,
        )
    }
}
