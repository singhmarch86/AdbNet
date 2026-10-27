# Converting the GantMan NSFW model to TFLite

The in-app safe browser needs `androidApp/src/main/assets/nsfw.tflite`. This
converts the open-source **GantMan `nsfw_model`** to the format the app expects.

> Licence note: the model is third-party. Review GantMan/nsfw_model's licence
> and its training-data provenance before shipping it in a product.

## 1. Get the model

Download the **MobileNet 224** model from the releases page:
<https://github.com/GantMan/nsfw_model/releases>

Grab the asset **`nsfw_mobilenet2.224x224.h5`** (the 224-input MobileNetV2).
A SavedModel directory works too — the script accepts either.

## 2. Set up Python (Keras 2 era — important)

Old models load cleanly on TensorFlow 2.15 (Keras 2). TF 2.16+ defaults to
Keras 3 and may fail to load them.

```bash
cd mobile-shield/tools
python3.11 -m venv .venv && source .venv/bin/activate
pip install --upgrade pip
pip install "tensorflow==2.15.1" pillow
#   Apple Silicon: if the above can't find a wheel, try:
#   pip install "tensorflow-macos==2.15.0" pillow
```

## 3. Convert (and verify)

```bash
python convert_nsfw_to_tflite.py nsfw_mobilenet2.224x224.h5 nsfw.tflite some_neutral_photo.jpg
```

Check the printed output:
- Input should be `[1 224 224 3]` float32, output `[1 5]`.
- For a normal (SFW) photo, `neutral` should be the highest probability and the
  `NSFW` total low. If a clearly explicit test image scores high NSFW and a
  normal one scores low, the model + preprocessing are aligned with the app.

If the input is not float `0..1` (e.g. the model bakes in its own rescaling, or
uses a different size/class count), the script warns — tell me the printed
shapes/dtype and I'll adjust `TfliteNsfwClassifier` to match.

## 4. Install the model in the app

```bash
cp nsfw.tflite ../androidApp/src/main/assets/nsfw.tflite
cd ..
./gradlew :androidApp:assembleDebug -Pmobileshield.android=true --no-daemon
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

The Browser tab's "no model installed" banner disappears and images are
classified live. (`assets/nsfw.tflite` is a binary blob — consider Git LFS or
keeping it out of version control depending on size/licence.)
