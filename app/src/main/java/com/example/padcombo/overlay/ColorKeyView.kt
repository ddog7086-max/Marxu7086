package com.example.padcombo.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.padcombo.core.Orb
import com.example.padcombo.core.PDBoard

/**
 * Tiny standalone colour key shown in its own small window at the screen's top-left
 * corner (not inside the route box): a 6x5 swatch of the recognised board so the
 * user can compare colours against the real orbs. Touch-through; never blocks play.
 */
class ColorKeyView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private var cells: IntArray? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setBoard(board: PDBoard) {
        cells = board.cells
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = cells ?: return
        val d = resources.displayMetrics.density
        val sw = 13f * d
        val gap = 2f * d
        for (row in 0 until PDBoard.ROWS) {
            for (col in 0 until PDBoard.COLS) {
                fill.color = orbColor(c[PDBoard.index(row, col)])
                val x = col * (sw + gap)
                val y = row * (sw + gap)
                canvas.drawRect(x, y, x + sw, y + sw, fill)
            }
        }
    }

    private fun orbColor(orb: Int): Int = when (orb) {
        Orb.FIRE -> android.graphics.Color.rgb(230, 64, 56)
        Orb.WATER -> android.graphics.Color.rgb(64, 140, 242)
        Orb.WOOD -> android.graphics.Color.rgb(97, 204, 97)
        Orb.LIGHT -> android.graphics.Color.rgb(250, 219, 71)
        Orb.DARK -> android.graphics.Color.rgb(158, 82, 199)
        Orb.HEART -> android.graphics.Color.rgb(245, 128, 161)
        else -> android.graphics.Color.rgb(90, 90, 90)
    }
}
