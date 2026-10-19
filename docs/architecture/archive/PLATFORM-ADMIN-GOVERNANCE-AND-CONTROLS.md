# Platform Admin — Governance, Controls & Operations (Design + Roadmap)

**Purpose.** A single blueprint for the platform-maintenance admin: budgets &
spend, provider/model config, monitoring & reports, fraud/abuse detection,
content moderation, and the controls to run all of it safely at scale — up to
~1B users. This is a **design + phased roadmap**, not a claim that all of it is
built. It is deliberately grounded in what the codebase **already has** so we
extend and unify rather than rebuild.

> Scope note: the admin *UI* lives in `web-admin/` (a separate agent's scope);
> this doc owns the backend design + the information architecture the UI should
> render. Content-moderation and fraud detection are safety-critical — they are
> designed here to be layered and human-reviewable, never a single rushed model.

---

## 1. What already exists (foundation — reuse, don't rebuild)

The platform is much further along on governance than it looks; the pieces are
just scattered across modules. Inventory (verified in code):

| Concern | Existing building blocks |
|---|---|
| **Content filtering** | `guardrails/`: `PatternBasedGuardrailService` (regex/keyword), `SemanticGuardrailService` (LLM-judge), `GuardedToolCallback`, `ToolCallOutputValidator`, `GuardrailViolationException` |
| **Rate limiting / abuse** | `SignupRateLimiter`, `WorkflowTriggerRateLimiter`, `McpServerRateLimiter`, `LlmGatewayRateLimiterService`, `ToolCircuitBreakerService`, `FreeTierUsageLimiterService` (msg + token, daily/monthly) |
| **Spend / budget** | `LlmGatewayBudgetService`, `LlmCallMetricsService`, `LlmMetricsAdvisor`, `LlmDashboardMetrics`, `LlmCallLog` (per-call tokens/cost/latency), `LlmPricingCatalog` (now multi-provider) |
| **Audit** | `audit/`: `AuditLogService`, `AuditLogController`, `AuditLogEntry` (actor/action/resource/outcome) |
| **Emergency control** | `TenantEmergencyControlService`, `PlatformKillSwitch`, `TenantStatusGuard` (per-tenant + platform-wide stop) |
| **Issues / observability** | `PlatformIssueService` (fingerprinted issues portal), Prometheus/Grafana/Loki wired, Langfuse tracing |
| **Metrics / reporting** | `PlatformTenantMetricsService`, `RlReportingService` |
| **Hosted LLM pool** | `PlatformHostedLlmProviderService` (runtime provider rows + master switch) |
| **Billing** | Razorpay + PhonePe gateways, `PaymentGatewayRegistry` (top-ups build on this) |

**Implication:** ~70% of the governance primitives exist. The work is (a) a few
new subsystems (per-user $ budget, provider fallback chain, fraud/anomaly,
moderation coverage for illegal content), (b) **unifying** them behind one admin
console, and (c) scale hardening.

---

## 2. Requirements → gaps

| You asked for | Status / gap |
|---|---|
| Per-user **$ budget** (e.g. $0.15/user), **configurable amount + window** in admin | ❌ New. Today: per-*tenant*, token-based, env-configured. Build `PerUserBudgetService` + `PlatformSetting`-backed runtime config. |
| **Per-modality** budgets (text/image/voice; video = pay-extra) | ❌ New. Cost metering must span modalities; sub-caps per modality. |
| **Accurate cost** to enforce budgets | ✅ Done — `LlmPricingCatalog` now prices Gemini/GPT/Groq/DeepSeek/Mistral (was Claude-only → $0 bug). |
| **Provider list** (free/cheap, latency, base URL, models) + **fallbacks** | ⚠️ Partial. Hosted pool picks ONE top-priority row (no fallback chain). See §6 + Appendix A. |
| **Paid top-up** when budget hit | ❌ New; build on existing billing gateways. |
| **"You're on Platform LLM" notice** to customers | ❌ New (UI, flagged) — `GET /api/providers/platform-llm` already returns the active label/model; surface as a **Note:** banner. |
| **Reports / spend monitoring** | ⚠️ Partial — LLM dashboard + tenant metrics exist; need per-user spend + modality + export. |
| **Fraud / hacker / misuse / flooding detection** | ⚠️ Partial — rate limiters + audit exist; need an anomaly/abuse-scoring layer + alerts. |
| **Illegal / terrorism / CSAM / restricted / foul language** moderation | ⚠️ Partial — guardrails exist (pattern + semantic); need expanded policy packs, an external safety classifier, and a **human review queue**. |
| **Scale to ~1B users** | ⚠️ Architecture notes in §8. |

---

## 3. Rearranged Platform Admin — proposed information architecture

One **Governance & Operations Console**, organized so an admin finds any control
in ≤2 clicks. Each section maps to existing + new backend services.

```
Platform Admin
├── 1. Overview (health, live spend today, active tenants/users, open alerts)
├── 2. LLM & Providers
│     ├── Hosted pool (providers, models, priority, FALLBACK CHAIN, master switch)
│     ├── Per-task model config (PlatformLlmTaskService)
│     └── Pricing catalog (view/override rates)
├── 3. Budgets & Limits
│     ├── Global defaults ($/user/day, window, per-modality caps)
│     ├── Per-plan overrides (Free / Premium / Unlimited)
│     ├── Per-tenant / per-user overrides
│     └── Top-up / overage policy (payment on exhaustion)
├── 4. Spend & Reports
│     ├── Spend by tenant / user / model / modality / day
│     ├── Token & message volume, cache hit-rate, latency p50/p95
│     └── Exports (CSV / scheduled reports)
├── 5. Safety & Moderation
│     ├── Policy packs (foul language, illegal, terrorism, CSAM, restricted, PII)
│     ├── Guardrail config (pattern + semantic + external classifier thresholds)
│     ├── Review queue (flagged content → approve / block / escalate)
│     └── Blocklists (users, numbers, domains, regions/sanctions)
├── 6. Abuse & Rate Control
│     ├── Rate limits (per channel / per user / per IP / per tenant)
│     ├── Flood protection (message/email bursts, signup abuse)
│     └── Circuit breakers (tools, providers)
├── 7. Fraud & Anomaly
│     ├── Anomaly signals (spend spikes, geo-velocity, token-burn, tool-abuse)
│     ├── Risk score per tenant/user → watch / throttle / suspend
│     └── Alerts (webhook / email / PagerDuty)
├── 8. Audit & Compliance
│     ├── Audit log (actor/action/resource/outcome, searchable)
│     ├── Data residency + subject-erasure (GDPR)
│     └── Kill switch (per-tenant + platform-wide)
└── 9. Tenants & Users (lifecycle, plan, status, impersonation-safe view)
```

---

## 4. Budgets & Limits — the core new subsystem

**Model: a per-principal DOLLAR budget over a configurable window**, decremented
by *every* modality's real cost. Dollars (not tokens) because voice/image/video
aren't token-priced.

- **`PerUserBudgetService`** (mirrors `FreeTierUsageLimiterService`'s Redis
  fixed-window shape): key = `tenant:user:window`; `checkBudget()` before a
  call (block if spent ≥ cap), `recordSpend(costUsd, modality)` after. Separate
  sub-counters per modality so per-modality caps are independent gates.
- **Runtime-configurable** via a `PlatformSetting` (amount + window seconds +
  per-modality split) editable in admin — no redeploy. Precedence:
  per-user override → per-tenant override → per-plan → global default.
- **Window**: configurable duration (default 24h rolling or fixed-daily); "wait
  for reset" message includes time remaining.
- **Overage → payment**: on exhaustion, return a structured `402`-style signal
  the UI turns into a "top up to continue" flow via `PaymentGatewayRegistry`; a
  successful top-up credits the current window.
- **Video generation**: excluded from the included budget — always pay-per-use
  (one clip > a day's text budget; see §7 cost math in the latency doc).

Example: **$0.15/user/day** on Gemini 2.0 Flash-Lite ≈ ~1.1M tokens/day (~750
turns) — generous for text; ~4 generated images; video = pay-extra.

---

## 5. Safety & Moderation (layered, human-reviewable)

Never a single model deciding "illegal." Defense in depth:

1. **Fast pattern layer** (`PatternBasedGuardrailService`) — cheap deterministic
   blocklists: foul language, known restricted terms, PII regexes. Policy packs
   toggled per-tenant/plan in admin.
2. **Semantic layer** (`SemanticGuardrailService`) — LLM-judge for nuanced
   categories (harassment, extremism, self-harm) with admin-tuned thresholds.
3. **External safety classifier** (new, pluggable) — for the highest-stakes
   categories (CSAM, credible terrorism/violence): route to a dedicated
   provider (e.g. a hash-match/known-CSAM service + a policy classifier) rather
   than relying on the general LLM. CSAM detection has **legal reporting
   obligations** — this must be designed with counsel, and matches/reports go to
   a restricted audit trail, never general logs.
4. **Human review queue** (new) — anything above a confidence band is queued for
   an admin/trust-&-safety reviewer: approve / block / escalate, all audited.
5. **Blocklists & sanctions** (new) — users, phone numbers, domains, and
   **geo/region** (cross-border/sanctioned-jurisdiction) checks at inbound.

Every decision writes to `AuditLogService` with the policy that fired.

---

## 6. Providers & fallback (from Appendix A)

Configure a **priority-ordered chain** in the hosted pool (extend
`resolveTopPriorityEnabled` → `resolveChain`) so a `429`/timeout on the primary
transparently falls to the next — the same one-shot fallback pattern
`AgentChatService.callWithProviderFallback` already uses for BYOK, applied to the
pool. Recommended chain (fast → cheap → resilient):

1. **Groq** `llama-3.1-8b-instant` (fastest, near-free)
2. **Gemini** `gemini-2.0-flash-lite` (fast, multimodal, generous free)
3. **Cerebras** / **SambaNova** llama (fast backups)
4. **OpenRouter** *paid* tier (last-resort breadth) — never the free tier (slow/`429`).

---

## 7. Abuse, flooding & fraud

- **Flooding**: per-user + per-channel + per-IP token-bucket limits (extend the
  existing per-tenant limiters); burst caps on inbound WhatsApp/email/mail;
  dedupe (already present for inbound messages).
- **Fraud / anomaly scoring** (new `AbuseSignalService`): spend spikes vs
  baseline, geo-velocity (same user, impossible travel), token-burn rate, tool
  error-abuse, signup velocity → a risk score → auto-actions (watch → throttle →
  suspend via `TenantEmergencyControlService`) + alerts.
- **Alerts**: reuse `PlatformIssueService` fingerprinting; add webhook/email/
  PagerDuty sinks.

---

## 8. Scaling to ~1B users (architecture notes)

- **Metering is hot-path**: keep budget/rate checks in Redis (O(1) `INCR`), write
  cost/audit **asynchronously** (already the pattern) to avoid blocking chat.
- **Redis**: cluster/shard by tenant; counters are naturally shardable by key.
- **Postgres**: `llm_call_log`/audit are append-heavy → **time-partition** +
  roll to cold storage; read replicas for reports; never scan hot tables for
  dashboards — pre-aggregate into rollup tables (hourly/daily) via a job.
- **Reports**: serve from rollups, not raw rows; stream exports.
- **Moderation/classifier calls**: sample + cache; async where not blocking.
- **Multi-region**: data-residency service already exists; pin tenant data +
  route inference regionally.
- **Backpressure**: platform + per-tenant kill switch already exists; add
  per-user circuit breaking.

---

## 9. Phased roadmap

| Phase | Deliverable | Scope |
|---|---|---|
| ✅ **P0** | Multi-provider cost pricing (fixes $0) | done |
| ✅ **P0** | Fast pool default (Groq) + landmine fix | done |
| **P1** | `PerUserBudgetService` + per-modality caps + admin-configurable amount/window | core |
| **P2** | Paid top-up / overage via billing | core |
| **P3** | Provider fallback chain in hosted pool | core |
| **P4** | Spend & reports (per user/model/modality + rollups + export) | core + web-admin |
| **P5** | Fraud/anomaly scoring + alerts | core |
| **P6** | Moderation policy packs + external classifier + review queue | core (safety-reviewed) |
| **P7** | Admin console rearrangement (the §3 IA) | web-admin |
| **P8** | Scale hardening (partitioning, rollups, sharding) | core |
| — | "You're on Platform LLM" Note banner | web (flagged) |

---

## Appendix A — Provider reference (free/cheap, ranked by latency)

| Provider | Free tier | ~$/Mtok in/out | Latency | OpenAI-compatible base URL | Models |
|---|---|---|---|---|---|
| Groq | Yes (rate-limited) | 0.05 / 0.08 (8B) | ★★★★★ | `https://api.groq.com/openai` | `llama-3.1-8b-instant`, `llama-3.3-70b-versatile` |
| Cerebras | Yes | ~0.10 | ★★★★★ | `https://api.cerebras.ai` | `llama-3.3-70b`, `llama-3.1-8b` |
| SambaNova | Yes | ~0.10 | ★★★★☆ | `https://api.sambanova.ai` | `Meta-Llama-3.3-70B-Instruct`, Qwen |
| Google Gemini | Very generous | 0.075 / 0.30 (Flash-Lite) | ★★★★☆ | `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-2.0-flash-lite`, `gemini-2.0-flash` (multimodal) |
| Mistral | Yes | 0.10 / 0.30 (small) | ★★★☆☆ | `https://api.mistral.ai` | `mistral-small-latest` |
| OpenAI | No | 0.15 / 0.60 (mini) | ★★★★☆ | `https://api.openai.com` | `gpt-4o-mini`, `gpt-4.1-mini` |
| Anthropic | No | 1 / 5 (Haiku) | ★★★★☆ | (native) | `claude-haiku-4-5` |
| DeepSeek | Small | 0.27 / 1.10 | ★★★☆☆ | `https://api.deepseek.com` | `deepseek-chat` |
| OpenRouter | Yes (SLOW/429) | varies | ★☆☆☆☆ free | `https://openrouter.ai/api` | aggregator — paid only |

**Recommended pool config:** Groq (primary) → Gemini Flash-Lite (multimodal
fallback) → Cerebras (backup). Add each provider's key in **Platform Admin →
LLM & Providers**. See also `LLM-LATENCY-AND-MODEL-SELECTION.md`.

---

## §10 Diagnostics & Observability (single pane in Platform Admin)

Goal: the admin **diagnoses everything from one portal** — no hunting across
tools. Two tiers of data:

### Already IN-APP (surface these natively — no external tool needed)
These are exposed by core APIs today; the admin should render them directly:
- **LLM cost / latency / tokens** — `LlmCallLogController` + `LlmDashboardMetrics`
  (per-agent/session/model spend, success rate, p50/p95 latency).
- **Audit log** — `AuditLogController` (actor/action/resource/outcome, searchable).
- **Issues portal** — `PlatformIssueService` (fingerprinted platform issues).
- **Tenant metrics** — `PlatformTenantMetricsService`.
- **System status** — `SystemStatusController` (core/sidecar/dependency health).

### External tools (deep-link now; embed via iframe later)
Local dev endpoints (map to the deployed hostnames via Caddy/ingress in prod):

| Tool | URL (local) | Health | Use |
|---|---|---|---|
| **Grafana** | http://localhost:3003 | ✅ | Dashboards; also the UI for Loki logs + Prometheus metrics |
| **Loki** (logs) | via Grafana → Explore | ✅ | Log search (no standalone UI; query through Grafana) |
| **Prometheus** | http://localhost:9090 | ✅ | Raw metrics / PromQL |
| **Langfuse** (LLM traces) | http://localhost:3002 | ⚠️ **unhealthy** | LLM tracing — needs a fix; until then use the in-app LLM dashboard |
| **Temporal UI** | http://localhost:8088 | ✅ | Durable workflow executions |
| **Core actuator** | http://localhost:8080/actuator | ✅ | health, metrics, prometheus scrape |
| (tunnel) Caddy | http://localhost:8899 | ✅ | single-origin proxy for remote access |

### Implementation approach (web-admin)
1. **Phase A (fast):** a "Diagnostics & Observability" admin section with
   deep-link buttons (open Grafana/Prometheus/Langfuse/Temporal in a new tab) +
   native panels for the in-app APIs above. Links come from config
   (`OBSERVABILITY_*_URL` env) so prod hostnames differ from local.
2. **Phase B:** embed Grafana panels + Langfuse traces via iframe (requires
   Grafana `allow_embedding` + a scoped anonymous/org token, and CSP/frame-
   ancestors allowances) so dashboards render inside the portal.
3. **Fix Langfuse** (`langfuse-web` unhealthy for 26h+) so LLM tracing works, or
   rely on the in-app `LlmCallLog` dashboard which already has cost/latency.

Env-driven URLs (so the portal shows the right links per environment):
`GRAFANA_URL`, `PROMETHEUS_URL`, `LANGFUSE_URL`, `TEMPORAL_UI_URL`.
