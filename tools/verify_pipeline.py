#!/usr/bin/env python3
"""Проверка пайплайна распознавания: зеркало алгоритма из GridExtractor.kt.

Рисует синтетическое судоку, прогоняет тот же маршрут, что и в приложении:
    размытие 7x7 -> Оцу -> связные компоненты -> углы -> уточнение прямых
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


def detect_quad(gray: np.ndarray):
    """Связные компоненты + экстремальные углы + уточнение прямых (PCA)."""
    h, w = gray.shape
    threshold = otsu(gray)
    ink = gray <= threshold

    labels = np.zeros((h, w), dtype=np.int32)
    best_id, best_score, best_box = 0, 0.0, None
    comp_id = 0
    ys, xs = np.nonzero(ink)
    for sy, sx in zip(ys, xs):
        if labels[sy, sx]:
            continue
        comp_id += 1
        stack = [(sy, sx)]
        labels[sy, sx] = comp_id
        count = 0
        y0 = x0 = 10**9
        y1 = x1 = -1
        while stack:
            y, x = stack.pop()
            count += 1
            x0, x1 = min(x0, x), max(x1, x)
            y0, y1 = min(y0, y), max(y1, y)
            for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= ny < h and 0 <= nx < w and ink[ny, nx] and not labels[ny, nx]:
                    labels[ny, nx] = comp_id
                    stack.append((ny, nx))
        bw, bh = x1 - x0 + 1, y1 - y0 + 1
        if bw < 0.30 * w or bh < 0.30 * h:
            continue
        aspect = bw / bh
        if not (0.45 <= aspect <= 2.2):
            continue
        score = count * min(1.0, aspect)
        if score > best_score:
            best_score, best_id, best_box = score, comp_id, (x0, y0, x1, y1)

    if best_id == 0:
        raise RuntimeError("сетка не найдена")

    py, px = np.nonzero(labels == best_id)
    s = px + py
    d = px - py
    pts = [
        (px[np.argmin(s)], py[np.argmin(s)]),   # tl
        (px[np.argmax(d)], py[np.argmax(d)]),   # tr
        (px[np.argmax(s)], py[np.argmax(s)]),   # br
        (px[np.argmin(d)], py[np.argmin(d)]),   # bl
    ]
    quad = np.array(pts, dtype=np.float64)

    # уточнение: PCA-прямая по пикселям вблизи ребра, затем пересечение
    diag = float(np.hypot(w, h))
    for _ in range(2):
        lines = []
        for i in range(4):
            a, b = quad[i], quad[(i + 1) % 4]
            direction = b - a
            length = np.hypot(*direction)
            if length < 1:
                return None
            normal = np.array([-direction[1], direction[0]]) / length
            band = diag * 0.035
            dist = np.abs((px - a[0]) * normal[0] + (py - a[1]) * normal[1])
            sel = dist <= band
            if sel.sum() < 20:
                return None
            pts_x, pts_y = px[sel].astype(np.float64), py[sel].astype(np.float64)
            mx, my = pts_x.mean(), pts_y.mean()
            ex, ey = pts_x - mx, pts_y - my
            sxx, syy, sxy = (ex * ex).sum(), (ey * ey).sum(), (ex * ey).sum()
            theta = 0.5 * np.arctan2(2 * sxy, sxx - syy)
            lines.append((np.array([-np.sin(theta), np.cos(theta)]), (mx, my)))
        nxt = []
        for i in range(4):
            (n1, p1), (n2, p2) = lines[(i - 1) % 4], lines[i]
            det = n1[0] * n2[1] - n1[1] * n2[0]
            if abs(det) < 1e-9:
                return None
            r1, r2 = n1 @ p1, n2 @ p2
            nxt.append(
                [(r1 * n2[1] - r2 * n1[1]) / det, (n1[0] * r2 - n2[0] * r1) / det]
            )
        nxt = np.array(nxt)
        rough = _area(quad)
        if rough <= 0 or not (0.65 <= _area(nxt) / rough <= 1.55):
            return None
        quad = nxt
    return quad


def _area(q):
    p = np.array(q, dtype=np.float64)
    x, y = p[:, 0], p[:, 1]
    return 0.5 * abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1)))


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
        print("УТОЧНЕНИЕ УГЛОВ НЕ СРАБОТАЛО", file=sys.stderr)
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
