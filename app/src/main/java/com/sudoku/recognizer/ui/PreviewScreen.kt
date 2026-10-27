package com.sudoku.recognizer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudoku.recognizer.AppViewModel

/**
 * Предпросмотр распознанного судоку: числа можно править перед решением.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(vm: AppViewModel) {
    val colors = MaterialTheme.colorScheme
    val values = vm.grid
    val givenCount = values.count { it != 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Проверьте числа") },
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = "Распознано чисел: $givenCount. Нажмите клетку и поправьте цифру, если она определена неверно.",
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )

            vm.hint?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(text = message, color = colors.error, fontSize = 13.sp)
            }

            if (vm.busy) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Распознаю цифры… ${((vm.progress ?: 0f) * 100).toInt()}%",
                        color = colors.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight)
                SudokuGrid(
                    values = values,
                    modifier = Modifier.size(side),
                    lowConfidence = vm.lowConfidence,
                    conflictCells = vm.conflicts,
                    selected = vm.selected,
                    onCellTap = vm::select,
                )
            }

            Spacer(Modifier.height(10.dp))
            GridLegend()
            Spacer(Modifier.height(10.dp))

            NumberPad(
                onDigit = vm::inputDigit,
                onErase = { vm.inputDigit(0) },
                enabled = vm.selected >= 0,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = vm::back,
                    modifier = Modifier.height(52.dp),
                ) {
                    Icon(Icons.Outlined.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(text = "  Кадр", fontSize = 15.sp)
                }
                Button(
                    onClick = vm::solve,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    Text(text = "Решить судоку", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Экран решения: исходные числа — тёмные, добавленные решателем — синие. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolutionScreen(vm: AppViewModel) {
    val colors = MaterialTheme.colorScheme
    val solution = vm.solution ?: return
    val filled = vm.filledBySolver

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Решение") },
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (vm.savedToHistory) {
                Text(
                    text = "Готово · сохранено в истории",
                    color = colors.secondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Text(
                    text = "Заполненные клетки подсвечены",
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(10.dp))

            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight)
                SudokuGrid(
                    values = solution,
                    modifier = Modifier.size(side),
                    accent = filled,
                    fontSize = 24.sp,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = vm::openHistory,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text(text = "История", fontSize = 15.sp)
                }
                Button(
                    onClick = vm::goHome,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    Text(text = "Новое судоку", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
