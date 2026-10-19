# sidecar

VegaDuta ML sidecar (`services/sidecar`, FastAPI on 8001): embeddings, rerank, extraction, STT/TTS and
media rendering. Internal only (core, core-jobs, core-worker, voice-agent), authenticated with
`SIDECAR_SHARED_SECRET` from the shared internal Secret. Single replica by design (render jobs are
in-process). Root filesystem stays writable for model caches.
