#!/usr/bin/env bash
# Build sherpa-onnx 1.13.8 native libraries for arm64-v8a with Qualcomm QNN
# (Hexagon NPU) and speaker diarization enabled, then copy the resulting
# `libsherpa-onnx-jni.so` together with `libonnxruntime.so` and the
# Qualcomm QNN 2.40 HTP runtime shared libraries into
# `app/src/main/jniLibs/arm64-v8a/`. Extract the Kotlin API classes
# from the official 1.13.8 AAR into `app/libs/sherpa-onnx-classes-1.13.8.jar`.
#
# Idempotent: a second run with all expected outputs in place exits 0
# immediately. Pass `--force` to wipe the work directory and rebuild.
#
# Run with: `bash scripts/build-sherpa-onnx.sh` (after `source scripts/env.sh`).
# The build itself takes 10-30 minutes; the onnxruntime download is a
# prebuilt release from https://github.com/csukuangfj/onnxruntime-libs.

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
source scripts/env.sh

VERSION=1.13.8
QNN_VERSION=2.40.0.251030
ONNXRUNTIME_VERSION=1.28.2

WORK="$HOME/.local/share/convoscribe-toolchain/sherpa-onnx-build"
OUT="app/src/main/jniLibs/arm64-v8a"
JAR="app/libs/sherpa-onnx-classes-$VERSION.jar"

SHERPA_DIR="$WORK/sherpa-onnx"
QNN_INCLUDE_DIR="$WORK/qnn-include-$QNN_VERSION"
QNN_LIBS_DIR="$WORK/qnn-libs-$QNN_VERSION"
AAR_FILE="$WORK/sherpa-onnx-$VERSION.aar"
AAR_SHA256="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

FORCE=0
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    -h|--help)
      echo "Usage: bash scripts/build-sherpa-onnx.sh [--force]"
      exit 0
      ;;
  esac
done

log() {
  echo "[build-sherpa-onnx] $*"
}

# sherpa-onnx's build-android-arm64-v8a.sh calls `wget` to fetch a prebuilt
# onnxruntime. This container has no wget, so put our shim first on PATH
# (scripts/wget -> curl, provided by the repo's tooling).
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ -x "$SCRIPT_DIR/wget" ]]; then
  PATH="$SCRIPT_DIR:$PATH"
  export PATH
fi

if [[ "$FORCE" -eq 1 ]]; then
  log "--force given: wiping $WORK"
  rm -rf "$WORK"
  rm -rf "$OUT"
  rm -f "$JAR"
fi

# Skip the whole pipeline if every expected artifact is already in place.
if [[ -s "$OUT/libsherpa-onnx-jni.so" \
   && -s "$OUT/libQnnHtp.so" \
   && -s "$JAR" ]]; then
  log "sherpa-onnx native libs OK"
  exit 0
fi

download() {
  # download <url> <output>  -- fail loudly on HTTP errors and follow redirects.
  local url="$1" out="$2"
  curl --fail --location --silent --show-error --retry 3 --retry-delay 5 -o "$out" "$url"
}

# ---------------------------------------------------------------------------
# 1. Source: clone sherpa-onnx v$VERSION (shallow) into $WORK/sherpa-onnx.
# ---------------------------------------------------------------------------
if [[ ! -d "$SHERPA_DIR/.git" ]]; then
  log "Cloning sherpa-onnx v$VERSION into $SHERPA_DIR ..."
  mkdir -p "$WORK"
  rm -rf "$SHERPA_DIR"
  git clone --depth 1 --branch "v$VERSION" \
    https://github.com/k2-fsa/sherpa-onnx "$SHERPA_DIR"
else
  log "Reusing existing sherpa-onnx source at $SHERPA_DIR"
fi

# ---------------------------------------------------------------------------
# 2. QNN headers and runtime libraries (Qualcomm AI Engine Direct, 2.40.0.251030).
# ---------------------------------------------------------------------------
if [[ ! -f "$QNN_INCLUDE_DIR/include/QNN/QnnInterface.h" ]]; then
  log "Downloading QNN headers ($QNN_VERSION) ..."
  mkdir -p "$WORK"
  download \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/qnn-include-$QNN_VERSION.tar.bz2" \
    "$WORK/qnn-include-$QNN_VERSION.tar.bz2"
  # The tarball has a top-level qnn-include-<ver>/ directory; extract into
  # $WORK so QNN_INCLUDE_DIR ends up at $WORK/qnn-include-<ver>/include/...
  rm -rf "$QNN_INCLUDE_DIR"
  tar -xjf "$WORK/qnn-include-$QNN_VERSION.tar.bz2" -C "$WORK"
  rm -f "$WORK/qnn-include-$QNN_VERSION.tar.bz2"
else
  log "Reusing existing QNN headers at $QNN_INCLUDE_DIR"
fi

if [[ ! -f "$QNN_LIBS_DIR/libQnnHtp.so" ]]; then
  log "Downloading QNN runtime libraries ($QNN_VERSION) ..."
  download \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/qnn-libs-$QNN_VERSION.tar.bz2" \
    "$WORK/qnn-libs-$QNN_VERSION.tar.bz2"
  rm -rf "$QNN_LIBS_DIR"
  tar -xjf "$WORK/qnn-libs-$QNN_VERSION.tar.bz2" -C "$WORK"
  rm -f "$WORK/qnn-libs-$QNN_VERSION.tar.bz2"
else
  log "Reusing existing QNN runtime libraries at $QNN_LIBS_DIR"
fi

