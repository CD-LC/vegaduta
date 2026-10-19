# End-to-end business-case suite

Playwright suite driving the real Next.js console against a real running `core` + `sidecar` + Postgres + Keycloak + Redis stack. No mocking anywhere - every spec is a real HTTP round trip, same "real stack, real data" convention as every `STAGE-*-VERIFICATION.md` doc in the repo root.

## What's covered

| Spec file | Business case |
|---|---|
| `01-signup-and-login.spec.js` | Self-service signup form, duplicate org/email rejection, Keycloak login, logout |
| `02-providers-agents-chat.spec.js` | LLM provider activation (+ invalid-key rejection), agent creation, streaming chat |
| `03-mcp-registry.spec.js` | Registering/enabling/disabling an MCP tool server |
| `04-knowledge-collections-and-multimodal.spec.js` | Collection CRUD/hierarchy, `.md`/PDF/DOCX/image/audio/video ingestion, search, async job polling |
| `05-agent-access-control.spec.js` | Per-agent tool allow-list and knowledge-collection allow-list |
| `06-admin-users-and-roles.spec.js` | Creating a teammate, assigning roles, their forced-password-change first login |
| `07-approvals-and-audit.spec.js` | HITL approve/reject flow, per-action policy override, audit log |
| `08-channels-whatsapp.spec.js` | Twilio credential activation (+ rejection) via the Twilio tab, default agent routing, fresh-tenant empty inbound log |
| `09-multi-tenant-isolation.spec.js` | Two tenants, zero cross-visibility (UI **and** direct API probe) |
| `10-api-security.spec.js` | Webhook signature rejection, unauthenticated/under-privileged 401/403s |
| `11-workflow-designer.spec.js` | Canvas build/save-draft/validate + the free-tier 3-workflow cap (UI), webhook/schedule triggers + a synchronous run (API-level, since ACTIVE + a real trigger→output connection can't be driven through React Flow headlessly) |
| `12-quick-task.spec.js` | Delivery-number gate, quick-fill example chips, Task History empty state (UI); recent/lookup endpoints (API-level - a real run needs a working agent + LLM provider, same boundary as spec 02) |
| `13-user-created-tools.spec.js` | SpEL sandboxed tool create/test/toggle/delete, required-field validation |
| `14-issues-portal.spec.js` | Admin-only gate, empty state + status tabs (UI); a real FAILED workflow run recorded as an issue, then acknowledged and resolved (API-level, full lifecycle) |
| `15-agent-templates.spec.js` | Audience filtering, picking a template pre-fills the Agents page create form |
| `16-google-integration.spec.js` | Admin-only gate, disconnected empty state, the `?google=connected\|error` OAuth-callback param handling (a real Google consent round-trip can't be automated) |
| `17-social-channels.spec.js` | Admin-only gate, required-field validation for Messenger+Instagram/Slack/Discord/X selected via their `.ax-ch-card` in ChannelsPanel.js's grid (a real app credential for any of the four can't be automated), X's `?x=connected\|error` callback handling |
| `18-subscription-tiers.spec.js` | The 5-agent free-tier cap + the shared UpgradePrompt dialog it opens (UI); `POST /api/tenant/upgrade`'s admin-only gate and real Razorpay subscription handoff (API-level - a real payment can't be completed) |
| `19-web-admin-platform-app.spec.js` | The separate web-admin app (port 3001) - a real tenant-admin without the platform-admin role sees a clear "Access restricted" screen (fully automated); actual platform-admin content is opt-in (see below), since that realm role is never grantable through any API this suite can drive |
| `20-telegram-and-email-channels.spec.js` | Telegram Bot and Email/IMAP channels (closing the gap flagged below) - a fabricated Bot Token/unreachable IMAP host is rejected by each channel's own real "prove it before persisting" check (UI + API-level, no opt-in credential needed since the rejection path needs no real one) |
| `00-full-platform-showcase.spec.js` | Not a business-case spec - a single, slow, narrated walkthrough of the whole product (signup through channels) for a human to watch. See "Watch it run" below. |

