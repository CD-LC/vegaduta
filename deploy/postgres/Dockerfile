# Custom Postgres image with
# BOTH pgvector (already relied on by knowledge_chunks/agent_memory) AND
# Apache AGE (new: graph memory, see V44__graph_memory.sql), since no
# official image ships both together. Built FROM the standard postgres:16
# image (not pgvector/pgvector:pg16 - that image doesn't expose the build
# toolchain needed to also compile AGE, so this compiles pgvector from
# source too, pinned to the same version the previous prebuilt image shipped,
# rather than mixing a prebuilt image with a second manual extension build).
#
# FALLBACK, stated explicitly: if this image ever fails to build (a pinned
# release disappearing, an Ubuntu/Postgres base image change breaking the
# build toolchain, etc.), revert deploy/docker-compose.yml's `postgres`
# service from `build: ./postgres` back to `image: pgvector/pgvector:pg16` -
# the one line docker-compose.yml had before this stage. That reversion loses
# ONLY the graph-memory feature (the "graph-memory" Spring profile simply has
# nothing to connect to); every other part of the platform, including
# pgvector-backed knowledge/agent memory, is completely unaffected either way.
#
# Versions pinned deliberately (not "latest") so a rebuild six months from
# now doesn't silently pick up a breaking change in either project - and each
# clone is verified against a pinned COMMIT, because a git tag can be moved
# (INF-016):
#   pgvector v0.8.0 @ 2627c5ff775ae6d7aef0c430121ccf857842d2f2 - stable
#     release compatible with Postgres 16.
#   Apache AGE PG16/v1.6.0-rc0 @ 2db2f060c4c9265a14d40f007eb8c56febf31e4c.
#     Re-checked 2026-09-30 with `git ls-remote --tags https://github.com/apache/age.git`:
#     the only PG16 tags are PG16/v1.5.0-rc0 and PG16/v1.6.0-rc0 - there is NO
#     "GA" (non-rc) PG16 tag. Apache AGE names every per-PG-major release tag
#     "PG<n>/v<x>-rc0" and publishes the release from it, so despite the suffix
#     this is the 1.6.0 release for PG16, not a separate pre-release channel
#     (INF-015). An earlier version of this Dockerfile pinned PG16/v1.5.0
#     (no -rc0), which does not exist and fails with "Remote branch not
#     found" - kept this note so that mistake isn't repeated.
#   Both tags are lightweight (ls-remote shows no peeled ^{} entry), so the
#   tag SHA IS the commit SHA checked below.
# Re-pin tag AND commit together (and re-test) rather than bumping blindly.

# BASE_IMAGE selects the flavour; the extension build is identical for both:
#   postgres:16.x-trixie@sha256 (default)  Compose tiers (official image entrypoint, initdb)
#   ghcr.io/cloudnative-pg/postgresql:16-system-trixie   Helm chart deploy/helm/charts/postgres (CloudNativePG
#                                          operand, includes barman-cloud; pass FINAL_USER=26,
#                                          the CNPG postgres uid)
# The release workflow publishes both: ghcr.io/<owner>/postgres:16 and :16-pgvector-age.
# Default is tag + digest pinned (INF-018; tag kept so Renovate/Dependabot can
# bump both). A --build-arg BASE_IMAGE=... still overrides it wholesale.
ARG BASE_IMAGE=postgres:16.15-trixie@sha256:ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d
# hadolint ignore=DL3006
FROM ${BASE_IMAGE}
ARG FINAL_USER=root
USER root
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

ARG PGVECTOR_VERSION=v0.8.0
ARG PGVECTOR_COMMIT=2627c5ff775ae6d7aef0c430121ccf857842d2f2
ARG AGE_VERSION=PG16/v1.6.0-rc0
ARG AGE_COMMIT=2db2f060c4c9265a14d40f007eb8c56febf31e4c
ARG PG_CONFIG=/usr/lib/postgresql/16/bin/pg_config

