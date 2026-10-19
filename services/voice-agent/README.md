# voice-agent

A real-time voice AI pipeline for VegaDūta's self-hosted LiveKit calling,
built on [LiveKit Agents (Python)](https://docs.livekit.io/agents/). This is
what finally makes the "Call" button real.

## Why this exists

`core/voice`'s `VoiceCallController` javadoc and `web/app/components/agents/
CallPanel.tsx`'s header comment both describe today's self-hosted calling as
**two independent pieces, deliberately not merged into one flow**:

1. **The LiveKit call itself** (`startCall`/`endCall` in `CallPanel.tsx`) -
   joins a real WebRTC room via `livekit-client`, captures the mic, shows
   connection status. Proves the self-hosted WebRTC infra works, but -
   before this package existed - **nobody was listening on the other end**.
   Being "in a call" only proved the plumbing was real.
2. **Push-to-talk against the agent** (`startPushToTalk`/`sendTurn` in
   `CallPanel.tsx`, backed by `VoiceCallController#turn`) - a much simpler
   MediaRecorder -> `POST /api/voice/agents/{id}/turn` -> MP3 reply round
   trip, explicitly NOT carried over the LiveKit room's own media tracks.
   `VoiceCallController`'s own javadoc explains why: *"LiveKit has no
   first-party Java server-side agent framework... building a Java WebRTC
   media-track subscriber from scratch was not realistic."* This is the
   "honest limitation" the original build brief called out: *"one-shot
   request/response... not fully continuous conversational turn-taking."*

`voice-agent` is the fix for limitation #2, built the way `VoiceCallController`
always assumed it eventually would be: LiveKit's Agents ecosystem **is**
Python/Node, so this is a small Python worker that joins the SAME LiveKit
rooms `CallPanel.tsx` already joins, subscribes to the caller's real-time
audio track, and replies with real-time synthesized audio - VAD-driven
turn-taking instead of hold-to-talk.

## Architecture

```
 browser (CallPanel.tsx)                    voice-agent (this package)
 ─────────────────────────                  ──────────────────────────
 livekit-client Room.connect()  ───────►    LiveKit server (self-hosted,
                                             deploy/docker-compose.yml)
                                                       │
                                          AgentSession (main.py)
                                          ┌────────────┴────────────┐
                                    Silero VAD              (turn detection)
                                          │
                              SidecarWhisperSTT   (stt_sidecar_whisper.py)
                                          │  wraps sidecar's existing
                                          │  POST /transcribe (faster-whisper)
                                          ▼
                              CoreChatBridgeLLM     (llm_core_bridge.py)
                                          │  NOT a real LLM - streams SSE
                                          │  from core's EXISTING
                                          │  POST /api/agents/{id}/chat
                                          │  (same endpoint/pipeline the
                                          │  text Playground uses: full
                                          │  tool-calling, memory,
                                          │  guardrails, tenant's model)
                                          ▼
                              livekit-plugins-elevenlabs TTS
                                          │  (this platform's default TTS -
                                          │   see SpeakToolCallback's javadoc)
                                          ▼
                              synthesized audio ──────────────────►  browser
```

The `CoreChatBridgeLLM` piece is the important architectural decision from
the build brief, worth restating: **this worker does not call any LLM
itself.** `core`'s `AgentChatService` already owns the tenant's configured
model, tool calling, memory, and guardrails - reimplementing any of that
here would fork agent behavior between the text and voice surfaces. The
"LLM" plugin in this pipeline is pure protocol translation: VAD-bounded
utterance in, SSE-streamed reply out.

## Files

| File | Role |
|---|---|
| `app/main.py` | Worker entrypoint - registers with LiveKit, wires the `AgentSession` per call, resolves the caller's agent id + auth token. |
| `app/stt_sidecar_whisper.py` | Custom STT plugin wrapping `sidecar`'s `POST /transcribe`. |
| `app/llm_core_bridge.py` | Custom "LLM" plugin bridging to core's `POST /api/agents/{agentId}/chat` SSE stream. |
| `app/config.py` | All environment configuration, one place, with a comment on each var explaining exactly which existing platform convention it mirrors (or deliberately doesn't, and why). |
| `tests/` | Unit tests for the two custom plugins against a real local `http.server` (see below). |

## Running locally

```bash
cd voice-agent
pip install -r requirements.txt -r requirements-dev.txt
python -m app.main dev     # hot-reload dev mode
# or:
python -m app.main start   # production mode (what the Dockerfile's CMD runs)
```

Needs `LIVEKIT_URL`/`LIVEKIT_API_KEY`/`LIVEKIT_API_SECRET` pointed at a real
LiveKit server (the same three vars `core`/`deploy/.env` already set for
`deploy/docker-compose.yml`'s `livekit` service), `SIDECAR_BASE_URL`
reachable, `CORE_API_BASE_URL` reachable, and an `ELEVENLABS_API_KEY`. See
`app/config.py` for every var and its default.

### Running the tests

```bash
cd voice-agent
pytest tests/
```

## Known gaps (read before assuming this is fully wired end-to-end)

This is a **genuinely new capability**, built for the first time in this
pass. Some pieces could not be verified without live infrastructure this
session didn't have access to - flagged here explicitly rather than implied
to be "verified working":

- **The browser -> auth-token handoff is an assumed contract, not a
  verified integration.** A parallel session is updating `CallPanel.tsx`/
  `LiveKitTokenService.java` to actually attach the caller's Keycloak access
  token to the LiveKit participant (via the access token's `attributes` or
  `metadata` claim - both are real, documented LiveKit token grant fields).
  Neither existed yet as of this file being written. `main.py`'s
  `_extract_auth_token` checks both `participant.attributes["authToken"]`
  and a JSON/raw-JWT `participant.metadata`, and fails LOUDLY (a clear log
  line, then a spoken "voice calling isn't fully set up" message, then every
  turn's `CoreChatBridgeLLM` call raises rather than proceeding
  unauthenticated) if neither is present - by design, per the build brief.
  Once the parallel change lands, confirm it actually matches one of these
  two shapes (or update `_extract_auth_token` to match whatever it actually
  does).

- **No per-tenant ElevenLabs key resolution.** Every other ElevenLabs call
  path in this platform (`SpeakToolCallback`, `VoiceCallController#turn`/
  `#speak`) resolves a per-tenant credential + the tenant's own cloned voice
  via `ElevenLabsVoiceService`/`ElevenLabsClient` - both Java-only, DB-backed,
  and unreachable from a Python worker with no new core endpoint (which would
  mean touching `core/`, out of this pass's scope - see `config.py`'s
  `ELEVENLABS_API_KEY` comment). Until that endpoint exists, every real-time
  call uses ONE platform-level key/voice, not the tenant's own cloned voice
  the push-to-talk path already supports.

- **Not wired into `deploy/docker-compose.yml`.** Per the build brief, that
  file is owned by a parallel session right now; adding a `voice-agent`
  service block (image build, `agent-io` network membership so it can reach
  `livekit`, env vars per `app/config.py`) is a small, mechanical follow-up
  once this file lands, not done here to avoid a merge collision.

- **The real LiveKit Agents SDK could not be *imported* on the machine this
  was built on** (a Windows host) - `livekit-agents` 1.6.8 bundles a native
  extension (`livekit.local_inference._native`, backing LiveKit Cloud's
  optional semantic turn-detector, which this deployment doesn't use - see
  `main.py`'s `turn_handling=TurnHandlingOptions(turn_detection="vad")`)
  that crashed the Python process on import (a clean access violation,
  confirmed with `python -X faulthandler`, root-caused to that machine's
  native DLL loading, unrelated to this repo). Worked around for
  verification by pre-stubbing that one native module in `sys.modules`
  (see `tests/conftest.py`'s long comment) - which let the REAL
  `livekit-agents`/`livekit-plugins-elevenlabs` packages import and run for
  real, and let every class in this package (`SidecarWhisperSTT`,
  `CoreChatBridgeLLM`, `main.py`'s room-name/auth-token parsing) be
  constructed and exercised against the real SDK base classes - just not on
  a genuine LiveKit room. `livekit-plugins-silero` itself also failed to
  import for the same class of reason (`onnxruntime`'s native DLL, a
  separate package) and could not be worked around the same way (VAD
  actually needs its real native inference), so Silero VAD specifically
  remains **unverified by execution** in this session - only by reading its
  source directly (see `stt_sidecar_whisper.py`'s and `main.py`'s comments
  on exactly what was confirmed this way). The target deployment is a Linux
  container (`python:3.12-slim`, this package's own `Dockerfile`), where
  none of this class of native-DLL issue is expected to reproduce - but that
  is an expectation, not something this session could verify directly.

- **No live end-to-end call was made.** Nothing in this session had a real
  LiveKit room with a real browser participant, a real ElevenLabs API key,
  or a real Keycloak-issued access token to test against - every piece above
  was verified at the level of "imports for real, constructs for real,
  handles a real (mocked-server) HTTP/SSE exchange correctly," not "a phone
  call actually worked."
