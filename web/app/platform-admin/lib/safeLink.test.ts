import { describe, it, expect, afterEach, vi } from "vitest";
import { firstPartyOrigins, resolveSafeLink, safeHref } from "./safeLink";

const opts = {
  currentOrigin: "https://admin.example.com",
  trustedOrigins: ["https://app.example.com", "https://api.example.com"],
};

describe("resolveSafeLink", () => {
  it("passes single-slash relative paths through as paths", () => {
    expect(resolveSafeLink("/platform-admin/tenants/42", opts)).toEqual({
      kind: "path",
      path: "/platform-admin/tenants/42",
    });
    expect(resolveSafeLink("/agents/1?tab=x#y", opts)).toEqual({ kind: "path", path: "/agents/1?tab=x#y" });
  });

  it("rejects protocol-relative and backslash paths", () => {
    expect(resolveSafeLink("//evil.example/x", opts)).toBeNull();
    expect(resolveSafeLink("/\\evil.example/x", opts)).toBeNull();
  });

  it("reduces same-origin absolute URLs to a path", () => {
    expect(resolveSafeLink("https://admin.example.com/audit?x=1", opts)).toEqual({
      kind: "path",
      path: "/audit?x=1",
    });
  });

  it("allows https links on a trusted first-party origin", () => {
    expect(resolveSafeLink("https://app.example.com/agents/1", opts)).toEqual({
      kind: "external",
      href: "https://app.example.com/agents/1",
    });
  });

  it("rejects foreign hosts and plain http to trusted hosts", () => {
    expect(resolveSafeLink("https://evil.example/phish", opts)).toBeNull();
    expect(resolveSafeLink("http://app.example.com/agents/1", opts)).toBeNull();
    expect(resolveSafeLink("https://app.example.com.evil.example/", opts)).toBeNull();
  });

  it("rejects script-bearing and non-web schemes", () => {
    expect(resolveSafeLink("javascript:alert(1)", opts)).toBeNull();
    expect(resolveSafeLink("JaVaScRiPt:alert(1)", opts)).toBeNull();
    expect(resolveSafeLink("java\tscript:alert(1)", opts)).toBeNull();
    expect(resolveSafeLink("data:text/html,<script>alert(1)</script>", opts)).toBeNull();
    expect(resolveSafeLink("vbscript:msgbox(1)", opts)).toBeNull();
  });

  it("rejects non-strings, empty values and bare relative forms", () => {
    expect(resolveSafeLink(null, opts)).toBeNull();
    expect(resolveSafeLink(undefined, opts)).toBeNull();
    expect(resolveSafeLink(42, opts)).toBeNull();
    expect(resolveSafeLink("", opts)).toBeNull();
    expect(resolveSafeLink("tenants/1", opts)).toBeNull();
    expect(resolveSafeLink(" /tenants", opts)).toBeNull();
  });
});

describe("firstPartyOrigins", () => {
  afterEach(() => vi.unstubAllEnvs());

  it("collects configured public origins plus extras, deduped", () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/v1");
    vi.stubEnv("NEXT_PUBLIC_APP_URL", "https://app.example.com");
    vi.stubEnv("NEXT_PUBLIC_KEYCLOAK_URL", "");
    const origins = firstPartyOrigins(["https://app.example.com/", null, "not a url"]);
    expect(origins.sort()).toEqual(["https://api.example.com", "https://app.example.com"]);
  });
});

describe("safeHref", () => {
  it("returns http(s) URLs normalised", () => {
    expect(safeHref("https://github.com/org/repo")).toBe("https://github.com/org/repo");
    expect(safeHref("http://docs.example.com")).toBe("http://docs.example.com/");
    expect(safeHref("  https://x.example/a  ")).toBe("https://x.example/a");
  });

  it("returns undefined for javascript: and other non-web schemes", () => {
    expect(safeHref("javascript:alert(document.cookie)")).toBeUndefined();
    expect(safeHref("JAVASCRIPT:alert(1)")).toBeUndefined();
    expect(safeHref("java\nscript:alert(1)")).toBeUndefined();
    expect(safeHref("data:text/html,<script>alert(1)</script>")).toBeUndefined();
    expect(safeHref("vbscript:msgbox(1)")).toBeUndefined();
    expect(safeHref("file:///etc/passwd")).toBeUndefined();
  });

  it("returns undefined for relative, protocol-relative, empty and non-string values", () => {
    expect(safeHref("//evil.example/x")).toBeUndefined();
    expect(safeHref("/local/path")).toBeUndefined();
    expect(safeHref("")).toBeUndefined();
    expect(safeHref(null)).toBeUndefined();
    expect(safeHref({ href: "https://x" })).toBeUndefined();
  });
});
