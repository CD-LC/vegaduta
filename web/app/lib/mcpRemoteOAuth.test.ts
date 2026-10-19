import { describe, expect, it } from "vitest";
import {
  isConnectableUrl,
  isInsecureUrl,
  noticeForRemoteReturn,
  parseRemoteOAuthReturn,
  serverLabel,
  stripRemoteReturnParams,
  viewForState,
} from "./mcpRemoteOAuth";
import { parseOAuthReturn } from "./mcpOAuthProviders";

const params = (qs: string) => new URLSearchParams(qs);

describe("isConnectableUrl", () => {
  it("accepts a plain https URL", () => {
    expect(isConnectableUrl("https://mcp.example.com/mcp")).toBe(true);
    expect(isConnectableUrl("  https://mcp.example.com:8443/a/b ")).toBe(true);
  });
  it("rejects anything that cannot be an https server URL yet", () => {
    for (const bad of ["", "   ", null, undefined, "mcp.example.com", "http://mcp.example.com", "https://", "https://localhost/mcp",
      "https://user:pw@mcp.example.com/mcp", "https://mcp.example.com/mcp#frag", "ftp://mcp.example.com", "https://not a url"]) {
      expect(isConnectableUrl(bad as string | null | undefined)).toBe(false);
    }
  });
  it("flags plain http so the dialog can explain", () => {
    expect(isInsecureUrl("http://mcp.example.com/mcp")).toBe(true);
    expect(isInsecureUrl("https://mcp.example.com/mcp")).toBe(false);
  });
});

describe("viewForState", () => {
  it("covers the four required states, each with the right single action", () => {
    expect(viewForState("not_required")).toEqual({ messageKey: "notRequired", tone: "neutral", action: null });
    expect(viewForState("needs_signin")).toEqual({ messageKey: "needsSignin", tone: "info", action: "connect" });
    expect(viewForState("connected")).toEqual({ messageKey: "connected", tone: "success", action: "disconnect" });
    expect(viewForState("needs_reauth")).toEqual({ messageKey: "needsReauth", tone: "warning", action: "reauthorize" });
  });
  it("offers a retry when the server could not be reached, and nothing while checking", () => {
    expect(viewForState("unknown").action).toBe("retry");
    expect(viewForState("checking").action).toBeNull();
  });
});

describe("parseRemoteOAuthReturn", () => {
  it("reads a connected return", () => {
    expect(parseRemoteOAuthReturn(params("mcp_oauth=connected&provider=remote&mcp=registered&server=https%3A%2F%2Fmcp.example.com%2Fmcp"))).toEqual({
      result: "connected",
      reason: null,
      mcp: "registered",
      server: "https://mcp.example.com/mcp",
    });
  });
  it("reads an error return with its fixed reason", () => {
    expect(parseRemoteOAuthReturn(params("mcp_oauth=error&provider=remote&reason=issuer_mismatch"))).toMatchObject({
      result: "error",
      reason: "issuer_mismatch",
    });
  });
  it("ignores returns that belong to the one-click provider grid (and vice versa)", () => {
    expect(parseRemoteOAuthReturn(params("mcp_oauth=connected&provider=github"))).toBeNull();
    expect(parseRemoteOAuthReturn(params("tab=servers"))).toBeNull();
    expect(parseRemoteOAuthReturn(null)).toBeNull();
    // the provider panel still parses a remote return (it filters by provider id itself), so the page routes by provider
    expect(parseOAuthReturn(params("mcp_oauth=connected&provider=remote"))?.provider).toBe("remote");
  });
});

describe("noticeForRemoteReturn", () => {
  it("maps connected returns by what happened to the registrations", () => {
    const base: { result: "connected"; reason: string | null; server: string | null } = { result: "connected", reason: null, server: null };
    expect(noticeForRemoteReturn({ ...base, mcp: "registered" })).toEqual({ variant: "success", key: "noticeConnected" });
    expect(noticeForRemoteReturn({ ...base, mcp: "none" })).toEqual({ variant: "success", key: "noticeConnectedNoServer" });
    expect(noticeForRemoteReturn({ ...base, mcp: "failed" })).toEqual({ variant: "destructive", key: "noticeLinkFailed" });
  });
  it("maps every backend reason code to its own wording and unknown ones to a generic failure", () => {
    const err = (reason: string | null) => noticeForRemoteReturn({ result: "error", reason, mcp: null, server: null }).key;
    expect(err("consent_declined")).toBe("noticeDeclined");
    expect(err("expired_state")).toBe("noticeExpired");
    expect(err("replayed_state")).toBe("noticeReplayed");
    expect(err("issuer_mismatch")).toBe("noticeIssuer");
    expect(err("tenant_mismatch")).toBe("noticeTenant");
    expect(err("blocked")).toBe("noticeBlocked");
    expect(err("invalid_state")).toBe("noticeInvalid");
    expect(err("no_pending_connection")).toBe("noticeInvalid");
    expect(err("exchange_failed")).toBe("noticeExchangeFailed");
    expect(err(null)).toBe("noticeExchangeFailed");
    expect(err("<script>")).toBe("noticeExchangeFailed");
  });
});

describe("stripRemoteReturnParams / serverLabel", () => {
  it("removes only the return params", () => {
    expect(stripRemoteReturnParams("/tools", "?mcp_oauth=connected&provider=remote&mcp=none&server=x&tab=servers")).toBe("/tools?tab=servers");
    expect(stripRemoteReturnParams("/tools", "?mcp_oauth=error&provider=remote&reason=blocked")).toBe("/tools");
  });
  it("labels a server by host", () => {
    expect(serverLabel("https://mcp.example.com/mcp")).toBe("mcp.example.com");
    expect(serverLabel("not a url")).toBe("not a url");
    expect(serverLabel(null)).toBe("");
  });
});
