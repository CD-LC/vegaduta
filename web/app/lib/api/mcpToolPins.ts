import { apiFetch, toJson } from "./core";

// Tool-definition pinning (core/mcp McpToolPinController, docs/architecture/mcp-spec-alignment.md).
// A remote MCP server can change a tool's description or schema after an admin reviewed it; the backend
// records a hash of the tool list and flags a difference. Approving accepts the new list as the baseline.

/** off = nothing recorded; warn = record and report, agents keep the server; enforce = a changed server's tools are withheld. */
export type McpToolPinMode = "off" | "warn" | "enforce";

export interface McpToolPinStatus {
  registrationId: string;
  name: string;
  mode: McpToolPinMode;
  pinned: boolean;
  changed: boolean;
  pinnedHash: string | null;
  observedHash: string | null;
  pinnedAt: string | null;
  pinnedBy: string | null;
  changedAt: string | null;
  added: string[];
  removed: string[];
  changedTools: string[];
}

/** How callers may authenticate to this deployment's /mcp endpoint (GET /api/mcpserver/grants/auth-options). */
export interface McpServerAuthOptions {
  grantKeys: boolean;
  oauth: {
    enabled: boolean;
    resource: string | null;
    metadataUrl: string | null;
    issuer: string | null;
    tenantClaim: string;
    grantClaim: string;
    scopes: string[];
  };
  origin: { mode: "enforce" | "report" | "off"; allowed: string[] };
}

export const mcpToolPinsApi = {
  listChangedMcpToolPins: (): Promise<McpToolPinStatus[]> => apiFetch(`/api/mcp/tool-pins`).then(toJson<McpToolPinStatus[]>),
  getMcpToolPin: (id: string): Promise<McpToolPinStatus> =>
    apiFetch(`/api/mcp/tool-pins/${encodeURIComponent(id)}`).then(toJson<McpToolPinStatus>),
  approveMcpToolPins: (id: string): Promise<McpToolPinStatus> =>
    apiFetch(`/api/mcp/tool-pins/${encodeURIComponent(id)}/approve`, { method: "POST" }).then(toJson<McpToolPinStatus>),
  getMcpServerAuthOptions: (): Promise<McpServerAuthOptions> =>
    apiFetch(`/api/mcpserver/grants/auth-options`).then(toJson<McpServerAuthOptions>),
};
