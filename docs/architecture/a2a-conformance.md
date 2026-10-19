# A2A conformance

Where the A2A implementation in `core/agents-interop/.../a2a` stands against the Agent2Agent
specification, requirement by requirement. The machine-checked capability rows (are the
files and symbols there) live in [docs/api/conformance.md](../api/conformance.md), generated
by `scripts/ci/conformance-report.py`; this page is the judgement layer on top: what the spec
requires, what we do, and where we deviate.

- **Spec read:** A2A **1.0.0** (latest release; earlier: 0.3.0, 0.2.6, 0.1.0), the HTML
  specification at <https://a2a-protocol.org/latest/specification/>, on 2026-10-14. Version
  identifiers are `Major.Minor` (spec 3.6).
- **Bindings the spec defines:** JSON-RPC (section 9), gRPC (section 10), HTTP+JSON/REST
  (section 11), plus custom bindings (section 12). Section 5.1: every supported binding MUST
  offer identical functionality.
- **Task states (4.1.3):** `SUBMITTED`, `WORKING`, `COMPLETED`, `FAILED`, `CANCELED`,
  `INPUT_REQUIRED`, `REJECTED`, `AUTH_REQUIRED` (plus `UNSPECIFIED`). Terminal: completed,
  failed, canceled, rejected. Interrupted: input-required, auth-required.

