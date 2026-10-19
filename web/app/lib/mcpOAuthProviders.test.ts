import { describe, expect, it } from "vitest";
import type { McpOAuthProvider } from "./api/mcpOauth";
import {
  connectedStatusKey,
  countProviders,
  disconnectNoticeKey,
  noticeForReturn,
  notConfiguredReasonKey,
  orderProviders,
  parseOAuthReturn,
  providerCardState,
  scopeLabels,
  stripOAuthReturnParams,
} from "./mcpOAuthProviders";

function provider(over: Partial<McpOAuthProvider>): McpOAuthProvider {
  return {
    id: "github",
    name: "GitHub",
    family: "github",
    configured: true,
    notConfiguredReason: null,
    connected: false,
    scopes: [],
    revocable: true,
    registersMcpServer: false,
    redirectUri: null,
    docsUrl: null,
    ...over,
  };
}

const params = (qs: string) => new URLSearchParams(qs);

describe("providerCardState", () => {
  it("never offers Connect for a provider the admin has not configured", () => {
    expect(providerCardState(provider({ configured: false, notConfiguredReason: "no_client" }))).toBe("not_configured");
  });
  it("offers Connect only when configured and not yet connected", () => {
    expect(providerCardState(provider({ configured: true, connected: false }))).toBe("available");
  });
  it("keeps an existing connection manageable even if the credentials were later removed", () => {
    expect(providerCardState(provider({ configured: false, connected: true }))).toBe("connected");
  });
});

describe("notConfiguredReasonKey", () => {
  it("maps each server reason to its own copy", () => {
    expect(notConfiguredReasonKey("feature_off")).toBe("reasonFeatureOff");
    expect(notConfiguredReasonKey("disabled")).toBe("reasonDisabled");
    expect(notConfiguredReasonKey("no_client")).toBe("reasonNoClient");
    expect(notConfiguredReasonKey("no_callback_base")).toBe("reasonNoCallbackBase");
    expect(notConfiguredReasonKey("no_console_redirect")).toBe("reasonNoConsoleRedirect");
  });
  it("falls back for an unknown or missing reason instead of throwing", () => {
    expect(notConfiguredReasonKey("something_new")).toBe("reasonUnknown");
    expect(notConfiguredReasonKey(null)).toBe("reasonUnknown");
    expect(notConfiguredReasonKey(undefined)).toBe("reasonUnknown");
  });
});

describe("orderProviders / countProviders", () => {
  const list = [
    provider({ id: "a", configured: false }),
    provider({ id: "b", configured: true }),
    provider({ id: "c", configured: true, connected: true }),
    provider({ id: "d", configured: true }),
    provider({ id: "e", configured: false }),
  ];
  it("puts connected first, then ready, then not configured, stable inside each group", () => {
    expect(orderProviders(list).map((p) => p.id)).toEqual(["c", "b", "d", "a", "e"]);
  });
  it("does not mutate its input", () => {
    const before = list.map((p) => p.id);
    orderProviders(list);
    expect(list.map((p) => p.id)).toEqual(before);
  });
  it("counts the three states", () => {
    expect(countProviders(list)).toEqual({ connected: 1, available: 2, notConfigured: 2 });
  });
});

describe("scopeLabels", () => {
  it("shortens Google scope URLs and leaves plain scopes alone", () => {
    expect(scopeLabels(["https://www.googleapis.com/auth/spreadsheets", "channels:read", "User.Read"])).toEqual([
      "spreadsheets",
      "channels:read",
      "User.Read",
    ]);
  });
});

