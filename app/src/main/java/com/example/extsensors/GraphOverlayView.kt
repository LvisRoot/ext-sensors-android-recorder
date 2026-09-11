package com.example.extsensors

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max

/** Transparent overlay drawn on top of the video: signal traces plus a playback-position line. */
class GraphOverlayView(context: Context) : View(context) {
    private data class Series(val points: List<Pair<Float, Float>>, val color: Int, val unit: String)

    private val allSeries = mutableMapOf<String, Series>()
    private val visibleKeys = mutableSetOf<String>()
    private var durationSeconds = 1f
    private var windowStartSeconds = 0f
    private var windowEndSeconds = 1f
    private var progressSeconds = 0f
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f; color = Color.WHITE }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f; style = Paint.Style.STROKE }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    var onTimeWindowChanged: ((startSeconds: Float, endSeconds: Float) -> Unit)? = null
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (width == 0) return false
            val oldSpan = windowEndSeconds - windowStartSeconds
            val minimumSpan = max(durationSeconds / 100f, 0.25f)
            val newSpan = (oldSpan / detector.scaleFactor).coerceIn(minimumSpan, durationSeconds)
            val focalTime = windowStartSeconds + (detector.focusX / width) * oldSpan
            val newStart = (focalTime - (detector.focusX / width) * newSpan)
                .coerceIn(0f, durationSeconds - newSpan)
            windowStartSeconds = newStart
            windowEndSeconds = newStart + newSpan
            onTimeWindowChanged?.invoke(windowStartSeconds, windowEndSeconds)
            invalidate()
            return true
        }
    })

    /** Signals sharing a unit share a min/max scale calculated from active traces. */
    fun setSeries(data: Map<String, List<Pair<Float, Float>>>, colors: Map<String, Int>, units: Map<String, String>, durationSeconds: Float) {
        allSeries.clear()
        data.forEach { (key, points) -> allSeries[key] = Series(points, colors[key] ?: Color.CYAN, units[key] ?: "") }
        this.durationSeconds = durationSeconds.coerceAtLeast(0.1f)
        resetTimeWindow()
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

    fun setProgressSeconds(seconds: Float) {
        progressSeconds = seconds.coerceIn(0f, durationSeconds)
        invalidate()
    }

    fun resetTimeWindow() {
        windowStartSeconds = 0f
        windowEndSeconds = durationSeconds
        onTimeWindowChanged?.invoke(windowStartSeconds, windowEndSeconds)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val visibleUnits = visibleKeys.mapNotNull { allSeries[it]?.unit }.distinct()
        visibleUnits.forEachIndexed { index, unit -> drawUnitAxis(canvas, unit, index) }
        visibleKeys.forEach { key -> allSeries[key]?.let { drawSeries(canvas, it) } }
        if (progressSeconds in windowStartSeconds..windowEndSeconds) {
            val x = ((progressSeconds - windowStartSeconds) / visibleDurationSeconds()) * width
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
        }
    }

    private fun drawUnitAxis(canvas: Canvas, unit: String, index: Int) {
        val (minValue, maxValue) = rangeForUnit(unit) ?: return
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
        val (minValue, maxValue) = rangeForUnit(series.unit) ?: return
        val range = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
        tracePaint.color = series.color
        val path = Path()
        var isFirstVisiblePoint = true
        points.forEach { (elapsed, value) ->
            if (elapsed !in windowStartSeconds..windowEndSeconds) return@forEach
            val x = ((elapsed - windowStartSeconds) / visibleDurationSeconds()) * width
            val y = height - ((value - minValue) / range) * height
            if (isFirstVisiblePoint) {
                path.moveTo(x, y)
                isFirstVisiblePoint = false
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, tracePaint)
    }

    private fun visibleDurationSeconds(): Float = (windowEndSeconds - windowStartSeconds).coerceAtLeast(0.1f)

    private fun rangeForUnit(unit: String): Pair<Float, Float>? {
        val points = visibleKeys
            .mapNotNull { allSeries[it] }
            .filter { it.unit == unit }
            .flatMap { it.points }
        return if (points.isEmpty()) null else points.minOf { it.second } to points.maxOf { it.second }
    }
}
