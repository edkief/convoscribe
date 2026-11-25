# sherpa-onnx's streaming ASR built for the browser (issue #164), built once
# by scripts/build-sherpa-onnx-wasm.sh and shipped as a files-only image so
# server releases never compile it. server/Dockerfile copies it into the
# image's public/sherpa-onnx/<version>/.
#
# Build and push with `bash scripts/build-images.sh --push sherpa-wasm` (it
# derives the tag from the versions pinned in the script). Build context:
# repo root.
#
# Layout of the final image (nothing else, it is FROM scratch):
#   /sherpa-onnx-wasm/sherpa-onnx-wasm-main-asr.{js,wasm}
#   /sherpa-onnx-wasm/sherpa-onnx-asr.mjs

ARG EMSDK_VERSION=4.0.23
FROM emscripten/emsdk:${EMSDK_VERSION} AS build

WORKDIR /src
COPY scripts/ scripts/
ENV CONVOSCRIBE_TOOLCHAIN=/opt/toolchain
RUN bash scripts/build-sherpa-onnx-wasm.sh /out

FROM scratch
ARG SHERPA_WASM_TAG=unknown
LABEL org.opencontainers.image.title="convoscribe-sherpa-wasm" \
      org.opencontainers.image.source="https://github.com/edkief/convoscribe" \
      org.opencontainers.image.version="${SHERPA_WASM_TAG}"
COPY --from=build /out/ /sherpa-onnx-wasm/
