# Image the Tekton release task runs scripts/release.sh in: JDK 17, Gradle
# and the Android SDK (no NDK), gh, plus the prebuilt sherpa-onnx outputs
# copied from the sherpa image at /opt/sherpa. release.sh copies them into
# the checkout (CONVOSCRIBE_SHERPA_PREBUILT), so nothing native is built.
#
# Build and push with scripts/build-images.sh. Build context: repo root.

ARG SHERPA_IMAGE
FROM ${SHERPA_IMAGE} AS sherpa

FROM ubuntu:24.04

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      ca-certificates curl git unzip python3 gh \
 && rm -rf /var/lib/apt/lists/*

ENV CONVOSCRIBE_TOOLCHAIN=/opt/toolchain
COPY scripts/ /tmp/scripts/
RUN CONVOSCRIBE_APP_ONLY=1 bash /tmp/scripts/setup-toolchain.sh \
 && rm -rf /tmp/scripts

COPY --from=sherpa /sherpa/ /opt/sherpa/
ENV CONVOSCRIBE_SHERPA_PREBUILT=/opt/sherpa
