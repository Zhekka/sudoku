package com.sudoku.recognizer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Цвета, общие для всех экранов с сеткой. */
object GridColors {
    /** Клетка, в которой модель не уверена. */
    val LowConfidence = Color(0x4DFFB300)

    /** Клетка с конфликтом правил. */
    val Conflict = Color(0x4DE53935)

    /** Выбранная клетка. */
    val Selected = Color(0x2E2F6BE6)
}

/**
 * Сетка 9×9: подсветки рисуются под цифрами, линии и маркеры — поверх,
 * обработчик нажатий — на всём поле.
 */
@Composable
fun SudokuGrid(
    values: IntArray,
    modifier: Modifier = Modifier,
    lowConfidence: BooleanArray? = null,
    conflictCells: Set<Int> = emptySet(),
    accent: BooleanArray? = null,
    selected: Int = -1,
    fontSize: TextUnit = 22.sp,
    onCellTap: (Int) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val thinLine = colors.outlineVariant
    val thickLine = colors.onSurface

    Box(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            for (row in 0 until 9) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (col in 0 until 9) {
                        val index = row * 9 + col
                        val background = when {
                            index == selected -> GridColors.Selected
                            index in conflictCells -> GridColors.Conflict
                            lowConfidence?.getOrNull(index) == true -> GridColors.LowConfidence
                            else -> Color.Transparent
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(background),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (values[index] != 0) {
                                val isAccent = accent?.getOrNull(index) == true
                                Text(
                                    text = values[index].toString(),
                                    color = if (isAccent) colors.secondary else colors.onSurface,
                                    fontSize = fontSize,
                                    fontWeight = if (isAccent) FontWeight.Medium else FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(values, conflictCells, selected) {
                    detectTapGestures { offset ->
                        val col = (offset.x / (size.width / 9f)).toInt().coerceIn(0, 8)
                        val row = (offset.y / (size.height / 9f)).toInt().coerceIn(0, 8)
                        onCellTap(row * 9 + col)
                    }
                },
        ) {
            val cellWidth = size.width / 9f
            val cellHeight = size.height / 9f
            val thin = 1.dp.toPx()
            val thick = 2.5.dp.toPx()

            for (i in 0..9) {
                val isMajor = i % 3 == 0
                val stroke = if (isMajor) thick else thin
                val color = if (isMajor) thickLine else thinLine
                val x = i * cellWidth
                val y = i * cellHeight
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            }

            if (lowConfidence != null) {
                val radius = 3.dp.toPx()
                for (index in lowConfidence.indices) {
                    if (!lowConfidence[index]) continue
                    val row = index / 9
                    val col = index % 9
                    drawCircle(
                        color = Color(0xFFFFB300),
                        radius = radius,
                        center = Offset(
                            x = (col + 1) * cellWidth - radius * 2f,
                            y = row * cellHeight + radius * 2f,
                        ),
                    )
                }
            }
        }
    }
}

/** Ряд цифр для правки распознанных значений. */
@Composable
fun NumberPad(
    onDigit: (Int) -> Unit,
    onErase: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (digit in 1..9) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceVariant)
                        .pointerInput(enabled) {
                            detectTapGestures {
                                if (enabled) onDigit(digit)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = digit.toString(),
                        color = colors.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceVariant)
                .pointerInput(enabled) {
                    detectTapGestures {
                        if (enabled) onErase()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Стереть",
                color = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.4f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Легенда под сеткой. */
@Composable
fun GridLegend(modifier: Modifier = Modifier, showSolutionHint: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(color = GridColors.LowConfidence, text = "проверьте")
        LegendItem(color = GridColors.Conflict, text = "конфликт")
        if (showSolutionHint) {
            Text(
                text = "синий — решение",
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(14.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
        Text(text = text, color = colors.onSurfaceVariant, fontSize = 13.sp)
    }
}
