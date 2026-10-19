import { describe, it, expect, beforeEach, afterEach } from "vitest";

// Host-canonicalisation tests for web/proxy.js (the apex -> app.* rule added
// 2026-09-28). proxy.js sits outside app/ so there is no colocated test file;
// this suite exercises the real proxy() with a minimal NextRequest-shaped
// stub (nextUrl is a plain URL + clone(), cookies a tiny map).
// @ts-ignore - web/proxy.js is plain JS with no type declarations
import { proxy } from "../../proxy";

function fakeRequest(url: string, cookies: Record<string, string> = {}, headers: Record<string, string> = {}) {
  const u = new URL(url) as URL & { clone: () => URL };
  u.clone = () => new URL(u.toString());
  return {
    nextUrl: u,
    headers: new Headers(headers),
    cookies: {
      get: (name: string) => (cookies[name] !== undefined ? { name, value: cookies[name] } : undefined),
    },
  } as any;
}

describe("proxy() apex -> app host canonicalisation", () => {
  // Billing ON: these cases use /billing as the example public route; the
  // billing-OFF redirect has its own block at the end of this file.
  beforeEach(() => {
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    process.env.NEXT_PUBLIC_SITE_MODE = "classic";
  });
  afterEach(() => {
    delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
    delete process.env.NEXT_PUBLIC_SITE_MODE;
  });

  it("redirects console routes on the apex host to app.vegaduta.ai", async () => {
    for (const path of ["/tools", "/agents", "/build", "/tools/servers", "/admin", "/workflows", "/ai-models", "/help/docs"]) {
      const res = await proxy(fakeRequest(`https://vegaduta.ai${path}`));
      expect(res.status, `${path} should 308`).toBe(308);
      expect(res.headers.get("location")).toBe(`https://app.vegaduta.ai${path}`);
    }
  });

  it("preserves the query string on the redirect", async () => {
    const res = await proxy(fakeRequest("https://vegaduta.ai/tools?atlassian=connected"));
    expect(res.status).toBe(308);
    expect(res.headers.get("location")).toBe("https://app.vegaduta.ai/tools?atlassian=connected");
  });

  it("never redirects the OIDC callback (PKCE verifier is origin-bound)", async () => {
    for (const suffix of ["?code=abc&state=xyz", "?error=login_required&state=xyz", "?session_state=s&iss=i"]) {
      const res = await proxy(fakeRequest(`https://vegaduta.ai/tools${suffix}`));
      expect(res.status, `callback ${suffix} must stay on apex`).not.toBe(308);
      expect(res.headers.get("location")).toBeNull();
    }
  });

  it("leaves the marketing homepage on the apex host, even with the session hint", async () => {
    // "/" is dual-purpose and can carry a fragment-mode OIDC callback the
    // proxy cannot see - it must never be host-redirected.
    for (const cookies of [{}, { vd_authed: "1" }]) {
      const res = await proxy(fakeRequest("https://vegaduta.ai/", cookies));
      expect(res.status).not.toBe(308);
      expect(res.headers.get("location")).toBeNull();
    }
  });

  it("leaves public routes on the apex host (marketing surface)", async () => {
    for (const path of ["/docs", "/billing", "/signup", "/templates", "/templates/mine", "/a/abc123", "/workflow/wf9", "/try", "/compare/x", "/code", "/status"]) {
      const res = await proxy(fakeRequest(`https://vegaduta.ai${path}`));
      expect(res.status, `${path} should stay on apex`).not.toBe(308);
      expect(res.headers.get("location"), `${path} should stay on apex`).toBeNull();
    }
  });

  it("leaves file-like paths alone (silent-check-sso iframe, robots, manifest)", async () => {
    for (const path of ["/silent-check-sso.html", "/robots.txt", "/manifest.webmanifest", "/media/hero.png"]) {
      const res = await proxy(fakeRequest(`https://vegaduta.ai${path}`));
      expect(res.status, `${path} should not redirect`).not.toBe(308);
      expect(res.headers.get("location"), `${path} should not redirect`).toBeNull();
    }
  });

  it("does not touch the app host, www-less subdomains, or localhost", async () => {
    for (const host of ["app.vegaduta.ai", "api.vegaduta.ai", "localhost:3000"]) {
      const res = await proxy(fakeRequest(`https://${host}/tools`));
      expect(res.status, `${host} must not be rewritten`).not.toBe(308);
      expect(res.headers.get("location"), `${host} must not be rewritten`).toBeNull();
    }
  });

  it("maps the www apex and the .xyz staging apex to their app hosts", async () => {
    const www = await proxy(fakeRequest("https://www.vegaduta.ai/tools"));
    expect(www.headers.get("location")).toBe("https://app.vegaduta.ai/tools");
    const xyz = await proxy(fakeRequest("https://vegaduta.xyz/tools"));
    expect(xyz.headers.get("location")).toBe("https://app.vegaduta.xyz/tools");
  });

  it("strips a locale prefix in the redirect target (console routes are not localized)", async () => {
    const res = await proxy(fakeRequest("https://vegaduta.ai/hi/tools"));
    expect(res.status).toBe(308);
    expect(res.headers.get("location")).toBe("https://app.vegaduta.ai/tools");
  });

  it("still applies the locale redirect for prefixed public routes on apex", async () => {
    // /tools is not localized so /hi/tools is handled above; a localized
    // public route must keep its normal locale behaviour instead.
    const res = await proxy(fakeRequest("https://vegaduta.ai/hi/billing"));
    const loc = res.headers.get("location");
    expect(loc === null || loc === "https://vegaduta.ai/billing" || res.status === 200).toBe(true);
    // Above all: never silently send a public page to the app host.
    expect(loc?.startsWith("https://app.")).toBeFalsy();
  });
});

