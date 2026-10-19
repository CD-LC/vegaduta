# Pinned patch + Debian release, identical across every Python service image.
FROM python:3.12.15-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258

WORKDIR /app

# Knowledge Stage F3b - pytesseract (Python package, in requirements.txt)
# is only a wrapper around the actual OCR engine, which is a separate
# system binary Debian/Ubuntu packages as tesseract-ocr. Without this, every
# image upload would fail at runtime with "tesseract is not installed"
# despite `pip install` having succeeded cleanly - a classic Python-wrapper-
# around-a-native-tool trap, worth calling out explicitly since it's easy to
# miss in local dev (macOS/Linux dev machines often already have it via
# brew/apt for some other reason, masking that it's not actually declared
# as a dependency anywhere until this Dockerfile).
#
# ffmpeg (Video Pipeline Stage 3, render.py) is here for EXACTLY the same
# reason, and it is worth spelling out because the trap is subtler the
# second time: PyAV already ships in this image as a transitive dependency
# of faster-whisper, and PyAV bundles the ffmpeg *libraries*. So `import
# av` works, and transcription of .mp4 works, which makes it easy to
# conclude ffmpeg is present. It is not - PyAV bundles no CLI, and
# render.py shells out to the `ffmpeg`/`ffprobe` BINARIES. Without this
# package every /render job fails at its first subprocess call with ENOENT
# while every other media endpoint keeps working perfectly, which is the
# same Python-wrapper-around-a-native-tool shape as tesseract above.
# fonts-dejavu-core comes with it deliberately: it is what makes
# render.py's `FontName=DejaVu Sans` actually resolve when the subtitles
# filter burns captions in, instead of silently substituting a fallback.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        ffmpeg \
        fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.lock .
# CPU-only torch (2026-08-05 - "no infrastructure increase" latency/build
# audit). sentence-transformers pulls in torch as a transitive dependency;
# left to pip's default resolution against plain PyPI, that means the full
# CUDA-enabled wheel - nvidia_cudnn/cublas/nccl/triton/cuda_toolkit, over
# 1.5GB of GPU libraries this CPU-only service (every model call here uses
# device="cpu" explicitly - embeddings.py, transcription.py) never touches.
# This is PyTorch's own documented CPU-only install method
# (pytorch.org/get-started/locally): installing torch from this index FIRST
# means sentence-transformers' later dependency resolution sees an
# already-satisfying torch already installed and never reaches for the GPU
# wheel. Directly cuts image size and build/pull time - the previous image
# was 9.2GB; almost all of that was unused CUDA libraries, which also meant
# every fresh build/deploy spent minutes downloading gigabytes nothing here
# ever calls.
#
# Hash-locked (2026-09-30): requirements.lock (services/lock-requirements.sh)
# pins the full transitive tree with sha256 hashes: torch as 2.x.y+cpu from
# the CPU index (sidecar/lock/pyproject.toml scopes that index to torch
# alone), every other package from PyPI. The install mirrors that split: the
# torch block is installed first, --no-deps, from the CPU index ONLY; the
# second pass installs the whole lock from PyPI ONLY and finds torch already
# satisfied. No package can be served by the "other" index, and
# --require-hashes accepts only the exact artifacts recorded in the lock.
RUN python -c "import re; t = open('requirements.lock').read(); open('/tmp/torch.lock', 'w').write(re.search(r'^torch==.*?(?=^\S|\Z)', t, re.M | re.S).group(0))" \
    && pip install --no-cache-dir --require-hashes --no-deps \
        --index-url https://download.pytorch.org/whl/cpu -r /tmp/torch.lock \
    && pip install --no-cache-dir --require-hashes -r requirements.lock \
    && rm /tmp/torch.lock

COPY app ./app

# Security review 2026-07-27: no service dir here holds a persistent volume
# (see deploy/docker-compose.yml's `sidecar` service - no `volumes:` entry),
# so switching to a non-root runtime user carries no existing-volume
# ownership migration risk, unlike code-intel/Dockerfile's /data mount.
#
# CORRECTION (Video Pipeline Stage 3): that is no longer true. The `sidecar`
# service now has one volume - agentic-sidecar-workspace, mounted at
# /app/workspace - because a render needs scratch space for clips, the
# narration track, the SRT and the output mp4, none of which fit the
# base64-in-JSON convention every other endpoint on this service uses. The
# reasoning above is kept rather than deleted, so the next reader can see
# exactly WHICH premise stopped holding. The mitigation is the mkdir+chown
# below: /app/workspace is created and owned by `sidecar` at BUILD time, so
# the mount inherits that ownership instead of arriving root-owned and
# unwritable by the runtime user - which is precisely the migration this
# comment once said did not apply here. code-intel's /data mount is the
# in-repo precedent for getting this wrong.
# Mirrors core/Dockerfile's `agentic` user / web+web-admin's `nextjs` user -
# this was the one real service Dockerfile still running as root.
# Hugging Face / sentence-transformers cache must live somewhere the non-root
# sidecar user can write. The default ($HOME/.cache -> /home/sidecar/.cache) is
# unusable: `useradd -r` creates NO home dir and /home is root-owned, so the
# very first model download died with `PermissionError: '/home/sidecar'` and
# every /embed returned a raw HTTP 500. Point both caches under /app (chowned
# below) AND pre-bake the default model at build time so production needs no
# Hugging Face egress at runtime (GCP prod may block it, which would 500 the
# same way even once the permission issue is fixed).
ENV HF_HOME=/app/.cache/huggingface \
    SENTENCE_TRANSFORMERS_HOME=/app/.cache/sentence-transformers
# Cold-start/egress gap (2026-08-07): the default-model bake above only covers
# /embed's default path. Two more models download from Hugging Face at FIRST
# USE at runtime - the reranker (reranking.py's cross-encoder/ms-marco-MiniLM-
# L-6-v2, the ONLY reranker, ~80MB) and the Indic-positioning embedder
# (embeddings.py's multilingual-minilm -> paraphrase-multilingual-MiniLM-
# L12-v2, ~470MB) - so on a prod host that blocks HF egress, the first /rerank
# call and the first multilingual /embed each return a raw 500. Bake both by
# default: ~+550MB image, but correctness beats image size for a platform
# whose positioning IS Indic-language RAG and whose GCP prod may block egress
# (the exact failure mode the default-model bake above was added for). CI or a
# deliberately slim build can opt out with --build-arg BAKE_EXTRA_MODELS=false.
# Deliberately NOT baked (rarely selected, still runtime-downloaded on first
# use): mpnet-base-v2, bge-small-en, multi-qa-minilm - baking every registry
# row would roughly double the image again for models nothing selects by
# default; if one of them becomes load-bearing, promote it into this list.
ARG BAKE_EXTRA_MODELS=true
RUN groupadd -r sidecar && useradd -r -g sidecar sidecar \
    && mkdir -p /app/.cache /app/workspace \
    && python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')" \
    && if [ "$BAKE_EXTRA_MODELS" = "true" ]; then \
         python -c "from sentence_transformers import SentenceTransformer, CrossEncoder; CrossEncoder('cross-encoder/ms-marco-MiniLM-L-6-v2'); SentenceTransformer('sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2')"; \
       fi \
    && chown -R sidecar:sidecar /app
USER sidecar

EXPOSE 8001

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
