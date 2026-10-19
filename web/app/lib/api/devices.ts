import { apiFetch, toJson, jsonBody } from "./core";
import type { McpServerGrantResponse, McpServerGrantCreatedResponse } from "./types";

export const devicesApi = {
  // Mobile SDK (#157) - console-side pairing/management, authenticated the
  // normal JWT way (any signed-in tenant member, not admin-only - pairing
  // your own phone is a personal utility action). The phone's own
  // claim/poll/result calls (X-Device-Token, no JWT) are not called from
  // this web console at all - those are made by the Android/iOS app itself.
  generateDevicePairingCode: () => apiFetch(`/api/devices/pairing-code`, { method: "POST" }).then(toJson),
  listDevices: () => apiFetch(`/api/devices`).then(toJson<unknown[]>),
  revokeDevice: (deviceId: string) => apiFetch(`/api/devices/${deviceId}`, { method: "DELETE" }).then(toJson),
  listDeviceCommands: (deviceId: string) => apiFetch(`/api/devices/${deviceId}/commands`).then(toJson),
  sendDeviceCommand: (deviceId: string, commandType: string, payload: object) =>
    apiFetch(`/api/devices/${deviceId}/commands`, {
      method: "POST",
      ...jsonBody({ commandType, payload: payload || undefined }),
    }).then(toJson),

  // Inbound MCP server access (core/mcpserver's McpServerAccessController,
  // /api/mcpserver/grants) - tenant-admin-issued API keys that let an
  // external MCP client (Claude Desktop, another agent framework) connect
  // INTO this tenant and call a curated, opt-in set of this tenant's own
  // Agents as MCP tools. Every mutating call here is gated server-side to
  // ROLE_tenant-admin (SecurityConfig), same posture as /api/mcp/registry,
  // /api/providers, /api/channels/* etc. createMcpServerGrant/
  // rotateMcpServerGrant both return a McpServerAccessGrantCreatedResponse
  // whose rawApiKey is shown exactly once - the backend only ever stores its
  // hash, never the plaintext again (see that DTO's javadoc). exposedWorkflowIds/
  // exposedKnowledgeCollectionIds are accepted and persisted by
  // updateMcpServerGrant/createMcpServerGrant for forward-compat but are not
  // yet read by any MCP tool - only exposedAgentIds is enforced today (see
  // McpServerAccessGrant's javadoc).
  listMcpServerGrants: () => apiFetch(`/api/mcpserver/grants`).then(toJson<McpServerGrantResponse[]>),
  createMcpServerGrant: (payload: object) =>
    apiFetch(`/api/mcpserver/grants`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson<McpServerGrantCreatedResponse>),
  updateMcpServerGrant: (id: string, payload: object) =>
    apiFetch(`/api/mcpserver/grants/${id}`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson<McpServerGrantResponse>),
  setMcpServerGrantEnabled: (id: string, enabled: boolean) =>
    apiFetch(`/api/mcpserver/grants/${id}`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    }).then(toJson<McpServerGrantResponse>),
  // Mints a fresh key for the same grant and immediately invalidates the old
  // one (McpServerAccessGrantService#rotate) - the response's rawApiKey is
  // the new plaintext key, shown exactly once, same as createMcpServerGrant.
  rotateMcpServerGrant: (id: string) => apiFetch(`/api/mcpserver/grants/${id}/rotate`, { method: "POST" }).then(toJson<McpServerGrantCreatedResponse>),
  // Hard delete with an audit-trail row recorded first (see
  // McpServerAccessGrantService#revoke's javadoc) - irreversible.
  revokeMcpServerGrant: (id: string) => apiFetch(`/api/mcpserver/grants/${id}`, { method: "DELETE" }).then(toJson),
};
