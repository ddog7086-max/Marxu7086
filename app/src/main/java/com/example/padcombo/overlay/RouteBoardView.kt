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

    /** Calibrated board rect supplied by the service (normalised 0..1). */
    var boardRect: PDBoardRect? = null

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 255, 255, 255)
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /** Visibility of the whole route (line / start dot / end arrow), 0..255. */
    var routeAlpha: Int = 245
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }

    fun show(board: PDBoard, route: PDRoute) {
        this.board = board
        this.route = route
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = routeAlpha.coerceIn(0, 255)
        linePaint.alpha = a
        dotPaint.alpha = a
        fillPaint.alpha = a

        // Main preview fills itself; the overlay uses the calibrated rect,
        // falling back to an auto-fit square grid if none was saved.
        val fb = if (showOrbs) PDBoardRect.FALLBACK
            else (boardRect ?: PDBoardRect.LOCKED)
        val ox = (fb.x * width).toFloat()
        val oy = (fb.y * height).toFloat()
        val cellW = (fb.width * width / PDBoard.COLS).toFloat()
        val cellH = (fb.height * height / PDBoard.ROWS).toFloat()
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
            // When the route visits the same cell a second (third...) time, shift
            // that visit off-centre along the track's normal so the revisit runs
            // in a parallel lane beside the orb instead of stacking on top of it.
            // Only genuine crossings still intersect. Purely visual; the solved
            // drag path and clear count are unchanged.
            val cell = minOf(cellW, cellH)
            val seen = HashMap<Int, Int>()
            val offsetPts = path.mapIndexed { i, idx ->
                val c = center(idx)
                val k = (seen[idx] ?: 0) + 1
                seen[idx] = k
                if (k >= 2 && i > 0 && i < path.size - 1) {
                    val a = center(path[i - 1])
                    val b = center(path[i + 1])
                    // Only offset a straight-through revisit (same direction in/out);
                    // a turn cell stays on-centre. The shift is always axis-aligned:
                    // a horizontal lane shifts vertically, a vertical lane shifts
                    // horizontally, so the track can never lean diagonal.
                    val d1x = c[0] - a[0]; val d1y = c[1] - a[1]
                    val d2x = b[0] - c[0]; val d2y = b[1] - c[1]
                    val straight = d1x * d2x + d1y * d2y > 0f
                    if (straight) {
                        val s = if (k % 2 == 0) 1f else -1f
                        val off = cell * 0.16f * s
                        if (Math.abs(d1x) > Math.abs(d1y)) {
                            floatArrayOf(c[0], c[1] + off)
                        } else {
                            floatArrayOf(c[0] + off, c[1])
                        }
                    } else c
                } else c
            }

            // Orthogonalise: every move must be purely horizontal or vertical.
            // Split any diagonal jump into an H-then-V elbow so the track runs
            // parallel to the grid (no diagonal slash, no sharp Y-junction).
            val pts = ArrayList<FloatArray>()
            pts.add(offsetPts[0])
            for (i in 1 until offsetPts.size) {
                val p = pts.last()
                val c = offsetPts[i]
                if (Math.abs(c[0] - p[0]) > 1f && Math.abs(c[1] - p[1]) > 1f) {
                    pts.add(floatArrayOf(c[0], p[1]))
                }
                pts.add(c)
            }

            // LOCKED style: rectilinear / Manhattan track with a LARGE rounded
            // fillet so each 90-degree turn sweeps through a near-semicircle
            // instead of a sharp corner. Do not use free-form bezier.
            val curve = Path()
            val corner = cell * 0.40f
            fun lerp(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(
                a[0] + (b[0] - a[0]) * t,
                a[1] + (b[1] - a[1]) * t,
            )
            curve.moveTo(pts[0][0], pts[0][1])
            for (i in 1 until pts.size - 1) {
                val p0 = pts[i - 1]; val p1 = pts[i]; val p2 = pts[i + 1]
                val d1x = p1[0] - p0[0]; val d1y = p1[1] - p0[1]
                val d2x = p2[0] - p1[0]; val d2y = p2[1] - p1[1]
                val dot = d1x * d2x + d1y * d2y
                if (dot <= 0f) {
                    curve.lineTo(p1[0], p1[1])
                    continue
                }
                val l1 = Math.hypot(d1x.toDouble(), d1y.toDouble()).toFloat()
                val l2 = Math.hypot(d2x.toDouble(), d2y.toDouble()).toFloat()
                val rr = minOf(corner, l1 / 2f, l2 / 2f)
                val a = lerp(p1, p0, rr / l1)
                val b = lerp(p1, p2, rr / l2)
                curve.lineTo(a[0], a[1])
                curve.quadTo(p1[0], p1[1], b[0], b[1])
            }
            curve.lineTo(pts.last()[0], pts.last()[1])

            // Reference-guide grey pipe: dark-grey outer stroke; inner core fades
            // white -> black linearly along the route from start to end.
            val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 12f
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
                color = Color.argb(a, 70, 68, 68)
            }
            val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 6f
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
                shader = android.graphics.LinearGradient(
                    pts[0][0], pts[0][1], pts.last()[0], pts.last()[1],
                    Color.argb(a, 255, 255, 255),
                    Color.argb(a, 0, 0, 0),
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(curve, outerPaint)
            canvas.drawPath(curve, innerPaint)

            // Start: a small solid white filled circle.
            val start = pts[0]
            val dotR = cell * 0.09f
            canvas.drawCircle(start[0], start[1], dotR, dotPaint)

            // End: a single solid white filled triangular arrowhead at the very
            // last point (no mid arrows, no hollow rings anywhere).
            val prev = pts[pts.size - 2]
            val end = pts.last()
            val dx = end[0] - prev[0]
            val dy = end[1] - prev[1]
            val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len > 1f) {
                val ux = dx / len
                val uy = dy / len
                val px = -uy
                val py = ux
                val s = cell * 0.18f
                val arrow = Path()
                arrow.moveTo(end[0], end[1])
                arrow.lineTo(end[0] - ux * s + px * s * 0.55f, end[1] - uy * s + py * s * 0.55f)
                arrow.lineTo(end[0] - ux * s - px * s * 0.55f, end[1] - uy * s - py * s * 0.55f)
                arrow.close()
                canvas.drawPath(arrow, fillPaint)
            }

            val td = resources.displayMetrics.density
            val label = "消 ${route.cleared}/30 颗 · ${route.combos} combo · ${path.size} 格"
            val bx = 16f * td
            val by = 150f * td
            val tw = textPaint.measureText(label)
            canvas.drawRect(bx, by - 24f * td, bx + tw + 16f * td, by + 8f * td, bgPaint)
            canvas.drawText(label, bx, by, textPaint)
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
