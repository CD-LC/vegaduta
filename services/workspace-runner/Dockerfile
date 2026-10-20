# workspace-runner - runs the Assistant's shell commands for core, in the folder the person
# attached, away from core's secrets (see server.js and docs/personal-mode.md).
#
#   docker build -t ghcr.io/cd-lc/workspace-runner:local services/workspace-runner
#
# Toolchains: JDK 21 (Temurin), Maven, Node 22 + npm, git, and the usual build basics. Gradle
# projects bring their own ./gradlew; Maven projects may use ./mvnw too.
ARG NODE_IMAGE=node:22.23.3-trixie-slim@sha256:154ba2f4d6fec323d28e4f4bb86bba4677f1223391a1979cf521304e03a98dfa
ARG JDK_IMAGE=eclipse-temurin:21-jdk-noble

FROM ${NODE_IMAGE} AS node

FROM ${JDK_IMAGE}

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl git maven unzip zip make python3 \
    && rm -rf /var/lib/apt/lists/*

# Node from the official image (same glibc family), not a curl | sh installer.
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx

# Non-root. HOME is its own volume in compose, so ~/.m2, ~/.gradle and ~/.npm caches survive
# restarts and never land inside the person's project folder.
RUN userdel -r ubuntu 2>/dev/null || true; \
    useradd --uid 1000 --create-home --home-dir /home/runner runner \
    && mkdir -p /workspace /app \
    && chown runner:runner /workspace

WORKDIR /app
COPY server.js /app/server.js

ENV WORKSPACE_ROOT=/workspace \
    WORKSPACE_RUNNER_PORT=8090 \
    HOME=/home/runner \
    GRADLE_USER_HOME=/home/runner/.gradle

USER runner
# No published port: core reaches it on the compose network only.
CMD ["node", "/app/server.js"]
