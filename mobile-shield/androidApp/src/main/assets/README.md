# NSFW model for the in-app safe browser

The in-app browser (Browser tab) filters images pixel-by-pixel using an
on-device TensorFlow Lite classifier. The model is **not bundled** — sourcing a
model and accepting its licence is a deployment decision.

To enable image content filtering, drop a compatible model here as:

    androidApp/src/main/assets/nsfw.tflite

## Expected model format

`TfliteNsfwClassifier` expects the common **GantMan `nsfw_model`** layout:

- Input: `1 x 224 x 224 x 3` float, RGB, normalized to `0..1`.
- Output: `1 x 5` softmax over `[drawings, hentai, neutral, porn, sexy]`.
- NSFW score = `hentai + porn + sexy`; images scoring ≥ 0.7 are blocked.

A model with a different input size / class layout needs matching tweaks in
`TfliteNsfwClassifier` (SIZE, NUM_CLASSES, and the score formula).

## Without a model

If `nsfw.tflite` is absent, the browser still works but shows a banner and does
**no image filtering** — the DNS family filter (Protect tab) still blocks whole
adult/malware domains. This keeps the app honest about what is and isn't active.
