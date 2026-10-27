# n8n

Bridge VegaDūta and [n8n](https://n8n.io) in both directions over plain
HTTP — no plugin on either side (n8n is fair-code, so the bridge is HTTP
only, never embedded code). The importable files live in
[`examples/integrations/n8n/`](../../examples/integrations/n8n/README.md).

## VegaDūta → n8n

The workflow canvas's **HTTP Request** node (`http`) POSTs to the
production URL of an n8n Webhook trigger:
`https://<your-n8n>/webhook/<path>`. Method, headers and body are
configured on the node; `{{input}}`, `{{field}}` and `{{input_json}}`
template the upstream node's output into the request; `failOnHttpError`
(default on) marks a non-2xx as SKIPPED — turn it off (and use `saveAs`)
when a downstream `condition` should inspect the status or body itself.

[`vegaduta-to-n8n-webhook.json`](../../examples/integrations/n8n/vegaduta-to-n8n-webhook.json)
is a minimal two-node example (`trigger → http → output`) you can POST to
`/api/workflows`.

!!! warning "The URL must resolve to a public address"
    The node's URL is checked by the platform's SSRF guard
    (`SsrfGuard` via `WebPageFetcher#validatePublicHttpUri`): http/https
    only, every resolved address must be public (loopback, RFC-1918,
    link-local and IPv6 ULA are refused), and every redirect hop is
    re-validated. A self-hosted n8n that only answers on a private
    address or internal DNS name cannot be reached — expose it through a
    public HTTPS URL or a reverse proxy. No setting relaxes this;
    `TOOL_HTTP_REQUEST_ALLOWED_HOSTS`-style allow-lists can only narrow
    which public hosts are reachable. If a header value carries a
    `{{secret.NAME}}` vault reference, that secret's own allowed-hosts
    list must cover the n8n host or it never resolves.

## n8n → VegaDūta

An n8n **HTTP Request** node (or its OpenAI nodes) can reach any of these
surfaces:

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /webhook/workflows/{token}` | Token in the URL — no header | Fire a workflow's trigger node; enable per node via `POST /api/workflows/{workflowId}/nodes/{nodeId}/webhook` (tenant-admin). Body `{"input": "..."}`; the workflow must be Active. |
| `POST /webhook/workflows/oidc/{workflowId}` | `Bearer` Keycloak JWT with the `workflow-trigger` realm role | Identity-gated variant of the same trigger. |
| `POST /api/workflows/{workflowId}/run` | `Bearer` platform JWT or `vdak_` tenant API key | Synchronous run returning the node trace; `{"background": true}` + `GET /api/workflows/{id}/runs/{runId}` for fire-and-poll. `Idempotency-Key` header is honoured. |
| `POST /v1/chat/completions` | `Bearer` platform JWT or `vmcp_` grant key | Call one agent, OpenAI-compatible; `model` is an agent id or name (`GET /v1/models`). Requires `OPENAI_COMPAT_ENABLED=true`. |
| `POST /api/agents/{agentId}/a2a` (or `/api/a2a`) | `Bearer` platform JWT | A2A JSON-RPC `message/send` / `tasks/get`; cards at `/.well-known/agent-card.json` and `/api/agents/{agentId}/a2a/agent-card.json`. |

[`n8n-to-vegaduta-agent.json`](../../examples/integrations/n8n/n8n-to-vegaduta-agent.json)
is an importable n8n workflow (Manual trigger → HTTP Request) that calls
`/v1/chat/completions`; it reads `{{ $env.VEGADUTA_BASE_URL }}` and
`{{ $env.VEGADUTA_AGENT }}` and expects a Header Auth credential holding
`Authorization: Bearer <token>`.

!!! tip "n8n's OpenAI nodes work too"
    Because `/v1` is OpenAI-compatible, an n8n OpenAI credential with its
    Base URL overridden to `https://<your-core>/v1` and a `vmcp_` grant
    key as the API key drives a VegaDūta agent without a raw HTTP node —
    the grant confines the caller to its exposed agents, with rate limits
    and budget caps enforced server-side.

## Moving workflows instead of bridging

If the goal is to *migrate* an n8n workflow rather than call across, use
the portability importer — `POST /api/workflows/import` with
`source: "n8n"`, behind `WORKFLOW_PORTABILITY_ENABLED`. Node-by-node
mapping and limits: [workflow DSL v1](../api/workflow-dsl-v1.md).

The fuller walkthrough, including n8n-side webhook configuration and the
setup checklist, is in
[`examples/integrations/n8n/README.md`](../../examples/integrations/n8n/README.md).
