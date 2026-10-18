#!/usr/bin/env bash
# Build the debug APK, copy it to a versioned path under dist/, and
# print enough metadata for sideloading (path, size, sha256, adb
# install command). This wrapper runs the full verify command
# (ktlintCheck + testDebugUnitTest + lintDebug + assembleDebug) so a
# single bash scripts/build-apk.sh gives you a known-good APK.
#
# Usage: bash scripts/build-apk.sh [--install]
#   --install  after a successful build, `adb install -r` the APK on the
#              connected device (set ANDROID_SERIAL to pick one of several).

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: bash scripts/build-apk.sh [--install]

Build, verify and copy the debug APK to dist/.

Options:
  --install   install the APK on the connected device once the build
              succeeds (set ANDROID_SERIAL to choose among several)
  -h, --help  show this help
USAGE
}

# Parse options before building so a typo fails in seconds, not after
# a multi-minute Gradle run.
install=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --install) install=true ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "build-apk: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

# Resolve the repo root from this script's location so the wrapper
# works regardless of the caller's cwd.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# Source the toolchain env. We do not `set -e` inside env.sh (it is
# meant to be sourced), and we accept its side effects unconditionally.
# shellcheck disable=SC1091
source "$SCRIPT_DIR/env.sh"

# The Gradle check in app/build.gradle.kts only tests that the sherpa-onnx
# outputs exist, so empty placeholders (enough for unit tests) still yield
# an APK that crashes on first transcription. Require the real files here.
SHERPA_FILES=(
  app/libs/sherpa-onnx-classes-1.13.8.jar
  app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so
  app/src/main/jniLibs/arm64-v8a/libonnxruntime.so
  app/src/main/jniLibs/arm64-v8a/libQnnHtp.so
)
missing=()
for f in "${SHERPA_FILES[@]}"; do
  [[ -s "$f" ]] || missing+=("$f")
done
if [[ ${#missing[@]} -gt 0 ]]; then
  echo "build-apk: sherpa-onnx native build is missing or empty:" >&2
  printf '    %s\n' "${missing[@]}" >&2
  echo "Run: bash scripts/build-sherpa-onnx.sh" >&2
  exit 1
fi

if [[ "$install" == true ]] && ! command -v adb >/dev/null 2>&1; then
  echo "build-apk: --install needs adb, which is not on PATH (expected in \$ANDROID_HOME/platform-tools)." >&2
  exit 1
fi

# Stop any daemons left over from earlier runs so the verify command
# starts from a clean slate.
./gradlew --stop >/dev/null 2>&1 || true

# Full verify command. assembleDebug rebuilds app-debug.apk; the rest
# is the gate every task must pass.
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug

APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
METADATA="app/build/outputs/apk/debug/output-metadata.json"

if [[ ! -f "$APK_SRC" ]]; then
  echo "build-apk: expected APK at $APK_SRC but it is not there." >&2
  exit 1
fi

if [[ ! -f "$METADATA" ]]; then
  echo "build-apk: expected $METADATA but it is not there." >&2
  exit 1
fi

# Extract versionName without taking a jq dependency.
version_name=$(grep -oP '"versionName"\s*:\s*"\K[^"]+' "$METADATA" || true)
if [[ -z "${version_name:-}" ]]; then
  echo "build-apk: could not read versionName out of $METADATA." >&2
  exit 1
fi

DIST_DIR="$REPO_ROOT/dist"
APK_DEST="$DIST_DIR/convoscribe-${version_name}-debug.apk"

mkdir -p "$DIST_DIR"

# Overwrite any previous copy at the same versioned path.
cp -f "$APK_SRC" "$APK_DEST"

size_bytes=$(stat -c '%s' "$APK_DEST")

if command -v numfmt >/dev/null 2>&1; then
  size_human=$(numfmt --to=iec "$size_bytes")
else
  # awk fallback: report as e.g. 97M, 1.2G, 532K.
  size_human=$(awk -v b="$size_bytes" 'BEGIN {
    split("K M G T", u, " ");
    n = b;
    i = 0;
    while (n >= 1024 && i < 3) {
      n = n / 1024;
      i++;
    }
    if (i == 0) {
      printf "%dB\n", n;
    } else {
      printf "%.1f%s\n", n, u[i];
    }
  }')
fi

sha256=$(sha256sum "$APK_DEST" | awk '{ print $1 }')

cat <<EOF
APK: $APK_DEST
Size: $size_bytes ($size_human)
sha256: $sha256
EOF

if [[ "$install" != true ]]; then
  cat <<EOF

Install on a connected device with:
    adb install -r $APK_DEST
EOF
fi

# Always stop daemons so the next invocation does not pay a stop-the-
# world penalty on the first Gradle call.
./gradlew --stop >/dev/null 2>&1 || true

if [[ "$install" == true ]]; then
  echo
  # `adb get-state` prints "device" only when exactly one device (or
  # the one named by ANDROID_SERIAL) is connected and authorised.
  if ! state=$(adb get-state 2>&1) || [[ "$state" != "device" ]]; then
    echo "build-apk: no device ready to install on (adb: $state)." >&2
    echo "Connect and authorise a device, or set ANDROID_SERIAL to pick one of several." >&2
    exit 1
  fi
  echo "Installing on ${ANDROID_SERIAL:-the connected device} ..."
  if ! install_out=$(adb install -r "$APK_DEST" 2>&1); then
    echo "$install_out" >&2
    if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$install_out"; then
      # Not done automatically: uninstalling wipes the app's data.
      echo >&2
      echo "The installed app is signed with a different key. Uninstall it once (this wipes its data), then re-run:" >&2
      echo "    adb uninstall com.convoscribe" >&2
    fi
    exit 1
  fi
  echo "$install_out"
fi