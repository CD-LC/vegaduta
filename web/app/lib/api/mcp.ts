import { apiFetch, toJson, jsonBody } from "./core";
import type { McpServerResponse } from "./types";
import type { ApiError } from "./core";

/** POST .../test result - or { unsupported: true } on a core that predates it. */
export interface McpServerTestResult {
  unsupported?: boolean;
  ok?: boolean;
  phase?: string | null;
  detail?: string | null;
  hint?: string | null;
  toolCount?: number | null;
  [key: string]: unknown;
}

export const mcpApi = {
  // MCP registry (Stage 4)
  // Curated "add without typing" gallery - a fixed, hand-maintained list
  // (see McpCatalogService.java's javadoc), separate from listMcpServers
  // below (this tenant's own registrations).
  listMcpCatalog: () => apiFetch(`/api/mcp/registry/catalog`).then(toJson<unknown[]>),
  listMcpServers: () => apiFetch(`/api/mcp/registry`).then(toJson<McpServerResponse[]>),
  registerMcpServer: (payload: object) =>
    apiFetch(`/api/mcp/registry`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson<McpServerResponse & { reviewStatus?: string | null; warnings?: string[] | null }>),
  toggleMcpServer: (id: string, enabled: boolean) =>
    apiFetch(`/api/mcp/registry/${id}`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    }).then(toJson),
  // #424 - explicit "try connecting again" for a dynamic registration (e.g.
  // after fixing a bad API key) without re-submitting the whole form. No-op
  // (returns the registration unchanged) for static/non-dynamic ones.
  connectMcpServer: (id: string) => apiFetch(`/api/mcp/registry/${id}/connect`, { method: "POST" }).then(toJson),
  // #edit-delete-gap - there was previously no way to remove a registration
  // at all, only disable it forever. 204 No Content on success (toJson
  // already returns null for that status).
  deleteMcpServer: (id: string) => apiFetch(`/api/mcp/registry/${id}`, { method: "DELETE" }).then(toJson),
  // MCP enable/diagnose UX - phased connection test. Contract (core, being
  // built in parallel): POST .../{id}/test -> { ok, phase: "SPAWN"|"HTTP"|
  // "HANDSHAKE"|"INIT_TIMEOUT"|"OK", detail, hint, toolCount }. Deliberately
  // NOT routed through toJson: an older core that predates the endpoint
  // returns a 404, which is "feature not available" (the UI hides the test
  // affordance and falls back to the plain reconnect button), not an error
  // worth toJson's global error toast. The contract names the route under
  // /api/mcp/registrations/ while every shipped MCP route is mounted under
  // /api/mcp/registry/ - both are probed so whichever mount the core agent
  // lands on works without another web change.
  testMcpServer: async (id: string): Promise<McpServerTestResult> => {
    const attempt = (base: string) => apiFetch(`${base}/${id}/test`, { method: "POST", timeoutMs: 60000 });
    let res = await attempt(`/api/mcp/registrations`);
    if (res.status === 404) res = await attempt(`/api/mcp/registry`);
    if (res.status === 404) return { unsupported: true };
    if (!res.ok) {
      let body: { error?: string } = {};
      try {
        body = (await res.json()) ?? {};
      } catch {
        // Non-JSON error body - fall through to the status-based message.
      }
      const err: ApiError = new Error(body.error || `Connection test failed (HTTP ${res.status}).`);
      err.status = res.status;
      throw err;
    }
    const text = await res.text();
    return text ? JSON.parse(text) : { ok: false, phase: null, detail: "Empty response from the test endpoint.", hint: null, toolCount: null };
  },
  // "Use anyway" override for a server that hasn't passed (or has failed) its
  // connection test - marks the registration ACKNOWLEDGED so agents may use
  // it. Same defensive 404 handling and dual-mount probing as testMcpServer:
  // { unsupported: true } means the running core predates the feature.
  acknowledgeMcpServer: async (id: string): Promise<{ unsupported?: boolean; [key: string]: unknown } | null> => {
    const attempt = (base: string) => apiFetch(`${base}/${id}/acknowledge`, { method: "POST" });
    let res = await attempt(`/api/mcp/registrations`);
    if (res.status === 404) res = await attempt(`/api/mcp/registry`);
    if (res.status === 404) return { unsupported: true };
    if (!res.ok) {
      let body: { error?: string } = {};
      try {
        body = (await res.json()) ?? {};
      } catch {
        // Non-JSON error body - fall through.
      }
      const err: ApiError = new Error(body.error || `Couldn't mark the server as trusted (HTTP ${res.status}).`);
      err.status = res.status;
      throw err;
    }
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  },
  // "Browse registry" - live search over the official public MCP Registry
  // (registry.modelcontextprotocol.io) via core's proxy. Returns { results,
  // nextCursor }; each result carries a launch-ready command/args/env or a
  // remote url, ready to hand straight to registerMcpServer above. cursor is
  // the previous page's nextCursor (null/omit for the first page).
  searchMcpRegistry: (q: string, cursor: string) => {
    const params = new URLSearchParams();
    if (q) params.set("q", q);
    if (cursor) params.set("cursor", cursor);
    const qs = params.toString();
    return apiFetch(`/api/mcp/registry/search${qs ? `?${qs}` : ""}`).then(toJson<{ results: unknown[]; nextCursor: string | null; unavailable?: boolean }>);
  },
};
