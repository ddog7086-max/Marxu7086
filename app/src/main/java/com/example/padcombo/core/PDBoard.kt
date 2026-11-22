package com.example.padcombo.core

/**
 * Orb colour identifiers. [EMPTY] is only used by the simulator / cascade loop,
 * never by the recognizer. 1:1 port of PDCore.swift.
 */
object Orb {
    const val FIRE = 0
    const val WATER = 1
    const val WOOD = 2
    const val LIGHT = 3
    const val DARK = 4
    const val HEART = 5
    const val EMPTY = -1
    const val COLOR_COUNT = 6
}

/**
 * Normalised board rectangle on screen (0..1, origin top-left).
 * The only thing the user calibrates: where the 6x5 board sits.
 */
data class PDBoardRect(
    var x: Double,
    var y: Double,
    var width: Double,
    var height: Double,
) {
    companion object {
        val FALLBACK = PDBoardRect(0.07, 0.615, 0.86, 0.235)
    }
}

/**
 * 6 columns x 5 rows. [cells] is row-major: index = row * cols + col, row 0 top.
 */
class PDBoard(val cells: IntArray) {
    companion object {
        const val COLS = 6
        const val ROWS = 5
        const val CELL_COUNT = COLS * ROWS

        fun index(row: Int, col: Int) = row * COLS + col
        fun rowOf(index: Int) = index / COLS
        fun colOf(index: Int) = index % COLS
    }

    init {
        require(cells.size == CELL_COUNT) { "board must hold 30 cells" }
    }

    fun cell(row: Int, col: Int) = cells[index(row, col)]

    /**
     * Net effect of one P&D drag: every orb on the path shifts one cell backwards,
     * and the orb you picked up ends up on the last cell of the path.
     */
    fun dragged(along: List<Int>): PDBoard {
        if (along.size <= 1) return this
        val values = along.map { cells[it] }
        val out = cells.copyOf()
        for (i in 0 until along.size - 1) {
            out[along[i]] = values[i + 1]
        }
        out[along[along.size - 1]] = values[0]
        return PDBoard(out)
    }

    /** All maximal horizontal / vertical runs of three or more identical orbs. */
    fun runs(): List<List<Int>> {
        val found = mutableListOf<List<Int>>()

        for (row in 0 until ROWS) {
            var col = 0
            while (col < COLS) {
                val value = cell(row, col)
                val start = col
                while (col < COLS && cell(row, col) == value) col++
                if (value != Orb.EMPTY && col - start >= 3) {
                    found.add((start until col).map { index(row, it) })
                }
            }
        }

        for (col in 0 until COLS) {
            var row = 0
            while (row < ROWS) {
                val value = cell(row, col)
                val start = row
                while (row < ROWS && cell(row, col) == value) row++
                if (value != Orb.EMPTY && row - start >= 3) {
                    found.add((start until row).map { index(it, col) })
                }
            }
        }
        return found
    }

    /** Clears matches, drops the remainder, repeats. Returns the combo count. */
    fun resolveCombos(): Int {
        var combos = 0
        repeat(64) {
            val matched = runs()
            if (matched.isEmpty()) return combos
            combos += matched.size
            for (run in matched) for (i in run) cells[i] = Orb.EMPTY
            for (col in 0 until COLS) {
                val survivors = ArrayList<Int>(ROWS)
                for (row in 0 until ROWS) {
                    if (cell(row, col) != Orb.EMPTY) survivors.add(cell(row, col))
                }
                val padding = ROWS - survivors.size
                for (row in 0 until ROWS) {
                    cells[index(row, col)] = if (row < padding) Orb.EMPTY else survivors[row - padding]
                }
            }
        }
        return combos
    }

    /** Cheap heuristic: how many cells already sit in a pair. */
    val nearScore: Int
        get() {
            var score = 0
            for (row in 0 until ROWS) {
                var col = 0
                while (col < COLS) {
                    val value = cell(row, col)
                    val start = col
                    while (col < COLS && cell(row, col) == value) col++
                    if (value != Orb.EMPTY && col - start == 2) score += 1
                }
            }
            for (col in 0 until COLS) {
                var row = 0
                while (row < ROWS) {
                    val value = cell(row, col)
                    val start = row
                    while (row < ROWS && cell(row, col) == value) row++
                    if (value != Orb.EMPTY && row - start == 2) score += 1
                }
            }
            return score
        }

    override fun equals(other: Any?): Boolean =
        other is PDBoard && other.cells.contentEquals(cells)

    override fun hashCode(): Int = cells.contentHashCode()
}
