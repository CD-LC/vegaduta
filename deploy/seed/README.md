# B9-PR1 seed fixture

Seeds a running `deploy/docker-compose.yml` (+ `docker-compose.ci.yml`)
stack with two tenants for `.github/workflows/stack.yml`'s full-stack CI
smoke test. See `seed.sh`'s own header comment for the full provisioning
sequence and reasoning.

## Usage

```bash
cd deploy
docker compose -f docker-compose.yml -f docker-compose.ci.yml up -d --wait \
  postgres pgbouncer redis keycloak-db keycloak redpanda \
  temporal-postgresql temporal minio sidecar mock-provider core web
bash seed/seed.sh
```

Requires on the machine running this script (not inside any container):
`psql`, `docker` (for the Flyway migration step — pulls `flyway/flyway`),
`curl`, `jq`.

## What gets created

| Schema | Plan | Notes |
|---|---|---|
| `seed_free_tenant` | FREE | 3 agents, 1 knowledge collection (2 fixture chunks), 1 workflow. FREE tenants never use BYOK; they chat through the platform-hosted pool. The seeded `platform_hosted_llm_provider` row (`mock-provider`) carries a plaintext placeholder key that core cannot decrypt, so core skips it (see "Known limitation" below): register the mock through the platform-admin API before expecting FREE-tier chat to answer. |
| `seed_premium_tenant` | PREMIUM | Stub `llm_provider_credential` (OPENAI, placeholder ciphertext) + `use_platform_llm=TRUE`, so it also reaches `mock-provider` in CI without a real key. No business data seeded here — see `seed.sql`'s header for why. |

Keycloak users (same shared `agentic-ai` realm, `tenant-admin` role):
`seed-free-admin@example.test` / `seed-premium-admin@example.test`,
password from `SEED_USER_PASSWORD` env (default `SeedFixture!2026`).

## Idempotency

Every SQL insert is `ON CONFLICT`/`WHERE NOT EXISTS`-guarded on a fixed
seed UUID or natural unique column; the Keycloak step checks for an
existing username first. Re-running against an already-seeded stack is a
safe no-op.

## Standing caveat

Not run against a real Docker daemon in-sandbox — written and manually
cross-checked against the live Flyway migrations / entity columns / realm
export, not executed. See `seed.sh`'s header for the same caveat every
other doc in this repo carries.

## Local dev: FREE-tier agent creation and chat

If your local stack has zero rows in `control_plane.platform_hosted_llm_provider`,
FREE-tier signup completes fine but agent creation fails with "No active LLM
provider yet" - `core` has no generic "provider base URL" env var for this
(see `docker-compose.ci.yml`'s header comment); the base URL is a DATA row,
not compose config.

### Replaced: the raw-SQL provider seed

`local-hosted-llm-provider-seed.{sh,sql}` were removed. They inserted a row whose
`encrypted_api_key` was the plaintext `unused-mock-key`, which core cannot
decrypt (raw SQL cannot produce AES-GCM ciphertext under `APP_ENCRYPTION_KEY`),
so the row was always left out of the hosted pool. (The CI `control-plane-seed.sql`
still carries that placeholder row for its own purposes.) Use the startup
bootstrap instead.

### Local Ollama as the FREE pool (no manual API calls)

Set these on core (Compose passes them through; see `deploy/compose/.env.example`
and `docs/configuration.md` "Local LLM pool bootstrap"):

```bash
APP_LOCAL_LLM_BOOTSTRAP_ENABLED=true
APP_LOCAL_LLM_BOOTSTRAP_BASE_URL=http://ollama:11434
APP_LOCAL_LLM_BOOTSTRAP_MODEL=llama3.2
APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS=ollama:11434
```

On startup the api shape creates the pool row through the normal service (key
encrypted, SSRF guard applied), enabled and ahead of other rows. It is
idempotent and never fails startup; if the model is not pulled it logs the exact
`ollama pull <model>` command.

### Stub provider (canned answers)

1. Bring up the stub (same one `seed.sh`/CI use, via a local-only compose
   override kept separate from the base file, see `docker-compose.ci.yml` for the
   opt-in reasoning):

   ```bash
   cd deploy
   docker compose -f docker-compose.yml -f docker-compose.local-llm.yml up -d mock-provider
   ```

2. Register it through the API as a platform-admin, which validates and
   encrypts the key properly: `POST /api/platform/hosted-llm-providers` with
   base URL `http://mock-provider:9101`, model `mock-gpt` and any non-empty key
   (the stub does not check it). Chat responses are canned, not a real model.
   For real answers use the Ollama bootstrap above, or register a real
   OpenAI-compatible provider (Groq, Together AI, Fireworks, Deepinfra) with
   your own key the same way.
