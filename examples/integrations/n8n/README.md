# integrations/n8n

A plain-HTTP bridge between VegaDūta and [n8n](https://n8n.io), in both
directions. n8n is a fair-code product, so nothing here embeds its code —
the two platforms just call each other's endpoints. No plugin, no SDK, no
extra service.

Two files:

- [`vegaduta-to-n8n-webhook.json`](vegaduta-to-n8n-webhook.json) — a
  `WorkflowCreateRequest` body (same shape as
  [`../../workflow-templates/`](../../workflow-templates/README.md)) whose one
  `http` node POSTs the run's input to an n8n Webhook trigger.
- [`n8n-to-vegaduta-agent.json`](n8n-to-vegaduta-agent.json) — an n8n
  workflow export (nodes + connections) whose HTTP Request node calls a
  VegaDūta agent through the OpenAI-compatible surface.

## Direction A: VegaDūta workflow → n8n webhook

The workflow engine's **HTTP Request** node (palette name "HTTP Request",
wire type `http`) is the outbound half. Point it at the Webhook trigger
node's **production URL** in your n8n workflow —
`https://<your-n8n>/webhook/<path>`. The `/webhook-test/<path>` URL n8n
shows in the editor only answers while "Listen for test event" is running;
it is not the URL to put in a saved workflow.

The node config (from `vegaduta-to-n8n-webhook.json`):

| Key | Value |
|---|---|
| `method` | `POST` |
| `url` | `https://n8n.example.com/webhook/REPLACE_ME` — replace it |
| `headers` | `Content-Type: application/json` (add more as `{name, value}` rows) |
| `bodyType` / `body` | `json` / `{ "input": {{input_json}}, "source": "vegaduta" }` |
| `timeoutSeconds` | 15 (cap is 30) |
| `failOnHttpError` | `true` — a non-2xx marks the node SKIPPED (input passes through). Set `false`, optionally with `saveAs`, when a downstream `condition` should read the status/body itself |

`url`, header/query values and `body` all accept the canvas templating:
`{{input}}` (raw upstream output), `{{field}}` (a field of a JSON-object
input), and `{{input_json}}` (the input as a JSON string literal — the
safe way to embed free text inside a JSON body). With no `saveAs` set, the
node's output IS the response body, so the n8n side's answer flows
downstream.

**Two things the platform will catch you on:**

**The URL must be public.** The HTTP node delegates to the same SSRF
guard the `http_request` agent tool uses (`SsrfGuard` /
`WebPageFetcher#validatePublicHttpUri`): the host is resolved at connect
time, every resolved address must be a public one, and every redirect hop
is re-validated. Loopback, RFC-1918, link-local and IPv6 ULA are all
refused — so an n8n reachable only on a private address or internal DNS
name cannot be called from a workflow. Expose n8n on a public HTTPS URL
(its `WEBHOOK_URL` setting, or a reverse proxy in front of it). There is
no allowlist that relaxes this; host allow-lists only *narrow* which
public hosts are reachable.

**Vault secrets are destination-scoped.** If you authenticate the call
with a header value like `Bearer {{secret.N8N_HEADER}}`, the reference
only resolves when the secret's own allowed-hosts list includes your n8n
host — a secret with no allowed hosts never resolves in an HTTP call.
The plaintext is substituted inside the executor and redacted out of logs
and responses; never paste the credential itself into the node.

On the n8n side: add a **Webhook** trigger (method `POST`), then either a
**Respond to Webhook** node if the VegaDūta workflow should wait for an
answer, or let n8n's default immediate `200` acknowledgment close the
call while the n8n workflow runs on.

## Direction B: n8n → VegaDūta

Four real inbound surfaces, simplest first:

| Endpoint | Auth | Use when |
|---|---|---|
| `POST /webhook/workflows/{token}` | none — the token IS the credential | n8n should fire-and-forget a specific workflow's trigger node |
| `POST /webhook/workflows/oidc/{workflowId}` | `Authorization: Bearer <Keycloak JWT>` carrying the `workflow-trigger` realm role | you want the webhook gated on a real identity rather than a shared secret |
| `POST /api/workflows/{workflowId}/run` | Bearer — platform JWT **or** `vdak_` tenant API key | you want the run's result/trace in the HTTP response (synchronous; or `{"background": true}` + poll `GET /api/workflows/{id}/runs/{runId}`) |
| `POST /v1/chat/completions` | Bearer — platform JWT **or** `vmcp_` grant key | you want to call one *agent*, not a workflow — this is what `n8n-to-vegaduta-agent.json` uses |

Notes per surface:

- **Shared-secret webhook.** Enable it per trigger node with
  `POST /api/workflows/{workflowId}/nodes/{nodeId}/webhook` (tenant-admin);
  the response's `token` makes the callable URL
  `<base>/webhook/workflows/{token}`. The workflow must be **Active** and
  the trigger node's auth mode `SHARED_SECRET`. Body is a JSON object:
  an `input` field becomes the trigger's output verbatim; anything else is
  forwarded as the serialized body. Redelivery is deduped on
  `X-Delivery-Id` / `X-Webhook-Id` / `X-Request-Id` (falling back to a
  body hash) — send one of those headers if n8n retries. Optional
  sender-signature verification exists (GitHub/Stripe/Slack/HMAC-SHA256
  presets, `PUT .../webhook/signature`) if you can compute an HMAC in n8n.
- **`/api/workflows/{id}/run`.** Body `{"input": "...", "background":
  false}`; an `Idempotency-Key` header is honoured (retry with the same
  key → 409 instead of a second run). Needs RUNNER access on the
  workflow — the `vdak_` key must carry the matching `PERM_*` scope.
- **`/v1/chat/completions`.** Off by default — set
  `OPENAI_COMPAT_ENABLED=true` on core. `model` is an agent id or exact
  agent name (`GET /v1/models` lists them). A `vmcp_` grant key is the
  easy credential to give n8n: it's long-lived, scoped to the grant's
  exposed agents, rate-limited and budget-capped server-side. n8n's
  OpenAI-family nodes can also point their Base URL at
  `<VEGADUTA_BASE_URL>/v1` with that key and drive an agent without a raw
  HTTP Request node.
- **A2A** (`POST /api/agents/{agentId}/a2a`, JSON-RPC `message/send`;
  cards at `/.well-known/agent-card.json` and
  `/api/agents/{agentId}/a2a/agent-card.json`) works over the same Bearer
  auth if you prefer the protocol shape — useful when the n8n flow will
  poll `tasks/get` rather than hold a long request.

**When to use which direction.** VegaDūta → n8n when the governed thing
(agent turn, approval, multi-step workflow) happens here and n8n owns the
last mile into a SaaS catalog it already knows (sheets, CRMs, mail,
Slack). n8n → VegaDūta when an event originates in n8n's world (a
schedule, a SaaS trigger node) and the work that follows needs an agent
or a governed workflow run here. If you are moving a whole n8n workflow
into VegaDūta rather than calling across, that's the importer, not the
bridge — `POST /api/workflows/import` with `source: "n8n"` behind
`WORKFLOW_PORTABILITY_ENABLED` (see
[`docs/api/workflow-dsl-v1.md`](../../../docs/api/workflow-dsl-v1.md)).

## Setup checklist

1. Create the n8n credential: **Header Auth** → Name `Authorization`,
   Value `Bearer <platform JWT or vmcp_ key>`.
2. Import `n8n-to-vegaduta-agent.json` into n8n (Workflows → Import),
   attach the credential to the "Call VegaDuta agent" node, set
   `VEGADUTA_BASE_URL` (e.g. `https://core.example.com` — no trailing
   slash) and `VEGADUTA_AGENT` in n8n's environment or replace the
   placeholders in the node.
3. Create the VegaDūta workflow from `vegaduta-to-n8n-webhook.json`:

   ```bash
   curl -X POST "$CORE_BASE_URL/api/workflows" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d @vegaduta-to-n8n-webhook.json
   ```

   Replace `REPLACE_ME` first; the URL failing SSRF validation is the
   most common reason this node's run step comes back SKIPPED.
