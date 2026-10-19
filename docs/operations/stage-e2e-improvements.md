# Stage E2E improvements and fix batches

Source: a 14-area parallel test of the kind stage (the raw per-area report is kept outside the repository). 82 deduplicated items: 1 blocker, 10 high, 33 medium, 38 low. Items with no supporting evidence were dropped (listed at the end).

Evidence levels: **tested** = reproduced by a Playwright assertion or API call on the stage. **observed** = seen by hand, curl or logs. **source** = read from code, not run. Fix suggestions are not compiled or run unless stated.

Each item names the `test.fixme` (or skipped test) that should be un-skipped when it is fixed, in `tests/e2e/tests-prod/stage-*-live.spec.ts`.

## How to use this file

Batches are split by code area so that agents do not edit the same files. Each batch has one self-contained prompt. Order and dependencies:

- DEPLOY-2 and TESTS-1 can start immediately and unblock verification of the others.
- CORE-1 to CORE-5 touch different modules; they can run in parallel, but only one Maven build may run at a time on the machine (see the prompts).
- WEB-1 to WEB-6 touch different files, with two cross-batch notes: CORE-2 owns the `comment` run field and edits one call site in `WorkflowCanvas.tsx` after WEB-1 has merged; DEPLOY-1 changes the compose default for code-intel while WEB-4 owns the browser-side guard.

## Master list (severity ranked)

Columns: id | severity | kind | page | evidence | repro | suggested fix | batch.

### Blocker

| id | sev | kind | page | evidence | repro | suggested fix | batch |
|---|---|---|---|---|---|---|---|
| SE-01 | blocker | config | all console and admin | The stage serves https but the web image bakes `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_KEYCLOAK_URL`, `NEXT_PUBLIC_SITE_URL` as `http://...nip.io`. On https the console logs Mixed Content for `/api/public/status`, auth iframe and token calls; sign-in hangs on "Signing you in" (admin host) or shows the signed-out landing. Works only on http with `--unsafely-treat-insecure-origin-as-secure` (keycloak-js needs a secure context). Tested, reported by 10 areas. | Open `https://app.127.0.0.1.nip.io`, sign in, read console. | Pick one scheme. Either set the kind public URLs to https (NEXT_PUBLIC_*, KEYCLOAK_ISSUER_URI, KEYCLOAK_ADDITIONAL_ISSUERS per AGENTS.md rule 7, ingress TLS secret), or document that the kind stage is http-only with the secure-origin browser flag in `docs/operations/local-stack-guide.md`. When Keycloak init fails after retries, show an error state with Retry instead of an endless splash. | DEPLOY-1 (docs, helm defaults), DEPLOY-2 (kind values), WEB-6 (splash error) |

### High

| id | sev | kind | page | evidence | repro | suggested fix | batch |
|---|---|---|---|---|---|---|---|
| SE-02 | high | bug | /workflows/{id} Run | `WorkflowCanvas.tsx:1856` calls `crypto.randomUUID()` unguarded after `setBusy(true)`, outside the try/finally. On any non-secure origin (plain http, LAN installs, this stage) it throws `crypto.randomUUID is not a function`, sends no request, and the modal stays on "Running..." forever. Tested 3 of 3 (fixme in `stage-workflow-exec-live.spec.ts`, `stage-workflow-build-live.spec.ts`). | Open a workflow on http, Run now, Run. | Use the guarded id helper in `web/app/lib/requestLifecycle.ts` (`makeRequestId`, line ~357); extract to a shared util with a `getRandomValues` fallback. Move the call inside the try so busy/runLive reset and show an error toast. Add a vitest stubbing `crypto.randomUUID = undefined`. Grep `web/` for other bare `crypto.randomUUID`. | WEB-1 |
| SE-03 | high | bug | /agent/{shareId}, /workflow/{shareId} | Share reports and viewer counts are never persisted: 3 reports (204 each) leave `reportCount=0`, `viewerCount=0`, link stays active (threshold `app.agent.share.report-revoke-threshold=3`). Workflow share likewise. Tested (fixme `SHARE-COUNTS`). Root cause (source, not run): `spring.jpa.open-in-view=false`; the controller loads the share in a read-only tx so the entity is detached; `AgentShareService.report()` and `recordViewer()` mutate it without `save`. Same shape in `WorkflowShareService`. | Mint a share, POST 3 reports, GET owner share status. | Re-load by id inside the `@Transactional` method (or `repository.save`) in `AgentShareService` and `WorkflowShareService`; keep the TenantContext set by `withResolvedShare`. Check `DemoShareController`. Add an integration test: 3 reports revokes the link and the owner sees `reportCount=3`. | CORE-3 |
| SE-04 | high | bug | eval datasets API | Run conflict, delete with a live run, GET of an unknown dataset, and `PUT /quality/gate` with an unknown datasetId all return 500. The exceptions (`EvalRunConflictException`, `EvalDatasetNotFoundException`, `EvalRunNotFoundException`) carry `@ResponseStatus` but `FallbackExceptionHandler` (catches `Exception.class`) ignores it. Controller javadoc promises 409. Tested + core logs (3 fixme in `stage-eval-insights-live.spec.ts`). | Start a run, POST a second run on the same dataset. | In `core/app/.../common/FallbackExceptionHandler.java` or `ResourceStateExceptionHandler` honour `@ResponseStatus` (`AnnotatedElementUtils.findMergedAnnotation`) or add explicit handlers. Grep other `@ResponseStatus` exceptions. | CORE-1 |
| SE-05 | high | bug | /sitemap.xml, /robots.txt | `Sitemap:` line and all 225 `<loc>` use `http://localhost:3000` although the pod sets `SITE_URL`. `sitemap.ts` / `robots.ts` are static so the default is baked at `next build`. Breaks AGENTS.md rule 4. Tested (3 existing tests fail; fixme in `stage-public-auth-live.spec.ts`). | `curl http://app.127.0.0.1.nip.io/robots.txt` | `export const dynamic = 'force-dynamic'` on both files, or derive origin from env at request time. | WEB-5 |
| SE-06 | high | bug | /knowledge | No UI to delete a knowledge document. API `DELETE /api/knowledge/documents/{id}` exists (204/404) but no web caller. Deleting a collection that holds a file returns 409, so such a collection can never be deleted from the console. Tested (fixme `a knowledge document can be deleted from the UI`). | Create a collection, upload a file, try to delete the collection. | Add `deleteKnowledgeDocument(id)` in `web/app/lib/api/knowledge.ts`; add a trash button with confirm in the file row of `KnowledgePanel.tsx` (~line 1218); refresh lists after. | WEB-2 |
| SE-07 | high | bug | /knowledge (all cached GETs) | After a successful POST/upload the list does not show the change until reload (seen in 2 of 5 and 3 of 4 runs). Source analysis: `apiFetch` clears `getCache` before the write is sent; `refreshGet()` stores a response of a GET that was in flight or started during the write without checking `cacheEpoch`; `inflightGets` is never cleared on a write. Tested (intermittent). | Create a collection on /knowledge; watch the sidebar. | In `web/app/lib/api/core.ts`: clear `inflightGets` on a write; flush again after the write resolves and bump `cacheEpoch`; in `refreshGet` capture the epoch at start and skip `getCache.set` / persist if it changed. Add a unit test: GET in flight, POST, GET must hit the network. | WEB-2 |
| SE-08 | high | bug | admin host gate "Sign out" | Keycloak returns 400 "Invalid redirect uri" for `post_logout_redirect_uri=http://admin.127.0.0.1.nip.io`; the app origin returns 302. SSO session survives. The realm template already lists the admin URL (`deploy/helm/charts/keycloak/templates/_realm.tpl` line 16), so the stage realm is stale or built with a different admin URL. Tested twice (fixme in `stage-admin-live.spec.ts`). | Sign in on the admin host as a non-admin, click Sign out. | Patch/re-import the stage realm client `post.logout.redirect.uris`; verify the template renders the admin URL for kind values. Optional client fallback to the app origin in `web/app/lib/auth.ts` `logout()` (~line 586). | DEPLOY-1 (realm), WEB-5 (optional fallback) |
| SE-09 | high | config | /jobs, scheduled agent runs | Jobs fail `EXECUTION_FAILED` ("This model server URL is not allowed: ... internal host name", `LlmHttpClientFactory.requireAllowedTenantBaseUrl`). `vegaduta-core` has `APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS=mock-llm:11434`; `core-jobs` and the worker do not (`tests/load/kind/values-loadtest.yaml` sets it under `core.env` only). Observed in logs + tested (fixme). Test environment, not a product defect. | Start any job. | Add the env to core-jobs and core-worker env in the kind values; also treat the SSRF-guard `IllegalArgumentException` as non-retryable so it fails fast. | DEPLOY-2 (values), CORE-5 (non-retryable) |
| SE-10 | high | test-gap | tests/e2e helpers and ~15 existing specs | About 80 tests fail at `prod-session.ts:104` (header "Sign in" button). The console shows a link, then an email step ("Continue to secure sign-in"). `getBearerToken` reads `sessionStorage.vd_kc_session` which `auth.ts` now only deletes. Tour keys are user-scoped (`vd_tour_status:<sub>`), so the welcome modal still blocks clicks. `prod-env.ts` hardcodes https. `.e2e-prod-auth-state.worker-0.json` is shared and deleted by parallel runs. Autonomy spec clicks a title hidden by an overlay button. Tested. | Run any existing prod spec with `playwright.stage.local.config.ts`. | See TESTS-1 prompt. Working flows exist in `stage-chat-switch-live.spec.ts` (`signIn()`, token capture) and `stage-sharing-misc-live.spec.ts` (`login()`). | TESTS-1 |
| SE-11 | high | bug | /code-intel, /agents | Browser calls `http://localhost:8090/health` and `/repos` (ERR_CONNECTION_REFUSED); page says "reachable at http://localhost:8090". `NEXT_PUBLIC_CODE_INTEL_URL` is baked from `PUBLIC_CODE_INTEL_URL:-http://localhost:8090` (`deploy/compose/docker-compose.build.yml:50`, `docker-compose.yml:548`, `.env.example:76`). `web/app/code-intel/lib.ts` falls back to the core proxy when empty. Violates AGENTS.md rule 4. Tested (fixmes in tools-mcp, agents specs). Image build args were not inspected. | Open /agents or /code-intel with the network panel open. | Default `PUBLIC_CODE_INTEL_URL` to empty (as `lite.yml:281` does); in `lib.ts` ignore a localhost URL when the page origin is not localhost; `AgentWorkbench.tsx` skips the repos call when code-intel is unconfigured; show "not enabled" instead of a localhost hint. | DEPLOY-1 (compose), WEB-4 (guard) |

### Medium