# pgvector's Makefile defaults to OPTFLAGS=-march=native, i.e. the CPU of whatever
# machine builds the image (a CI runner). On any host whose CPU lacks one of those
# instructions the backend dies with SIGILL the first time a vector is built or an
# HNSW index is created (the tenant baseline migration does), so core never boots.
# OPTFLAGS="" builds portable code; pgvector still picks its fast distance kernels
# at runtime (function multiversioning on x86-64), so there is no recall or
# correctness change. Apache AGE's Makefile sets no -march flag (it uses the
# distro pg_config CFLAGS), so it needs nothing. Keep OPTFLAGS="" on both make calls.

# Build deps are purged in the same layer, so apt version pins would only make
# the build break on every Debian point release without changing the image.
# hadolint ignore=DL3008
RUN apt-get update     && apt-get install -y --no-install-recommends         build-essential         git         postgresql-server-dev-16         libreadline-dev         zlib1g-dev         flex         bison         ca-certificates     && git clone --branch "${PGVECTOR_VERSION}" --depth 1 https://github.com/pgvector/pgvector.git /tmp/pgvector     && test "$(git -C /tmp/pgvector rev-parse HEAD)" = "${PGVECTOR_COMMIT}"     && make -C /tmp/pgvector PG_CONFIG="${PG_CONFIG}" OPTFLAGS=""     && make -C /tmp/pgvector install PG_CONFIG="${PG_CONFIG}" OPTFLAGS=""     && git clone --branch "${AGE_VERSION}" --depth 1 https://github.com/apache/age.git /tmp/age     && test "$(git -C /tmp/age rev-parse HEAD)" = "${AGE_COMMIT}"     && make -C /tmp/age PG_CONFIG="${PG_CONFIG}"     && make -C /tmp/age install PG_CONFIG="${PG_CONFIG}"     && pkglibdir="$("${PG_CONFIG}" --pkglibdir)"     && mkdir -p "${pkglibdir}/plugins"     && ln -sf ../age.so "${pkglibdir}/plugins/age.so"     && test -e "${pkglibdir}/plugins/age.so"     && rm -rf /tmp/pgvector /tmp/age     && apt-get purge -y --auto-remove build-essential git postgresql-server-dev-16 flex bison     && rm -rf /var/lib/apt/lists/*

# Loading AGE:
#   * compose (deploy/docker-compose.yml `command:`) and the Helm chart set
#     shared_preload_libraries=age, so every backend already has it loaded.
#     That is a runtime -c flag / cluster parameter, not baked in here -
#     postgresql.conf.sample is only consulted by initdb on a brand-new data
#     volume, so a Dockerfile-baked change would silently stop applying the
#     moment anyone reuses an existing agentic-postgres-data volume. A
#     command-line -c flag always applies, on every container start,
#     regardless of volume state.
#   * Where a session must LOAD it explicitly, a non-superuser (the app role)
#     has to use  LOAD '$libdir/plugins/age';  - plain LOAD 'age' is
#     superuser-only. The $libdir/plugins/age.so symlink created above is what
#     makes that work (Postgres lets non-superusers load only from plugins/).


# CloudNativePG base only (the official postgres image has no Python): its barman-cloud
# is pip-installed with pip removed afterwards, so a Python package with a published
# fix cannot be upgraded the usual way. Replace certifi in place from its hash-pinned
# wheel when the base ships an older one (2026-10-22: certifi 2026.10.18 in
# cloudnative-pg/postgresql:16.15-system-trixie carries critical GHSA-6q48-6c7w-9gw2,
# which the release's grype gate refuses). Drop this once the base image ships >= it.
ARG CERTIFI_VERSION=2026.10.19
ARG CERTIFI_SHA256=42d35340cc5b9ba78e818ddbff0c5b1c2b779de448a340fd1b989f5fa8670e1b
COPY replace-certifi.py /tmp/replace-certifi.py
RUN if command -v python3 >/dev/null 2>&1 && python3 -c 'import certifi' 2>/dev/null; then \
      python3 /tmp/replace-certifi.py "${CERTIFI_VERSION}" "${CERTIFI_SHA256}"; \
    fi \
    && rm -f /tmp/replace-certifi.py

HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=10 \
    CMD ["pg_isready", "-U", "postgres"]

USER ${FINAL_USER}USER ${FINAL_USER}
