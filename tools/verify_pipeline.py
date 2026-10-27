#!/usr/bin/env python3
"""Проверка пайплайна распознавания: зеркало алгоритма из GridExtractor.kt.

Рисует синтетическое судоку и прогоняет тот же маршрут, что и в приложении:
    гауссово размытие 7x7 -> порог Оцу -> связные компоненты
    -> внешний контур (алгоритм Мура) -> approxPolyDP(0.04·периметра)
    -> выпуклый четырёхугольник с макс. периметром -> сортировка углов
    -> гомографика -> выравнивание 306x306 -> бинаризация Оцу
    -> 81 клетка 28x28 -> эвристика пустоты -> TFLite.
Сверяет найденную сетку и распознанные цифры с исходной задачей.

Запуск:  python tools/verify_pipeline.py
"""

import pathlib
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont
import tensorflow as tf

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODEL = ROOT / "app" / "src" / "main" / "assets" / "sudoku_model.tflite"

CELL = 34
GRID = CELL * 9
SIZE = 720           # размер «фото»
MARGIN = 60          # поле вокруг сетки

# Направления обхода по часовой стрелке (ось Y направлена вниз)
DIR_X = [1, 1, 0, -1, -1, -1, 0, 1]
DIR_Y = [0, 1, 1, 1, 0, -1, -1, -1]
DIRS = {(1, 0): 0, (1, 1): 1, (0, 1): 2, (-1, 1): 3, (-1, 0): 4, (-1, -1): 5, (0, -1): 6, (1, -1): 7}

PUZZLE = [
    [5, 3, 0, 0, 7, 0, 0, 0, 0],
    [6, 0, 0, 1, 9, 5, 0, 0, 0],
    [0, 9, 8, 0, 0, 0, 0, 6, 0],
    [8, 0, 0, 0, 6, 0, 0, 0, 3],
    [4, 0, 0, 8, 0, 3, 0, 0, 1],
    [7, 0, 0, 0, 2, 0, 0, 0, 6],
    [0, 6, 0, 0, 0, 0, 2, 8, 0],
    [0, 0, 0, 4, 1, 9, 0, 0, 5],
    [0, 0, 0, 0, 8, 0, 0, 7, 9],
]


# ---------------------------------------------------------------- отрисовка
def render_puzzle() -> np.ndarray:
    img = Image.new("L", (SIZE, SIZE), 255)
    draw = ImageDraw.Draw(img)
    step = (SIZE - 2 * MARGIN) / 9.0

    for i in range(10):
        pos = MARGIN + i * step
        width = 5 if i % 3 == 0 else 2
        draw.line([(MARGIN, pos), (SIZE - MARGIN, pos)], fill=0, width=width)
        draw.line([(pos, MARGIN), (pos, SIZE - MARGIN)], fill=0, width=width)

    try:
        font = ImageFont.truetype(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", int(step * 0.62)
        )
    except OSError:
        try:
            font = ImageFont.truetype(
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", int(step * 0.62)
            )
        except OSError:
            font = ImageFont.load_default(size=int(step * 0.62))

    for r in range(9):
        for c in range(9):
            value = PUZZLE[r][c]
            if value == 0:
                continue
            cx = MARGIN + (c + 0.5) * step
            cy = MARGIN + (r + 0.5) * step
            draw.text((cx, cy), str(value), fill=0, font=font, anchor="mm")

    return np.asarray(img, dtype=np.float32)


# ------------------------------------------------------------ зеркало CV-части
def gaussian_blur(gray: np.ndarray) -> np.ndarray:
    radius, sigma = 3, 1.4
    k = np.exp(-((np.arange(-radius, radius + 1) ** 2) / (2 * sigma**2)))
    k /= k.sum()
    padded = np.pad(gray, radius, mode="edge")
    tmp = np.apply_along_axis(lambda m: np.convolve(m, k, mode="valid"), 1, padded)
    out = np.apply_along_axis(lambda m: np.convolve(m, k, mode="valid"), 0, tmp)
    return out.astype(np.float32)


def otsu(values: np.ndarray) -> float:
    hist = np.bincount(
        np.clip(values.astype(np.int32), 0, 255).ravel(), minlength=256
    ).astype(np.float64)
    total = hist.sum()
    sum_all = (hist * np.arange(256)).sum()
    sum_b, w_b, best, max_var = 0.0, 0, 0, -1.0
    for t in range(256):
        w_b += hist[t]
        if w_b == 0:
            continue
        w_f = total - w_b
        if w_f == 0:
            break
        sum_b += t * hist[t]
        m_b = sum_b / w_b
        m_f = (sum_all - sum_b) / w_f
        between = w_b * w_f * (m_b - m_f) ** 2
        if between > max_var:
            max_var, best = between, t
    return float(best)