# ---------------------------------------------------------------------------
# 3. Configure and compile sherpa-onnx with QNN + diarization ON.
#    Mirrors sherpa-onnx's own scripts/apk/build-apk-qnn-asr.sh.in,
#    but with SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON. The prebuilt
#    onnxruntime is fetched automatically by build-android-arm64-v8a.sh.
# ---------------------------------------------------------------------------
log "Configuring sherpa-onnx for arm64-v8a (QNN + diarization ON) ..."
log "  QNN_SDK_ROOT                          = $QNN_INCLUDE_DIR"
log "  ANDROID_NDK                           = $ANDROID_NDK"
log "  SHERPA_ONNX_ENABLE_QNN                = ON"
log "  SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION= ON"
log "  SHERPA_ONNX_ENABLE_TTS                = OFF"
log "  SHERPA_ONNX_ENABLE_BINARY             = OFF"
log "  SHERPA_ONNX_ENABLE_C_API              = OFF"
log "  SHERPA_ONNX_ENABLE_JNI                = ON"
log "  BUILD_SHARED_LIBS                     = ON"
log "Building (this can take 10-30 minutes) ..."

pushd "$SHERPA_DIR" >/dev/null
env \
  QNN_SDK_ROOT="$QNN_INCLUDE_DIR" \
  ANDROID_NDK="$ANDROID_NDK" \
  SHERPA_ONNX_ENABLE_QNN=ON \
  SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON \
  SHERPA_ONNX_ENABLE_TTS=OFF \
  SHERPA_ONNX_ENABLE_BINARY=OFF \
  SHERPA_ONNX_ENABLE_C_API=OFF \
  SHERPA_ONNX_ENABLE_JNI=ON \
  BUILD_SHARED_LIBS=ON \
  SHERPA_ONNX_ONNXRUNTIME_VERSION="$ONNXRUNTIME_VERSION" \
  ./build-android-arm64-v8a.sh
popd >/dev/null

# ---------------------------------------------------------------------------
# 4. Copy built libraries (sherpa-onnx + onnxruntime) and QNN runtime libs
#    into app/src/main/jniLibs/arm64-v8a/.
# ---------------------------------------------------------------------------
log "Copying libraries into $OUT ..."
mkdir -p "$OUT"
rm -f "$OUT"/*.so

cp "$SHERPA_DIR/build-android-arm64-v8a/install/lib/"*.so "$OUT/"
cp "$QNN_LIBS_DIR/"*.so "$OUT/"

# ---------------------------------------------------------------------------
# 5. Verify the build produced what we need.
# ---------------------------------------------------------------------------
if [[ ! -f "$OUT/libsherpa-onnx-jni.so" ]]; then
  echo "ERROR: $OUT/libsherpa-onnx-jni.so is missing after build." >&2
  exit 1
fi
if [[ ! -f "$OUT/libonnxruntime.so" ]]; then
  echo "ERROR: $OUT/libonnxruntime.so is missing after build." >&2
  exit 1
fi
if ! grep -q "Java_com_k2fsa_sherpa_onnx_OfflineSpeakerDiarization" \
     <(strings "$OUT/libsherpa-onnx-jni.so" 2>/dev/null); then
  echo "ERROR: libsherpa-onnx-jni.so does not export OfflineSpeakerDiarization JNI symbols." >&2
  echo "       Build was probably done with SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF." >&2
  exit 1
fi
if ! grep -q "prependAdspLibraryPath" \
     <(strings "$OUT/libsherpa-onnx-jni.so" 2>/dev/null); then
  echo "ERROR: libsherpa-onnx-jni.so does not reference prependAdspLibraryPath." >&2
  echo "       Build was probably done without SHERPA_ONNX_ENABLE_QNN=ON." >&2
  exit 1
fi
log "Native libraries look good."

# ---------------------------------------------------------------------------
# 6. Kotlin API classes: download official AAR, verify its hash, extract
#    classes.jar -> app/libs/sherpa-onnx-classes-$VERSION.jar. We never
#    use the AAR itself (it bundles native libs that have no NPU and
#    would clash with the ones we just compiled).
# ---------------------------------------------------------------------------
log "Downloading sherpa-onnx v$VERSION AAR for the Kotlin API ..."
if [[ ! -f "$AAR_FILE" ]]; then
  download \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-$VERSION.aar" \
    "$AAR_FILE"
fi

actual_sha=$(sha256sum "$AAR_FILE" | awk '{print $1}')
if [[ "$actual_sha" != "$AAR_SHA256" ]]; then
  echo "ERROR: AAR sha256 mismatch." >&2
  echo "  expected: $AAR_SHA256" >&2
  echo "  actual  : $actual_sha" >&2
  rm -f "$AAR_FILE"
  exit 1
fi
log "AAR sha256 OK."

mkdir -p "$(dirname "$JAR")"
rm -f "$JAR"
unzip -p "$AAR_FILE" classes.jar > "$JAR"
if [[ ! -s "$JAR" ]]; then
  echo "ERROR: $JAR is empty after extraction." >&2
  exit 1
fi
log "Wrote $JAR."

# ---------------------------------------------------------------------------
# 7. Final smoke check: re-run the idempotency guard to make sure a second
#    invocation exits 0 immediately.
# ---------------------------------------------------------------------------
if [[ -f "$OUT/libsherpa-onnx-jni.so" \
   && -f "$OUT/libQnnHtp.so" \
   && -f "$JAR" ]]; then
  log "sherpa-onnx native libs OK"
  log "Build done. jniLibs: $OUT    classes jar: $JAR"
else
  echo "ERROR: post-build verification failed." >&2
  exit 1
fi