| id | sev | kind | page | evidence | repro | suggested fix | batch |
|---|---|---|---|---|---|---|---|
| SE-12 | medium | bug | workflow cancel during LLM call | Reproduced 2 of 2: Stop mid agent loop logs HikariPool "marked as broken SQLSTATE(08006)", `LlmMetricsAdvisor` "Failed to record LLM stream outcome cancel ... Could not open JPA EntityManager" (`LlmMetricsAdvisor.java:655`, thread `loomBoundedElastic`). Run ends CANCELLED. Observed in logs only. | Run an agent-loop workflow and cancel it. | Record the cancel outcome on a non-interrupted path before disposing; avoid interrupting threads holding a JDBC connection; check the workflow cancel hook. | CORE-2 |
| SE-13 | medium | bug | workflow schedule | `PUT /schedule` accepts `* * * * * *` (every second, `nextRunAt` now+1s). Scheduled tasks enforce 15 min; workflows do not (`WorkflowService.enableSchedule` ~1105 only parses). Scheduler firing not tested. Tested (fixme). | PUT schedule with `* * * * * *` on an ACTIVE workflow. | After parse, compute two fire times; reject below `app.workflow.schedule.min-interval-seconds` (default 60), 400 with message; document in `docs/configuration.md` and env files. | CORE-2 |
| SE-14 | medium | behaviour | Run modal | The optional "Run comment" is appended to the run `input` (`...\n\nRun comment: ...`), so nodes and branches see it. Tested (asserted as current behaviour). | Run with a prompt and a comment containing "refund" against a condition node. | Send `comment` as a separate field on the run request, store on the run, show in history; do not concatenate. Frontend call site in `WorkflowCanvas.tsx runWithRuntimeInputs` (run after WEB-1 merges). | CORE-2 |
| SE-15 | medium | ux | workflow nodes | Bad regex, blocked HTTP URL, unusable classifier/extractor reply leave the run COMPLETED with the node SKIPPED and the input passed on. SSRF guard itself works. Tested (asserted as current behaviour). | Regex node with `([unclosed`. | Keep the safe fallthrough; add a run-level warning count/flag and show it in the run header and history; optionally per-node on-error setting (`NodeErrorStrategy` exists). | CORE-2 |
| SE-16 | medium | config | chat resume | `APP_CHAT_RESUMABLE_STREAMS_STORE=redis` is set but `ENABLED` defaults false (`application.yml:1153`), so `X-Turn-Id` is never returned and the web resume path cannot trigger. Tested (test skips). | POST /chat with `X-Resumable-Stream: 1`. | Add `APP_CHAT_RESUMABLE_STREAMS_ENABLED=true` to the stage values; document; then run the skipped test (needs SE-40 slow mock). | DEPLOY-1 (chart defaults/docs), DEPLOY-2 (kind values) |
| SE-17 | medium | behaviour | /playground | A completed transcript and session are lost on reload ("No messages yet.") while the page still GETs `llm-requests` and `llm-usage` for the old session id. Only in-flight turns are restored (`ChatPanel.tsx` ~441-490). Observed (fixme). | Chat 2 turns, reload. | Restore the last session's history for the persisted sessionId, or drop the stale id and the per-session calls. Say so in the UI if intended. | WEB-3 |
| SE-18 | medium | ux | all console pages | The "Build the agent" welcome modal (`data-tour-blocker=welcome`, z-1150), the product tour (z-1152), and the "Turn on two-factor authentication" card appear seconds after load and intercept pointer events; Escape closes the tour before the palette. Seeding `vd_tour_status` does not work because keys are user-scoped (`userScoped()` in `TourProvider.tsx` ~1308-1345, 1544). Reported by 9 areas. Tested. | Fresh context, sign in, click anything within 6 s. | Do not mount the blocker while the user is typing or a turn is in flight or on deep links to editing surfaces; persist dismissal per user (server-side or the same keys tests can seed); make the 2FA card non-modal and anchored away from toolbars; topmost layer first on Escape. | WEB-6 |
| SE-19 | medium | config | chat turn path | Core logs a recurring WARN "RL policy MODEL_ROUTE is ENABLED ... no selectable arms" on the hot path (`ContextualBanditService`). Observed in logs. | Chat with load-test-agent; `kubectl logs deploy/vegaduta-core`. | Seed arms in `tests/load/kind/provision.sh` or disable the policy for the agent; downgrade the log to once per agent per hour. | DEPLOY-2 (seed), CORE-5 (log rate) |
| SE-20 | medium | config | stage Keycloak | Parallel agents sharing one user hit `user_temporarily_disabled` several times (login shows "Invalid username or password"). Observed in Keycloak logs. | Sign in 8+ times in quick succession. | Reuse stored auth state, one user per runner, or relax brute-force settings for the stage realm only. | DEPLOY-2 |
| SE-21 | medium | bug | PUT /api/tenant/ai-model-preferences/{featureKey} | Any featureKey is accepted and stored as an orphan row (`nofeature`); a known-but-unconnected provider and an invented model are also accepted. Tested (fixme). | PUT `/nofeature` with a valid body, then GET. | Reject keys not in `AiModelFeature` with 400 in `TenantAiModelPreferenceController`/`...Service#upsert` (core/tenancy). Optionally require a connected provider. | CORE-4 |
| SE-22 | medium | ux | unknown routes | Unknown URLs return 404 but render the landing page (not `not-found.tsx`); body about 830 KB raw vs 250 KB for `/`. Same anonymous fallback for `/agents`. Tested (fixmes). | Open `/anything-unknown` signed out. | Let `notFound()` render for routes that do not exist before the anonymous gate; this also removes most of the payload. | WEB-5 |
| SE-23 | medium | bug | landing header | Clicking `/#dependencies` on `/` changes the hash but does not scroll (scrollY stays 0); direct load scrolls. Tested (fixme). | Click "Dependencies" on the landing page. | In `OssChrome.tsx` use a plain `<a href="#dependencies">` or call `scrollIntoView` on click. | WEB-5 |
| SE-24 | medium | behaviour | /sitemap.xml | Lists routes that oss mode 307s to `/` (/try, /hire, /enterprise, /earn, /compare...). Observed with curl (fixme). | `curl -I /hire`. | Filter with `isCommercialRoute()` (`web/app/lib/siteMode.ts`) when `isOssSite()`. | WEB-5 |
| SE-25 | medium | config | /docs, /help, /build, public HTML | Public HTML and `/__public-env` contain `http://localhost:8090` and links to `http://localhost:4100` (Academy; `web/app/lib/academyUrl.ts:19` default) incl. "Learn in the Academy" on /build. Breaks AGENTS.md rule 4. Tested (3 existing tests, fixme). | `curl -s / \| grep localhost`. | Drop the localhost default in `academyUrl.ts`; hide the link when unset; set `NEXT_PUBLIC_ACADEMY_URL` / code-intel in chart values and `.env.example`. | WEB-5 (code), DEPLOY-1 (config) |
| SE-26 | medium | ux | workflow Validate | Invalid graph: `POST /validate` returns `valid:false` with errors but nothing is shown (valid graph toasts). Observed in screenshots (fixme). | Tool node with no agent + HTTP node with no URL, click Validate. | Show `result.errors` as a toast/panel and highlight offending nodes in `WorkflowCanvas.tsx`. | WEB-1 |
| SE-27 | medium | bug | POST /api/workflows | 400-char name returns 500 (blank name correctly 400). Tested (fixme). | POST with `name` of 400 chars. | `@Size(max=<column length>)` on the create/update request DTO in core/workflow; UI `maxLength`. | CORE-2 |
| SE-28 | medium | bug | POST /api/knowledge/collections | 500 on a 500-char name (column 255, DTOs only `@NotBlank`). `topK` unbounded. Tested (fixme). | POST name of 500 chars. | `@Size(max=255)` on create/update DTOs; `@Min/@Max` on chunk sizes and `topK`. | CORE-4 |
| SE-29 | medium | ux | /knowledge collection delete | The 409 detail is shown raw with a UUID and "DELETE /api/knowledge/documents/{documentId}", overflows the sidebar, plus a duplicate global toast. Observed. | Delete a collection that has a file. | Map the 409 to a localized "Delete the N files first" message, wrap long text, use `toJsonQuietOn([409])`; backend drops the endpoint path from the detail. | WEB-2 (UI), CORE-4 (detail) |
| SE-30 | medium | ux | /playground composer | Send is enabled with 20000 chars; server rejects over 8000 with 400. Observed (fixme). | Paste 8001+ chars and send. | `maxLength` or a counter with disabled Send in `ChatPanel.tsx`, sharing the server constant; show the 400 detail inline. | WEB-3 |
| SE-31 | medium | a11y | all console routes | Same `<title>` on 59 of 66 routes (WCAG 2.4.2). Tested (fixme). | Read `document.title` on any two console routes. | `useDocumentTitle(title)` effect in `AppShell.tsx` from its title/eyebrow, or per-segment `generateMetadata`. | WEB-4 |
| SE-32 | medium | a11y | /usage, /admin/approvals, /admin/input-requests | /usage has two visible h1; the other two have none. Tested. | Inspect headings. | One h1 per page; `ApprovalsPanel` and input-requests render h1 when used as a page. | WEB-4 |
| SE-33 | medium | a11y | /admin/channels, /quick-task, /tools, /admin/contacts, /admin/branding | Switches without accessible name; unlabelled textarea/search/file inputs. Tested. | Run an axe scan or inspect. | `aria-label`/`aria-labelledby` on the shared Switch and the inputs. | WEB-4 |
| SE-34 | medium | bug | /tools at 768px | Horizontal overflow of 187 px (`shrink-0` flex header actions). Tested (fixme). | Resize to 768. | `flex-wrap`/`min-w-0` in `web/app/tools/page.tsx` header row. | WEB-4 |
| SE-35 | medium | bug | many pages | Probes that 404 and log console errors: `GET /api/browser/takeover/capabilities` (every fresh context), `GET /api/agents/{id}/improvements/health`, `POST /api/workflows/import/preview` on /workflows load (`workflowPortability.ts:112`), judge-calibration reads on /agents/quality, `GET /api/tutor/status` on /learn. Tested (several fixmes). | Load /playground, /agents, /workflows, /agents/quality. | Use `toJsonQuietOn([404])` or expose flags in `/api/session/config` or `/api/system/status`; replace the POST probe. Core: `/api/tutor/status` and takeover capabilities answer 200 `{enabled:false}`. | WEB-2 (api layer), CORE-5 (endpoints) |
| SE-36 | medium | bug | /memory, /insights, /view-as, /admin/integrations, /admin/issues | Intermittent CORS failure on `GET /api/public/status` (no `Access-Control-Allow-Origin`); not reproduced in two probes, likely a 429/5xx without CORS headers during load. Observed only. | Not reliably reproducible. | Check core logs for 429/502; ensure error and rate-limit responses carry CORS headers; make `platformStatus.ts:160` swallow failures. | WEB-6 |
| SE-37 | medium | performance | all console pages | 14-47 API calls per page; `/api/system/status` twice on nearly every page (3x /shared-with-me); /home 36 calls with 7x `/api/approvals`; /agents 3x providers/availability and 3x `POST /api/model-pricing`; credentials fetched serially per channel (9 calls); ~20 sidebar RSC prefetches (aborted); /home, /workflows, /integrations, /plugins, /directory never network-idle; canvas loads a 51 KB `/api/agents`. Tested/observed. | Load any console page with the network panel open. | In-flight request map in `apiFetch` (`core.ts`); one poller for status and approvals; `prefetch={false}` on sidebar links; cache static capability endpoints; slim agent list for node pickers; find what holds a connection open (likely `/api/system/status/live`). | WEB-2 (api layer), WEB-6 (shell) |
| SE-38 | medium | bug | POST /api/devices/poll | Without `X-Device-Token` returns 500 (`MissingRequestHeaderException` unhandled); the endpoint is permitAll so anyone can create 500s and log noise. Tested (fixme). | `curl -X POST .../api/devices/poll`. | Handle `MissingRequestHeaderException` in `RequestBindingExceptionHandler` (400/401) or make the header optional and reject null in `DeviceAuthResolver`. | CORE-1 |
| SE-39 | medium | bug | core platform issue recording | "Failed to record platform issue for unhandled exception" because `platform_issue` is looked up under the tenant search_path (relation does not exist). One occurrence in the log. Observed. | Cause an unhandled exception in a tenant request. | Run the fingerprint lookup/save in the control-plane schema (`ExceptionHandlerSupport`); same approach as other control-plane tables. Respect AGENTS.md rule 12. | CORE-1 |
| SE-40 | medium | test-gap | mock LLM | Mock replies `ok ok ...` in ~150 ms and never calls tools, so persona, bullets, language, maxTokens, stop sequence, tool use, supervised interception, approvals, stop-mid-stream and resume cannot be tested; judges are unavailable (eval runs FAILED). | Run `agent-workbench-behavior-live`. | Make `tests/load/kind/mock-llm.yaml` scriptable: echo a system-prompt marker, emit a tool call on a trigger phrase, answer judge prompts, add a slow profile (e.g. model `mock-slow:latest`). | DEPLOY-2 |
| SE-41 | medium | test-gap | signup.spec disposable email | The test submits a real signup (`e2e-prod-<stamp>@mailinator.com`, valid password); both runs reported "NOT refused" so users/tenants may have been created. Not checked. | Run `tests-prod/signup.spec.ts:125`. | Gate it behind a prod-only flag; check and clean up `e2e-prod-*` users/tenants. | TESTS-1 |
| SE-42 | medium | test-gap | ~28 prod specs | They assume the classic marketing site and fail in oss mode (routes 307 to `/`): landing hero, pricing, compare, hire, earn, billing, galleries, calculators. Tested. | Run `tests-prod/landing` on the stage. | `E2E_SITE_MODE=oss\|classic` in `helpers/prod-env.ts`; skip commercial specs in oss mode; add an oss landing spec. | TESTS-1 |
| SE-43 | medium | test-gap | tools, agent tool-call | Filesystem MCP activation/trust, custom server registration, agent tool call, approval and failure flows were never run on the stage. | n/a | After SE-10, re-run `mcp-server-registration-live` and `agent-tool-execution-live`; add `e2e-tools-mcp-` register/validate/delete/approval tests. Needs SE-40 for tool calls. | TESTS-1 |
| SE-44 | medium | test-gap | /platform-admin allowed paths | 17 admin pages, forms and mutations untestable: no platform-admin user exists on the stage. | n/a | Provision a platform-admin realm-role user in `tests/load/kind/provision.sh`; export `E2E_ADMIN_EMAIL/PASSWORD` from the stage env; un-fixme the 17 placeholders. | DEPLOY-2 |

