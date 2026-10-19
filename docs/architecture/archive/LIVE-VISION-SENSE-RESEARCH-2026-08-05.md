# Program S — Live Vision ("Sight") for Agents: Research & Implementation Plan
**Date:** 2026-08-05 · **Status:** RESEARCH / DESIGN — nothing here is implemented
**Companion to:** `docs/plans/EDGE-CAPABILITIES-IMPLEMENTATION-PLAN-2026-08-04.md` (Programs E/V/M/F/X/B)

Goal: a user in the Agent Workbench (and later any surface) can turn on their **webcam
and stream it to an agent**, and the agent **analyzes what it sees** — live commentary,
Q&A about the scene, inspection, monitoring, and durable memory of what was observed.
This is the "sight" sense in the larger "agents with all senses" direction; the last
section maps the full sensory roster honestly.

Grounded in a code-level audit (2026-08-05, two parallel exploration passes over
`web/`, `core/`, `voice-agent/`, `sidecar/`, `deploy/`).

---

## 1. What the audit found (facts this design stands on)

### Already built — reuse, don't rebuild

| Capability | Where | Relevance to live vision |
|---|---|---|
| **LiveKit SFU, self-hosted** | `deploy/compose/docker-compose.yml` `livekit` service (v1.13.4, ports 7880/7881 + UDP 50000-50100, network `agent-io`); config `deploy/livekit/livekit.yaml` | The WebRTC transport for live mode already runs in the default stack. Tokens already grant `canPublish` — a camera track needs **zero token changes**. |
| **LiveKit token minting** | `core/voice/.../LiveKitTokenService.java` — Nimbus HS256, no SDK; embeds caller's Keycloak JWT in the `metadata` claim as `coreAccessToken` | The identity-forwarding pattern a vision worker needs (call core *as the user*) is already solved. |
| **Room-name → agent routing** | `VoiceCallController`: rooms named `voice-<agentId>-<8hex>`; `voice-agent/app/main.py` `_ROOM_NAME_RE` parses it back | A `sight-` room prefix rides the exact same convention. |
| **Python agent worker** | `voice-agent/` — LiveKit Agents 1.6.8, Silero VAD, `SidecarWhisperSTT`, `CoreChatBridgeLLM` → `POST /api/agents/{id}/chat` | Currently `AutoSubscribe.AUDIO_ONLY`. The `livekit-agents` framework natively supports subscribing to **video tracks and reading `VideoFrame`s** — the worker is the natural home for server-side frame sampling. |
| **Vision-capable chat, both contracts** | `POST /api/agents/{agentId}/chat` accepts (A) staged `attachmentKey` (`ChatAttachmentService`, image 8MB) and (B) inline base64 `attachments[]` (`InlineAttachmentProcessor`, ≤3 files, ≤10MB total) → Spring AI `spec.media(...)` | **A webcam frame is just an image attachment.** The entire "agent looks at a frame and reasons about it" leg already exists end-to-end for any vision model. |
| **Modality capability system** | `ModelModalitiesService` (config overrides → provider catalog `supports_image_input` → name-glob heuristics; `gemini-*` = image+audio+video+document), surfaced as `AgentModalitiesView` on `AgentResponse`, consumed by `ChatAttachments.readModalities()` | The gate "can this agent see?" is already a queryable, non-drifting fact. Camera UI must simply require `modalities.image`. |
| **Push-to-talk + LiveKit call UI** | `web/app/components/agents/CallPanel.tsx` (two independent pipelines in one panel — documented design) mounted inside `AgentPlayground` | The exact UX pattern to copy for a `CameraPanel`: a strip under the playground header, explicit user-gesture start, live status, hold-to-X interactions. |
| **Consent/approval chokepoint** | `ApprovalPolicyService` glob patterns checked by `GuardedToolCallback`; `livekit.egress.enabled` default-false precedent for privacy-sensitive features | Camera capture becomes approval-gatable with a policy row, and default-off with one settings key. |
| **Quota/budget machinery** | `VideoGenerationQuotaService` pattern (per-tier allowances under `app.*` keys, default `enabled=false`), `PerUserBudgetService` (modality splits), `PlatformFeatureBudgetService` (string-keyed master caps), `FreeTierUsageLimiterService` | Live vision is token-expensive; every governor it needs already has a worked pattern. |
| **Edge local vision (planned)** | Program E-5: SmolVLM-500M/Moondream2 via transformers.js, on-device image Q&A; `downloadManager.ts`/`tierRouter.ts`/`EdgeSetupChip` shipped in Wave 1 | The free/privacy tier of live vision: frames analyzed **in the browser**, nothing leaves the device. |
| **Meeting Companion design (M0)** | Plan §M0: `getDisplayMedia` capture → chunk POST → session entity → rolling Redis transcript → live Q&A → summary + `remember_fact` | The session/transcript/consent/erasure skeleton for any *continuous perception* session. Live vision should share it, not duplicate it. |

