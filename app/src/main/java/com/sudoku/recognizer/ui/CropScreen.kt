package com.sudoku.recognizer.ui

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.sudoku.recognizer.AppViewModel
import com.sudoku.recognizer.image.GridExtractor
import com.sudoku.recognizer.image.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Кадрирование: сетка судоку подхватывается автоматически, углы можно
 * подвинуть пальцем.
 */
@Composable
fun CropScreen(vm: AppViewModel) {
    val colors = MaterialTheme.colorScheme
    val bitmap = vm.source
    val quad = vm.quad

    /** Показывать автоматически выровненное изображение вместо исходного фото. */
    var showAligned by remember { mutableStateOf(false) }

    /** Выровненный кадр: пересчитывается с задержкой, пока углы перестают двигаться. */
    val aligned by produceState<Bitmap?>(null, bitmap, quad, showAligned) {
        val source = bitmap ?: return@produceState
        val corners = quad ?: return@produceState
        if (!showAligned) {
            value = null
            return@produceState
        }
        delay(150)
        value = withContext(Dispatchers.Default) {
            GridExtractor.warpAligned(source, corners)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::back) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад", tint = colors.onSurface)
            }
            Text(
                text = "Кадрирование",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
        }

        when {
            bitmap == null || quad == null -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Segment(text = "Фото", selected = !showAligned) { showAligned = false }
                    Segment(text = "Выравнивание", selected = showAligned) { showAligned = true }
                }
                Spacer(Modifier.height(8.dp))

                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val photo = fitSize(maxWidth, maxHeight, bitmap.width / bitmap.height.toFloat())
                    val side = minOf(photo.first, photo.second)
                    val boxWidth = if (showAligned) side else photo.first
                    val boxHeight = if (showAligned) side else photo.second

                    Box(
                        Modifier
                            .width(boxWidth)
                            .height(boxHeight),
                    ) {
                        if (showAligned) {
                            val preview = aligned
                            if (preview != null) {
                                Image(
                                    bitmap = preview.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Canvas(Modifier.fillMaxSize()) {
                                    val cellWidth = size.width / 9f
                                    val cellHeight = size.height / 9f
                                    for (i in 0..9) {
                                        val major = i % 3 == 0
                                        val lineColor = if (major) Color(0xFF2F6BE6) else Color(0x662F6BE6)
                                        val stroke = if (major) 2.dp.toPx() else 1.dp.toPx()
                                        val x = i * cellWidth
                                        val y = i * cellHeight
                                        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                                        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
                                    }
                                }
                            } else {
                                Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) { CircularProgressIndicator() }
                            }
                        } else {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )

                        Canvas(Modifier.fillMaxSize()) {
                            val sx = size.width / bitmap.width
                            val sy = size.height / bitmap.height
                            val path = Path().apply {
                                moveTo(quad.tl.x * sx, quad.tl.y * sy)
                                lineTo(quad.tr.x * sx, quad.tr.y * sy)
                                lineTo(quad.br.x * sx, quad.br.y * sy)
                                lineTo(quad.bl.x * sx, quad.bl.y * sy)
                                close()
                            }
                            drawPath(path, Color(0x332F6BE6))
                            drawPath(
                                path,
                                Color(0xFF2F6BE6),
                                style = Stroke(width = 3.dp.toPx()),
                            )
                        }

                        Canvas(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(bitmap, vm) {
                                    var active = -1
                                    detectDragGestures(
                                onDragStart = { position ->
                                    val current = vm.quad
                                    active = if (current == null) {
                                        -1
                                    } else {
                                        nearestCorner(
                                            position,
                                            current,
                                            size.width.toFloat() / bitmap.width,
                                            size.height.toFloat() / bitmap.height,
                                        )
                                    }
                                },
                                onDrag = { change, _ ->
                                    if (active >= 0) {
                                        val sx = size.width.toFloat() / bitmap.width
                                        val sy = size.height.toFloat() / bitmap.height
                                        vm.moveCorner(
                                            active,
                                            PointF(change.position.x / sx, change.position.y / sy),
                                        )
                                        change.consume()
                                    }
                                },
                                        onDragEnd = { active = -1 },
                                        onDragCancel = { active = -1 },
                                    )
                                },
                        ) {
                            val sx = size.width / bitmap.width
                            val sy = size.height / bitmap.height
                            val radius = 13.dp.toPx()
                            val points = listOf(quad.tl, quad.tr, quad.br, quad.bl)
                            for (point in points) {
                                drawCircle(
                                    color = Color.White,
                                    radius = radius + 3.dp.toPx(),
                                    center = Offset(point.x * sx, point.y * sy),
                                )
                                drawCircle(
                                    color = Color(0xFF2F6BE6),
                                    radius = radius,
                                    center = Offset(point.x * sx, point.y * sy),
                                )
                            }
                        }

                        }

                        if (vm.busy) {
                            Box(
                                Modifier.fillMaxSize().background(Color(0x66000000)),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(color = Color.White) }
                        }
                    }
                }

                if (showAligned) {
                    Text(
                        text = "Сетка выровнена автоматически — именно так задачу увидит нейросеть",
                        color = colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
            }
        }

        vm.hint?.let { message ->
            Text(
                text = message,
                color = colors.error,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = vm::redetect,
                modifier = Modifier.height(52.dp),
            ) {
                Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(text = "  Авто", fontSize = 15.sp)
            }
            Button(
                onClick = vm::recognize,
                enabled = !vm.busy,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                Text(text = "Распознать числа", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun fitSize(maxW: Dp, maxH: Dp, ratio: Float): Pair<Dp, Dp> =
    if (maxW / maxH > ratio) {
        maxH to maxH * ratio
    } else {
        maxW to maxW / ratio
    }

/** Один из двух сегментов переключателя вида. */
@Composable
private fun Segment(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.primary else colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun nearestCorner(position: Offset, quad: Quad, sx: Float, sy: Float): Int {
    val points = listOf(quad.tl, quad.tr, quad.br, quad.bl)
    var best = -1
    var bestDistance = 96f
    for (i in points.indices) {
        val dx = points[i].x * sx - position.x
        val dy = points[i].y * sy - position.y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (distance < bestDistance) {
            bestDistance = distance
            best = i
        }
    }
    return best
}