describe("parseOAuthReturn", () => {
  it("reads a successful return", () => {
    expect(parseOAuthReturn(params("mcp_oauth=connected&provider=github"))).toEqual({
      result: "connected",
      provider: "github",
      reason: null,
      mcp: null,
    });
  });
  it("reads an error return with its reason and mcp flag", () => {
    expect(parseOAuthReturn(params("mcp_oauth=error&provider=slack&reason=expired_state"))).toEqual({
      result: "error",
      provider: "slack",
      reason: "expired_state",
      mcp: null,
    });
    expect(parseOAuthReturn(params("mcp_oauth=connected&provider=linear&mcp=failed"))?.mcp).toBe("failed");
  });
  it("is null for any other URL, including the Atlassian return", () => {
    expect(parseOAuthReturn(params(""))).toBeNull();
    expect(parseOAuthReturn(params("atlassian=connected"))).toBeNull();
    expect(parseOAuthReturn(params("mcp_oauth=maybe&provider=x"))).toBeNull();
    expect(parseOAuthReturn(null)).toBeNull();
  });
  it("reduces the provider to the id alphabet so it can never carry markup", () => {
    expect(parseOAuthReturn(params("mcp_oauth=error&provider=%3Cb%3EGit_Hub%3C%2Fb%3E"))?.provider).toBe("bgithubb");
  });
});

describe("noticeForReturn", () => {
  const ret = (over: Partial<NonNullable<ReturnType<typeof parseOAuthReturn>>>): NonNullable<ReturnType<typeof parseOAuthReturn>> => ({
    result: "error" as const,
    provider: "github",
    reason: null,
    mcp: null,
    ...over,
  });
  it("success is green; a failed MCP registration after a successful connect is called out", () => {
    expect(noticeForReturn(ret({ result: "connected" }))).toEqual({ variant: "success", key: "noticeConnected" });
    expect(noticeForReturn(ret({ result: "connected", mcp: "failed" })).key).toBe("noticeMcpFailed");
    expect(noticeForReturn(ret({ result: "connected", mcp: "registered" })).key).toBe("noticeConnected");
  });
  it("gives each backend reason its own message", () => {
    expect(noticeForReturn(ret({ reason: "consent_declined" })).key).toBe("noticeConsentDeclined");
    expect(noticeForReturn(ret({ reason: "expired_state" })).key).toBe("noticeExpired");
    expect(noticeForReturn(ret({ reason: "replayed_state" })).key).toBe("noticeReplayed");
    expect(noticeForReturn(ret({ reason: "invalid_state" })).key).toBe("noticeInvalid");
    expect(noticeForReturn(ret({ reason: "provider_mismatch" })).key).toBe("noticeInvalid");
    expect(noticeForReturn(ret({ reason: "not_configured" })).key).toBe("noticeNotConfigured");
  });
  it("treats an unknown reason as a failed exchange and always as destructive", () => {
    const n = noticeForReturn(ret({ reason: "exchange_failed" }));
    expect(n).toEqual({ variant: "destructive", key: "noticeExchangeFailed" });
    expect(noticeForReturn(ret({ reason: "brand_new_code" })).key).toBe("noticeExchangeFailed");
  });
});

describe("stripOAuthReturnParams", () => {
  it("removes only the OAuth return params", () => {
    expect(stripOAuthReturnParams("/tools", "?mcp_oauth=connected&provider=github&mcp=registered&tab=oauth")).toBe("/tools?tab=oauth");
  });
  it("returns the bare path when nothing else is left", () => {
    expect(stripOAuthReturnParams("/tools", "?mcp_oauth=error&provider=x&reason=expired_state")).toBe("/tools");
    expect(stripOAuthReturnParams("/tools", "")).toBe("/tools");
  });
});

describe("disconnectNoticeKey", () => {
  it("is honest about what happened at the provider", () => {
    expect(disconnectNoticeKey("REVOKED", true)).toBe("noticeDisconnectedRevoked");
    expect(disconnectNoticeKey("FAILED", true)).toBe("noticeDisconnectedRevokeFailed");
    expect(disconnectNoticeKey("NOT_SUPPORTED", true)).toBe("noticeDisconnectedNoRevoke");
    expect(disconnectNoticeKey("NOT_SUPPORTED", false)).toBe("noticeNothingToDisconnect");
  });
});

describe("connectedStatusKey", () => {
  it("says Connected only when agents can use the token (the provider registers an MCP server)", () => {
    expect(connectedStatusKey(provider({ registersMcpServer: true }))).toBe("statusConnected");
  });
  it("says the access is only saved when no agent tool consumes the token", () => {
    expect(connectedStatusKey(provider({ registersMcpServer: false }))).toBe("statusStored");
  });
});
