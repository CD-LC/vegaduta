# LLM Latency & Model Selection

**TL;DR — end-to-end response latency is dominated by the LLM model/provider you
route to, not by platform code.** A measured 8169 ms response broke down as
~8088 ms in the model call and ~80 ms of platform overhead. If responses feel
slow, the first and highest-leverage lever is *which model on which provider* a
turn runs against — not caching, not concurrency, not app tuning (though those
help too, see the end).

For the platform's own share of a turn (database transactions, caches, query budgets) see
[Hot-path performance](HOT-PATH-PERFORMANCE.md).

This doc explains how model selection works in this platform and gives the
recommended paths for keeping responses fast **without compromising quality**.

---

## 1. Where the latency actually goes

Every turn's real LLM latency is recorded per call in `llm_call_log`
(`latency_ms`, `model`, `provider_name`, `success`, tokens). To see it:

```sql
SELECT started_at, latency_ms, provider_name, model, success, completion_tokens
FROM <tenant_schema>.llm_call_log
ORDER BY started_at DESC LIMIT 20;
```

A worked example from this deployment (why "8 seconds for a one-line reply" is a
*model* problem, not an app problem):

| model | provider | prompt→completion tokens | latency | note |
|---|---|---|---|---|
| `gemma-3-27b-it` | OpenRouter (free) | 258 → 27 | **8088 ms** | 27 output tokens taking 8 s = time-to-first-token / queueing, not generation |
| `gemma-3-27b-it` | OpenRouter (free) | — | 4249 ms | **`429 Too Many Requests`** — free tier rate-limited (~60% of calls) |

A large dense model (27B) on a **free, shared, rate-limited** endpoint is
inherently multi-second and frequently `429`s. No app-side optimization fixes
that; the fix is the model/provider.

---

## 2. The free-tier / platform-hosted pool

FREE-plan tenants (and paid tenants who opted into `usePlatformLlm`) route
through the **platform-hosted pool**, not their own key. Mechanics:

- Rows live in `control_plane.platform_hosted_llm_provider`
  (`label`, `base_url`, `model`, encrypted key, `enabled`, `priority`).
- `PlatformHostedLlmProviderService.resolveTopPriorityEnabled()` picks the
  **lowest-`priority`-number enabled row**. A master switch
  (`hosted_llm_pool_settings.enabled`) can turn the whole pool off.
- Managed by platform admins via **`/api/platform/hosted-llm-providers`** (the
  Platform admin screen), or directly on the row.

**Pick a fast model for this pool.** Recommended:

- ✅ **Groq** (`llama-3.1-8b-instant` for max speed, or `llama-3.3-70b-versatile`
  for much better quality at still-low latency) — Groq's LPU serves at hundreds
  of tokens/sec with sub-second TTFT. This is the current active default.
- ✅ Other low-latency OpenAI-compatible hosts (Together, Fireworks, Cerebras).
- ❌ **Avoid large dense models on free OpenRouter tiers** (`*-27b`, `*-70b`
  `:free`) — rate-limited (`429`) and queued behind paid traffic (seconds to
  tens of seconds). This was the cause of the 8 s example above.

**Latent-priority caveat:** a *disabled* row still has its `priority`. If you
re-enable a slow provider whose number is lower than the fast one, it silently
becomes primary again. Keep slow rows disabled, or give them a higher `priority`
number than the fast row so the fast one always wins.

**8B vs 70B tradeoff:** `llama-3.1-8b-instant` is the fastest and cheapest but
weaker on complex reasoning; `llama-3.3-70b-versatile` is a large step up in
quality and still fast on Groq (but has lower free-tier rate limits). Choose per
what the free tier is *for* on your deployment.

---

## 3. Recommended path for latency-sensitive tenants: BYOK with a fast model

**For any tenant that cares about latency, the recommended setup is BYOK
(bring-your-own-key) with a fast hosted model — not the shared free pool.** BYOK
gives dedicated quota (no shared free-tier `429`s), first-class latency, and
streaming.