### Low

| id | sev | kind | page | evidence | repro | suggested fix | batch |
|---|---|---|---|---|---|---|---|
| SE-45 | low | ux | /tools | Header "5 enabled / 26 available" vs list "1-10 of 23". Observed. | Open /tools. | Derive both from one list in `web/app/tools/page.tsx`. | WEB-4 |
| SE-46 | low | ux | /plugins, /account-deletion | /plugins says "COMING SOON" for VS Code/JetBrains/Chrome and "Available" below; contact link is `contact@example.com` (`platform-facts.ts:184`, `NEXT_PUBLIC_CONTACT_EMAIL` unset), also on the store-compliance /account-deletion page. Tested (fixme). | Open both pages. | Drive status from one catalog; omit the mailto or fail CI on the placeholder; set the env. | WEB-5 (code), DEPLOY-1 (env) |
| SE-47 | low | bug | /capabilities | 404; only `/capabilities/decision` exists. Tested (fixme). | Open /capabilities. | Add `web/app/capabilities/page.tsx` redirecting to `/capabilities/decision`. | WEB-4 |
| SE-48 | low | bug | /insights | "1 calls". Observed. | Open /insights with one call in the top-models list. | ICU plural in the insights i18n string. | WEB-4 |
| SE-49 | low | ux | /admin/channels | Header "12 available" but 11 tiles (`sms` counted, no tile). Tested. | Open the page. | Exclude `sms` from totals in `ChannelsPanel.tsx` or add a tile. | WEB-4 |
| SE-50 | low | ux | /admin/channels, /devices | `?channel=` is stripped after load (`router.replace('/admin/channels')`), so reload loses the tab. Tested. | Reload `/admin/channels?channel=slack`. | Keep the param and update it on tile click. | WEB-4 |
| SE-51 | low | ux | workflow run summary | Inspector shows "step 4/5 COMPLETED" for a finished branching run; inspector closed after reload. Observed. | Run a condition workflow. | Count the executed path or show "4 of 5 (1 branch not taken)"; remember panel state. | WEB-1 |
| SE-52 | low | behaviour | loop, approval node | Loop on non-array input yields `[]` silently; approval node "Auto-approved by policy" for a tenant admin (policy not inspected, unverified). Tested/observed. | Loop with input `plain`. | Log a warning on non-list input; show the auto-approving policy in the trace. | CORE-2 |
| SE-53 | low | bug | max-steps failure | ERROR trace row reuses the previous node's id and duplicates the last entry. Tested (fixme). | `maxStepsPerRun=1` on a 3-node graph. | Build the entry from the node about to run (`WorkflowExecutionEngine`). | CORE-2 |
| SE-54 | low | behaviour | run cancel/pause/resume | On terminal runs return 200 with unchanged state; cancel on RUNNING returns the pre-cancel status. Tested. | Cancel a COMPLETED run. | 409 with current status, or make the body reflect CANCELLING (`WorkflowController` ~658-700). | CORE-2 |
| SE-55 | low | bug | PATCH /execution-limits | Requires all three fields; a partial body names the wrong field. Tested (fixme). | `{maxStepsPerRun:-5}`. | Make fields optional (null = unchanged). | CORE-2 |
| SE-56 | low | behaviour | secrets rotate | Rotation ignores a changed description (UI shows a Note field). Tested (fixme). | POST same name twice with a new description. | Copy description/expiry/codeToolAccess on rotate in `SecretVaultService.store`, or hide the fields. | CORE-4 |
| SE-57 | low | behaviour | DELETE /api/secrets/{id}?purge=true | Unknown or already purged secret returns 400, revoke twice returns 204. Tested (fixme). | Purge twice. | 404 or idempotent 204. | CORE-4 |
| SE-58 | low | ux | /api/providers | Unknown provider name gives "missing or not valid JSON" / "Invalid request." (`ProviderName.valueOf` unguarded). Tested (fixme). | `DELETE /api/providers/NOPE`. | Message listing allowed values. | CORE-4 |
| SE-59 | low | behaviour | usage quota | `tokensUsedMonth=0` while the provider shows ~190k successful calls (BYOK path not counted). Intent unconfirmed. Observed. | GET `/api/tenant/usage-quota`. | Confirm intent in `FreeTierUsageLimiterService.quotaSnapshot`: label "platform pool only" or count all traffic. | CORE-4 |
| SE-60 | low | behaviour | duplicate names | Duplicate knowledge collection names (201 twice) and eval dataset names (200 twice) are accepted, and sidebar names truncate. Tested. | Create twice. | Decide intent; prefer a service-level 409 check. A DB unique constraint needs a new Flyway version (AGENTS.md rule 3) - ask first. | CORE-4 |
| SE-61 | low | ux | re-upload identical file | 400 quoting a document UUID. Tested. | Upload the same file twice. | 409 with a stable problem type; localized UI text. | CORE-4 (backend), WEB-2 (UI) |
| SE-62 | low | behaviour | share links | Dead-link codes differ (unknown agent 404, revoked 404, deleted 410, unknown workflow 410, demo 404), against the "indistinguishable" javadoc; invalid `/agent/x` renders HTTP 200 (soft 404). Tested/observed. | Request bogus and revoked share ids. | One code and body for every dead-link case; ensure noindex. | CORE-3 |
| SE-63 | low | ux | /quick-task | "Get it done" disabled with no reason when no phone is saved; auto-select picks an arbitrary agent. Observed. | Open with no phone and WhatsApp default. | `aria-describedby` helper text; default to Log only; show the selection rule. | WEB-3 |
| SE-64 | low | config | `EvalDatasetsSection.tsx` | 1145 lines (limit at the time 1000; now a 700-line cap that fails above 665 unless baselined; baseline not checked). | `wc -l`. | Check the file-size baseline; split if not baselined. | WEB-4 |
| SE-65 | low | a11y | /admin/channels | "Bot Token is required." announced twice (two `role=alert`). Tested. | Empty Telegram Connect. | One alert region per form. | WEB-4 |
| SE-66 | low | a11y | misc | "Code intelligence" header lacks `aria-expanded` until toggled; inspector Label input named after the node type; duplicate "Delete dataset" names and unlabelled persona checkboxes; theme popover without role/aria-expanded. Tested/observed (not an axe run). | Inspect each. | Fix in `AgentWorkbench.tsx`, `NodeConfigDrawer.tsx`, `EvalDatasetsSection.tsx`, `SimulationSection.tsx`, theme popover. | WEB-4 |
| SE-67 | low | behaviour | /llms.txt | Still describes the classic commercial product (UPI, plans) in oss mode. Observed (fixme). | `curl /llms.txt`. | Serve an oss variant when `isOssSite()`. | WEB-5 |
| SE-68 | low | config | admin host | Sign-in uses `client_id=agentic-ai-web`, existing spec expects `agentic-ai-platform-admin`. Realm not checked. Tested (fixme). | Open `/platform-admin` signed out. | Decide intended client; set `NEXT_PUBLIC_KEYCLOAK_CLIENT_ID` for the admin host or update the spec. | DEPLOY-1 |
| SE-69 | low | performance | Telegram connect | Bad token took 0.5-7.7 s with a spinner only. Tested. | POST a junk token. | Timeout on Telegram `getMe`; "Validating..." text. | CORE-5 |
| SE-70 | low | ux | agent schedule | `DELETE /api/agents/{id}/schedule` only disables; Schedules tab tells a tenant admin "Writing requires a tenant admin." Observed. | Delete a schedule. | Add real removal or label "Pause"; show the hint to non-admins only. | CORE-5 (endpoint), WEB-4 (hint) |
| SE-71 | low | config | device claim limiter | 10 per IP per 10 min shared by all stage clients; reruns got 429 for ~10 min. Observed. | Claim 11 times. | Raise on the stage only. | DEPLOY-2 |
| SE-72 | low | performance | `web/public/logo-mark.png` | 359 KB, used in `layout.tsx`, `privacy/page.tsx`, `api-reference/page.tsx`, `global-error.tsx`. Observed. | Load /plugins. | Resize to display size or WebP/SVG. | WEB-6 |
| SE-73 | low | performance | core JSON responses | `GET /api/agents` 45 KB, no content-encoding or ETag. Observed. | `curl -I`. | Enable compression at ingress or `server.compression.enabled=true`. | CORE-5 |
| SE-74 | low | performance | /skills | `GET /api/skills/registry/search` 2852 ms, the only call over 2 s in 66 routes. Observed. | Open /skills. | Short-TTL server cache or lazy load. | CORE-5 |
| SE-75 | low | config | edge manifest | `rife-video-interpolation` has a url but no sha256/shardSha256 (console warning). Observed. | Open any console page. | Add the hash to the manifest served by `/api/edge/manifest` (find the source file). | CORE-5 |
| SE-76 | low | config | stage memory | web pod 4 restarts, postgres 2, intermittent 20 s timeouts under parallel load. | `kubectl get pods`. | Check limits against `~/vegaduta-values-kind-memory.yaml`. | DEPLOY-2 |
| SE-77 | low | test-gap | disabled features | Research, judge calibration, scheduled tasks, coding tasks, data tables are off on the stage; no second tenant user for shared-with-me; no hosted-form workflow. | Open the pages. | Enable flags and seed fixtures in the kind values/`provision.sh`; document in `docs/configuration.md`. | DEPLOY-2 |
| SE-78 | low | test-gap | existing workflow specs | Leave `E2E NodeCfg ...` workflows when a test fails. Observed. | Run `workflow-node-config-live` with a failure. | Delete in `finally` in the `withWorkflow` helper. | TESTS-1 |
| SE-79 | low | test-gap | a11y, perf specs | Existing a11y/responsive specs cover only logged-out pages; no LCP capture. | n/a | Adopt `stage-a11y-responsive-errors-live.spec.ts`; add a PerformanceObserver set before navigation. | TESTS-1 |
| SE-80 | low | ux | GET /api/workflows (no token) | 401 has an empty body while other errors use problem+json. Observed. | `curl`. | problem+json from the security entry point. | CORE-5 |
| SE-81 | low | behaviour | Validate and Run | Each first PUT-saves the canvas and creates a workflow version (6 after 6 clicks). Observed. | Click Validate repeatedly. | Skip PUT when clean; dedupe identical versions. | WEB-1 |
| SE-82 | low | bug | admin host | Something links to `/platform-admin/docs` (404, plus prefetch). Observed. | Open the admin host. | Point at the real docs URL or `prefetch={false}`. | WEB-5 |

