# Pinned patch + Debian release, identical across every Python service image.
FROM python:3.14.8-slim-trixie@sha256:fbecd461fc72d21b2143c6eaba25143738079154a6d0ea3da3aec724f50eed4a

# No system packages needed: this is an LLM-driven service (no git, no OCR
# binary, no CUDA). Deliberately torch-free - see requirements.txt / README.

WORKDIR /srv/rl-trainer
# Hash-locked install: requirements.lock is the full transitive tree with
# sha256 hashes (generated from requirements.txt by
# services/lock-requirements.sh), so a rebuild can neither drift to other
# versions nor accept a substituted artifact.
COPY requirements.lock .
RUN pip install --no-cache-dir --require-hashes -r requirements.lock

COPY app ./app

ENV RL_TRAINER_DATA_DIR=/data

# Non-root like the other always-on services. uid/gid 10001 match the Helm
# chart's podSecurityContext. The only write target is RL_TRAINER_DATA_DIR
# (/data: tenants/ artifacts, HF/fastembed caches in compose) plus /tmp.
# Upgrading a compose volume created by the old root image: chown it once,
#   docker compose run --rm -u 0 --entrypoint chown rl-trainer -R 10001:10001 /data
RUN groupadd -r -g 10001 rltrainer && useradd -r -u 10001 -g rltrainer -d /srv/rl-trainer rltrainer     && mkdir -p /data && chown -R rltrainer:rltrainer /data
VOLUME ["/data"]
USER 10001:10001

EXPOSE 8095
# Single worker: matches the other sidecars' posture. The optimizer/judge are
# stateless LLM calls, so scale by running more replicas, not more workers.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8095/health', timeout=3)" || exit 1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8095", "--workers", "1"]
