# VegaDuta workflow DSL, version 1

A workflow can be exported as one JSON file and imported back (into the same or another workspace), and workflows can
be migrated in from n8n (JSON) and Dify (YAML DSL). The whole feature is behind
`app.workflow.portability.enabled` (env `WORKFLOW_PORTABILITY_ENABLED`, default `false`); with it off all four routes
answer `404`.

| Route | What it does |
|---|---|
| `GET  /api/workflows/{id}/export` | Downloads the workflow as `<name>.vegaduta-workflow.json` (VIEWER access). |
| `POST /api/workflows/import/preview` | Converts and validates a file. Creates nothing. |
| `POST /api/workflows/import` | Same, then creates a **DRAFT** workflow (`201`). Never activates, schedules, triggers or runs it. |

Both import routes take `{ "source": "auto|vegaduta|n8n|dify", "payload": <file text or JSON object>,
"overrides": { "name": "..." }, "mappings": { "<placeholder key>": "<uuid>" } }`. The file is data only: nothing is
fetched or executed. Size cap `app.workflow.portability.max-payload-bytes` (2 MiB, `413` above it). YAML is read with
SnakeYAML's `SafeConstructor` plus alias, depth and size limits, so `!!java` style tags and alias bombs are refused.

## File shape

```json
{
  "format": "vegaduta.workflow",
  "schemaVersion": 1,
  "name": "Order flow",
  "description": "",
  "config": {
    "maxStepsPerRun": 50, "stepTimeoutSeconds": 120, "maxRetryAttempts": 0,
    "reasoningDepth": "BALANCED", "tuningMode": "SIMPLE", "stepCachingEnabled": false
  },
  "nodes": [
    { "id": "a1", "type": "agent", "position": { "x": 200, "y": 0 },
      "data": { "label": "Researcher", "config": { "agentId": "@placeholder:agent_1" } } }
  ],
  "edges": [
    { "id": "e1", "source": "t1", "target": "a1", "sourceHandle": "true", "targetHandle": null }
  ],
  "placeholders": [
    { "key": "agent_1", "kind": "agent", "label": "Researcher", "usedBy": [ { "nodeId": "a1", "field": "agentId" } ] }
  ],
  "checksum": "sha256:..."
}
```

* `nodes` / `edges` are the canvas graph (React Flow shape) exactly as stored; `type` is a workflow node type.
* `config` carries the execution limits. On import `maxStepsPerRun` (1-200), `stepTimeoutSeconds` (1-3600) and
  `maxRetryAttempts` (0-5) are applied; the other keys are informational.
* **Deterministic.** There is no export timestamp and no tenant, user, workflow id, trigger or owner. Exporting the
  same workflow twice gives byte-identical output (two-space indent, `\n` line endings).
* **`checksum`** is the SHA-256 of the canonical form of the document (object keys sorted, compact) without the
  `checksum` field. A mismatch on import is a warning ("edited after export"), never an error.
* **Versioning.** `schemaVersion` is checked on import. A file with a higher version than the server understands is
  refused with a message naming both versions. New optional fields are added without a bump; a breaking change bumps it.

## What is removed or replaced on export

| Thing | In the file |
|---|---|
| Agent / tool / supervisor `agentId` | `@placeholder:agent_N` |
| Knowledge `collectionId` | `@placeholder:kb_N` |
| Workflow node `targetWorkflowId` | `@placeholder:workflow_N` |
| Bulk-email `groupId`, device-control `deviceId` | `@placeholder:group_N`, `@placeholder:device_N` |
| Delivery destinations (`emailTo`, `telegramChatId`, `teamsConversationId`, `whatsappTo`, `driveFolderId`, bulk-email `staticRecipients`/`replyTo`) | `@placeholder:destination_N` |
| Credential-looking values typed into a node (API key in a header, bearer token, key in a URL, JWT, ...) | `[[CREDENTIAL_n]]` (found by the same detector the AI editor uses) |
| Vault references `{{secret.NAME}}` | kept (they are references, not values) and listed as `secret:NAME` placeholders |
| Webhook tokens, schedules, channel bindings, owner, timestamps, runs, version history, shares | never read |

Placeholders are also found inside sub-flow bodies. The same foreign id used twice is one placeholder.

## Import: placeholders and the report

`preview` and `import` return the converted graph, the placeholders the file refers to, the orchestrator's own
structural validation issues, a fidelity summary and a conversion report (`converted`, `needsManual`, `unsupported`,
`warnings` - the same structure the agent importer returns).