This intentionally mixes real browser-driven UI tests with direct API calls (via Playwright's `request` fixture) for setup and for cases that have no browser surface at all (webhooks, security probing) - see the project's own scope decision: build out the missing web console panels first, then test everything through them, rather than leaving gaps as API-only coverage.

## Prerequisites

The full stack, running locally: Postgres (with `pgvector`), Redis, Keycloak, the sidecar, `core`, and the web console itself. `core` needs these Spring profiles active - several specs will report false failures with fewer active (see each spec file's own comments): `security`, `mcp`, `knowledge`, plus the new `e2e` feature-flag profile explained below. The sidecar needs `tesseract-ocr` installed (image OCR) and enough RAM/CPU for `faster-whisper`'s "base" model (audio/video transcription) if you're running those specs.

**All commands below are PowerShell**, run from the repo root unless a `cd` is shown.

### Step 1 - infrastructure (Postgres, Redis, Keycloak)

If these aren't already running from earlier stages:

```powershell
docker run --name agentic-postgres -e POSTGRES_USER=agentic -e POSTGRES_PASSWORD=changeme -e POSTGRES_DB=agentic_ai -p 5432:5432 -d pgvector/pgvector:pg16
docker run --name agentic-redis -p 6379:6379 -d redis:7
cd deploy
docker compose up -d keycloak-db keycloak
cd ..
```

### Step 2 - the `e2e` feature-flag profile (this is the fix for the `429` you hit)

`SignupRateLimiter` defaults to 5 signups per IP per 60-minute window (`core/src/main/resources/application.yml`, `SignupRateLimiter.java`) - a full suite run signs up roughly 25-40 fresh tenants from your one machine, so the default limit fails fast with `429 Too many signup attempts` after the 5th one. That's the rate limiter doing its job correctly, not a suite bug.

Fixed as a real feature flag, not a number to hand-edit: **`core/src/main/resources/application-e2e.yml`** is a new Spring profile - same opt-in, additive pattern as the existing `security`/`knowledge`/`mcp` profiles already in this app - that raises the limit to 1000/5min. It does nothing unless you explicitly activate it. Nothing to edit, nothing to remember to revert.

### Step 3 - start `core` with the right profiles

```powershell
.\run-core.ps1 -Profiles "security,knowledge,mcp,e2e"
```

(`run-core.ps1` also loads `core\.env.local` into this PowerShell session - `APP_ENCRYPTION_KEY` and `KEYCLOAK_CLIENT_SECRET` must already be set there; see that script's own comments if this is your first run.)

Health check in a second terminal: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`.

### Step 4 - start the sidecar (needed for knowledge/multi-modal specs)

```powershell
cd sidecar
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8001
cd ..
```

### Step 5 - the E2E suite needs one more secret: `E2E_KEYCLOAK_CLIENT_SECRET`

Several specs (05, 09, 10) need a Keycloak access token to call the API directly (not through a browser). They use the **`agentic-ai-core`** client - the same confidential client `core` itself already uses to call Keycloak's Admin API - **not** `agentic-ai-web` (the browser's public client, which has `directAccessGrantsEnabled: false` in `deploy/keycloak/agentic-ai-realm.json` on purpose - turning that on just for tests would be a real security regression to the realm config, not a harmless test convenience).

Use the exact same value already in `core\.env.local`'s `KEYCLOAK_CLIENT_SECRET`:

```powershell
$env:E2E_KEYCLOAK_CLIENT_SECRET = (Get-Content core\.env.local | Select-String "^KEYCLOAK_CLIENT_SECRET=").ToString().Split("=",2)[1]
```

(Or paste it directly: `$env:E2E_KEYCLOAK_CLIENT_SECRET = "your-secret-here"` - get it from Keycloak admin console → `agentic-ai` realm → Clients → `agentic-ai-core` → Credentials tab, same place `run-core.ps1`'s own comments point to.) This only needs setting once per PowerShell session - it won't persist to a new terminal window, same caveat `run-core.ps1`'s header comment already explains for the other secrets.

### Step 6 - install Playwright and the web console's dependencies

```powershell
cd web
npm install
npx playwright install --with-deps chromium
```

## Running

**All testing for this project goes through Playwright** - no ad hoc manual clicking is the goal here, this suite is meant to be the actual verification method. From `web\`:

```powershell
npm run e2e            # headless, all specs
npm run e2e:ui         # Playwright's interactive UI mode - best for first run / debugging
npm run e2e:report     # open the HTML report from the last run
```

`--headed` (what you ran) also works and is genuinely useful for watching what's happening: `npx playwright test --headed`. Add `--debug` to step through a single spec, or `-g "signup"` to filter by test name.

Point at a non-default stack:

```powershell
$env:E2E_BASE_URL = "http://localhost:3000"
$env:E2E_API_BASE_URL = "http://localhost:8080"
$env:E2E_KEYCLOAK_URL = "http://localhost:8180"
npm run e2e
```

## Watch it run: the full-platform showcase

`00-full-platform-showcase.spec.js` is a different kind of spec: instead of proving one business case works, it drives the whole product end to end in a single real browser session - signup, tenant-admin login, the tenant-admin creating a teammate, that teammate's forced first login, MCP tool registry, knowledge base upload/search, (if `E2E_ANTHROPIC_API_KEY` is set) provider activation + agent creation + streaming chat + long-term memory recall, then back to the admin console for HITL approvals, audit log, and a Twilio channel guardrail check. It types into every field character-by-character (`pressSequentially`, not `.fill()`) and pauses between sections, specifically so a person watching the headed browser can follow along - it's the "show me everything working" script, not a CI-speed regression check.

Run just this one, headed:

```powershell
cd web
npm run e2e:showcase
```

Tune the pace without editing the file:

```powershell
$env:SHOWCASE_TYPE_DELAY_MS = "30"   # ms between keystrokes (default 70)
$env:SHOWCASE_PAUSE_MS = "400"       # ms pause between sections (default 900)
npm run e2e:showcase
```

It also runs as part of `npm run e2e` (the full suite) like any other spec - the env vars above only affect pacing, not correctness.

## Troubleshooting: "Unable to create user" (signup form OR Admin Control Panel)

Both the self-service signup form and the Admin Control Panel's "Users & Roles" -> Create user form call the exact same backend path: `TenantProvisioningService`/`UserAdminController` -> `KeycloakAdminClient` -> Keycloak's Admin REST API, authenticating as the `agentic-ai-core` client's own service account. If user creation fails (in the UI, in this showcase spec, or in specs 01/06/09's fixtures), it is almost always one of these two - both are real, previously-hit issues, not hypothetical:

1. **`core\.env.local`'s `KEYCLOAK_CLIENT_SECRET` doesn't match the client's actual current secret in Keycloak.** If it's still the literal placeholder text from the realm-export file (`CHANGE_ME_IN_A_REAL_ENVIRONMENT_DO_NOT_COMMIT_A_REAL_SECRET`), or the secret was regenerated in Keycloak since it was last copied, every admin-API call fails at the token step with a 401 - surfaced in the UI as a 502 error box mentioning "obtain admin token". Fix: Keycloak admin console (`http://localhost:8180`) -> `agentic-ai` realm -> Clients -> `agentic-ai-core` -> Credentials tab -> copy the actual secret -> paste it into `core\.env.local`'s `KEYCLOAK_CLIENT_SECRET=` line (replacing whatever is there now) -> restart `core` via `.\run-core.ps1`.
2. **The `agentic-ai-core` service account is missing the `realm-management` client's `manage-users`/`view-users` roles.** This is a one-time manual step the realm-export JSON cannot grant to itself (see `application-security.yml`'s comment and the main README's Phase 0 Stage 3 section) - easy to forget, and easy to lose if the Keycloak database volume is ever recreated. Token issuance succeeds but every actual call (create/list/delete user) fails with a 403 - surfaced in the UI as a 502 error box that explicitly says "confirm the agentic-ai-core service account has the realm-management client's manage-users/view-users roles assigned." Fix: Keycloak admin console -> `agentic-ai` realm -> Clients -> `agentic-ai-core` -> Service accounts roles tab -> Assign role -> filter by clients -> `realm-management` -> check `manage-users` and `view-users` -> Assign.

The error box's own text (both in the UI and in this suite's failure output) already names which of the two it is - HTTP 401 at "obtain admin token" means (1), HTTP 403 on any other call means (2).

## Opt-in specs (need real credentials or sample files)

Everything above runs with zero extra setup. These specific tests skip themselves (not fail) when their env var isn't set - run once with each set to get full coverage:

| Env var | Enables |
|---|---|
| `E2E_ANTHROPIC_API_KEY` | Real chat round-trip in spec 02 |
| `E2E_TWILIO_ACCOUNT_SID` / `E2E_TWILIO_AUTH_TOKEN` / `E2E_TWILIO_WHATSAPP_FROM` | Real Twilio credential activation in spec 08 |
| `E2E_SAMPLE_PDF_PATH` | PDF extraction (F3a) |
| `E2E_SAMPLE_DOCX_PATH` | DOCX extraction (F3a) |
| `E2E_SAMPLE_IMAGE_PATH` | Image OCR (F3b) - use an image with real legible text in it |
| `E2E_SAMPLE_AUDIO_PATH` | Audio transcription (F3c) - a short clip (under a minute) with clear speech |
| `E2E_SAMPLE_VIDEO_PATH` | Video transcription (F3d) - same, but a video container |
| `E2E_ADMIN_BASE_URL` | Base URL for the separate web-admin app in spec 19 (default `http://localhost:3001`) - not part of `playwright.config.js`'s `baseURL` since that's bound to the tenant console on :3000 |
| `E2E_PLATFORM_ADMIN_EMAIL` / `E2E_PLATFORM_ADMIN_PASSWORD` | Real platform-admin content in spec 19 - a Keycloak account that's been manually granted the `platform-admin` realm role (never grantable through signup or any tenant-scoped API - see `web-admin/app/lib/auth.js`'s `PLATFORM_ADMIN_ROLE` comment). Grant it once via Keycloak admin console → `agentic-ai` realm → Users → (the account) → Role mapping → assign `platform-admin`, same manual-step category as the `agentic-ai-core` service account roles in the troubleshooting section above. |

Why gated rather than synthesized inline: a valid-enough PDF can be hand-built as raw bytes, but a valid DOCX (a real zip archive) and audio/video with actual decodable speech cannot be faked cheaply, and a blank/silent file would just exercise the *failure* path (F3b/F3c's "no readable content" error), not the happy path this is meant to prove works. Grab any tiny real sample file for each format instead - a few seconds of audio/video is enough, no meaningful content is required, just something the sidecar can genuinely decode.

## Design notes worth knowing before you extend this suite

- **Fresh tenant per test, not shared.** `web/e2e/fixtures.js`'s `tenant`/`adminPage`/`consolePage` fixtures sign up a brand-new tenant (via `POST /api/signup` directly, not the UI) for every single test. Slower than reusing one tenant across the whole run, but it means a failure in one spec never leaves state that makes a *later* spec fail for an unrelated reason - every test's failure is self-contained and reproducible in isolation.
- **No real payment is ever completed.** Confirmed (`STAGE-F1-VERIFICATION.md`) that a tenant's schema is provisioned and immediately usable (`ACTIVE` status) as soon as `POST /api/signup` succeeds, independent of the Razorpay checkout step - so every fixture just uses the tenant right away rather than driving the Checkout widget (which would need a real card).
- **Two logged-in roles, two different landing pages.** `app/page.js` redirects any `tenant-admin` straight to `/admin` - so the main console (providers/agents/chat/MCP/knowledge) is only ever reachable by a *non-admin* tenant member. `consolePage` handles this by creating a second user via the Admin Users API and logging in as them, which also means it exercises Keycloak's real forced-password-change flow (`temporary=true` credential) on every single test that uses it - not a shortcut, an actual onboarding path getting incidental extra coverage.
- **Keycloak selectors assume the stock default theme** (`#username`, `#password`, `#kc-login`, `#password-new`/`#password-confirm` for the forced-change screen). If this deployment uses a customized Keycloak theme, `e2e/fixtures.js`'s `loginViaKeycloak`/`loginAndSetPermanentPassword` are the first and only place to update.
- **Every panel now lives on its own route, not stacked on one page.** Nav-restructure pass: the old single `/` (Providers/Agents/Chat/MCP/Knowledge/Workflow Designer all stacked) and `/admin` (Users/Approvals/Audit/Channels all stacked) got split into `/`  (Dashboard overview), `/workflows` (+ `/workflows/[id]` for the canvas), `/agents`, `/playground` (chat), `/knowledge`, `/providers`, `/tools`, and `/admin` (overview) + `/admin/users`, `/admin/approvals`, `/admin/audit`, `/admin/channels` - see `AppShell.js`'s nav sections. Every spec that used to rely on `consolePage`/`adminPage` landing directly on the right panel now calls `page.goto("/whatever")` explicitly first; a new spec touching any of these panels needs the same. The two heading assertions that predate the split (`"Agentic AI Platform"` on `/`, `"Admin Control Panel"` on `/admin`) still hold - both routes kept their original `<h1>` text on purpose.
- **`/` no longer auto-redirects to Keycloak.** Visual redesign pass: `AuthProvider` uses Keycloak's `onLoad: "check-sso"` (not `"login-required"`), so a visit to `/` renders this app's own branded `LoginLanding` screen first. `loginViaKeycloak`/`loginAndSetPermanentPassword`/the showcase spec's `slowKeycloakLogin` all click `[data-testid="login-landing-button"]` before Keycloak's form ever appears - a new spec that logs in by hand instead of using those helpers needs that same click.
- **The password-grant helper (`keycloakPasswordGrantToken`) is test-only plumbing**, not something the app itself uses anywhere (the real app only ever does the PKCE browser flow via `keycloak-js`). It exists purely so specs can get a bearer token for direct API setup/probing calls without driving a full browser login every time. It authenticates through `agentic-ai-core` (confidential, `directAccessGrantsEnabled: true`) using `E2E_KEYCLOAK_CLIENT_SECRET` - see Step 5 above. If it 400s, that secret doesn't match the client's current one in Keycloak (secrets get regenerated occasionally) - re-copy it from the admin console.
- **`fullyParallel: false` / `workers: 1`** in `playwright.config.js` is deliberate, not a performance oversight - `SignupRateLimiter` (Redis-backed) rate-limits signups, and hammering `/api/signup` from many parallel workers is exactly the abuse pattern it exists to block. Raise workers only if you've separately confirmed your rate-limit config tolerates it.
- **The web-admin app (spec 19) is a genuinely separate Next.js app**, not another route on the tenant console - its own port (3001 by default), its own Keycloak client (`agentic-ai-platform-admin`), and its own `onLoad: "login-required"` auth mode (an unauthenticated visit redirects straight to Keycloak, with no branded landing page to click through first, unlike the tenant console's `check-sso` mode). `playwright.config.js`'s single project/`baseURL` is bound to the tenant console on :3000, so spec 19 uses absolute URLs (`E2E_ADMIN_BASE_URL`) throughout rather than relative `page.goto()` calls. Its login helper (`loginToWebAdmin`) is defined locally in that one spec file rather than added to `fixtures.js`, since nothing else in this suite talks to that app yet - worth hoisting up if a second web-admin spec is ever added.
- **`/admin/channels` (ChannelsPanel.js) is one component for all nine channels, not one panel per channel.** The task #472 AXON rebuild folded the old WhatsApp-only `ChannelsPanel` and the separate `SocialChannelsPanel` (Messenger/Instagram/Slack/Discord/X) into a single card grid (`.ax-ch-card`, one per channel) driving one contextual inspector - clicking a card sets `selected` and swaps the inspector's form. `SocialChannelsPanel.js` itself is dead code now (nothing imports or renders it, confirmed by grepping the whole app), and every channel's error/success state is one shared pair of alerts (`.ax-tl-alert-error`/`.ax-tl-alert-success`) rather than a per-panel `.error-box`/`.success-box`. Specs 08 and 17 were both still written against the pre-#472 shape (found and fixed in the same pass that added spec 20 for Telegram/Email) - worth remembering if any *other* spec in this repo ever starts failing with "element not found" on a channels-page selector: check ChannelsPanel.js's current JSX before assuming the test is wrong.

## Tag taxonomy: `@mvp-acceptance` vs the full suite

B9-PR2 (`VegaDuta-PR-Prompts-B9-A1.md`) added `.github/workflows/e2e.yml`, which runs a curated slice of this suite - tagged `@mvp-acceptance` directly in the `test(...)` title string (Playwright's own `--grep`-filterable convention; nothing in `playwright.config.js` needed to change) - against the `.github/actions/boot-stack`-booted CI stack, nightly + on push to `main` + on PRs carrying the `full-stack` label. Everything else in this suite (the full `npm run e2e`) stays a local/manual verification tool per this README's own setup steps above; it is deliberately never run in any workflow (real audio/video transcription specs "can take real minutes on a CPU-only sidecar" - see this file's own header - not something a merge-blocking gate should wait on).

Six journeys are currently tagged:

| Spec | Tagged test | Journey |
|---|---|---|
| `01-signup-and-login.spec.js` | `"@mvp-acceptance the signup admin logs in and lands on the admin console"` | signup → Keycloak login |
| `15-agent-templates.spec.js` | `"@mvp-acceptance picking a template pre-fills the Agents page create form and doesn't create anything by itself"` | agent-from-template (pre-fill step) |
| `04-knowledge-collections-and-multimodal.spec.js` | `"@mvp-acceptance .md upload lands in the Default collection and is searchable"` | knowledge upload + semantic search |
| `11-workflow-designer.spec.js` | `"@mvp-acceptance create a workflow, add and configure an Output node, and save it as a draft"` | workflow create (UI) |
| `11-workflow-designer.spec.js` | `"@mvp-acceptance running a workflow synchronously returns a completed run with its output"` | workflow run + run-detail (API-level - see this file's own comment on why the ACTIVE+trigger→output path can't be driven through React Flow headlessly) |
| `19-web-admin-platform-app.spec.js` | `"@mvp-acceptance a real tenant-admin without the platform-admin role sees the Access restricted screen, not a crash or silent redirect"` | web-admin app loads + access control (negative path - see below for why not the positive one) |

**Why not a positive "platform admin overview loads" check**: every real-overview-content assertion in spec 19 lives behind the opt-in `E2E_PLATFORM_ADMIN_EMAIL`/`E2E_PLATFORM_ADMIN_PASSWORD` pair, which per that file's own header "is never grantable through any API this suite can drive" - a structurally manual Keycloak step, not something B9-PR1's seed fixture can provision. The negative "Access restricted" path is the genuinely automatable acceptance check for this app; flagging here rather than silently narrowing scope.

**One journey is currently `test.fixme`'d, not tagged-and-passing**: `02-providers-agents-chat.spec.js`'s new `"@mvp-acceptance FREE-tier agent creation and chat via the platform-hosted pool (mock-provider in CI)"` test — the journey B9-PR1's `mock-provider` was built to prove out end-to-end (create an agent + chat with it with zero real API keys). Written while auditing this suite for B9-PR2: `AgentWorkbench.tsx`'s create-agent gate (`activeProviders.length === 0`) only counts BYOK `providers` (`GET /api/providers`) - it does not account for a FREE-plan tenant's platform-hosted pool at all, so a fresh FREE-tier signup is blocked from creating any agent today, regardless of B9-PR1's seeded `platform_hosted_llm_provider` row. This is a real product gap (core-business scope: `core/providers` + `web/app/components/agents/AgentWorkbench.tsx`), not a test bug - the test is written against real, current selectors and is ready to un-skip the moment that gate is fixed; do not delete it or rework it to route around the block. Carries an `expires:2026-08-18` token per the skip-expiry convention below, so this doesn't silently live forever.

**Skip-expiry convention** (`scripts/ci/skip-expiry.sh`, B9-PR3): any `test.fixme(...)`/`.skip(...)` that represents a known, tracked gap (not an opt-in-credential skip like the ones throughout this suite for `E2E_SAMPLE_*_PATH`/`E2E_ANTHROPIC_API_KEY`) must carry an `expires:YYYY-MM-DD` token on the SAME source line as the `test.fixme(`/`.skip(` call itself (the CI check is a per-line grep) - CI fails once that date passes, so a "temporary" skip can't quietly outlive its own tracking. Environment/credential-gated `test.skip(!process.env.X, ...)` calls do NOT need this - only genuine "this is currently broken, here's why, here's who owns it" skips do.

Adding a new `@mvp-acceptance` journey: tag an existing un-gated test's title string (preferred - "reuse existing specs, tag, don't rewrite" per this project's B9-PR2 spec) or add a new one if no un-gated test covers the journey yet, following the patterns above.

## Testing convention for new features (standing practice)

Every new user-facing feature added to this project from here on gets one of two things before it's considered done, not after:

- **A browser surface exists** -> a new spec file (or an addition to an existing one) in `e2e/specs/`, following the same pattern as 01-10: real stack, no mocking, `data-testid`/text selectors that match the actual component.
- **No browser surface** (a background job, a webhook, a CLI script) -> covered by a direct API-level test the same way specs like `10-api-security.spec.js` already do, using Playwright's `request` fixture rather than driving a browser for something that has no UI.

If you're asking for a feature outside of this session (e.g. from Claude Code's CLI directly against this repo) and want the same discipline applied, a prompt like this reproduces it:

> "Before marking this done, add a Playwright spec under `web/e2e/specs/` covering the happy path and at least one rejection/validation case, following the existing pattern in `web/e2e/fixtures.js` and `web/e2e/README.md`. Run it and show me the result."



- Retry-with-backoff (Stage C's `RetryFailedActionsJob`) isn't exercised here - forcing a *transient* failure on demand through the UI/API alone isn't practical; `STAGE-C-VERIFICATION.md` already covers it via direct manipulation the CLI session had access to.
- Telegram and Email/IMAP channel coverage (credential activation + rejection, same shape as spec 08's Twilio coverage) is now in `20-telegram-and-email-channels.spec.js` - this bullet previously flagged it as a gap; closed.
- Mail-checking worker (`mail_worker.py` in the original PRD) isn't built yet either, so there's nothing to test.
- The reject-a-request test's dialog-based reason prompt (`window.prompt`) is a known UX shortcut in `ApprovalsPanel.js`, not something this suite could improve on its own - a real modal would be a nicer follow-up but wasn't in scope for this pass.