## Counts

| Severity | Count |
|---|---|
| blocker | 1 |
| high | 10 |
| medium | 33 |
| low | 38 |
| total | 82 |

## Dropped (no evidence or not a defect)

- First chat turn 6 s (not reproduced) and mock chat turn 11 s (mock pacing).
- `/hire` and `/earn` redirect to `/` (intended in oss site mode).
- `llm-providers` denied page 1.26 s (inconclusive; cold compile).
- `requestStorageAccess` console error (side effect of the http stage; resolved by SE-01).
- `/eval` 404 (dev-only route by design).

## Fix batches

Shared rules for every batch (repeated in each prompt):

- Run one Maven build at a time (shared `~/.m2`). Use `JAVA_HOME=/Users/mroads/jdk/jdk-24.0.2+12/Contents/Home`. After a signature change compile the whole reactor (`cd core && mvn -B -ntp -DskipTests package`); `-pl X -am` does not build dependents.
- Run `make spring-wiring` and `bash scripts/ci/entity-name-lint.sh` for Java changes. Never edit a released Flyway migration; new versions share one namespace across `controlplane/` and `tenant/`; run `bash scripts/ci/flyway-lint.sh`.
- Work handed to another thread must carry the tenant (`TenantScope.capture()`, `TenantScopedFlux`); `AsyncHopTenantScopeRulesTest` must stay green.
- No `localhost`/`127.0.0.1` in browser-facing URLs or token issuers; document new config in `docs/configuration.md` and `deploy/compose/.env.example`.
- Architecture baselines only shrink (`core/app/src/test/resources/archunit_store`).
- Add a line under `## [Unreleased]` in `CHANGELOG.md` for user-visible changes. Conventional Commits, `git commit -s`.
- Other sessions may be working in the same checkout: stage and commit explicit paths only. Do not commit unrelated files (especially untracked `tests/e2e/wfb/`, `tests/e2e/stage-public-auth-http/`, `tests/e2e/.e2e-prod-auth-state*.json`, `tests/e2e/stage-results.json`, and the `docs/operations/local-stack-guide.md` change you did not make).
- State what you verified: compiled, unit-tested, or run on the stage.

Stage facts for verification: console `http://app.127.0.0.1.nip.io`, API `http://api.127.0.0.1.nip.io`, Keycloak `http://auth.127.0.0.1.nip.io`, mock LLM `mock:latest`. Browser tests need `--unsafely-treat-insecure-origin-as-secure` (configured in `tests/e2e/playwright.stage.local.config.ts`). Test user: `loadtest@vegaduta.test`; ask the user for the password, never write it into a file or commit.

Run a spec: `cd tests/e2e && npx playwright test -c playwright.stage.local.config.ts tests-prod/<spec> --project=chromium --workers=1`.

### Batch index

| Batch | Area | Items | Main files |
|---|---|---|---|
| WEB-1 | workflow canvas | SE-02, 26, 51, 81 | `web/app/components/workflow/WorkflowCanvas.tsx`, `web/app/lib/requestLifecycle.ts` |
| WEB-2 | web api layer and knowledge | SE-06, 07, 29 (UI), 35 (api layer), 37 (api layer), 61 (UI) | `web/app/lib/api/*`, `KnowledgePanel.tsx`, `KnowledgeCollectionsPanel.tsx` |
| WEB-3 | chat and quick task | SE-17, 30, 63 | `ChatPanel.tsx`, `SimpleTaskRunner.tsx` |
| WEB-4 | a11y, layout, small pages | SE-11 (guard), 31-34, 45, 47-50, 64-66, 70 (hint) | `AppShell.tsx`, `tools/page.tsx`, `ChannelsPanel.tsx`, `code-intel/lib.ts`, `AgentWorkbench.tsx` |
| WEB-5 | public site | SE-05, 08 (fallback), 22-25, 46, 67, 82 | `web/app/sitemap.ts`, `robots.ts`, `OssChrome.tsx`, `lib/siteMode.ts`, `academyUrl.ts`, `platform-facts.ts` |
| WEB-6 | shell, overlays, perf | SE-01 (splash), 18, 36, 37 (shell), 72 | `TourProvider.tsx`, `AuthProvider*`, shell providers, `platformStatus.ts` |
| CORE-1 | exception handling | SE-04, 38, 39 | `core/app/.../common/*Handler*.java` |
| CORE-2 | workflow | SE-12-15, 27, 52-55 | `core/workflow` |
| CORE-3 | sharing | SE-03, 62 | `core/agents-turn/.../share`, `core/workflow/.../share` |
| CORE-4 | tenant config APIs | SE-21, 28, 29 (detail), 56-61 | `core/tenancy`, `core/knowledge`, `core/secrets`, providers controller |
| CORE-5 | misc core | SE-09 (fast fail), 19 (log), 35 (endpoints), 69, 70 (endpoint), 73-75, 80 | various |
| DEPLOY-1 | repo deploy configs and docs | SE-01, 08, 11, 16, 25, 46, 68 | `deploy/`, `docs/` |
| DEPLOY-2 | kind stage harness | SE-01, 09, 16, 19, 20, 40, 44, 71, 76, 77 | `tests/load/kind/*` |
| TESTS-1 | e2e helpers | SE-10, 41-43, 78, 79 | `tests/e2e/helpers`, existing `tests-prod` specs |

Batches that edit the same spec file for different `fixme` markers (several areas) should edit only their own markers.

---

## PROMPT WEB-1: workflow canvas

```
You are fixing web defects in the VegaDuta workflow designer (repo: /Users/mroads/Documents/GitHub/vegaduta-oss, Next.js console under web/). Other agents work in the same checkout: edit only the files listed and commit explicit paths only. Do not commit unrelated files.

Context: a stage E2E run found these issues. Specs that reproduce them are tests/e2e/tests-prod/stage-workflow-exec-live.spec.ts and stage-workflow-build-live.spec.ts (each has test.fixme markers).

Fix:
1. SE-02 (high): web/app/components/workflow/WorkflowCanvas.tsx ~line 1856 calls crypto.randomUUID() unguarded in runWithRuntimeInputs, after setBusy(true)/setRunLive(true) and outside the try/finally. On plain-http origins crypto.randomUUID does not exist, so Run hangs forever. Extract the guarded id helper from web/app/lib/requestLifecycle.ts (makeRequestId, ~line 357; getRandomValues fallback) into a shared util, use it here, move the call inside the try so busy/runLive always reset, and toast an error if starting fails. Grep web/ for other bare crypto.randomUUID calls (web/app/lib/localcode/fsAccess.ts already guards).
2. SE-26 (medium): the Validate button shows nothing when POST /api/workflows/{id}/validate returns valid:false with errors. Show each error (toast or panel) and highlight the offending nodes.
3. SE-51 (low): inspector shows "step 4/5 COMPLETED" for a finished branching run; show "4 of 5 (1 branch not taken)" or count the executed path; remember inspector open/closed per workflow.
4. SE-81 (low): Validate and Run first PUT-save the canvas and create a version each time; skip the PUT when the canvas is clean.

Rules: TypeScript, existing design tokens/components, no new UI framework. Files over 665 lines (hard cap 700) fail the file-size ratchet unless baselined: WorkflowCanvas.tsx may already be large, so put new logic in new small files instead of growing it. No localhost URLs. Add a CHANGELOG.md line under [Unreleased].

Acceptance: (a) a vitest that stubs crypto.randomUUID = undefined and shows Run still starts; (b) Validate on an invalid graph shows the messages; (c) npm run lint, npm run typecheck and npm test pass in web/.

Verify: cd web && npm run lint && npm run typecheck && npm test. Then on the stage (http://app.127.0.0.1.nip.io, ask the user for the loadtest@vegaduta.test password) un-fixme the Run-on-http test in stage-workflow-exec-live.spec.ts and stage-workflow-build-live.spec.ts and the Validate test, remove the randomUUID polyfill from the spec only where the test is meant to cover the real button, and run them with: cd tests/e2e && npx playwright test -c playwright.stage.local.config.ts tests-prod/stage-workflow-exec-live.spec.ts tests-prod/stage-workflow-build-live.spec.ts --project=chromium --workers=1. Report exactly what you ran (unit tests vs stage run).
```

