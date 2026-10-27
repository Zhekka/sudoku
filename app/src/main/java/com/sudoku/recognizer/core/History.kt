package com.sudoku.recognizer.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Одна сохранённая задача с решением. */
data class HistoryRecord(
    val id: String,
    val createdAt: Long,
    /** 81 значение: 0 — пустая клетка. */
    val puzzle: IntArray,
    val solution: IntArray,
    /** Откуда взято фото: "camera" или "file". */
    val source: String,
) {
    val givenCount: Int
        get() = puzzle.count { it != 0 }

    override fun equals(other: Any?): Boolean = other is HistoryRecord && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/** Хранилище истории в filesDir/history.json. */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val lock = Any()

    fun load(): List<HistoryRecord> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                HistoryRecord(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    createdAt = obj.optLong("createdAt", 0L),
                    puzzle = decodeGrid(obj.optString("puzzle", "")),
                    solution = decodeGrid(obj.optString("solution", "")),
                    source = obj.optString("source", "camera"),
                )
            }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(record: HistoryRecord) = synchronized(lock) {
        val current = load().toMutableList()
        current.removeAll { it.id == record.id }
        current.add(0, record)
        write(current)
        Unit
    }

    fun delete(id: String) = synchronized(lock) {
        write(load().filterNot { it.id == id })
        Unit
    }

    fun deleteAll() = synchronized(lock) {
        write(emptyList())
        Unit
    }

    private fun write(records: List<HistoryRecord>) {
        val array = JSONArray()
        for (r in records) {
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("createdAt", r.createdAt)
            obj.put("puzzle", encodeGrid(r.puzzle))
            obj.put("solution", encodeGrid(r.solution))
            obj.put("source", r.source)
            array.put(obj)
        }
        file.writeText(array.toString())
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun encodeGrid(grid: IntArray): String = buildString(81) {
            for (value in grid) append(if (value in 1..9) value.toString() else ".")
        }

        fun decodeGrid(text: String): IntArray {
            val out = IntArray(81)
            for (i in 0 until 81) {
                val c = text.getOrNull(i)
                out[i] = if (c != null && c in '1'..'9') c - '0' else 0
            }
            return out
        }
    }
}
