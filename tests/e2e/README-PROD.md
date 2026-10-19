# VegaDūta — deployed-instance E2E suite

Playwright coverage of a **deployed** VegaDūta instance (your production or
staging environment). Separate from the local suite (`playwright.config.ts` +
`tests/`) on purpose: a suite that can be pointed at production by
*forgetting* an env var is a footgun, so the deployed-instance suite gets its
own config and its own test directory.


```bash
cd e2e
npm install
npx playwright install chromium

npm run test:prod             # both projects (desktop + mobile)
npm run test:prod:chromium    # desktop only — faster triage
npm run report:prod           # open the HTML report
```

## Safety posture — read this before running

This suite runs against **real production**. Every test that is on by
default is **read-only**: it navigates, reads the DOM, and asserts. Nothing
signs up, creates an agent, changes a plan, or touches the payment gateway.

Anything that *writes* is gated behind an explicit opt-in and **skips**
(visibly, with a reason) otherwise:

| Gate | Enables |
|---|---|
| `E2E_ALLOW_MUTATIONS=1` | Real self-service signup; real agent creation. **Leaves real data in production** — the tests annotate what they left behind. |
| `E2E_ALLOW_SPEND=1` | The Describe-it workflow draft in `six-for-everyone-live.spec.ts` (makes the platform call a hosted model; creates then deletes one marked draft workflow). |
| `E2E_USER_EMAIL` / `E2E_USER_PASSWORD` | The whole authenticated console suite (18 surfaces + tenant admin). |
| `E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD` | The platform-admin console suite (20 routes on `admin.<E2E_DOMAIN>`). |

Skips are deliberate: a skipped test is visible in the report, whereas a
test that simply doesn't exist is not.

## Environment

Every host is derived from one variable, so the same specs can be
dry-run against staging without editing a file:

```bash
E2E_DOMAIN=staging.example.com npm run test:prod    # staging dry-run
```

| Var | Default | Purpose |
|---|---|---|
| `E2E_DOMAIN` | `example.com` (set this) | Derives `app.`, `admin.`, `api.`, `auth.`, `academy.` |
| `E2E_SCHEME` | `https` | Set to `http` for the local kind stage, whose console and Keycloak URLs are http. |
| `E2E_FOREIGN_DOMAIN` | unset | Optional: a sibling environment's domain that pages must never link to (cross-environment leak guard) |
| `E2E_ALLOW_MUTATIONS` | unset | Opt in to writing real data |
| `E2E_SCHEME` | `https` | URL scheme of every derived host. The local kind stage serves plain `http` (`E2E_SCHEME=http`); `E2E_STAGE_SCHEME` is accepted as an alias |
| `E2E_SITE_MODE` | `classic` | `oss` for the open-source site (commercial marketing specs skip; the header Sign in is a link to `/login`), `classic` for the commercial site |
| `E2E_PROD_ONLY` | unset | Opt in to the one test that submits a real disposable-email signup (`signup.spec.ts`). Never set it on a stage or local stack |
| `E2E_STATE_DIR` / `E2E_RUN_ID` | OS temp dir / runner pid | Where the per-run, per-worker captured sign-in cookies live (outside the repo). Distinct runs never share or delete each other's state |
| `E2E_USER_EMAIL` / `E2E_USER_PASSWORD` | unset | Tenant console account |
| `E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD` | unset | `PLATFORM_ADMIN_ROLE` account |

### Running against the local kind stage

```bash
cd tests/e2e
E2E_DOMAIN=127.0.0.1.nip.io E2E_SCHEME=http E2E_SITE_MODE=oss \
E2E_USER_EMAIL=... E2E_USER_PASSWORD=... E2E_ALLOW_MUTATIONS=1 \
npx playwright test -c playwright.stage.local.config.ts tests-prod/<spec> --project=chromium --workers=1
```

`playwright.stage.local.config.ts` sets `--unsafely-treat-insecure-origin-as-secure` for the stage's http origins once,
for every project. Credentials come from the environment only; never write them to a file.

Sign-in details (`helpers/prod-session.ts`): the console keeps tokens in memory, so the bearer for API-level specs is
captured from the app's own `Authorization` header (`trackBearerToken` / `getBearerToken` in `helpers/prod-api.ts`),
full logins are serialised across workers (`withLoginLock`), and every later sign-in of the run restores from the saved Keycloak SSO cookies instead of logging in again. The user-scoped tour keys
(`vd_tour_status:<sub>`, `vd_tour_seen_security:<sub>`) are seeded from the token's subject after sign-in.

## Coverage

