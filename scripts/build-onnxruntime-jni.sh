#!/usr/bin/env bash
# Build onnxruntime's Java JNI library (`libonnxruntime4j_jni.so`) for
# arm64-v8a against the `libonnxruntime.so` that
# scripts/build-sherpa-onnx.sh puts in app/src/main/jniLibs (issue #61).
#
# Why: the text embedder uses onnxruntime's Java API from the
# `onnxruntime-android` AAR, but that AAR's JNI library binds to
# `OrtGetApiBase@VERS_1.28.0` while the runtime we ship (sherpa-onnx's,
# built with the QNN provider) only defines `VERS_1.28.2`, so Android's
# linker refuses to load it. Compiling the same tag's JNI sources and
# linking them against our runtime gives a library that binds to it.
# The app's copy replaces the AAR's at packaging (app/build.gradle.kts).
#
# Training, CUDA and TensorRT sources are left out: the app uses none of
# them, and their Java entry points then fail only if called.
#
# Run after scripts/build-sherpa-onnx.sh; takes under a minute.
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
source scripts/env.sh

# Must match `onnxruntime` in gradle/libs.versions.toml (the Java classes).
ORT_TAG=v1.28.0

WORK="$HOME/.local/share/convoscribe-toolchain/onnxruntime-jni-build"
SRC="$WORK/onnxruntime-$ORT_TAG"
OUT="app/src/main/jniLibs/arm64-v8a"
API=29

log() {
  echo "[build-onnxruntime-jni] $*"
}

if [[ ! -f "$OUT/libonnxruntime.so" ]]; then
  echo "ERROR: $OUT/libonnxruntime.so is missing - run bash scripts/build-sherpa-onnx.sh first." >&2
  exit 1
fi

if [[ ! -d "$SRC/java/src/main/native" ]]; then
  log "Fetching onnxruntime $ORT_TAG JNI sources and headers ..."
  mkdir -p "$WORK"
  rm -rf "$SRC"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "$ORT_TAG" --filter=blob:none --sparse \
    https://github.com/microsoft/onnxruntime.git "$SRC"
  git -C "$SRC" sparse-checkout set java/src/main/native java/src/main/java java/src/main/jvm include/onnxruntime/core/session include/onnxruntime/core/providers
fi

# CMake normally generates this; the JNI sources only read ORT_VERSION
# from it (HAS_REALLOCARRAY left undefined selects the portable path).
CONFIG_DIR="$WORK/config"
mkdir -p "$CONFIG_DIR"
printf '#pragma once\n#define ORT_VERSION "%s"\n' "${ORT_TAG#v}" > "$CONFIG_DIR/onnxruntime_config.h"

# The JNI headers (prototypes and constants) come from the Java sources,
# as onnxruntime's own build makes them with `javac -h`.
HEADERS="$WORK/jni-headers"
rm -rf "$HEADERS"
log "Generating JNI headers ..."
# shellcheck disable=SC2046
"$JAVA_HOME/bin/javac" -nowarn -h "$HEADERS" -d "$HEADERS/classes" \
  $(find "$SRC/java/src/main/java" "$SRC/java/src/main/jvm" -name '*.java')

CC="$(ls "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android$API-clang)"
SOURCES=()
for file in "$SRC"/java/src/main/native/*.c; do
  case "$(basename "$file")" in
    *Training* | *CUDA* | *TensorRT*) ;;
    *) SOURCES+=("$file") ;;
  esac
done

log "Compiling ${#SOURCES[@]} sources with $(basename "$CC") ..."
"$CC" -shared -fPIC -O2 -std=c11 \
  -I "$SRC/include" -I "$SRC/include/onnxruntime/core/session" -I "$SRC/java/src/main/native" -I "$CONFIG_DIR" -I "$HEADERS" \
  -Wl,-soname,libonnxruntime4j_jni.so -Wl,--no-undefined -Wl,-z,max-page-size=16384 \
  "${SOURCES[@]}" \
  -L "$OUT" -lonnxruntime -llog -landroid \
  -o "$OUT/libonnxruntime4j_jni.so"

READELF="$(ls "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf)"
if "$READELF" --dyn-syms "$OUT/libonnxruntime4j_jni.so" | grep -q "OrtGetApiBase@VERS_1.28.0"; then
  echo "ERROR: libonnxruntime4j_jni.so still binds to VERS_1.28.0." >&2
  exit 1
fi
log "Wrote $OUT/libonnxruntime4j_jni.so"