Best fast, high-quality models (sub-second to low-second TTFT):

| Provider | Fast model | Why |
|---|---|---|
| **Google Gemini** | `gemini-2.0-flash` / `gemini-2.0-flash-lite` | Purpose-built for low latency + long context; excellent quality/speed ratio |
| **Anthropic** | `claude-haiku-4-5` | Fast Claude tier, strong quality, great tool-use |
| **OpenAI** | `gpt-4o-mini` (or a mini/flash tier) | Low latency, cheap, solid quality |
| **Groq (BYOK)** | `llama-3.3-70b-versatile` | Fastest inference for open models |

How a tenant sets it up:

1. **Providers** page → activate the provider with the tenant's own API key.
2. On the agent, set its **model** (and provider) to the fast model above
   (`PATCH /api/agents/{id}/model`).
3. Optionally set a **fallback** provider/model (`/{id}/fallback`) so a primary
   hiccup retries once on a second fast model instead of erroring.

**Do not put a big dense reasoning model on the hot path for latency-sensitive
chat.** Reserve larger/slower models for `CAREFUL`/`RIGOROUS` reasoning-depth
agents where verified quality is worth the extra seconds — that's an explicit,
per-agent choice, not the default.

---

## 4. Platform features that further cut latency (already built)

These stack on top of a good model choice — none of them can rescue a slow
model, but each removes real time:

- **Semantic response cache** (opt-in per agent,
  `PATCH /api/agents/{id}/semantic-response-caching`) — near-identical repeat
  questions return in ms instead of a full model call. Only caches tool-free
  turns; high similarity threshold. Best for FAQ/informational agents. See
  `SemanticResponseCacheService`.
- **Exact-match response cache** (`/{id}/response-caching`) — literal repeat
  questions, zero ambiguity.
- **Cost/economy routing** (`/{id}/cost-routing`) — route classifier-judged
  *simple* turns to a cheaper/faster economy model automatically.
- **Parallelized per-turn overhead + virtual threads** (Java 21) — the ~80 ms of
  history/tool/memory I/O now runs concurrently, and the request tier runs on
  virtual threads for far higher concurrency under load.
- **Streaming** (web/SSE) — for a chat UI, perceived latency ≈ time-to-first-
  token; a fast model + streaming feels instant even for longer answers.
- **Platform-pool warm-up** (v0.1.8) — a hosted model that scales to zero (or a
  local Ollama that unloaded it) can take 70 s or more on the first request. The
  console calls `POST /api/platform-llm/warm` when someone signs in or opens a
  chat; `PlatformPoolWarmer` (`core/chat`) sends a one-token request to the
  pool's top row, at most once per `app.platform-llm.warm.interval-seconds`
  (default 240) per core instance. Off with `APP_PLATFORM_LLM_WARM_ENABLED=false`.
- **Local models kept loaded** (v0.1.8) — the Compose `local-ai` overlay loads the
  model at start and keeps it in memory (`OLLAMA_KEEP_ALIVE=-1`) and sends
  `reasoning_effort: none` (`LOCAL_LLM_REASONING_EFFORT`, core property
  `app.llm.ollama.reasoning-effort`) so reasoning models skip their hidden
  thinking pass.

---

## 5. Quick triage checklist when "responses are slow"

1. `SELECT latency_ms, model, provider_name, success FROM <tenant>.llm_call_log
   ORDER BY started_at DESC LIMIT 20;` — is the time in the model call?
2. Are there `429`s / failures inflating latency (failed attempt + fallback)?
3. What model/provider is the turn actually using? (FREE → the hosted pool row;
   BYOK → the agent's `model`.)
4. Is it a large dense model on a free/shared endpoint? → switch to a fast model
   (§2) or move the tenant to BYOK-with-a-fast-model (§3).
5. Only after the model is fast: enable semantic cache for repeat traffic, and
   confirm streaming is on for the UI.
