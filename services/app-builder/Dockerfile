# Pinned patch + Debian release, identical across every Python service image.
FROM python:3.12.15-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258

# git is required for scaffold.py's `git init`/commit and generate.py's
# promote-to-checkout commit step.
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv/app-builder
# Hash-locked install: requirements.lock is the full transitive tree with
# sha256 hashes (generated from requirements.txt by
# services/lock-requirements.sh), so a rebuild can neither drift to other
# versions nor accept a substituted artifact.
COPY requirements.lock .
RUN pip install --no-cache-dir --require-hashes -r requirements.lock

COPY app ./app
COPY templates ./templates

ENV APP_BUILDER_DATA_DIR=/data
ENV APP_BUILDER_TEMPLATES_DIR=/srv/app-builder/templates
VOLUME ["/data"]

# Non-root by default (see code-intel/Dockerfile's own 2026-07-27 security
# review comment for why every always-on service in this platform now
# does this from the start rather than retrofitting it later).
RUN groupadd -r appbuilder && useradd -r -g appbuilder appbuilder \
    && mkdir -p /data && chown -R appbuilder:appbuilder /data /srv/app-builder
USER appbuilder

EXPOSE 8091
# Single worker on purpose, same reasoning as code-intel: the project
# registry (projects.py) does its own in-process file locking per tenant
# subtree, not cross-process. Scale by sharding tenants across containers.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8091", "--workers", "1"]
