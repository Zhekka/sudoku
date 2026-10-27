package com.sudoku.recognizer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudoku.recognizer.AppViewModel
import com.sudoku.recognizer.core.HistoryRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Список сохранённых решений. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: AppViewModel) {
    val colors = MaterialTheme.colorScheme
    val records = vm.history
    val formatter = remember { SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("История") },
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        if (records.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Здесь появятся решённые судоку.\nСфотографируйте первое —",
                    color = colors.onSurfaceVariant,
                    fontSize = 15.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    HistoryCard(
                        record = record,
                        date = formatter.format(Date(record.createdAt)),
                        onClick = { vm.openRecord(record) },
                        onDelete = { vm.deleteRecord(record.id) },
                    )
                }
            }
        }
    }
}

/** Карточка одного сохранённого решения. */
@Composable
private fun HistoryCard(
    record: HistoryRecord,
    date: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniGrid(values = record.puzzle, modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = date,
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Подсказок: ${record.givenCount} · ${sourceLabel(record.source)}",
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Удалить",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun sourceLabel(source: String): String = when (source) {
    "camera" -> "фото"
    "file" -> "файл"
    "manual" -> "ручной ввод"
    else -> source
}

/** Мини-превью сетки: заполненные клетки — тёмные квадратики. */
@Composable
private fun MiniGrid(values: IntArray, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        val cell = Size(size.width / 9f, size.height / 9f)
        for (index in 0 until 81) {
            if (values[index] == 0) continue
            val row = index / 9
            val col = index % 9
            drawRect(
                color = colors.onSurface,
                topLeft = Offset(
                    x = col * cell.width + 1f,
                    y = row * cell.height + 1f,
                ),
                size = Size(cell.width - 2f, cell.height - 2f),
            )
        }
    }
}

/** Полный просмотр одного сохранённого решения. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(vm: AppViewModel, record: HistoryRecord) {
    val colors = MaterialTheme.colorScheme
    val formatter = remember { SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru")) }
    val filled = remember(record) { BooleanArray(81) { record.puzzle[it] == 0 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(formatter.format(Date(record.createdAt))) },
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.deleteRecord(record.id) }) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = "Удалить",
                            tint = colors.onSurfaceVariant,
                        )
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
            Text(
                text = "Исходное судоку сохранено вместе с решением",
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight)
                SudokuGrid(
                    values = record.solution,
                    modifier = Modifier.size(side),
                    accent = filled,
                    fontSize = 24.sp,
                )
            }

            Spacer(Modifier.height(12.dp))
            GridLegend()
            Spacer(Modifier.height(12.dp))
        }
    }
}
