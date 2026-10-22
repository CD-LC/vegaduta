# API contract (OpenAPI) and SDKs

`docs/api/openapi.json` is the machine-readable contract of the platform's REST API (OpenAPI 3.1).
It is **committed output**: nobody edits it by hand, it is regenerated from the Spring controllers
and reviewed as a diff. The two first-party SDKs in [`sdk/`](../../sdk/README.md) are generated from it.

```
core controllers  --OpenApiSpecGenerator-->  docs/api/openapi.json  --sdk/generate.mjs-->  sdk/typescript, sdk/python
```

## Regenerate

1. Write the spec. This runs the generator test in "write" mode (JDK 21 or newer, one Maven build at a time;
   `-am` is needed or `app` links against stale module jars):

   ```
   cd core
   mvn -o -pl app -am test -Dtest=OpenApiSpecGeneratorTest -Dopenapi.write=true -Dsurefire.failIfNoSpecifiedTests=false
   ```

   Without `-Dopenapi.write=true` the same test class only runs its checks and writes nothing. The
   test finds the repo root by walking up from the working directory (the first directory holding
   both `docs/` and `core/pom.xml`) and refuses to write anywhere but `docs/api/openapi.json`. The
   `info.version` is the `core/pom.xml` version without `-SNAPSHOT`.

2. Regenerate the SDK sources and the AsyncAPI document (plain Node, no dependencies):

   ```
   node sdk/generate.mjs
   node scripts/docs/asyncapi.mjs
   ```

   `node sdk/generate.mjs --check` exits 1 when the generated SDK files are out of date with the
   spec, which is handy as a CI drift guard.

3. Review and commit `docs/api/openapi.json` and the generated SDK files together.

## How the spec is produced

`core/app/src/main/java/com/agenticai/core/apidocs/OpenApiSpecGenerator.java` is a pure
reflection tool: it scans the classpath for `@RestController` classes under `com.agenticai.core`
with Spring's `ClassPathScanningCandidateComponentProvider`, reads the mapping annotations with
`AnnotatedElementUtils`, and walks DTO records and POJOs by reflection. It needs no application
context and no database, and it adds **no dependency** (springdoc is deliberately not used: the
offline Maven build has to keep working and that starter auto-exposes endpoints).

