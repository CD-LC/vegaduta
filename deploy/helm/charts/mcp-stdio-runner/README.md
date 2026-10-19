# mcp-stdio-runner

Runs third-party stdio MCP servers (npx/uvx packages) on behalf of core (`services/mcp-stdio-runner`,
relay 8097, health 8098). Optional (`global.components.mcpStdioRunner`); when on, core relays stdio MCP
connections here instead of spawning packages inside its own pod. Same isolation rules as tool-sandbox
(VM-isolating `runtimeClassName`, one secret `MCP_STDIO_SIDECAR_SHARED_SECRET`, no identity), with
deliberate internet egress on 80/443 so spawned servers can install packages; nothing in the cluster is reachable.

`browser: true` runs the `mcp-stdio-runner-browser` image instead (same service, same address in core;
built with `WITH_BROWSERS=true`): pinned Playwright Chromium, `@playwright/mcp` chrome-for-testing and
puppeteer Chrome for the `puppeteer` / `playwright-vision` MCP servers. A 1Gi memory limit becomes 2Gi.