* A **mappable** placeholder (agent, knowledge base, workflow, contact group, device) can be pointed at an existing
  resource with `mappings` (agents are checked to exist). Anything not mapped has its field **removed**, never left
  holding a foreign id or a token, and the node gets `needsReview` and an `importNotes` line.
* Credentials, vault secrets and destinations are listed, never imported.
* Validation uses a stand-in agent id for unmapped agent steps, so the issues listed are the real structural ones.

## n8n import (JSON)

Mapping tables were authored from the observed public file format; no n8n code is used. Each source node becomes
exactly one of **exact**, **approximate** (needs review), **placeholder** (inert, no equivalent) or **skipped**
(sticky notes).

| n8n | VegaDuta |
|---|---|
| Manual / Start trigger | `trigger` (exact) |
| Webhook | `trigger` with channel `WEBHOOK` (a new URL is created when you enable it) |
| Schedule / Cron / Interval | `trigger`; the schedule is **not** imported (needs manual) |
| Other `*Trigger` | plain `trigger` (needs manual) |
| HTTP Request | `http` (method, url, headers, query, json/form/raw body, timeout; auth and other options are reported) |
| IF (v1 and v2 condition shapes) | `condition` in expression mode; output 0 = `true`, 1 = `false` |
| Switch | placeholder (there is no Switch node); outputs kept as `output_N` handles |
| Code - JavaScript | `code` stub: passes its input through; the source is kept as text in `importedSource`; never executed |
| Code - Python | placeholder (source kept) |
| Set / Edit Fields | placeholder |
| Respond to Webhook | `output` (log) |
| Split In Batches | `loop`; the connection back into the loop is removed, the `done` output is reattached after the body |
| AI Agent / OpenAI / LLM chain | `agent` with the agent left to map; prompt kept as `importedPrompt` / `importedInstructions` |
| Execute Workflow | `workflow` with the target as a mappable placeholder |
| No-op | pass-through (exact); a disabled node becomes a pass-through |
| Anything else | placeholder |

* Connections become edges. Several targets on one output are kept but flagged (the engine follows the first
  non-error edge; use a Parallel node).
* AI components attached through non-`main` connections (models, tools, memory) are not steps; they are kept on the
  parent as `importedSubNodes`.
* Credentials: n8n exports only a credential's id and name. Only the name is kept (a `credential_N` placeholder);
  the id is never read. Inline credential-looking values are replaced by `[[CREDENTIAL_..]]` before anything is copied.
* `={{ ... }}` expressions have `$('Node').item.json` / `$node["Node"].json` / `$input.item.json` rewritten to
  `$node["Node"]` / `$json`; everything else is kept as written, linted with the engine's own expression linter, and
  the node is marked for review (n8n expressions are JavaScript).
* `pinData`, `settings`, `tags`, `active` are not imported (`active` workflows arrive as DRAFTs).

## Dify import (YAML DSL)

| Dify `data.type` | VegaDuta |
|---|---|
| `start` | `trigger` |
| `llm`, `parameter-extractor`, `agent` | `agent` (agent to map; prompt, model kept as text) |
| `knowledge-retrieval` | `knowledge` (dataset is a mappable placeholder; `top_k` kept) |
| `if-else` | one `condition` per case, chained (`false` feeds the next case) |
| `question-classifier` | `supervisor` with a route per class |
| `http-request` | `http` (headers, params, json/text/form body; the API key is never imported) |
| `template-transform` | `code` template when it only substitutes variables; Jinja logic -> placeholder |
| `answer`, `end` | a template `code` node followed by a log `output` |
| `iteration` | `sub_flow` over the body nodes (itemsField from `iterator_selector`) |
| `tool` | `tool` node (agent to map; tool name copied as written) |
| `code` | JavaScript stub / Python placeholder, as above |
| anything else (`variable-aggregator`, `assigner`, ...) | placeholder |

`{{#node.var#}}` references become `{{ $node["Title"].var }}`; `sys.query` becomes `$trigger`; a secret environment
variable becomes `{{secret.NAME}}` in HTTP fields. References with no equivalent (conversation variables, most
`sys.*`) are left as written and noted. Secret environment variables and API keys are never imported - only names.
A Dify chat/agent app (no workflow graph) is refused with an explanation.

## Limits

500 nodes and 2000 connections per file; YAML: 10 aliases, 64 levels, 200,000 expanded values.
