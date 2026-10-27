# ClusterFuzzLite build environment for the Jazzer fuzz targets.
# base-builder-jvm ships JDK 15 + the Jazzer driver/agent; the reactor needs
# JDK 21 + Maven, so both are installed here (Temurin pinned by sha256).
FROM gcr.io/oss-fuzz-base/base-builder-jvm

RUN apt-get update && apt-get install -y maven curl ca-certificates \
    && curl -fsSL -o /tmp/jdk.tar.gz \
       "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz" \
    && echo "d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c  /tmp/jdk.tar.gz" | sha256sum -c - \
    && mkdir -p /opt/jdk21 && tar -xzf /tmp/jdk.tar.gz -C /opt/jdk21 --strip-components=1 \
    && rm /tmp/jdk.tar.gz

ENV JAVA_HOME=/opt/jdk21
ENV PATH="/opt/jdk21/bin:${PATH}"

COPY . $SRC/vegaduta
WORKDIR $SRC/vegaduta
COPY .clusterfuzzlite/build.sh $SRC/
