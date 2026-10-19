# Multi-stage build for the core Spring Boot deployables (the Maven reactor
# in core/pom.xml). This containerizes what was previously always run
# directly via `mvn spring-boot:run` (see run-core.ps1) - "Stage 8" of the
# original MVP Build Plan, deliberately deferred until now (see
# Agentic-AI-Platform-Microservices-Istio-Docker-Build-Plan.md v2, Phase 0.2).
#
# MODULE selects which executable assembly to package - `app` (the API
# server, default) or `app-worker` (the dedicated Temporal workflow worker).
# Same reactor, same config (app-config), different main class.
# `app-jobs` is the background-jobs shape (scheduled sweeps/pollers, no REST;
# docs/plans/CORE-JOBS-DEPLOYABLE.md).
#
# MCP_TOOLCHAIN selects what core can spawn as local STDIO MCP servers
# (essential vs plug-and-play split, 2026-09-29 - the full toolchain made the
# image ~6 GB, almost none of it Java):
#   none (default) - slim JRE image. No npm/npx/uv/Playwright/Chrome. Carries
#                    only the bare `node` binary (~100 MB, no npm) so the
#                    trusted mcp-stdio-proxy relay can forward STDIO MCP
#                    servers - tenant-registered AND the application-mcp.yml
#                    built-ins (StaticMcpStdioSidecarRouting) - to the
#                    mcp-stdio-runner service (MCP_STDIO_SIDECAR_ENABLED=true;
#                    browser tools need the mcp-stdio-runner-browser image).
#                    Default profiles leave out `mcp`; with `mcp` on and the
#                    runner off, core fails startup with a clear message
#                    (McpStdioLauncherPreflight) instead of cryptic spawn errors.
#   full           - exactly the pre-split runtime: NodeSource Node 22 + npm,
#                    uv/uvx, Playwright Chromium, @playwright/mcp chrome-for-
#                    testing, server-puppeteer + its Chrome, `mcp` in the
#                    default profiles. For deployments that cannot run the
#                    runner (published as `core-full`).
# The toolchain lives in its own stage (mcp-toolchain-*), below the jar, so
# the heavy layers are shared by every MODULE and never rebuilt on a source
# change.
#
# Build from the repo root (build context needs core/ only):
#   docker build -f core/Dockerfile -t vegaduta-core:local core
#   docker build -f core/Dockerfile --build-arg MODULE=app-worker -t vegaduta-core-worker:local core
#   docker build -f core/Dockerfile --build-arg MCP_TOOLCHAIN=full -t vegaduta-core-full:local core
# Or via docker-compose (see deploy/docker-compose.yml's `core` service and
# docker-compose.domain.yml's `core-worker` service).

# ENABLE_AOT_CACHE (opt-in, default false = image identical to before):
# "true" adds an AppCDS training run and starts the JVM from the resulting
# class-data-sharing archive - boot-time only, no effect on request handling.
# See the `runtime-aot-true` stage at the bottom. Must be declared before the
# first FROM because it selects the final stage.
ARG ENABLE_AOT_CACHE=false
# none | full - see the header. Also selects a stage, so it is declared here.
ARG MCP_TOOLCHAIN=none

# ---- Build stage --------------------------------------------------------
FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
ARG MODULE=app
WORKDIR /build

# Retry transient download failures (a dropped TLS handshake to Maven Central once failed a
# ~6-minute in-image dependency download on a laptop build): the dependency tree is hundreds of
# artifacts and a single reset should cost a retry, not the whole image build.
ENV MAVEN_OPTS="-Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.http.retryHandler.requestSentEnabled=true -Dmaven.wagon.httpconnectionManager.ttlSeconds=25"

