package com.example.padcombo.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.example.padcombo.core.Orb
import com.example.padcombo.core.PDBoard
import com.example.padcombo.core.PDRoute

/**
 * Floating route layer. On the overlay it is transparent and draws ONLY the smoothed
 * white route (rounded curve + start circle + end square + mid-point arrows) over the
 * real orbs. A faint 6x5 helper grid appears only in align mode. The recognised
 * colour key lives in a separate small window at the screen's top-left corner.
 */
class RouteBoardView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private var board: PDBoard? = null
    private var route: PDRoute = PDRoute()

    /** Main preview re-draws the board; overlay keeps false. */
    var showOrbs = false

    /** Show the faint 6x5 alignment grid (align mode only). */
    var showGrid = false

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    fun show(board: PDBoard, route: PDRoute) {
        this.board = board
        this.route = route
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cellW = width.toFloat() / PDBoard.COLS
        val cellH = height.toFloat() / PDBoard.ROWS
        val orb = minOf(cellW, cellH) * 0.82f

        val b = board
        if (showOrbs && b != null) {
            for (index in 0 until PDBoard.CELL_COUNT) {
                val cx = (PDBoard.colOf(index) + 0.5f) * cellW
                val cy = (PDBoard.rowOf(index) + 0.5f) * cellH
                orbPaint.color = orbColor(b.cells[index])
                canvas.drawCircle(cx, cy, orb / 2f, orbPaint)
            }
        } else if (!showOrbs) {
            // Faint hairline frame around the capture box.
            canvas.drawRect(1f, 1f, width - 1f, height - 1f, framePaint)

            // Alignment grid: faint 6x5 lines shown only in align mode so the user
            // can line the box up with the orb centres.
            if (showGrid) {
                for (c in 1 until PDBoard.COLS) {
                    val x = c * cellW
                    canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
                }
                for (r in 1 until PDBoard.ROWS) {
                    val y = r * cellH
                    canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
                }
            }
        }

        // Smoothed rounded-curve route; start = hollow circle, end = square.
        val path = route.path
        if (path.size >= 2) {
            fun center(i: Int): FloatArray {
                val cx = (PDBoard.colOf(i) + 0.5f) * cellW
                val cy = (PDBoard.rowOf(i) + 0.5f) * cellH
                return floatArrayOf(cx, cy)
            }
            val pts = path.map { center(it) }

            // Rounded curve: connect mid-points with quadratic beziers through each node.
            val curve = Path()
            curve.moveTo(pts[0][0], pts[0][1])
            for (i in 1 until pts.size - 1) {
                val mx = (pts[i][0] + pts[i + 1][0]) / 2
                val my = (pts[i][1] + pts[i + 1][1]) / 2
                curve.quadTo(pts[i][0], pts[i][1], mx, my)
            }
            curve.lineTo(pts.last()[0], pts.last()[1])
            canvas.drawPath(curve, linePaint)

            // Mid-point direction arrows along each original segment.
            val arrowHead = Path()
            for (k in 1 until pts.size) {
                val a = pts[k - 1]
                val c = pts[k]
                val mx = (a[0] + c[0]) / 2
                val my = (a[1] + c[1]) / 2
                val dx = c[0] - a[0]
                val dy = c[1] - a[1]
                val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (len > 1f) {
                    val ux = dx / len
                    val uy = dy / len
                    val px = -uy
                    val py = ux
                    val s = orb * 0.16f
                    arrowHead.reset()
                    arrowHead.moveTo(mx + ux * s * 1.6f, my + uy * s * 1.6f)
                    arrowHead.lineTo(mx - ux * s + px * s, my - uy * s + py * s)
                    arrowHead.lineTo(mx - ux * s - px * s, my - uy * s - py * s)
                    arrowHead.close()
                    canvas.drawPath(arrowHead, linePaint)
                }
            }
            // Start: hollow circle.
            val start = pts[0]
            canvas.drawCircle(start[0], start[1], orb / 2f, startPaint)
            // End: filled square.
            val end = pts.last()
            val hs = orb * 0.26f
            canvas.drawRect(end[0] - hs, end[1] - hs, end[0] + hs, end[1] + hs, startPaint)

            canvas.drawText("${route.combos} combo · ${path.size} 格", width / 2f, 30f, textPaint)
        }
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
