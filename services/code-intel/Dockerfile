# Pinned patch + Debian release, identical across every Python service image.
FROM python:3.12.15-slim-trixie@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1

# git is required for clone/fetch + incremental diff detection
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv/code-intel
# Hash-locked install: requirements.lock is the full transitive tree with
# sha256 hashes (generated from requirements.txt by
# services/lock-requirements.sh), so a rebuild can neither drift to other
# versions nor accept a substituted artifact.
COPY requirements.lock .
# --no-deps: the lock is the complete tree, and overrides.txt pins pillow past fastembed's
# declared range, which pip's resolver would otherwise refuse.
RUN pip install --no-cache-dir --require-hashes --no-deps -r requirements.lock

COPY codebase_rag ./codebase_rag
COPY app ./app

ENV CODE_INTEL_DATA_DIR=/data
VOLUME ["/data"]

# Security review 2026-07-27: this was the other real (always-on) service
# still running as root - core/Dockerfile creates `agentic`, web/web-admin
# create `nextjs`, this one had no USER directive at all.
#
# CAVEAT FOR WHOEVER REDEPLOYS THIS: deploy/compose/docker-compose.yml mounts a
# named volume at /data (agentic-code-intel-data). If that volume already
# has files written by the OLD root-running container, this new non-root
# user will get EACCES on them until ownership is fixed - a Dockerfile
# chown only affects the image's own layer, not a pre-existing external
# volume's contents. Before rolling this out: either (a) `docker exec
# agentic-code-intel chown -R codeintel:codeintel /data` once against the
# live volume before restarting with this image, or (b) accept a fresh
# volume (code-intel's /data is a rebuildable RAG index over the checked-
# out repos, not a source of truth - safe to lose and let it reindex).
# NOT applied live by this review - build+redeploy is the orchestrating
# session's call.
RUN groupadd -r codeintel && useradd -r -g codeintel codeintel \
    && mkdir -p /data && chown -R codeintel:codeintel /data /srv/code-intel
USER codeintel

EXPOSE 8090
# Single worker on purpose: per-repo locks are in-process. Scale by
# running one container per shard of repos, not by adding workers.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090", "--workers", "1"]
