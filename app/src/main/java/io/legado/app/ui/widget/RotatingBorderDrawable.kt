package io.legado.app.ui.widget

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * 绕圆角矩形外框顺时针旋转的加载描边 Drawable，供非 Compose 工具栏按钮复用。
 */
class RotatingBorderDrawable(
    private val cornerRadiusPx: Float,
    private val strokeWidthPx: Float,
    color: Int,
) : Drawable() {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        this.color = color
        alpha = 56
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        alpha = 255
    }

    private val outlinePath = Path()
    private val segmentPath = Path()
    private val pathMeasure = PathMeasure()
    private val rectF = RectF()
    private var progress = 0f
    private var reverse: Boolean = false

    private var animator: ValueAnimator? = null

    fun setBorderColor(color: Int) {
        val baseAlpha = trackPaint.alpha
        trackPaint.color = color
        trackPaint.alpha = baseAlpha
        val arcAlpha = arcPaint.alpha
        arcPaint.color = color
        arcPaint.alpha = arcAlpha
        invalidateSelf()
    }

    fun setReverse(reverse: Boolean) {
        if (this.reverse == reverse) return
        this.reverse = reverse
        invalidateSelf()
    }

    fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val side = min(b.width(), b.height()).toFloat() - strokeWidthPx
        if (side <= 0f) return

        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val half = side / 2f
        rectF.set(cx - half, cy - half, cx + half, cy + half)

        outlinePath.reset()
        outlinePath.addRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        canvas.drawPath(outlinePath, trackPaint)

        pathMeasure.setPath(outlinePath, false)
        val totalLen = pathMeasure.length
        if (totalLen <= 0f) return

        val segLen = totalLen * 0.36f
        val effectiveProgress = if (reverse) (1f - progress) else progress
        val start = (effectiveProgress * totalLen) % totalLen
        val end = start + segLen

        segmentPath.reset()
        if (end <= totalLen) {
            pathMeasure.getSegment(start, end, segmentPath, true)
        } else {
            pathMeasure.getSegment(start, totalLen, segmentPath, true)
            canvas.drawPath(segmentPath, arcPaint)
            segmentPath.reset()
            pathMeasure.getSegment(0f, end - totalLen, segmentPath, true)
        }
        canvas.drawPath(segmentPath, arcPaint)
    }

    override fun setAlpha(alpha: Int) {
        trackPaint.alpha = (alpha * 0.22f).toInt().coerceIn(0, 255)
        arcPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        trackPaint.colorFilter = colorFilter
        arcPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