| Spec element | Source |
|---|---|
| paths, verbs | class-level `@RequestMapping` merged with method-level mapping annotations; regex in `{name:regex}` is dropped |
| `operationId` | `<ControllerSimpleName>_<method>`, suffixed `_2`, `_3` when it would repeat |
| `tags` | the controller name without the `Controller` suffix |
| `description` | derived notes (public route, grant-key auth, feature flag from `@ConditionalOnProperty`, SSE), led by the curated prose in `OpenApiDescriptions` for routes whose contract reflection cannot see (session ownership, service-level feature flags); keyed by `VERB /path`, and `OpenApiSpecGeneratorTest#curatedDescriptionsMatchRealOperations` fails when a key no longer matches a scanned operation |
| parameters | `@PathVariable`, `@RequestParam`, `@RequestHeader`, `@CookieValue` (required flag, type, default); every `{var}` in a path is always declared |
| request body | `@RequestBody` (JSON or the declared `consumes`), `@RequestPart` / `MultipartFile` (multipart/form-data) |
| schemas | records and POJOs by reflection into `components/schemas`; `@JsonProperty` / `@JsonIgnore`; Bean Validation (`@NotNull`, `@NotBlank`, `@Size`, `@Min`, `@Max`, `@Pattern`, ...); enums; cycles end in a `$ref` |
| success response | the return type, unwrapping `ResponseEntity`, `Optional`, futures, `Mono`. Status: `@ResponseStatus` when present; else the one constant 2xx status the handler body sets (`ResponseEntity.created/accepted/noContent`, `status(HttpStatus.X)`, `new ResponseEntity<>(body, HttpStatus.X)`, `response.setStatus(201)`), read from the controller bytecode by `OpenApiBodyStatusScanner`, following lambdas and same-class helpers; else 204 for an empty DELETE; else 200. A status taken from a variable, or two different 2xx statuses, keeps the default. A 204 response has no content |
| streaming | `Flux<ServerSentEvent>`, `SseEmitter` or `produces = text/event-stream` become `text/event-stream` and carry `x-sse: true` |
| errors | 400, 401, 403, 404, 429, 500 reference one shared `Error` schema matching `ErrorResponseFactory`'s body: RFC 9457 Problem Details (`type`, `title`, `status`, `detail`, `instance`) plus the legacy members (`timestamp`, `error`, `code`) and `requestId`/`traceId`, offered as `application/problem+json` and `application/json` (the latter when `app.errors.problem-details.enabled=false`); 429 (with `Retry-After`) only on the routes `PublicEndpointRateLimitFilter.throttles` names |
| headers | `components.headers`: `X-Request-Id` on the shared error responses; `X-Total-Count` and `X-Has-More` on list operations that take both `limit` and `offset`; `Retry-After` on 429 |
| security | `bearerAuth` (JWT) by default, with `tenantApiKey` (`Bearer vdak_...`, minted at `/api/tenant/api-keys`; the key's stored `PERM_*` scopes are its authority set) as an alternative on every authenticated `/api/**` route; `security: []` for the permitAll routes; `/api/dev/**` and `/mcp/**` use `apiKeyAuth` (a `vmcp_` grant key); `/v1/**` accepts a JWT or a grant key |

The permitAll list lives in `OpenApiSecurity.PUBLIC_RULES`, hand-mirrored from `SecurityConfig`.
`OpenApiSpecGeneratorTest#publicAllowlistMirrorsSecurityConfig` parses `SecurityConfig.java` and fails
when the two disagree, so a new `permitAll()` there has to be copied over.

Output is deterministic (sorted paths, verbs, components and skipped list; LF line endings; no
timestamps), so a regeneration with no controller change produces an empty diff.

## Paging convention

List endpoints that can grow without bound take optional `limit` and `offset` query parameters and answer
with two additive response headers; the JSON body keeps its original shape (a plain array, or the
object it always was).

| Piece | Meaning |
|---|---|
| `limit` | rows to return, clamped to `[1, max]`; the maximum differs per endpoint |
| `offset` | rows to skip, newest first (a negative value is 0; above 1,000,000,000 returns an empty page with 200) |
| `X-Total-Count` | the number of rows matching the request's filters before paging |
| `X-Has-More` | `true` when rows remain beyond this page |

Without `limit`, the four original list endpoints return the complete list, as before pagination
existed; an opt-in server switch (`LIST_DEFAULT_CAP`, see
[Configuration](../configuration.md#scale-and-reliability-limits)) caps them instead. The audit log,
platform users, notifications, quick-task history and AI reports have a bounded default instead. The
per-endpoint maximums and defaults, and the list endpoints that are still unpaged, are in
[List endpoint paging](LIST-ENDPOINT-PAGING.md), which is the source of truth: it is not repeated here.
Order is newest first with `id` as tiebreaker, so consecutive pages do not repeat or skip rows.

## API conventions

Cross-cutting behaviour every client can rely on. Each one is in the generated spec (and therefore the SDKs), read from
the same static rule the runtime filter applies, and pinned by `OpenApiConventionsTest`.

| Convention | What the server does | In the spec |
|---|---|---|
| Errors | RFC 9457 Problem Details (`application/problem+json`) with a stable `type`, plus the legacy members | `Error` schema, shared error responses |
| Correlation | `X-Request-Id` on every response (the caller's own when it sent a valid one) | `components.headers.X-Request-Id` |
| Authentication | Keycloak JWT, or a tenant API key `Bearer vdak_...` on `/api/**` | `bearerAuth`, `tenantApiKey` |
| Permissions | URL rules in `SecurityConfig` | `x-permissions` (`anyOf` / `allOf`) and `x-roles`, read from the security rule snapshot, plus a sentence in the description |
| Idempotent retries | `Idempotency-Key` on authenticated JSON `POST`/`PATCH` under `/api/**`: a repeat replays the first response (`Idempotent-Replayed: true`) for 24 h; 409 while the first is running; 422 for the same key with a different body. Workflow and evaluation runs keep their own older semantics. `IdempotencyKeyFilter` | `components.parameters.IdempotencyKey`, 409, 422 |
| Optimistic concurrency | `ETag` on `GET /api/agents/{id}` and `GET /api/workflows/{id}`; `If-Match` on their `PUT`/`PATCH`/`DELETE` and on single-segment settings `PATCH`es refuses a stale write with 412. `ConditionalRequestFilter` | `ETag` header, `components.parameters.IfMatch`, 412 |
| Offset paging | `limit` / `offset`, `X-Total-Count` / `X-Has-More` | headers on every list operation with both parameters |
| Keyset paging | `cursor` on the audit log and workflow run history: follow `X-Next-Cursor`; no count query, stable under inserts | `cursor` parameter, `X-Next-Cursor` header |
| Rate limits | Public routes: per IP/device, 429 + `Retry-After`. Authenticated API: opt-in (`app.rate-limit.api.enabled`) per-caller budget with IETF `RateLimit` / `RateLimit-Policy` headers | `429` on the throttled public routes; `RateLimit*` header components |
| Deprecation | `Deprecation`, `Sunset`, `Link` headers; 180-day minimum notice | `deprecated: true`, `x-sunset`, `x-replacement`. Policy: [DEPRECATION.md](DEPRECATION.md) |
| Webhooks | Signed (`X-VegaDuta-Signature: t=..,v1=<HMAC-SHA256>`) at-least-once POSTs of the `WorkflowEvent` envelope, retried with backoff | top-level `webhooks` object |
| Event streams | SSE streams, webhooks and the voice relay WebSocket | [`asyncapi.json`](asyncapi.json) (AsyncAPI 3.0), generated by `node scripts/docs/asyncapi.mjs` |

## Tenant ROI and pilot endpoints

`/api/tenant/roi/**` backs the per-agent cost and ROI view and the pilot report. The behaviour, the
permission each route needs and how the numbers are derived are in [Cost and ROI](../cost-and-roi.md)
and the [pilot kit](../pilots/README.md); the routes in `RoiController` are:

| Route | Purpose |
|---|---|
| `GET /api/tenant/roi/agents`, `GET .../agents.csv` | per-agent table (JSON or CSV) |
| `GET /api/tenant/roi/costs` | priced cost grouped by agent, workflow, channel, owner or day |
| `GET /api/tenant/roi/budget` | spend against the monthly cap |
| `GET`/`POST /api/tenant/roi/prices`, `DELETE .../prices/{id}` | model price versions |
| `GET`/`PUT .../agents/{agentId}/assumptions` | tenant-entered value assumptions |
| `GET`/`PUT .../agents/{agentId}/baseline` | tenant-entered pre-pilot baseline |
| `POST /api/tenant/roi/outcomes` | mark a conversation outcome |
| `GET .../agents/{agentId}/pilot-report?from&to&includeAssumed&format=html\|pdf\|json` | one-page pilot report (default `html`; any other `format` is a 400) |
| `GET /api/tenant/roi/demo` | whether the workspace is a seeded Example tenant |

## Size of the committed contract

Counted from `docs/api/openapi.json` (`info.version` 0.1.0) in this revision: 1,135 paths, 1,386
operations, 1,432 component schemas, and 5 handlers listed in `info.x-skipped`. These change with every
regeneration; recount rather than trust this paragraph. Standards conformance (MCP, A2A, AG-UI,
OpenTelemetry) is a separate, generated page: [Standards conformance](conformance.md).

## Known limits

- A handler that cannot be described is **skipped**, not fatal. Skips (no HTTP method on the mapping,
  ant wildcards such as `/**`, property placeholders in a path, classes that fail to load) are listed
  in `info.x-skipped`.
- The exact 2xx status is not knowable by reflection when a handler builds its own `ResponseEntity`.
  The spec says 200 (204 for an empty `DELETE`, or the `@ResponseStatus`), and the SDKs accept any 2xx.
- Handlers returning `ResponseEntity<?>` have an untyped (`{}`) success body.
- Required-ness is conservative: a field is `required` when it is a primitive or carries `@NotNull`,
  `@NotBlank` or `@NotEmpty`. A primitive also carries its Java default, and the SDKs treat a
  required field with a `default` as optional on input.
- Permission requirements finer than "authenticated" (for example `PERM_MANAGE_AGENTS`) are not in
  the spec; they live in `SecurityConfig`.
- Plan/limit failures (403 upgrade, 402 budget, 423 locked) share the generic `Error` body; their
  extra fields are allowed by `additionalProperties`.
- 429 is documented only for the public routes `PublicEndpointRateLimitFilter` throttles. Handlers that
  answer 429 themselves (turn concurrency, demo and share limits) and the opt-in authenticated API budget are not
  listed per operation.
- `x-permissions` comes from `core/app/src/test/resources/security/authorization-rule-snapshot.txt`, which
  `SecurityRuleSnapshotTest` keeps equal to the real filter chain. Permission checks made inside a handler (sharing
  levels, ownership) are not in it.

Why the platform stays REST + OpenAPI rather than adding GraphQL, and the API roadmap (breaking-change
gate, webhooks in the spec, `Idempotency-Key`, `ETag`/`If-Match`, rate-limit headers, deprecation
policy), are in [ADR-0026](../architecture/ADR-0026-api-styles-and-graphql.md).

## Status of the committed files

`docs/api/openapi.json` and the generated SDK sources (`sdk/typescript/src/generated/{types,client}.ts`,
`sdk/python/vegaduta_sdk/generated/{__init__,models,client}.py`) are produced by the steps above and have to be
generated on a machine with JDK 21+ and Node. They are intentionally not hand-written.

## Checks that keep the contract honest

| Check | Fails when | Runs in |
|---|---|---|
| `OpenApiSpecFreshnessTest` (core/app) | the committed `docs/api/openapi.json` differs from a regeneration; names the first differing line | `mvn verify` (core CI job) |
| `node sdk/generate.mjs --check` | the generated SDK sources differ from `docs/api/openapi.json` | `make gates` (static gates) |
| `node scripts/docs/asyncapi.mjs --check` | `docs/api/asyncapi.json` differs from a regeneration, or a documented workflow-run event name no longer exists in `core/workflow` | `make gates` (static gates) |
| `ConsumerContractVerificationTest` (core/app) | an interaction in `contracts/consumers/**` names an operation, status or field the generated spec does not have | `mvn verify` (core CI job) |
| `node scripts/api-contract.mjs --check` and `app/lib/api/consumerContract.test.ts` (web) | the console calls an endpoint missing from `contracts/consumers/web`, or the contract is stale | web CI job |
| `node scripts/ci/openapi-breaking.mjs` | `docs/api/openapi.json` changed incompatibly against the merge base with the base branch (below) | `make gates` (static gates) |

### Breaking-change gate

`scripts/ci/openapi-breaking.mjs` (plain Node, no dependencies) reads the spec at the merge base
(`git show <merge-base>:docs/api/openapi.json`) and compares it with the working tree. It fails on:

| Rule | Example |
|---|---|
| `path-removed`, `operation-removed` | a route or a verb is gone (renaming a path variable, `{id}` to `{agentId}`, is not a break) |
| `operation-id-changed` | `AgentController_reliability` became `AgentChatSessionController_reliability` after a controller split; the SDK method name derives from it |
| `parameter-required`, `request-body-required`, `request-property-required` | an input is new and required, or was optional; a property with a `default` (a Java primitive) is exempt |
| `parameter-removed`, `request-property-removed` | a query/header parameter or a request property is gone, so the server ignores what clients send (a schema with `additionalProperties: true` is exempt) |
| `security-tightened`, `access-tightened` | an operation that was public now needs credentials, stops accepting a security scheme, or now needs a role or permission (`x-roles`, `x-permissions`), or one that sufficed no longer does |
| `constraint-tightened` | an input gained or tightened `minLength`/`maxLength`, `minimum`/`maximum`, `minItems`/`maxItems`, `pattern` or `format` (`int32` to `int64` and `float` to `double` widen, so they pass) |
| `composition-narrowed` | an input's `oneOf`/`anyOf` lost an alternative |
| `request-media-type-removed` | the request body no longer accepts a content type it accepted |
| `response-body-removed`, `response-property-removed` | a 2xx body or one of its properties is gone (a 200 that became 201 is compared as the same response: the gate does not flag the code itself) |
| `response-required-relaxed`, `constraint-loosened`, `composition-widened` | a response property that was always present may now be missing; a response lost or loosened a bound or `pattern`, or changed `format`; a response `oneOf`/`anyOf` gained an alternative |
| `enum-narrowed` | an enum lost a value, or an input gained an enum it did not have |
| `type-changed` | a schema `type` changed; an input may widen (`string` to `string\|null`) and an output may narrow |

A shared component is reported once, at `schemas.<Name>.<property>`, not once per operation that uses it.
The base is the PR base in CI (`GITHUB_BASE_REF`), the push's previous commit on a push, and `main`
locally; `--base <ref>` or `OPENAPI_BREAKING_BASE_REF` override it, and `--old a.json --new b.json`
compares two files. When the break is deliberate, add the printed key to
`scripts/ci/openapi-breaking-allowlist.txt` with a `# reason` (an entry without one fails), say so in the
CHANGELOG, and remove the entry once it is merged. The rules are tested against a fixture pair in
`scripts/ci/testdata/openapi-breaking/` (`node --test scripts/ci/openapi-breaking.test.mjs`).

So a controller change is complete when the spec, the SDKs and (if the console uses the route) the web contract are
regenerated in the same change. The contract format and the extraction rules are in
[ADR-0025](../architecture/ADR-0025-consumer-driven-contracts.md); `make contracts` runs the contract checks locally.
