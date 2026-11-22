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
import com.example.padcombo.core.PDBoardRect
import com.example.padcombo.core.PDRoute

/**
 * Transparent route layer over the real board (static frame mode). Maps the 6x5
 * grid onto the fixed FALLBACK full-board rectangle and draws the classic style:
 * hollow white circles on every cell the route visits, a smoothed white curve
 * linking them, a start circle and an end marker, with mid-point arrows.
 */
class RouteBoardView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private var board: PDBoard? = null
    private var route: PDRoute = PDRoute()

    /** Main preview re-draws the board; overlay keeps false. */
    var showOrbs = false

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 255, 255)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
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

        // In the main preview the view itself IS the board; on the overlay the
        // grid maps onto the fixed FALLBACK full-board rectangle on screen.
        val fb = PDBoardRect.FALLBACK
        val ox: Float
        val oy: Float
        val cellW: Float
        val cellH: Float
        if (showOrbs) {
            ox = 0f; oy = 0f
            cellW = width.toFloat() / PDBoard.COLS
            cellH = height.toFloat() / PDBoard.ROWS
        } else {
            ox = (fb.x * width).toFloat()
            oy = (fb.y * height).toFloat()
            cellW = (fb.width * width / PDBoard.COLS).toFloat()
            cellH = (fb.height * height / PDBoard.ROWS).toFloat()
        }
        val orb = minOf(cellW, cellH) * 0.82f

        val b = board
        if (showOrbs && b != null) {
            for (index in 0 until PDBoard.CELL_COUNT) {
                val cx = ox + (PDBoard.colOf(index) + 0.5f) * cellW
                val cy = oy + (PDBoard.rowOf(index) + 0.5f) * cellH
                orbPaint.color = orbColor(b.cells[index])
                canvas.drawCircle(cx, cy, orb / 2f, orbPaint)
            }
        }

        val path = route.path
        if (path.size >= 2) {
            fun center(i: Int): FloatArray {
                val cx = ox + (PDBoard.colOf(i) + 0.5f) * cellW
                val cy = oy + (PDBoard.rowOf(i) + 0.5f) * cellH
                return floatArrayOf(cx, cy)
            }
            val pts = path.map { center(it) }

            // Hollow white circle on every visited cell (classic style).
            val ringR = minOf(cellW, cellH) * 0.42f
            for (p in pts) canvas.drawCircle(p[0], p[1], ringR, ringPaint)

            // Smoothed rounded curve through the nodes.
            val curve = Path()
            curve.moveTo(pts[0][0], pts[0][1])
            for (i in 1 until pts.size - 1) {
                val mx = (pts[i][0] + pts[i + 1][0]) / 2
                val my = (pts[i][1] + pts[i + 1][1]) / 2
                curve.quadTo(pts[i][0], pts[i][1], mx, my)
            }
            curve.lineTo(pts.last()[0], pts.last()[1])
            canvas.drawPath(curve, linePaint)

            // Mid-point direction arrows along each segment.
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
            // Start: hollow circle (slightly larger).
            val start = pts[0]
            canvas.drawCircle(start[0], start[1], ringR * 1.15f, startPaint)
            // End: small square marker.
            val end = pts.last()
            val hs = orb * 0.26f
            canvas.drawRect(end[0] - hs, end[1] - hs, end[0] + hs, end[1] + hs, startPaint)

            canvas.drawText(
                "${route.combos} combo · ${path.size} 格",
                ox + (fb.width * width / 2).toFloat(),
                (oy - 10f).coerceAtLeast(24f),
                textPaint,
            )
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
