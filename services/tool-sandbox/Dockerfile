# tool-sandbox - compiles, verifies and runs tenant-authored Java/Python/
# JavaScript/TypeScript tools.
#
# Why this is its own image rather than something core does in-process:
#   * core's runtime image is a JRE, so it has no `javac` and no `python3`;
#   * core holds the datasource credentials, APP_ENCRYPTION_KEY, Keycloak
#     secrets and every tenant's provider keys, and bridges five Docker
#     networks including the data tier. Running tenant code in that process is
#     the exact data-leak scenario this feature has to avoid.
# This image holds no platform credentials at all, which is the primary
# control; everything else is defence in depth.

# ---------------------------------------------------------------- harness ---
# The trusted Java side, built once at image build time. `vega-api.jar` is what
# tenant code compiles against; `harness.jar` is the loader that invokes it and
# is deliberately NOT on the tenant's compile classpath.
FROM eclipse-temurin:24.0.2_12-jdk-noble@sha256:dacac8e9a0df0d2bd24e702b4431132875c249930b70555ebd7ca285b5bee684 AS harness

WORKDIR /build
COPY harness/java ./java
RUN set -eux; \
    mkdir -p out/api out/internal lib; \
    javac -nowarn -d out/api java/vega/Log.java java/vega/Secrets.java; \
    javac -nowarn -d out/internal -cp out/api java/vega/internal/Json.java java/vega/internal/Harness.java; \
    (cd out/api && jar --create --file /build/lib/vega-api.jar .); \
    (cd out/internal && jar --create --file /build/lib/harness.jar .)

# ---------------------------------------------------------------- runtime ---
# Same pinned CPython as the rest of the platform's Python services (3.12), so
# tenant Python tools, the harness and the API all run on one interpreter and
# the module allowlist in app/policy.py is checked against the stdlib that
# actually ships. (Previously this stage was the temurin jammy image with apt's
# python3 - 3.10 - the only 3.10 service in the repo.)
# The Node runtime for the javascript/typescript languages: the same pinned
# 22.x tag every other Node-based service uses (services/workspace-runner,
# services/mcp-stdio-runner).
FROM node:26.12.1-trixie-slim@sha256:94816674c62774da0d099a947bf4613b9e1972a2733abd0d0cd80e795f8411d0 AS node

FROM python:3.14.8-slim-trixie@sha256:fbecd461fc72d21b2143c6eaba25143738079154a6d0ea3da3aec724f50eed4a

ENV DEBIAN_FRONTEND=noninteractive

# A JDK, not a JRE: `javac` is the whole point. Copied from the harness stage
# so the runtime JDK is byte-for-byte the one the harness jars were built with
# (one pinned temurin tag, one place). A noble-built JDK runs on trixie: it
# needs glibc >= 2.35 (trixie ships 2.41) and bundles its own zlib/libjpeg;
# `java -version` / `javac -version` below make the build fail if that ever
# stops being true.
COPY --from=harness /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=/opt/java/openjdk/bin:$PATH