## PROMPT WEB-2: web API layer and knowledge

```
You are fixing web defects in the VegaDuta console API layer and Knowledge page (repo: /Users/mroads/Documents/GitHub/vegaduta-oss, web/). Other agents work in the same checkout: edit only the files listed and commit explicit paths only. Do not commit unrelated files.

Fix, in priority order:
1. SE-07 (high): stale GET cache after a write. In web/app/lib/api/core.ts apiFetch clears getCache BEFORE a write is sent, refreshGet() stores the response of a GET that was in flight (or started during the write) without checking cacheEpoch, and inflightGets is never cleared on a write. Result: after creating a collection or uploading a file the list/file table does not show it until reload. Fix: on any non-GET clear inflightGets; flush again and bump cacheEpoch after the write resolves; in refreshGet capture the epoch at start and skip getCache.set and persistGet if it changed. Add a unit test: GET in flight, then POST, then GET must hit the network.
2. SE-06 (high): no UI to delete a knowledge document. Add deleteKnowledgeDocument(id) in web/app/lib/api/knowledge.ts (DELETE /api/knowledge/documents/{id}, returns 204) and a trash button with a confirm step next to Download in the file row of web/app/components/KnowledgePanel.tsx (~line 1218); refresh via fetchDocuments and onDocumentsChanged.
3. SE-29 (medium): KnowledgeCollectionsPanel.tsx handleDelete shows the raw 409 detail (UUID plus an API path) overflowing the sidebar and a duplicate global toast. Map the 409 not-empty problem to a localized "Delete the N files in this collection first" message, break long words, use toJsonQuietOn([409]).
4. SE-61 (low): identical re-upload returns 400 quoting a document id; show a localized "already uploaded as <name>" message (backend CORE-4 may later return 409 with a stable type; handle both).
5. SE-35 (medium): quiet the probes that 404 on load: GET /api/browser/takeover/capabilities, GET /api/agents/{id}/improvements/health (web/app/lib/api/agents.ts:242), POST /api/workflows/import/preview (web/app/lib/api/workflowPortability.ts:112), the judge-calibration reads in web/app/lib/api/quality.ts. Use toJsonQuietOn([404]) and cache the miss; do not add a failing-POST probe.
6. SE-37 (medium, api layer only): in-flight request dedupe in apiFetch so /api/system/status, /api/providers/availability, POST /api/model-pricing and similar are not issued 2-3 times per page; cache static capability endpoints (edge/manifest, coding-tasks/capabilities, scheduled-tasks/capabilities, data-tables/config). Do NOT edit shell components (another agent owns AppShell/providers).

Rules: TypeScript, existing components. Keep each file at or under 665 lines (hard cap 700). Add a CHANGELOG.md line under [Unreleased].

Acceptance: unit tests for items 1 and 6 pass; npm run lint, typecheck, test pass; on the stage the knowledge flow shows new collections/files immediately 10 runs in a row.

Verify: cd web && npm run lint && npm run typecheck && npm test. On the stage (http://app.127.0.0.1.nip.io, ask the user for the loadtest@vegaduta.test password) un-fixme the matching tests in tests/e2e/tests-prod/stage-knowledge-data-live.spec.ts (document delete from UI; remove the 'KD DEFECT-OBSERVED' tolerance so a stale list fails) and run: cd tests/e2e && npx playwright test -c playwright.stage.local.config.ts tests-prod/stage-knowledge-data-live.spec.ts --project=chromium --workers=1 --repeat-each=3. Say which checks were unit-tested and which were run on the stage.
```

## PROMPT WEB-3: chat and quick task

```
You are fixing web defects in the VegaDuta chat Playground and Quick Task pages (repo: /Users/mroads/Documents/GitHub/vegaduta-oss, web/). Other agents work in the same checkout: edit only web/app/components/ChatPanel.tsx, web/app/components/SimpleTaskRunner.tsx and files they import for this change; commit explicit paths only. Do not commit unrelated files.

Fix:
1. SE-17 (medium): after a finished chat and reload the UI shows "No messages yet." and "No session yet", yet still GETs /api/agents/{id}/sessions/{oldSid}/llm-requests and /llm-usage for the old session. Only in-flight turns are restored (ChatPanel.tsx ~441-490). Restore the last session's history for the persisted sessionId (GET the session history and render it), or drop the stored session id so no stale per-session calls are made. If the loss is intended, say so in the UI copy and stop the stale calls.
2. SE-30 (medium): the composer enables Send with 20000 chars but the server rejects messages over 8000 (400 "message is too long (8000 character maximum)"). Add a counter/maxLength using one shared constant and show the server's 400 detail inline.
3. SE-63 (low): in SimpleTaskRunner.tsx "Get it done" is disabled with no reason when no phone number is saved and the channel is WhatsApp. Add aria-describedby helper text ("Add a phone number or choose Log only"), consider defaulting to Log only when no phone is on file, and show the auto-select rule in the plan card.

Rules: TypeScript, existing design tokens and components, no new UI framework. Keep files at or under 665 lines (hard cap 700; extract new code into new files if ChatPanel.tsx is near the limit). Add a CHANGELOG.md line under [Unreleased].

Acceptance: reload after a completed chat shows the transcript (or no stale per-session calls and clear copy); Send is blocked or warned above 8000; disabled Get it done explains why; lint, typecheck, vitest pass.

Verify: cd web && npm run lint && npm run typecheck && npm test. On the stage (http://app.127.0.0.1.nip.io; ask the user for the loadtest@vegaduta.test password) un-fixme the matching tests in tests/e2e/tests-prod/stage-chat-switch-live.spec.ts (completed transcript restore) and stage-eval-insights-live.spec.ts (composer limit) and run them with: cd tests/e2e && npx playwright test -c playwright.stage.local.config.ts <spec> --project=chromium --workers=1. Report unit-tested vs stage-run.
```

## PROMPT WEB-4: accessibility, layout and small pages

```
You are fixing accessibility and layout defects in the VegaDuta console (repo: /Users/mroads/Documents/GitHub/vegaduta-oss, web/). Other agents work in the same checkout: edit only files related to the items below; commit explicit paths only. Do not commit unrelated files. Do not touch web/app/lib/api/* (another agent owns it) or TourProvider/AuthProvider.

Fix:
1. SE-31 (medium): every console route has the same <title> (59 of 66). Add a useDocumentTitle effect in web/app/components/AppShell.tsx from its title/eyebrow (or per-segment generateMetadata); format "<Page> - VegaDuta".
2. SE-32 (medium): /usage renders two visible h1; /admin/approvals and /admin/input-requests have none (ApprovalsPanel renders h2). One h1 per page; keep h2 when embedded in /admin/maintenance.
3. SE-33 (medium): unnamed role=switch buttons in channel settings (Sentinels enabled etc.); unlabelled textarea in /quick-task and /admin/contacts, search input on /tools, file input in the branding modal. Add aria-label / aria-labelledby on the shared Switch and those inputs.
4. SE-34 (medium): /tools overflows 187px at 768 wide; header action row uses shrink-0 flex without wrap in web/app/tools/page.tsx: add flex-wrap / min-w-0.
5. SE-45: /tools subtitle "5 enabled / 26 available" vs list "1-10 of 23": derive both from one list.
6. SE-47: /capabilities returns 404; add web/app/capabilities/page.tsx redirecting to /capabilities/decision.
7. SE-48: insights "1 calls": use an ICU plural in the i18n string rendered by web/app/insights/page.tsx.
8. SE-49 / SE-50 / SE-65 in web/app/components/ChannelsPanel.tsx: header says 12 available but 11 tiles (exclude sms from totals or add a tile); keep ?channel= in the URL (do not router.replace it away) and update it on tile click; one role=alert per form (Bot Token message is announced twice).
9. SE-11 guard (high, browser side): web/app/code-intel/lib.ts must ignore a localhost URL when the page origin is not localhost (use the core proxy), and web/app/components/agents/AgentWorkbench.tsx must skip the /repos call when code-intel is not configured; show "code-intel not enabled" instead of a localhost hint on /code-intel. (DEPLOY-1 changes the compose default; you only change the code.)
10. SE-66 (low): AgentWorkbench "Code intelligence" header needs aria-expanded from first paint; inspector Label input in NodeConfigDrawer.tsx needs an accessible name "Label"; EvalDatasetsSection.tsx row buttons include the dataset name in aria-label; SimulationSection.tsx persona checkboxes get labels; theme popover gets role=dialog and trigger aria-expanded.
11. SE-64: web/app/agents/quality/EvalDatasetsSection.tsx is 1145 lines; check the file-size baseline (scripts/ci) and split if not baselined.
12. SE-70 hint: web/app/components/jobs/SchedulePanel.tsx shows "Writing requires a tenant admin." to tenant admins; show only to non-admins.

Rules: TypeScript, existing tokens; no new UI framework; files at or under 665 lines (hard cap 700). Add a CHANGELOG.md line under [Unreleased].

Acceptance: lint, typecheck, vitest pass; each item verifiable in the browser.

Verify: cd web && npm run lint && npm run typecheck && npm test. On the stage (http://app.127.0.0.1.nip.io; ask the user for the loadtest@vegaduta.test password) un-fixme the matching tests in tests/e2e/tests-prod/stage-a11y-responsive-errors-live.spec.ts, stage-channels-devices-live.spec.ts, stage-tools-mcp-live.spec.ts, stage-agents-live.spec.ts (only your items) and run them with: cd tests/e2e && npx playwright test -c playwright.stage.local.config.ts <spec> --project=chromium --workers=1. Report unit-tested vs stage-run.
```

## PROMPT WEB-5: public site

