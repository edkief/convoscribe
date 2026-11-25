#!/usr/bin/env bash
# Build sherpa-onnx's streaming ASR for the browser (issue #164): the
# WebAssembly runtime the web app's Record page runs the Kroko models with,
# the same version as the app's native build (scripts/build-sherpa-onnx.sh).
#
# Upstream's browser build bakes one model into a `.data` file. This one
# has no model: the web app downloads the model on request and writes it
# into the module's in-memory filesystem, so FS is exported. It is an ES
# module for a dedicated worker, single-threaded (no SharedArrayBuffer, so
# no cross-origin isolation headers).
#
# Writes into OUT_DIR (default server/public/sherpa-onnx/<version>/, which
# `pnpm dev` serves and .gitignore excludes):
#   sherpa-onnx-wasm-main-asr.js    the module factory (export default)
#   sherpa-onnx-wasm-main-asr.wasm  the runtime
#   sherpa-onnx-asr.mjs             upstream's JS API, with an ESM export
#
# Idempotent: exits at once when OUT_DIR already holds this build. Pass
# `--force` to rebuild. Releases never run this: the server image copies
# the files from the files-only image docker/sherpa-onnx-wasm.Containerfile
# builds (scripts/build-images.sh sherpa-wasm).
#
# Needs git and python3; emsdk brings its own clang. Uses `emcc` from
# PATH when it is there (the emsdk image), else
# installs emsdk $EMSDK_VERSION under the toolchain directory. CMake comes
# from PATH, else from a Python venv. The build takes 5-15 minutes.
#
# Usage: bash scripts/build-sherpa-onnx-wasm.sh [--force] [OUT_DIR]

set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=1.13.8
# sherpa-onnx's build-wasm-simd-asr.sh names 4.0.23 as known to work.
EMSDK_VERSION=4.0.23

FORCE=0
OUT=""
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    -h | --help)
      sed -n '2,29p' "$0"
      exit 0
      ;;
    *) OUT="$arg" ;;
  esac
done
OUT="${OUT:-server/public/sherpa-onnx/$VERSION}"
STAMP="$OUT/.build"
STAMP_VALUE="sherpa-onnx=$VERSION emsdk=$EMSDK_VERSION"

if [[ $FORCE == 0 && -f "$STAMP" && "$(cat "$STAMP")" == "$STAMP_VALUE" ]]; then
  echo "build-sherpa-onnx-wasm: $OUT is up to date ($STAMP_VALUE)"
  exit 0
fi

WORK="${CONVOSCRIBE_TOOLCHAIN:-$HOME/.local/share/convoscribe-toolchain}/sherpa-onnx-wasm-build"
mkdir -p "$WORK"

if ! command -v emcc >/dev/null 2>&1; then
  if [[ ! -d "$WORK/emsdk" ]]; then
    git clone -q https://github.com/emscripten-core/emsdk.git "$WORK/emsdk"
  fi
  "$WORK/emsdk/emsdk" install "$EMSDK_VERSION" >/dev/null
  "$WORK/emsdk/emsdk" activate "$EMSDK_VERSION" >/dev/null
  # shellcheck disable=SC1091
  source "$WORK/emsdk/emsdk_env.sh" >/dev/null 2>&1
fi
if ! command -v cmake >/dev/null 2>&1; then
  if [[ ! -x "$WORK/cmake-venv/bin/cmake" ]]; then
    python3 -m venv "$WORK/cmake-venv"
    "$WORK/cmake-venv/bin/pip" install -q cmake
  fi
  export PATH="$WORK/cmake-venv/bin:$PATH"
fi

SRC="$WORK/sherpa-onnx-$VERSION"
if [[ $FORCE == 1 ]]; then rm -rf "$SRC"; fi
if [[ ! -d "$SRC" ]]; then
  git clone -q --depth 1 -b "v$VERSION" https://github.com/k2-fsa/sherpa-onnx.git "$SRC"
fi

# Upstream's CMake bakes in a model and builds for the page's main thread.
# Drop the model check and preload, export FS, and build an ES module for a
# worker. Each edit is checked, so a changed upstream file fails loudly.
CMAKE_FILE="$SRC/wasm/asr/CMakeLists.txt"
if ! grep -q 'convoscribe' "$CMAKE_FILE"; then
  python3 - "$CMAKE_FILE" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path).read()
def sub(pattern, repl, flags=0):
    global text
    text, n = re.subn(pattern, repl, text, flags=flags)
    if n != 1:
        sys.exit(f"build-sherpa-onnx-wasm: upstream {path} changed ({pattern!r} matched {n} times)")
sub(r'if\(NOT EXISTS "\$\{CMAKE_CURRENT_SOURCE_DIR\}/assets/encoder\.onnx"\).*?endif\(\)\n', '', re.S)
sub(r'string\(APPEND MY_FLAGS "--preload-file [^\n]*\n',
    '# convoscribe: no baked-in model; an ES module for a dedicated worker.\n'
    'string(APPEND MY_FLAGS " -sENVIRONMENT=worker -sMODULARIZE=1 -sEXPORT_ES6=1 -sEXPORT_NAME=createSherpaModule ")\n')
sub(r"'UTF8ToString',", "'UTF8ToString','FS',")
sub(r'\n\s*"index\.html"', '')
sub(r'\n\s*"app-asr\.js"', '')
sub(r'\n\s*"\$<TARGET_FILE_DIR:sherpa-onnx-wasm-main-asr>/sherpa-onnx-wasm-main-asr\.data"', '')
open(path, "w").write(text)
PY
fi

(
  cd "$SRC"
  sed -i 's/^make -j[0-9]*/make -j"$(nproc)"/' build-wasm-simd-asr.sh
  ./build-wasm-simd-asr.sh
)

BUILT="$SRC/build-wasm-simd-asr/install/bin/wasm/asr"
mkdir -p "$OUT"
cp "$BUILT/sherpa-onnx-wasm-main-asr.js" "$BUILT/sherpa-onnx-wasm-main-asr.wasm" "$OUT/"
# Upstream's API is a plain script for the page; a module worker imports it.
{
  cat "$BUILT/sherpa-onnx-asr.js"
  printf '\nexport { createOnlineRecognizer };\n'
} >"$OUT/sherpa-onnx-asr.mjs"
printf '%s' "$STAMP_VALUE" >"$STAMP"
ls -l "$OUT"
