package com.sudoku.recognizer.core

/**
 * Решатель судоку: 81 ячейка, 0 — пустая.
 * Ветвление с выбором клетки по минимальному числу кандидатов (MRV),
 * поэтому даже жёсткие задачи решаются мгновенно.
 */
object SudokuSolver {

    /** Индексы ячеек, которые конфликтуют с другими заполненными ячейками. */
    fun conflictsOf(grid: IntArray): Set<Int> {
        val bad = HashSet<Int>()
        val line = IntArray(9)
        for (i in 0 until 9) {
            for (j in 0 until 9) line[j] = i * 9 + j
            mark(line, grid, bad)

            for (j in 0 until 9) line[j] = j * 9 + i
            mark(line, grid, bad)
        }
        for (boxRow in 0 until 3) {
            for (boxCol in 0 until 3) {
                var k = 0
                for (r in 0 until 3) {
                    for (c in 0 until 3) {
                        line[k++] = (boxRow * 3 + r) * 9 + boxCol * 3 + c
                    }
                }
                mark(line, grid, bad)
            }
        }
        return bad
    }

    private fun mark(indices: IntArray, grid: IntArray, bad: MutableSet<Int>) {
        val seen = HashMap<Int, Int>(9)
        for (index in indices) {
            val value = grid[index]
            if (value == 0) continue
            val previous = seen.put(value, index)
            if (previous != null) {
                bad.add(previous)
                bad.add(index)
            }
        }
    }

    /** Возвращает копию grid с решением или null, если задача нерешаема. */
    fun solve(grid: IntArray): IntArray? {
        // Заданные числа не должны противоречить друг другу — иначе решать бессмысленно.
        if (conflictsOf(grid).isNotEmpty()) return null
        if (grid.any { it !in 0..9 }) return null
        val work = grid.copyOf()
        return if (fill(work)) work else null
    }

    fun isSolved(grid: IntArray): Boolean {
        for (value in grid) if (value == 0) return false
        return conflictsOf(grid).isEmpty()
    }

    private fun fill(grid: IntArray): Boolean {
        var best = -1
        var bestMask = 0
        var bestCount = 10
        for (i in 0 until 81) {
            if (grid[i] != 0) continue
            val mask = candidates(grid, i)
            val count = Integer.bitCount(mask)
            if (count < bestCount) {
                bestCount = count
                bestMask = mask
                best = i
                if (count <= 1) break
            }
        }
        if (best == -1) return true
        if (bestCount == 0) return false

        var mask = bestMask
        while (mask != 0) {
            val bit = mask and -mask
            // бит d соответствует цифре d (см. candidates)
            val digit = Integer.numberOfTrailingZeros(bit)
            grid[best] = digit
            if (fill(grid)) return true
            grid[best] = 0
            mask = mask xor bit
        }
        return false
    }

    private fun candidates(grid: IntArray, index: Int): Int {
        val row = index / 9
        val col = index % 9
        var used = 0
        for (j in 0 until 9) {
            used = used or (1 shl grid[row * 9 + j])
            used = used or (1 shl grid[j * 9 + col])
        }
        val boxRow = row / 3 * 3
        val boxCol = col / 3 * 3
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                used = used or (1 shl grid[(boxRow + r) * 9 + boxCol + c])
            }
        }
        return 0x3FE and used.inv() // биты 1..9, нулевой бит (значение 0) отбрасываем
    }
}
