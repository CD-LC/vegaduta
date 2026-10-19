# Pinned patch + Debian release, identical across every Python service image.
FROM python:3.12.15-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258

WORKDIR /app

# Hash-locked install: requirements.lock is the full transitive tree with
# sha256 hashes (generated from requirements.txt by
# services/lock-requirements.sh), so a rebuild can neither drift to other
# versions nor accept a substituted artifact.
COPY requirements.lock .
RUN pip install --no-cache-dir --require-hashes -r requirements.lock

COPY app ./app

# Unlike sidecar/Dockerfile's sentence-transformers model (downloaded from
# Hugging Face on first use, which needed HF_HOME pointed at a writable dir
# under the non-root user - see that Dockerfile's own long comment on the
# permission trap this caused), Silero's VAD model ships INSIDE the
# livekit-plugins-silero wheel itself
# (site-packages/livekit/plugins/silero/resources/silero_vad.onnx, loaded
# via importlib.resources - confirmed by reading that plugin's
# onnx_model.py directly). `pip install` above already baked it into the
# image; there is no equivalent runtime-download/cache-permission trap to
# guard against here, so no HF_HOME-style env var or pre-warm-at-build-time
# step is needed.
#
# ElevenLabs TTS and the sidecar-Whisper/core-chat HTTP bridges
# (stt_sidecar_whisper.py, llm_core_bridge.py) are pure network calls - no
# local model weights, nothing else to bake in.
RUN groupadd -r voiceagent && useradd -r -g voiceagent voiceagent \
    && chown -R voiceagent:voiceagent /app
USER voiceagent

# AgentServer's own health-check HTTP server (see livekit/agents/worker.py's
# `run()`: a plain HttpServer exposing GET / and GET /worker) - defaults to
# port 8081 in production mode (`start`, used below), 0 (disabled/random) in
# `dev` mode. Not wired into deploy/docker-compose.yml yet - see README.md's
# "Known gaps" section; a parallel/follow-up change owns adding the actual
# `voice-agent` service block there (out of this pass's scope, which is
# voice-agent/ only).
EXPOSE 8081

# "start" = production mode (see livekit/agents/cli/_legacy.py's `start`
# command - the one this repo's LIVEKIT_URL/LIVEKIT_API_KEY/
# LIVEKIT_API_SECRET env vars flow into via WorkerOptions, same as `core`'s
# equivalent three vars). "dev" mode (hot-reload, verbose logs) is for local
# iteration only - see README.md.
CMD ["python", "-m", "app.main", "start"]
