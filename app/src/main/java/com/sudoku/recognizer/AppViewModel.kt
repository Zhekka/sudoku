package com.sudoku.recognizer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sudoku.recognizer.core.HistoryRecord
import com.sudoku.recognizer.core.HistoryStore
import com.sudoku.recognizer.core.SudokuSolver
import com.sudoku.recognizer.image.ExtractionResult
import com.sudoku.recognizer.image.GridExtractor
import com.sudoku.recognizer.image.Quad
import com.sudoku.recognizer.ml.DigitRecognizer
import com.sudoku.recognizer.ml.Recognition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Откуда взяты числа в сетке: фото (камера/галерея) или ручной ввод. */
enum class InputMode { PHOTO, MANUAL }

sealed class Screen {
    data object Home : Screen()
    data object Camera : Screen()
    data object Crop : Screen()
    data object Preview : Screen()
    data object Solution : Screen()
    data object History : Screen()
    data class HistoryDetail(val record: HistoryRecord) : Screen()
}

/**
 * Единое состояние приложения: фото → кадрирование → предпросмотр с правкой →
 * решение → история.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    /** Фото (уменьшенное); quad задан в координатах именно этого bitmap'а. */
    var source by mutableStateOf<Bitmap?>(null)
        private set

    var quad by mutableStateOf<Quad?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    /** Прогресс распознавания 0..1 (null — не идёт). */
    var progress by mutableStateOf<Float?>(null)
        private set

    var hint by mutableStateOf<String?>(null)
        private set

    var inputMode by mutableStateOf(InputMode.PHOTO)
        private set

    /** Распознанные или введённые числа: 81 значение, 0 — пусто. */
    var grid by mutableStateOf(IntArray(81))
        private set

    /** Клетки, в которых модель не уверена или не смогла прочитать цифру. */
    var lowConfidence by mutableStateOf(BooleanArray(81))
        private set

    var conflicts by mutableStateOf<Set<Int>>(emptySet())
        private set

    var selected by mutableStateOf(-1)

    /** Решение (после solve()) либо null. */
    var solution by mutableStateOf<IntArray?>(null)
        private set

    /** Какие клетки заполнил решатель (для подсветки на экране решения). */
    var filledBySolver by mutableStateOf<BooleanArray?>(null)
        private set

    var history by mutableStateOf<List<HistoryRecord>>(emptyList())
        private set

    var savedToHistory by mutableStateOf(false)
        private set

    private val store = HistoryStore(application)
    private var recognizer: DigitRecognizer? = null
    private var sourceTag = "camera"

    init {
        viewModelScope.launch(Dispatchers.IO) { history = store.load() }
    }

    // ------------------------------------------------------------------
    // Навигация
    // ------------------------------------------------------------------

    fun goHome() {
        resetPuzzle()
        screen = Screen.Home
    }

    fun openCamera() {
        screen = Screen.Camera
    }

    fun openHistory() {
        screen = Screen.History
    }

    /** Пустая сетка для ручного ввода задачи. */
    fun openManual() {
        resetPuzzle()
        inputMode = InputMode.MANUAL
        sourceTag = "manual"
        screen = Screen.Preview
    }

    /** Очистка сетки на экране ручного ввода. */
    fun clearGrid() {
        grid = IntArray(81)
        lowConfidence = BooleanArray(81)
        conflicts = emptySet()
        selected = -1
        dismissHint()
    }

    fun openRecord(record: HistoryRecord) {
        screen = Screen.HistoryDetail(record)
    }

    fun back() {
        screen = when (screen) {
            Screen.Camera -> Screen.Home
            Screen.Crop -> Screen.Home
            Screen.Preview -> Screen.Crop
            Screen.Solution -> {
                resetPuzzle()
                Screen.Home
            }
            Screen.History -> Screen.Home
            is Screen.HistoryDetail -> Screen.History
            else -> Screen.Home
        }
    }

    fun dismissHint() {
        hint = null
    }

    /** Сообщение об ошибке из внешних компонентов (например, камеры). */
    fun showError(message: String) {
        hint = message
    }

    private fun resetPuzzle() {
        grid = IntArray(81)
        lowConfidence = BooleanArray(81)
        conflicts = emptySet()
        solution = null
        filledBySolver = null
        selected = -1
        hint = null
        progress = null
        savedToHistory = false
        inputMode = InputMode.PHOTO
    }

    // ------------------------------------------------------------------
    // Ввод изображения
    // ------------------------------------------------------------------

    fun onPhotoCaptured(bitmap: Bitmap) {
        startPipeline(bitmap, "camera")
    }

    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) { decodeImage(uri) }
            if (decoded == null) {
                hint = "Не удалось открыть изображение"
            } else {
                startPipeline(decoded, "file")
            }
        }
    }

    private fun decodeImage(uri: Uri): Bitmap? = try {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIM) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        null
    }

    private fun startPipeline(bitmap: Bitmap, tag: String) {
        sourceTag = tag
        resetPuzzle()
        screen = Screen.Crop
        busy = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val scaled = GridExtractor.prepare(bitmap)
                val detected = GridExtractor.detectQuad(scaled)
                source = scaled
                quad = detected
            } catch (e: Exception) {
                val scaled = GridExtractor.prepare(bitmap)
                source = scaled
                quad = GridExtractor.fallbackQuad(scaled)
                hint = "Не удалось автоматически найти сетку — подгоните углы вручную"
            } finally {
                busy = false
            }
        }
    }

    fun moveCorner(index: Int, point: PointF) {
        val current = quad?.copyOf() ?: return
        when (index) {
            0 -> current.tl = point
            1 -> current.tr = point
            2 -> current.br = point
            3 -> current.bl = point
        }
        quad = current
    }

    fun redetect() {
        val src = source ?: return
        busy = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                quad = GridExtractor.detectQuad(src)
                hint = null
            } catch (e: Exception) {
                quad = GridExtractor.fallbackQuad(src)
            } finally {
                busy = false
            }
        }
    }

    // ------------------------------------------------------------------
    // Распознавание
    // ------------------------------------------------------------------

    fun recognize() {
        val src = source ?: return
        val q = quad ?: return
        hint = null
        screen = Screen.Preview
        busy = true
        progress = 0f
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val engine = recognizer()
                val extraction = GridExtractor.extractCells(src, q)
                val recognition = engine.recognize(extraction.cells) { p -> progress = p }
                applyRecognition(extraction, recognition)
            } catch (e: Exception) {
                hint = when (e) {
                    is java.io.FileNotFoundException -> "Не найдена модель распознавания (assets/sudoku_model.tflite)"
                    else -> "Не удалось распознать цифры: ${e.message}"
                }
                screen = Screen.Crop
            } finally {
                busy = false
                progress = null
            }
        }
    }

    private fun recognizer(): DigitRecognizer {
        recognizer?.let { return it }
        val created = DigitRecognizer(getApplication())
        recognizer = created
        return created
    }

    private fun applyRecognition(extraction: ExtractionResult, recognition: Recognition) {
        val values = IntArray(81)
        val low = BooleanArray(81)
        for (i in 0 until 81) {
            val digit = recognition.digits[i]
            val confidence = recognition.confidences[i]
            when {
                // В клетке пусто — так и оставляем.
                !extraction.ink[i] -> values[i] = 0

                // Есть чернил и модель уверенно назвала цифру.
                confidence >= MIN_CONFIDENCE && digit in 1..9 -> {
                    values[i] = digit
                    low[i] = confidence < HIGH_CONFIDENCE
                }

                // Похоже на цифру, но прочитать не удалось — показываем для проверки.
                else -> {
                    values[i] = 0
                    low[i] = true
                }
            }
        }
        grid = values
        lowConfidence = low
        conflicts = SudokuSolver.conflictsOf(values)
        solution = null
        filledBySolver = null
        selected = -1
        savedToHistory = false
        hint = if (conflicts.isNotEmpty()) {
            "Часть чисел противоречит правилам — исправьте подсвеченные клетки"
        } else {
            null
        }
    }

    // ------------------------------------------------------------------
    // Правка и решение
    // ------------------------------------------------------------------

    fun select(index: Int) {
        selected = if (selected == index) -1 else index
        dismissHint()
    }

    /** Ввод цифры с панели: работает только для выбранной клетки. */
    fun inputDigit(digit: Int) {
        if (selected < 0) {
            hint = "Сначала выберите клетку"
            return
        }
        setCell(selected, digit)
    }

    fun setCell(index: Int, value: Int) {
        if (index !in 0..80) return
        val next = grid.copyOf()
        next[index] = value
        grid = next
        conflicts = SudokuSolver.conflictsOf(next)
        val low = lowConfidence.copyOf()
        low[index] = false
        lowConfidence = low
        solution = null
        filledBySolver = null
        savedToHistory = false
        dismissHint()
    }

    fun solve() {
        val current = grid
        if (current.all { it == 0 }) {
            hint = if (inputMode == InputMode.MANUAL) {
                "Сначала введите числа задачи"
            } else {
                "Сначала распознайте судоку"
            }
            return
        }
        val bad = SudokuSolver.conflictsOf(current)
        if (bad.isNotEmpty()) {
            conflicts = bad
            hint = "Сначала исправьте конфликтные числа"
            return
        }
        val solved = SudokuSolver.solve(current)
        if (solved == null) {
            hint = "Решение не найдено — проверьте распознанные числа"
            return
        }
        val filled = BooleanArray(81) { current[it] == 0 }
        solution = solved
        filledBySolver = filled
        selected = -1
        hint = null
        savedToHistory = true

        val record = HistoryRecord(
            id = HistoryStore.newId(),
            createdAt = System.currentTimeMillis(),
            puzzle = current.copyOf(),
            solution = solved.copyOf(),
            source = sourceTag,
        )
        viewModelScope.launch(Dispatchers.IO) {
            store.add(record)
            history = store.load()
        }
        screen = Screen.Solution
    }

    // ------------------------------------------------------------------
    // История
    // ------------------------------------------------------------------

    fun deleteRecord(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(id)
            val updated = store.load()
            history = updated
            if (screen is Screen.HistoryDetail) screen = Screen.History
        }
    }

    override fun onCleared() {
        recognizer?.close()
        recognizer = null
        super.onCleared()
    }

    companion object {
        /** Порог «цифра считана» — 0.5, как в ноутбуке. */
        private const val MIN_CONFIDENCE = 0.5f

        /** Ниже этого уровня клетка помечается как требующая проверки. */
        private const val HIGH_CONFIDENCE = 0.85f

        private const val MAX_DIM = 1400
    }
}
