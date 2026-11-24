#!/usr/bin/env bash
# Replace the onnxruntime that scripts/build-sherpa-onnx.sh puts in
# app/src/main/jniLibs/arm64-v8a with Microsoft's onnxruntime-android-qnn
# build of the same version, and put its Java JNI library
# (`libonnxruntime4j_jni.so`) next to it (issues #61, #159).
#
# Why: the text embedder (#61) and Whisper on the NPU (#159) use
# onnxruntime's Java API, and Whisper needs the QNN execution provider.
# The runtime sherpa-onnx is built against (csukuangfj/onnxruntime-libs)
# has none: sherpa-onnx's own NPU models (Nemotron) call the QNN SDK
# directly, so they never needed it. onnxruntime-android-qnn does have it.
# Both runtimes export the same symbol version (`OrtGetApiBase@VERS_<
# version>`), so sherpa-onnx's JNI library loads against the swapped one;
# Android's linker refuses any other version. The app's copies replace
# the onnxruntime-android AAR's at packaging (app/build.gradle.kts).
#
# The QNN libraries (libQnnHtp.so and the stubs/skels) come from the QAIRT
# SDK pinned in scripts/build-sherpa-onnx.sh, not from Maven's
# qnn-runtime package the AAR declares.
#
# Run after scripts/build-sherpa-onnx.sh; takes under a minute.
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
source scripts/env.sh

# Must match ONNXRUNTIME_VERSION in scripts/build-sherpa-onnx.sh and
# `onnxruntime` in gradle/libs.versions.toml (the Java classes).
ORT_VERSION=1.29.0
ORT_AAR_URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/$ORT_VERSION/onnxruntime-android-qnn-$ORT_VERSION.aar"
ORT_AAR_SHA256="cc4cfd72a47703aee65d8e6ce7448fa7488e3fff6556f9d6d354ec1687347cfd"

WORK="$HOME/.local/share/convoscribe-toolchain/onnxruntime-jni-build"
AAR="$WORK/onnxruntime-android-qnn-$ORT_VERSION.aar"
OUT="app/src/main/jniLibs/arm64-v8a"

log() {
  echo "[build-onnxruntime-jni] $*"
}

if [[ ! -f "$OUT/libsherpa-onnx-jni.so" ]]; then
  echo "ERROR: $OUT/libsherpa-onnx-jni.so is missing - run bash scripts/build-sherpa-onnx.sh first." >&2
  exit 1
fi

mkdir -p "$WORK"
if [[ ! -f "$AAR" ]]; then
  log "Downloading onnxruntime-android-qnn $ORT_VERSION ..."
  curl --fail --location --silent --show-error --retry 3 --retry-delay 5 -o "$AAR.part" "$ORT_AAR_URL"
  mv "$AAR.part" "$AAR"
fi
actual_sha=$(sha256sum "$AAR" | awk '{print $1}')
if [[ "$actual_sha" != "$ORT_AAR_SHA256" ]]; then
  echo "ERROR: onnxruntime-android-qnn-$ORT_VERSION.aar sha256 mismatch." >&2
  echo "  expected: $ORT_AAR_SHA256" >&2
  echo "  actual  : $actual_sha" >&2
  rm -f "$AAR"
  exit 1
fi

EXTRACT="$WORK/extract-$ORT_VERSION"
rm -rf "$EXTRACT"
unzip -q -o "$AAR" "jni/arm64-v8a/*" -d "$EXTRACT"
cp "$EXTRACT/jni/arm64-v8a/libonnxruntime.so" "$OUT/libonnxruntime.so"
cp "$EXTRACT/jni/arm64-v8a/libonnxruntime4j_jni.so" "$OUT/libonnxruntime4j_jni.so"
rm -rf "$EXTRACT"

READELF="$(ls "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf)"
# Capture first: with pipefail, `grep -q` closing the pipe early would
# fail the pipeline through readelf's SIGPIPE.
for lib in libonnxruntime4j_jni.so libsherpa-onnx-jni.so; do
  syms="$("$READELF" --dyn-syms "$OUT/$lib")"
  if ! grep -q "OrtGetApiBase@VERS_$ORT_VERSION" <<<"$syms"; then
    echo "ERROR: $lib does not bind to onnxruntime VERS_$ORT_VERSION." >&2
    exit 1
  fi
done
# Every onnxruntime build names "QNNExecutionProvider"; only one with the
# provider compiled in has its backend manager and `backend_path` option.
ort_strings="$(strings "$OUT/libonnxruntime.so")"
if ! grep -q "QnnBackendManager" <<<"$ort_strings" || ! grep -qx "backend_path" <<<"$ort_strings"; then
  echo "ERROR: $OUT/libonnxruntime.so has no QNN execution provider (needed by Whisper on the NPU)." >&2
  exit 1
fi
log "Wrote $OUT/libonnxruntime.so and libonnxruntime4j_jni.so (onnxruntime-android-qnn $ORT_VERSION)"