def label_components(ink: np.ndarray):
    """Разметка связных компонент 4-связности + [x0, y0, x1, y1] каждой."""
    h, w = ink.shape
    labels = np.zeros((h, w), dtype=np.int32)
    stats = []
    comp_id = 0
    ys, xs = np.nonzero(ink)
    for py, px in zip(ys, xs):
        if labels[py, px]:
            continue
        comp_id += 1
        stack = [(py, px)]
        labels[py, px] = comp_id
        x0 = x1 = px
        y0 = y1 = py
        while stack:
            y, x = stack.pop()
            x0, x1 = min(x0, x), max(x1, x)
            y0, y1 = min(y0, y), max(y1, y)
            for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= ny < h and 0 <= nx < w and ink[ny, nx] and not labels[ny, nx]:
                    labels[ny, nx] = comp_id
                    stack.append((ny, nx))
        stats.append((x0, y0, x1, y1))
    return labels, stats


def trace_contour(labels, comp_id, w, h, start_x, start_y):
    """Внешний контур компоненты — алгоритм Мура (как findContours(RETR_EXTERNAL))."""
    sx = sy = None
    for y in range(start_y, h):
        for x in range(start_x, w):
            if labels[y, x] == comp_id:
                sx, sy = x, y
                break
        if sx is not None:
            break
    if sx is None:
        return []

    contour = []
    cx, cy = sx, sy
    bx, by = sx - 1, sy
    steps = 0
    limit = w * h // 2 + 16
    while steps < limit:
        contour.append((cx, cy))
        back = DIRS.get((bx - cx, by - cy), -1)
        if back < 0:
            break
        found, last_bg = -1, back
        for i in range(1, 9):
            d = (back + i) % 8
            nx, ny = cx + DIR_X[d], cy + DIR_Y[d]
            if 0 <= nx < w and 0 <= ny < h and labels[ny, nx] == comp_id:
                found = d
                break
            last_bg = d
        if found < 0:
            break
        bx, by = cx + DIR_X[last_bg], cy + DIR_Y[last_bg]
        cx += DIR_X[found]
        cy += DIR_Y[found]
        steps += 1
        if (cx, cy) == (sx, sy):
            break
    return contour


def closed_perimeter(points):
    total = 0.0
    for i, a in enumerate(points):
        b = points[(i + 1) % len(points)]
        total += float(np.hypot(b[0] - a[0], b[1] - a[1]))
    return total


def approx_poly_dp(points, epsilon):
    """Douglas–Peucker для замкнутого контура (аналог approxPolyDP(closed=True))."""
    if len(points) < 4:
        return list(points)
    closed = list(points) + [points[0]]
    keep = [False] * len(closed)
    keep[0] = keep[-1] = True
    stack = [(0, len(closed) - 1)]
    while stack:
        first, last = stack.pop()
        if last <= first + 1:
            continue
        ax, ay = closed[first]
        bx, by = closed[last]
        dx, dy = bx - ax, by - ay
        length = float(np.hypot(dx, dy))
        max_dist, max_idx = -1.0, -1
        for i in range(first + 1, last):
            px, py = closed[i]
            if length < 1e-9:
                dist = float(np.hypot(px - ax, py - ay))
            else:
                dist = abs((px - ax) * dy - (py - ay) * dx) / length
            if dist > max_dist:
                max_dist, max_idx = dist, i
        if max_dist > epsilon and max_idx > 0:
            keep[max_idx] = True
            stack.append((first, max_idx))
            stack.append((max_idx, last))
    return [p for i, p in enumerate(closed[:-1]) if keep[i]]


def is_convex(points):
    sign = 0
    n = len(points)
    for i in range(n):
        a, b, c = points[i], points[(i + 1) % n], points[(i + 2) % n]
        cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
        if cross == 0:
            continue
        current = 1 if cross > 0 else -1
        if sign == 0:
            sign = current
        elif sign != current:
            return False
    return sign != 0


def sort_corners(points):
    """tl — min(x+y), br — max(x+y), tr — max(x−y), bl — min(x−y)."""
    tl = min(range(len(points)), key=lambda i: points[i][0] + points[i][1])
    br = max(range(len(points)), key=lambda i: points[i][0] + points[i][1])
    tr = max(range(len(points)), key=lambda i: points[i][0] - points[i][1])
    bl = min(range(len(points)), key=lambda i: points[i][0] - points[i][1])
    if len({tl, br, tr, bl}) != 4:
        return None
    return [points[tl], points[tr], points[br], points[bl]]


def detect_quad(gray: np.ndarray):
    """Граница картинки по схеме из ноутбука: контур -> approxPolyDP -> выпуклый 4-угольник."""
    h, w = gray.shape
    threshold = otsu(gray)
    labels, stats = label_components(gray <= threshold)

    min_span = int(min(w, h) * 0.2)
    best_quad, best_perim = None, 0.0
    for idx, (x0, y0, x1, y1) in enumerate(stats):
        if x1 - x0 + 1 < min_span or y1 - y0 + 1 < min_span:
            continue
        # компонент, закрывающий весь кадр, — фон фото, а не судоку
        if x0 == 0 and y0 == 0 and x1 == w - 1 and y1 == h - 1:
            continue
        contour = trace_contour(labels, idx + 1, w, h, x0, y0)
        if len(contour) < 8:
            continue
        perim = closed_perimeter(contour)
        if perim <= best_perim:
            continue
        approx = approx_poly_dp(contour, 0.04 * perim)
        if len(approx) != 4 or not is_convex(approx):
            continue
        quad = sort_corners(approx)
        if quad is None:
            continue
        best_perim, best_quad = perim, quad
    return best_quad


