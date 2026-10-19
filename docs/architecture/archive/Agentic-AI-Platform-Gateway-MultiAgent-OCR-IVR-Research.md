# Research: Sandbox Re-validation, LLM Gateway, Multi-Agent Learning, Agent Lifecycle, OCR/IVR

Companion to `Agentic-AI-Platform-Reliability-Governance-SDLC-Build-Plan.md`. Pure research + one scoped gap-check, no code written yet. Covers five topics raised in one message; each ends with a recommendation.

## 1. Is a per-run ephemeral Docker container the right isolation for the SDLC agent?

**Short answer: no, not alone.** 2026 consensus is that standard containers share the host kernel and are not considered safe for executing untrusted/AI-generated code in production — a container escape is a kernel exploit away. The SDLC agent is the highest-risk surface in this whole platform (it runs Claude Code with shell access against a tenant's real repo), so it deserves the strongest isolation tier, not the weakest.

Three-tier spectrum, weakest to strongest:
- **Standard containers (Docker)** — fast, cheap, shared host kernel. What Stage 1 originally proposed.
- **gVisor** — a user-space kernel (Sentry) intercepts syscalls before they reach the real kernel. Meaningfully safer than raw Docker, still container-shaped (works with existing Docker/K8s tooling via `runsc` runtime), moderate performance overhead.
- **microVMs (Firecracker / Kata Containers)** — a dedicated, minimal VM per workload with hardware-enforced (KVM) isolation. Strongest boundary, ~100ms-class cold starts on modern implementations, purpose-built for exactly this "run untrusted code per request" use case (Firecracker was built by AWS for Lambda).

**Options for who runs it:**
- **Self-hosted Firecracker/Kata via K8s (Kata Containers runtimeClass)** — full control, fits this platform's existing K8s-based deployment plan (`CLOUD_DEPLOYMENT_AND_COMPLIANCE_PLAN.md`), no per-run vendor cost, but real ops burden (image lifecycle, host patching, networking).
- **E2B** — Firecracker-backed sandbox-as-a-service, ~$0.05/vCPU-hr, 24hr max session on the Pro tier, purpose-built for AI code execution, has a persistent-filesystem "template" concept that fits a checked-out repo well.
- **Daytona** — similar space, Docker-based (not microVM) but with the fastest cold start (~90ms) of the options surveyed; weaker isolation than E2B since it's still shared-kernel.
- **Modal** — GPU-capable, good if the SDLC agent ever needs to run tenant test suites with GPU dependencies; $30-250/mo tiers, less purpose-fit for "checkout a repo and run a shell."

**Recommendation:** don't ship Stage 1 on raw Docker. Two credible paths, both changing "ephemeral Docker sandbox" to "ephemeral microVM sandbox":
1. **Managed (E2B)** for v1 — fastest to ship, isolation is someone else's operational problem, per-run cost is small relative to the LLM cost of a Claude Code run anyway. Reassess self-hosting once volume justifies the ops cost.
2. **Self-hosted Kata Containers** if data residency/compliance requires the sandbox to never leave the tenant's chosen region/cluster (relevant given this platform's compliance plan already discusses region-pinned deployment).

Given BYOK (tenant's own Anthropic key) and Option C (auto-merge) were both already decided, and Option C raises the stakes of any sandbox escape (an escaped process with a merge path is worse than one that's merely wrong), the isolation tier should not be the place to cut a corner. Recommend **E2B for v1**, revisit self-hosted Kata post-launch.

## 2. LLM gateway / orchestrator — monitor and segregate calls before they reach the LLM, plus fallback

