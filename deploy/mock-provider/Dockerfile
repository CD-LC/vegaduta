# B9-PR1 - tiny OpenAI-compatible stub so the full-stack CI job (stack.yml)
# boots with ZERO real LLM API keys. See main.py's module docstring for why
# this is reached via a seeded control_plane.platform_hosted_llm_provider
# row, not an env var on core. CI-only image - never referenced by
# deploy/docker-compose.yml's dev defaults, only by docker-compose.ci.yml.
FROM python:3.14.8-slim-trixie@sha256:fbecd461fc72d21b2143c6eaba25143738079154a6d0ea3da3aec724f50eed4a

WORKDIR /app

RUN pip install --no-cache-dir fastapi==0.115.* "uvicorn[standard]==0.32.*"

COPY main.py ./main.py

EXPOSE 9101

# python:3.12.x-slim has no curl (see deploy/docker-compose.yml's sidecar
# service comment for the same constraint) - urllib is stdlib, so the
# compose-level healthcheck below needs nothing extra installed here.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:9101/health',timeout=3).status==200 else 1)"]
USER 65534
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "9101"]
