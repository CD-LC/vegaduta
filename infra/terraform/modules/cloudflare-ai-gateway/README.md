# cloudflare-ai-gateway

Puts Cloudflare's AI Gateway in front of the hosted LLM pool
(`control_plane.platform_hosted_llm_provider` in the app repo) as a
transparent proxy — same API keys, same providers, gateway just observes/
caches/rate-limits the traffic in between. Point a pool row's `base_url` at
`gateway_base_url` + `/{provider-slug}` to route it through the gateway.

Requires the **cloudflare/cloudflare v5** provider (`cloudflare_ai_gateway`
does not exist in v4); the other Cloudflare modules here use v4 syntax, so
call this one from a separate root module or provider alias.

## What's Terraform-managed here (verified against Cloudflare's docs)

- **Logging** (`collect_logs`) — on by default.
- **Caching** (`cache_ttl`) — short TTL (5 min default) since most traffic
  through this pool is live conversation, not static content. Cloudflare's
  documented max is 1 month; this module deliberately doesn't go anywhere
  near that.
- **Rate limiting** (`rate_limiting_*`) — applies **uniformly to the whole
  gateway**, not per API key. That's a real limitation for the "let the
  free-tier key run until it's actually exhausted, then fail over" goal —
  Cloudflare's gateway-level limit can't distinguish which pool row a
  request is for. Per-key protection still has to come from either (a) the
  provider's own rate limit (which is what triggers the pool's existing
  fallover today, unchanged), or (b) running one gateway per pool row
  instead of one shared gateway — worth revisiting if the uniform limit
  turns out to be too blunt in practice.

## What's deliberately NOT implemented here

- **Cross-provider fallback / the "Universal Endpoint"** — Cloudflare's
  gateway can do its own fallback across providers. Not used here on
  purpose: the app's own `PlatformHostedLlmProviderService` already does
  tenant-aware, circuit-breaking priority fallover
  (`resolveEnabledChain()` + `HostedFailoverPolicy`), and running two
  failover systems against each other is how you get a confusing incident.
  This module routes each pool row through the gateway as a **plain
  per-provider proxy** — the app stays the one thing deciding which row to
  call.
- **Guardrails, DLP, spend limits and Evaluations** — real Cloudflare AI
  Gateway features (the v5 schema exposes `guardrails`, `dlp`,
  `spend_limits`, ...), but not wired up here. Configure them in the
  dashboard or extend this module.
- **Cloudflare-billed third-party usage** ("pay for OpenAI/etc. through your
  Cloudflare invoice") — not used; the platform already manages its own
  provider API keys and billing per account, and
  routing spend through a second billing path would just make the existing
  per-account cost tracking harder to reason about, not easier.

## Before trusting this in production

Run one real test call through the gateway (e.g. `curl` against
`gateway_base_url` + `/{provider}/...` with the provider's normal API key)
before switching every pool row over. Confirm the response matches calling
the provider directly and that a log entry shows up in the Cloudflare
dashboard, before flipping the `base_url` on rows that serve real traffic.
