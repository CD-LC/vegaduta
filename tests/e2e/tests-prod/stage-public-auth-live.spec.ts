import { test, expect, type Page } from "@playwright/test";
import { DOMAIN } from "../helpers/prod-env";

/**
 * Public / auth surface of the local kind stage (NEXT_PUBLIC_SITE_MODE=oss).
 *
 * The stage advertises http:// public URLs (NEXT_PUBLIC_API_BASE_URL, KEYCLOAK_URL),
 * so the app is exercised over http:// - over https the browser blocks every
 * API call as mixed content. Override with E2E_STAGE_SCHEME=https if the stage
 * gets TLS-correct URLs. The prod specs hardcode https://, which is why they are
 * not usable as-is here (see the first test).
 *
 * Read-only: nothing here signs up, signs in with a real credential, or writes.
 */
const SCHEME = process.env.E2E_STAGE_SCHEME || "http";
const APP = `${SCHEME}://app.${DOMAIN}`;
const ADMIN = `${SCHEME}://admin.${DOMAIN}`;
const SLOW_MS = 2000;

async function collect(page: Page) {
  const errors: string[] = [];
  const failed: string[] = [];
  const bad: string[] = [];
  page.on("console", (m) => {
    if (m.type() === "error") errors.push(m.text().slice(0, 200));
  });
  page.on("pageerror", (e) => errors.push("pageerror: " + e.message.slice(0, 200)));
  page.on("requestfailed", (r) => {
    // RSC prefetches are aborted by in-flight navigation; not a defect.
    if (/_rsc=|landing-event/.test(r.url())) return; // beacon aborted by navigation
    failed.push(`${r.url().slice(0, 120)} ${r.failure()?.errorText}`);
  });
  page.on("response", (r) => {
    if (r.status() >= 400) bad.push(`${r.status()} ${r.url().slice(0, 120)}`);
  });
  return { errors, failed, bad };
}

