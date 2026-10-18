#!/usr/bin/env bash
# Idempotent installer for JDK 17, Gradle, and Android command-line tools.
# Nothing here uses sudo or apt; everything installs under $CONVOSCRIBE_TOOLCHAIN
# (default /workspace/toolchain), the same root scripts/env.sh points at.
#
# CONVOSCRIBE_APP_ONLY=1 installs only what building the app needs (JDK,
# Gradle, SDK) and skips CMake, the NDK and the sherpa-onnx build. The
# release builder image uses it and takes sherpa-onnx prebuilt instead
# (see docker/android-builder.Containerfile).

set -euo pipefail

TOOLS="${CONVOSCRIBE_TOOLCHAIN:-/workspace/toolchain}"
ANDROID_HOME="$TOOLS/Android/Sdk"
ANDROID_NDK_VERSION="27.2.12479018"
GRADLE_VERSION="8.11.1"
JDK_VERSION="17"
ANDROID_PLATFORM="35"
ANDROID_BUILD_TOOLS="35.0.0"
ANDROID_PLATFORM_TOOLS="platform-tools"
APP_ONLY="${CONVOSCRIBE_APP_ONLY:-0}"

mkdir -p "$TOOLS"

# Temporary directory that is cleaned up on exit.
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

# download <url> <output-file>
# Streams a URL to a file. Follows redirects and fails on HTTP errors.
download() {
  local url="$1"
  local out="$2"
  curl --fail --location --silent --show-error --retry 3 --retry-delay 5 -o "$out" "$url"
}

# ----------------------------------------------------------------------------
# JDK 17
# ----------------------------------------------------------------------------
JDK_DIR="$TOOLS/jdk-${JDK_VERSION}"
if [[ ! -x "$JDK_DIR/bin/javac" ]]; then
  echo "Installing JDK ${JDK_VERSION} from Adoptium..."
  mkdir -p "$JDK_DIR"
  jdk_tmp="$TMP_DIR/jdk17.tar.gz"
  download "https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/linux/x64/jdk/hotspot/normal/eclipse" "$jdk_tmp"
  tar -xzf "$jdk_tmp" --strip-components=1 -C "$JDK_DIR"
else
  echo "JDK ${JDK_VERSION} already installed."
fi

# ----------------------------------------------------------------------------
# Gradle
# ----------------------------------------------------------------------------
GRADLE_DIR="$TOOLS/gradle-${GRADLE_VERSION}"
if [[ ! -x "$GRADLE_DIR/bin/gradle" ]]; then
  echo "Installing Gradle ${GRADLE_VERSION}..."
  gradle_tmp="$TMP_DIR/gradle.zip"
  download "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" "$gradle_tmp"
  unzip -q "$gradle_tmp" -d "$TOOLS"
else
  echo "Gradle ${GRADLE_VERSION} already installed."
fi

# ----------------------------------------------------------------------------
# CMake (host build tool; needed for the sherpa-onnx native build later).
# Installed only if missing, and only from a Kitware release tarball (no sudo/apt).
# ----------------------------------------------------------------------------
CMAKE_VERSION="${CMAKE_VERSION:-3.27.9}"
CMAKES_DIR="$TOOLS/cmake-${CMAKE_VERSION}"
if [[ "$APP_ONLY" == 1 ]]; then
  echo "CONVOSCRIBE_APP_ONLY=1: skipping CMake."
elif [[ ! -x "$CMAKES_DIR/bin/cmake" ]]; then
  echo "Installing CMake ${CMAKE_VERSION}..."
  cmake_tmp="$TMP_DIR/cmake.tar.gz"
  download "https://github.com/Kitware/CMake/releases/download/v${CMAKE_VERSION}/cmake-${CMAKE_VERSION}-linux-x86_64.tar.gz" "$cmake_tmp"
  mkdir -p "$TOOLS/cmake-${CMAKE_VERSION}"
  tar -xzf "$cmake_tmp" --strip-components=1 -C "$TOOLS/cmake-${CMAKE_VERSION}"
