# Promo reels kit (Instagram Reels, YouTube Shorts, LinkedIn)

Everything needed to produce short vertical promo videos of VegaDūta from a **real** install: a
Playwright recorder that drives the console and captures crisp phone-sized frames, a Pillow renderer
for hook/end cards and the terminal animation, Kokoro voice-over, synthesized (royalty-free) music,
and an ffmpeg assembler that burns captions, ducks music under the voice and writes SRT files.

Status on 2026-10-21 (cloud session; nothing in `out/` is committed because video and frames are
large and are re-recorded on the machine that runs the install):

| Piece | State |
|---|---|
| `scripts.md` | 5 reels + 1 overview: voice-over, on-screen text, shot list, captions, hashtags |
| `research-notes.md` | what the 2026 Reels/Shorts guidance says and how it is applied |
| `pipeline/music.py` | two original tracks (`drive` 122 BPM, `calm` 92 BPM), 60 s, numpy/scipy only |
| `pipeline/vo.json` + `vo.py` | every voice line; Kokoro ONNX (`af_heart`) renders them to `audio/` |
| `pipeline/cards.py` | hook cards, end card, caption plates, terminal animation re-rendered from the real boot log |
| `pipeline/build.py` + `spec_r1.json` | assembler; reel 1 ("One command") was built and checked |
| `recorder/` | `record.js` (CDP screencast + visible cursor + human typing), `login.js` (password sign-in, dismisses first-run dialogs), one `scene-*.js` per shot |
| `stack/` | `start-core.sh` (host-JVM core, same env as `scripts/vegaduta personal --native-core`), `api.sh` (owner login + curl), `wf.json` (refund workflow with an approval node), `prompt.txt` (support agent instructions), `llm-tools-shim.py` |
| `demo-knowledge/` | the fictional "Northwind Bikes" policy documents the support agent answers from |

Recorded on the cloud machine (all real, all to be re-recorded locally): first-run password setup,
connecting a Local/OpenAI-compatible provider (llama.cpp preset), uploading the three documents and a
semantic search, creating the "Northwind Support" agent with the local model and knowledge on.
Still to record: the playground answer, the workflow run with the manager-approval pause, the
Connect page, the Cost & ROI page (seeded with `scripts/vegaduta seed-demo`).

## Model choice and the tools shim

Llama 3.2 3B Instruct (Q4_K_M, llama.cpp) answers well from injected knowledge but its tool-call
output does not satisfy llama.cpp's tool-call parser (HTTP 500 on every request that carries
`tools`). The platform's tool-call probe is advisory only, so `stack/llm-tools-shim.py` sits in
front of llama-server and drops `tools`/`tool_choice`; the agent then answers in plain text.
With Ollama and a tool-capable model (`qwen3.5:4b`, the personal-mode default) the shim is not
needed: use `./scripts/vegaduta personal --build --local-ai=host`.

Embeddings without the sidecar: `all-MiniLM-L6-v2` as GGUF behind a second llama-server
(`--embeddings --pooling mean`), with `APP_EMBEDDINGS_PROVIDER=openai-compatible`,
`APP_EMBEDDINGS_BASE_URL=http://127.0.0.1:8090/v1`, `APP_EMBEDDINGS_MODEL=all-minilm-l6-v2`,
`APP_EMBEDDINGS_DIMENSIONS=384`. With Ollama, `nomic-embed-text` through the bootstrap does the same.

## Running it (any machine)

Every script reads and writes under one work folder, `out/` next to this file (git-ignored; override
with `VD_REELS_WORK`). `pipeline/paths.py` and `recorder/paths.js` hold the layout; nothing else
carries a machine-specific path.

```sh
# 1. a running install, recorded for reel 1 (types the command into a real shell under asciinema)
#    needs: Docker, Ollama with qwen3.5:4b + nomic-embed-text, asciinema, agg, ffmpeg
python3 pipeline/rec_term.py "./scripts/vegaduta personal --build --local-ai=host" out/term/boot.cast
python3 pipeline/term.py out/term/boot.cast out/term/boot.mp4 --speed 1.5     # cast -> 1080x1920 clip
# 2. TTS + music + cards (macOS: brew install --cask font-inter font-dejavu; Linux: the Inter/DejaVu packages)
python3 -m venv ~/tts && ~/tts/bin/pip install kokoro-onnx soundfile numpy scipy pillow
# kokoro-v1.0.onnx + voices-v1.0.bin into ~/tts (or VD_TTS_DIR): github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0
python3 pipeline/music.py 80 && python3 pipeline/vo.py af_heart && python3 pipeline/cards.py
# 3. recordings (writes frames + frames.txt + events.json per take under out/rec/<take>)
#    the demo owner's password goes in stack/owner-password.txt (git-ignored); the console URL in
#    VD_CONSOLE_URL and the API in VD_CORE_URL when the install is not on :3000 / :8080
cd recorder && npm i && VD_SETUP_URL='<the setup link the launcher printed>' node scene-setup.js
node scene-knowledge.js && node scene-agent.js && node scene-chat.js <agentId> ../out/rec/05-chat "<question>" 90000
node scene-workflow.js <workflowId> ../out/rec/08-workflow 240000 && node scene-connect.js && node scene-roi.js
# 4. assemble (paths inside a spec are relative to out/)
python3 pipeline/build.py pipeline/spec_r1.json      # -> out/r1.mp4 + out/r1.srt
```