```
You are fixing public-site defects in the VegaDuta web app (repo: /Users/mroads/Documents/GitHub/vegaduta-oss, web/). The stage runs NEXT_PUBLIC_SITE_MODE=oss, where commercial routes 307 to / (web/proxy.js, web/app/lib/siteMode.ts) - that is intended. Other agents work in the same checkout: edit only files related to these items; commit explicit paths only. Do not commit unrelated files.

Fix:
1. SE-05 (high): sitemap.xml and the robots.txt Sitemap line use http://localhost:3000 because web/app/sitemap.ts and robots.ts are static and web/app/lib/site-url.ts is evaluated at next build. Add export const dynamic = 'force-dynamic' (or derive the origin at request time from env/Host). AGENTS.md rule 4: no localhost in browser-facing URLs.
2. SE-24: in oss mode the sitemap lists routes that redirect to / (/try, /hire, /enterprise, /earn, /compare...). Filter with isCommercialRoute() when isOssSite().
3. SE-22: unknown URLs return 404 but render the landing page instead of web/app/not-found.tsx, and the response is ~830 KB. Make the anonymous gate (AuthProvider*) let notFound() render for routes that do not exist.
4. SE-23: the header link /#dependencies updates the hash but does not scroll when already on / (web/app/components/oss-landing/OssChrome.tsx): use a plain anchor or scrollIntoView on click.
5. SE-25: remove the http://localhost:4100 default in web/app/lib/academyUrl.ts (hide the Academy link when NEXT_PUBLIC_ACADEMY_URL is unset); make sure no public HTML or /__public-env carries localhost.
6. SE-46: /plugins contradicts itself (catalog says COMING SOON for VS Code/JetBrains/Chrome, section below says Available): drive both from one catalog. web/app/lib/platform-facts.ts:184 falls back to contact@example.com; omit the mailto (also on /account-deletion, which is a store-compliance page) when the env is unset.
7. SE-67: web/app/llms.txt still advertises the classic commercial product (UPI, plans); serve an oss variant when isOssSite().
8. SE-82: something on the admin host links to /platform-admin/docs (404); point it at the real docs URL or prefetch={false}.
9. SE-08 (optional): in web/app/lib/auth.ts logout() (~line 586) fall back to the app origin if post_logout_redirect_uri fails (DEPLOY-1 fixes the Keycloak client itself).

Rules: TypeScript; no new UI framework; files at or under 665 lines (hard cap 700). Add a CHANGELOG.md line under [Unreleased].

Acceptance: curl of /robots.txt and /sitemap.xml on the stage contains no localhost and no redirected routes; unknown route shows the 404 page with the correct title and a small body; Dependencies link scrolls; lint, typecheck, vitest pass.

Verify: cd web && npm run lint && npm run typecheck && npm test. Stage verification needs a rebuilt web image (ask the user how the kind stage is rebuilt; do not restart pods yourself). Then un-fixme the matching tests in tests/e2e/tests-prod/stage-public-auth-live.spec.ts and stage-sharing-misc-live.spec.ts and run them: cd tests/e2e && npx playwright test -c playwright.stage.local.config.ts <spec> --project=chromium --workers=1. If the image was not rebuilt, say the stage check was NOT run.
```

## PROMPT WEB-6: shell, overlays, resilience, performance

```
You are fixing console shell defects in VegaDuta (repo: /Users/mroads/Documents/GitHub/vegaduta-oss, web/). Other agents work in the same checkout: edit only the shell files listed (AppShell and layout providers, TourProvider.tsx and web/app/components/tour/*, the Keycloak init/AuthProvider splash, web/app/lib/platformStatus.ts, web/public/logo-mark.png and its users). Do NOT edit web/app/lib/api/* (another agent owns request dedupe there). Commit explicit paths only. Do not commit unrelated files.

Fix:
1. SE-18 (medium): the "Build the agent" welcome modal (data-tour-blocker=welcome, z-1150), the Product tour (z-1152) and the "Turn on two-factor authentication" card appear seconds after load and intercept clicks; they reappear on fresh storage; setting vd_tour_status does not help because keys are per user (userScoped() in TourProvider.tsx ~1308-1345, 1544; vd_tour_status:<subject>, vd_tour_seen_security:<subject>). Do not mount the blocker while the user is typing or a turn is in flight, or on deep links to editing surfaces; persist dismissal per user (server-side or the same per-user keys, documented so tests can seed them); make the 2FA announcement non-modal and anchored away from toolbars; on Escape close the topmost layer first (command palette before tour).
2. SE-01 (web part): when Keycloak init fails after its retries (e.g. mixed content), show an error state with Retry and the failing endpoint instead of an endless "Signing you in..." splash (affects the platform-admin gate).
3. SE-36: intermittent CORS errors on GET /api/public/status from PlatformStatusBanner (web/app/lib/platformStatus.ts:160): make failures silent and non-noisy. Do not change CORS server config here.
4. SE-37 (shell only): one poller for /api/system/status and /api/approvals shared by all consumers (the shell currently fetches status twice per page and /home makes 7 approvals calls); sidebar Links get prefetch={false} (or prefetch on intent) - ~20 aborted RSC prefetches per page; find and document what keeps a connection open so /home, /workflows, /directory never reach network idle.
5. SE-72: web/public/logo-mark.png is 359 KB; resize to display size or ship WebP/SVG; use it in layout.tsx, privacy/page.tsx, api-reference/page.tsx, global-error.tsx.

Rules: TypeScript, existing tokens/components; no new UI framework; files at or under 665 lines (hard cap 700). Add a CHANGELOG.md line under [Unreleased].

Acceptance: a fresh signed-in context can click controls within the first 10 s without an overlay intercepting; Escape ordering is correct; /api/system/status is requested once per page; lint, typecheck, vitest pass.

Verify: cd web && npm run lint && npm run typecheck && npm test. On the stage (http://app.127.0.0.1.nip.io; ask the user for the loadtest@vegaduta.test password) un-fixme the matching tests in tests/e2e/tests-prod/stage-a11y-responsive-errors-live.spec.ts and stage-perf-efficiency-live.spec.ts and run them: cd tests/e2e && npx playwright test -c playwright.stage.local.config.ts <spec> --project=chromium --workers=1. Report unit-tested vs stage-run.
```

## PROMPT CORE-1: exception handling

```
You are fixing error mapping in the VegaDuta core (repo: /Users/mroads/Documents/GitHub/vegaduta-oss, Java 21 Spring Boot reactor under core/). Other agents work in the same checkout and other Maven runs may be active. Edit only core/app/src/main/java/com/agenticai/core/common/ handlers (and the one device controller if needed); commit explicit paths only. Do not commit unrelated files.

Fix:
1. SE-04 (high): EvalRunConflictException, EvalDatasetNotFoundException and EvalRunNotFoundException carry @ResponseStatus (CONFLICT/NOT_FOUND) but FallbackExceptionHandler (core/app/.../common/FallbackExceptionHandler.java, catches Exception.class) turns them into 500 "Something went wrong". Observed: second POST .../datasets/{id}/runs while one is QUEUED/RUNNING; DELETE of a dataset with a live run; GET of a deleted dataset; PUT /quality/gate with an unknown datasetId - all 500. The controller javadoc promises 409. Honour @ResponseStatus generically (AnnotatedElementUtils.findMergedAnnotation) in ResourceStateExceptionHandler or add explicit handlers; keep problem+json bodies and no stack traces. Grep other @ResponseStatus exceptions and cover them.
2. SE-38 (medium): POST /api/devices/poll with no X-Device-Token returns 500 (MissingRequestHeaderException, FallbackExceptionHandler "Unhandled exception"). Handle MissingRequestHeaderException in RequestBindingExceptionHandler (400, or 401 for auth headers), or declare @RequestHeader(value="X-Device-Token", required=false) in core/devices DeviceGatewayController (poll, ack) and DeviceDataIngestionController and reject null via DeviceAuthResolver. The endpoint is permitAll so this is attacker-triggerable log noise.
3. SE-39 (medium): "Failed to record platform issue for unhandled exception" because platform_issue is looked up under the tenant search_path ("relation platform_issue does not exist"). Run the fingerprint lookup/save in the control-plane schema (see ExceptionHandlerSupport). Respect AGENTS.md rule 12 (TenantContext on async hops: capture and re-apply with TenantScope).

Rules that bite: one Maven build at a time (shared ~/.m2) with JAVA_HOME=/Users/mroads/jdk/jdk-24.0.2+12/Contents/Home; compile the whole reactor after signature changes (cd core && mvn -B -ntp -DskipTests package); run make spring-wiring and bash scripts/ci/entity-name-lint.sh; do not edit released Flyway migrations; architecture baselines only shrink (archunit_store); new config documented in docs/configuration.md and deploy/compose/.env.example. Add a CHANGELOG.md line under [Unreleased].

Acceptance: eval conflict returns 409, missing dataset 404, missing device header 4xx, platform issue recorded without a relation error; add unit/WebMvc tests for each.

Verify: run the new tests with mvn -B -ntp -pl <module> test, then the full compile above, then the lint scripts. Stage run needs a redeployed core; ask the user - do not restart pods. If you cannot run on the stage, say "compiled and unit-tested, not run on a stack". When a stage is available un-fixme the tests in tests/e2e/tests-prod/stage-eval-insights-live.spec.ts and stage-channels-devices-live.spec.ts (only yours).
```

## PROMPT CORE-2: workflow module

```
You are fixing the VegaDuta workflow module (repo: /Users/mroads/Documents/GitHub/vegaduta-oss, core/workflow and its controller). Other agents work in the same checkout and other Maven runs may be active. Edit only core/workflow (and core/chat LlmMetricsAdvisor for SE-12); commit explicit paths only. Do not commit unrelated files.

Fix (evidence from stage specs tests/e2e/tests-prod/stage-workflow-exec-live.spec.ts and stage-workflow-build-live.spec.ts):
1. SE-12 (medium): cancelling a run during an LLM call breaks a pooled Postgres connection (HikariPool "marked as broken SQLSTATE(08006)") and loses the LLM outcome record (core/chat/.../LlmMetricsAdvisor.java:655 "Failed to record LLM stream outcome cancel ... Could not open JPA EntityManager"). Reproduced 2 of 2. Record the cancel outcome on a non-interrupted path before disposing the subscription; avoid Thread.interrupt on threads holding a JDBC connection; review the workflow cancel hook (WorkflowRunCancellationService users). AGENTS.md rule 12: any thread hop must carry TenantScope.
2. SE-13 (medium): PUT /api/workflows/{id}/schedule accepts "* * * * * *" (every second). In WorkflowService.enableSchedule (~line 1105) after CronExpression.parse compute two consecutive fire times and reject with a 400 workflow-validation message if the gap is below app.workflow.schedule.min-interval-seconds (default 60). Document in docs/configuration.md and the Helm/compose env.
3. SE-14 (medium): the Run modal appends "Run comment: ..." to the run input, so nodes and branches see it. Add a separate optional comment field to WorkflowRunRequest, store it on the run, return it in run history; stop relying on concatenation. If a DB column is needed add a NEW Flyway migration (one namespace across controlplane/ and tenant/; check the next free version; never edit released ones; run bash scripts/ci/flyway-lint.sh). The one web call site is runWithRuntimeInputs in web/app/components/workflow/WorkflowCanvas.tsx: change it only after WEB-1 has merged.
4. SE-15 (medium): failing nodes (bad regex, blocked HTTP URL, unusable classifier/extractor reply) leave the run COMPLETED with the node SKIPPED. Keep the fallthrough but surface it: a run-level warning count/flag, shown in the run response and history (NodeErrorStrategy exists).
5. SE-27 (medium): POST /api/workflows with a 400-char name returns 500; add @Size(max=<column length>) to the create/update DTO.
6. SE-52, 53, 54, 55 (low): loop on non-list input should log a warning; approval auto-approval should show its policy in the trace summary; max-steps ERROR trace row must carry the node about to run, not the previous node (WorkflowExecutionEngine); cancel/pause/resume on terminal runs return 409 with current status (WorkflowController ~658-700) and cancel on RUNNING should reflect CANCELLING; PATCH /execution-limits fields optional (null = unchanged).

Rules that bite: one Maven build at a time (shared ~/.m2) with JAVA_HOME=/Users/mroads/jdk/jdk-24.0.2+12/Contents/Home; -pl X -am does not build dependents, so finish with cd core && mvn -B -ntp -DskipTests package; run make spring-wiring, bash scripts/ci/entity-name-lint.sh, bash scripts/ci/flyway-lint.sh; tenant context on async hops; new config in docs/configuration.md and deploy/compose/.env.example; architecture baselines only shrink; files over 665 lines (hard cap 700) fail the ratchet unless baselined. Add CHANGELOG.md lines under [Unreleased].

Acceptance: sub-minute cron returns 400; 400-char name returns 400; cancel mid-LLM produces no Hikari broken-connection or LlmMetricsAdvisor error in the core log; run comment is a separate field; unit tests for each.

Verify: mvn -B -ntp -pl core/workflow test (from core/), then the full compile, then lint scripts. For the stage, ask the user to redeploy core (do not restart pods yourself); then un-fixme your tests in stage-workflow-exec-live.spec.ts and stage-workflow-build-live.spec.ts and run them. Say clearly: compiled, unit-tested, or run on the stage.
```

