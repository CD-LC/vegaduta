import { apiFetch, jsonBody } from "./core";

// MCP Apps (core McpAppsController, /api/mcp-apps). The appId is an opaque, short-lived handle core minted for
// one tool result (it binds tenant + user); the browser never names a ui:// URI or a server. Neither call goes
// through toJson: a failure here means "show the plain tool result instead", not a global error toast.
export interface McpAppView {
  appId: string;
  serverName: string;
  tool: string;
  uri: string;
  mimeType: string;
  html: string;
  csp: string;
  toolInput: string;
  toolResult: string;
  toolResultTruncated: boolean;
}

export interface McpAppToolOutcome {
  isError: boolean;
  text: string;
}

export const mcpAppsApi = {
  /** The app document for one tool result, or null when it is unavailable for any reason. */
  getMcpApp: async (appId: string): Promise<McpAppView | null> => {
    try {
      // skipGetCache: a one-shot, user-bound payload must not enter the shared GET cache or its persisted copy.
      const res = await apiFetch(`/api/mcp-apps/${encodeURIComponent(appId)}`, { skipGetCache: true });
      if (!res.ok) return null;
      const body = await res.json();
      return body && typeof body === "object" ? (body as McpAppView) : null;
    } catch {
      return null;
    }
  },
  /** A tool call the app asked for. Server-side it runs through the same approvals/policies/audit as a model call. */
  callMcpAppTool: async (appId: string, name: string, args: Record<string, unknown>): Promise<McpAppToolOutcome> => {
    try {
      const res = await apiFetch(`/api/mcp-apps/${encodeURIComponent(appId)}/tools/call`, {
        method: "POST",
        timeoutMs: 60000,
        ...jsonBody({ name, arguments: args }),
      });
      let body: { isError?: boolean; text?: string; message?: string } = {};
      try {
        body = await res.json();
      } catch {
        /* non-JSON body: fall through to the generic message */
      }
      if (typeof body.text === "string") return { isError: res.ok ? body.isError === true : true, text: body.text };
      return { isError: true, text: body.message || "The tool could not be run." };
    } catch {
      return { isError: true, text: "The tool could not be run." };
    }
  },
};
