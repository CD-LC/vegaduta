import { describe, expect, it } from "vitest";
import {
  APP_FRAME_CSP,
  APP_FRAME_SANDBOX,
  MAX_APP_HTML_BYTES,
  buildAppDoc,
  isUiResourceUri,
  parseUiResource,
  effectiveAppCsp,
  parseAppPointer,
  stripMetaRefresh,
} from "./mcpApps";

describe("isUiResourceUri", () => {
  it("accepts ui:// only", () => {
    expect(isUiResourceUri("ui://weather/dashboard")).toBe(true);
    expect(isUiResourceUri("ui://weather")).toBe(true);
    expect(isUiResourceUri("https://evil.example/x")).toBe(false);
    expect(isUiResourceUri("javascript:alert(1)")).toBe(false);
    expect(isUiResourceUri("ui://")).toBe(false);
    expect(isUiResourceUri("ui://a b/c")).toBe(false);
    expect(isUiResourceUri("ui://a/<script>")).toBe(false);
    expect(isUiResourceUri(42)).toBe(false);
  });
});

describe("parseUiResource", () => {
  const ok = { uri: "ui://s/v", mimeType: "text/html;profile=mcp-app", text: "<p>hi</p>" };
  it("accepts inline html", () => {
    expect(parseUiResource(ok)).toEqual({ ok: true, uri: "ui://s/v", html: "<p>hi</p>" });
    expect(parseUiResource({ ...ok, mimeType: "Text/HTML; profile=mcp-app" }).ok).toBe(true);
  });
  it("rejects bad uri, mime, empty and oversize", () => {
    expect(parseUiResource({ ...ok, uri: "http://x" }).ok).toBe(false);
    expect(parseUiResource({ ...ok, mimeType: "application/javascript" }).ok).toBe(false);
    expect(parseUiResource({ ...ok, mimeType: "text/html" }).ok).toBe(false);
    expect(parseUiResource({ ...ok, mimeType: "text/html;profile=other" }).ok).toBe(false);
    expect(parseUiResource({ ...ok, mimeType: undefined }).ok).toBe(false);
    expect(parseUiResource({ ...ok, text: "" }).ok).toBe(false);
    expect(parseUiResource({ ...ok, text: undefined }).ok).toBe(false);
    expect(parseUiResource({ ...ok, text: "a".repeat(MAX_APP_HTML_BYTES + 1) }).ok).toBe(false);
    expect(parseUiResource(null).ok).toBe(false);
  });
});

describe("frame hardening", () => {
  it("sandbox is exactly allow-scripts", () => {
    expect(APP_FRAME_SANDBOX).toBe("allow-scripts");
    expect(APP_FRAME_SANDBOX).not.toContain("same-origin");
  });
  it("CSP blocks network, frames, base and forms", () => {
    for (const d of [
      "default-src 'none'",
      "connect-src 'none'",
      "frame-src 'none'",
      "base-uri 'none'",
      "form-action 'none'",
      "object-src 'none'",
    ]) {
      expect(APP_FRAME_CSP).toContain(d);
    }
    expect(APP_FRAME_CSP).not.toMatch(/https?:/);
  });
  it("puts the CSP meta before app content", () => {
    const doc = buildAppDoc("<script>1</script>");
    expect(doc.indexOf("Content-Security-Policy")).toBeLessThan(doc.indexOf("<script>1"));
  });
});

describe("stripMetaRefresh", () => {
  it("removes meta refresh variants but keeps other tags", () => {
    expect(stripMetaRefresh('<meta http-equiv="refresh" content="0;url=https://x.example"><p>a</p>')).toBe("<p>a</p>");
    expect(stripMetaRefresh("<META HTTP-EQUIV=Refresh content='0;url=https://x'>ok")).toBe("ok");
    expect(buildAppDoc('<meta http-equiv="refresh" content="0;url=https://x">')).not.toMatch(/http-equiv="refresh"/i);
  });
});

describe("effectiveAppCsp / buildAppDoc with a core-supplied CSP", () => {
  const coreCsp =
    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: https:; font-src data:; media-src data:; " +
    "connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'";

  it("uses the core CSP when it keeps the closed baseline", () => {
    expect(effectiveAppCsp(coreCsp)).toBe(coreCsp);
    expect(buildAppDoc("<p>x</p>", coreCsp)).toContain(`content="${coreCsp}"`);
  });
  it("accepts explicit https hosts core granted from its allowlist", () => {
    const widened = coreCsp.replace("connect-src 'none'", "connect-src https://api.good.example");
    expect(effectiveAppCsp(widened)).toBe(widened);
  });
  it("falls back to the closed default for anything malformed or looser", () => {
    for (const bad of [
      undefined,
      "",
      42,
      "default-src 'none'; script-src 'unsafe-inline'\" onload=\"x",
      coreCsp.replace("default-src 'none'", "default-src *"),
      coreCsp + "; script-src 'unsafe-eval'",
      coreCsp.replace("connect-src 'none'", "connect-src *"),
      coreCsp.replace("connect-src 'none'", "connect-src https:"),
      coreCsp.replace("connect-src 'none'", "connect-src http://x.example"),
      coreCsp.replace("form-action 'none'", "form-action 'self'"),
      "default-src 'none'; frame-ancestors *",
      coreCsp + "\n; connect-src *",
      coreCsp + "<script>",
    ]) {
      expect(effectiveAppCsp(bad)).toBe(APP_FRAME_CSP);
    }
  });
  it("never lets a hostile csp break out of the meta attribute", () => {
    const doc = buildAppDoc("<p>x</p>", `${coreCsp}"><script>alert(1)</script>`);
    expect(doc).toContain(`content="${APP_FRAME_CSP}"`);
    expect(doc).not.toContain("<script>alert(1)");
  });
});

describe("parseAppPointer", () => {
  const good = { appId: "abcdefghijklmnopqrstuvwx", server: "weather", tool: "forecast", uri: "ui://weather/forecast" };
  it("accepts a well-formed pointer", () => {
    expect(parseAppPointer(good)).toEqual(good);
  });
  it("rejects non-ui uris, bad ids and wrong shapes", () => {
    expect(parseAppPointer({ ...good, uri: "https://evil.example/x" })).toBeNull();
    expect(parseAppPointer({ ...good, appId: "short" })).toBeNull();
    expect(parseAppPointer({ ...good, appId: "has space/and/slash/........" })).toBeNull();
    expect(parseAppPointer({ ...good, server: "" })).toBeNull();
    expect(parseAppPointer({ ...good, tool: 5 })).toBeNull();
    expect(parseAppPointer(null)).toBeNull();
    expect(parseAppPointer([good])).toBeNull();
    expect(parseAppPointer("x")).toBeNull();
  });
});