else
  echo "CMake ${CMAKE_VERSION} already installed."
fi

# ----------------------------------------------------------------------------
# Android command-line tools
# ----------------------------------------------------------------------------
CMDLINE_DIR="$ANDROID_HOME/cmdline-tools/latest"
if [[ ! -x "$CMDLINE_DIR/bin/sdkmanager" ]]; then
  echo "Installing Android command-line tools..."
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  cmdline_tmp="$TMP_DIR/cmdline-tools.zip"
  download "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" "$cmdline_tmp"
  extract_tmp="$TMP_DIR/cmdline-tools-extract"
  mkdir -p "$extract_tmp"
  unzip -q "$cmdline_tmp" -d "$extract_tmp"
  # The zip extracts to <extract_tmp>/cmdline-tools/{bin,lib,...}; move it under
  # the conventional location cmdline-tools/latest/.
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
  # Use find to be resilient to anything inside the extracted directory.
  if [[ -d "$extract_tmp/cmdline-tools" ]]; then
    cp -r "$extract_tmp/cmdline-tools/." "$ANDROID_HOME/cmdline-tools/latest/"
  else
    echo "Unexpected layout in commandlinetools archive." >&2
    exit 1
  fi
else
  echo "Android command-line tools already installed."
fi

# ----------------------------------------------------------------------------
# Android SDK packages (licenses, platform-tools, platforms, build-tools, NDK)
# ----------------------------------------------------------------------------
export JAVA_HOME="$JDK_DIR"
export PATH="$JDK_DIR/bin:$CMDLINE_DIR/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "Accepting Android SDK licenses..."
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true

SDK_PACKAGES=("$ANDROID_PLATFORM_TOOLS" "platforms;android-${ANDROID_PLATFORM}"
              "build-tools;${ANDROID_BUILD_TOOLS}")
if [[ "$APP_ONLY" != 1 ]]; then
  SDK_PACKAGES+=("ndk;${ANDROID_NDK_VERSION}")
fi

NEED_INSTALL=0
for pkg in "${SDK_PACKAGES[@]}"; do
  installed=$(sdkmanager --sdk_root="$ANDROID_HOME" --list_installed 2>/dev/null \
              | grep -E "^[[:space:]]+${pkg}[[:space:]]" || true)
  if [[ -z "$installed" ]]; then
    NEED_INSTALL=1
    break
  fi
done

if [[ "$NEED_INSTALL" -eq 1 ]]; then
  echo "Installing Android SDK packages..."
  { yes || true; } | sdkmanager --sdk_root="$ANDROID_HOME" "${SDK_PACKAGES[@]}" >/dev/null
else
  echo "Android SDK packages already installed."
fi

echo "Toolchain ready."
echo "  JAVA_HOME=$JDK_DIR"
echo "  Gradle  : $GRADLE_DIR"
echo "  SDK root: $ANDROID_HOME"
echo "  NDK     : $ANDROID_HOME/ndk/${ANDROID_NDK_VERSION}"

# sherpa-onnx 1.13.8 native libraries (Qualcomm QNN + speaker diarization
# enabled) and the Kotlin API jar. This is a no-op on a re-run; pass
# --force to rebuild from scratch. The build takes 10-30 minutes.
SHERPA_BUILD_SCRIPT="$(cd "$(dirname "$0")" && pwd)/build-sherpa-onnx.sh"
if [[ "$APP_ONLY" == 1 ]]; then
  echo "CONVOSCRIBE_APP_ONLY=1: skipping the sherpa-onnx build."
elif [[ -x "$SHERPA_BUILD_SCRIPT" ]]; then
  bash "$SHERPA_BUILD_SCRIPT"
else
  echo "WARNING: sherpa-onnx build script not found at $SHERPA_BUILD_SCRIPT; skipping." >&2
fi
