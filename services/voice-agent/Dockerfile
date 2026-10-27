# Pinned patch + Debian release, identical across every Python service image.
FROM python:3.14.8-slim-trixie@sha256:fbecd461fc72d21b2143c6eaba25143738079154a6d0ea3da3aec724f50eed4a

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
# Bake the default Piper voice (local, keyless TTS - see app/tts_piper.py and
# config.py's PIPER_* block). Both files are sha256-verified - the same
# supply-chain posture as every other external fetch in this repo's images.
# PIPER_VOICE defaults to this exact voice; other voices are still loadable at
# runtime by placing the .onnx under PIPER_MODEL_DIR.
RUN mkdir -p /opt/piper-voices
COPY piper_voices_download.py /tmp/piper_voices_download.py
RUN python /tmp/piper_voices_download.py en_US-lessac-medium /opt/piper-voices \
    && rm /tmp/piper_voices_download.py
ENV PIPER_MODEL_DIR=/opt/piper-voices

RUN groupadd -r voiceagent && useradd -r -g voiceagent voiceagent \
    && chown -R voiceagent:voiceagent /app /opt/piper-voices
USER voiceagent

# AgentServer's own health-check HTTP server (see livekit/agents/worker.py's
# `run()`: a plain HttpServer exposing GET / and GET /worker) - defaults to
# port 8081 in production mode (`start`, used below), 0 (disabled/random) in
# `dev` mode. Wired into deploy/compose/docker-compose.yml as the `voice-agent`
# service (compose profile `voice`, health check on this port) and into the Helm
# chart deploy/helm/charts/voice-agent.
EXPOSE 8081

# "start" = production mode (see livekit/agents/cli/_legacy.py's `start`
# command - the one this repo's LIVEKIT_URL/LIVEKIT_API_KEY/
# LIVEKIT_API_SECRET env vars flow into via WorkerOptions, same as `core`'s
# equivalent three vars). "dev" mode (hot-reload, verbose logs) is for local
# iteration only - see README.md.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 CMD python -c "import socket; socket.create_connection(('127.0.0.1', 8081), 3)" || exit 1
CMD ["python", "-m", "app.main", "start"]
