---
title: "MCP tool pinning: commit, test, and make `enforce` reachable"
labels: [security, owasp, high]
status: draft
owasp: MCP03, ASI04
---

# MCP tool pinning: commit, test, and make `enforce` reachable

OWASP: MCP03, ASI04. Source: [docs/security/owasp-mapping.md](../../docs/security/owasp-mapping.md). Rated High by the author's own judgement, not by a scoring system.

## Problem

Tool pinning (`McpToolPin`, `McpToolPinService`, `McpToolPinGate`, `McpToolPinController`, migration `V491`) was uncommitted in the working tree when the OWASP mapping was written. It hashes name, description and input schema as the model sees them; modes `off|warn|enforce`, default `warn`; the gate is called from `DynamicMcpConnectionManager`. Found gaps: no test for any pin class, the `app.mcp.tool-pinning.mode` key and docs were being added by a separate in-flight change (`docs/architecture/mcp-spec-alignment.md`) when this was written, so re-check before duplicating that part, annotations are not part of the pin, and statically configured MCP servers were not checked for the gate.

## Proposed fix

- Land the feature with tests: first sight pins, unchanged passes, description change -> `mcp.tools.changed` audit and (enforce) filtered out and fail-closed, admin approval re-pins, schema-only change detected.
- Add the config key to `application.yml`, `docs/configuration.md`, `.env.example`.
- Include annotations (`readOnlyHint`, `destructiveHint`) in the manifest.
- Route static servers through the same gate or document why not.
- Consider `enforce` as default for tenant-registered remote servers.

## Tests

As above, plus a Flyway lint pass for V491 (`bash scripts/ci/flyway-lint.sh`).

## Status

Implementation exists but is not part of this change; do not merge this issue's work with it blindly - coordinate with whoever owns that branch.
