# VegaDuta SDKs

> **Looking for the Agent SDKs** — governance for agents inside your own application,
> not a client of this API? Those are [`java/`](java/README.md)
> (`io.vegaduta:vegaduta-agent-*`) and [`python-agent/`](python-agent/README.md)
> (`vegaduta-agent`). This page covers the generated REST clients for the platform API.

First-party TypeScript and Python clients for the VegaDuta REST API. Both are generated from
[`docs/api/openapi.json`](../docs/api/README.md) by one dependency-free Node script, and both have a
small hand-written runtime. Package names (`vegaduta-sdk`) and the version (`0.1.0`) are
placeholders until a registry name is reserved; neither package has any runtime dependency.

```
sdk/
  generate.mjs            spec -> SDK sources (node sdk/generate.mjs [--spec f] [--out dir] [--check])
  lib/                    model + TypeScript emitter + Python emitter + naming helpers
  test/                   node:test tests on a tiny fixture spec (fixture-openapi.json)
  typescript/             package.json, tsconfig.json, src/
    src/runtime.ts        hand-written: fetch client, ApiError, SSE decoder
    src/index.ts          hand-written re-exports
    src/generated/types.ts    GENERATED  (do not edit)
    src/generated/client.ts   GENERATED  (do not edit)
  python/                 pyproject.toml, vegaduta_sdk/, tests/
    vegaduta_sdk/runtime.py   hand-written: urllib client, ApiError, SSE decoder
    vegaduta_sdk/generated/models.py   GENERATED  (TypedDicts, do not edit)
    vegaduta_sdk/generated/client.py   GENERATED  (do not edit)
  python-agent/           pyproject.toml, vegaduta_agent/, tests/
    hand-written, NOT generated: the governance SDK (@governed, approval gate,
    effect journal, budgets, guardrails, audit) — the Python sibling of sdk/java
```

The generated files do not exist until the spec has been produced and `node sdk/generate.mjs` has
run (see [docs/api/README.md](../docs/api/README.md)). Until then `vegaduta_sdk.runtime` is
importable but `VegadutaClient` raises an ImportError that says so.

## Shape of the API

One property per controller (the tag), one method per operation:
`client.agent.create(...)` is `AgentController#create`. Path parameters are positional, then the
request body, then per-call options (`query`, `headers`, `signal` in TypeScript; keyword arguments
in Python). A non-2xx response raises `ApiError` with `status` and the parsed `body` (the shared
`ErrorBody`: `status`, `error`, optional `code`). Endpoints that stream (`x-sse` in the spec)
return an async iterator (TypeScript) or iterator (Python) of text chunks.

Authentication is a bearer token: a Keycloak access token (JWT) for the console API, or a long-lived
`vmcp_` grant key for `/api/dev/**` and `/v1/**` (issue one under `/api/mcpserver/grants`).

## Quickstart: TypeScript

Create an agent, chat with it, then stream a reply. `POST /api/agents` and
`POST /api/agents/{agentId}/chat` (a Server-Sent Events stream) are the real endpoints.

```ts
import { VegadutaClient } from "vegaduta-sdk";

const client = new VegadutaClient({ baseUrl: process.env.VEGADUTA_BASE_URL, token: process.env.VEGADUTA_TOKEN });

// 1. create an agent
const agent = await client.agent.create({ name: "Support bot", systemPrompt: "Answer billing questions briefly.", providerName: "PLATFORM_HOSTED" });
const agentId = agent.id!; // generated response fields are optional

// 2. chat with it: the reply arrives as chunks; join them for the full text
let reply = "";
for await (const chunk of client.agent.chat(agentId, { message: "How do I change my plan?" })) reply += chunk;
console.log(reply);

// 3. stream: print each chunk as it arrives, reusing a session for context
for await (const chunk of client.agent.chat(agentId, { message: "And invoices?", sessionId: "demo-session" })) process.stdout.write(chunk);
```

## Quickstart: Python (3.9+, standard library only)