# Copy every module's pom.xml first (not the source) so Docker's layer cache
# can skip re-downloading the entire dependency tree on every source-only
# change - only a pom.xml edit invalidates this layer. Every <module> of pom.xml needs a line
# here (scripts/ci/dockerfile-poms.sh checks it): a missing one makes the go-offline step below
# fail silently and the final package step re-download everything.
COPY pom.xml .
COPY admin/pom.xml admin/
COPY agents/pom.xml agents/
COPY agents-jobs/pom.xml agents-jobs/
COPY agents-tools/pom.xml agents-tools/
COPY agents-turn/pom.xml agents-turn/
COPY agents-browser/pom.xml agents-browser/
COPY agents-budget/pom.xml agents-budget/
COPY agents-chatport/pom.xml agents-chatport/
COPY agentspi/pom.xml agentspi/
COPY agents-interop/pom.xml agents-interop/
COPY agents-studio/pom.xml agents-studio/
COPY approval/pom.xml approval/
COPY audit/pom.xml audit/
COPY billing/pom.xml billing/
COPY chat/pom.xml chat/
COPY codeintel/pom.xml codeintel/
COPY documentgen/pom.xml documentgen/
COPY crypto/pom.xml crypto/
COPY crypto-kms/pom.xml crypto-kms/
COPY secrets/pom.xml secrets/
COPY devices/pom.xml devices/
COPY reliability/pom.xml reliability/
COPY eventbus/pom.xml eventbus/
COPY google/pom.xml google/
COPY graphmemory/pom.xml graphmemory/
COPY guardrails/pom.xml guardrails/
COPY imagegen/pom.xml imagegen/
COPY elevenlabs/pom.xml elevenlabs/
COPY knowledge/pom.xml knowledge/
COPY mcp/pom.xml mcp/
COPY mcpserver/pom.xml mcpserver/
COPY omnichannel/pom.xml omnichannel/
COPY omnichannel-social/pom.xml omnichannel-social/
COPY platform/pom.xml platform/
COPY providers/pom.xml providers/
COPY quality/pom.xml quality/
COPY rl/pom.xml rl/
COPY sarvam/pom.xml sarvam/
COPY sdlc/pom.xml sdlc/
COPY security/pom.xml security/
COPY sharing/pom.xml sharing/
COPY speech/pom.xml speech/
COPY storage/pom.xml storage/
COPY dbauth/pom.xml dbauth/
COPY tenancy/pom.xml tenancy/
COPY voice/pom.xml voice/
COPY workflow-expression/pom.xml workflow-expression/
COPY workflow/pom.xml workflow/
COPY workflow-portability/pom.xml workflow-portability/
COPY templates/pom.xml templates/
COPY app-config/pom.xml app-config/
COPY app/pom.xml app/
COPY app-worker/pom.xml app-worker/
COPY app-jobs/pom.xml app-jobs/
COPY json/pom.xml json/
COPY payments/pom.xml payments/
COPY brief/pom.xml brief/
# Verified against core/pom.xml's <modules> block (2026-07-29 reliability
# pass): the reactor has 34 modules, this list had only 28 - mcpserver,
# sharing, eval (since merged into quality), quality, voice, and rl (all added after this COPY list was
# last updated) were missing, so `dependency:go-offline` below could never
# warm their deps into this cache layer. The build was still CORRECT (COPY .
# below brings the real source, and go-offline's `|| true` means a partial
# resolve here never fails the build) - just slower: those six modules'
# dependencies re-resolved from the network on every single source-only
# rebuild instead of only on a pom.xml change, same as every other module
# below already gets.
RUN mvn -q -B dependency:go-offline || true

# Now copy real source and build the executable jar. maven.test.skip (not
# just -DskipTests) so a stale/mid-refactor test file can't block the image
# build - test correctness is verified separately by CI's own `mvn test`
# (.github/workflows/ci.yml, stack.yml), not by this packaging step.
COPY . .
RUN mvn -q -B -pl ${MODULE} -am -Dmaven.test.skip=true package

