# B9-PR1 - tiny OpenAI-compatible stub so the full-stack CI job (stack.yml)
# boots with ZERO real LLM API keys. See main.py's module docstring for why
# this is reached via a seeded control_plane.platform_hosted_llm_provider
# row, not an env var on core. CI-only image - never referenced by
# deploy/docker-compose.yml's dev defaults, only by docker-compose.ci.yml.
FROM python:3.12.15-slim@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1

WORKDIR /app

RUN pip install --no-cache-dir fastapi==0.115.* "uvicorn[standard]==0.32.*"

COPY main.py ./main.py

EXPOSE 9101

# python:3.12.x-slim has no curl (see deploy/docker-compose.yml's sidecar
# service comment for the same constraint) - urllib is stdlib, so the
# compose-level healthcheck below needs nothing extra installed here.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "9101"]
