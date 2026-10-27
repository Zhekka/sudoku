package com.sudoku.recognizer

import com.sudoku.recognizer.core.HistoryStore
import com.sudoku.recognizer.core.SudokuSolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SudokuSolverTest {

    private val puzzle = intArrayOf(
        5, 3, 0, 0, 7, 0, 0, 0, 0,
        6, 0, 0, 1, 9, 5, 0, 0, 0,
        0, 9, 8, 0, 0, 0, 0, 6, 0,
        8, 0, 0, 0, 6, 0, 0, 0, 3,
        4, 0, 0, 8, 0, 3, 0, 0, 1,
        7, 0, 0, 0, 2, 0, 0, 0, 6,
        0, 6, 0, 0, 0, 0, 2, 8, 0,
        0, 0, 0, 4, 1, 9, 0, 0, 5,
        0, 0, 0, 0, 8, 0, 0, 7, 9,
    )

    private val expected = intArrayOf(
        5, 3, 4, 6, 7, 8, 9, 1, 2,
        6, 7, 2, 1, 9, 5, 3, 4, 8,
        1, 9, 8, 3, 4, 2, 5, 6, 7,
        8, 5, 9, 7, 6, 1, 4, 2, 3,
        4, 2, 6, 8, 5, 3, 7, 9, 1,
        7, 1, 3, 9, 2, 4, 8, 5, 6,
        9, 6, 1, 5, 3, 7, 2, 8, 4,
        2, 8, 7, 4, 1, 9, 6, 3, 5,
        3, 4, 5, 2, 8, 6, 1, 7, 9,
    )

    @Test
    fun solvesClassicPuzzle() {
        val solution = SudokuSolver.solve(puzzle)
        assertNotNull(solution)
        assertArrayEquals(expected, solution)
        assertTrue(SudokuSolver.isSolved(solution!!))
    }

    @Test
    fun doesNotModifyInput() {
        val copy = puzzle.copyOf()
        SudokuSolver.solve(puzzle)
        assertArrayEquals(copy, puzzle)
    }

    @Test
    fun detectsConflicts() {
        val grid = puzzle.copyOf()
        grid[1] = 7 // 7 уже стоит в четвёртой клетке первой строки
        val conflicts = SudokuSolver.conflictsOf(grid)
        assertTrue(conflicts.contains(1))
        assertTrue(conflicts.contains(4))
        assertEquals(2, conflicts.size)
    }

    @Test
    fun refusesContradictoryPuzzle() {
        val grid = puzzle.copyOf()
        grid[1] = 7 // дубль в первой строке
        assertNull(SudokuSolver.solve(grid))
    }

    @Test
    fun handlesAlreadySolvedGrid() {
        assertTrue(SudokuSolver.isSolved(expected))
        assertFalse(SudokuSolver.isSolved(puzzle))
        assertArrayEquals(expected, SudokuSolver.solve(expected))
    }

    @Test
    fun gridSerializationRoundTrip() {
        val encoded = HistoryStore.encodeGrid(puzzle)
        assertEquals(81, encoded.length)
        assertArrayEquals(puzzle, HistoryStore.decodeGrid(encoded))
        assertArrayEquals(IntArray(81), HistoryStore.decodeGrid(""))
    }
}
