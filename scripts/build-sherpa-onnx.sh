#!/usr/bin/env bash
# Build sherpa-onnx 1.13.8 native libraries for arm64-v8a with Qualcomm QNN
# (Hexagon NPU) and speaker diarization enabled, then copy the resulting
# `libsherpa-onnx-jni.so` together with `libonnxruntime.so` (1.29.0) and
# the Qualcomm QAIRT 2.51 HTP runtime shared libraries into
# `app/src/main/jniLibs/arm64-v8a/`. Extract the Kotlin API classes
# from the official 1.13.8 AAR into `app/libs/sherpa-onnx-classes-1.13.8.jar`.
#
# Idempotent: a second run with all expected outputs in place exits 0
# immediately. Pass `--force` to wipe the work directory and rebuild.
#
# Run with: `bash scripts/build-sherpa-onnx.sh` (after `source scripts/env.sh`).
# The build itself takes 10-30 minutes; the onnxruntime download is a
# prebuilt release from https://github.com/csukuangfj/onnxruntime-libs.
#
# That runtime has no QNN execution provider (sherpa-onnx's own QNN
# models call the QNN SDK directly), so scripts/build-onnxruntime-jni.sh
# then swaps it for Microsoft's onnxruntime-android-qnn build of the same
# version, which Whisper on the NPU needs (issue #159). sherpa-onnx's JNI
# library only binds to the version (`OrtGetApiBase@VERS_<version>`).

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
source scripts/env.sh

VERSION=1.13.8
# QAIRT 2.51 loads the precompiled Whisper builds Qualcomm publishes
# (issue #159) and still runs Nemotron's QNN model libraries.
QNN_VERSION=2.51.0.260929
QNN_SDK_URL="https://apigwx-aws.qualcomm.com/qsc/public/v1/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/$QNN_VERSION/v$QNN_VERSION.zip"
QNN_SDK_SHA256="9b4ca798902f02a2543857f5de4d233830c044d19701b92fc66a12fdc292211f"
# HTP architectures whose stub/skel pair is bundled: v68 (888) to v85
# (8 Elite Gen 6).
HTP_ARCHS=(68 69 73 75 79 81 85)
# Must match ORT_VERSION in scripts/build-onnxruntime-jni.sh: 1.29.0 is the
# newest onnxruntime-android-qnn release on Maven.
ONNXRUNTIME_VERSION=1.29.0

WORK="$HOME/.local/share/convoscribe-toolchain/sherpa-onnx-build"
OUT="app/src/main/jniLibs/arm64-v8a"
JAR="app/libs/sherpa-onnx-classes-$VERSION.jar"

