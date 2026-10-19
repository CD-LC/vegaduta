import { apiFetch, toJson, jsonBody } from "./core";

// One-click OAuth providers (Google, GitHub, Slack, Notion, Linear, HubSpot,
// Microsoft, Asana, Zoom) - core/mcp OAuthProviderController. The browser leaves
// the app for the provider's consent screen and comes back through the backend's
// public callback, which 302s to /tools?mcp_oauth=connected|error&provider=<id>
// (see lib/mcpOAuthProviders.ts for the pure helpers that read that return).

/** Why a provider cannot connect on this deployment (server codes, see OAuthProviderRegistry). */
export type McpOAuthNotConfiguredReason =
  | "feature_off"
  | "disabled"
  | "no_client"
  | "no_callback_base"
  | "no_console_redirect";

export interface McpOAuthProvider {
  id: string;
  name: string;
  /** Groups variants of one vendor (all Google products share "google"). */
  family: string;
  configured: boolean;
  notConfiguredReason: McpOAuthNotConfiguredReason | null;
  connected: boolean;
  /** Scopes a connection requests (empty when the provider's app settings decide). */
  scopes: string[];
  /** The provider has a token-revocation endpoint. */
  revocable: boolean;
  /** Connecting also registers the provider's remote MCP server - i.e. agents can actually use the token. False = the token is only stored. */
  registersMcpServer: boolean;
  /** Exact redirect URI to register with the provider's OAuth app; null when no callback base is set. */
  redirectUri: string | null;
  docsUrl: string | null;
}

export interface McpOAuthProvidersResponse {
  /** Master switch (app.mcp.oauth.providers-enabled). */
  enabled: boolean;
  providers: McpOAuthProvider[];
}

export type McpOAuthRevocation = "REVOKED" | "NOT_SUPPORTED" | "FAILED";

export interface McpOAuthDisconnectResponse {
  disconnected: boolean;
  providerRevocation: McpOAuthRevocation;
}

// "Connect with OAuth" for ANY remote MCP server URL the user types (core/mcp McpOAuthController, spec-discovered:
// RFC 9728 -> 8414 -> DCR or Client ID Metadata Document -> PKCE). Everything except the capability probe 404s while the
// deployment has app.mcp.oauth.enabled off, so the console asks the probe first and renders nothing when it is off.

export interface McpRemoteOAuthCapabilities {
  enabled: boolean;
  /** The platform publishes its OAuth Client ID Metadata Document (used instead of dynamic registration where supported). */
  cimd: boolean;
}

/** not_required | needs_signin | connected | needs_reauth | unknown (server could not be reached). */
export type McpRemoteOAuthState = "not_required" | "needs_signin" | "connected" | "needs_reauth" | "unknown";

export interface McpRemoteOAuthStatus {
  state: McpRemoteOAuthState;
  resource: string | null;
  issuer: string | null;
  scope: string | null;
}

export interface McpRemoteOAuthAuthorizeResponse {
  url: string;
  resource: string;
  issuer: string;
  /** "dcr" | "cimd" | "existing" - how the client identity was obtained. */
  clientMode: string;
}

export interface McpRemoteOAuthDisconnectResponse {
  disconnected: boolean;
  liveConnectionsDropped: number;
}

export const mcpOauthApi = {
  getMcpRemoteOAuthCapabilities: (): Promise<McpRemoteOAuthCapabilities> => apiFetch(`/api/mcp/oauth/remote`).then(toJson<McpRemoteOAuthCapabilities>),
  getMcpRemoteOAuthStatus: (url: string): Promise<McpRemoteOAuthStatus> =>
    apiFetch(`/api/mcp/oauth/remote/status`, { method: "POST", ...jsonBody({ url }) }).then(toJson<McpRemoteOAuthStatus>),
  // Returns the authorization server's consent URL; the caller sends the browser there.
  startMcpRemoteOAuth: (url: string): Promise<McpRemoteOAuthAuthorizeResponse> =>
    apiFetch(`/api/mcp/oauth/remote/authorize`, { method: "POST", ...jsonBody({ url }) }).then(toJson<McpRemoteOAuthAuthorizeResponse>),
  disconnectMcpRemoteOAuth: (url: string): Promise<McpRemoteOAuthDisconnectResponse> =>
    apiFetch(`/api/mcp/oauth/remote?url=${encodeURIComponent(url)}`, { method: "DELETE" }).then(toJson<McpRemoteOAuthDisconnectResponse>),
  listMcpOAuthProviders: (): Promise<McpOAuthProvidersResponse> => apiFetch(`/api/mcp/oauth/providers`).then(toJson<McpOAuthProvidersResponse>),
  // Returns the provider's consent URL; the caller sends the browser there.
  startMcpOAuth: (provider: string): Promise<{ url: string; provider: string }> =>
    apiFetch(`/api/mcp/oauth/${encodeURIComponent(provider)}/authorize`, { method: "POST", ...jsonBody({}) }).then(toJson<{ url: string; provider: string; }>),
  disconnectMcpOAuth: (provider: string): Promise<McpOAuthDisconnectResponse> =>
    apiFetch(`/api/mcp/oauth/${encodeURIComponent(provider)}`, { method: "DELETE" }).then(toJson<McpOAuthDisconnectResponse>),
  // Lets an existing remote MCP registration on the provider's own MCP host use the connection as its bearer. 204.
  attachMcpOAuth: (provider: string, registrationId: string) =>
    apiFetch(`/api/mcp/oauth/${encodeURIComponent(provider)}/attach/${encodeURIComponent(registrationId)}`, {
      method: "POST",
    }).then(toJson),
};