describe("proxy() billing switch (NEXT_PUBLIC_BILLING_ENABLED)", () => {
  afterEach(() => {
    delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
    delete process.env.NEXT_PUBLIC_SITE_MODE;
  });

  it("sends billing routes (and locale variants) to the console when billing is off", async () => {
    const cases: Array<[string, string]> = [
      ["http://localhost:3000/billing", "http://localhost:3000/"],
      ["http://localhost:3000/billing/checkout?plan=PREMIUM", "http://localhost:3000/"],
      ["http://localhost:3000/hi/billing", "http://localhost:3000/"],
      ["http://localhost:3000/features", "http://localhost:3000/"],
      ["http://localhost:3000/admin/billing", "http://localhost:3000/usage"],
    ];
    for (const [from, to] of cases) {
      const res = await proxy(fakeRequest(from));
      expect(res.status, from).toBe(307);
      expect(res.headers.get("location"), from).toBe(to);
    }
  });

  it("leaves billing routes alone when billing is on", async () => {
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    process.env.NEXT_PUBLIC_SITE_MODE = "classic";
    for (const path of ["/billing", "/admin/billing", "/features"]) {
      const res = await proxy(fakeRequest(`http://localhost:3000${path}`));
      expect(res.headers.get("location"), path).toBeNull();
    }
  });

  it("never touches non-billing routes", async () => {
    for (const path of ["/usage", "/billingish", "/admin"]) {
      const res = await proxy(fakeRequest(`http://localhost:3000${path}`));
      expect(res.status, path).not.toBe(307);
    }
  });
});

describe("proxy() site-mode switch (NEXT_PUBLIC_SITE_MODE)", () => {
  const commercial = ["/pricing", "/enterprise", "/hire", "/earn", "/why-vegaduta", "/compare/x"];
  beforeEach(() => {
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
  });
  afterEach(() => {
    delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
    delete process.env.NEXT_PUBLIC_SITE_MODE;
  });

  it("sends commercial pages to the landing page in the default oss mode", async () => {
    for (const path of commercial) {
      const res = await proxy(fakeRequest(`http://localhost:3000${path}`));
      expect(res.status, path).toBe(307);
      expect(res.headers.get("location"), path).toBe("http://localhost:3000/");
    }
  });

  it("also catches locale-prefixed commercial pages in oss mode", async () => {
    const res = await proxy(fakeRequest("http://localhost:3000/hi/enterprise"));
    expect(res.headers.get("location")).toBe("http://localhost:3000/");
  });

  it("serves them untouched in classic mode", async () => {
    process.env.NEXT_PUBLIC_SITE_MODE = "classic";
    for (const path of commercial) {
      const res = await proxy(fakeRequest(`http://localhost:3000${path}`));
      expect(res.headers.get("location"), path).toBeNull();
    }
  });

  it("keeps the about, docs and legal pages in oss mode", async () => {
    for (const path of ["/about", "/docs", "/contact", "/terms", "/privacy", "/changelog"]) {
      const res = await proxy(fakeRequest(`http://localhost:3000${path}`));
      expect(res.headers.get("location"), path).toBeNull();
    }
  });
});