How this was verified, and its limits, are in [What was not verified](#what-was-not-verified).

## Summary

| Area | Status |
|---|---|
| Agent Card (discovery, required fields, interfaces) | Met |
| Card signing (JWS) | **Partial** - works, deviates from 8.4 in three ways |
| JSON-RPC binding, 4 of 11 operations | Partial (by design, advertised honestly) |
| HTTP+JSON/REST binding, 4 of 11 operations | Partial (new, same four operations) |
| gRPC binding | **Not implemented** - decision needed, see [gRPC proposal](#grpc-proposal-needs-your-decision) |
| Task state machine | Partial - 5 of 8 states |
| Content parts | Partial - text only |
| Errors and versioning | Met for JSON-RPC; REST has one documented deviation |

## Requirement table

Status key: **Met** = implemented and covered by a test; **Partial** = implemented with a
stated gap; **Missing** = not implemented; **n/a** = optional and not offered.

### Agent Card

| Spec | Requirement | Status | Evidence / note |
|---|---|---|---|
| 8.1 | Servers MUST make an Agent Card available | Met | `A2aAgentCardController` (`/.well-known/agent-card.json`, legacy `/.well-known/agent.json`); `A2aAgentCardServiceTest` |
| 8.2 | Well-known URI `/.well-known/agent-card.json` | Met | same |
| 4.4.1 | Required fields: `name`, `description`, `supportedInterfaces`, `version`, `capabilities`, `defaultInputModes`, `defaultOutputModes`, `skills` | Met | `A2aAgentCardService.card`; the platform card sends `skills: []` on purpose (unauthenticated endpoint, see class javadoc). Whether an empty `skills` satisfies "required" is not stated in the part of the spec I read |
| 8.3.1 / 4.4.6 | Each `AgentInterface` has `url`, `protocolBinding`, `protocolVersion`; first entry is preferred | Met | JSON-RPC first, `HTTP+JSON` second; `A2aAgentCardServiceTest` |
| 8.3.1 | `tenant` on an interface (optional) | n/a | not used |
| 8.4 | Cards MAY be signed with JWS (RFC 7515) | Met | `A2aCardSigningService` (EdDSA/Ed25519, `kid`, JWKS at `/.well-known/jwks.json`); `A2aCardSigningServiceTest`, `A2aCardVerifierTest` |
| 8.4.1 | Canonicalise with JCS (RFC 8785), exclude `signatures`, **drop default-valued fields** | **Partial** | We sort map keys and write compact JSON; that equals JCS for the types the card holds (strings, booleans, maps, lists, no floats), but we do not drop default-valued fields (`pushNotifications:false` etc. stay in the signed bytes). A third-party verifier that follows 8.4.1 literally may compute a different payload. Not tested against an external verifier |
| 8.4 | Protected header: `alg`, `typ` (SHOULD be `"JOSE"`), `kid` | **Partial** | We send `typ: "JWS"`. Changing it changes every signature, so it is left until a verifier interop test exists |
| 8.6 | Servers SHOULD send `Cache-Control` and `ETag` | **Partial** | `Cache-Control` yes (5 min public on the platform card, `no-store` on per-agent cards); no `ETag` |
| 4.4.x | 0.3 compatibility fields (`url`, `preferredTransport`) | Met (extra) | both generations' fields are emitted, non-conflicting |

### Operations

The cards advertise `streaming: true`, `pushNotifications: false`, `extendedAgentCard: false`.

| Spec | Operation | JSON-RPC | REST | Note |
|---|---|---|---|---|
| 3.1.1 | SendMessage | Met | Met | blocking by default; `returnImmediately`/`blocking:false` honoured |
| 3.1.2 | SendStreamingMessage | Met | Met | SSE, frame order pinned by tests |
| 3.1.3 | GetTask | Met | Met | `historyLength` honoured |
| 3.1.4 | ListTasks | Missing | Missing | answered with `UnsupportedOperationError` (-32004 / HTTP 400). Needs a tenant-scoped listing on `A2aTaskStore`, including its Redis mode |
| 3.1.5 | CancelTask | Met | Met | `TaskNotCancelableError` on a terminal task |
| 3.1.6 | SubscribeToTask | Missing | Missing | `UnsupportedOperationError`. The spec requires that error when `capabilities.streaming` is false; ours is true, so this is a gap, not a conforming refusal |
| 3.1.7-3.1.10 | Push-notification config (create/get/list/delete) | Missing | Missing | `PushNotificationNotSupportedError`; matches `pushNotifications:false` |
| 3.1.11 | GetExtendedAgentCard | Missing | Missing | `UnsupportedOperationError`, which section 13 prescribes when `extendedAgentCard` is false |
| 5.1 | Identical functionality across bindings | Met | Met | both bindings expose the same four operations and share the task service, RUNNER check and SSE writer |

### Data model

| Spec | Requirement | Status | Note |
|---|---|---|---|
| 4.1.3 | Task states | **Partial** | emitted: submitted, working, completed, failed, canceled. Not emitted: input-required, auth-required, rejected. An agent turn here never pauses for the caller; a follow-up on the same `contextId` continues the conversation (`A2aTaskState` javadoc). A message carrying `taskId` is refused with invalid-params |
| 4.1.6 | Part: exactly one of `text`, `raw`, `url`, `data` | **Partial** | text in and out only; `raw`/`url`/`data` parts are refused with `ContentTypeNotSupportedError` (-32005 / HTTP 400) rather than dropped (`A2aWire.extractText`) |
| 4.1.7 | Artifact: `artifactId`, at least one part | Met | one `response` artifact per task |
| 5.5 | camelCase JSON; ProtoJSON enum names (`TASK_STATE_*`, `ROLE_*`) | Met (1.0 dialect) | 0.3 dialect is separate and selected by header |
| 5.6.1 | Timestamps ISO 8601 UTC with `Z`, ms precision SHOULD | **Partial** | `Instant.toString()`: `Z` always; precision is whatever the clock gives, not clamped to milliseconds |
| A.2.1 | 1.0 servers MUST NOT emit `kind` | Met | `A2aWire` adds `kind` only in the 0.3 dialect; `A2aJsonRpcControllerTest` |
| 5.7 | Ignore unrecognised fields | Met | tree-based parsing |

### Errors and versioning

| Spec | Requirement | Status | Note |
|---|---|---|---|
| 5.4 / 9.5 | JSON-RPC codes: -32001 TaskNotFound, -32002 TaskNotCancelable, -32003 PushNotificationNotSupported, -32004 UnsupportedOperation, -32005 ContentTypeNotSupported, -32006 InvalidAgentResponse, -32007 ExtendedAgentCardNotConfigured, -32008 ExtensionSupportRequired, -32009 VersionNotSupported | **Partial** | produced: -32001, -32002, -32003, -32004, -32005, -32009. Not produced: -32006 (we never validate a remote reply on the server side), -32007 (no extended card), -32008 (no extension is declared required) |
| 5.4 / 11.6 | REST: HTTP status per error (404 TaskNotFound; 400 for the FAILED_PRECONDITION and INVALID_ARGUMENT family; 500 InvalidAgentResponse) | Met | `A2aRestController.mapping`; `A2aRestControllerTest` |
| 11.6 | REST error body is `google.rpc.Status` JSON with a `google.rpc.ErrorInfo` detail: `reason` UPPER_SNAKE_CASE, `domain: a2a-protocol.org` | Met | same |
| 9.1 | JSON-RPC failures travel in the envelope | Met | HTTP 200 with `error`, as the JSON-RPC binding is written here |
| 3.2.6 / 3.6.2 | `A2A-Version`; unsupported version -> `VersionNotSupportedError`; empty value means 0.3 | Met on JSON-RPC | absent header = 0.3 dialect |
| 3.6 | same, on REST | **Deviation** | REST is 1.0-only (0.3 REST used different paths). An absent header is accepted as 1.0 rather than rejected or treated as 0.3. Any 0.x value gets `VersionNotSupportedError`. Documented in `A2aRestController` |
| 3.2.6 | `A2A-Extensions` header | n/a | ignored; no extension is declared |
| 13 | Every operation enforces the caller's authorization, before queries that could leak existence | Met | RUNNER check on the target agent, tenant+subject on task lookup; unknown and not-yours are indistinguishable (404 / "unknown agentId"); `A2aTaskServiceTenantTest` |
| 11.4 | Media type `application/a2a+json` SHOULD be used | Met | responses use it; requests accept it and `application/json` |
| 11.7 | SSE: first a Task (or Message), then status/artifact updates, stream closes after terminal state | Met | same writer as JSON-RPC, without the envelope; `A2aRestControllerTest` |

## REST binding

Added in this change. `A2aRestController` serves, for both the platform base
`/api/a2a/rest` and the agent-scoped base `/api/agents/{agentId}/a2a/rest`:

| Method and path | Operation |
|---|---|
| `POST /message:send` | SendMessage |
| `POST /message:stream` | SendStreamingMessage (SSE) |
| `GET /tasks/{id}` | GetTask |
| `POST /tasks/{id}:cancel` | CancelTask |
| `GET /tasks`, `POST /tasks/{id}:subscribe`, push-config routes, `GET /extendedAgentCard` | answered with the spec's unsupported-operation errors |

Both cards list `{url: <endpoint>/rest, protocolBinding: "HTTP+JSON", protocolVersion: "1.0"}`
after the JSON-RPC entry, so JSON-RPC stays the preferred interface and 0.3 clients, which
read `url`/`preferredTransport`, see no change. Auth is the same bearer JWT through the main
Spring Security chain; no new `SecurityConfig` rule was needed.

Signed cards keep working: the card is built first and signed last, so the added interface is
covered by the signature. `A2aCardSigningServiceTest` and `A2aCardVerifierTest` pass unchanged.

## gRPC proposal (needs your decision)

Not implemented. The spec defines it (section 10) with the same operations as the other
bindings plus `tenant` routing. What it would take, and why I would hold off:

- **Work:** generate the service from the spec's `a2a.proto`; add grpc-java and protobuf
  runtimes (new dependencies: licence review against `scripts/ci/license-allowlist.txt`, SBOM,
  CVE surface); a second listener (gRPC needs HTTP/2 end to end, so a new port or an h2c
  connector plus a proxy rule in Compose, Helm and Caddy); a server interceptor that validates
  the Keycloak JWT and binds `TenantContext` (a ThreadLocal, AGENTS.md rule 12) on the call
  thread; server-streaming for `SendStreamingMessage`; the 5.4 status mapping; tests.
- **Rough size:** a week of work plus a deploy-surface change. The chat hot path would not
  gain a hop (rule 6): the gRPC service would call `A2aTaskService` in process.
- **Value:** only clients that cannot use JSON-RPC or REST. The popular SDKs speak JSON-RPC and
  REST; I have not checked which clients in your target set need gRPC.
- **Recommendation:** defer until a concrete integration asks for it. If you want it, say so and
  I will write the design (ADR) first rather than the code.

## Known gaps worth scheduling

1. `ListTasks` and `SubscribeToTask` (the card says `streaming: true`, so the second one is a
   real gap).
2. Align card signing with 8.4.1/8.4 (drop default-valued fields, `typ: JOSE`) together with an
   interop test against an independent verifier, then re-sign.
3. `ETag` on the cards.
4. `INPUT_REQUIRED` / `AUTH_REQUIRED` if approvals should surface as A2A task states instead of
   a tool-level refusal.

## What was not verified

- The specification page is about 256,000 characters. It was read through a summarising fetch
  in three windows, not verbatim, and the very end (after Section 14 and Appendix A/B) was seen
  only as an outline. Sections 3.1.4 (ListTasks) and 3.1.6 (SubscribeToTask) and the whole of
  Section 10 (gRPC) were **not** read in detail; the statements about them above come from the
  section lists and the error table, not from their text.
- The "required" status of fields I cite comes from 4.4.1 as summarised. Re-check against the
  spec before claiming certification.
- No external A2A client or the official TCK was run against this server. "Met" means "covered
  by this repository's unit tests", not "interoperates with other implementations".
- REST routing is tested through Spring MVC standalone (`MockMvc`), not on a running stack.