## PROMPT CORE-3: sharing

```
You are fixing agent and workflow sharing in VegaDuta core (repo: /Users/mroads/Documents/GitHub/vegaduta-oss, core/agents-turn/.../share and core/workflow/.../share, plus DemoShareController). Other agents work in the same checkout and other Maven runs may be active. Edit only those; commit explicit paths only. Do not commit unrelated files.

Fix:
1. SE-03 (high): share abuse reports and viewer counts are never persisted. Tested on the stage: 3 reports (each 204) leave the owner GET /api/agents/{id}/share at reportCount=0, viewerCount=0, active=true, though app.agent.share.report-revoke-threshold=3. The workflow share shows reportCount=0 after 1 report. Source analysis (not run): spring.jpa.open-in-view=false (application.yml:288); the controller loads the share via AgentShareService.resolveByShareId in a read-only tx so the entity is detached; AgentShareService.report() and recordViewer() are @Transactional but mutate that detached entity and never call repository.save; WorkflowShareService has the same shape. Fix: re-load the row by id inside the @Transactional method (repository.findById(share.getId()).orElseThrow()) or call save, then mutate. Remember withResolvedShare sets TenantContext, so the write must run inside it (AGENTS.md rule 12 if any thread hop is involved). Check DemoShareController for the same pattern.
2. SE-62 (low): dead-link responses differ: unknown agent id 404, revoked 404, deleted agent 410, unknown workflow id 410, bogus /api/demo/share 404, while the AgentShareController javadoc says unknown and revoked are deliberately indistinguishable. Pick one status and body for every dead-link case (AgentShareController.withResolvedShare, WorkflowShareController, DemoShareController); the UI already treats them alike.

Rules that bite: one Maven build at a time (shared ~/.m2) with JAVA_HOME=/Users/mroads/jdk/jdk-24.0.2+12/Contents/Home; finish with the whole reactor compile (cd core && mvn -B -ntp -DskipTests package); run make spring-wiring and bash scripts/ci/entity-name-lint.sh; no released Flyway edits; architecture baselines only shrink. Add a CHANGELOG.md line under [Unreleased].

Acceptance: an integration test: 3 reports revoke the link and the owner status shows reportCount=3; viewerCount increments; all dead-link cases return the same status and body.

Verify: mvn -B -ntp -pl <your modules> test (from core/), full compile, lint scripts. Stage: ask the user to redeploy core, then un-fixme SHARE-COUNTS in tests/e2e/tests-prod/stage-sharing-misc-live.spec.ts and run: cd tests/e2e && npx playwright test -c playwright.stage.local.config.ts tests-prod/stage-sharing-misc-live.spec.ts --project=chromium --workers=1. Say clearly what was compiled, unit-tested, or run on the stage. The 3 reports must be sent as the spec does (anonymous POST on the share link).
```

## PROMPT CORE-4: tenant config APIs

```
You are fixing input validation and consistency in VegaDuta core tenant configuration APIs (repo: /Users/mroads/Documents/GitHub/vegaduta-oss, core/tenancy, core/knowledge, core/secrets, and the providers controller). Other agents work in the same checkout and other Maven runs may be active. Edit only those modules; commit explicit paths only. Do not commit unrelated files.

Fix (specs: tests/e2e/tests-prod/stage-providers-billing-live.spec.ts and stage-knowledge-data-live.spec.ts have the test.fixme markers):
1. SE-21 (medium): PUT /api/tenant/ai-model-preferences/{featureKey} accepts any featureKey and stores an orphan row (e.g. "nofeature"); an unconnected provider and an invented model are also accepted. Reject keys not in AiModelFeature with 400 in TenantAiModelPreferenceController / TenantAiModelPreferenceService#upsert; optionally require PLATFORM_HOSTED or an ACTIVE credential.
2. SE-28 (medium): POST /api/knowledge/collections with a 500-char name returns 500 (column length 255; KnowledgeCollectionCreateRequest and KnowledgeCollectionUpdateRequest only have @NotBlank). Add @Size(max=255); add @Min/@Max on chunkSize, chunkOverlap and SearchRequest.topK (topK=100000 is accepted unbounded).
3. SE-29 backend: the 409 for deleting a non-empty collection includes an endpoint path in its detail ("DELETE /api/knowledge/documents/{documentId}"); drop it from KnowledgeCollectionNotEmptyException, keep a stable problem type.
4. SE-61: a duplicate-content upload returns 400 quoting a document UUID; return 409 with a stable problem type (knowledge-duplicate-document) and the file name only.
5. SE-56: secret rotate (POST same name) ignores a changed description; in SecretVaultService.store copy description, expiresAt and codeToolAccess on rotate.
6. SE-57: DELETE /api/secrets/{id}?purge=true for an unknown or already purged id returns 400 "No such secret"; return 404 (or idempotent 204 consistently with revoke).
7. SE-58: POST /api/providers {providerName:"NOPE"} says "missing or not valid JSON"; DELETE /api/providers/NOPE says "Invalid request." (ProviderName.valueOf unguarded). Return a detail listing the allowed values, like ai-model-preferences does.
8. SE-59: usage-quota tokensUsedMonth=0 while the provider shows ~190k successful calls (FreeTierUsageLimiterService.quotaSnapshot). Confirm intent with the user; either label the meter "platform pool only" or count all LLM traffic from LlmCallLogService.
9. SE-60: duplicate knowledge collection names and duplicate eval dataset names are accepted. Ask the user for the intended rule first; implement as a service-level 409 check. Do NOT add a DB unique constraint without approval (new Flyway version, one namespace across controlplane/ and tenant/, never edit released ones, bash scripts/ci/flyway-lint.sh).

Rules that bite: one Maven build at a time (shared ~/.m2), JAVA_HOME=/Users/mroads/jdk/jdk-24.0.2+12/Contents/Home; finish with cd core && mvn -B -ntp -DskipTests package; run make spring-wiring and bash scripts/ci/entity-name-lint.sh; tenant context on async hops; architecture baselines only shrink; new REST controllers must not reuse another's base path. Add CHANGELOG.md lines under [Unreleased].

Acceptance: each case above returns the documented 4xx with a clear message and tests cover it.

Verify: mvn -B -ntp -pl <module> test per module, full compile, lint scripts. Stage: ask the user to redeploy core; then un-fixme your tests in the two specs and run them with: cd tests/e2e && npx playwright test -c playwright.stage.local.config.ts <spec> --project=chromium --workers=1. Say clearly: compiled, unit-tested, or run on the stage.
```

## PROMPT CORE-5: miscellaneous core

```
You are fixing small independent core issues in VegaDuta (repo: /Users/mroads/Documents/GitHub/vegaduta-oss, Java 21 Spring Boot under core/). Other agents work in the same checkout and other Maven runs may be active. Edit only the files for the items below; commit explicit paths only. Do not commit unrelated files.

Fix:
1. SE-19 (medium): ContextualBanditService logs WARN "RL policy MODEL_ROUTE is ENABLED for agent ... but has no selectable arms" on every chat turn (hot path). Log once per agent per hour.
2. SE-09 (core part): the SSRF guard IllegalArgumentException from LlmHttpClientFactory.requireAllowedTenantBaseUrl ("model server URL is not allowed ... internal host name") makes background jobs retry and fail after 32 s; treat it as a non-retryable config error so the job fails fast with a clear message. (DEPLOY-2 fixes the stage env.)
3. SE-35 (endpoints): GET /api/tutor/status (core/agents-studio/.../tutor/StudyPlanController.java) and GET /api/browser/takeover/capabilities should answer 200 {enabled:false} when disabled instead of 404; keep current paths and auth.
4. SE-69: Telegram bot-token validation (getMe) in core/omnichannel took up to 7.7 s; add a connect/read timeout and a clear 4xx message.
5. SE-70: DELETE /api/agents/{id}/schedule (AgentScheduleController#disable) only disables; add a real remove endpoint or document it as pause (coordinate naming with the UI hint in WEB-4).
6. SE-73: JSON responses are not compressed (GET /api/agents 45 KB, no content-encoding/ETag); enable server.compression for JSON in core/app-config application.yml (not for SSE/event-stream) and document it.
7. SE-74: GET /api/skills/registry/search took 2852 ms; add a short-TTL server cache or make the call cheaper.
8. SE-75: the edge manifest served by /api/edge/manifest has an entry (rife-video-interpolation) with a url but no sha256/shardSha256; find the source file and add the hash.
9. SE-80: GET /api/workflows without a token returns 401 with an empty body; return problem+json from the security entry point like other errors.

Rules that bite: one Maven build at a time (shared ~/.m2), JAVA_HOME=/Users/mroads/jdk/jdk-24.0.2+12/Contents/Home; -pl X -am does not build dependents, so finish with cd core && mvn -B -ntp -DskipTests package; run make spring-wiring and bash scripts/ci/entity-name-lint.sh; keep the chat hot path in-process (no new network hop); tenant context on async hops; new config documented in docs/configuration.md and deploy/compose/.env.example; architecture baselines only shrink; files over 665 lines (hard cap 700) fail the ratchet unless baselined. Add CHANGELOG.md lines under [Unreleased].

Acceptance: unit tests per item; no regressions in the SSE path when compression is enabled (check Content-Type text/event-stream is excluded).

Verify: mvn -B -ntp -pl <module> test per module, full compile, lint scripts. For the stage ask the user to redeploy core (never restart pods yourself), then run the relevant tests in tests/e2e/tests-prod/stage-*-live.spec.ts. Because item 6 touches the chat path, ask the user whether to run the 32-user kind benchmark afterwards (project memory: validate under load before calling a turn-path change healthy). Say clearly: compiled, unit-tested, or run on the stage.
```

## PROMPT DEPLOY-1: repo deploy configs and docs

