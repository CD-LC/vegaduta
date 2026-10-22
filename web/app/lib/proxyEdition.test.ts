import { describe, it, expect, beforeEach, afterEach } from "vitest";
import robots from "../robots";
import sitemap from "../sitemap";

// Personal-edition 404s in web/proxy.js (lean edition, WP-WEB-404). The proxy answers a real 404 for
// the pages EDITION_ROUTES lists as denied, for /platform-admin paths outside the personal allowlist and
// for the A2A discovery files. Every other edition must behave exactly as it did before the block existed.
// @ts-ignore - web/proxy.js is plain JS with no type declarations
import { proxy, isPersonalEditionAtRuntime } from "../../proxy";

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

const ENV_KEYS = [
  "NEXT_PUBLIC_EDITION",
  "NEXT_PUBLIC_AUTH_MODE",
  "NEXT_PUBLIC_DEPLOY_ENV",
  "NEXT_PUBLIC_BILLING_ENABLED",
  "NEXT_PUBLIC_SITE_MODE",
];
const saved: Record<string, string | undefined> = {};

beforeEach(() => {
  for (const k of ENV_KEYS) {
    saved[k] = process.env[k];
    delete process.env[k];
  }
});
afterEach(() => {
  // Other describe blocks (and files in the same worker) must still see keycloak, billing off, oss mode.
  for (const k of ENV_KEYS) {
    if (saved[k] === undefined) delete process.env[k];
    else process.env[k] = saved[k];
  }
});

const ORIGIN = "http://localhost:3000";
const get = (path: string, headers: Record<string, string> = {}) => proxy(fakeRequest(`${ORIGIN}${path}`, {}, headers));

/** What a caller can observe about a response; two proxies agree when these are equal. */
async function observe(path: string, headers: Record<string, string> = {}) {
  const res = await get(path, headers);
  const h: Record<string, string> = {};
  res.headers.forEach((v: string, k: string) => {
    // The CSP nonce is random per request.
    if (k === "content-security-policy" || k === "x-nonce") return;
    if (k.startsWith("x-middleware-request-")) return;
    // Cookie expiry is wall-clock time.
    h[k] = v.replace(/Expires=[^;]*/g, "Expires=T");
  });
  return { status: res.status, location: res.headers.get("location"), headers: h };
}

describe("proxy() on a personal install", () => {
  beforeEach(() => {
    process.env.NEXT_PUBLIC_EDITION = "personal";
  });

  it("answers 404 for the hosted, team, billing and discovery pages", async () => {
    const denied = [
      "/directory",
      "/directory/some-agent",
      "/signup",
      "/try",
      "/view-as",
      "/.well-known/agent-card.json",
      "/.well-known/agent.json",
      "/platform-admin/billing",
      "/admin/payments",
      "/admin/commerce",
      "/admin/sso",
      "/roi",
      "/studio",
      "/templates/mine",
    ];
    for (const path of denied) {
      const res = await get(path);
      expect(res.status, path).toBe(404);
      expect(res.headers.get("location"), path).toBeNull();
    }
  });

  it("answers 404 for a locale-prefixed denied route (the prefix is stripped first)", async () => {
    expect((await get("/hi/signup")).status).toBe(404);
    expect((await get("/hi/directory")).status).toBe(404);
  });

  it("denies /platform-admin paths outside the personal allowlist and keeps the allowlist", async () => {
    for (const path of ["/platform-admin/tenants", "/platform-admin/billing", "/platform-admin/ops", "/platform-admin/fleet"]) {
      expect((await get(path)).status, path).toBe(404);
    }
    for (const path of [
      "/platform-admin",
      "/platform-admin/llm-providers",
      "/platform-admin/llm-providers/pool-1",
      "/platform-admin/tool-credentials",
      "/platform-admin/settings",
    ]) {
      expect((await get(path)).status, path).not.toBe(404);
    }
  });

  it("passes the pages a personal install does have", async () => {
    for (const path of ["/login", "/docs", "/assistant", "/agents", "/usage", "/admin/maintenance", "/robots.txt"]) {
      const res = await get(path);
      expect(res.status, path).not.toBe(404);
    }
  });

  it("keeps the billing redirect ahead of the 404 (/admin/billing -> /usage, /features -> /)", async () => {
    const billing = await get("/admin/billing");
    expect(billing.status).toBe(307);
    expect(billing.headers.get("location")).toBe(`${ORIGIN}/usage`);
    const features = await get("/features");
    expect(features.status).toBe(307);
    expect(features.headers.get("location")).toBe(`${ORIGIN}/`);
  });

  it("is 404 for /try and /signup in the classic site mode too", async () => {
    process.env.NEXT_PUBLIC_SITE_MODE = "classic";
    expect((await get("/try")).status).toBe(404);
    expect((await get("/signup")).status).toBe(404);
  });

  it("recognises a personal install by local auth when NEXT_PUBLIC_EDITION is absent", async () => {
    delete process.env.NEXT_PUBLIC_EDITION;
    process.env.NEXT_PUBLIC_AUTH_MODE = "local";
    expect(isPersonalEditionAtRuntime()).toBe(true);
    expect((await get("/directory")).status).toBe(404);
  });

  it("lets an explicit shared tier win over local auth", async () => {
    process.env.NEXT_PUBLIC_EDITION = "lite";
    process.env.NEXT_PUBLIC_AUTH_MODE = "local";
    expect(isPersonalEditionAtRuntime()).toBe(false);
    expect((await get("/directory")).status).not.toBe(404);
  });
});

