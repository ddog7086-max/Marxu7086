package com.example.padcombo.core

import kotlin.math.exp
import kotlin.math.max

/**
 * Deterministic, fast RNG (SplitMix64). Deterministic matters: the same board
 * must always produce the same route, otherwise the overlay flickers between
 * candidates every frame. 1:1 port of the Swift solver.
 */
class SplitMix64(seed: ULong = 0x9E3779B97F4A7C15u) {
    private var state: ULong = seed

    fun next(): ULong {
        state += 0x9E3779B97F4A7C15u
        var z = state
        z = (z xor (z.toLong() ushr 30).toULong()) * 0xBF58476D1CE4E5B9u
        z = (z xor (z.toLong() ushr 27).toULong()) * 0x94D049BB13311EBu
        return z xor (z.toLong() ushr 31).toULong()
    }

    /** Uniform double in [0, 1). */
    fun nextUnit(): Double = (next().toLong() ushr 11).toDouble() / 9_007_199_254_740_992.0
}

data class PDRoute(
    val path: List<Int> = emptyList(),
    val combos: Int = 0,
)

/** Simulated-annealing search over complete drag paths. */
object PDSolver {
    private val NEIGHBOURS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    fun solve(
        board: PDBoard,
        maxLen: Int = 24,
        budgetPerRestart: Int = 1400,
        restarts: Int = 7,
        seed: ULong = 0x5DEECE66D00000001u,
    ): PDRoute {
        val rng = SplitMix64(seed)

        var bestPath: List<Int> = emptyList()
        var bestScore = Double.NEGATIVE_INFINITY
        var bestCombos = 0

        // Seed from a handful of random drags, including short ones: short paths
        // often score better because of the length penalty.
        repeat(12) {
            val start = rng.nextInt(PDBoard.CELL_COUNT)
            val length = rng.nextInt(8, maxLen + 1)
            val candidate = randomPathFrom(start, length, maxLen, rng)
            val (score, combos) = evaluate(board, candidate)
            if (score > bestScore) {
                bestScore = score
                bestPath = candidate
                bestCombos = combos
            }
        }

        repeat(restarts) {
            var currentPath = bestPath
            var currentScore = bestScore
            var temperature = 2.5

            repeat(budgetPerRestart) {
                var candidate = currentPath

                if (rng.nextUnit() < 0.6) {
                    // Re-route the tail from a random cell.
                    val cut = if (candidate.size > 1) rng.nextInt(1, candidate.size) else 1
                    val head = candidate.subList(0, cut)
                    val room = maxLen - head.size
                    if (room >= 1) {
                        val extra = rng.nextInt(1, room + 1)
                        val tail = randomPathFrom(head[head.size - 1], extra, maxLen, rng)
                        candidate = head + tail.drop(1)
                    } else {
                        return@repeat
                    }
                } else {
                    // Wiggle a single step.
                    if (candidate.size < 2) return@repeat
                    val i = rng.nextInt(1, candidate.size)
                    val options = neighbourIndicesOf(candidate[i - 1])
                    if (options.isEmpty()) return@repeat
                    candidate = candidate.toMutableList().also {
                        it[i] = options[rng.nextInt(options.size)]
                    }
                    val broken = candidate.indices.drop(1).firstOrNull { j ->
                        !areAdjacent(candidate[j - 1], candidate[j])
                    }
                    if (broken != null) candidate = candidate.subList(0, broken)
                }

                if (candidate.size < 2) return@repeat
                val (score, combos) = evaluate(board, candidate)
                val delta = score - currentScore
                if (delta >= 0 || rng.nextUnit() < exp(delta / max(temperature, 1e-6))) {
                    currentPath = candidate
                    currentScore = score
                    if (score > bestScore) {
                        bestScore = score
                        bestPath = candidate
                        bestCombos = combos
                    }
                }

                temperature *= 0.9965
                if (temperature < 0.05) temperature = 2.5
            }
        }

        return PDRoute(bestPath, bestCombos)
    }

    fun evaluate(board: PDBoard, path: List<Int>): Pair<Double, Int> {
        val after = board.dragged(along = path)
        val combos = after.resolveCombos()
        // Combo count dominates; path length is only a tie-breaker, because every
        // extra cell costs the player time inside the 4-5 second window.
        return Pair(combos * 10.0 - 0.35 * path.size, combos)
    }

    private fun randomPathFrom(start: Int, length: Int, maxLen: Int, rng: SplitMix64): List<Int> {
        val path = mutableListOf(start)
        var previous = -1
        var last = start
        val target = minOf(length, maxLen)

        while (path.size < target) {
            val options = neighbourIndicesOf(last).filter { it != previous }
            if (options.isEmpty()) break
            val next = options[rng.nextInt(options.size)]
            path.add(next)
            previous = last
            last = next
        }
        return path
    }

    private fun neighbourIndicesOf(index: Int): List<Int> {
        val row = PDBoard.rowOf(index)
        val col = PDBoard.colOf(index)
        val options = ArrayList<Int>(4)
        for ((dRow, dCol) in NEIGHBOURS) {
            val nextRow = row + dRow
            val nextCol = col + dCol
            if (nextRow in 0 until PDBoard.ROWS && nextCol in 0 until PDBoard.COLS) {
                options.add(PDBoard.index(nextRow, nextCol))
            }
        }
        return options
    }

    private fun areAdjacent(a: Int, b: Int): Boolean =
        Math.abs(PDBoard.rowOf(a) - PDBoard.rowOf(b)) +
            Math.abs(PDBoard.colOf(a) - PDBoard.colOf(b)) == 1

    // -- helpers on SplitMix64: uniform int ranges, mirroring Swift's Int.random --

    private fun SplitMix64.nextInt(until: Int): Int {
        if (until <= 0) return 0
        return (next() % until.toULong()).toInt()
    }

    private fun SplitMix64.nextInt(from: Int, until: Int): Int {
        if (until <= from) return from
        return from + nextInt(until - from)
    }
}
