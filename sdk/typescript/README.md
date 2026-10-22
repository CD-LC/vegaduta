# vegaduta-sdk

TypeScript/JavaScript client for the [VegaDūta](https://vegaduta.ai) REST API. Generated
from the platform's OpenAPI spec, with a zero-dependency hand-written runtime: it uses the
platform `fetch` only, so it runs on Node 18+, Deno, Bun and browsers.

```sh
npm install vegaduta-sdk
```

## Quickstart

Create an agent, chat with it, then stream a reply:

```ts
import { VegadutaClient } from "vegaduta-sdk";

const client = new VegadutaClient({
  baseUrl: process.env.VEGADUTA_BASE_URL, // e.g. https://api.example.com
  token: process.env.VEGADUTA_TOKEN,      // Keycloak JWT or a vmcp_ grant key
});

// 1. create an agent
const agent = await client.agent.create({
  name: "Support bot",
  systemPrompt: "Answer billing questions briefly.",
  providerName: "PLATFORM_HOSTED",
});
const agentId = agent.id!; // generated response fields are optional

// 2. chat: the reply arrives as SSE chunks; join them for the full text
let reply = "";
for await (const chunk of client.agent.chat(agentId, { message: "How do I change my plan?" })) {
  reply += chunk;
}
console.log(reply);
```

## Authentication

`token` may be a string or a function returning one (`string | () => string | Promise<string>`),
resolved per request so it can refresh. On a `401` the client re-resolves the token once and
retries the call.

## Options

```ts
new VegadutaClient({
  baseUrl: "https://api.example.com",   // or VEGADUTA_BASE_URL (Node)
  token: myTokenSource,
  headers: { "X-Team": "billing" },     // sent on every request
  timeoutMs: 30_000,                    // per-request deadline (0 = none; streams are never bounded)
  maxRetries: 2,                        // idempotent GET/HEAD retries on 429/5xx/network errors
  userAgent: "my-app/1.2.3",            // appended to the SDK's User-Agent
  fetch: myFetch,                        // custom fetch (tests, proxies)
});
```

Per call: `{ query, headers, signal, timeoutMs }` as the last argument.

## Errors

- `ApiError` — every non-2xx. `status`, parsed `body` (`ErrorBody`: `status`, `error`, `code`),
  `headers` (request ids, rate limits), `retryAfterMs`.
- `NetworkError` — the request never got an answer (DNS, TLS, abort, deadline).
- `StreamInterruptedError` — the server aborted an SSE stream mid-answer.

Safe `GET`/`HEAD` calls retry automatically on `429`, `408`, `5xx` and network failures,
honouring `Retry-After` and backing off with jitter. Mutating methods are never retried.

## Shape of the client

One property per controller, one method per operation:
`client.agent.create(...)` is `AgentController#create`. Path parameters are positional, then the
request body, then the call options. Endpoints marked `x-sse` in the spec return an
`AsyncGenerator<string>` of chunks.

## License

Apache-2.0 — see [LICENSE](./LICENSE).
