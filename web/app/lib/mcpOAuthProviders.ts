// Pure helpers for the "Connect apps" grid (components/McpOAuthPanel.tsx): card
// state, honest not-configured wording, and reading the result the backend's
// OAuth callback leaves in the URL. No React, no fetching - everything here is
// unit-tested in mcpOAuthProviders.test.ts.
import type { McpOAuthProvider, McpOAuthRevocation } from "./api/mcpOauth";

export type ProviderCardState = "connected" | "available" | "not_configured";

/** What the card shows. A provider the admin has not set up is NEVER "available": no Connect button for it. */
export function providerCardState(p: Pick<McpOAuthProvider, "configured" | "connected">): ProviderCardState {
  // A connection that already exists stays manageable (Disconnect) even if the admin later removed the credentials.
  if (p.connected) return "connected";
  return p.configured ? "available" : "not_configured";
}

/**
 * Badge wording for a connected card. "Connected" is only honest when agents can actually use the token, i.e. the
 * provider registers an MCP server; otherwise the access is merely saved for a future agent tool.
 */
export function connectedStatusKey(p: Pick<McpOAuthProvider, "registersMcpServer">): string {
  return p.registersMcpServer ? "statusConnected" : "statusStored";
}

/** i18n key (inside the mcpOAuth namespace) explaining why a provider is not configured. */
export function notConfiguredReasonKey(reason: string | null | undefined): string {
  switch (reason) {
    case "feature_off":
      return "reasonFeatureOff";
    case "disabled":
      return "reasonDisabled";
    case "no_client":
      return "reasonNoClient";
    case "no_callback_base":
      return "reasonNoCallbackBase";
    case "no_console_redirect":
      return "reasonNoConsoleRedirect";
    default:
      return "reasonUnknown";
  }
}

export interface ProviderCounts {
  connected: number;
  available: number;
  notConfigured: number;
}

export function countProviders(providers: McpOAuthProvider[]): ProviderCounts {
  const counts: ProviderCounts = { connected: 0, available: 0, notConfigured: 0 };
  for (const p of providers) {
    const s = providerCardState(p);
    if (s === "connected") counts.connected += 1;
    else if (s === "available") counts.available += 1;
    else counts.notConfigured += 1;
  }
  return counts;
}

/**
 * Display order: connected first, then ready-to-connect, then not configured -
 * stable within each group (the server's order, which keeps the Google products together).
 */
export function orderProviders(providers: McpOAuthProvider[]): McpOAuthProvider[] {
  const rank: Record<ProviderCardState, number> = { connected: 0, available: 1, not_configured: 2 };
  return providers
    .map((p, index) => ({ p, index, rank: rank[providerCardState(p)] }))
    .sort((a, b) => a.rank - b.rank || a.index - b.index)
    .map((x) => x.p);
}

/** The scope string a card shows: a short readable tail per scope (full URLs are useless on a card). */
export function scopeLabels(scopes: string[]): string[] {
  return scopes.map((s) => {
    const tail = s.split("/").pop() || s;
    return tail.trim();
  });
}

// ---------------------------------------------------------------- callback return

export interface OAuthReturn {
  result: "connected" | "error";
  provider: string;
  /** Fixed backend code (consent_declined, expired_state, ...). Never provider text. */
  reason: string | null;
  /** "registered" | "failed" when connecting also tried to add the provider's MCP server. */
  mcp: string | null;
}

interface SearchParamsLike {
  get(name: string): string | null;
}

/** Reads ?mcp_oauth=connected|error&provider=&reason=&mcp= . Null when the URL is not an OAuth return. */
export function parseOAuthReturn(params: SearchParamsLike | null | undefined): OAuthReturn | null {
  if (!params) return null;
  const result = params.get("mcp_oauth");
  if (result !== "connected" && result !== "error") return null;
  const provider = (params.get("provider") || "").toLowerCase().replace(/[^a-z0-9-]/g, "");
  return { result: result as "connected" | "error", provider, reason: params.get("reason") || null, mcp: params.get("mcp") || null };
}

export interface OAuthNotice {
  variant: "success" | "destructive";
  /** i18n key inside the mcpOAuth namespace; the template takes {name}. */
  key: string;
}

/** Maps a callback return to the banner to show. */
export function noticeForReturn(ret: OAuthReturn): OAuthNotice {
  if (ret.result === "connected") {
    return ret.mcp === "failed"
      ? { variant: "destructive", key: "noticeMcpFailed" }
      : { variant: "success", key: "noticeConnected" };
  }
  switch (ret.reason) {
    case "consent_declined":
      return { variant: "destructive", key: "noticeConsentDeclined" };
    case "expired_state":
      return { variant: "destructive", key: "noticeExpired" };
    case "replayed_state":
      return { variant: "destructive", key: "noticeReplayed" };
    case "invalid_state":
    case "provider_mismatch":
    case "missing_code_or_state":
      return { variant: "destructive", key: "noticeInvalid" };
    case "not_configured":
      return { variant: "destructive", key: "noticeNotConfigured" };
    default:
      return { variant: "destructive", key: "noticeExchangeFailed" };
  }
}

const RETURN_PARAMS = ["mcp_oauth", "provider", "reason", "mcp"];

/** The current URL's query without the OAuth return params (for router.replace), keeping everything else. */
export function stripOAuthReturnParams(pathname: string, search: string): string {
  const params = new URLSearchParams(search.startsWith("?") ? search.slice(1) : search);
  for (const k of RETURN_PARAMS) params.delete(k);
  const rest = params.toString();
  return rest ? `${pathname}?${rest}` : pathname;
}

/** i18n key for the banner after a disconnect, from what the provider reported about revocation. */
export function disconnectNoticeKey(revocation: McpOAuthRevocation | string | null | undefined, wasConnected: boolean): string {
  if (!wasConnected) return "noticeNothingToDisconnect";
  if (revocation === "REVOKED") return "noticeDisconnectedRevoked";
  if (revocation === "FAILED") return "noticeDisconnectedRevokeFailed";
  return "noticeDisconnectedNoRevoke";
}