```
You are fixing deployment configuration and docs in VegaDuta (repo: /Users/mroads/Documents/GitHub/vegaduta-oss; deploy/compose, deploy/helm, docs/). Other agents work in the same checkout: edit only deploy/ and docs/ files named below; commit explicit paths only. Do not commit unrelated files - note docs/operations/local-stack-guide.md already has someone else's uncommitted change: read it first, merge your edit on top, and do not revert theirs.

Fix:
1. SE-01 (blocker): the kind stage serves https at the ingress but the web build bakes http:// NEXT_PUBLIC_API_BASE_URL / NEXT_PUBLIC_KEYCLOAK_URL / NEXT_PUBLIC_SITE_URL, so the browser blocks every API call (mixed content). Decide the scheme with the user. Either (a) make the public URL helper in deploy/helm/charts/web (vegaduta-lib.publicUrl) and the issuer settings consistent for https (KEYCLOAK_ISSUER_URI, KEYCLOAK_ADDITIONAL_ISSUERS per AGENTS.md rule 7, ingress TLS), or (b) document in docs/operations/local-stack-guide.md that the kind stage is http-only and that nip.io over http needs the browser flag --unsafely-treat-insecure-origin-as-secure because keycloak-js requires a secure context. The kind values file itself is owned by the DEPLOY-2 batch (tests/load/kind/).
2. SE-11 (compose part): deploy/compose/docker-compose.build.yml:50, docker-compose.yml:548 and deploy/compose/.env.example:76 default PUBLIC_CODE_INTEL_URL to http://localhost:8090, which is baked into the browser bundle (AGENTS.md rule 4). Default to empty (as lite.yml:281 does) so web/app/code-intel/lib.ts uses the core proxy. Document in docs/configuration.md.
3. SE-25 / SE-46 (config): set or document NEXT_PUBLIC_ACADEMY_URL and NEXT_PUBLIC_CONTACT_EMAIL (placeholders are contact@example.com and http://localhost:4100) in the chart values, .env.example and docs/configuration.md; no real addresses or secrets in the repo.
4. SE-08 (high): the admin host sign-out gets Keycloak 400 "Invalid redirect uri" for http://admin.127.0.0.1.nip.io. deploy/helm/charts/keycloak/templates/_realm.tpl line 16 already sets the post-logout URIs to "<app>/* <admin>/*", so the live stage realm is stale or the admin publicUrl is not rendered. Verify the template renders the admin URL for the kind values; if the stage realm is stale, document the one-line patch/re-import command (do not change the live realm yourself).
5. SE-68: the admin host signs in with client agentic-ai-web, a spec expects agentic-ai-platform-admin. Check the realm template; decide the intended client with the user; set NEXT_PUBLIC_KEYCLOAK_CLIENT_ID for the admin host or note the spec should change.
6. SE-16: add APP_CHAT_RESUMABLE_STREAMS_ENABLED to the core chart env and document it (the redis STORE setting has no effect while ENABLED is false, application.yml:1153).

Rules that bite: never hardcode localhost/127.0.0.1 in browser-facing URLs or token issuers (only internal service-to-service URLs); APP_ENCRYPTION_KEY identical across core shapes; PgBouncer stays in session pooling mode; no secrets in the repo (bash scripts/ci/secret-scan.sh); new config documented in docs/configuration.md and deploy/compose/.env.example. Add a CHANGELOG.md line under [Unreleased].

Acceptance: make compose-validate and make helm-lint pass; the rendered web deployment has no localhost value in any NEXT_PUBLIC_* variable.

Verify: make compose-validate && make helm-lint && make shellcheck; bash scripts/ci/secret-scan.sh. Rendering check: helm template deploy/helm/charts/vegaduta ... | grep -n localhost. Do not apply anything to the kind cluster; say clearly that the stage was not changed.
```

## PROMPT DEPLOY-2: kind stage harness

```
You are fixing the VegaDuta kind stage test harness (repo: /Users/mroads/Documents/GitHub/vegaduta-oss; tests/load/kind/ - values-loadtest.yaml, mock-llm.yaml, provision.sh, stage.env). Other agents work in the same checkout: edit only tests/load/kind/ files; commit explicit paths only. Do not commit unrelated files. Never apply anything to the live cluster or restart pods yourself; give the user the exact helm upgrade command (always include ~/vegaduta-values-kind-memory.yaml, per project memory) and let them run it.

Fix:
1. SE-09 (high): background jobs, scheduled agent runs and the Temporal worker cannot call the mock LLM: vegaduta-core has APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS=mock-llm:11434 but vegaduta-core-jobs does not (values-loadtest.yaml sets it under core.env only). Add it to core-jobs.env, core-worker.env and any other shape. Every job currently fails EXECUTION_FAILED "model server URL is not allowed".
2. SE-01 (kind values): make the public URLs consistent with the scheme chosen in DEPLOY-1 (https with TLS, or http-only plus a note in the stage README).
3. SE-16: set APP_CHAT_RESUMABLE_STREAMS_ENABLED=true for core.
4. SE-40: make mock-llm scriptable: (a) echo a persona/system-prompt marker, (b) emit a tool call on a trigger phrase, (c) answer judge prompts so eval runs can finish, (d) add a slow profile, e.g. model name mock-slow:latest with TOKEN_MS=300 and TOKENS=60 (today FIRST_TOKEN_MS=50, TOKEN_MS=5, TOKENS=20, a whole reply is ~150 ms, so stop-mid-stream and reload-resume cannot be tested). Keep the default behaviour unchanged so the 32-user benchmark numbers are comparable.
5. SE-19: in provision.sh seed arms for the MODEL_ROUTE policy (POST /api/rl/agents/{id}/policies/MODEL_ROUTE/arms) or disable the policy for the load-test agent.
6. SE-44: in provision.sh create a platform-admin user (realm role platform-admin) and export E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD from stage.env (generate the credentials; do not print or commit real passwords - write them to the gitignored stage env only). Also seed a second tenant member (for shared-with-me) and one workflow with a hosted form trigger.
7. SE-20 / SE-71: relax Keycloak brute-force settings for the stage realm only (quick-login wait) and raise app.devices.claim.rate-limit.max-attempts-per-ip on the stage; production defaults unchanged. Document.
8. SE-77: enable research (stub search key), judge calibration (app.quality.eval.calibration.enabled), scheduled tasks, coding tasks (stub runner) and data tables in the kind values; document each flag in docs/configuration.md.
9. SE-76: check web/postgres limits against ~/vegaduta-values-kind-memory.yaml (web pod 4 restarts, postgres 2).

Rules that bite: no secrets in the repo (bash scripts/ci/secret-scan.sh); APP_ENCRYPTION_KEY identical across core shapes; no localhost in browser-facing config; new config in deploy/compose/.env.example and docs/configuration.md where relevant.

Acceptance: helm template with the values shows the allowed-private-hosts env on core, core-jobs and core-worker; shellcheck passes; the mock keeps default timing.

Verify: make shellcheck; bash scripts/ci/secret-scan.sh; helm template check. After the user upgrades the stage, start a job from /jobs and confirm it reaches SUCCEEDED, un-fixme the test in tests/e2e/tests-prod/stage-channels-devices-live.spec.ts, and run it. Say clearly what was only rendered/linted versus run on the stage.
```

## PROMPT TESTS-1: shared e2e helpers and existing specs

```
You are repairing the shared Playwright helpers and existing prod specs so they run against the VegaDuta kind stage (repo: /Users/mroads/Documents/GitHub/vegaduta-oss, tests/e2e/). Other agents work in the same checkout: edit only tests/e2e/helpers/*, tests/e2e/tests-prod/ existing (non stage-*) specs, and tests/e2e/playwright.stage.local.config.ts; do not edit stage-*-live.spec.ts (other batches own their fixme markers). Commit explicit paths only. Do not commit unrelated files; the untracked scratch dirs tests/e2e/wfb/ and tests/e2e/stage-public-auth-http/, tests/e2e/.e2e-prod-auth-state*.json and tests/e2e/stage-results.json are scratch: ask the user whether to delete them, add them to .gitignore, or leave them, but never commit them.

Context: about 80 existing tests across ~15 specs fail at tests/e2e/helpers/prod-session.ts:104 (waiting for a header BUTTON named Sign in). Working reference flows already exist: signIn() and the token capture in tests/e2e/tests-prod/stage-chat-switch-live.spec.ts, login() in stage-sharing-misc-live.spec.ts, and patched helper copies in tests/e2e/wfb/helpers/.

Fix:
1. SE-10 (high) in helpers/prod-session.ts: Sign in is now a link to /login followed by an email step ("Continue to secure sign-in") then the Keycloak form; accept link or button, or goto /login directly. helpers/prod-env.ts: derive the scheme from E2E_SCHEME (default https; stage = http) and add E2E_SITE_MODE=oss|classic. helpers/prod-api.ts getBearerToken: tokens are memory-only now (web/app/lib/auth.ts only deletes vd_kc_session); capture the Authorization header from the app's first /api request instead. helpers/prod-auth-state.ts: key the state file by run (E2E_STATE_DIR or pid) so parallel runs do not delete each other's refresh tokens; seed the USER-SCOPED tour keys (vd_tour_status:<sub>, vd_tour_seen_security:<sub>) and dismiss the welcome modal and 2FA card centrally. Set the --unsafely-treat-insecure-origin-as-secure launch arg once in playwright.stage.local.config.ts.
2. SE-41: gate the signup.spec disposable-email test (tests-prod/signup.spec.ts:125) behind a prod-only flag so it never runs on the stage; ask the user whether to check Keycloak/tenants for leftover e2e-prod-*@mailinator.com entries (do not delete anything without approval).
3. SE-42: with E2E_SITE_MODE=oss test.skip the ~28 commercial/classic specs (landing hero, #outcomes, #faq, atlas, pricing-billing, compare, hire, earn, why-vegaduta, billing, whatsapp calculator, galleries); the OSS header Sign in is a link.
4. SE-43: re-run mcp-server-registration-live and agent-tool-execution-live after the login fix; add e2e-tools-mcp- prefixed register/validate/delete/approval tests as far as the mock model allows (tool-call tests need DEPLOY-2's scriptable mock).
5. Stale selectors: agent-autonomy-approval-live clicks the card title hidden by an overlay button; use getByRole('button',{name:'filesystem'}). build-app-behavior-live panel switches are role=tab, not button. workflow-node-config-live reads node output from the DOM, which is no longer inline: read it from the run API or inspector.
6. SE-78: delete the workflow created in a finally/afterEach in the withWorkflow helper of workflow-node-config-live.spec.ts and prefix names with an e2e marker.
7. SE-79: add LCP capture (PerformanceObserver registered before navigation) to the perf helper, and run the new a11y spec in CI-style config once its fixmes are gone.

Rules: no secrets in the repo (never write the loadtest password or any token into a file; read it from env E2E_PASSWORD or ask the user); no localhost in browser-facing URLs; do not commit unrelated files.

Acceptance: from a clean checkout, with env E2E_DOMAIN=127.0.0.1.nip.io E2E_SCHEME=http, the previously failing existing specs get past login; remaining failures are real product or mock-LLM limitations and are listed.

Verify: cd tests/e2e && E2E_SCHEME=http npx playwright test -c playwright.stage.local.config.ts tests-prod/<spec> --project=chromium --workers=1 for each repaired spec; report the before/after pass counts and say which results are real product failures versus mock-model limits.
```
