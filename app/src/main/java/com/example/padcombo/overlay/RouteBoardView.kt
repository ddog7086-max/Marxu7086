package com.example.padcombo.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.padcombo.core.Orb
import com.example.padcombo.core.PDBoard
import com.example.padcombo.core.PDRoute
import com.example.padcombo.core.PDSolver

/**
 * Draws the recognized 6x5 board plus the solved white route. Reused both in
 * the main preview and on the floating overlay. Mirrors RoutePipView.swift.
 */
class RouteBoardView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private var board: PDBoard? = null
    private var route: PDRoute = PDRoute()

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(242, 255, 255, 255)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        isFakeBoldText = true
    }

    fun show(board: PDBoard, route: PDRoute) {
        this.board = board
        this.route = route
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val header = 42f
        val cellW = width.toFloat() / PDBoard.COLS
        val cellH = (height - header) / PDBoard.ROWS
        val orb = minOf(cellW, cellH) * 0.82f

        val b = board ?: return
        for (index in 0 until PDBoard.CELL_COUNT) {
            val cx = (PDBoard.colOf(index) + 0.5f) * cellW
            val cy = header + (PDBoard.rowOf(index) + 0.5f) * cellH
            orbPaint.color = orbColor(b.cells[index])
            canvas.drawCircle(cx, cy, orb / 2f, orbPaint)
        }

        // Route polyline.
        val path = route.path
        if (path.size >= 2) {
            for (k in 1 until path.size) {
                val a = path[k - 1]
                val c = path[k]
                canvas.drawLine(
                    (PDBoard.colOf(a) + 0.5f) * cellW,
                    header + (PDBoard.rowOf(a) + 0.5f) * cellH,
                    (PDBoard.colOf(c) + 0.5f) * cellW,
                    header + (PDBoard.rowOf(c) + 0.5f) * cellH,
                    linePaint,
                )
            }
            val start = path[0]
            canvas.drawCircle(
                (PDBoard.colOf(start) + 0.5f) * cellW,
                header + (PDBoard.rowOf(start) + 0.5f) * cellH,
                orb / 2f,
                startPaint,
            )
        }

        canvas.drawText("${route.combos} combo · ${path.size} 格", 12f, 30f, textPaint)
    }

    companion object {
        private fun rgb(r: Double, g: Double, b: Double) =
            Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())

        fun orbColor(orb: Int): Int = when (orb) {
            Orb.FIRE -> rgb(0.90, 0.25, 0.22)
            Orb.WATER -> rgb(0.25, 0.55, 0.95)
            Orb.WOOD -> rgb(0.38, 0.80, 0.38)
            Orb.LIGHT -> rgb(0.98, 0.86, 0.28)
            Orb.DARK -> rgb(0.62, 0.32, 0.78)
            Orb.HEART -> rgb(0.96, 0.50, 0.63)
            else -> rgb(0.35, 0.35, 0.35)
        }
    }
}
