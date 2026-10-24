# VegaDūta — public-surface E2E suite

Playwright tests for the **logged-out** VegaDūta surface (landing page, signup
wizard, legal pages, and the handoff to Keycloak sign-in) — written ahead of
a staged user-test release. This suite is deliberately a standalone
top-level package (its own `package.json` + `playwright.config.ts`), **not**
part of `web/`'s dependency tree, and it never installs or touches anything
under `web/` (incl. `web/app/platform-admin/`), or `core/`.

It defaults to a local development stack (web on `http://localhost:3000`,
Keycloak on `http://localhost:8180`, …) and every target URL is configurable
via env vars (see `helpers/e2e-env.ts`), so the same suite can point at a
preview/staging deployment without editing any file. A few assertions
("no browser-facing URL is `localhost`") are meant for deployed
environments and fail against local defaults by design.

## What's covered

| File | Covers |
|---|---|
| `tests/landing.spec.ts` | Logged-out `/` (rendered by `DemoLanding.tsx`): sticky nav + anchor links, the live language-demo widget (chips actually swap the rendered transcript into Hindi/Tamil script, not just relabel a tab), scenario tabs, the Academy link's target host, and the historically-buggy "Get started" CTA (must land on `/signup`, never on Keycloak sign-in). |
| `tests/signup.spec.ts` | The signup wizard (`web/app/signup/page.tsx`): step indicator renders, submitting the details step with an **unchecked** Terms checkbox blocks submission with the exact validation message and never navigates away, and the Terms/Privacy links resolve with `200`. |
| `tests/legal.spec.ts` | `/terms` and `/privacy` return `200` and render their `<h1>`, and cross-link to each other + back to `/signup`. |
| `tests/auth.spec.ts` | Clicking "Sign in" redirects the whole tab to the real Keycloak login page on the configured `E2E_AUTH_URL` origin, the branded login page shows the VegaDūta wordmark, and the Google/GitHub social-login links are present. **Does not attempt an actual login** — no credentials live in this suite. |
| `tests/visual.spec.ts` | Screenshot assertions for the landing page (desktop `1280x800` and mobile `375x812`) and the Keycloak login page; a mobile horizontal-overflow check (`document.documentElement.scrollWidth` vs. viewport width); and a console-error trap that fails the test on any uncaught JS error or `console.error` (ignoring known third-party noise — Cloudflare's RUM beacon and browser-extension errors — see `helpers/console-errors.ts`). |
| `tests/accessibility-smoke.spec.ts` | Light, dependency-free checks across `/`, `/signup`, `/terms`, `/privacy`: every `<img>` has an `alt` attribute, exactly one `<h1>` per page, and every `<button>` has a non-empty accessible name. |
| `tests/bundles-connect-chat.spec.ts` | Signed-in journey on one fresh tenant: preview then import a ready-made agent file (nothing created by the preview), a bad file is refused, export returns a valid secret-free agent file, `/connect` lists every channel and opens the Telegram inspector, and the imported agent answers a message in the playground. Needs a model behind the platform pool: with the CI overlay set `E2E_MOCK_PROVIDER=1` to assert the mock's echo, otherwise any non-failed reply passes (so it runs on a local-model install too). Provisions a real tenant. |
| `tests/runtime-settings.spec.ts` | Agent controls, one fresh tenant: set "Most tool calls per turn" for the workspace at `/admin/runtime-settings` (out-of-range refused inline, saved value survives a reload), the agent's Behavior and limits tab shows it as inherited from the workspace, an override on the agent changes only that agent, Reset to inherited gives the workspace value back, and resetting the workspace value returns the platform default. No model needed. |
| `tests/risky-setting-reason.spec.ts` | A security setting (Toxicity score that blocks): saving opens a confirmation, Confirm stays disabled until a typed reason of 3+ characters and the acknowledgment are given, Cancel changes nothing, confirming stores the reason in the setting's history, and a value weaker than the platform's is refused by the server and shown inline. No model needed. |
| `tests/approval-queue-continuation.spec.ts` | Approvals queue: approve a parked tool call and watch "The agent is carrying on with the task." change to "The agent finished carrying on with the task." Real console and session on a fresh tenant; only the four calls that need a tool-calling model (queue list, approve, the stored conversation) are stubbed at the network layer, because the CI mock provider never calls tools. |
| `tests/prompt-override-preview.spec.ts` | Prompt registry: override `directive.current_date` for the workspace, see it listed as "Workspace v1", see the text and "tenant override v1" in the agent's Preview final prompt, then Revert and see the shipped wording again. No model needed (the preview does not call one). |

## Install

```bash
cd e2e
npm install
npx playwright install --with-deps chromium
```

(`--with-deps` is only needed once per machine/CI image — it installs the OS
packages Chromium needs alongside the browser binary itself.)

## Run

```bash
# Full suite against the local dev stack (default — E2E_BASE_URL defaults to
# http://localhost:3000)
npm test

# Against a deployed environment (a preview deploy, staging, etc.)
E2E_BASE_URL=https://app.staging.example.com E2E_AUTH_URL=https://auth.staging.example.com npm test        # bash/zsh
$env:E2E_BASE_URL = "https://app.staging.example.com"; $env:E2E_AUTH_URL = "https://auth.staging.example.com"; npm test  # PowerShell

# Headed, so you can watch it run
npm run test:headed

# Just one file
npx playwright test tests/landing.spec.ts

# Open the HTML report from the last run
npm run report
```

### Visual baselines (`tests/visual.spec.ts`)

`toHaveScreenshot()` is a pixel-diff assertion against a committed baseline
image. There are no baselines checked in yet — the **first** run on any
machine (or CI image) must generate them:

```bash
npx playwright test tests/visual.spec.ts --update-snapshots
```

Commit the resulting `tests/visual.spec.ts-snapshots/` directory. Every
subsequent run diffs against those images (`maxDiffPixelRatio: 0.02` gives
some headroom for anti-aliasing/font-rendering differences across machines —
tighten it if that proves too loose in practice). Regenerate baselines
deliberately whenever the landing page or login theme changes on purpose.

## Env vars

| Var | Default | Purpose |
|---|---|---|
| `E2E_BASE_URL` | `http://localhost:3000` | Base URL of the tenant web app the whole suite runs against. |
| `E2E_AUTH_URL` | `http://localhost:8180` | That same environment's Keycloak origin; the auth/visual specs assert the sign-in redirect lands here. |
| `E2E_API_BASE_URL` | `http://localhost:8080` | Core API origin (specs that call the backend directly). |
| `E2E_ADMIN_BASE_URL` | `http://localhost:3000/platform-admin` | platform-admin console URL (was web-admin). |
| `E2E_ACADEMY_URL` | `http://localhost:4100` | Expected target of the landing page's Academy link. |
| `E2E_SITE_MODE` | `oss` | Which signed-out landing the target serves: `oss` (the default, what compose and the nightly run) or `classic` (web built/run with `NEXT_PUBLIC_SITE_MODE=classic`). `landing.spec.ts` runs the describe for that mode and skips the other. |
| `E2E_SITE_URL` | `E2E_BASE_URL` | Canonical site URL the web app was configured with (sitemap assertions). |
| `CI` | unset | When set, `forbidOnly` is enabled (a stray `.only()` fails the run instead of silently narrowing it). |

## Known gaps / things this suite works around

These are selectors chosen to avoid touching application source — per this
task's scope, no `data-testid` was added anywhere. If you're free to touch
`web/` later, the following would make a few of these tests less brittle:

- **Landing nav / hero.** No `data-testid`s on the sticky nav links, the
  language chips, or the scenario tabs (`web/app/components/demo-landing/
  DemoLanding.tsx` and `HeroLanguageDemo.tsx`). Tests currently select by
  role + accessible name/aria-label (e.g. `Show this conversation in Hindi`),
  which is resilient to styling changes but would break if that copy is
  reworded.
- **Signup wizard.** `DetailsForm`'s Terms checkbox has no `id`/`data-testid`
  — it's matched via its implicit `<label>` text (`getByLabel(/I agree to
  the/i)`). A `data-testid="signup-terms-checkbox"` would be more robust
  against copy changes to the consent sentence.
- **Keycloak login theme.** The social-login links already have stable ids
  (`#social-google`, `#social-github` — see `deploy/keycloak/themes/agentic-
  ai/login/login.ftl`), so no gap there. However: **the committed
  `deploy/keycloak/agentic-ai-realm.json` currently has both the `google` and
  `github` identity providers set to `"enabled": false`.** If the *deployed*
  realm hasn't separately had these toggled on via the Keycloak admin
  console, `auth.spec.ts`'s social-buttons test will correctly fail — that's
  a real environment/config gap this test surfaces, not a test bug. Worth
  confirming before the staged user-test release if social login is meant to
  be live.
- **Accessibility smoke.** Deliberately shallow (no axe-core, per the "no new
  deps" constraint) — it catches missing `alt`/accessible-name/heading-
  structure regressions, not full WCAG conformance. Treat a pass as "no
  obvious regression," not as an accessibility sign-off.
