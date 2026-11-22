package com.example.padcombo.core

import android.graphics.Bitmap

/**
 * Turns a screen bitmap plus a board rectangle into 30 orb colours.
 * Recognition is the weakest link: the reference colours are tuned for the
 * classic six orbs and may need adjusting per theme / device.
 * 1:1 port of OrbRecognizer.swift.
 */
object OrbRecognizer {
    private data class Reference(
        val color: Int,
        val r: Double,
        val g: Double,
        val b: Double,
    )

    /** Sampled from the middle of each orb type (0...1 RGB). */
    private val REFERENCES = listOf(
        Reference(Orb.FIRE, 0.86, 0.22, 0.18),
        Reference(Orb.WATER, 0.22, 0.50, 0.92),
        Reference(Orb.WOOD, 0.35, 0.75, 0.35),
        Reference(Orb.LIGHT, 0.98, 0.85, 0.25),
        Reference(Orb.DARK, 0.58, 0.28, 0.72),
        Reference(Orb.HEART, 0.95, 0.45, 0.60),
    )

    fun classify(bitmap: Bitmap, rect: PDBoardRect): PDBoard? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 8 || height < 8) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val cells = IntArray(PDBoard.CELL_COUNT) { Orb.EMPTY }

        for (row in 0 until PDBoard.ROWS) {
            for (col in 0 until PDBoard.COLS) {
                val fx = rect.x + rect.width * (col + 0.5) / PDBoard.COLS
                val fy = rect.y + rect.height * (row + 0.5) / PDBoard.ROWS
                val px = (fx * width).toInt()
                val py = (fy * height).toInt()
                if (px < 3 || px >= width - 3 || py < 3 || py >= height - 3) continue

                var r = 0.0
                var g = 0.0
                var b = 0.0
                var samples = 0
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val pixel = pixels[(py + dy) * width + (px + dx)]
                        r += (pixel shr 16 and 0xFF)
                        g += (pixel shr 8 and 0xFF)
                        b += (pixel and 0xFF)
                        samples++
                    }
                }
                r /= samples * 255.0
                g /= samples * 255.0
                b /= samples * 255.0

                cells[PDBoard.index(row, col)] = nearestColor(r, g, b)
            }
        }
        return PDBoard(cells)
    }

    private fun nearestColor(r: Double, g: Double, b: Double): Int {
        var best = Orb.EMPTY
        var bestDistance = Double.POSITIVE_INFINITY
        for (ref in REFERENCES) {
            // Green is weighted slightly higher: heart and fire differ mostly there.
            val distance = (r - ref.r) * (r - ref.r) +
                1.4 * (g - ref.g) * (g - ref.g) +
                (b - ref.b) * (b - ref.b)
            if (distance < bestDistance) {
                bestDistance = distance
                best = ref.color
            }
        }
        // Too far from anything we know: poison / nuisance / empty cell.
        return if (bestDistance > 1.6) Orb.EMPTY else best
    }
}