```python
import os
from vegaduta_sdk import VegadutaClient

client = VegadutaClient(token=os.environ["VEGADUTA_TOKEN"], base_url=os.environ["VEGADUTA_BASE_URL"])

# 1. create an agent
agent = client.agent.create({"name": "Support bot", "systemPrompt": "Answer billing questions briefly.", "providerName": "PLATFORM_HOSTED"})

# 2. chat with it: the reply arrives as chunks; join them for the full text
print("".join(client.agent.chat(agent["id"], {"message": "How do I change my plan?"})))

# 3. stream: print each chunk as it arrives, reusing a session for context
for chunk in client.agent.chat(agent["id"], {"message": "And invoices?", "sessionId": "demo-session"}):
    print(chunk, end="", flush=True)
```

## Server-to-server with a grant key

`POST /api/dev/v1/agents/{agentId}/chat` (needs the `mcpserver` profile) returns the whole reply as
JSON, authenticated by a grant key that exposes that agent:

```ts
const dev = new VegadutaClient({ token: process.env.VEGADUTA_GRANT_KEY });
const { reply } = (await dev.developerApi.chat(agentId, { message: "Hello" })) as { reply: string };
```

```python
dev = VegadutaClient(token=os.environ["VEGADUTA_GRANT_KEY"])
print(dev.developer_api.chat(agent_id, {"message": "Hello"})["reply"])
```

That handler returns `ResponseEntity<?>`, so the generated return type is `unknown` / `Any`.
When `app.openai-compat.enabled` is on you can also point the official OpenAI SDKs at
`<api>/v1` with a grant key as the api_key (`/v1/chat/completions`, `/v1/models`, `/v1/responses`).

## Streaming details

The server writes each model chunk as one SSE event (`data:<chunk>`, no separator space; a chunk
with newlines becomes several `data:` lines). The runtimes strip exactly the five characters
`data:` and join the lines of an event with `\n`, so spaces and line breaks in the reply survive.
If a stream dies after it started, the server sends a final marker chunk; the runtimes turn it into
a `StreamInterruptedError` instead of yielding it as text. Stop iterating early to close the
connection.

## Regenerate and test

```
node sdk/generate.mjs                 # after docs/api/openapi.json changes
node sdk/generate.mjs --check         # CI: fail if generated files are stale
node --test sdk/test/*.test.mjs       # generator tests (+ TS runtime tests on Node 22.18+/24)
cd sdk/python && python -m unittest discover -s tests
cd sdk/typescript && npx tsc -p tsconfig.json --noEmit   # type-check the generated client (needs TypeScript)
```

The TypeScript and Python examples on this page are tests. `sdk/test/consumer-contract.test.mjs` and
`sdk/python/tests/test_contracts.py` run them against the real generated clients and fail when they
call an endpoint that is not in the consumer contracts under
`contracts/consumers/sdk-typescript/` and `contracts/consumers/sdk-python/`, or when an endpoint or
field there is missing from `docs/api/openapi.json`. Adding an example that calls a new endpoint
means adding that interaction to both contracts; core's provider verifier then checks it.

A contract states the status core really sends, which is not always what the spec says: the spec is
built by reflection and cannot see `ResponseEntity.status(...)`, so it lists 200 for `POST
/api/agents`, which answers 201. Such operations are recorded in
`contracts/consumers/tools/spec-status-drift.json`; the consumer check accepts the real status only
through an entry there and fails once the spec documents it, so the entry gets removed.

## Known limits

- Generated response types come from reflection. Record fields are optional unless they are
  primitives or validated `@NotNull`/`@NotBlank`, and a required field that has a `default` is
  treated as optional, so expect `!` / `.get` on response fields.
- Operations whose controller returns `ResponseEntity<?>` are typed `unknown` / `Any`.
- The exact 2xx status is not known from reflection; the SDKs accept any 2xx and treat an empty
  body as `undefined` / `None`.
- Multipart bodies take a `FormData` (TypeScript) or a dict of `str | bytes | (filename, bytes[, type])`
  (Python).
