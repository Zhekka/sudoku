package com.sudoku.recognizer.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Четыре угла области судоку в координатах исходного bitmap'а:
 * tl – верхний левый, tr – верхний правый, br – нижний правый, bl – нижний левый.
 */
data class Quad(
    var tl: PointF,
    var tr: PointF,
    var br: PointF,
    var bl: PointF,
) {
    fun points(): List<PointF> = listOf(tl, tr, br, bl)

    fun copyOf(): Quad = Quad(
        PointF(tl.x, tl.y),
        PointF(tr.x, tr.y),
        PointF(br.x, br.y),
        PointF(bl.x, bl.y),
    )
}

/** Результат нарезки: 81 клетка 28×28 (белые цифры на чёрном, значения 0..1). */
class ExtractionResult(
    val cells: Array<FloatArray>,
    /** Эвристика «в клетке что-то нарисовано» (как block_is_null в ноутбуке). */
    val ink: BooleanArray,
)

/**
 * Порт pipeline'а из ноутбука (cv2) на чистый Kotlin:
 *  размытие → Оцу → поиск четырёхугольника сетки → перспективное преобразование
 *  → нарезка на 81 клетку 34×34 с центральным кадрированием 28×28.
 */
object GridExtractor {

    /** Размер клетки на «выровненном» изображении (как block_size в ноутбуке). */
    const val CELL = 34
    /** Сторона квадрата после перспективного преобразования. */
    const val GRID = CELL * 9
    private const val MAX_DIM = 1400
    private const val CELL_INNER = 28
    private const val CELL_SHIFT = (CELL - CELL_INNER) / 2

