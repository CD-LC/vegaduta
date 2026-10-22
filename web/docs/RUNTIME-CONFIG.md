# Runtime public config (web and web-admin)

One prebuilt image of `web/` or `web-admin/` works on any domain. Public
settings (API and auth URLs, realm, client id, feature flags) are read from the
container environment on each request. They are no longer fixed when
`next build` runs.

## How it works

1. `app/lib/env.ts` (one copy in each app) holds an **allowlist** of public keys
   and the function `getPublicEnv(key)`.
2. The root layout renders `<script id="__public-env">window.__ENV={...}</script>`
   as the **first element of `<head>`**. The object is built from the server
   process's environment when the request arrives. Only allowlisted keys that
   have a value are included. The JSON is escaped so that no value can close
   the script tag.
3. Every read goes through `getPublicEnv(key)`:

   | Where   | Lookup order |
   |---------|--------------|
   | Browser | `window.__ENV[key]`, then the value inlined at build time, then `undefined` |
   | Server  | `process.env[key]` (a dynamic lookup, so the bundler can't inline it), then the value inlined at build time, then `undefined` |

   Each call site keeps its own default after that (`|| "http://localhost:8080"`
   and so on). Those defaults only apply in local development.

**Precedence: runtime env > build-time value (`--build-arg` / `.env*` at build) > code default.**
An **empty string counts as unset**. So a compose line such as
`NEXT_PUBLIC_API_BASE_URL: ${NEXT_PUBLIC_API_BASE_URL:-}` never clears a value
that was baked into the image. This also means you can't blank out a baked
value at runtime. For the `=== "1"` flags, pass `0` to switch one off.

### Why an inline script in the layout, and not a `/env.js` route

- Both root layouts were **already dynamic**, so the inline script costs no
  static-page performance. web reads `cookies()`/`headers()` on every request.
  web-admin has `export const dynamic = "force-dynamic"`.
- An inline script runs in order while the parser works through `<head>`. It
  needs no extra round trip, and no async chunk can run before it. A
  synchronous `<script src="/env.js">` would add a blocking request. It would
  also race Next's `async` chunks.
- Timing is safe for module-level reads such as `export const API_BASE = getPublicEnv(...)`.
  Next's runtime evaluates a client component module only when the RSC flight
  data (`self.__next_f.push(...)` scripts in `<body>`) references it. That is
  always after the parser has run the `<head>` script.
- CSP: both apps ENFORCE a nonce-based `Content-Security-Policy`
  (`script-src 'self' 'nonce-…' 'strict-dynamic'`, no `'unsafe-inline'`, no
  `'unsafe-eval'` outside `next dev`). `proxy.js` / `proxy.ts` mint a nonce per
  request and forward it as `x-nonce`; the root layout puts it on
  `<script id="__public-env">` (and, in web, on `no-flash-theme` /
  `no-flash-density`). Any new inline `<script>` must carry
  `nonce={nonce}` or the browser blocks it. Operators can set
  `CSP_REPORT_ONLY=true` (server env, runtime) to send the same policy as
  `Content-Security-Policy-Report-Only` while diagnosing a violation.

## Keys

### web (tenant console)

| Key | Purpose | Code default |
|-----|---------|--------------|
| `NEXT_PUBLIC_API_BASE_URL` | core API origin (browser-facing) | `http://localhost:8080` |
| `NEXT_PUBLIC_KEYCLOAK_URL` | Keycloak origin (browser-facing, token issuer) | `http://localhost:8180` |
| `NEXT_PUBLIC_KEYCLOAK_REALM` | realm | `agentic-ai` |
| `NEXT_PUBLIC_KEYCLOAK_CLIENT_ID` | public OIDC client | `agentic-ai-web` |
| `NEXT_PUBLIC_ACADEMY_URL` | Academy link target | see `lib/academyUrl.ts` |
| `NEXT_PUBLIC_CODE_INTEL_URL` | direct code-intel origin (optional) | unset (goes through core) |
| `NEXT_PUBLIC_APP_BUILDER_URL` | App Builder origin (optional) | empty, which hides /build |
| `NEXT_PUBLIC_EDGE_AGENTS` | `1` turns on the on-device agents UI | off |
| `NEXT_PUBLIC_EDGE_MODEL_MANIFEST_URL` | fallback edge model manifest | empty |
| `NEXT_PUBLIC_EDITION` | `personal` / `lite` / `full`: the edition core runs as (`app.edition.tier`). Decides which pages the console hides and 404s on a personal install (`lib/edition.ts`, one `EDITION_ROUTES` table). Compose derives it and core's `APP_EDITION_TIER` from the single `VEGADUTA_EDITION`. Unset falls back to `NEXT_PUBLIC_AUTH_MODE=local` = personal, then `NEXT_PUBLIC_DEPLOY_ENV=lite\|full`; a console that is told nothing hides nothing. The console compares it with `edition.tier` from `/api/public/status` and warns on a mismatch | derived (see left) |
| `NEXT_PUBLIC_DEPLOY_ENV` | topbar environment pill (`production` / `staging`) | none |
| `NEXT_PUBLIC_FREE_MODE` | `1` hides pricing | off |
| `NEXT_PUBLIC_BILLING_ENABLED` | `1` shows plans/prices/upgrade/billing UI; off redirects `/billing`, `/features`, `/admin/billing` (`lib/billing-flag.ts`) | off |
| `NEXT_PUBLIC_COFFEE_URL` | donation link | none |
| `NEXT_PUBLIC_DOCS_URL` | absolute URL of the published documentation site; unset = the in-app help at `/docs` (`lib/siteLinks.ts`) | none |
| `NEXT_PUBLIC_COMMERCIAL_URL` | absolute URL of a hosted/commercial offering; unset = the OSS pages show no such link | none |
| `NEXT_PUBLIC_MOBILE_APK_URL` / `_SHA256` / `_VERSION` / `_SIZE_MB` | optional direct-download Android APK panel (channels page, /try); unset hides it | none |
| `NEXT_PUBLIC_SITE_URL` | canonical marketing origin | `http://localhost:3000` (also `SITE_URL`) |
| `NEXT_PUBLIC_CONTACT_EMAIL` | public contact | `contact@example.com` |
| `NEXT_PUBLIC_LEGAL_ENTITY` / `_LEGAL_CIN` / `_LEGAL_DIN` | operator shown in the footer, /about and JSON-LD | `VegaDūta contributors` / none |

### web-admin (platform admin)

| Key | Purpose | Code default |
|-----|---------|--------------|
| `NEXT_PUBLIC_API_BASE_URL` | core API origin | `http://localhost:8080` |
| `NEXT_PUBLIC_KEYCLOAK_URL` | Keycloak origin | `http://localhost:8180` |
| `NEXT_PUBLIC_KEYCLOAK_REALM` | realm | `agentic-ai` |
| `NEXT_PUBLIC_KEYCLOAK_CLIENT_ID` | public OIDC client | `agentic-ai-platform-admin` |
| `NEXT_PUBLIC_APP_URL` | tenant-console origin for notification links | derived from the API URL (`api.X` becomes `app.X`) |
| `NEXT_PUBLIC_BILLING_ENABLED` | `1` shows Billing & Subscriptions; off hides it and redirects `/billing` to Overview | off |
| `GRAFANA_URL`, `PROMETHEUS_URL`, `LANGFUSE_URL`, `TEMPORAL_UI_URL` | observability deep links | `http://localhost:3003/9090/3002/8088` |

`ADMIN_SITE_URL` (metadataBase) is a plain server env var that is already read
at runtime. Its build-time alternative, `NEXT_PUBLIC_ADMIN_URL`, is not in the
allowlist.

### Values that are still fixed at build time

- `CDN_URL` (`assetPrefix`) and `ALLOWED_DEV_ORIGINS` are read in `next.config.js`.
- The `headers()` output in `next.config.js` (HSTS, nosniff and the rest) is
  computed when `next build` runs and saved in the routes manifest. The
  CSP is deliberately NOT there: `proxy.js` sets it per request
  from `app/lib/csp.ts`, whose connect-src/frame-src origins come from
  `getPublicEnv` (`NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_KEYCLOAK_URL`,
  `NEXT_PUBLIC_CODE_INTEL_URL`), so a deployment on its own domain gets a
  policy naming its own origins, and whose script nonce is fresh per request.
- `ENABLE_DEV_ROUTES=true` (web, runtime) re-enables the developer-only
  `/ui-kit` and `/eval` routes on a production server; by default they 404.
- Routes that are statically prerendered freeze whatever they read at build.
  Today these are `sitemap.ts`, `robots.ts` and `llms.txt/route.ts`, which is
  `force-static`, and they use `SITE_URL`. Set `NEXT_PUBLIC_SITE_URL` at build
  time, or make those routes dynamic, if the sitemap must match the runtime domain.
  `/.well-known/change-password` was changed to `force-dynamic` so that its
  Keycloak redirect is resolved at runtime.

## Adding a key

1. Add it to `PUBLIC_ENV_KEYS` **and** to the `switch` in `buildTimeValue()`. The
   switch must use the literal `process.env.NEXT_PUBLIC_X`, because that is the
   only form Next inlines.
2. Read it with `getPublicEnv("NEXT_PUBLIC_X")`. Never read
   `process.env.NEXT_PUBLIC_X` directly: that is fixed at build time on both
   server and client.
3. **Never add a secret.** Every allowlisted value can be seen with view-source.

## Compose, Helm and other deployers

Pass the same names as ordinary **container environment variables**:

```yaml
# docker compose
web:
  image: ghcr.io/<org>/vegaduta-web:<tag>
  environment:
    NEXT_PUBLIC_API_BASE_URL: https://api.${DOMAIN}
    NEXT_PUBLIC_KEYCLOAK_URL: https://auth.${DOMAIN}
    NEXT_PUBLIC_KEYCLOAK_REALM: agentic-ai
    NEXT_PUBLIC_KEYCLOAK_CLIENT_ID: agentic-ai-web
```

```yaml
# helm values -> Deployment env
env:
  - { name: NEXT_PUBLIC_API_BASE_URL, value: "https://api.example.com" }
  - { name: NEXT_PUBLIC_KEYCLOAK_URL, value: "https://auth.example.com" }
```

- No rebuild is needed when the domain changes. Restart or roll the pod with the new env.
- `--build-arg NEXT_PUBLIC_*` still works (all build args are optional and
  default to empty), so an existing build pipeline produces the same result.
- The image uses the Next `standalone` server (`node server.js`), runs as the
  non-root `nextjs` user (system uid 999), and listens on `PORT` (3000 for web,
  3001 for web-admin) at `HOSTNAME=0.0.0.0`. It includes a Docker `HEALTHCHECK`.
- Checking a running container:
  `curl -s https://app.example.com/ | grep -o 'window.__ENV=[^<]*'`
