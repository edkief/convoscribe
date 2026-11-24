#!/usr/bin/env bash
# Put onnxruntime's Java JNI library (`libonnxruntime4j_jni.so`) for
# arm64-v8a next to the `libonnxruntime.so` that
# scripts/build-sherpa-onnx.sh puts in app/src/main/jniLibs (issues #61,
# #159).
#
# Why: the text embedder (#61) and Whisper on the NPU (#159) use
# onnxruntime's Java API from the `onnxruntime-android` AAR, but that
# AAR's runtime has no QNN execution provider, so the app ships
# sherpa-onnx's runtime instead (csukuangfj/onnxruntime-libs, built with
# the QNN provider) and the JNI library has to bind to that runtime's
# symbol version (`OrtGetApiBase@VERS_<version>`), or Android's linker
# refuses to load it. The same onnxruntime-libs release ships a JNI
# library built against that runtime; this script takes it from there.
# The app's copy replaces the AAR's at packaging (app/build.gradle.kts).
#
# Run after scripts/build-sherpa-onnx.sh; takes under a minute.
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
source scripts/env.sh

# Must match ONNXRUNTIME_VERSION in scripts/build-sherpa-onnx.sh and
# `onnxruntime` in gradle/libs.versions.toml (the Java classes).
ORT_VERSION=1.30.0
ORT_ZIP_URL="https://github.com/csukuangfj/onnxruntime-libs/releases/download/v$ORT_VERSION/onnxruntime-android-$ORT_VERSION.zip"
ORT_ZIP_SHA256="150d1fd3356220e7539e17cb4ec81a7f6649218e5d2bab5afbeef6e23daf9641"

WORK="$HOME/.local/share/convoscribe-toolchain/onnxruntime-jni-build"
ZIP="$WORK/onnxruntime-android-$ORT_VERSION.zip"
OUT="app/src/main/jniLibs/arm64-v8a"

log() {
  echo "[build-onnxruntime-jni] $*"
}

if [[ ! -f "$OUT/libonnxruntime.so" ]]; then
  echo "ERROR: $OUT/libonnxruntime.so is missing - run bash scripts/build-sherpa-onnx.sh first." >&2
  exit 1
fi

mkdir -p "$WORK"
if [[ ! -f "$ZIP" ]]; then
  log "Downloading onnxruntime-libs $ORT_VERSION for Android ..."
  curl --fail --location --silent --show-error --retry 3 --retry-delay 5 -o "$ZIP.part" "$ORT_ZIP_URL"
  mv "$ZIP.part" "$ZIP"
fi
actual_sha=$(sha256sum "$ZIP" | awk '{print $1}')
if [[ "$actual_sha" != "$ORT_ZIP_SHA256" ]]; then
  echo "ERROR: onnxruntime-android-$ORT_VERSION.zip sha256 mismatch." >&2
  echo "  expected: $ORT_ZIP_SHA256" >&2
  echo "  actual  : $actual_sha" >&2
  rm -f "$ZIP"
  exit 1
fi

EXTRACT="$WORK/extract-$ORT_VERSION"
rm -rf "$EXTRACT"
unzip -q -o "$ZIP" "jni/arm64-v8a/*" -d "$EXTRACT"

# The JNI library must come from the same build as the shipped runtime.
if ! cmp -s "$EXTRACT/jni/arm64-v8a/libonnxruntime.so" "$OUT/libonnxruntime.so"; then
  echo "ERROR: $OUT/libonnxruntime.so is not the onnxruntime-libs $ORT_VERSION runtime." >&2
  echo "       Rebuild with bash scripts/build-sherpa-onnx.sh (ONNXRUNTIME_VERSION=$ORT_VERSION)." >&2
  exit 1
fi
cp "$EXTRACT/jni/arm64-v8a/libonnxruntime4j_jni.so" "$OUT/libonnxruntime4j_jni.so"
rm -rf "$EXTRACT"

READELF="$(ls "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf)"
# Capture first: with pipefail, `grep -q` closing the pipe early would
# fail the pipeline through readelf's SIGPIPE.
JNI_SYMS="$("$READELF" --dyn-syms "$OUT/libonnxruntime4j_jni.so")"
if ! grep -q "OrtGetApiBase@VERS_$ORT_VERSION" <<<"$JNI_SYMS"; then
  echo "ERROR: libonnxruntime4j_jni.so does not bind to VERS_$ORT_VERSION." >&2
  exit 1
fi
log "Wrote $OUT/libonnxruntime4j_jni.so"
