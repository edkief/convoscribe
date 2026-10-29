# Source me: `. scripts/env.sh` or `source scripts/env.sh`. Do NOT run me.
# This file must not use `set -e` because we are sourced and may be appended to.
#
# Everything lives under $CONVOSCRIBE_TOOLCHAIN (default /workspace/toolchain,
# the persistent volume; $HOME is ephemeral). scripts/setup-toolchain.sh
# installs into the same directory. Export CONVOSCRIBE_TOOLCHAIN before
# sourcing to use another location.
#
# Per-machine override: if scripts/env.<hostname>.sh exists (hostname as
# printed by `uname -n`), it is sourced INSTEAD of the rest of this file.
# A host file may itself `source` this env.sh to reuse the defaults and then
# override a few variables; the guard below stops that from looping.

if [[ -z "${_CONVOSCRIBE_ENV_HOST_LOADING:-}" ]]; then
  # Directory of this file when sourced: BASH_SOURCE in bash, %x in zsh.
  _convoscribe_env_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")" && pwd)"
  _convoscribe_host_env="$_convoscribe_env_dir/env.$(uname -n).sh"
  unset _convoscribe_env_dir
  if [[ -f "$_convoscribe_host_env" ]]; then
    export _CONVOSCRIBE_ENV_HOST_LOADING=1
    source "$_convoscribe_host_env"
    unset _CONVOSCRIBE_ENV_HOST_LOADING _convoscribe_host_env
    return 0
  fi
  unset _convoscribe_host_env
fi

export CONVOSCRIBE_TOOLCHAIN="${CONVOSCRIBE_TOOLCHAIN:-/workspace/toolchain}"
export JAVA_HOME="$CONVOSCRIBE_TOOLCHAIN/jdk-17"
export ANDROID_HOME="$CONVOSCRIBE_TOOLCHAIN/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK="$ANDROID_HOME/ndk/27.2.12479018"
export GRADLE_USER_HOME="$CONVOSCRIBE_TOOLCHAIN/gradle-home"

prepend_path() {
  case ":$PATH:" in
    *":$1:"*) ;;
    *) export PATH="$1:$PATH" ;;
  esac
}

prepend_path "$JAVA_HOME/bin"
prepend_path "$CONVOSCRIBE_TOOLCHAIN/gradle-8.11.1/bin"
prepend_path "$ANDROID_HOME/cmdline-tools/latest/bin"
prepend_path "$ANDROID_HOME/platform-tools"

# CMake directory layout: setup-toolchain.sh installs the version pinned by
# CMAKE_VERSION inside scripts/setup-toolchain.sh. We add any matching directory
# we can find so env.sh does not have to know the current version.
for cmake_dir in "$CONVOSCRIBE_TOOLCHAIN"/cmake-*/bin; do
  if [[ -d "$cmake_dir" ]]; then
    prepend_path "$cmake_dir"
    break
  fi
done

# Node.js and pnpm for server/, installed by setup-toolchain.sh at the
# version it pins; same approach as CMake.
for node_dir in "$CONVOSCRIBE_TOOLCHAIN"/node-v*/bin; do
  if [[ -d "$node_dir" ]]; then
    prepend_path "$node_dir"
    break
  fi
done

# Robolectric caches android-all jars in ~/.m2; keep them in the toolchain too.
if [[ ! -L "$HOME/.m2" ]]; then
  mkdir -p "$CONVOSCRIBE_TOOLCHAIN/m2"
  rm -rf "$HOME/.m2"
  ln -s "$CONVOSCRIBE_TOOLCHAIN/m2" "$HOME/.m2"
fi
