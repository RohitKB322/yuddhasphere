package com.yuddhasphere.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class LiveMarketChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = Color.parseColor("#1A2D4A")
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        textSize = 10f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val path = Path()
    private val fillPath = Path()
    private val dataPoints = mutableListOf<Double>()
    private var isUp = true

    fun setPriceHistory(prices: List<Double>, isPositive: Boolean) {
        dataPoints.clear()
        dataPoints.addAll(prices)
        isUp = isPositive
        postInvalidate()
    }

    fun setAsset(asset: com.yuddhasphere.models.MarketAsset) {
        setPriceHistory(asset.priceHistory, asset.changePercent >= 0.0)
    }

    fun addLiveTick(newPrice: Double) {
        val lastPrice = dataPoints.lastOrNull() ?: newPrice
        addLiveTick(newPrice, newPrice >= lastPrice)
    }

    fun addLiveTick(newPrice: Double, isPositive: Boolean) {
        dataPoints.add(newPrice)
        if (dataPoints.size > 28) {
            dataPoints.removeAt(0)
        }
        isUp = isPositive
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.size < 2 || width <= 0 || height <= 0) return

        val strokeColor = if (isUp) Color.parseColor("#30D158") else Color.parseColor("#FF453A")
        linePaint.color = strokeColor
        dotPaint.color = strokeColor
        haloPaint.color = Color.argb(60, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor))

        val padLeft = 8f * resources.displayMetrics.density
        val padRight = 55f * resources.displayMetrics.density // space for price labels on right
        val padTop = 16f * resources.displayMetrics.density
        val padBottom = 16f * resources.displayMetrics.density

        val usableWidth = (width - padLeft - padRight).coerceAtLeast(1f)
        val usableHeight = (height - padTop - padBottom).coerceAtLeast(1f)

        var minVal = dataPoints.minOrNull() ?: 0.0
        var maxVal = dataPoints.maxOrNull() ?: 1.0
        if (minVal == maxVal) {
            minVal -= 1.0
            maxVal += 1.0
        }
        val range = (maxVal - minVal).coerceAtLeast(0.001)

        // Draw 3 horizontal grid lines with price labels
        for (i in 0..2) {
            val y = padTop + (usableHeight * i / 2f)
            canvas.drawLine(padLeft, y, width - padRight, y, gridPaint)
            val labelPrice = maxVal - (range * i / 2.0)
            canvas.drawText("$%,.1f".format(labelPrice), width - padRight + 8f, y + 4f * resources.displayMetrics.density, textPaint)
        }

        val stepX = usableWidth / (dataPoints.size - 1)
        path.reset()
        fillPath.reset()

        var lastX = 0f
        var lastY = 0f

        dataPoints.forEachIndexed { index, value ->
            val x = padLeft + (index * stepX)
            val normalized = ((value - minVal) / range).toFloat()
            val y = padTop + (1.0f - normalized) * usableHeight

            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height.toFloat() - padBottom)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            lastX = x
            lastY = y
        }

        fillPath.lineTo(lastX, height.toFloat() - padBottom)
        fillPath.close()

        // Gradient shader
        val gradient = LinearGradient(
            0f, padTop, 0f, height.toFloat() - padBottom,
            Color.argb(70, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = gradient
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        // Pulsating halo and dot at live head
        canvas.drawCircle(lastX, lastY, 9f * resources.displayMetrics.density, haloPaint)
        canvas.drawCircle(lastX, lastY, 4f * resources.displayMetrics.density, dotPaint)
    }
}
