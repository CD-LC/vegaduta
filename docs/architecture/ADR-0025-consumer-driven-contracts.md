# ADR-0025: Consumer-driven contracts between core and its clients

Status: Accepted. Provider verification, the OpenAPI freshness gate and the web consumer are implemented; the Java and
Python SDK consumers follow the same format.

## Context

Core's REST surface is about 1,300 operations. Three kinds of client depend on it: the web console (`web/`), the generated
REST SDKs (`sdk/typescript`, `sdk/python`) and the hand-written agent SDKs (`sdk/java`, `sdk/python-agent`). Until now the
only shared artefact was `docs/api/openapi.json`, generated from the controllers by `OpenApiSpecGenerator`. Two gaps let
drift through silently:

1. Nothing failed when the committed `docs/api/openapi.json` fell behind the controllers. The SDK drift check
   (`node sdk/generate.mjs --check`) compares the SDKs with that file, so a stale file made it pass. At the time of this ADR
   the committed spec was missing seven fields of `PlatformHostedLlmProvider` that two recent commits had added.
2. Nothing tied a consumer to the operations and fields it actually uses. A controller could rename a field the console
   reads, or drop an endpoint it calls, and every build stayed green until a person hit the screen.

## Decision

### Contract format

Each consumer owns a directory `contracts/consumers/<consumer>/` of `<area>.contract.json` files:

```json
{
  "consumer": "web",
  "provider": "core",
  "interactions": [
    {
      "id": "get-api-agents-agentId",
      "method": "GET",
      "path": "/api/agents/{agentId}",
      "status": 200,
      "request": { "requiredFields": ["$.name"] },
      "response": { "requiredFields": ["$.id", "$.name", "$.items[*].id"] }
    }
  ]
}
```

- `path` uses OpenAPI templating. Parameter names are not compared, only the template shape.
- `status` is the success status the consumer relies on; it must be declared on the operation.
- `requiredFields` name the fields the consumer reads (`response`) or sends (`request`), JSONPath-ish:
  `$.a.b`, `$.items[*].x`, `$[*].x` for a top-level array. List only what the consumer depends on, not the whole DTO.
- `consumer` must equal the directory name and `provider` must be `core`; `id` is unique within a consumer.
- Other files in the directory (for example `drift.json`, `uncontracted.json`) are ignored by the provider.

### Provider verification (core)

`core/app/src/test/java/com/agenticai/core/apidocs/ConsumerContractVerificationTest` generates the OpenAPI document from the
controllers on the test classpath, with the same generator and version that write `docs/api/openapi.json`, and checks every
interaction of every consumer:

- the operation (method plus path template) exists;
- the status is declared;
- each request field is in the request body schema and each response field is in the schema of that status.

Field resolution (`ContractSchemaFields`) follows `$ref`, `allOf`/`oneOf`/`anyOf` and array `items`. A free-form object (a
`Map`, `ResponseEntity<?>`, `JsonNode`: a schema without `properties`) accepts any name, because nothing more can be checked
against it; the test prints how many fields resolved only that way. A failure names the consumer, the contract file, the
interaction id, the operation and the missing operation, status or field. The fix is either in the controller (the provider
broke a consumer) or in the consumer's contract (it was wrong), never in the test.

It runs in `mvn verify`, so it is part of the core CI job, and CI also runs the core job when `contracts/consumers/**`
changes.

### Freshness of docs/api/openapi.json

`OpenApiSpecFreshnessTest` fails when the committed `docs/api/openapi.json` differs from a regeneration, names the first
differing line and prints the regeneration command. With it, the chain controllers, then `openapi.json`, then SDKs
(`node sdk/generate.mjs --check`) and consumer contracts is checked at every link.

### Web consumer

The console's contract is generated, not hand-written, by `web/scripts/api-contract.mjs`:

