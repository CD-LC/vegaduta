// Pure helpers for the "Expose as MCP tool" switch (core/mcpserver McpToolExposureController,
// /api/mcpserver/exposure/{agents|workflows}/{id}). Kept free of React and of the api barrel so
// the response normalisation and the copy-paste connection snippets are unit-testable.
//
// Client formats checked against their docs while building this:
//  - Claude Desktop only launches local stdio servers from claude_desktop_config.json, so a remote
//    server goes through the mcp-remote bridge; its README documents `--header` plus an env var,
//    and the `Authorization:${AUTH_HEADER}` (no space) form for Windows
//    (https://github.com/geelen/mcp-remote).
//  - Cursor reads `{ "mcpServers": { name: { url, headers } } }` for a Streamable HTTP server
//    (https://cursor.com/docs/context/mcp).

export type McpExposure = {
  exposed: boolean;
  /** Allocated the first time the resource is exposed; null before that. */
  toolName: string | null;
  /** False when this deployment does not serve exposed tools at all. */
  featureEnabled: boolean;
  /** Path of the MCP endpoint on the API host, e.g. "/mcp". */
  mcpPath: string;
};

export type McpExposureKind = "agents" | "workflows";

/** Defensive parse of the API response; null when it is not the expected shape. */
export function normalizeMcpExposure(raw: unknown): McpExposure | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.exposed !== "boolean") return null;
  return {
    exposed: r.exposed,
    toolName: typeof r.toolName === "string" && r.toolName ? r.toolName : null,
    featureEnabled: r.featureEnabled === true,
    mcpPath: typeof r.mcpPath === "string" && r.mcpPath ? r.mcpPath : "/mcp",
  };
}

/** Joins the API base URL and the MCP path with exactly one slash between them. */
export function mcpEndpointUrl(apiBase: string, mcpPath: string): string {
  const base = (apiBase || "").replace(/\/+$/, "");
  const path = (mcpPath || "/mcp").startsWith("/") ? mcpPath || "/mcp" : `/${mcpPath}`;
  return `${base}${path}`;
}

const KEY_PLACEHOLDER = "<your vmcp_ API key>";

/** Cursor / any client that speaks Streamable HTTP with headers. */
export function buildCursorConfig(url: string): string {
  return JSON.stringify(
    {
      mcpServers: {
        vegaduta: { url, headers: { Authorization: `Bearer ${KEY_PLACEHOLDER}` } },
      },
    },
    null,
    2
  );
}

/** Claude Desktop, through the mcp-remote bridge (key kept in env, not on the command line). */
export function buildClaudeDesktopConfig(url: string): string {
  return JSON.stringify(
    {
      mcpServers: {
        vegaduta: {
          command: "npx",
          args: ["-y", "mcp-remote", url, "--header", "Authorization:${AUTH_HEADER}"],
          env: { AUTH_HEADER: `Bearer ${KEY_PLACEHOLDER}` },
        },
      },
    },
    null,
    2
  );
}

/** A raw JSON-RPC call, for testing the connection with curl. */
export function buildCurlExample(url: string): string {
  return [
    `curl -X POST ${url} \\`,
    `  -H "Authorization: Bearer ${KEY_PLACEHOLDER}" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  -H "Accept: application/json, text/event-stream" \\`,
    `  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'`,
  ].join("\n");
}