def solve_homography(dst, src):
    """dst -> src, 8 неизвестных."""
    a, b = [], []
    for (u, v), (x, y) in zip(dst, src):
        a.append([u, v, 1, 0, 0, 0, -u * x, -v * x])
        b.append(x)
        a.append([0, 0, 0, u, v, 1, -u * y, -v * y])
        b.append(y)
    return np.linalg.solve(np.array(a), np.array(b))


def warp_and_extract(gray, quad):
    dst = np.array([[0, 0], [GRID, 0], [GRID, GRID], [0, GRID]], dtype=np.float64)
    h = solve_homography(dst, quad)
    hh, ww = gray.shape
    v, u = np.mgrid[0:GRID, 0:GRID].astype(np.float64)
    den = h[6] * u + h[7] * v + 1.0
    x = (h[0] * u + h[1] * v + h[2]) / den
    y = (h[3] * u + h[4] * v + h[5]) / den
    x = np.clip(x, 0, ww - 1)
    y = np.clip(y, 0, hh - 1)
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    x1, y1 = np.minimum(x0 + 1, ww - 1), np.minimum(y0 + 1, hh - 1)
    fx, fy = x - x0, y - y0
    warped = (
        gray[y0, x0] * (1 - fx) * (1 - fy)
        + gray[y0, x1] * fx * (1 - fy)
        + gray[y1, x0] * (1 - fx) * fy
        + gray[y1, x1] * fx * fy
    ).astype(np.float32)

    threshold = otsu(warped)
    white = np.where(warped <= threshold, 255.0, 0.0)

    shift = (CELL - 28) // 2
    cells = np.zeros((81, 28, 28), dtype=np.float32)
    ink = np.zeros(81, dtype=bool)
    for index in range(81):
        row, col = index // 9, index % 9
        x0c, y0c = col * CELL + shift, row * CELL + shift
        patch = white[y0c : y0c + 28, x0c : x0c + 28]
        cells[index] = patch / 255.0
        ink[index] = _has_ink(cells[index])
    return cells, ink, white


def _has_ink(cell):
    votes = 0
    for shift in (6, 8, 10):
        region = cell[shift : 28 - shift, shift : 28 - shift]
        if region.size and (region > 0.5).mean() * 100 >= 11:
            votes += 1
    return votes >= 1


# ------------------------------------------------------------------------ main
def main() -> int:
    photo = render_puzzle()
    blurred = gaussian_blur(photo)

    quad = detect_quad(blurred)
    if quad is None:
        print("КОНТУР СЕТКИ НЕ НАЙДЕН", file=sys.stderr)
        return 1

    expected_corners = np.array(
        [
            [MARGIN, MARGIN],
            [SIZE - MARGIN, MARGIN],
            [SIZE - MARGIN, SIZE - MARGIN],
            [MARGIN, SIZE - MARGIN],
        ],
        dtype=np.float64,
    )
    quad = np.array(quad, dtype=np.float64)
    error = np.abs(quad - expected_corners).max()
    print(f"найденные углы:\n{quad.round(1)}")
    print(f"макс. отклонение от истинных углов: {error:.1f} px")
    if error > 12:
        print("ПЛОХАЯ ДЕТЕКЦИЯ СЕТКИ", file=sys.stderr)
        return 2

    cells, ink, _ = warp_and_extract(blurred, quad)

    non_empty = int(ink.sum())
    expected_non_empty = sum(1 for row in PUZZLE for v in row if v)
    print(f"клеток с «чернилами»: {non_empty}, ожидалось заполненных: {expected_non_empty}")

    interpreter = tf.lite.Interpreter(model_path=str(MODEL))
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()[0]
    out = interpreter.get_output_details()[0]

    digits = np.zeros(81, dtype=int)
    conf = np.zeros(81, dtype=np.float32)
    for i in range(81):
        interpreter.set_tensor(inp["index"], cells[i][None, ..., None])
        interpreter.invoke()
        probs = interpreter.get_tensor(out["index"])[0]
        digits[i] = int(np.argmax(probs))
        conf[i] = float(probs[digits[i]])

    recognized = []
    for i in range(81):
        if not ink[i]:
            recognized.append(0)
        elif conf[i] >= 0.5 and digits[i] in range(1, 10):
            recognized.append(int(digits[i]))
        else:
            recognized.append(0)

    truth = [v for row in PUZZLE for v in row]
    wrong = [i for i in range(81) if recognized[i] != truth[i]]
    print(f"распознано совпадений: {81 - len(wrong)}/81")
    if wrong:
        print(f"ошибки в клетках {wrong}")
        for i in wrong:
            r, c = i // 9, i % 9
            print(
                f"  [{r},{c}] истина={truth[i]} распознано={recognized[i]} "
                f"conf={conf[i]:.2f} ink={ink[i]}"
            )

    if wrong:
        return 3

    print("OK: пайплайн восстановил задачу полностью")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
