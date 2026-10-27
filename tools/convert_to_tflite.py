#!/usr/bin/env python3
"""Конвертация Keras-модели распознавания цифр судоку в TFLite.

Вход:  [1, 28, 28, 1] float32, значения 0..1 (белая цифра на чёрном фоне).
Выход: [1, 10] float32, softmax по классам 0..9.

Запуск:
    python tools/convert_to_tflite.py
Результат: app/src/main/assets/sudoku_model.tflite
"""

import pathlib
import sys

import numpy as np
import tensorflow as tf

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "sudoku_model_improved.keras"
DST = ROOT / "app" / "src" / "main" / "assets" / "sudoku_model.tflite"


def main() -> int:
    if not SRC.exists():
        print(f"Не найдена модель: {SRC}", file=sys.stderr)
        return 1

    model = tf.keras.models.load_model(SRC)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_bytes = converter.convert()

    DST.parent.mkdir(parents=True, exist_ok=True)
    DST.write_bytes(tflite_bytes)
    print(f"Записано {DST} ({len(tflite_bytes)} байт)")

    # --- проверка: TFLite должен повторять предсказания Keras ---
    rng = np.random.default_rng(0)
    sample = rng.random((1, 28, 28, 1), dtype=np.float32)

    keras_out = model.predict(sample, verbose=0)[0]

    interp = tf.lite.Interpreter(model_path=str(DST))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    print("input:", inp["shape"], inp["dtype"], " output:", out["shape"], out["dtype"])

    interp.set_tensor(inp["index"], sample)
    interp.invoke()
    tflite_out = interp.get_tensor(out["index"])[0]

    diff = float(np.max(np.abs(keras_out - tflite_out)))
    print("argmax keras :", int(np.argmax(keras_out)))
    print("argmax tflite:", int(np.argmax(tflite_out)))
    print(f"макс. расхождение вероятностей: {diff:.6f}")
    if diff > 1e-3:
        print("ПРЕДУПРЕЖДЕНИЕ: расхождение больше 1e-3", file=sys.stderr)
        return 2

    # --- проверка на реальных цифрах MNIST ---
    (_, _), (x_test, y_test) = tf.keras.datasets.mnist.load_data()
    x = x_test[:500].astype("float32")[..., None] / 255.0
    keras_pred = np.argmax(model.predict(x, verbose=0), axis=1)

    interp2 = tf.lite.Interpreter(model_path=str(DST))
    interp2.allocate_tensors()
    inp2 = interp2.get_input_details()[0]
    out2 = interp2.get_output_details()[0]
    tfl_pred = []
    for i in range(len(x)):
        interp2.set_tensor(inp2["index"], x[i:i + 1])
        interp2.invoke()
        tfl_pred.append(int(np.argmax(interp2.get_tensor(out2["index"])[0])))
    tfl_pred = np.array(tfl_pred)

    acc_k = float(np.mean(keras_pred == y_test[:500]))
    acc_t = float(np.mean(tfl_pred == y_test[:500]))
    same = float(np.mean(keras_pred == tfl_pred))
    print(f"точность Keras {acc_k:.4f} | TFLite {acc_t:.4f} | совпадение {same:.4f}")
    if same < 0.99:
        print("ПРЕДУПРЕЖДЕНИЕ: предсказания заметно расходятся", file=sys.stderr)
        return 3

    print("OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
