import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it, expect, afterEach } from "vitest";
import {
  buildAppFrameCsp,
  buildPageCsp,
  buildSandboxFrameCsp,
  createNonce,
  cspHeaderName,
  originOf,
  staticFilePolicy,
  CSP_HEADER,
  CSP_REPORT_ONLY_HEADER,
  NONCE_HEADER,
  SILENT_CHECK_SSO_SCRIPT_HASH,
} from "./csp";
// @ts-ignore - web/proxy.js is plain JS with no type declarations
import { proxy } from "../../proxy";

const NONCE = "AAAAAAAAAAAAAAAAAAAAAA==";

function directive(policy: string, name: string): string[] {
  const d = policy.split("; ").find((p) => p.startsWith(name + " "));
  return d ? d.split(" ").slice(1) : [];
}

function reader(values: Record<string, string>) {
  return (key: string) => values[key];
}

describe("csp - enforced page policy from runtime config", () => {
  it("puts the configured API, auth and code-intel origins in connect-src", async () => {
    const policy = buildPageCsp({
      nonce: NONCE,
      read: reader({
        NEXT_PUBLIC_API_BASE_URL: "https://api.example.org/some/path",
        NEXT_PUBLIC_KEYCLOAK_URL: "https://sso.example.org:8443",
        NEXT_PUBLIC_CODE_INTEL_URL: "https://ci.example.org",
      }),
    });
    const connect = directive(policy, "connect-src");
    expect(connect.slice(0, 6)).toEqual([
      "'self'",
      "blob:",
      "data:",
      "https://api.example.org",
      "https://sso.example.org:8443",
      "https://ci.example.org",
    ]);
    expect(connect).toContain("wss:");
    expect(connect).toContain("https://huggingface.co");
    expect(directive(policy, "frame-src")).toContain("https://sso.example.org:8443");
    expect(directive(policy, "form-action")).toEqual(["'self'", "https://sso.example.org:8443"]);
  });

  it("allows plain websockets to a loopback host only when the install itself is on one (local LiveKit)", () => {
    const local = directive(
      buildPageCsp({ nonce: NONCE, read: reader({ NEXT_PUBLIC_API_BASE_URL: "http://localhost:18100" }) }),
      "connect-src"
    );
    expect(local).toContain("ws://localhost:*");
    const hosted = directive(
      buildPageCsp({ nonce: NONCE, read: reader({ NEXT_PUBLIC_API_BASE_URL: "https://api.example.org" }) }),
      "connect-src"
    );
    expect(hosted.some((v) => v.startsWith("ws://"))).toBe(false);
  });

  it("allows scripts by nonce + strict-dynamic only: no unsafe-inline/eval, no CDN", async () => {
    const script = directive(buildPageCsp({ nonce: NONCE, read: reader({}) }), "script-src");
    expect(script).toEqual(["'self'", `'nonce-${NONCE}'`, "'strict-dynamic'", "'wasm-unsafe-eval'"]);
    expect(script.join(" ")).not.toMatch(/unsafe-inline|'unsafe-eval'|jsdelivr|https:/);
  });

  it("adds unsafe-eval (React Refresh) only in development", async () => {
    expect(directive(buildPageCsp({ nonce: NONCE, read: reader({}), dev: true }), "script-src")).toContain("'unsafe-eval'");
  });

  it("locks down plugins, base URI and framing", async () => {
    const policy = buildPageCsp({ nonce: NONCE, read: reader({}) });
    expect(directive(policy, "object-src")).toEqual(["'none'"]);
    expect(directive(policy, "base-uri")).toEqual(["'self'"]);
    expect(directive(policy, "frame-ancestors")).toEqual(["'self'"]);
    expect(buildPageCsp({ nonce: NONCE, read: reader({}), frameable: true })).not.toContain("frame-ancestors");
  });

  it("names no hard-coded platform domain", async () => {
    expect(buildPageCsp({ nonce: NONCE, read: reader({}) })).not.toMatch(/vegaduta/i);
  });

  it("dedupes origins shared by several services", async () => {
    const policy = buildPageCsp({
      nonce: NONCE,
      read: reader({
        NEXT_PUBLIC_API_BASE_URL: "https://example.org/api",
        NEXT_PUBLIC_KEYCLOAK_URL: "https://example.org/auth",
      }),
    });
    expect(directive(policy, "connect-src").filter((v) => v === "https://example.org")).toHaveLength(1);
  });

  it("drops values that are not plain http(s) origins instead of injecting them", async () => {
    expect(originOf("not a url")).toBeNull();
    expect(originOf("javascript:alert(1)")).toBeNull();
    expect(originOf("https://a;b.example.org")).toBeNull();
    expect(originOf("")).toBeNull();
    expect(originOf(undefined)).toBeNull();
    expect(originOf("http://localhost:8080/api")).toBe("http://localhost:8080");
    const policy = buildPageCsp({ nonce: NONCE, read: reader({ NEXT_PUBLIC_API_BASE_URL: "https://x.org; script-src *" }) });
    expect(policy).not.toContain("script-src *");
  });

  it("rejects a malformed nonce and mints distinct well-formed ones", async () => {
    expect(() => buildPageCsp({ nonce: "x'; script-src *", read: reader({}) })).toThrow();
    const a = createNonce();
    const b = createNonce();
    expect(a).not.toBe(b);
    expect(() => buildPageCsp({ nonce: a, read: reader({}) })).not.toThrow();
  });

  it("is enforced unless CSP_REPORT_ONLY=true", async () => {
    expect(cspHeaderName(undefined)).toBe(CSP_HEADER);
    expect(cspHeaderName("false")).toBe(CSP_HEADER);
    expect(cspHeaderName(" TRUE ")).toBe(CSP_REPORT_ONLY_HEADER);
  });
});