### Gaps that shape the design
1. **No webcam capture code exists anywhere** in `web/` (grep-verified: the only
   `getUserMedia` calls are audio-only in `CallPanel.tsx`). Clean slate on the client.
2. **No frame extraction server-side** — sidecar's `/transcribe` reads *only the audio
   track* of video files via PyAV (deliberate, documented in `transcription.py` and
   `STAGE-F3D-VERIFICATION.md`). No ffmpeg, no scene detection, no visual analysis.
3. **The `voice-agent` worker is opt-in** (`profiles: ["voice-agent"]` in compose) and
   audio-only; no video subscription, no LiveKit webhooks, no egress worker container.
4. **WebRTC media ≠ Cloudflare tunnel.** The tunnel only carries HTTP(S); RTC media
   needs UDP 50000-50100 forwarded (already a known constraint for remote *audio* —
   see `livekit-public-routing-and-keys` note). Video multiplies the bandwidth stake.
5. **`ChatRequest.message` is `@NotBlank`** — a frames-only turn still needs message
   text (fine: the panel supplies an instruction string).
6. **Session memory stores only a placeholder** for attachments
   (`withAttachmentNote`) — the model never re-sees old frames. Good for cost; means
   continuous vision needs its own rolling "what I've seen" text state (like M0's
   rolling transcript), not attachment history.
7. **Vision billing** rides normal LLM token billing (`ModalityClassifier` buckets
   spend by model id), but there is no per-minute/per-frame governor for a *streaming*
   consumer. Must be added before any live mode ships.

---

## 2. The core design insight: "video understanding" = sampled frames + transcript, not video

No production agent system feeds continuous video into an LLM. Every realistic
architecture — including Google's own Gemini Live API — reduces live video to:

- **sampled frames** (typically **0.5–1 fps**, event-boosted), plus
- **the audio transcript** (already solved on this platform), plus
- **a rolling text state** of what has been seen (scene descriptions, detected
  changes), which is what the agent actually reasons over across time.

Cost math that makes this non-negotiable (order-of-magnitude, Gemini-class pricing):
one image ≈ 258 tokens (per 768×768 tile). At 1 fps that is ~15,500 input tokens/min
of frames alone; a 10-minute session ≈ 155K input tokens *before* any text. At
Gemini 2.x Flash-class input pricing (~$0.10–0.30/M input tokens) that is
$0.015–0.05 per 10-min session — **cheap enough to ship at PREMIUM** with sampling
discipline, and **ruinous without it** (30 fps naive = 900× that). GPT-4o-class
pricing is ~10× higher per token — pool-model choice matters. Frame *selection*
(send frames only when the scene changes or the user asks) routinely cuts another
5–20× in real deployments.

Consequences:
- **Frame sampling + change detection is the product**, not an optimization. Client
  does cheap pixel-diff gating (free); server/worker enforces a hard fps ceiling.
- The **existing image-attachment chat path is the correct analysis backbone** for
  everything except sub-second-latency conversation; realtime APIs (Gemini Live) are
  a premium *latency* upgrade, not the foundation.
- The platform's cost governors (per-user budget, quota services) must meter
  **frames and minutes**, not just turns.

---

## 3. Design options evaluated

### Option A — Client-side frame capture → existing chat/vision path (snapshot & watch modes)
`getUserMedia({video})` → `<video>` preview → canvas → JPEG (~50-150KB at 1024px) →
existing **inline attachment contract** (`attachments[{fileName,mimeType,dataBase64}]`)
on the normal SSE chat stream.

- **Snapshot ("Show the agent")**: user clicks 📷 or holds a "Show" button (the
  push-to-talk analog); 1–3 frames + their typed/spoken question go up as one turn.
