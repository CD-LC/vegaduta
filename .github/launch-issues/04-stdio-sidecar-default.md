---
title: "Run stdio MCP servers in the isolated sidecar by default"
labels: [security, owasp, high]
status: draft
owasp: ASI05, MCP05
---

# Run stdio MCP servers in the isolated sidecar by default

OWASP: ASI05, MCP05. Source: [docs/security/owasp-mapping.md](../../docs/security/owasp-mapping.md). Rated High by the author's own judgement, not by a scoring system.

## Problem

`mcp-stdio-sidecar.enabled` defaults to false (`MCP_STDIO_SIDECAR_ENABLED`). Without it, a registered stdio MCP server (`npx <package>`, `uvx ...`) runs inside the core container with whatever the process can reach; `McpStdioCommandGuard` blocks inline-eval flags and shell metacharacters but cannot vouch for the package. The repo's own design note and threat model state this as an open exposure. The sidecar (`services/mcp-stdio-runner`, seccomp profile, isolated network, `server.test.js`) exists and is tested.

## Proposed fix

- Make the sidecar the default in the Compose and Helm stacks and refuse to start a stdio server in-process unless `app.mcp.stdio.allow-in-process=true` is set explicitly.
- Document the single-user/dev exception in `docs/configuration.md`.
- Add a boot check (`scripts/ci/boot-check.sh`) asserting the sidecar is reachable when stdio servers are registered.

## Tests

Unit: in-process refusal unless flag set. Compose/Helm lint (`make compose-validate`, `make helm-lint`). Not verifiable without a stack: a registered stdio server actually executes in the sidecar network.

## Status

None yet.
