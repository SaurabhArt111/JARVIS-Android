#!/usr/bin/env python3
"""
Record a golden fixture from the REFERENCE openWakeWord implementation (python, onnxruntime).

What this is for
----------------
The Kotlin class `com.jarvis.core.wake.OpenWakeWordPipeline` re-implements openWakeWord's streaming
feature extraction (AudioFeatures._streaming_features) and prediction logic (Model.predict) so that it can
be unit-tested on the JVM with the neural networks swapped for a `NeuralRunner`.

This script runs the reference implementation on seeded synthetic audio and records, in order, EVERY call it
makes to the three ONNX models (melspectrogram, embedding, wake-word): the input tensor and the output tensor.
The Kotlin test `OpenWakeWordGoldenTest` then feeds the same audio to the Kotlin pipeline with a replay runner
that (a) checks every input the pipeline builds equals the recorded input (tolerance 1e-4) and (b) answers with
the recorded output, and finally compares the per-feed scores.

What this does and does NOT prove
---------------------------------
Proves : the windowing, buffering, index arithmetic, remainder handling, mel transform (x/10+2), stride-8 / 76-frame
         embedding windows, 16-embedding wake window, "first 5 predictions are zero" and multi-chunk max logic
         of the Kotlin port produce the same neural inputs as the reference.
Does NOT prove: ONNX Runtime numerics on Android, detection accuracy for real speech (the audio is synthetic and
         contains no spoken wake word), or latency/battery.

Usage:  python3 -I make_golden.py [--models DIR] [--out FILE]
Needs : numpy, onnxruntime, and the reference package `openwakeword` (pip install --no-deps openwakeword==0.6.0;
        plus tqdm and requests, which its import pulls in). Model files (not in the repo, CC BY-NC-SA 4.0):
        melspectrogram.onnx, embedding_model.onnx, hey_jarvis_v0.1.onnx from
        https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1

Fixture format (gzip, big-endian, readable with java.io.DataInputStream):
  "OWW1" | int scenarios | per scenario:
    UTF name | int warmupLen | short[warmupLen] | int feeds | per feed: int len, short[len], float score, int callsAfter
    | int calls | per call: byte kind(0 mel,1 embedding,2 wake) | int nIn | float[nIn] | int nOut | float[nOut]
"""
import argparse
import gzip
import os
import struct
import sys

import numpy as np
import onnxruntime as ort

KIND = {"input": 0, "input_1": 1, "x.1": 2}


def synth_audio(seed: int, seconds: float) -> np.ndarray:
    """Seeded noise + amplitude-modulated tones (speech-ish energy, but definitely not a spoken wake word)."""
    rng = np.random.default_rng(seed)
    n = int(16000 * seconds)
    t = np.arange(n) / 16000.0
    sig = rng.normal(0, 300, n)
    for f0, f1, depth in ((180, 320, 0.8), (700, 1200, 0.6), (2300, 3100, 0.3)):
        phase = 2 * np.pi * np.cumsum(np.linspace(f0, f1, n)) / 16000.0
        env = 0.5 * (1 + np.sin(2 * np.pi * (1.7 + f0 / 1000.0) * t)) * depth
        sig += 3500 * env * np.sin(phase)
    return np.clip(sig, -32768, 32767).astype(np.int16)


def record_scenario(models_dir: str, name: str, audio: np.ndarray, feed_sizes):
    calls = []
    orig_run = ort.InferenceSession.run

    def run(self, output_names, input_feed, run_options=None):
        out = orig_run(self, output_names, input_feed, run_options)
        key = list(input_feed.keys())[0]
        calls.append((KIND[key], np.asarray(input_feed[key], dtype=np.float32).ravel().copy(),
                      np.asarray(out[0], dtype=np.float32).ravel().copy()))
        return out

    ort.InferenceSession.run = run
    try:
        from openwakeword.model import Model
        np.random.seed(12345)  # the reference warms its feature buffer with np.random noise at construction
        model = Model(
            wakeword_models=[os.path.join(models_dir, "hey_jarvis_v0.1.onnx")],
            inference_framework="onnx",
            melspec_model_path=os.path.join(models_dir, "melspectrogram.onnx"),
            embedding_model_path=os.path.join(models_dir, "embedding_model.onnx"),
        )
        # the very first recorded call is the melspectrogram of the warm-up noise: that is the warm-up audio
        assert calls and calls[0][0] == 0, "expected the warm-up melspectrogram call first"
        warmup = calls[0][1].astype(np.int16)
        assert np.array_equal(warmup.astype(np.float32), calls[0][1]), "warm-up audio must be integral"

        feeds = []
        pos = 0
        for size in feed_sizes:
            if pos + size > len(audio):
                break
            chunk = audio[pos:pos + size]
            pos += size
            scores = model.predict(chunk)
            score = float(next(iter(scores.values())))
            feeds.append((chunk, score, len(calls)))
        return warmup, feeds, calls
    finally:
        ort.InferenceSession.run = orig_run


def write_fixture(path, scenarios):
    with gzip.open(path, "wb", compresslevel=9) as f:
        f.write(b"OWW1")
        f.write(struct.pack(">i", len(scenarios)))
        for name, warmup, feeds, calls in scenarios:
            nb = name.encode("utf-8")
            f.write(struct.pack(">H", len(nb)) + nb)
            f.write(struct.pack(">i", len(warmup)))
            f.write(warmup.astype(">i2").tobytes())
            f.write(struct.pack(">i", len(feeds)))
            for chunk, score, calls_after in feeds:
                f.write(struct.pack(">i", len(chunk)))
                f.write(chunk.astype(">i2").tobytes())
                f.write(struct.pack(">f", score))
                f.write(struct.pack(">i", calls_after))
            f.write(struct.pack(">i", len(calls)))
            for kind, x, y in calls:
                f.write(struct.pack(">b", kind))
                f.write(struct.pack(">i", len(x)))
                f.write(x.astype(">f4").tobytes())
                f.write(struct.pack(">i", len(y)))
                f.write(y.astype(">f4").tobytes())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="./oww-models")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                   "..", "..", "core", "src", "test", "resources", "wake",
                                                   "oww_golden.bin.gz"))
    args = ap.parse_args()

    # Scenario A: 40 aligned chunks of 1280 samples (the normal Android path: 80 ms reads).
    audio_a = synth_audio(20260101, 3.2)
    # Scenario B: irregular read sizes: exercises remainders, sub-chunk reads and multi-chunk (max) predictions.
    audio_b = synth_audio(77, 2.6)
    sizes_b = [960, 1600, 2560, 400, 3840, 1280, 700, 1000, 2000, 5120, 100, 1180, 640, 6400, 333, 1947, 4096, 2560]
    scen = []
    for name, audio, sizes in (("aligned", audio_a, [1280] * 40), ("irregular", audio_b, sizes_b)):
        warmup, feeds, calls = record_scenario(args.models, name, audio, sizes)
        scen.append((name, warmup, feeds, calls))
        scores = [s for _, s, _ in feeds]
        print(f"{name}: {len(feeds)} feeds, {len(calls)} neural calls, max score {max(scores):.6f}")
    out = os.path.normpath(args.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    write_fixture(out, scen)
    print("wrote", out, os.path.getsize(out), "bytes")


if __name__ == "__main__":
    sys.exit(main())
