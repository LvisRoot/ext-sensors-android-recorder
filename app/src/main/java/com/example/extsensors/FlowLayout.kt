package com.example.extsensors

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Minimal wrapping row layout: lays children left-to-right, moving to a new line when a child
 * would overflow the available width, so legend chips always fit on screen. */
class FlowLayout(context: Context) : ViewGroup(context) {
    var horizontalSpacing = 16
    var verticalSpacing = 12

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0
        var y = 0
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            if (x != 0 && x + childWidth > maxWidth) {
                x = 0
                y += lineHeight + verticalSpacing
                lineHeight = 0
            }
            x += childWidth + horizontalSpacing
            lineHeight = maxOf(lineHeight, childHeight)
        }
        setMeasuredDimension(maxWidth, resolveSize(y + lineHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l
        var x = 0
        var y = 0
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            if (x != 0 && x + childWidth > maxWidth) {
                x = 0
                y += lineHeight + verticalSpacing
                lineHeight = 0
            }
            child.layout(x, y, x + childWidth, y + childHeight)
            x += childWidth + horizontalSpacing
            lineHeight = maxOf(lineHeight, childHeight)
        }
    }
}