describe("proxy() everywhere but a personal install is unchanged", () => {
  const paths = [
    "/directory",
    "/signup",
    "/try",
    "/view-as",
    "/.well-known/agent-card.json",
    "/.well-known/agent.json",
    "/platform-admin/billing",
    "/platform-admin/tenants",
    "/platform-admin/llm-providers",
    "/admin/payments",
    "/admin/sso",
    "/roi",
    "/studio",
    "/templates/mine",
    "/hi/signup",
    "/login",
    "/assistant",
  ];

  // Responses captured by running the proxy as it was at the integration base (git f3b91c2b3, before the
  // personal block existed) with the same fake request and env: none of these paths is a 404 on a shared
  // install; every one passes through (NextResponse.next / rewrite, status 200, no Location).
  const BEFORE: Record<string, { status: number; location: string | null }> = Object.fromEntries(
    paths.map((p) => [p, { status: 200, location: null as string | null }])
  );

  const shapes: Array<[string, Record<string, string>]> = [
    ["nothing declared", {}],
    ["lite", { NEXT_PUBLIC_EDITION: "lite" }],
    ["full", { NEXT_PUBLIC_EDITION: "full" }],
    ["deploy env lite", { NEXT_PUBLIC_DEPLOY_ENV: "lite" }],
    ["deploy env full", { NEXT_PUBLIC_DEPLOY_ENV: "full" }],
    ["unrecognised edition", { NEXT_PUBLIC_EDITION: "enterprise" }],
  ];

  for (const [label, env] of shapes) {
    it(`serves exactly the pre-edition responses (${label})`, async () => {
      for (const [k, v] of Object.entries(env)) process.env[k] = v;
      expect(isPersonalEditionAtRuntime()).toBe(false);
      for (const path of paths) {
        const res = await observe(path);
        expect({ status: res.status, location: res.location }, path).toEqual(BEFORE[path]);
      }
    });
  }

  it("is identical across every non-personal shape, headers included", async () => {
    const baseline: Record<string, unknown> = {};
    for (const path of paths) baseline[path] = await observe(path);
    for (const [label, env] of shapes) {
      for (const k of ENV_KEYS) delete process.env[k];
      for (const [k, v] of Object.entries(env)) process.env[k] = v;
      for (const path of paths) {
        expect(await observe(path), `${label} ${path}`).toEqual(baseline[path]);
      }
    }
  });

  it("keeps the billing-on and classic-site paths intact", async () => {
    process.env.NEXT_PUBLIC_EDITION = "full";
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    process.env.NEXT_PUBLIC_SITE_MODE = "classic";
    for (const path of ["/try", "/signup", "/billing", "/features", "/directory"]) {
      const res = await get(path);
      expect(res.status, path).toBe(200);
      expect(res.headers.get("location"), path).toBeNull();
    }
  });
});

describe("proxy() personal state does not leak between tests", () => {
  it("sees no edition once the environment is cleared", () => {
    expect(process.env.NEXT_PUBLIC_EDITION).toBeUndefined();
    expect(isPersonalEditionAtRuntime()).toBe(false);
  });
});

describe("robots.txt and sitemap.xml by edition", () => {
  it("disallow everything and list no URLs on a personal install", () => {
    process.env.NEXT_PUBLIC_EDITION = "personal";
    expect(robots()).toEqual({ rules: [{ userAgent: "*", disallow: "/" }] });
    expect(sitemap()).toEqual([]);
  });

  it("keep the public funnel (and the sitemap line) on lite, full and undeclared installs", () => {
    for (const edition of [undefined, "lite", "full"]) {
      if (edition) process.env.NEXT_PUBLIC_EDITION = edition;
      else delete process.env.NEXT_PUBLIC_EDITION;
      const r = robots();
      expect(r.sitemap, String(edition)).toMatch(/\/sitemap\.xml$/);
      expect(sitemap().length, String(edition)).toBeGreaterThan(20);
    }
  });
});
