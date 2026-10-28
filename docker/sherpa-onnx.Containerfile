# sherpa-onnx native libraries (arm64-v8a, QNN + speaker diarization), the
# Kotlin API jar, and onnxruntime's JNI library linked against sherpa's
# runtime (issue #61), built once by scripts/build-sherpa-onnx.sh and
# scripts/build-onnxruntime-jni.sh and shipped as a files-only image so
# releases never rebuild them.
#
# Build and push with scripts/build-images.sh (it derives the tag from the
# versions pinned in scripts/build-sherpa-onnx.sh). Build context: repo root.
#
# Layout of the final image (nothing else, it is FROM scratch):
#   /sherpa/jniLibs/arm64-v8a/*.so
#   /sherpa/libs/sherpa-onnx-classes-<version>.jar

FROM ubuntu:24.04 AS build

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      ca-certificates curl git make bzip2 unzip binutils build-essential python3 \
 && rm -rf /var/lib/apt/lists/*

ENV CONVOSCRIBE_TOOLCHAIN=/opt/toolchain
WORKDIR /src
COPY scripts/ scripts/
# Installs JDK, SDK, NDK and CMake, then runs build-sherpa-onnx.sh and
# build-onnxruntime-jni.sh, which write into /src/app/.
RUN bash scripts/setup-toolchain.sh

FROM scratch
ARG SHERPA_TAG=unknown
LABEL org.opencontainers.image.title="convoscribe-sherpa" \
      org.opencontainers.image.source="https://github.com/edkief/convoscribe" \
      org.opencontainers.image.version="${SHERPA_TAG}"
COPY --from=build /src/app/src/main/jniLibs/arm64-v8a/ /sherpa/jniLibs/arm64-v8a/
COPY --from=build /src/app/libs/ /sherpa/libs/