- `web/scripts/api-contract/extract.mjs` parses every non-test `.ts`/`.tsx` file under `web/app` with the TypeScript parser
  and records each call of `apiFetch`, `apiFetchOnce`, `fetchPaged`, `prefetchApiGets`, and of `fetch`/`fetchWithRetry` on
  `${API_BASE}`. It resolves template literals, file-level path constants (`const BASE = "/api/tenant/roi"`), one-line path
  builders (`const base = (id) => \`/api/workflows/${id}/versions\``) and literal conditionals
  (`${wf ? "workflows" : "agents"}`); it drops query strings and query-shaped suffixes (`${qs}`).
- `web/scripts/api-contract/generate.mjs` matches each call to an operation of `docs/api/openapi.json`, takes request fields
  from the keys of an inline object passed to `jsonBody(...)` or `JSON.stringify(...)`, and response fields from the
  non-optional top-level properties of `T` in `.then(toJson<T>)` (TypeScript type checker; one level, `$[*].x` for arrays).
- A field the console uses that the provider schema does not declare is drift. It is not written into the contract (the
  provider test would fail at once) but listed in `contracts/consumers/web/drift.json`, which should only shrink.
- Call sites it cannot read (a URL held in a variable or a helper's `path` parameter) are counted per file in
  `contracts/consumers/web/uncontracted.json`, with any call to a route the spec does not declare.

`app/lib/api/consumerContract.test.ts` (vitest, part of `npm test`) fails when a call site is missing from the contract,
when a contract entry has no call site left, or when the per-file count of unreadable call sites changes. The web CI job
also runs `node scripts/api-contract.mjs --check`, which fails when any generated file is out of date, including fields.
`make contracts` runs the web check, the vitest and the two core tests.

To regenerate after a console or controller change: regenerate `docs/api/openapi.json` if controllers changed (see
[docs/api/README.md](../api/README.md)), then `node scripts/api-contract.mjs --write` from `web/`, and commit
`contracts/consumers/web`.

## Coverage at the time of writing

- 964 interactions in 74 files; 3,593 response and 318 request fields; every one verified by the provider test.
- 32 call sites the extractor cannot read: generic helpers that take a path (`app/lib/api/core.ts`,
  `app/platform-admin/lib/apiCore.ts`, both `pagedFetch.ts`), the studio clients that append a sub-path
  (`studioClient.ts`, `demoClient.ts`), the resumable chat stream, and a few probes that try two mounts
  (`mcp.ts`). Their endpoints are often covered by another, literal call site; the rest are not contracted.
- 44 drift entries, all real mismatches between a TypeScript type and the provider DTO, for example: the console types
  `DELETE /api/agents/{agentId}/share` and `DELETE /api/workflows/{workflowId}/share` as returning a share status although
  the provider answers 204 with no body; `GET /api/research/runs` is typed as a list of the full `AgentJobResponse` but
  the provider returns `AgentJobSummaryResponse`; `POST /api/agents/{agentId}/versions/{versionId}/restore` is typed as
  `AgentVersionResponse` but the provider returns `AgentResponse`. They are listed in `drift.json` for follow-up.

## Known limits

- Response fields come from declared TypeScript types, not from the property accesses the console makes, so the contract
  states what the console is typed to receive. A field read through `any` is invisible; a field declared but never read is
  still listed. Only the top level of a response is recorded.
- A response consumed without `.then(toJson<T>)` (read later with `await res.json()`, or with an untyped `toJson`)
  contributes no response fields; its method, path and status are still contracted.
- Request fields are recorded only for inline object literals; a payload built elsewhere and passed as a variable is not.
- Free-form provider schemas (`ResponseEntity<?>`, maps) accept any field, as the generator cannot describe them (see the
  known limits in [docs/api/README.md](../api/README.md)).
- The provider test checks shape, not behaviour: it does not call the endpoints.

## Consequences

- A controller change that removes or renames an operation, a declared status or a field a consumer depends on fails the
  core build, with the consumer named.
- A console change that calls a new endpoint fails the web build until the contract is regenerated, which makes the new
  dependency visible in review.
- Changing a controller now also means regenerating `docs/api/openapi.json` (and the SDKs) in the same change.
