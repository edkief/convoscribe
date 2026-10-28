#!/usr/bin/env bash
# Build (and optionally push) the release container images:
#
#   sherpa   docker/sherpa-onnx.Containerfile: files-only image with the
#            sherpa-onnx native libs + Kotlin jar, and onnxruntime's JNI
#            library rebuilt against them. Slow (10-30 min); only rebuild
#            when the pins in scripts/build-sherpa-onnx.sh or
#            scripts/build-onnxruntime-jni.sh change.
#   builder  docker/android-builder.Containerfile: JDK + SDK + gh with the
#            sherpa outputs at /opt/sherpa; what Tekton runs release.sh in.
#
# Tags are derived from the pinned versions, so an unchanged pin means an
# unchanged tag and there is nothing to rebuild:
#   sherpa : <sherpa>-qnn<qnn>-ort<onnxruntime>-jni<onnxruntime JNI tag>,
#            e.g. 1.13.8-qnn2.40.0.251030-ort1.28.2-jniv1.28.0
#   builder: <sherpa tag>-tc<hash of setup-toolchain.sh + env.sh>
#
# Usage: bash scripts/build-images.sh [options] [sherpa|builder|all]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

usage() {
  cat <<'USAGE'
Usage: bash scripts/build-images.sh [options] [sherpa|builder|all]

Build the sherpa-onnx and/or Android builder images (default: all).

Options:
  --push                  push the images after building them
  --registry HOST[/PATH]  registry prefix (default: $CONVOSCRIBE_REGISTRY,
                          else registry.kieffer.me)
  --tls-verify=false      for a plain-HTTP registry such as
                          docker-registry.docker-registry:5000 (buildah/podman)
  --print-sherpa-image    print the sherpa image reference and exit
  --print-builder-image   print the builder image reference and exit
  -h, --help              show this help

Uses buildah if available, else podman, else docker. Log in to the
registry first (e.g. `buildah login registry.kieffer.me`).
USAGE
}

registry="${CONVOSCRIBE_REGISTRY:-registry.kieffer.me}"
push=false
tls_verify=true
print=""
target=all
while [[ $# -gt 0 ]]; do
  case "$1" in
    --push) push=true ;;
    --registry)
      registry="${2:?--registry needs a value}"
      shift
      ;;
    --registry=*) registry="${1#*=}" ;;
    --tls-verify=false) tls_verify=false ;;
    --print-sherpa-image) print=sherpa ;;
    --print-builder-image) print=builder ;;
    sherpa | builder | all) target="$1" ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "build-images: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done
registry="${registry%/}"

# pin <NAME> [script]: the value of NAME=... in [script] (default build-sherpa-onnx.sh).
pin() {
  local value script="${2:-scripts/build-sherpa-onnx.sh}"
  value=$(sed -n "s/^$1=\\([^[:space:]]*\\).*/\\1/p" "$script" | head -n 1)
  if [[ -z "$value" ]]; then
    echo "build-images: could not read $1 from $script" >&2
    exit 1
  fi
  printf '%s' "$value"
}

sherpa_tag="$(pin VERSION)-qnn$(pin QNN_VERSION)-ort$(pin ONNXRUNTIME_VERSION)"
sherpa_tag+="-jni$(pin ORT_TAG scripts/build-onnxruntime-jni.sh)"
toolchain_hash=$(cat scripts/setup-toolchain.sh scripts/env.sh | sha256sum | cut -c1-10)
sherpa_image="$registry/convoscribe-sherpa:$sherpa_tag"
builder_image="$registry/convoscribe-android-builder:$sherpa_tag-tc$toolchain_hash"

case "$print" in
  sherpa)
    echo "$sherpa_image"
    exit 0
    ;;
  builder)
    echo "$builder_image"
    exit 0
    ;;
esac

if command -v buildah >/dev/null 2>&1; then
  engine=buildah
elif command -v podman >/dev/null 2>&1; then
  engine=podman
elif command -v docker >/dev/null 2>&1; then
  engine=docker
else
  echo "build-images: need buildah, podman or docker on PATH." >&2
  exit 1
fi

tls_args=()
if [[ "$tls_verify" == false ]]; then
  if [[ "$engine" == docker ]]; then
    echo "build-images: --tls-verify=false is not supported with docker; list the registry under insecure-registries in daemon.json instead." >&2
    exit 2
  fi
  tls_args=(--tls-verify=false)
fi

# build_image <containerfile> <image> [build-arg...]
build_image() {
  local file="$1" image="$2"
  shift 2
  local args=()
  local arg
  for arg in "$@"; do
    args+=(--build-arg "$arg")
  done
  echo "build-images: building $image ($engine)"
  if [[ "$engine" == buildah ]]; then
    buildah bud "${tls_args[@]}" --layers -f "$file" -t "$image" "${args[@]}" .
  else
    "$engine" build "${tls_args[@]}" -f "$file" -t "$image" "${args[@]}" .
  fi
  if [[ "$push" == true ]]; then
    echo "build-images: pushing $image"
    "$engine" push "${tls_args[@]}" "$image"
  fi
}

if [[ "$target" == sherpa || "$target" == all ]]; then
  build_image docker/sherpa-onnx.Containerfile "$sherpa_image" "SHERPA_TAG=$sherpa_tag"
fi
if [[ "$target" == builder || "$target" == all ]]; then
  # The builder copies from the sherpa image by reference, so when only
  # the builder is built, that image must already be pushed (or local).
  build_image docker/android-builder.Containerfile "$builder_image" "SHERPA_IMAGE=$sherpa_image"
fi

echo
echo "sherpa : $sherpa_image"
echo "builder: $builder_image"
