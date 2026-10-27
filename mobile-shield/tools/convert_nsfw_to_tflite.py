#!/usr/bin/env python3
"""Convert the GantMan nsfw_model (MobileNetV2 224) to TensorFlow Lite.

Produces a float32 model whose input is RGB normalized to 0..1 in NHWC
[1,224,224,3] and whose output is 5-class softmax in the order
[drawings, hentai, neutral, porn, sexy] — exactly what TfliteNsfwClassifier
expects. NSFW = hentai + porn + sexy.

Usage:
    python convert_nsfw_to_tflite.py <saved_model_dir | model.h5> [out.tflite] [test_image.jpg]

Example:
    python convert_nsfw_to_tflite.py nsfw_mobilenet2.224x224.h5 nsfw.tflite neutral_photo.jpg
"""
import os
import sys
import numpy as np
import tensorflow as tf

LABELS = ["drawings", "hentai", "neutral", "porn", "sexy"]


def load_converter(src: str) -> "tf.lite.TFLiteConverter":
    if os.path.isdir(src):
        return tf.lite.TFLiteConverter.from_saved_model(src)
    if src.endswith(".h5"):
        model = tf.keras.models.load_model(src, compile=False)
        return tf.lite.TFLiteConverter.from_keras_model(model)
    raise SystemExit(f"Unsupported model path: {src} (expected a SavedModel dir or .h5 file)")


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else "nsfw.tflite"
    test = sys.argv[3] if len(sys.argv) > 3 else None

    converter = load_converter(src)
    # Keep pure float32 I/O so it matches the app's preprocessing (RGB / 255).
    # (Do NOT enable optimizations here unless you also adjust the app.)
    tflite_model = converter.convert()
    with open(dst, "wb") as f:
        f.write(tflite_model)
    print(f"Wrote {dst} ({len(tflite_model):,} bytes)")

    interp = tf.lite.Interpreter(model_path=dst)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    print("Input :", inp["shape"], inp["dtype"])
    print("Output:", out["shape"], out["dtype"])

    if list(out["shape"][-1:]) != [len(LABELS)]:
        print(f"WARNING: expected {len(LABELS)} output classes; got {out['shape']}. "
              "The app's NUM_CLASSES / score formula may need adjusting.")

    if test:
        from PIL import Image
        img = Image.open(test).convert("RGB").resize((224, 224))
        x = (np.asarray(img, dtype=np.float32) / 255.0)[None, ...]
        interp.set_tensor(inp["index"], x.astype(inp["dtype"]))
        interp.invoke()
        probs = interp.get_tensor(out["index"])[0]
        print("Test image probabilities:")
        for label, p in zip(LABELS, probs):
            print(f"  {label:9s} {p:.3f}")
        print(f"  NSFW (hentai+porn+sexy) = {probs[1] + probs[3] + probs[4]:.3f}")
        print("Sanity check: a neutral photo should score 'neutral' highest.")
    else:
        print("Tip: pass a test image as the 3rd arg to verify the outputs look right.")


if __name__ == "__main__":
    main()
