# Site modes and public pages

One console image serves two sets of signed-out pages. Which set depends on
`NEXT_PUBLIC_SITE_MODE` (Compose: `WEB_SITE_MODE`), read at runtime
(`web/app/lib/siteMode.ts`).

| Mode | Who it is for | What the signed-out visitor sees |
|---|---|---|
| `oss` (default) | A self-hosted or community deployment | A technical landing (`/`): what the project is, the stack, the open standards it speaks, install tiers, how to use it as a dependency, where it runs (plugins, apps, mobile), how to contribute. `/about` describes the project, governance and the operator. No plans, prices, lead forms or vendor comparisons; the commercial routes redirect to `/`. |
| `classic` | The hosted commercial offering | The original marketing pages: the demo landing, `/pricing`, `/enterprise`, `/hire`, `/earn`, `/compare`, `/why-vegaduta`, `/features`, the cost calculator and the refund and shipping policies. |

Pages that exist in **both** modes: `/docs` (in-app help), `/api-reference`, `/plugins`
(plugins, apps and wearables), `/templates` and `/directory` (community galleries),
`/integrations`, `/changelog`, `/downloads` (the newest platform release's Setup apps and other artifacts), `/glossary`,
`/contact`, `/terms`, `/privacy`, `/login`, `/signup`.

## Where every public surface links

This is the wiring map. If you add a surface, add its row here and link it from the places in
the row.

| Surface | Path or coordinates | Linked from (oss) | Linked from (classic) | Source of truth |
|---|---|---|---|---|
| Hosted documentation (this site) | `NEXT_PUBLIC_DOCS_URL`, else `/docs` | header "Docs", landing button | footer "Resources" | `docs/`, `mkdocs.yml` |
| In-app help | `/docs` | header "Docs" when no docs URL is set | footer "Help docs" | `web/app/help/docs-content.tsx` |
| API reference | `/api-reference` | footer | not in the footer | `docs/api/openapi.json` |
| Downloads (Setup apps, source tarball, `SHA256SUMS`, images, Helm chart) | `/downloads` | header "Downloads", footer, landing install cards | footer "Developers" column | GitHub releases API, read on the server and cached an hour (`web/app/downloads/page.tsx`) |
| Academy (separate, hosted) | `NEXT_PUBLIC_ACADEMY_URL`, else `https://academy.vegaduta.ai` | header "Academy", footer | footer, only when the variable is set | `web/app/lib/academyUrl.ts` |
| Java Agent SDK | `io.github.cd-lc:vegaduta-agent-spring-boot-starter` | landing "Use it as a dependency" (docs button) | footer "Developers" column | `sdk/java/README.md` |
| TypeScript and Python clients | `vegaduta-sdk`, `vegaduta_sdk` | landing "Use it as a dependency" (cards and the "All SDKs" button) | not linked | `sdk/README.md` |
| Python Agent SDK | `vegaduta-agent` (`sdk/python-agent/`) | reachable through "All SDKs" ([SDKs and dependencies](sdk/README.md)); no card of its own on the landing | not linked | `sdk/python-agent/README.md` |
| Plugins (VS Code, JetBrains, Chrome) | `/plugins` | header "Plugins & apps", landing "Where it runs" | footer "Developers" column | `GET /api/plugins/catalog` (`PluginCatalogController`) |
| Android app, Wear OS, desktop agent, MCP server | `/plugins` (status list) | landing "Where it runs" | `/plugins` | `web/app/components/plugins/surfaces.ts` |
| Android APK direct download | `NEXT_PUBLIC_MOBILE_APK_URL` (+ `_SHA256`, `_VERSION`, `_SIZE_MB`) | Channels → Mobile SDK panel | same, plus the `/try` studio | `web/app/lib/mobileApp.ts` |
| Source, issues, security policy, roadmap | GitHub | header "GitHub", landing "Built in the open" band, footer community links | footer "Developers" column ("Open source") | repository |
| Pricing, enterprise, hire, earn, compare | commercial routes | redirected to `/` | nav and footer | `web/app/lib/siteMode.ts` |

The open-source header carries Docs, Academy, Downloads, Dependencies, Plugins & apps, Integrations, Changelog, About, GitHub and,
when `NEXT_PUBLIC_COMMERCIAL_URL` is set, Hosted version (`web/app/components/oss-landing/OssChrome.tsx`).
This table was re-checked against those components on 2026-10-21; the components win if they change.

## Configuration

| Variable | Compose name | Default | Effect |
|---|---|---|---|
| `NEXT_PUBLIC_SITE_MODE` | `WEB_SITE_MODE` | `oss` | Which page set is served |
| `NEXT_PUBLIC_DOCS_URL` | `WEB_DOCS_URL` | empty | Absolute URL of the published documentation site. When set, every "Docs" link on the public pages and the in-app help banner point there; when empty they point at the in-app `/docs` help |
| `NEXT_PUBLIC_COMMERCIAL_URL` | `WEB_COMMERCIAL_URL` | empty | Absolute URL of a hosted/commercial offering. When set, the open-source header and footer show a "Hosted version" link; the classic pages always link to the open-source repository and the self-hosting docs in return |
| `NEXT_PUBLIC_ACADEMY_URL` | `WEB_ACADEMY_URL` | `https://academy.vegaduta.ai` (Compose) | Origin of the Academy. The public header and footer always link it (the hosted Academy when empty); the classic footer and in-console links that need it are hidden when it is empty |
| `NEXT_PUBLIC_BILLING_ENABLED` | `WEB_BILLING_ENABLED` | off | Plans, prices and billing UI for signed-in users (independent of site mode) |
| `NEXT_PUBLIC_MOBILE_APK_URL`, `_SHA256`, `_VERSION`, `_SIZE_MB` | | empty | Direct Android APK download panels; hidden when the URL is empty |
| `NEXT_PUBLIC_CONTACT_EMAIL`, `_LEGAL_ENTITY`, `_LEGAL_CIN`, `_LEGAL_DIN`, `_SITE_URL` | | empty | Operator identity shown in footers and on About |

All of them are runtime values (`window.__ENV`, see `web/docs/RUNTIME-CONFIG.md`), so one
published image serves both modes and any domain.
