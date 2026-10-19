# Pinned patch + Debian release, identical across every Python service image.
FROM python:3.12.15-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258

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
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8095", "--workers", "1"]
