// Pure helpers for the "Connect with OAuth" section of the MCP add-server dialog
// (components/McpPanel/McpRemoteOAuthSection.tsx) and for reading the result the backend's
// public callback (core/mcp McpOAuthController#callback) leaves in the URL. No React, no fetching -
// unit-tested in mcpRemoteOAuth.test.ts.
import type { McpRemoteOAuthState } from "./api/mcpOauth";

/** The provider id the generic flow's callback uses in ?provider= - it can never be a one-click provider's id. */
export const REMOTE_OAUTH_PROVIDER = "remote";

/**
 * A URL worth probing/connecting: a parseable https URL with a host. OAuth sign-in is https-only server-side (the
 * backend also refuses private/loopback hosts); this only avoids calling the API for text that cannot be a URL yet.
 */
export function isConnectableUrl(raw: string | null | undefined): boolean {
  const value = (raw || "").trim();
  if (!/^https:\/\//i.test(value)) return false;
  try {
    const u = new URL(value);
    return u.protocol === "https:" && u.hostname.includes(".") && !u.username && !u.password && !u.hash;
  } catch {
    return false;
  }
}

/** True for a plain-http URL, so the UI can say "OAuth needs https" instead of staying silent. */
export function isInsecureUrl(raw: string | null | undefined): boolean {
  return /^http:\/\//i.test((raw || "").trim());
}

export interface RemoteOAuthView {
  /** i18n key (mcpRemoteOAuth namespace) for the status line. */
  messageKey: string;
  tone: "neutral" | "info" | "success" | "warning";
  /** The one primary action to offer, if any. */
  action: "connect" | "reauthorize" | "disconnect" | "retry" | null;
}

/** What the section shows for each server state - the four required states plus "could not check". */
export function viewForState(state: McpRemoteOAuthState | "checking"): RemoteOAuthView {
  switch (state) {
    case "not_required":
      return { messageKey: "notRequired", tone: "neutral", action: null };
    case "needs_signin":
      return { messageKey: "needsSignin", tone: "info", action: "connect" };
    case "connected":
      return { messageKey: "connected", tone: "success", action: "disconnect" };
    case "needs_reauth":
      return { messageKey: "needsReauth", tone: "warning", action: "reauthorize" };
    case "unknown":
      return { messageKey: "unknown", tone: "neutral", action: "retry" };
    default:
      return { messageKey: "checking", tone: "neutral", action: null };
  }
}

export interface RemoteOAuthReturn {
  result: "connected" | "error";
  reason: string | null;
  /** "registered" | "none" | "failed" - whether MCP registrations were linked after connecting. */
  mcp: string | null;
  /** The resource URL the connection is for (connected returns only). */
  server: string | null;
}

interface SearchParamsLike {
  get(name: string): string | null;
}

/** Reads ?mcp_oauth=connected|error&provider=remote&reason=&mcp=&server= . Null for any other return (incl. provider returns). */
export function parseRemoteOAuthReturn(params: SearchParamsLike | null | undefined): RemoteOAuthReturn | null {
  if (!params) return null;
  const result = params.get("mcp_oauth");
  if (result !== "connected" && result !== "error") return null;
  if ((params.get("provider") || "").toLowerCase() !== REMOTE_OAUTH_PROVIDER) return null;
  return {
    result,
    reason: params.get("reason") || null,
    mcp: params.get("mcp") || null,
    server: params.get("server") || null,
  };
}

export interface RemoteOAuthNotice {
  variant: "success" | "destructive";
  /** i18n key inside the mcpRemoteOAuth namespace; the template may take {server}. */
  key: string;
}

/** Maps a callback return to the banner to show. Reasons are the backend's fixed codes, never remote text. */
export function noticeForRemoteReturn(ret: RemoteOAuthReturn): RemoteOAuthNotice {
  if (ret.result === "connected") {
    if (ret.mcp === "failed") return { variant: "destructive", key: "noticeLinkFailed" };
    return { variant: "success", key: ret.mcp === "none" ? "noticeConnectedNoServer" : "noticeConnected" };
  }
  switch (ret.reason) {
    case "consent_declined":
      return { variant: "destructive", key: "noticeDeclined" };
    case "expired_state":
      return { variant: "destructive", key: "noticeExpired" };
    case "replayed_state":
      return { variant: "destructive", key: "noticeReplayed" };
    case "issuer_mismatch":
      return { variant: "destructive", key: "noticeIssuer" };
    case "tenant_mismatch":
      return { variant: "destructive", key: "noticeTenant" };
    case "blocked":
      return { variant: "destructive", key: "noticeBlocked" };
    case "invalid_state":
    case "missing_code_or_state":
    case "no_pending_connection":
      return { variant: "destructive", key: "noticeInvalid" };
    default:
      return { variant: "destructive", key: "noticeExchangeFailed" };
  }
}

const RETURN_PARAMS = ["mcp_oauth", "provider", "reason", "mcp", "server"];

/** The current URL's query without the OAuth return params (for router.replace), keeping everything else. */
export function stripRemoteReturnParams(pathname: string, search: string): string {
  const params = new URLSearchParams(search.startsWith("?") ? search.slice(1) : search);
  for (const k of RETURN_PARAMS) params.delete(k);
  const rest = params.toString();
  return rest ? `${pathname}?${rest}` : pathname;
}

/** Host part of a server URL for banners; falls back to the raw text. */
export function serverLabel(url: string | null | undefined): string {
  if (!url) return "";
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}