SHERPA_DIR="$WORK/sherpa-onnx"
QNN_SDK_ZIP="$WORK/qairt-$QNN_VERSION.zip"
# The SDK zip unpacks to qairt/<version>/{include,lib}; QNN_SDK_ROOT
# points there.
QNN_SDK_ROOT_DIR="$WORK/qairt/$QNN_VERSION"
# Records which runtime the libraries in $OUT came from, so a tree built
# with an older QNN or onnxruntime is rebuilt instead of reused.
STAMP="$OUT/.runtime-versions"
STAMP_VALUE="sherpa-onnx=$VERSION qnn=$QNN_VERSION onnxruntime=$ONNXRUNTIME_VERSION"
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
   && -s "$JAR" \
   && "$(cat "$STAMP" 2>/dev/null)" == "$STAMP_VALUE" ]]; then
  log "sherpa-onnx native libs OK ($STAMP_VALUE)"
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
# 2. QNN headers and runtime libraries from the Qualcomm AI Runtime (QAIRT)
#    SDK $QNN_VERSION, a public 3 GB download. Only the headers and the
#    Android/Hexagon HTP libraries are extracted; the zip is then deleted.
# ---------------------------------------------------------------------------
if [[ ! -f "$QNN_SDK_ROOT_DIR/include/QNN/QnnInterface.h" \
   || ! -f "$QNN_SDK_ROOT_DIR/lib/hexagon-v85/unsigned/libQnnHtpV85Skel.so" ]]; then
  mkdir -p "$WORK"
  if [[ ! -f "$QNN_SDK_ZIP" ]]; then
    log "Downloading QAIRT SDK $QNN_VERSION (about 3 GB) ..."
    download "$QNN_SDK_URL" "$QNN_SDK_ZIP.part"
    mv "$QNN_SDK_ZIP.part" "$QNN_SDK_ZIP"
  fi
  actual_qnn_sha=$(sha256sum "$QNN_SDK_ZIP" | awk '{print $1}')
  if [[ "$actual_qnn_sha" != "$QNN_SDK_SHA256" ]]; then
    echo "ERROR: QAIRT SDK sha256 mismatch." >&2
    echo "  expected: $QNN_SDK_SHA256" >&2
    echo "  actual  : $actual_qnn_sha" >&2
    rm -f "$QNN_SDK_ZIP"
    exit 1
  fi
  log "Extracting QNN headers and HTP libraries ..."
  rm -rf "$WORK/qairt"
  patterns=(
    "qairt/$QNN_VERSION/include/QNN/*"
    "qairt/$QNN_VERSION/lib/aarch64-android/libQnnHtp.so"
    "qairt/$QNN_VERSION/lib/aarch64-android/libQnnHtpPrepare.so"
    "qairt/$QNN_VERSION/lib/aarch64-android/libQnnSystem.so"
  )
  for arch in "${HTP_ARCHS[@]}"; do
    patterns+=(
      "qairt/$QNN_VERSION/lib/aarch64-android/libQnnHtpV${arch}Stub.so"
      "qairt/$QNN_VERSION/lib/hexagon-v${arch}/unsigned/libQnnHtpV${arch}Skel.so"
    )
  done
  unzip -q -o "$QNN_SDK_ZIP" "${patterns[@]}" -d "$WORK"
  rm -f "$QNN_SDK_ZIP"
else
  log "Reusing existing QAIRT headers and libraries at $QNN_SDK_ROOT_DIR"
fi

# ---------------------------------------------------------------------------
# 3. Configure and compile sherpa-onnx with QNN + diarization ON.
#    Mirrors sherpa-onnx's own scripts/apk/build-apk-qnn-asr.sh.in,
#    but with SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON. The prebuilt
#    onnxruntime is fetched automatically by build-android-arm64-v8a.sh.
# ---------------------------------------------------------------------------
log "Configuring sherpa-onnx for arm64-v8a (QNN + diarization ON) ..."
log "  QNN_SDK_ROOT                          = $QNN_SDK_ROOT_DIR"
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
  QNN_SDK_ROOT="$QNN_SDK_ROOT_DIR" \
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
QNN_LIB="$QNN_SDK_ROOT_DIR/lib"
cp "$QNN_LIB/aarch64-android/"libQnn{Htp,HtpPrepare,System}.so "$OUT/"
for arch in "${HTP_ARCHS[@]}"; do
  cp "$QNN_LIB/aarch64-android/libQnnHtpV${arch}Stub.so" "$OUT/"
  cp "$QNN_LIB/hexagon-v${arch}/unsigned/libQnnHtpV${arch}Skel.so" "$OUT/"
done

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
READELF="$(ls "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf)"
# Capture first: with pipefail, `grep -q` closing the pipe early would
# fail the pipeline through readelf's SIGPIPE.
JNI_SYMS="$("$READELF" --dyn-syms "$OUT/libsherpa-onnx-jni.so")"
if ! grep -q "OrtGetApiBase@VERS_$ONNXRUNTIME_VERSION" <<<"$JNI_SYMS"; then
  echo "ERROR: libsherpa-onnx-jni.so does not bind to onnxruntime VERS_$ONNXRUNTIME_VERSION." >&2
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
echo "$STAMP_VALUE" > "$STAMP"
if [[ -f "$OUT/libsherpa-onnx-jni.so" \
   && -f "$OUT/libQnnHtp.so" \
   && -f "$JAR" ]]; then
  log "sherpa-onnx native libs OK"
  log "Build done. jniLibs: $OUT    classes jar: $JAR"
else
  echo "ERROR: post-build verification failed." >&2
  exit 1
fi