    /** Уменьшает изображение, чтобы обработка была быстрой на телефоне. */
    fun prepare(src: Bitmap, maxDim: Int = MAX_DIM): Bitmap {
        val w = src.width
        val h = src.height
        val longest = max(w, h)
        if (longest <= maxDim) return src
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (w * scale).roundToInt(), (h * scale).roundToInt(), true)
    }

    /** Полный кадр с небольшим отступом — используется, когда сетку не нашли. */
    fun fallbackQuad(bitmap: Bitmap): Quad {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val dx = w * 0.05f
        val dy = h * 0.05f
        return Quad(PointF(dx, dy), PointF(w - dx, dy), PointF(w - dx, h - dy), PointF(dx, h - dy))
    }

    // ------------------------------------------------------------------
    // Детекция
    // ------------------------------------------------------------------

    fun detectQuad(bitmap: Bitmap): Quad {
        val w = bitmap.width
        val h = bitmap.height
        val gray = blurredGray(bitmap, w, h)
        val threshold = otsu(gray)
        val ink = BooleanArray(w * h) { gray[it] <= threshold }

        val labels = IntArray(w * h)
        val stack = IntArray(w * h)

        var bestId = 0
        var bestScore = 0.0

        var id = 0
        var index = 0
        while (index < w * h) {
            if (ink[index] && labels[index] == 0) {
                id++
                var sp = 0
                stack[sp++] = index
                labels[index] = id
                var count = 0
                var x0 = w
                var y0 = h
                var x1 = 0
                var y1 = 0
                while (sp > 0) {
                    val p = stack[--sp]
                    val x = p % w
                    val y = p / w
                    count++
                    if (x < x0) x0 = x
                    if (y < y0) y0 = y
                    if (x > x1) x1 = x
                    if (y > y1) y1 = y
                    if (x > 0 && ink[p - 1] && labels[p - 1] == 0) {
                        labels[p - 1] = id; stack[sp++] = p - 1
                    }
                    if (x < w - 1 && ink[p + 1] && labels[p + 1] == 0) {
                        labels[p + 1] = id; stack[sp++] = p + 1
                    }
                    if (y > 0 && ink[p - w] && labels[p - w] == 0) {
                        labels[p - w] = id; stack[sp++] = p - w
                    }
                    if (y < h - 1 && ink[p + w] && labels[p + w] == 0) {
                        labels[p + w] = id; stack[sp++] = p + w
                    }
                }

                val bw = x1 - x0 + 1
                val bh = y1 - y0 + 1
                val wideEnough = bw >= 0.30f * w && bh >= 0.30f * h
                val aspect = bw.toFloat() / bh.toFloat()
                val aspectOk = aspect in 0.45f..2.2f
                if (wideEnough && aspectOk) {
                    // Кандидат: предпочитаем компоненту с большим количеством «чернил».
                    val score = count.toDouble() * min(1.0, aspect.toDouble())
                    if (score > bestScore) {
                        bestScore = score
                        bestId = id
                    }
                }
            }
            index++
        }

        if (bestId == 0) return fallbackQuad(bitmap)

        val bestPixels = collectPixels(labels, bestId, w * h)
        if (bestPixels.size < 64) return fallbackQuad(bitmap)

        var quad = cornersOf(bestPixels, w)
        quad = refineQuad(bestPixels, w, h, quad) ?: quad
        if (areaOf(quad) <= 0.0f) quad = fallbackQuad(bitmap)
        return clampQuad(quad, w, h)
    }

    private fun collectPixels(labels: IntArray, label: Int, size: Int): IntArray {
        var n = 0
        for (i in 0 until size) if (labels[i] == label) n++
        if (n == 0) return IntArray(0)
        val out = IntArray(n)
        var k = 0
        for (i in 0 until size) if (labels[i] == label) out[k++] = i
        return out
    }

    /** Четыре экстремальных точки компоненты (диагонали квадрата). */
    private fun cornersOf(pixels: IntArray, width: Int): Quad {
        if (pixels.isEmpty()) return Quad(
            PointF(0f, 0f), PointF(width.toFloat(), 0f),
            PointF(width.toFloat(), width.toFloat()), PointF(0f, width.toFloat()),
        )
        var tl = pixels[0]; var br = pixels[0]; var tr = pixels[0]; var bl = pixels[0]
        var tlV = Int.MAX_VALUE; var brV = Int.MIN_VALUE
        var trV = Int.MIN_VALUE; var blV = Int.MAX_VALUE
        for (p in pixels) {
            val x = p % width
            val y = p / width
            val s = x + y
            val d = x - y
            if (s < tlV) { tlV = s; tl = p }
            if (s > brV) { brV = s; br = p }
            if (d > trV) { trV = d; tr = p }
            if (d < blV) { blV = d; bl = p }
        }
        fun pt(p: Int) = PointF((p % width).toFloat(), (p / width).toFloat())
        return Quad(pt(tl), pt(tr), pt(br), pt(bl))
    }

    private data class Line(val nx: Double, val ny: Double, val px: Double, val py: Double) {
        fun distanceTo(x: Double, y: Double): Double =
            abs(nx * (x - px) + ny * (y - py))
    }

    /**
     * Уточнение углов: по пикселям компоненты вблизи каждого ребра восстанавливаем
     * прямую (PCA) и пересекаем соседние прямые. Улучшает результат на перспективе.
     */
    private fun refineQuad(pixels: IntArray, width: Int, height: Int, quad: Quad): Quad? {
        if (pixels.size < 200) return null
        val diag = sqrt((width * width + height * height).toDouble())
        var current = quad
        val roughArea = areaOf(current)
        if (roughArea <= 0f) return null

        repeat(2) {
            val pts = current.points()
            val lines = arrayOf(
                fitLine(pixels, width, pts[0], pts[1], diag) ?: return null,
                fitLine(pixels, width, pts[1], pts[2], diag) ?: return null,
                fitLine(pixels, width, pts[2], pts[3], diag) ?: return null,
                fitLine(pixels, width, pts[3], pts[0], diag) ?: return null,
            )
            val next = arrayOf(
                intersect(lines[3], lines[0]) ?: current.tl,
                intersect(lines[0], lines[1]) ?: current.tr,
                intersect(lines[1], lines[2]) ?: current.br,
                intersect(lines[2], lines[3]) ?: current.bl,
            )
            val candidate = Quad(next[0], next[1], next[2], next[3])
            val ratio = areaOf(candidate) / roughArea
            if (ratio < 0.65f || ratio > 1.55f) return null
            current = candidate
        }
        return current
    }

    private fun fitLine(pixels: IntArray, width: Int, a: PointF, b: PointF, diag: Double): Line? {
        val ax = a.x.toDouble(); val ay = a.y.toDouble()
        val bx = b.x.toDouble(); val by = b.y.toDouble()
        val dx = bx - ax; val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1.0) return null
        val nx = -dy / len; val ny = dx / len
        val band = diag * 0.035

        var n = 0
        var sx = 0.0; var sy = 0.0
        val xs = DoubleArray(pixels.size)
        val ys = DoubleArray(pixels.size)
        for (p in pixels) {
            val x = (p % width).toDouble()
            val y = (p / width).toDouble()
            if (abs(nx * (x - ax) + ny * (y - ay)) <= band) {
                xs[n] = x; ys[n] = y; n++
                sx += x; sy += y
            }
        }
        if (n < 20) return null
        val mx = sx / n
        val my = sy / n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val ex = xs[i] - mx
            val ey = ys[i] - my
            sxx += ex * ex
            syy += ey * ey
            sxy += ex * ey
        }
        val theta = 0.5 * atan2(2.0 * sxy, sxx - syy)
        val dirX = cos(theta)
        val dirY = sin(theta)
        return Line(-dirY, dirX, mx, my)
    }

    private fun intersect(l1: Line, l2: Line): PointF? {
        val det = l1.nx * l2.ny - l1.ny * l2.nx
        if (abs(det) < 1e-9) return null
        val r1 = l1.nx * l1.px + l1.ny * l1.py
        val r2 = l2.nx * l2.px + l2.ny * l2.py
        val x = (r1 * l2.ny - r2 * l1.ny) / det
        val y = (l1.nx * r2 - l2.nx * r1) / det
        if (!x.isFinite() || !y.isFinite()) return null
        return PointF(x.toFloat(), y.toFloat())
    }

    private fun areaOf(q: Quad): Float {
        // Площадь по формуле Гаусса для четырёх точек.
        val p = q.points()
        var sum = 0.0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            sum += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
        }
        return (abs(sum) / 2.0).toFloat()
    }

    private fun clampQuad(q: Quad, width: Int, height: Int): Quad {
        fun clamp(p: PointF) = PointF(
            p.x.coerceIn(0f, width - 1f),
            p.y.coerceIn(0f, height - 1f),
        )
        return Quad(clamp(q.tl), clamp(q.tr), clamp(q.br), clamp(q.bl))
    }

    // ------------------------------------------------------------------
    // Кадрирование (перспективное преобразование) + нарезка клеток
    // ------------------------------------------------------------------

    fun extractCells(bitmap: Bitmap, quad: Quad): ExtractionResult {
        val w = bitmap.width
        val h = bitmap.height
        val gray = blurredGray(bitmap, w, h)

        // Соответствие точек: dst(0,0)->tl, (S,0)->tr, (S,S)->br, (0,S)->bl
        val side = GRID.toDouble()
        val dstX = doubleArrayOf(0.0, side, side, 0.0)
        val dstY = doubleArrayOf(0.0, 0.0, side, side)
        val q = quad.points()
        val sx = doubleArrayOf(q[0].x.toDouble(), q[1].x.toDouble(), q[2].x.toDouble(), q[3].x.toDouble())
        val sy = doubleArrayOf(q[0].y.toDouble(), q[1].y.toDouble(), q[2].y.toDouble(), q[3].y.toDouble())

        val homography = solveHomography(dstX, dstY, sx, sy)
            ?: return ExtractionResult(Array(81) { FloatArray(CELL_INNER * CELL_INNER) }, BooleanArray(81))

        // 1. Выравнивание (bilinear-сэмплинг размытого серого изображения).
        val warped = FloatArray(GRID * GRID)
        for (v in 0 until GRID) {
            val dv = v.toDouble()
            for (u in 0 until GRID) {
                val du = u.toDouble()
                val den = homography[6] * du + homography[7] * dv + 1.0
                if (den == 0.0) { warped[v * GRID + u] = 255f; continue }
                val x = (homography[0] * du + homography[1] * dv + homography[2]) / den
                val y = (homography[3] * du + homography[4] * dv + homography[5]) / den
                warped[v * GRID + u] = sampleBilinear(gray, w, h, x, y)
            }
        }

        // 2. Бинаризация Оцу уже на выровненном изображении (как THRESH_BINARY_INV).
        val threshold = otsuFloat(warped)
        val white = FloatArray(warped.size) { i -> if (warped[i] <= threshold) 255f else 0f }

        // 3. Нарезка: клетка 34×34 → центральное кадрирование 28×28.
        val cells = Array(81) { FloatArray(CELL_INNER * CELL_INNER) }
        val ink = BooleanArray(81)
        for (index in 0 until 81) {
            val row = index / 9
            val col = index % 9
            val baseX = col * CELL + CELL_SHIFT
            val baseY = row * CELL + CELL_SHIFT
            val cell = cells[index]
            var k = 0
            for (dy in 0 until CELL_INNER) {
                val sy2 = (baseY + dy) * GRID
                for (dx in 0 until CELL_INNER) {
                    cell[k++] = white[sy2 + baseX + dx] / 255f
                }
            }
            ink[index] = hasInk(cell)
        }
        return ExtractionResult(cells, ink)
    }

    /**
     * Аналог block_is_null из ноутбука: порог 11% «чернил» в центральной области
     * при смещениях 6/8/10; клетка считается непустой, если таких совпало >= 1.
     */
    private fun hasInk(cell: FloatArray): Boolean {
        var votes = 0
        for (shift in intArrayOf(6, 8, 10)) {
            var filled = 0
            var area = 0
            for (y in shift until CELL_INNER - shift) {
                val rowBase = y * CELL_INNER
                for (x in shift until CELL_INNER - shift) {
                    if (cell[rowBase + x] > 0.5f) filled++
                    area++
                }
            }
            if (area > 0 && filled * 100f / area >= 11f) votes++
        }
        return votes >= 1
    }

    // ------------------------------------------------------------------
    // Низкоуровневые операции
    // ------------------------------------------------------------------

    private fun grayOf(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt()
        }
    }

    /** Гауссово размытие 7×7 (σ ≈ 1.4), как GaussianBlur(gray, (7,7), 0). */
    private fun blurredGray(bitmap: Bitmap, w: Int, h: Int): IntArray {
        val gray = grayOf(bitmap)
        val radius = 3
        val sigma = 1.4
        val kernel = FloatArray(radius * 2 + 1) { i ->
            val d = i - radius
            exp(-(d * d) / (2.0 * sigma * sigma)).toFloat()
        }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum

        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                for (k in -radius..radius) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    acc += gray[row + xx] * kernel[k + radius]
                }
                tmp[row + x] = acc
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (k in -radius..radius) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    acc += tmp[yy * w + x] * kernel[k + radius]
                }
                out[y * w + x] = acc.toInt()
            }
        }
        return out
    }

    private fun otsu(values: IntArray): Int {
        val hist = IntArray(256)
        for (v in values) hist[v.coerceIn(0, 255)]++
        return otsuFromHist(hist, values.size)
    }

    private fun otsuFloat(values: FloatArray): Float {
        val hist = IntArray(256)
        for (v in values) hist[v.toInt().coerceIn(0, 255)]++
        return otsuFromHist(hist, values.size).toFloat()
    }

    private fun otsuFromHist(hist: IntArray, total: Int): Int {
        var sum = 0.0
        for (t in 0..255) sum += t * hist[t].toDouble()
        var sumB = 0.0
        var wB = 0
        var best = 0
        var maxVar = -1.0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxVar) { maxVar = between; best = t }
        }
        return best
    }

    private fun sampleBilinear(gray: IntArray, w: Int, h: Int, x: Double, y: Double): Float {
        val cx = x.coerceIn(0.0, (w - 1).toDouble())
        val cy = y.coerceIn(0.0, (h - 1).toDouble())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = (cx - x0).toFloat()
        val fy = (cy - y0).toFloat()
        val p00 = gray[y0 * w + x0]
        val p01 = gray[y0 * w + x1]
        val p10 = gray[y1 * w + x0]
        val p11 = gray[y1 * w + x1]
        val top = p00 * (1 - fx) + p01 * fx
        val bottom = p10 * (1 - fx) + p11 * fx
        return top * (1 - fy) + bottom * fy
    }

    /** Решает систему для гомографики dst→src (8 неизвестных, Гаусс). */
    private fun solveHomography(
        dstX: DoubleArray, dstY: DoubleArray,
        srcX: DoubleArray, srcY: DoubleArray,
    ): DoubleArray? {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val u = dstX[i]
            val v = dstY[i]
            a[i * 2][0] = u; a[i * 2][1] = v; a[i * 2][2] = 1.0
            a[i * 2][6] = -u * srcX[i]; a[i * 2][7] = -v * srcX[i]; a[i * 2][8] = srcX[i]
            a[i * 2 + 1][3] = u; a[i * 2 + 1][4] = v; a[i * 2 + 1][5] = 1.0
            a[i * 2 + 1][6] = -u * srcY[i]; a[i * 2 + 1][7] = -v * srcY[i]; a[i * 2 + 1][8] = srcY[i]
        }
        // Прямой ход
        for (col in 0 until 8) {
            var pivot = col
            for (r in col + 1 until 8) {
                if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            }
            if (abs(a[pivot][col]) < 1e-12) return null
            if (pivot != col) {
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            }
            for (r in 0 until 8) {
                if (r == col) continue
                val factor = a[r][col] / a[col][col]
                if (factor == 0.0) continue
                for (c in col until 9) a[r][c] -= factor * a[col][c]
            }
        }
        return DoubleArray(8) { a[it][8] / a[it][it] }
    }
}