| File | Covers |
|---|---|
| `public-pages.spec.ts` | All 23 public routes render their **own** title + `h1`; no framework crash screens; no console errors. Plus RULE 0 domain hygiene: no page links to `localhost` or to the *other* environment's domain. |
| `seo-sitemap.spec.ts` | `sitemap.xml` well-formed, https-only, no duplicates, canonical to this domain; **every advertised URL resolves** (all 149); `robots.txt` allows crawling and points at the sitemap; unique titles + meta description + `og:title` per page. |
| `landing.spec.ts` | Hero, CTAs (`Get started` → `/signup`, never straight to the IdP), all six landing sections exist, anchors scroll into view, language switcher offers Indian languages, channel-demo scenario switching, FAQ expansion, footer legal links, zero console errors. |
| `auth-gating.spec.ts` | 38 gated console routes leak **no** console content to a logged-out visitor; `admin.<E2E_DOMAIN>` bounces anonymous visitors to Keycloak with the right client + PKCE; `/explore` redirect. |
| `auth-handoff.spec.ts` | Sign-in handoff to the real IdP on this domain, correct `client_id`, PKCE `S256`, non-localhost `redirect_uri`; branded login page; social IdPs present; password-reset path; **invalid credentials rejected without leaking account existence**. |
| `signup.spec.ts` | Wizard renders, collects the tenant-provisioning fields, blocks submission with unaccepted Terms *(this is what keeps it non-destructive)*, rejects mismatched passwords, legal links resolve. Real signup behind the mutation gate. |
| `pricing-billing.spec.ts` | All three tiers, monthly/annual toggle actually changes prices, INR **and** USD amounts render (no `NaN`/placeholder), comparison table, no card fields on a public page. |
| `content-hubs.spec.ts` | The four SEO/AEO hubs render substantive content and link to children; **`/ai` does not swallow `/ai-models`** (the segment-aware matching regression); comparison pages; 8 industry recipe pages; the WhatsApp cost calculator computes. |
| `galleries.spec.ts` | Community templates gallery loads live API data (an empty gallery = failed public API call, invisible from HTTP status); filters work; plugin catalog; **share viewers fail closed on an invalid id** rather than crashing or leaking. |
| `api-contract.spec.ts` | 7 protected endpoints reject anonymous requests; errors leak no stack traces or internal hostnames; actuator `env`/`beans`/`heapdump` not public; CORS doesn't reflect arbitrary origins; OIDC discovery issuer correct for this environment; JWKS published; HSTS + `nosniff` + clickjacking protection; **no secrets in the served HTML**. |
| `accessibility.spec.ts` | 9 pages: `<html lang>`, exactly one `h1`, every `img` has `alt`, every button/link has an accessible name, every input is labelled; keyboard focus reaches interactive elements; `main` landmark. |
| `responsive.spec.ts` | 7 pages have no horizontal overflow at 375px; mobile sign-in affordance; tap targets ≥24px; dark mode has an explicit body background (the "transparent body borrows the host theme" bug); light mode. |
| `academy.spec.ts` | `academy.<E2E_DOMAIN>` renders, curriculum nav present, links back on-environment (no `E2E_FOREIGN_DOMAIN` leak), login handoff, mobile overflow. |
| `console-authenticated.spec.ts` | *(credentials)* 18 console surfaces each render their own authenticated view — explicitly asserting they did **not** fall back to the marketing landing page (what a dropped session looks like); tenant admin area; usage figures aren't `NaN`; no raw API errors; sign-out. |
| `admin-console.spec.ts` | *(credentials)* All 20 admin routes load without crashing or bouncing to the IdP; tenant roster shows real plans; spend/budget figures; diagnostics. Read-only — no plan overrides, approvals, or budget writes. |
| `six-for-everyone-live.spec.ts` | *(credentials)* Signed-in smoke for Research, Tutor, Memory, Workflows (Describe-it composer) and the Attack test: each page loads with no console errors or failed calls other than expected 401/404 feature probes; `/api/tutor/status` + `/api/research/status` answer JSON with `enabled` (or a coherent 404 when the feature is off); the Playground header has no overlapping boxes at 400px; Memory add-a-fact then delete (self-cleaning, own id only, skips when self-service is off); the Describe-it draft has an approval before any send (**only with `E2E_ALLOW_SPEND=1`**, calls a model, deletes its own draft). |

## Before ANY core image: boot-check the jar (`scripts/ci/boot-check.sh`)

Green unit tests do not start a Spring context; four prod revisions crash-looped at boot on bugs only a real start catches (see `docs/deploy/ZERO-DOWNTIME-DEPLOY-RULES.md` rules 1 and 12). Run this from Git Bash or Linux **before building or pushing any core image**, and before pushing core changes that add beans or migrations:

```bash
bash scripts/ci/boot-check.sh                 # mvn clean + package, throwaway deploy-postgres + redis, boot, probe
bash scripts/ci/boot-check.sh --skip-build    # reuse core/app/target/app-*.jar
bash scripts/ci/boot-check.sh --flags APP_TUTOR_ENABLED=true APP_RESEARCH_DEEP_ENABLED=true   # flag-on boot
```

It needs docker (found on PATH or under Docker Desktop / Rancher Desktop), the local `deploy-postgres:latest` image (pgvector + AGE) and a JDK 21+. It passes only when the app logs `Started CoreApplication`, `/actuator/health/readiness` is 200 and UP including `tenantMigration`, and the probed routes (`/api/tutor/status /api/research/status /api/memory/export`, override with `--routes`) answer 401 anonymously. It prints the Flyway `now at version` line and the start time, exits non-zero with the last 40 log lines on failure, and always kills the jar and removes its containers. It never touches a real database or any deployed host.

## Design notes worth knowing before editing

**HTTP status codes prove nothing here.** This is a client-auth Next.js SPA:
`/agents` returns `200` to an anonymous request because the shell always
renders. Assertions are on the DOM, not the status line.

**A logged-out visit to a gated route renders the *landing page*.** `AuthProvider` swaps in
`GenesisExperience` and leaves the URL as `/agents` — it does not 401 and
does not redirect. So the security property under test is "no tenant data
reached the DOM", not "the request was rejected". `CONSOLE_ONLY_MARKERS` in
`helpers/prod-routes.ts` is that check.

**Title *and* `h1` are asserted together.** This app has a specific
historical failure mode where a route served the correct `<title>` in
`<head>` but the landing page as its body. Either assertion alone misses it.

**Selectors were pinned from the deployed DOM, not the source tree.**
Production can run an older image than repo HEAD, so deployed behaviour is
the only safe source of truth. 

**`helpers/prod-session.ts` exists because `helpers/keycloak-login.ts`
assumes the local-suite hosts** in its `waitForURL` regexes and would hang
against a deployed domain. Don't "consolidate" them without making the original
domain-aware first.
