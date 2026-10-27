# Omnibus: the whole personal tier in ONE container - PostgreSQL (pgvector +
# Apache AGE), a Redis-protocol store, the core API and the console, the
# `docker run -p 3000:3000` shape. Same jar, same console bundle and the same
# `personal` Spring profile as deploy/compose/personal.yml; the difference is
# packaging, not capability.
#
# It is assembled FROM the release's own images (never rebuilt from source),
# so it can only be built after core, web and postgres are published - the
# release workflow's build-omnibus/publish-omnibus jobs run in exactly that
# order. The inputs are pulled by DIGEST, not tag (OpenSSF Pinned-Dependencies:
# a tag can be moved between publish and this build); release.yml resolves the
# just-published manifest digests and passes them as build args. Locally:
#
#   for i in core web postgres; do docker buildx imagetools inspect \
#     ghcr.io/cd-lc/$i:0.1.3 --format '{{.Manifest.Digest}}'; done
#   docker build -f deploy/omnibus/Dockerfile \
#     --build-arg CORE_IMAGE_DIGEST=sha256:... deploy
#
# (WEB_IMAGE_DIGEST and POSTGRES_IMAGE_DIGEST likewise; default digests below
# point at the published 0.1.12 manifests so a plain local build still works.)
#
#   docker run -d --name vegaduta -v vegaduta-data:/data \
#     -p 3000:3000 -p 8080:8080 ghcr.io/<owner>/omnibus:<version>
#
# Everything mutable lives under /data (one volume): the database cluster,
# uploaded files, Redis scratch space, and the generated secrets file
# (APP_ENCRYPTION_KEY is generated on first boot and must never change -
# losing it makes stored provider keys undecryptable; keep a /data backup).

ARG REGISTRY=ghcr.io/cd-lc
# Manifest-list digests of the three input images; the release workflow always
# overrides them with the digests it just published. The defaults point at the
# published 0.1.12 manifests only so a plain `docker build` still has something
# to pull - update them when cutting a release.
ARG CORE_IMAGE_DIGEST=f71c417044b3f9246946d41b8cd10e30a1101b500c657b8657f3e140cabb7501
ARG WEB_IMAGE_DIGEST=a4bb585b0176c05a6f9a8db519c941e3441ce6877030b16bc43bce26110c1bab
ARG POSTGRES_IMAGE_DIGEST=52fefb560a4a851adc105b5efaf2658e0011e552ca4f9b7ddf9804026929a4f3

# jlink-minimal JRE: same module list as core/Dockerfile's JRE=jlink variant
# (keep the two in sync). Roughly half the size of the full temurin JRE on
# the same glibc generation.
FROM eclipse-temurin:21.0.12.1_1-jdk-noble@sha256:107215954359f1ab0be058a247d7c64ed6e69c71c1682d471d38ef751ec8e964 AS jre-linker
RUN "$JAVA_HOME/bin/jlink" \
        --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,jdk.crypto.ec,jdk.jfr,jdk.localedata,jdk.unsupported,jdk.unsupported.desktop,jdk.zipfs \
        --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
        --output /jre-minimal \
    && rm -f /jre-minimal/lib/server/classes.jsa

FROM node:22.23.3-trixie-slim@sha256:154ba2f4d6fec323d28e4f4bb86bba4677f1223391a1979cf521304e03a98dfa AS node-img

# COPY --from cannot expand ARGs, so the release's own images are named stages.
FROM ${REGISTRY}/core@sha256:${CORE_IMAGE_DIGEST} AS core-img
FROM ${REGISTRY}/web@sha256:${WEB_IMAGE_DIGEST} AS web-img

# The substrate is our own postgres image: PostgreSQL 16 on Debian trixie
# with pgvector and Apache AGE already compiled in, plus gosu for dropping
# privileges - everything downstream (redis-server, node, the JRE) is either
# trixie-native or glibc-compatible.
FROM ${REGISTRY}/postgres@sha256:${POSTGRES_IMAGE_DIGEST}
USER root
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# redis-server (trixie) is protocol-identical for everything core uses -
# the compose tiers run the Valkey fork; inside one container the fork makes
# no difference. curl is only for the HEALTHCHECK below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends redis-server curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=jre-linker /jre-minimal /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"

COPY --from=node-img /usr/local/bin/node /usr/local/bin/node

COPY --from=core-img /app/app.jar /opt/core/app.jar
# The web image's /app IS the Next standalone bundle (server.js + traced
# node_modules + .next/static + public).
COPY --from=web-img /app /opt/web

# Runtime users (postgres exists in the base image). The only tree the
# console writes at runtime is .next/cache; the entrypoint gives each service
# a HOME under /data instead of the image layer, so nothing a restart must
# keep lives outside the volume.
RUN useradd -r -m -d /home/agentic agentic \
    && useradd -r -m -d /home/nextjs nextjs \
    && mkdir -p /opt/web/.next/cache && chown -R nextjs:nextjs /opt/web/.next/cache

# Context is deploy/ so the same db-roles script the compose stacks use can be
# reused verbatim - no second implementation of the app-role bootstrap to drift.
COPY omnibus/entrypoint.sh /usr/local/bin/vegaduta-omnibus
COPY postgres/ensure-app-role.sh /usr/local/bin/ensure-app-role.sh
RUN chmod 0755 /usr/local/bin/vegaduta-omnibus /usr/local/bin/ensure-app-role.sh

ENV OMNIBUS_DATA_DIR=/data
VOLUME /data
# 3000 console, 8080 API - same pair the personal compose tier publishes.
EXPOSE 3000 8080

# Healthy means: core answers readiness AND the console serves. Postgres
# liveness is implied by core's readiness (readinessState includes db).
HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --retries=10 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null \
        && curl -fsS -o /dev/null http://127.0.0.1:3000/ || exit 1

ENTRYPOINT ["/usr/local/bin/vegaduta-omnibus"]