# util-linux supplies `setpriv` and `prlimit`. Both are load-bearing: they are
# how a child gets its uid dropped and its rlimits applied without CPython
# running arbitrary code between fork() and exec(), which is a documented
# deadlock hazard in a threaded server. See app/proc.py. tzdata backs the
# `zoneinfo` module tenant tools may import (the jammy base had it; slim does
# not).
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        util-linux \
        ca-certificates \
        tzdata; \
    rm -rf /var/lib/apt/lists/*; \
    command -v setpriv; \
    command -v prlimit; \
    /opt/java/openjdk/bin/java -version; \
    /opt/java/openjdk/bin/javac -version

# The Node runtime tenant JavaScript/TypeScript tools run under. Only the
# binary is copied - there is no npm and no node_modules on purpose: tenant
# tools are single-file programs with no package ecosystem (the identifier
# policy denies `require`/`import` of anything at all).
# Node 26+ links libatomic on arm64 and (since 26.11) on amd64 too;
# python:slim ships libstdc++/libgcc but not libatomic, so install it for the
# copied binary on every arch. core/Dockerfile does the same unconditionally.
COPY --from=node /usr/local/bin/node /usr/local/bin/node
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends libatomic1; \
    rm -rf /var/lib/apt/lists/*; \
    /usr/local/bin/node --version

# Two identities, and the split is the point:
#   `sandbox` (uid 5000) owns nothing and is unused today, reserved for running
#       the API unprivileged once seccomp is not needed;
#   `sbxrun`  (uid 5001) is what tenant code executes as. It owns no file in
#       this image and no file this service writes, so tenant code cannot alter
#       the harness jars or the compiled-artifact cache - not for its own
#       tenant, and not for anyone else's.
RUN set -eux; \
    groupadd -r -g 5000 sandbox; \
    useradd -r -u 5000 -g sandbox -m -d /home/sandbox sandbox; \
    groupadd -r -g 5001 sbxrun; \
    useradd -r -u 5001 -g sbxrun -M -d /nonexistent -s /usr/sbin/nologin sbxrun

WORKDIR /opt/tool-sandbox

# Hash-locked: every wheel is verified against requirements.lock
# (`services/lock-requirements.sh tool-sandbox` regenerates it from
# requirements.txt).
COPY requirements.lock ./
RUN pip install --no-cache-dir --require-hashes -r requirements.lock

COPY --from=harness /build/lib ./lib
COPY app ./app
COPY harness/py_harness.py ./harness/py_harness.py

# Everything the service ships is root-owned and read-only to the run uid.
RUN set -eux; \
    chown -R root:root /opt/tool-sandbox; \
    chmod -R a=rX /opt/tool-sandbox; \
    mkdir -p /var/sandbox/work /var/sandbox/cache; \
    chown root:root /var/sandbox/work /var/sandbox/cache; \
    chmod 0755 /var/sandbox/cache; \
    chmod 0711 /var/sandbox/work

# Absolute paths, because the child's environment is scrubbed down to a
# minimal PATH and the JDK does not live on it.
ENV TOOL_SANDBOX_JAVAC=/opt/java/openjdk/bin/javac \
    TOOL_SANDBOX_JAVA=/opt/java/openjdk/bin/java \
    TOOL_SANDBOX_PYTHON=/usr/local/bin/python3 \
    TOOL_SANDBOX_NODE=/usr/local/bin/node \
    TOOL_SANDBOX_HARNESS_ROOT=/opt/tool-sandbox \
    TOOL_SANDBOX_WORK_ROOT=/var/sandbox/work \
    TOOL_SANDBOX_CACHE_ROOT=/var/sandbox/cache \
    TOOL_SANDBOX_RUN_UID=5001 \
    TOOL_SANDBOX_RUN_GID=5001 \
    TOOL_SANDBOX_REQUIRE_PRIVILEGE_DROP=true \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

EXPOSE 8095

# The image has no curl and the service has one unauthenticated route, so this
# uses the same stdlib-urllib probe the sidecar and code-intel services use.
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=20s \
    CMD /usr/local/bin/python3 -c "import urllib.request; urllib.request.urlopen('http://localhost:8095/health', timeout=3)"

# trivy:ignore:DS-0002 Runs as root ONLY so it can drop to uid 5001 for tenant code - `setpriv`
# needs CAP_SETUID/CAP_SETGID, and SIGKILLing the uid-5001 child on timeout
# needs CAP_KILL. This is the documented exception to "every service runs
# non-root": the compose entry and the helm chart drop every capability except
# SETUID/SETGID/KILL and set no-new-privileges. The app refuses to start when
# CAP_KILL is missing (see proc.verify_kill_capability) unless
# TOOL_SANDBOX_ALLOW_MISSING_CAP_KILL=true. A single worker keeps the
# per-uid RLIMIT_NPROC budget predictable; concurrency is bounded inside the
# app by a semaphore instead.
CMD ["/usr/local/bin/python3", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8095", "--workers", "1"]