This platform doesn't have one central point every LLM call passes through today — `AgentChatService`, `HostedLlmChatClientFactory`, and the various provider chat-client factories (OpenRouter/Grok/Gemini/Sarvam/Anthropic) each build/call a `ChatClient` directly, with `LlmMetricsAdvisor` as the only cross-cutting concern currently wired in (metrics, not policy). Gap #829 (provider fallback) already added retry/degradation at the factory level — a gateway would generalize and centralize that, plus add the "screen before it reaches the LLM" capability that's explicitly being asked for here (which #829 doesn't do — it's fallback after a call already went out).

**What a gateway is responsible for, per 2026 practice:**
- **Request routing** — pick a model/provider by cost, latency, or capability (e.g. cheap-model-first, escalate to a stronger model only if the cheap one's output looks insufficient).
- **Pre-call screening / segregation** — validate and moderate the outbound prompt before it leaves the platform: PII redaction, prompt-injection pattern checks, per-tenant policy (e.g. a tenant's autonomy tier from #830 should be enforceable here, not just trusted downstream), token/rate budget checks.
- **Fallback/retry** — on error, rate-limit (429), or a refusal, automatically retry against a different provider/model without the caller needing to know.
- **Rate limiting** — per user/tenant/key, token-based not just request-count-based (an LLM call's cost varies wildly by token count, unlike a typical API call).
- **Post-call moderation** — screen the response before it's returned/sent to a channel, distinct from pre-call screening.

**Build vs. buy options surveyed:**
- **LiteLLM** — open-source, self-hostable, OpenAI-compatible proxy in front of 100+ providers. Closest fit: this platform already has its own multi-provider abstraction (`ProviderName` enum, per-provider factories), so LiteLLM would either replace that abstraction (large rework) or sit awkwardly alongside it (duplication). Best fit if the team wanted to stop maintaining custom factories per provider.
- **Portkey** — SaaS-first, adds semantic caching and guardrails on top of gateway basics. Fastest to add moderation/caching, but is an external hop for every LLM call — a new third-party dependency in the hot path and a new place tenant prompts transit.
- **Kong AI Gateway** — enterprise-grade, but assumes existing Kong API-gateway infrastructure; this platform has none, so it's a bigger adoption cost than the others.
- **Apache APISIX** — open-source, has load-balancing/retry/fallback/rate-limiting/content-moderation plugins for multi-LLM already built in; a real self-hosted option without being LiteLLM-shaped.
- **Cloudflare AI Gateway / Bifrost** — Cloudflare AI Gateway is attractive if this platform's edge/CDN is already Cloudflare; Bifrost advertises very high failover uptime but is the least-established of the group.

**Recommendation: build this in-house as a thin internal layer, not adopt an external product, for v1.** Reasoning: the platform already has 90% of the pieces (`ChatClientRegistry`/factories for routing, `LlmMetricsAdvisor` for observability, #829's fallback logic, `GuardrailService`/langchain4j-guardrails for content policy, `TenantStatusGuard`/kill-switch/circuit-breaker for the "stop calls" side). What's missing is one seam that every call funnels through where pre-call screening + routing + fallback are composed together, the same architectural move already made for the kill switch (`AgentChatService.chat()` as universal chokepoint). Concretely: introduce an `LlmGatewayService` (or extend the existing chat-client resolution path) that wraps every outbound call with (a) a pre-call guardrail/policy check, (b) the existing fallback logic from #829 generalized to run through this one seam instead of scattered per-factory, (c) token-bucket rate limiting per tenant. This avoids a new network hop (LiteLLM/Portkey would add one) and avoids replacing an abstraction that already works. Revisit APISIX only if self-hosting a dedicated gateway process becomes worth the ops cost (e.g. once non-Java channels need the same policy layer).

## 3. Multi-agent platform: workflow-triggers-workflow, data passing, shared cross-agent learning

Two distinct asks bundled together — worth separating because they map onto different existing subsystems.

**3a. One workflow triggers another / passes data.** Not implemented today. `WorkflowExecutionEngine` walks one `WorkflowGraphDto` node-by-node in-process (or via `TemporalWorkflowExecutionEngine` for the durable path); there's no node type or mechanism for "invoke workflow X, get its output, continue." The event-driven substrate this needs already exists, though: `WorkflowEventPublisher`/`WorkflowEventOutboxRelay` (the Kafka outbox pattern) and `WorkflowExecutionEngine`'s own `WorkflowRun` records could support a new node type — a `TRIGGER_WORKFLOW` node, alongside the existing `TOOL`/`LOOP`/`CODE`/`Approval` node types from #701-707 — that (a) starts a new `WorkflowRun` for a target workflow definition, (b) passes the calling run's output as that run's input, and (c) either fires-and-forgets (async, via the outbox — fits the existing pattern) or blocks for the child run's result (sync, simpler but couples the two runs' lifetimes). Given Temporal is already in place for durable execution, Temporal's native **child workflow** primitive is a strong fit for the sync/blocking case specifically — it's designed for exactly "workflow A starts workflow B and waits," including automatic retry/timeout propagation, which is more robust than hand-rolling it over the outbox.

**3b. Shared learning across agents.** This is the bigger gap. Both memory systems in this codebase are intentionally **agent-scoped, not shared**:
- `AgentMemoryService` (pgvector) is keyed `(agent_id, subject_key)` — one agent's memory of one person, capped at 50 memories per agent.
- `GraphMemoryService` (Apache AGE) — its own javadoc says explicitly: "additive per-agent-per-subject context, never a cross-agent or cross-subject shared graph within a tenant."

That "never cross-agent" design was deliberate (likely to avoid one agent's bad inference poisoning another's behavior), so making agents share learning is a real design reversal, not a bug fix — worth flagging before building. The request as stated ("all agents should share their learning in system context and all should learn from it") implies a **tenant-level shared memory tier**, additive to (not replacing) each agent's private memory: a new scope, e.g. `(tenant_id, subject_key)` or `(tenant_id)` broad facts, that every agent in the tenant can read from and — per the "learn from it" framing — write to. The existing `remember_fact` tool pattern (agent decides something is worth saving) generalizes cleanly: add a second tool, e.g. `remember_shared_fact`, that writes to the tenant-shared table instead of the agent-private one, and extend `recall`/`GraphRecallToolCallback` to query both scopes and merge results. This is additive, contained to `core/agents/memory` and `core/graphmemory`, and doesn't touch the existing per-agent isolation guarantee — an agent that never calls the shared-write tool behaves exactly as it does today.

**Recommendation:** build 3a as a new `TRIGGER_WORKFLOW` node type (async via outbox for fire-and-forget, Temporal child workflow for blocking) and 3b as an additive tenant-scoped memory tier alongside the existing per-agent one, not a replacement. Both are scoped, incremental additions to code already in this repo — no new subsystem needed.

## 4. Unlimited-plan: edit agent, change model, delete agent

This isn't purely a research item — I checked the current code, and it's a **real gap for every tier, not just Unlimited**. `AgentController` today has: create, list, get, duplicate, and narrow `@PatchMapping` endpoints for specific fields (`knowledge-base`, `sarvam`, `tool-access`, `knowledge-access`, `collaborators`, `generation-params`, `tuning-mode`, `experience-mode`, `reasoning-depth`) — but no endpoint to change an agent's **model/provider** after creation, and **no delete endpoint for an agent at all** (only `DELETE /{agentId}/memories/{memoryId}` for individual memories). `EditLockPolicy` already exists and already says PREMIUM/UNLIMITED are "never locked, regardless of age" — so the tier-gating concept this request wants is already half-built; what's missing is the actual edit-model and delete actions to gate.

**Recommendation (build, not just research):** add `PATCH /api/agents/{agentId}/model` (change provider/model, reusing `EditLockPolicy`'s existing lock check so FREE-tier agents past their edit-lock age still can't do this, matching the existing pattern) and `DELETE /api/agents/{agentId}` (hard delete, or soft-delete if workflow/audit references need to survive — worth checking whether `WorkflowRun`/`ToolCallLog` rows FK to `agent_id` in a way that would break on hard delete before deciding). Both should go through `EditLockPolicy` the same way the existing PATCH endpoints do, so this is "add two endpoints using an established pattern," not new infrastructure. Should be quick once explicitly scoped — recommend adding as its own task now that the gap is confirmed, separate from the SDLC work.

## 5. OCR provider abstraction, Spring AI multimodal, low-latency IVR

**5a. OCR today.** Already implemented, not from scratch: Stage F3b (`sidecar/app/extraction.py`, `requirements.txt`) uses `pytesseract` (a wrapper around the open-source `tesseract-ocr` binary) for image ingestion into the knowledge base. So "else use open source OCR reading library" is already the current and only behavior — the gap is exactly what was asked: there's no tenant-configurable cloud-provider option, it's Tesseract unconditionally.

**Cloud OCR comparison (2026):**
- **Google Cloud Vision** — ~$1.50/1,000 units after 1,000 free/month, 200+ languages, strong on scene/general text, but does *not* return table/key-value structure — just text + bounding boxes.
- **AWS Textract** — same base price for plain text, jumps to ~$65/1,000 pages for structured extraction (tables, key-value pairs, typed invoice fields); best if documents are forms/invoices.
- **Azure Document Intelligence** — ~$10/1,000 pages for structured extraction (cheaper than Textract's structured tier), some benchmarks show it outperforming Textract on complex/multi-column layouts; best if already Microsoft-stack-adjacent.
- **Tesseract (current)** — free, no API dependency, 100+ languages, but materially lower accuracy than any cloud option on noisy/handwritten/complex-layout images, and no structured (table/form) extraction at all.

**Recommendation:** add a tenant-admin-configurable OCR provider setting, following the same shape this platform already uses for Sarvam/Twilio/Google credentials (a per-tenant credential table + a provider interface + factory selection): a new `OcrProviderConfig` (tenant-scoped, analogous to `TwilioCredential`/`SarvamCredential`) letting a tenant admin pick **Google Vision, AWS Textract, or Azure Document Intelligence** and supply that provider's credential, with the sidecar's existing `pytesseract` path kept as the **unconditional fallback** when no cloud provider is configured — exactly matching the phrasing "give an option to use any OCR AI service... in tenant admin if required, else use open source OCR." Concretely: the sidecar's `extraction.py` gains a provider-selection branch (call out to whichever cloud API if a tenant config + credential is passed down from the Java side, else keep doing what it does today), and the Java side needs a small new credential/config module plus a `KnowledgeController`/admin-panel UI addition — same shape as the Sarvam Stage 1 credential module, so no new architectural pattern, just a repeat of one already proven in this codebase.

**5b. Spring AI multimodal — confirms the claim.** Spring AI's `UserMessage.media` field already supports image/audio/video content alongside text, and this is the *same* Message API `AgentChatService`/the Stage 4 chat-attachment feature already uses — so audio/video/image handling is not a new integration, it's already wired at the message layer. Spring AI 1.0.6/1.1.5/2.0.0-M5 (April 2026) has `spring-ai-openai` now wrapping the official `openai-java` SDK directly for Chat/Embedding/Image/Audio/Moderation — the user's claim that "Spring AI is capable of handling all audio, video, and almost all AI tasks" is accurate as of 2026, and this platform is already positioned to use more of it (Sarvam's dedicated STT/TTS clients already coexist with this — Sarvam is used for Indian-language-specific quality, Spring AI's native audio path could be the non-Sarvam default/fallback).

**5c. Low-latency IVR / caller-question workflow.** This is a materially different capability from anything in the platform today (webhook-driven text-channel agents, not real-time telephony). 2026 landscape:
- **Standard pipeline** (speech-to-text → LLM → text-to-speech, each a separate hop): 800-1,500ms end-to-end latency.
- **Speech-to-speech models** (e.g. GPT-4o realtime-style): 400-800ms, because there's no intermediate text round-trip.
- **Twilio Conversation Relay** — purpose-built for this: combines low-latency STT/TTS with bring-your-own-LLM, over a WebSocket API, median latency under 500ms, built-in interruption handling (barge-in). Given this platform already has a full Twilio integration (WhatsApp via Twilio, `TwilioCredential`, webhook infra), Conversation Relay is the natural extension rather than a new telephony vendor.
- **IVR replacement framing**: "IVR is menu-driven; voice AI is intent-driven — it understands natural language and can complete workflows end to end without routing every decision through a menu." This reframes "automate the IVR selection" — the ask isn't really "click through menu options faster," it's "skip the menu and let the caller just say what they want," which is what a voice-workflow node would deliver.

**Recommendation:** this is a new, substantial capability (new channel type, new node type, real-time audio infra) — not a small addition like #4. If wanted, it should get its own scoping pass (a `Voice` channel alongside the existing WhatsApp/Telegram/Email channels, likely via Twilio Conversation Relay + a new `WorkflowExecutionEngine` entry point that runs in "respond fast, low-latency" mode rather than the current synchronous full-trace mode) rather than being folded into this round. Flagging it as real, scoped-out work rather than silently skipping it.

## Summary of what's research-only vs. ready to become a task

| # | Topic | Status |
|---|---|---|
| 1 | Sandbox isolation tier | Recommendation given (E2B managed, or self-hosted Kata) — still gates Stage 1, needs your pick before build |
| 2 | LLM gateway | Recommendation given (build in-house, one seam) — needs scoping doc if you want to proceed |
| 3a | Workflow-triggers-workflow | Recommendation given (new node type + Temporal child workflow) — needs scoping |
| 3b | Shared cross-agent learning | Recommendation given (additive tenant-scoped memory tier) — needs scoping, is a design change worth confirming |
| 4 | Agent edit-model / delete | Confirmed real gap, all tiers — small, ready to build once you say go |
| 5a | OCR provider abstraction | Confirmed real gap — small-to-medium, ready to build once you say go (reuses Sarvam-credential pattern) |
| 5b | Spring AI multimodal | Confirmed accurate, already partially leveraged — no action needed unless you want to reduce Sarvam-vs-native-Spring-AI overlap |
| 5c | Low-latency IVR/voice workflow | Real, substantial new capability — needs its own scoping pass, not a quick add |
