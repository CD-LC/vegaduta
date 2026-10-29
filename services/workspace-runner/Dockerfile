# workspace-runner - runs the Assistant's shell commands for core, in the folder the person
# attached, away from core's secrets (see server.js and docs/personal-mode.md).
#
#   docker build -t ghcr.io/cd-lc/workspace-runner:local services/workspace-runner
#
# Toolchains: JDK 21 (Temurin), Maven, Node 22 + npm, git, and the usual build basics. Gradle
# projects bring their own ./gradlew; Maven projects may use ./mvnw too.
ARG NODE_IMAGE=node:22.23.4-trixie-slim@sha256:2117f713a417f3aff682ac8f5bc2ebbdf1beed51f36e67ab1e0fd3866b6d2d8f
ARG JDK_IMAGE=eclipse-temurin:21.0.12.1_1-jdk-noble@sha256:107215954359f1ab0be058a247d7c64ed6e69c71c1682d471d38ef751ec8e964

FROM ${NODE_IMAGE} AS node
# The npm CLI bundled with the Node image carries High advisories in its own dependencies
# (brace-expansion, ip-address, picomatch, pacote, sigstore) that the base tag has not
# picked up. Builds here run `npm`, so it stays; replace it with a pinned release whose
# bundled dependencies are fixed. Keep NPM_VERSION in step with the other Node runner images
# (services/mcp-stdio-runner/dockerfile-npm.test.js checks that).
ARG NPM_VERSION=11.21.1
# sha512 of registry.npmjs.org/npm/-/npm-${NPM_VERSION}.tgz (the published
# dist.integrity). npm has no hash-verified global install, so the tarball is
# fetched, verified, and laid down by hand - same layout `npm i -g npm` makes.
ARG NPM_TGZ_SHA512=613ccbe90044257c87713bb426251ced128ca7e9b3949b65eeb18b98445a5d441e14720c73d6ad76be86f4cf42f2f0d66efe347a9a13400ccd2f6fcfb5954385
RUN npm pack "npm@${NPM_VERSION}" --pack-destination /tmp \
    && echo "${NPM_TGZ_SHA512}  /tmp/npm-${NPM_VERSION}.tgz" | sha512sum -c - \
    && rm -rf /usr/local/lib/node_modules/npm \
    && tar -xzf "/tmp/npm-${NPM_VERSION}.tgz" -C /usr/local/lib/node_modules \
    && mv /usr/local/lib/node_modules/package /usr/local/lib/node_modules/npm \
    && ln -sf ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -sf ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && rm "/tmp/npm-${NPM_VERSION}.tgz" \
    && npm cache clean --force

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
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD node -e "fetch('http://127.0.0.1:'+(process.env.WORKSPACE_RUNNER_PORT||8090)+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))" || exit 1
CMD ["node", "/app/server.js"]