describe("csp - static files with their own policy", () => {
  it("silent-check-sso.html's inline script matches the pinned hash", async () => {
    const html = readFileSync(join(__dirname, "../../public/silent-check-sso.html"), "utf8");
    const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
    expect(scripts).toHaveLength(1);
    const hash = `'sha256-${createHash("sha256").update(scripts[0]).digest("base64")}'`;
    expect(hash).toBe(SILENT_CHECK_SSO_SCRIPT_HASH);
    expect(staticFilePolicy("/silent-check-sso.html")).toContain(SILENT_CHECK_SSO_SCRIPT_HASH);
  });

  it("user-app frames get no network and no forms", async () => {
    const app = buildAppFrameCsp();
    expect(directive(app, "connect-src")).toEqual(["'none'"]);
    expect(directive(app, "form-action")).toEqual(["'none'"]);
    expect(directive(app, "default-src")).toEqual(["'none'"]);
    expect(directive(buildSandboxFrameCsp(), "form-action")).toEqual(["'none'"]);
  });

  it("other paths use the page policy", async () => {
    expect(staticFilePolicy("/agents")).toBeNull();
  });
});

describe("proxy() sets the enforced CSP", () => {
  afterEach(() => {
    delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
    delete process.env.CSP_REPORT_ONLY;
  });

  function fakeRequest(url: string) {
    const u = new URL(url) as URL & { clone: () => URL };
    u.clone = () => new URL(u.toString());
    return {
      nextUrl: u,
      headers: new Headers(),
      cookies: { get: (): undefined => undefined },
    } as any;
  }

  function nonceOf(policy: string | null): string | undefined {
    return policy?.match(/'nonce-([^']+)'/)?.[1];
  }

  it("on a page response (fresh nonce, forwarded to the render) and on a redirect", async () => {
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    const page = await proxy(fakeRequest("https://app.example.org/agents"));
    const policy = page.headers.get(CSP_HEADER);
    expect(policy).toMatch(/^default-src 'self'; script-src 'self' 'nonce-/);
    const nonce = nonceOf(policy);
    expect(nonce).toBeTruthy();
    expect(page.headers.get("x-middleware-request-" + NONCE_HEADER)).toBe(nonce);
    const again = await proxy(fakeRequest("https://app.example.org/agents"));
    expect(nonceOf(again.headers.get(CSP_HEADER))).not.toBe(nonce);
    const redirect = await proxy(fakeRequest("https://app.example.org/en/agents"));
    expect(redirect.status).toBe(308);
    expect(redirect.headers.get(CSP_HEADER)).toMatch(/connect-src 'self'/);
  });

  it("serves the embed widget without frame-ancestors", async () => {
    const embed = await proxy(fakeRequest("https://app.example.org/a/embed/abcdefghijklmnop"));
    expect(embed.headers.get(CSP_HEADER)).not.toContain("frame-ancestors");
  });

  it("gives the sandbox/app frames their own policy and plain assets none", async () => {
    expect((await proxy(fakeRequest("https://app.example.org/app-frame.html"))).headers.get(CSP_HEADER)).toMatch(/connect-src 'none'/);
    expect((await proxy(fakeRequest("https://app.example.org/logo-mark.png"))).headers.get(CSP_HEADER)).toBeNull();
  });

  it("CSP_REPORT_ONLY=true switches to the report-only header", async () => {
    process.env.CSP_REPORT_ONLY = "true";
    const page = await proxy(fakeRequest("https://app.example.org/agents"));
    expect(page.headers.get(CSP_HEADER)).toBeNull();
    expect(page.headers.get(CSP_REPORT_ONLY_HEADER)).toMatch(/'nonce-/);
  });
});

describe("proxy() hides developer-only routes in production", () => {
  const env = process.env as Record<string, string | undefined>;
  const originalNodeEnv = env.NODE_ENV;
  afterEach(() => {
    env.NODE_ENV = originalNodeEnv;
    delete env.ENABLE_DEV_ROUTES;
  });

  function get(path: string) {
    const u = new URL(`https://app.example.org${path}`) as URL & { clone: () => URL };
    u.clone = () => new URL(u.toString());
    return proxy({ nextUrl: u, headers: new Headers(), cookies: { get: (): undefined => undefined } } as any);
  }
  // get() is now a Promise since proxy() went async for the platform-admin
  // privileged-API gate - unwrap at call sites below.

  it("404s /ui-kit and /eval unless ENABLE_DEV_ROUTES=true", async () => {
    env.NODE_ENV = "production";
    expect((await get("/ui-kit")).status).toBe(404);
    expect((await get("/ui-kit/console-preview")).status).toBe(404);
    expect((await get("/eval/edge-faq")).status).toBe(404);
    expect((await get("/evaluations")).status).not.toBe(404);
    env.ENABLE_DEV_ROUTES = "true";
    expect((await get("/ui-kit")).status).not.toBe(404);
  });
});