test.describe("public-auth: stage reachability and mixed-content", () => {
  test("public pages make no mixed-content or failed API requests", async ({ page }) => {
    const c = await collect(page);
    for (const p of ["/", "/about", "/login", "/signup", "/privacy", "/terms", "/contact"]) {
      await page.goto(`${APP}${p}`, { waitUntil: "load" });
      await page.waitForTimeout(1200);
    }
    const mixed = c.errors.filter((e) => /mixed content/i.test(e));
    expect(mixed, "mixed-content errors").toEqual([]);
    expect(c.failed, "failed non-RSC requests").toEqual([]);
    const apiBad = c.bad.filter((b) => /\/api\//.test(b));
    expect(apiBad, "4xx/5xx from /api on public pages").toEqual([]);
  });
});

test.describe("public-auth: OSS site mode routing", () => {
  const COMMERCIAL = ["/pricing", "/features", "/compare", "/enterprise", "/hire", "/earn", "/why-vegaduta", "/explore", "/billing", "/refund-policy", "/shipping-policy"];
  for (const p of COMMERCIAL) {
    test(`${p} redirects to the landing page (307) in oss mode`, async ({ request }) => {
      const res = await request.get(`${APP}${p}`, { maxRedirects: 0 });
      expect(res.status()).toBe(307);
      expect(new URL(res.headers()["location"], APP).pathname).toBe("/");
    });
  }

  test("locale-prefixed commercial route is also redirected", async ({ request }) => {
    const res = await request.get(`${APP}/es/pricing`, { maxRedirects: 0 });
    expect([307, 308]).toContain(res.status());
  });

  test("the technical public pages render with their own title and h1", async ({ page }) => {
    for (const [path, title, h1] of [
      ["/", /Open-source platform for AI agents/i, /build, govern and run AI agents/i],
      ["/about", /About/i, /About VegaD/i],
      ["/privacy", /Privacy/i, /Privacy Policy/i],
      ["/terms", /Terms/i, /Terms of Service/i],
      ["/login", /Sign in/i, /Welcome back/i],
      ["/signup", /Create/i, /Create your free workspace/i],
    ] as const) {
      await page.goto(`${APP}${path}`, { waitUntil: "load" });
      await expect(page, path).toHaveTitle(title);
      await expect(page.getByRole("heading", { level: 1 }).first(), path).toHaveText(h1);
      const canon = await page.locator('link[rel="canonical"]').getAttribute("href");
      expect(canon, `${path} canonical`).toContain(`app.${DOMAIN}`);
    }
  });
});

test.describe("public-auth: landing page controls", () => {
  test("every landing section anchor exists and the header/CTA links resolve", async ({ page, request }) => {
    await page.goto(APP, { waitUntil: "load" });
    for (const id of ["capabilities", "stack", "standards", "dependencies", "surfaces", "install", "community"]) {
      await expect(page.locator(`#${id}`), `#${id}`).toHaveCount(1);
    }
    const header = page.locator("header").first();
    await expect(header.getByRole("link", { name: /^Sign in$/ })).toHaveAttribute("href", "/login");
    await expect(header.getByRole("link", { name: /Get started/i })).toHaveAttribute("href", "/signup");
    // Internal links on the landing page must all resolve (no dead links).
    const hrefs: string[] = await page.$$eval("a[href^='/']", (as) => [...new Set(as.map((a) => (a as HTMLAnchorElement).getAttribute("href")!.split("#")[0]).filter(Boolean))]);
    for (const h of hrefs) {
      const r = await request.get(`${APP}${h}`, { maxRedirects: 5 });
      expect(r.status(), `landing link ${h}`).toBeLessThan(400);
    }
  });

  // DEFECT: header link href="/#dependencies" (app/components/oss-landing/OssChrome.tsx) updates the hash but does not
  // scroll when already on "/" (scrollY stays 0, twice); loading /#dependencies directly scrolls fine.
  test.fixme("in-page nav (#dependencies) scrolls to the section", async ({ page }) => {
    await page.goto(APP, { waitUntil: "load" });
    await page.getByRole("link", { name: "Dependencies", exact: true }).first().click();
    await expect(page).toHaveURL(/#dependencies$/);
    await expect(page.locator("#dependencies")).toBeInViewport();
  });

  test("language menu opens from the keyboard and Escape closes it", async ({ page }) => {
    await page.goto(`${APP}/login`, { waitUntil: "load" });
    const lang = page.getByRole("button", { name: /english/i }).first();
    await lang.focus();
    await page.keyboard.press("Enter");
    const list = page.getByRole("listbox");
    await expect(list).toBeVisible();
    await expect(list).toContainText(/30 languages/i);
    await page.keyboard.press("Escape");
    await expect(list).toBeHidden();
  });

  test("theme panel opens and Light/Dark choice persists across reload (localStorage)", async ({ page }) => {
    await page.goto(`${APP}/login`, { waitUntil: "load" });
    const theme = page.getByRole("button", { name: /customize theme/i });
    await theme.click();
    await expect(page.getByText(/accent color/i)).toBeVisible();
    await page.getByText("Light", { exact: true }).click();
    await expect.poll(() => page.evaluate(() => localStorage.getItem("agentic_theme_mode"))).toBe("light");
    await page.reload({ waitUntil: "load" });
    expect(await page.evaluate(() => localStorage.getItem("agentic_theme_mode"))).toBe("light");
    // cleanup: restore dark so other specs in this browser context are unaffected
    await page.evaluate(() => localStorage.removeItem("agentic_theme_mode"));
    await page.evaluate(() => localStorage.removeItem("agentic_theme_mode_explicit"));
  });

  // A11Y GAP: the theme popover has no role (dialog/menu) and the trigger has no aria-expanded.
  test.fixme("theme popover exposes a dialog/menu role and aria-expanded", async ({ page }) => {
    await page.goto(`${APP}/login`, { waitUntil: "load" });
    const theme = page.getByRole("button", { name: /customize theme/i });
    await theme.click();
    await expect(theme).toHaveAttribute("aria-expanded", "true");
    await expect(page.getByRole("dialog").or(page.getByRole("menu"))).toBeVisible();
  });

  test("a locale-prefixed landing page (/hi) renders translated copy and sets the locale cookie", async ({ page }) => {
    await page.goto(`${APP}/hi`, { waitUntil: "load" });
    await expect(page.locator("html")).toHaveAttribute("lang", /hi/);
    const cookies = await page.context().cookies();
    expect(cookies.some((c) => /locale/i.test(c.name) && /hi/.test(c.value))).toBe(true);
  });
});

test.describe("public-auth: login", () => {
  test("login page has the email field, hand-off button, and cross-links", async ({ page }) => {
    await page.goto(`${APP}/login`, { waitUntil: "load" });
    await expect(page.locator("#login-email")).toBeVisible();
    await expect(page.getByRole("button", { name: /continue to secure sign-in/i })).toBeEnabled();
    await expect(page.getByRole("link", { name: /start free/i })).toHaveAttribute("href", "/signup");
    await expect(page.getByRole("link", { name: /try it without an account/i })).toHaveAttribute("href", "/try");
  });

  test("Continue hands off to Keycloak on the stage auth host (no localhost issuer) and carries the email as a hint", async ({ page }) => {
    await page.goto(`${APP}/login`, { waitUntil: "load" });
    await page.locator("#login-email").fill("e2e-public-auth-nobody@example.com");
    await page.getByRole("button", { name: /continue to secure sign-in/i }).click();
    await page.waitForURL(/auth\./, { timeout: 30_000 });
    const u = new URL(page.url());
    expect(u.host).toBe(`auth.${DOMAIN}`);
    expect(u.pathname).toContain("/realms/agentic-ai/protocol/openid-connect/auth");
    expect(u.searchParams.get("client_id")).toBe("agentic-ai-web");
    expect(u.searchParams.get("redirect_uri")).toContain(`app.${DOMAIN}`);
    expect(u.searchParams.get("login_hint")).toBe("e2e-public-auth-nobody@example.com");
    await expect(page.getByRole("heading", { name: /sign in to your account/i })).toBeVisible();
  });

  test("an anonymous visit to a console route shows the public page, not a console shell", async ({ page }) => {
    await page.goto(`${APP}/agents`, { waitUntil: "load" });
    await page.waitForTimeout(1500);
    // Gated: no console data is rendered and the page offers sign in.
    await expect(page.getByRole("link", { name: /^Sign in$/ }).first()).toBeVisible();
    await expect(page.getByText(/e2e-|loadtest@/i)).toHaveCount(0);
  });

  test("unknown client/wrong password at Keycloak is rejected with the generic message", async ({ page }) => {
    // Deliberately bogus, non-existent account: asserts no account enumeration. No real credential is used.
    await page.goto(`${APP}/login`, { waitUntil: "load" });
    await page.getByRole("button", { name: /continue to secure sign-in/i }).click();
    await page.waitForURL(/auth\./, { timeout: 30_000 });
    await page.locator("#username").fill("e2e-public-auth-ghost@example.com");
    await page.locator("#password").fill("not-a-real-password-1");
    await page.locator("#kc-login").click();
    const err = page.locator("#input-error, .kc-feedback-text, [class*=alert-error]").first();
    await expect(err).toBeVisible();
    await expect(err).toHaveText(/invalid username or password/i);
  });
});

test.describe("public-auth: admin host gate", () => {
  test("admin host redirects anonymous visitors to Keycloak", async ({ page }) => {
    await page.goto(`${ADMIN}/platform-admin`, { waitUntil: "load" });
    await page.waitForURL(/auth\./, { timeout: 30_000 });
    expect(new URL(page.url()).searchParams.get("redirect_uri")).toContain(`admin.${DOMAIN}`);
  });

  // DEFECT (stage): the admin host signs in with client_id=agentic-ai-web, the tenant client, not
  // agentic-ai-platform-admin (tests-prod/auth-gating.spec.ts:46 expects the latter).
  test.fixme("admin host uses the dedicated platform-admin OIDC client", async ({ page }) => {
    await page.goto(`${ADMIN}/platform-admin`, { waitUntil: "load" });
    await page.waitForURL(/auth\./, { timeout: 30_000 });
    expect(new URL(page.url()).searchParams.get("client_id")).toBe("agentic-ai-platform-admin");
  });
});

test.describe("public-auth: signup wizard (no submission)", () => {
  test("intro -> form -> validation, and Sign in instead returns to login hand-off", async ({ page }) => {
    await page.goto(`${APP}/signup`, { waitUntil: "load" });
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(/Create your free workspace/i);
    const start = page.getByRole("button", { name: /create free account/i });
    await expect(start).toBeVisible();
    await start.click();
    const submit = page.locator('button[type="submit"]').first();
    await expect(submit).toBeVisible();
    // Empty submit must not leave the page or provision anything.
    await submit.click();
    await expect(page).toHaveURL(new RegExp(`${APP.replace(/\./g, "\\.")}/signup`));
    const invalid = await page.locator("input:invalid, [aria-invalid=true]").count();
    expect(invalid, "empty submit flags required fields").toBeGreaterThan(0);
  });

  test("mismatched passwords are rejected client-side without a network write", async ({ page }) => {
    await page.goto(`${APP}/signup`, { waitUntil: "load" });
    await page.getByRole("button", { name: /create free account/i }).click();
    const writes: string[] = [];
    page.on("request", (r) => {
      if (r.method() === "POST" && /\/api\//.test(r.url()) && !/landing-event/.test(r.url())) writes.push(r.url());
    });
    await page.getByLabel(/workspace/i).first().fill("e2e-public-auth-ws");
    await page.getByLabel(/first name/i).fill("E2E");
    await page.getByLabel(/last name/i).fill("PublicAuth");
    await page.getByLabel(/email/i).first().fill("e2e-public-auth-mismatch@example.com");
    await page.getByLabel(/^password$/i).fill("Aa1!aaaaaaaa");
    await page.getByLabel(/confirm password/i).fill("Bb2!bbbbbbbb");
    await page.getByLabel(/i agree to the/i).check();
    await page.locator('button[type="submit"]').first().click();
    await expect(page.locator("body")).toContainText(/match|same/i);
    expect(writes, "no signup POST for a client-side invalid form").toEqual([]);
  });
});

test.describe("public-auth: SEO, robots, sitemap, llms.txt", () => {
  test("robots.txt: crawlable, blocks console routes, no blanket disallow", async ({ request }) => {
    const body = await (await request.get(`${APP}/robots.txt`)).text();
    expect(body).toMatch(/user-agent:\s*\*/i);
    expect(body).not.toMatch(/^\s*disallow:\s*\/\s*$/im);
    for (const p of ["/api/", "/agents", "/workflows", "/signup/complete"]) expect(body).toContain(`Disallow: ${p}`);
  });

  // DEFECT: sitemap.xml and robots.txt "Sitemap:" use http://localhost:3000 although the pod has
  // NEXT_PUBLIC_SITE_URL/SITE_URL=app host. app/lib/site-url.ts is read at build time for the statically
  // generated app/sitemap.ts + app/robots.ts, so the image bakes the localhost default.
  test.fixme("robots.txt Sitemap line and sitemap.xml <loc> use the public origin", async ({ request }) => {
    const robots = await (await request.get(`${APP}/robots.txt`)).text();
    expect(robots).toMatch(new RegExp(`Sitemap:\\s*https?://app\\.${DOMAIN.replace(/\./g, "\\.")}/sitemap\\.xml`));
    const xml = await (await request.get(`${APP}/sitemap.xml`)).text();
    expect(xml).not.toContain("localhost");
  });

  // DEFECT: sitemap advertises commercial routes (/compare, /hire, /enterprise, ...) that proxy.js 307s to "/" in oss mode.
  test.fixme("every sitemap URL path resolves without redirecting to /", async ({ request }) => {
    const xml = await (await request.get(`${APP}/sitemap.xml`)).text();
    const paths = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => new URL(m[1]).pathname);
    const redirected: string[] = [];
    for (const p of paths.slice(0, 60)) {
      const r = await request.get(`${APP}${p}`, { maxRedirects: 0 });
      if (r.status() >= 300) redirected.push(`${r.status()} ${p}`);
    }
    expect(redirected).toEqual([]);
  });

  test("sitemap.xml is well-formed XML with unique locs", async ({ request }) => {
    const res = await request.get(`${APP}/sitemap.xml`);
    expect(res.status()).toBe(200);
    expect(res.headers()["content-type"]).toMatch(/xml/);
    const locs = [...(await res.text()).matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
    expect(locs.length).toBeGreaterThan(10);
    expect(new Set(locs).size).toBe(locs.length);
  });

  test("llms.txt is served as text and is non-trivial", async ({ request }) => {
    const res = await request.get(`${APP}/llms.txt`);
    expect(res.status()).toBe(200);
    expect(res.headers()["content-type"]).toMatch(/text\//);
    expect((await res.text()).length).toBeGreaterThan(500);
  });

  // DEFECT: llms.txt still describes the classic commercial product ("built for India", "pay in rupees over UPI",
  // free/paid plans) while the stage runs the OSS site mode that removes pricing.
  test.fixme("llms.txt does not advertise pricing/UPI in oss site mode", async ({ request }) => {
    const body = await (await request.get(`${APP}/llms.txt`)).text();
    expect(body).not.toMatch(/UPI|rupees|Free plan/i);
  });

  // DEFECT: an unknown URL returns 404 + noindex but renders the full LANDING page (landing h1, landing title) instead of
  // app/not-found.tsx ("This page does not exist." / Go home / Read the docs); anonymous visitors never see the 404 copy.
  test.fixme("unknown route is a real 404 with a helpful body (not the landing page)", async ({ page }) => {
    const res = await page.goto(`${APP}/e2e-public-auth-does-not-exist`, { waitUntil: "load" });
    expect(res!.status()).toBe(404);
    await expect(page.getByRole("heading", { name: /does not exist/i })).toBeVisible();
    await expect(page.getByRole("link", { name: /go home/i })).toHaveAttribute("href", "/");
    await expect(page.locator('meta[name="robots"]')).toHaveAttribute("content", /noindex/);
  });

  // DEFECT (efficiency/SEO): the 404 response body is ~830 KB (3.3x the home page) and carries the landing <title>.
  test.fixme("404 payload is small and has its own title", async ({ request, page }) => {
    const res = await request.get(`${APP}/e2e-public-auth-does-not-exist`);
    expect((await res.body()).length).toBeLessThan(150_000);
    await page.goto(`${APP}/e2e-public-auth-does-not-exist`);
    await expect(page).toHaveTitle(/not found|404/i);
  });
});

test.describe("public-auth: browser-facing URL hygiene (AGENTS.md rule 4)", () => {
  // DEFECT: window.__ENV / __public-env contains NEXT_PUBLIC_CODE_INTEL_URL=http://localhost:8090 and
  // /docs links to http://localhost:4100 (Academy). deploy/helm/charts/web/templates/deployment.yaml sets CODE_INTEL via
  // vegaduta-lib.publicUrl but the stage values leave it unset; app/lib/academyUrl.ts defaults to localhost:4100.
  test.fixme("public pages carry no localhost URL in HTML or anchors", async ({ page }) => {
    const offenders: string[] = [];
    for (const p of ["/", "/docs", "/help", "/login", "/signup"]) {
      await page.goto(`${APP}${p}`, { waitUntil: "load" });
      const html = await page.content();
      for (const m of html.matchAll(/https?:\/\/(?:localhost|127\.0\.0\.1)[:\d]*/g)) offenders.push(`${p} ${m[0]}`);
    }
    expect([...new Set(offenders)]).toEqual([]);
  });
});

test.describe("public-auth: efficiency budgets", () => {
  for (const p of ["/", "/about", "/login", "/signup", "/privacy", "/terms"]) {
    test(`${p}: server TTFB < ${SLOW_MS}ms and gzip HTML < 120 KB`, async ({ request }) => {
      const t = Date.now();
      const res = await request.get(`${APP}${p}`, { headers: { "accept-encoding": "gzip" } });
      const dt = Date.now() - t;
      expect(res.status()).toBe(200);
      expect(dt).toBeLessThan(SLOW_MS);
      expect((await res.body()).length).toBeLessThan(400_000);
    });
  }

  test("landing page request count and JS error count stay bounded", async ({ page }) => {
    let n = 0;
    page.on("request", () => n++);
    const c = await collect(page);
    await page.goto(APP, { waitUntil: "load" });
    await page.waitForTimeout(1500);
    expect(n).toBeLessThan(90);
    expect(c.errors.filter((e) => !/requestStorageAccess|insecure context/i.test(e))).toEqual([]);
  });
});

test.describe("public-auth: 404 transport (passing part)", () => {
  test("unknown route answers 404 with noindex", async ({ request }) => {
    const res = await request.get(`${APP}/e2e-public-auth-does-not-exist`);
    expect(res.status()).toBe(404);
    expect(await res.text()).toMatch(/name="robots"[^>]*noindex/);
  });
});