# driver-bundle (the Playwright-Java runtime, ~190 MB inside the jar) embeds
# a node binary for mac, mac-arm64, linux, linux-arm64 AND win32_x64 - five
# platforms when a Linux container can only ever execute the linux ones.
# Unpack, drop the mac/windows drivers, repack: every image sheds ~130 MB and
# browser tools behave identically. Dev runs from `mvn package` outside
# Docker are untouched. zip -0 keeps the entry STORED, which Spring Boot's
# nested-jar loader requires for BOOT-INF/lib. zip/unzip live only in the
# build stage - they never reach the runtime image.
RUN apt-get update && apt-get install -y --no-install-recommends zip unzip \
    && rm -rf /var/lib/apt/lists/* \
    && JAR=$(echo /build/${MODULE}/target/*.jar) \
    && DRV=$(unzip -Z1 "$JAR" 'BOOT-INF/lib/driver-bundle-*.jar' | head -n1) \
    && if [ -n "$DRV" ]; then \
         mkdir -p /tmp/drvx /tmp/drvin \
         && unzip -oq "$JAR" "$DRV" -d /tmp/drvx \
         && unzip -oq "/tmp/drvx/$DRV" -d /tmp/drvin \
         && rm -rf /tmp/drvin/driver/mac /tmp/drvin/driver/mac-arm64 /tmp/drvin/driver/win32_x64 \
         && rm "/tmp/drvx/$DRV" \
         && (cd /tmp/drvin && zip -qr "/tmp/drvx/$DRV" .) \
         && (cd /tmp/drvx && zip -0 -q "$JAR" "$DRV") \
         && rm -rf /tmp/drvx /tmp/drvin; \
       fi

# ---- Runtime base (shared by both MCP_TOOLCHAIN variants) ----------------
FROM eclipse-temurin:21.0.12.1_1-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b AS runtime-base
WORKDIR /app

# curl: nothing in the app needs it - it's here solely so this image's own
# HEALTHCHECK (and deploy/docker-compose.yml's healthcheck: block, which
# runs inside this same container) can hit /actuator/health/readiness
# without pulling in a second base image or a JVM-based check. ~1MB, cached
# as its own layer so it never gets re-fetched by an app-source-only
# rebuild. See Platform-Tech-Stack-Audit-2026.md P1 (infra/ops section).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root runtime user - defense-in-depth, no functional dependency on it.
# A real (writable) home directory IS load-bearing now: npx and uvx cache
# downloaded server packages under $HOME (~/.npm, ~/.cache/uv) - with the
# old homeless system user every stdio spawn would re-fail on cache writes.
RUN groupadd -r agentic && useradd -r -g agentic -m -d /home/agentic agentic
ENV HOME=/home/agentic

# ---- MCP_TOOLCHAIN=none: relay-only `node` ------------------------------
# Only the node BINARY from the official image (same major the runner uses):
# no npm/npx/corepack, so nothing here can fetch or run an MCP package - it
# exists solely to execute /opt/mcp-stdio-proxy/proxy.js (zero-dependency,
# see core/mcp-stdio-proxy/framing.js). The official binary targets glibc
# >= 2.28 and links only libc/libstdc++/libgcc_s, all present in jammy.
FROM node:22.23.3-bookworm-slim@sha256:c3de60bf2f9dd0ac6370e6117950ff62d6e339527e7472301c9c78a017978392 AS node-relay

FROM runtime-base AS mcp-toolchain-none
COPY --from=node-relay /usr/local/bin/node /usr/local/bin/node
# No `mcp` here: the static STDIO built-ins need the runner (or core-full).
# Add `mcp` together with MCP_STDIO_SIDECAR_ENABLED=true - the deploy/
# compose `tools`/`browser` profiles and the helm chart do exactly that.
ENV SPRING_PROFILES_ACTIVE=security,knowledge,workflow,graph-memory

# ---- MCP_TOOLCHAIN=full: today's in-core STDIO toolchain -----------------
FROM runtime-base AS mcp-toolchain-full

# Node.js 22 LTS (npx) + uv (uvx) - the launchers dynamic STDIO MCP servers
# actually use (2026-07-25, direct user report: registry stdio servers -
# e.g. AI music generation - stayed ERROR). A stdio MCP server is a LOCAL
# SUBPROCESS of this container, and nearly every real-world one starts via
# `npx -y <pkg>` (Node) or `uvx <pkg>` (Python). Jammy's apt nodejs is v12
# (too old for the MCP ecosystem), so node + npm are COPIED from the
# digest-pinned official image above (the same binary node-relay uses; it
# targets glibc >= 2.28, present in jammy) and uv/uvx from astral's
# digest-pinned image - no `curl | sh`, nothing fetched at build time that
# is not pinned by digest (supply-chain review 2026-10-10). Without these
# runtimes every stdio connection dies at spawn no matter what the Java side
# does. Remote (SSE/streamable-HTTP) servers never needed this.
COPY --from=node-relay /usr/local/bin/node /usr/local/bin/node
COPY --from=node-relay /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && node --version && npm --version
COPY --from=ghcr.io/astral-sh/uv:0.13.0@sha256:cdc6093146eb3ff6a40107b38f008b789e050e77ad87865e381d9917da55a168 /uv /uvx /usr/local/bin/

# Playwright MCP (playwright-vision, application-mcp.yml's stdio connection)
# needs a REAL Chromium binary baked into the image - live prod incident
# 2026-08-09: relying on `npx -y @playwright/mcp` to fetch a browser at
# runtime failed with "Chromium distribution 'chrome' is not found at
# /opt/google/chrome/chrome" (the server defaulted to hunting for the
# BRANDED Chrome channel, which no Docker base image ships, and an ephemeral
# npx install doesn't reliably run postinstall browser-download hooks
# either way). `--with-deps` also installs the OS-level libs (fonts, X11
# stubs) headless Chromium needs to actually launch - that apt-get requires
# root, so this MUST run before `USER agentic` below.
# PLAYWRIGHT_BROWSERS_PATH pins an explicit shared cache dir so the runtime
# `npx`-spawned server (running as `agentic`, not root) finds exactly what
# this build step installed instead of guessing a per-user default path
# that may not match.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers
# This RUN still executes as root (HOME=/home/agentic above only points
# npm/npx at that directory, it doesn't change the executing uid), so npx
# fetching the `playwright` CLI package leaves root-owned files under
# /home/agentic/.npm - the exact directory every OTHER stdio MCP server's
# runtime `npx -y <pkg>` (filesystem/memory/sequential-thinking/puppeteer)
# also caches into as the non-root `agentic` user. Without the chown below,
# every one of those then fails at spawn with npm's "EACCES...cache folder
# contains root-owned files" - confirmed live in prod immediately after this
# Chromium install step was first added, 2026-08-09.
#
# SECOND live incident, same day: the plain `playwright` CLI package (used
# below purely for its `--with-deps` apt-get pass - fonts/X11 libs every
# Chromium-family browser needs) resolved to playwright-core 1.62.1 and
# downloaded browser revision chromium-1234, but application-mcp.yml's
# runtime `npx -y @playwright/mcp@latest` resolves ITS OWN, INDEPENDENT
# playwright-core dependency (1.63.0-alpha as of this incident) and expects
# a DIFFERENT revision (chromium-1237, under the label "chrome-for-testing")
# - two unpinned packages drifting to two different browser builds, so the
# server found no browser at the path it actually looked for. Fixed by
# pinning @playwright/mcp to one exact version here AND in
# application-mcp.yml (must stay in sync - bump both together, deliberately,
# never let either float to `@latest`) and installing the browser via THAT
# package's own installer, guaranteeing the binary on disk always matches
# what the pinned runtime package will ask for.
# playwright@1.63.0-alpha-2026-08-05 is exactly the version @playwright/mcp@0.0.79 depends on.
RUN npx --yes playwright@1.63.0-alpha-2026-08-05 install --with-deps chromium \
    && npx --yes @playwright/mcp@0.0.79 install-browser chrome-for-testing \
    # server-puppeteer pre-warm (2026-08-19 incident): unlike playwright-vision
    # above, puppeteer's package AND its browser were still fetched at runtime
    # by the first `npx -y @modelcontextprotocol/server-puppeteer` - a cold
    # start that reliably blew application-mcp.yml's 60s request-timeout, so
    # the client's lazy init failed every attempt ("Client failed to
    # initialize listing tools") and, before AgentToolProvider's per-client
    # isolation fix, took every other static server's tools down with it.
    # `npm exec` seeds the npx cache without STARTING the server (running the
    # package's own binary would listen on stdio and hang this build forever);
    # `puppeteer browsers install chrome` fetches the exact Chrome-for-Testing
    # build pinned by puppeteer@23.11.1 (server-puppeteer@2025.5.12's own
    # dependency - upstream deprecated, so these versions are frozen; bump
    # both together if the connection block ever changes) into
    # $HOME/.cache/puppeteer, which the chown below hands to the runtime user.
    && npm exec --yes --package=@modelcontextprotocol/server-puppeteer@2025.5.12 -- node -e "process.exit(0)" \
    && npx --yes puppeteer@23.11.1 browsers install chrome \
    && chown -R agentic:agentic "$PLAYWRIGHT_BROWSERS_PATH" /home/agentic

# server-puppeteer (application-mcp.yml's `puppeteer` stdio connection)
# already ships a Docker-aware launch path (docker_args = {headless: true,
# args: ["--no-sandbox", "--single-process", "--no-zygote"]}) gated on
# `process.env.DOCKER_CONTAINER` - see its own dist/index.js. Without this,
# it uses its default launch args, which assume a real user-namespace
# sandbox the non-root `agentic` user in THIS container does not have,
# and fails at browser launch with Chromium's own "No usable sandbox!"
# FATAL error (live prod incident, 2026-08-09) - not a missing-binary or
# missing-library problem like the two fixes above, a permissions one.
# ProcessBuilder (Spring AI's STDIO transport) inherits the JVM's own
# environment by default, so setting this here is sufficient - no per-
# connection `env:` block needed in application-mcp.yml.
ENV DOCKER_CONTAINER=true

# Same profile default as application.yml's spring.profiles.active - spelled
# out here too so it survives even if someone overrides SPRING_PROFILES_ACTIVE
# without realizing what the default was covering.
# `mcp` added 2026-08-07 (see deploy/docker-compose.yml's SPRING_PROFILES_ACTIVE
# comment for the root cause) - without it, application-mcp.yml's STDIO client
# wiring (filesystem/memory/sequential-thinking/puppeteer/playwright-vision)
# never loads and AgentToolProvider's mcpToolCallbackProvider bean never
# exists, so every statically-wired MCP server is unreachable to agents no
# matter what a tenant enables in the UI. This ENV is only a fallback for
# running this image outside compose - deploy/docker-compose*.yml set
# SPRING_PROFILES_ACTIVE explicitly and take precedence.
ENV SPRING_PROFILES_ACTIVE=security,knowledge,workflow,graph-memory,mcp

# ---- Runtime stage -------------------------------------------------------
FROM mcp-toolchain-${MCP_TOOLCHAIN} AS runtime
WORKDIR /app

# Re-declared HERE, not at the top of the stage: ARGs don't cross stage
# boundaries, and every RUN after an ARG gets it in its environment, so a
# stage-top ARG MODULE made the Node/Playwright/Chromium layers miss
# the cache for each module - app, app-jobs and app-worker then each carried
# their own ~5 GB copy of identical layers (found by the kind install test).
ARG MODULE=app
COPY --from=build /build/${MODULE}/target/*.jar app.jar

# mcp-stdio-proxy (MCP-STDIO-SANDBOXING, 2026-08-05, see
# docs/architecture/MCP-STDIO-SANDBOXING-DESIGN.md §8) - a small, fixed, TRUSTED relay script,
# NOT the real MCP server code. When mcp-stdio-sidecar.enabled=true,
# DynamicMcpConnectionManager.wrapForSidecar spawns THIS instead of the real
# `npx`/`uvx <pkg>` - it forwards bytes to the mcp-stdio-runner sidecar,
# which spawns the real (potentially untrusted) package inside itself
# instead. Root-owned (COPY default, no chown) and left out of the
# `chown -R agentic:agentic /app` below - the agentic user can read+execute
# it but not modify it, same "tenant can't tamper with trusted relay code"
# posture tool-sandbox's own harness uses for its runner.
COPY mcp-stdio-proxy/framing.js mcp-stdio-proxy/proxy.js /opt/mcp-stdio-proxy/

# mcp-data is MCP_FILESYSTEM_ROOT's default target (see core/.env.example) -
# create it here so the built-in filesystem MCP tool has somewhere writable
# even before any volume is mounted over it.
RUN mkdir -p /app/mcp-data && chown -R agentic:agentic /app

# Default root for app.storage.type=local (the `lite` profile,
# APP_STORAGE_LOCAL_PATH=/data/storage). Unused with the default S3 storage;
# mount a persistent volume here when running lite.
RUN mkdir -p /data/storage && chown -R agentic:agentic /data

USER agentic
EXPOSE 8080

# SPRING_PROFILES_ACTIVE's image default is set by the mcp-toolchain-* stage
# above (with `mcp` only in the full variant). deploy/compose and the helm
# charts always set it explicitly and take precedence.
# Build stamp for GET /api/platform/version (PlatformVersionService reads these
# env vars). Declared this late so a new SHA never invalidates the browser
# layers above. CI passes both; a local compose build leaves them empty and
# the console shows "not stamped" rather than a guess.
ARG GIT_SHA=
ARG BUILD_TIME=
ENV GIT_SHA=$GIT_SHA \
    BUILD_TIME=$BUILD_TIME

# Readiness, not liveness - "DB/Redis/Keycloak reachable", not just "JVM up"
# (see application.yml's management.endpoint.health.probes block). A
# generous start-period since the Maven-built jar boots 24 modules plus
# Flyway migrations on first start. Redundant with, not a replacement for,
# deploy/docker-compose.yml's own healthcheck: block on the core service -
# this one covers `docker run` used standalone, that one drives Compose's
# own depends_on/condition wiring.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -f http://localhost:8080/actuator/health/readiness || exit 1

# -Djava.net.preferIPv4Stack=true: confirmed live 2026-07-26 - outbound calls
# to generativelanguage.googleapis.com (and likely other external providers)
# were failing with UnresolvedAddressException. Root cause traced to this
# container's DNS resolution returning ONLY AAAA (IPv6) records for that
# hostname (`getent hosts generativelanguage.googleapis.com` - zero A
# records), while this container/host has no usable IPv6 egress - a common
# Docker-on-Windows-Desktop gap, not a code bug. The JVM's default dual-stack
# behavior then picks the IPv6 address DNS handed it and the connection dies
# at the socket layer. Forcing IPv4-only tells the JVM to never even attempt
# the unusable IPv6 address, using the working A record instead. Safe: every
# provider/service this platform talks to (Google, OpenAI, Keycloak, Postgres,
# Redis, etc.) is reachable over IPv4; nothing here depends on IPv6.
ENTRYPOINT ["java", "-Djava.net.preferIPv4Stack=true", "-jar", "/app/app.jar"]

# ---- Optional AppCDS archive (ENABLE_AOT_CACHE=true) ----------------------
# BuildKit (default since Docker 23) only builds the stage the final FROM
# selects - the legacy builder would build every stage. With the
# default (false) none of this runs and the image is exactly `runtime`.
#
# Why AppCDS and not the JDK 24+ AOT cache (JEP 483, -XX:AOTCache): the
# runtime base image is JDK 21, which only has (dynamic) AppCDS. Switch to
# -XX:AOTMode=record/-XX:AOTConfiguration + -XX:AOTCache once the runtime
# image is JDK 24+.
#
# Steps:
#  1. Spring Boot 3.3+ `jarmode=tools extract` replaces the fat jar with the
#     same-named thin launcher jar plus lib/ (CDS cannot map classes out of
#     nested jars). ENTRYPOINT is unchanged: `-jar /app/app.jar` still works.
#  2. Training run with -Dspring.context.exit=onRefresh, dumping every loaded
#     class into /app/app.jsa at JVM exit. No database/Redis/Keycloak exists
#     at build time, so the context refresh stops at the first bean that needs
#     one; the JVM still writes the archive at exit (a partial archive is
#     valid - it only covers the classes loaded up to that point). The step
#     fails the build if no archive was produced, so a broken opt-in is loud.
#  3. JDK_JAVA_OPTIONS makes the `java` launcher pick the archive up without
#     touching ENTRYPOINT. -Xshare:auto (the default) means a stale/invalid
#     archive is ignored rather than fatal. A deployment that sets its own
#     JDK_JAVA_OPTIONS must append this flag itself.
FROM runtime AS runtime-aot-false

FROM runtime AS runtime-aot-true
ARG AOT_TRAINING_PROFILES=lite
RUN set -eux; \
    cd /app; \
    java -Djarmode=tools -jar app.jar extract --destination /tmp/extracted; \
    rm app.jar; \
    mv /tmp/extracted/app.jar /app/app.jar; \
    mv /tmp/extracted/lib /app/lib; \
    rm -rf /tmp/extracted; \
    SPRING_PROFILES_ACTIVE="${AOT_TRAINING_PROFILES}" APP_STORAGE_LOCAL_PATH=/tmp/aot-storage \
        timeout 300 java -XX:ArchiveClassesAtExit=/app/app.jsa -Dspring.context.exit=onRefresh \
        -Djava.net.preferIPv4Stack=true -jar /app/app.jar > /tmp/aot-training.log 2>&1 || true; \
    tail -n 5 /tmp/aot-training.log || true; \
    rm -rf /tmp/aot-storage /tmp/aot-training.log; \
    test -s /app/app.jsa
ENV JDK_JAVA_OPTIONS="-XX:SharedArchiveFile=/app/app.jsa -Xlog:cds=off -Xlog:cds+dynamic=off"

FROM runtime-aot-${ENABLE_AOT_CACHE}
