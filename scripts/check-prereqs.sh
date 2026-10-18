#!/usr/bin/env bash
# Check network hosts and host tools required by the build.
# Does not modify the system.

set -uo pipefail

# If cmake is provided by setup-toolchain.sh, add it to PATH so this check can
# find it even when env.sh was not sourced.
for cmake_bin in "${CONVOSCRIBE_TOOLCHAIN:-/workspace/toolchain}"/cmake-*/bin; do
  if [[ -x "$cmake_bin/cmake" ]]; then
    PATH="$cmake_bin:$PATH"
    break
  fi
done

HOSTS=(
  "https://api.adoptium.net"
  "https://github.com"
  "https://dl.google.com"
  "https://services.gradle.org"
  "https://repo.maven.apache.org/maven2/"
  "https://maven.google.com/web/index.html"
  "https://plugins.gradle.org"
  "https://huggingface.co"
)

TOOLS=(cmake make git curl tar bzip2 unzip)

rc=0

# --- Network reachability ----------------------------------------------------
for host in "${HOSTS[@]}"; do
  code=$(curl -sS -o /dev/null -m 20 -w '%{http_code}' "$host" 2>/dev/null) || {
    printf 'FAIL %s\n' "$host"
    rc=1
    continue
  }
  printf 'OK %s\n' "$host"
done

# --- Free disk space under $HOME ---------------------------------------------
required_kb=$((12 * 1024 * 1024))   # 12 GB
if command -v df >/dev/null 2>&1; then
  avail_kb=$(df -Pk "$HOME" | awk 'NR==2 { print $4 }')
  if [[ -z "$avail_kb" || "$avail_kb" -lt "$required_kb" ]]; then
    printf 'FAIL disk free space under %s is less than 12 GB (have %s KB)\n' "$HOME" "${avail_kb:-0}"
    rc=1
  fi
else
  printf 'MISSING df\n'
  rc=1
fi

# --- Host tools --------------------------------------------------------------
check_tool() {
  local tool="$1"
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf 'MISSING %s\n' "$tool"
    rc=1
    return
  fi
  if [[ "$tool" == "cmake" ]]; then
    local ver
    ver=$(cmake --version 2>/dev/null | head -n1 | awk '{ print $3 }')
    if [[ -z "$ver" ]]; then
      printf 'MISSING %s\n' "$tool"
      rc=1
      return
    fi
    local major minor
    major=$(printf '%s' "$ver" | cut -d. -f1)
    minor=$(printf '%s' "$ver" | cut -d. -f2)
    if [[ -z "$major" || -z "$minor" || "$major" -lt 3 || ( "$major" -eq 3 && "$minor" -lt 22 ) ]]; then
      printf 'MISSING %s (have %s, need >= 3.22)\n' "$tool" "$ver"
      rc=1
      return
    fi
  fi
  printf 'OK %s\n' "$tool"
}

for tool in "${TOOLS[@]}"; do
  check_tool "$tool"
done

exit "$rc"