- **Watch mode**: a client timer captures a frame every N seconds, a pixel-diff gate
  (downscaled grayscale delta over threshold) drops unchanged frames, and each
  *changed* frame triggers a lightweight "describe what changed / check the standing
  instruction" turn. The standing instruction ("tell me when the oven smokes",
  "alert me if someone enters the frame") lives in the panel, not re-typed.

**Pros:** zero new transport, zero new infra, works today with any `supportsImageInput`
model in the pool, degrades to every tier, fully inside existing auth/guardrails/
billing, ~1–2 weeks to a demo. **Cons:** latency per look is a full chat turn (2–5s);
no fused audio+video single session (voice and vision are separate turns); browser tab
must stay open and visible-ish.

### Option B — LiveKit video track + perception worker (true live session)
`CallPanel`'s existing room gains `setCameraEnabled(true)`; a new **perception mode**
in the Python worker (room prefix `sight-`) subscribes to the video track, samples
frames server-side (VAD-equivalent: scene-change + fps ceiling), maintains the rolling
scene state in Redis (M0's transcript pattern, `subjectKey = "sight:"+sessionId`), and
drives turns through the same `CoreChatBridgeLLM` bridge — optionally speaking replies
back through the existing TTS chain. **Voice + vision become one session: the agent
hears you, sees what you show it, and talks back.**

**Pros:** genuinely multimodal live experience; sampling policy is server-enforced
(cost safety); transcript+scene-state unify with Program M (one perception pipeline,
two senses); works from mobile browsers well (WebRTC is battery-friendlier than
JS timer capture). **Cons:** needs the worker promoted from opt-in profile to default
stack; UDP path requirement (gap #4) makes it staging-verifiable but
port-forward-dependent; worker decodes video (CPU: one 720p VP8 decode ≈ fraction of
a core — fine at small concurrency, needs a cap); more moving parts.

### Option C — Native realtime multimodal API bridge (Gemini Live class)
LiveKit Agents ships a Google **Gemini Live API** realtime plugin: the worker pipes
audio+video frames straight into a bidirectional streaming model session
(sub-second voice replies, continuous vision at ~1fps managed by Google). OpenAI
Realtime remains audio+image-turn, not continuous video — Gemini Live is the only
mature fit today.

**Pros:** best-in-class latency and fused perception, least custom code for the hard
part. **Cons:** provider lock-in for the session (bypasses `AgentChatService`, so
tools/guardrails/memory need a re-entry design — the plugin supports tool calls, but
they'd need bridging back through core's guarded registry); per-minute pricing;
BYOK/pool policy decision needed. **Verdict: the premium ceiling (Phase 3), not the
foundation** — same reasoning as V3 Veo in the video ladder.

### Option D — Edge local VLM (free/privacy tier; E-5 extension)
SmolVLM/Moondream via transformers.js analyzing webcam frames **entirely on-device**:
snapshot Q&A and simple watch-mode ("did the scene change? describe it") with zero
frames ever leaving the browser. Rides the shipped `downloadManager`/`tierRouter`/
`EdgeSetupChip` machinery and E-5's planned model. Quality ceiling stated honestly
(500M-class VLM: solid captioning/OCR-lite/object presence; not fine-grained
reasoning). "Analyzed on your device" badge per the E-5 design.

### Recommendation — a ladder, like everything else on this platform

| Tier | Mode | Transport | Analysis | Entitlement |
|---|---|---|---|---|
| **S0** | Snapshot + Watch (Option A) | HTTPS (existing chat) | Pool/BYOK vision model via existing attachment path | PREMIUM (server frames); FREE gets S-edge |
| **S-edge** | Local snapshot/watch (Option D) | none — on-device | SmolVLM-class local | FREE (the honest free tier) |
| **S1** | Live session (Option B) | LiveKit WebRTC | Worker-sampled frames → core chat; voice fused | PREMIUM+, minute-metered |
| **S2** | Realtime conversation (Option C) | LiveKit ↔ Gemini Live | Native realtime multimodal | Top tier / BYOK, product call |

S0 first: it proves demand with ~zero infra, and every piece it builds (CameraPanel,
frame gating, consent, quotas, scene-state memory) is load-bearing for S1/S2.

---

## 4. Proposed architecture (S0 + S1 in detail)

### 4.1 Frontend — `web/app/components/agents/CameraPanel.tsx`
A sibling strip to `CallPanel` inside `AgentPlayground` (same mount pattern,
`AgentPlayground.tsx:772` area), gated on **`agent.modalities.image`** (reuse
`readModalities()` — do not invent a second capability check; nothing in the panel
renders when the agent's model can't see).

- **Capture core**: `navigator.mediaDevices.getUserMedia({video: {width: {ideal: 1280}, facingMode: "user"}})`
  → muted inline `<video>` preview → capture via `createImageBitmap` + canvas →
  `canvas.toBlob("image/jpeg", 0.8)` downscaled to ≤1024px long edge (~50–150KB —
  well inside the 10MB inline budget; 3-frame burst ≤ 0.5MB). Camera/device picker
  via `enumerateDevices`; front/back toggle for mobile (`facingMode: "environment"`).
- **Snapshot mode**: 📷 button attaches the current frame (+optional 3-frame
  half-second burst for motion questions) to the next normal playground turn —
  literally `api.streamChat(..., attachments)` (contract B). Frames render in history
  via the existing `TurnAttachments` thumbnails.
- **Watch mode**: interval capture (default 5s) → **pixel-diff gate** (draw to a 64×36
  grayscale canvas, mean-abs-diff vs last-sent frame, threshold ~8–12%) → on change,
  send frame + the standing instruction as a turn tagged `visionWatch: true`. Client
  hard ceiling (≥3s between sends) regardless of diff storms (camera shake). Standing
  instruction lives in the panel; responses stream into the transcript; an optional
  "speak alerts" toggle reuses `handleSpeak`/`playBrowserTTS`.
- **Edge mode** (S-edge): when tier ≥1 and the local VLM is cached, the same panel
  routes frames to the on-device model via a `tierRouter`-style seam
  (`routeVisionTurn()`), with the "analyzed on your device" badge. Fall-through to
  hosted only with explicit user consent (frames are more sensitive than text —
  **no silent escalation of camera frames off-device, ever**).
- **UX & trust rules** (binding): camera starts only from an explicit click; a
  hard-to-miss live indicator (red dot + "Camera on · frames sent only on change");
  a visible per-session frame counter ("14 frames analyzed"); stop releases tracks
  immediately (`track.stop()`); nothing auto-starts on mount, route change pauses
  capture (Page Visibility API); no frame persisted client-side beyond the last-diff
  reference.

### 4.2 Backend (S0) — almost nothing new
- Chat path: **unchanged.** Frames are inline attachments; `InlineAttachmentProcessor`
  and `ModelModalitiesService` already validate and gate them.
- **`VisionUsageService`** (new, `core/agents` or small `core/vision` package):
  Redis fixed-window counters (the `FreeTierUsageLimiterService` pattern) for
  **frames/day and watch-minutes/day per user**, keys under `app.vision.*`,
  `enabled` default **false**. Checked in `AgentChatService` when a turn carries
  image attachments flagged as camera frames (a boolean `cameraFrame` on the inline
  attachment DTO, or simpler: a `X-Vision-Watch` header the controller translates —
  decide at implementation; the DTO flag is cleaner and versioned).
- **Approval/consent**: add `camera.capture.start` to
  `ApprovalPolicyService.DEFAULT_SENSITIVE_PATTERNS` so tenants can require admin
  approval for agents using live camera; panel calls a tiny
  `POST /api/vision/sessions` (start/end, for audit + quota + the approval hook)
  even in S0 — cheap now, and it becomes the S1 session object.
- **Memory**: on session end, one summarisation turn ("summarize what you observed;
  store durable facts worth remembering") → existing `remember_fact` with
  `subjectKey = "sight:" + sessionId` (+ the user's principal). Erasure rides the
  existing subject-erasure path. Rolling scene state within a session: the
  `SessionMemoryService` Redis pattern (text descriptions only — never frame bytes).

### 4.3 S1 — live session (perception worker)
- **Client**: `CallPanel` gains a camera toggle; when on, `room.localParticipant.setCameraEnabled(true)`
  (the token already grants publish). One room = mic + camera = one fused session.
- **Worker** (`voice-agent/`, listener-mode style): room prefix `sight-` (or an
  attribute on the existing `voice-` room) switches the session to perception mode —
  `AutoSubscribe` widened to include video; a frame loop reads `VideoFrame`s,
  downscales, runs the same scene-change gate (server-enforced ceiling **max 1 frame
  sent per 2s**, config), and calls core chat with the frame + rolling scene state
  via `CoreChatBridgeLLM` (identity from the `coreAccessToken` metadata pattern,
  unchanged). Replies optionally go out through the existing TTS leg → the agent
  *talks about what it sees* with voice-call latency.
- **Transcript/scene unification with Program M**: the scene-state store and the
  session entity should be the *same* mechanism as M0's `MeetingSession`/rolling
  transcript (generalize the name: `PerceptionSession`, `kind = MEETING | SIGHT`).
  One pipeline, three capture front-ends (meeting tab audio, phone SIP, webcam).
- **Infra prerequisites**: promote `voice-agent` from opt-in compose profile into the
  default stack (already recommended by Program M1); document/verify the UDP
  50000-50100 forward on staging (known constraint); add a worker concurrency cap
  (env `PERCEPTION_MAX_ROOMS`, refuse politely beyond it). LiveKit **egress is NOT
  required** — we never record video by default; recording is a separate, later,
  consent-heavy decision (and the egress worker container is a known missing piece).

### 4.4 Entitlements, flags, brand (per Programs X/B conventions)
- Flags: `platform.vision.snapshot.enabled`, `app.vision.watch.*` (quota keys),
  `platform.vision.live.enabled` — all default off, staging (.xyz) first, then prod.
- Plan gating: one `plan_limits` boolean `live_vision_allowed` (8-step recipe;
  Features-page row — it's a selling point), plus quota numbers per tier:
  suggested start FREE = edge-only (0 server frames), PREMIUM = 300 frames/day +
  60 watch-min/day, higher tiers scale. `PerUserBudgetService`: vision frames bill
  as IMAGE-modality LLM tokens already; add a VISION split only if reporting needs it.
- Brand: consumer name suggestion **"VegaDūta Sight"** (model names in details panel
  only, per Program B rule 1); capability chip `sight` added to
  `agentIdentity.agentCapabilities()` (eye icon) when camera use is enabled/allowed;
  AppShell/help/Features/i18n/E2E per the Program B "integration completeness bar".
  All UI strings through the i18n namespace before any flag defaults on
  (`en.json` append-only, every key must exist).

### 4.5 Privacy & compliance (non-negotiables)
- Explicit gesture to start; always-visible on-air indicator; frame counter; hard stop.
- **Frames are ephemeral by default**: analyzed, never written to storage; only text
  (descriptions, transcripts, remembered facts) persists. Any future recording mode
  is a separate consent surface (egress + retention policy + region disclaimers,
  same posture as M1's two-party-consent note).
- Bystander reality: webcams see other people. The consent acknowledgement mirrors
  M0's "participants are informed" checkbox for watch/live modes.
- Guardrails: frame-derived text flows through the existing guardrail/PII pipeline
  unchanged (it's just model output); consider a standing system-prompt injection for
  vision sessions ("do not attempt to identify individuals") — cheap, honest.
- Erasure: `PerceptionSession` + memory keys join the existing data-erasure surface.

---

## 5. "All senses" — honest roster and where this fits

| Sense | Status today | Path |
|---|---|---|
| **Hearing (speech)** | ✅ Shipped — push-to-talk `/turn` SSE + LiveKit live voice + STT provider ladder | — |
| **Voice (speaking)** | ✅ Shipped — ElevenLabs→Sarvam→browser TTS ladder | — |
| **Sight (live camera)** | ❌ None (this document) | Programs S0 → S1 → S2 + S-edge |
| **Sight (files/images)** | ✅ Shipped — dual attachment contracts + modality gating | already the S0 backbone |
| **Screen sight** | Designed, unbuilt (M0 `getDisplayMedia`) | same `PerceptionSession` pipeline; tab-share video frames are a trivial S1 extension |
| **Hearing (ambient/meetings/calls)** | Designed, unbuilt (M0/M1) | shares the perception pipeline |
| **Reading (documents/OCR)** | ✅ Shipped — knowledge pipeline, sidecar extract/OCR | — |
| **Memory** | ✅ Shipped — session/agent/tenant/graph + planned auto-extraction (F-2.2) | perception sessions feed it |
| **Action (hands)** | ✅ Partial — guarded tool registry, MCP, channels, `core/devices` long-poll gateway | S-sessions can *trigger* tools ("when you see X, do Y") — that's the killer combination and needs zero new tool code, only the watch-mode standing instruction |
| **Smell/taste/touch** | ❌ No sensor path; be honest — IoT/MCP device integrations are the only realistic proxy | out of scope |

The strategic point: **sight is the last big missing input sense, and it composes
with everything already built** — a watch-mode instruction plus the existing guarded
tool registry gives "see something → do something" agents (inventory alert →
WhatsApp message; person at door → notification) with no new action machinery.

### Real use cases to design/demo against
1. **Show-and-ask support**: point the camera at a router/appliance/error screen; the
   agent troubleshoots ("show me the back panel"). Snapshot mode; day-one demo.
2. **Document/物 inspection**: invoices, labels, serial numbers, handwriting — frame +
   existing OCR-strong vision models. Snapshot.
3. **Kirana/retail shelf & inventory check**: sweep the camera; agent lists gaps /
   reads stock. Burst + watch. (On-brand with the India landing narrative.)
4. **Monitoring with standing instructions**: "watch this and tell me when the
   3D-print fails / the pot boils / a customer walks in." Watch mode + tool trigger.
5. **Accessibility**: continuous "describe my surroundings" for low-vision users.
   Live mode (S1/S2); the strongest ethical showcase.
6. **Coaching**: posture/form/presentation feedback at low fps. Watch/live.

---

## 6. Implementation plan (waves, scopes, estimates)

| Wave | Deliverables | Scope owners | Est. |
|---|---|---|---|
| **S-A: Snapshot** | `CameraPanel` (capture core + snapshot into existing chat), modality gating, on-air UX rules, `sight` capability chip, i18n | `web` | 1–2 wks |
| **S-B: Watch + governance** | Watch mode w/ pixel-diff gate; `POST /api/vision/sessions` + `VisionUsageService` quotas (default off); `camera.capture.start` approval pattern; session-end memory summarisation; Features-page row + `plan_limits.live_vision_allowed` (Flyway V166+ shared namespace — check current head) | `web` + the core maintainers | 2–3 wks |
| **S-C: Edge vision** | E-5 SmolVLM landing + `routeVisionTurn()` seam + no-silent-escalation consent; FREE tier story complete | `web` | 2 wks (rides E-5) |
| **S-D: Live session** | Worker perception mode (video subscribe, server-side sampling, scene state, TTS replies); `PerceptionSession` generalized with M0; `voice-agent` promoted to default stack; UDP path verified on staging | the core maintainers + `voice-agent` (+`web` toggle) | 3–4 wks |
| **S-E: Realtime ceiling** | Gemini Live bridge evaluation (tool-call re-entry through guarded registry is the hard design problem — spike first), BYOK-vs-pool product call | the core maintainers + `voice-agent` | spike 1 wk, then decide |

Sequencing logic: S-A ships value in days and de-risks nothing-works-yet UX; S-B makes
it governable (mandatory before any real tenant use); S-C completes the free tier;
S-D is where "all senses in one session" becomes real; S-E only if latency demand
proves out. S-D should be co-scheduled with Program M's Wave 3/4 — they share the
perception pipeline and the worker.

### Components touched
This spans `web`, `core` and `voice-agent`, and touches nothing in `sidecar` (frame analysis
deliberately does NOT go through sidecar — it's LLM-native, not a CPU model). Agree the
`/api/vision/sessions` contract and the inline-attachment `cameraFrame` flag before S-B
starts; backend lands first.

### Key risks
- **Cost runaway**: mitigated by change-gating + hard fps ceilings + quota service
  default-off + per-user budget. Never ship watch/live without S-B's governors.
- **UDP/WebRTC reachability** (S1 only): known constraint; staging verification
  gate; S0 is transport-immune.
- **Model pool capability drift**: FREE/pool tenants' modality profile can misreport
  (documented `ModelModalitiesService` gap) — S0 surfaces a clean 415 today; fix the
  pool-model resolution as part of S-B polish.
- **Privacy incidents** are the real tail risk: ephemeral-frames default, consent
  gates, on-air indicator, and erasure are launch-blocking requirements, not polish.
- **Safari/mobile quirks**: `toBlob` JPEG + getUserMedia are broadly safe; test iOS
  Safari early (autoplay-muted preview, background-tab throttling of watch timers —
  Page Visibility pause is the fix and the honest behavior anyway).
