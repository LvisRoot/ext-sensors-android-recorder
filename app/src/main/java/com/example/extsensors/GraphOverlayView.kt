package com.example.extsensors

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Transparent overlay drawn on top of the video: signal traces plus a playback-position line. */
class GraphOverlayView(context: Context) : View(context) {
    private data class Series(val points: List<Pair<Float, Float>>, val color: Int, val unit: String)

    private val allSeries = mutableMapOf<String, Series>()
    private val unitRange = mutableMapOf<String, Pair<Float, Float>>()
    private val visibleKeys = mutableSetOf<String>()
    private var durationSeconds = 1f
    private var progressFraction = 0f
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f; color = Color.WHITE }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f; style = Paint.Style.STROKE }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    /** Signals sharing a unit share one min/max scale, computed from all of them (not just visible ones)
     * so toggling traces on/off never rescales an already-visible one. */
    fun setSeries(data: Map<String, List<Pair<Float, Float>>>, colors: Map<String, Int>, units: Map<String, String>, durationSeconds: Float) {
        allSeries.clear()
        unitRange.clear()
        data.forEach { (key, points) -> allSeries[key] = Series(points, colors[key] ?: Color.CYAN, units[key] ?: "") }
        allSeries.values.groupBy { it.unit }.forEach { (unit, seriesList) ->
            val points = seriesList.flatMap { it.points }
            if (points.isNotEmpty()) unitRange[unit] = points.minOf { it.second } to points.maxOf { it.second }
        }
        this.durationSeconds = durationSeconds.coerceAtLeast(0.1f)
        invalidate()
    }

    fun toggle(key: String): Boolean {
        val nowVisible = if (!visibleKeys.remove(key)) { visibleKeys.add(key); true } else false
        invalidate()
        return nowVisible
    }

    fun clearVisible() {
        visibleKeys.clear()
        invalidate()
    }

    fun setProgressFraction(fraction: Float) {
        progressFraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val visibleUnits = visibleKeys.mapNotNull { allSeries[it]?.unit }.distinct()
        visibleUnits.forEachIndexed { index, unit -> drawUnitAxis(canvas, unit, index) }
        visibleKeys.forEach { key -> allSeries[key]?.let { drawSeries(canvas, it) } }
        val x = progressFraction * width
        canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
    }

    private fun drawUnitAxis(canvas: Canvas, unit: String, index: Int) {
        val (minValue, maxValue) = unitRange[unit] ?: return
        val color = allSeries.values.firstOrNull { it.unit == unit }?.color ?: Color.WHITE
        val range = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
        // The x axis (value = 0) is drawn for every visible unit whose range crosses zero.
        if (minValue <= 0f && maxValue >= 0f) {
            val zeroY = height - ((0f - minValue) / range) * height
            zeroPaint.color = color
            canvas.drawLine(0f, zeroY, width.toFloat(), zeroY, zeroPaint)
        }
        axisPaint.color = color
        val xOffset = 8f + index * 220f
        canvas.drawText("%.2f %s".format(maxValue, unit), xOffset, 34f, axisPaint)
        canvas.drawText("%.2f %s".format(minValue, unit), xOffset, height - 12f, axisPaint)
    }

    private fun drawSeries(canvas: Canvas, series: Series) {
        val points = series.points
        if (points.size < 2) return
        val (minValue, maxValue) = unitRange[series.unit] ?: (points.minOf { it.second } to points.maxOf { it.second })
        val range = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
        tracePaint.color = series.color
        val path = Path()
        points.forEachIndexed { index, (elapsed, value) ->
            val x = (elapsed / durationSeconds) * width
            val y = height - ((value - minValue) / range) * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, tracePaint)
    }
}
