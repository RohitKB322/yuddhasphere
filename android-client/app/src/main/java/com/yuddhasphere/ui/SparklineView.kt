package com.yuddhasphere.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val path = Path()
    private val fillPath = Path()
    private var dataPoints: List<Double> = emptyList()
    private var isUp: Boolean = true

    fun setData(prices: List<Double>, positive: Boolean) {
        dataPoints = prices
        isUp = positive
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.size < 2 || width <= 0 || height <= 0) return

        val strokeColor = if (isUp) Color.parseColor("#30D158") else Color.parseColor("#FF453A")
        linePaint.color = strokeColor
        dotPaint.color = strokeColor

        val padTop = 4f * resources.displayMetrics.density
        val padBottom = 4f * resources.displayMetrics.density
        val usableHeight = (height - padTop - padBottom).coerceAtLeast(1f)
        val usableWidth = width.toFloat()

        var minVal = dataPoints.minOrNull() ?: 0.0
        var maxVal = dataPoints.maxOrNull() ?: 1.0
        if (minVal == maxVal) {
            minVal -= 1.0
            maxVal += 1.0
        }
        val range = (maxVal - minVal).coerceAtLeast(0.001)

        val stepX = usableWidth / (dataPoints.size - 1)
        path.reset()
        fillPath.reset()

        var lastX = 0f
        var lastY = 0f

        dataPoints.forEachIndexed { index, value ->
            val x = index * stepX
            val normalized = ((value - minVal) / range).toFloat()
            val y = padTop + (1.0f - normalized) * usableHeight

            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height.toFloat())
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            lastX = x
            lastY = y
        }

        fillPath.lineTo(lastX, height.toFloat())
        fillPath.close()

        // Gradient shader for fill
        val gradient = LinearGradient(
            0f, padTop, 0f, height.toFloat(),
            Color.argb(55, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = gradient
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        // Pulsing end dot
        canvas.drawCircle(lastX, lastY, 3.5f * resources.displayMetrics.density, dotPaint)
    }
}
