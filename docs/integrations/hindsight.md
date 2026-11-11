# Hindsight (agent memory)

[Hindsight](https://github.com/vectorize-io/hindsight) (MIT) is a self-hosted
agent memory server that *learns*: retained memories are organised into
world facts, experiences, consolidated observations and mental models,
grouped into per-topic **banks**. It complements the platform's built-in
pgvector/Apache AGE memory rather than replacing it — use Hindsight when you
want a dedicated memory service shared across agents or runs.

## Run it beside the platform

Hindsight ships as an opt-in Compose profile:

```bash
docker compose -f deploy/compose/docker-compose.yml --env-file deploy/compose/.env \
  --profile hindsight up -d hindsight
```

Set `HINDSIGHT_API_LLM_API_KEY` in `deploy/compose/.env` first — memory
consolidation needs an LLM. Any of 25+ providers works via
`HINDSIGHT_API_LLM_PROVIDER`/`HINDSIGHT_API_LLM_MODEL`, including any
OpenAI-compatible endpoint via `HINDSIGHT_API_LLM_BASE_URL`. Pairing it with
the [`freellmapi` profile](../local-models.md#a-free-provider-aggregator-freellmapi)
gives a fully key-free local stack:

```dotenv
HINDSIGHT_API_LLM_PROVIDER=openai
HINDSIGHT_API_LLM_BASE_URL=http://freellmapi:3001/v1
HINDSIGHT_API_LLM_API_KEY=unused
```

The API listens on `http://localhost:${HINDSIGHT_API_HOST_PORT:-8888}` and
the optional web UI on `http://localhost:${HINDSIGHT_UI_HOST_PORT:-9999}`
(both bound to loopback only). Persistent memory lives in the
`hindsight-data` volume (`/home/hindsight/.pg0`, an embedded Postgres).

## Register it as an MCP server

Every Hindsight server exposes a built-in streamable-HTTP MCP endpoint,
one per memory bank:

```
http://hindsight:8888/mcp/<bank_id>/
```

The `hindsight` service shares the `agent-io` network with core, so the
compose DNS name above works for tenant registrations out of the box; for
an external Hindsight use its own URL (subject to the outbound SSRF guard —
private hosts need `APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS`).

Register it in the console under **Tools → MCP servers** — the `hindsight`
catalog entry walks through it — or via the registry API:

```bash
curl -X POST "$CORE/api/mcp/registry" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
        "name": "hindsight",
        "type": "STREAMABLE_HTTP",
        "enabled": true,
        "url": "http://hindsight:8888/mcp/agent/"
      }'
```

Register **one server per bank**: the bank id is part of the URL path, so
each tenant/team/agent can get its own memory space (e.g.
`.../mcp/support/`, `.../mcp/research/`). Registered agents then get the
`retain`, `recall` and `reflect` tools. Local Hindsight has no
authentication of its own — keep it off public networks.

## Notes and limits

- Hindsight is a third-party image (`ghcr.io/vectorize-io/hindsight`), run
  as a separate process — it is not part of the VegaDūta images (see
  `NOTICE`).
- A `helm` chart exists upstream
  (`oci://ghcr.io/vectorize-io/charts/hindsight`) for Kubernetes
  deployments; only the Compose path is wired in this repo.
- Upstream docs: [hindsight.vectorize.io](https://hindsight.vectorize.io/)
  (MCP endpoint details under *Developer → MCP server*).
