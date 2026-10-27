package com.sudoku.recognizer.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Результат распознавания 81 клетки. */
class Recognition(
    val digits: IntArray,
    val confidences: FloatArray,
)

/**
 * Обёртка над TFLite-моделью из ноутбука:
 * вход [1, 28, 28, 1] float32 (0..1), выход [1, 10] softmax, класс 0 — «пустая клетка».
 */
class DigitRecognizer(context: Context) {

    private val interpreter: Interpreter

    init {
        val model = loadModel(context)
        val options = Interpreter.Options().apply { setNumThreads(4) }
        interpreter = Interpreter(model, options)
    }

    /** Модель копируется из assets в filesDir и маппится в память. */
    private fun loadModel(context: Context): MappedByteBuffer {
        val file = File(context.filesDir, MODEL_NAME)
        context.assets.open(MODEL_NAME).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return FileInputStream(file).channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    /**
     * @param cells 81 клетка 28×28 (28*28 значений 0..1)
     * @param onProgress прогресс 0..1 (вызывается из рабочего потока)
     */
    fun recognize(cells: Array<FloatArray>, onProgress: ((Float) -> Unit)? = null): Recognition {
        val input = ByteBuffer.allocateDirect(CELL * CELL * 4).order(ByteOrder.nativeOrder())
        val output = Array(1) { FloatArray(10) }
        val digits = IntArray(cells.size)
        val confidences = FloatArray(cells.size)

        for (i in cells.indices) {
            input.rewind()
            for (value in cells[i]) input.putFloat(value)
            input.rewind()
            interpreter.run(input, output)

            var best = 0
            for (c in 1..9) if (output[0][c] > output[0][best]) best = c
            digits[i] = best
            confidences[i] = output[0][best]

            if (i % 6 == 0) onProgress?.invoke((i + 1).toFloat() / cells.size)
        }
        onProgress?.invoke(1f)
        return Recognition(digits, confidences)
    }

    fun close() {
        interpreter.close()
    }

    companion object {
        const val MODEL_NAME = "sudoku_model.tflite"
        private const val CELL = 28
    }
}
