import fs from "node:fs";
import path from "node:path";
import { expect, test as base, type Page } from "@playwright/test";
import { API_BASE_URL } from "../helpers/e2e-env";

/**
 * The console in its PERSONAL shape (VEGADUTA_EDITION=personal). Run it with
 * `npx playwright test -c playwright.personal.config.ts` against a personal stack (see that file for the
 * environment). Under the default config it is skipped: E2E_EDITION is only set by the personal config, and
 * the Keycloak-stack suites around it must not see it fail.
 *
 * The lists below are written out on purpose instead of imported from web/app/lib/edition.ts: this suite is the
 * independent oracle for "what a personal install does not have", so a route dropped from the web table by
 * mistake fails here. A guard test at the end compares the two files so the lists cannot silently drift.
 */
const PERSONAL = process.env.E2E_EDITION === "personal";
const OWNER_EMAIL = process.env.E2E_PERSONAL_EMAIL || "";
const OWNER_PASSWORD = process.env.E2E_PERSONAL_PASSWORD || "";
const HAS_OWNER = !!(OWNER_EMAIL && OWNER_PASSWORD);
const OWNER_IS_PLATFORM_ADMIN = process.env.E2E_PERSONAL_PLATFORM_ADMIN === "1";

const SKIP_REASON = "personal-edition suite: run it with playwright.personal.config.ts against a personal stack";

/**
 * Pages a personal install answers with a real HTTP 404 (proxy.js, rows with deny:true in EDITION_ROUTES).
 * A nested path is included for the rows that own a subtree.
 */
const DENIED_404 = [
  "/admin/sso",
  "/shared-with-me",
  "/admin/agent-trust",
  "/admin/api-keys",
  "/admin/access",
  "/admin/users",
  "/roi",
  "/admin/payments",
  "/admin/commerce",
  "/directory",
  "/directory/some-agent",
  "/studio",
  "/admin/leads",
  "/admin/branding",
  "/admin/mcp-servers",
  "/view-as",
  "/signup",
  "/try",
  "/templates/mine",
  "/.well-known/agent-card.json",
  "/.well-known/agent.json",
  // The multi-tenant operator console: everything outside the personal allowlist.
  "/platform-admin/tenants",
  "/platform-admin/users",
  "/platform-admin/billing",
  "/platform-admin/spend",
  "/platform-admin/fleet",
  "/platform-admin/ops",
  "/platform-admin/impersonation",
];

/**
 * Billing routes are redirected, never rendered: the billing switch (lib/billing-flag.ts) answers first with a
 * 307, so they are deliberately not 404 (the plan's "hidden URLs return 404" does not hold for these two).
 */
const BILLING_REDIRECTS: Array<{ path: string; to: string }> = [
  { path: "/admin/billing", to: "/usage" },
  { path: "/features", to: "/" },
  { path: "/billing", to: "/" },
];

/** Pages folded into /assistant on a personal install: neither 404 nor rendered, a redirect. */
const FOLDED_INTO_ASSISTANT = ["/playground", "/quick-task", "/jobs"];

/** Rail destinations a personal install must not list (rail, Ctrl+K and the platform-admin-free console). */
const HIDDEN_FROM_RAIL = [
  "/admin/sso",
  "/shared-with-me",
  "/admin/agent-trust",
  "/admin/api-keys",
  "/admin/access",
  "/admin/users",
  "/admin/billing",
  "/billing",
  "/features",
  "/roi",
  "/admin/payments",
  "/admin/commerce",
  "/directory",
  "/studio",
  "/admin/leads",
  "/admin/branding",
  "/admin/mcp-servers",
  ...FOLDED_INTO_ASSISTANT,
];

/** English labels (web/messages/en.json, nav.rail*) of the hidden rail destinations, for Ctrl+K. */
const HIDDEN_LABELS = [
  "Single sign-on",
  "Shared with me",
  "Agent trust",
  "API keys",
  "Team access",
  "Billing",
  "Plans & Features",
  "Cost & ROI",
  "Agent payments",
  "Agent commerce",
  "Agent Directory",
  "Studio",
  "Leads",
  "Branding",
  "MCP access",
];

/** Rail destinations that every install keeps; a rail that lists none of them was not read correctly. */
const KEPT_RAIL = ["/assistant", "/agents", "/workflows", "/knowledge"];

/** Channels whose adapters core does not run on a personal install (SOCIAL_CHANNELS). */
const HIDDEN_CHANNELS = ["slack", "discord", "teams", "x"];
/** Channels a personal install keeps in the grid. */
const KEPT_CHANNELS = ["telegram", "whatsapp"];

/** The platform-admin pages a personal install keeps. */
const PLATFORM_ADMIN_ALLOW = [
  "/platform-admin/llm-providers",
  "/platform-admin/tool-credentials",
  "/platform-admin/settings",
];

/**
 * `owner`: a page whose browser context is signed in as the owner. Sign-in goes through the console's own
 * same-origin proxy (/api/local-auth/login), which keeps the refresh token in an HttpOnly cookie that the next
 * page load turns into a session, exactly as the sign-in form does. The locale cookie pins the English labels.
 */
const test = base.extend<{ owner: Page }>({
  owner: async ({ page, baseURL }, use) => {
    await page.context().addCookies([{ name: "NEXT_LOCALE", value: "en", url: baseURL || "http://localhost:3000" }]);
    const res = await page.request.post("/api/local-auth/login", {
      data: { identifier: OWNER_EMAIL, password: OWNER_PASSWORD },
    });
    expect(res.status(), "owner sign-in through /api/local-auth/login (is first-run setup finished?)").toBe(200);
    await use(page);
  },
});

test.describe("personal edition: signed out", () => {
  test.skip(!PERSONAL, SKIP_REASON);

  for (const p of DENIED_404) {
    test(`${p} answers HTTP 404`, async ({ request }) => {
      const res = await request.get(p, { maxRedirects: 0 });
      expect(res.status(), `GET ${p}`).toBe(404);
    });
  }

  test("a locale-prefixed denied route is 404 too (the prefix is stripped first)", async ({ request }) => {
    for (const p of ["/hi/signup", "/hi/directory"]) {
      expect((await request.get(p, { maxRedirects: 0 })).status(), `GET ${p}`).toBe(404);
    }
  });

  for (const { path: p, to } of BILLING_REDIRECTS) {
    test(`${p} is redirected to ${to}, never rendered`, async ({ request }) => {
      const res = await request.get(p, { maxRedirects: 0 });
      expect(res.status(), `GET ${p}`).toBe(307);
      expect(new URL(res.headers()["location"], "http://placeholder.invalid").pathname).toBe(to);
    });
  }

  test("the pages a personal install does have are not 404", async ({ request }) => {
    for (const p of ["/login", "/assistant", "/agents", "/usage", "/robots.txt", ...PLATFORM_ADMIN_ALLOW]) {
      const res = await request.get(p, { maxRedirects: 0 });
      expect(res.status(), `GET ${p}`).not.toBe(404);
      expect(res.status(), `GET ${p}`).toBeLessThan(500);
    }
  });

  test("robots.txt disallows everything and names no sitemap; the sitemap lists nothing", async ({ request }) => {
    const robots = await (await request.get("/robots.txt")).text();
    expect(robots).toMatch(/^Disallow:\s*\/\s*$/m);
    expect(robots).not.toMatch(/^Sitemap:/im);
    const sitemap = await request.get("/sitemap.xml");
    if (sitemap.ok()) expect(await sitemap.text()).not.toContain("<loc>");
  });

  test("core reports the personal tier on /api/public/status", async ({ request }) => {
    const res = await request.get(`${API_BASE_URL}/api/public/status`);
    expect(res.status()).toBe(200);
    const body = (await res.json()) as {
      edition?: { tier: string; billingEnabled: boolean; authMode: string; features: Record<string, boolean> };
    };
    expect(body.edition, "status.edition (core lean-edition contract)").toBeTruthy();
    expect(body.edition).toMatchObject({ tier: "personal", billingEnabled: false, authMode: "local" });
    const features = body.edition!.features;
    // cloud-speech is an add-on a personal owner may have switched on (env + restart), so it is not asserted.
    for (const f of [
      "billing-payments",
      "social-channels",
      "a2a-directory",
      "mcp-server-exposure",
      "operator-console",
      "voice-realtime-share",
      "video-demo-media",
    ]) {
      expect(features[f], `feature ${f}`).toBe(false);
    }
  });
});

test.describe("personal edition: signed in as the owner", () => {
  test.skip(!PERSONAL, SKIP_REASON);
  test.skip(!HAS_OWNER, "set E2E_PERSONAL_EMAIL and E2E_PERSONAL_PASSWORD (the owner after first-run setup)");

  test("folded pages land on /assistant", async ({ owner }) => {
    for (const p of FOLDED_INTO_ASSISTANT) {
      await owner.goto(p);
      await expect(owner, `${p} should end on /assistant`).toHaveURL(/\/assistant(\?|$|\/)/);
    }
  });

  test("the rail lists none of the hidden destinations", async ({ owner }) => {
    await owner.goto("/assistant");
    const nav = owner.getByRole("navigation", { name: "Primary" });
    await expect(nav).toBeVisible();
    const hrefs = new Set<string>(await railHrefs(owner));
    for (const keep of KEPT_RAIL) {
      expect([...hrefs].some((h) => h === keep || h.startsWith(`${keep}/`)), `rail should still list ${keep}`).toBe(true);
    }
    for (const hidden of HIDDEN_FROM_RAIL) {
      const hit = [...hrefs].filter((h) => h === hidden || h.startsWith(`${hidden}/`));
      expect(hit, `rail must not link ${hidden}`).toEqual([]);
    }
  });

  test("the Jump to… palette lists none of the hidden destinations", async ({ owner }) => {
    await owner.goto("/assistant");
    await expect(owner.getByRole("navigation", { name: "Primary" })).toBeVisible();
    // Ctrl+K on /assistant opens the Assistant's own command palette (actions and prompts), not the
    // navigation palette this test inspects, so open the navigation one from its top-bar button.
    await owner.getByRole("button", { name: "Jump to…" }).click();
    const options = owner.getByRole("listbox", { name: "Jump to…" }).getByRole("option");
    await expect(options.first()).toBeVisible();
    // The first span of an option is its label; the second (if any) is its group.
    const labels = (await options.evaluateAll((els) => els.map((el) => el.querySelector("span")?.textContent?.trim() ?? ""))).filter(Boolean);
    expect(labels.length, "palette rows").toBeGreaterThan(3);
    for (const hidden of HIDDEN_LABELS) {
      expect(labels, `Ctrl+K must not offer "${hidden}"`).not.toContain(hidden);
    }
  });

  test("the channels grid has no Slack, Discord, Teams or X", async ({ owner }) => {
    await owner.goto("/admin/channels");
    const cards = owner.getByTestId("channel-card");
    await expect(cards.first()).toBeVisible();
    const keys = await cards.evaluateAll((els) => els.map((el) => el.getAttribute("data-channel") ?? ""));
    for (const hidden of HIDDEN_CHANNELS) expect(keys, `channel ${hidden}`).not.toContain(hidden);
    for (const kept of KEPT_CHANNELS) expect(keys, `channel ${kept}`).toContain(kept);
  });

  test("/usage has no top-up card and no link to /roi", async ({ owner }) => {
    await owner.goto("/usage");
    await expect(owner.getByRole("link", { name: "Insights" }).first()).toBeVisible();
    await expect(owner.getByTestId("budget-topup")).toHaveCount(0);
    await expect(owner.locator('a[href="/roi"]')).toHaveCount(0);
    await expect(owner.getByText("Top up budget")).toHaveCount(0);
  });

  test("a hidden page opened as the owner is a 404, not the app", async ({ owner }) => {
    const res = await owner.goto("/directory");
    expect(res?.status()).toBe(404);
  });
});

test.describe("personal edition: platform-admin allowlist", () => {
  test.skip(!PERSONAL, SKIP_REASON);
  test.skip(!HAS_OWNER, "set E2E_PERSONAL_EMAIL and E2E_PERSONAL_PASSWORD");
  test.skip(!OWNER_IS_PLATFORM_ADMIN, "set E2E_PERSONAL_PLATFORM_ADMIN=1 when the owner holds the platform-admin role");

  test("/platform-admin offers only LLM providers, tool credentials and settings", async ({ owner }) => {
    await owner.goto("/platform-admin");
    await expect(owner).toHaveURL(/\/platform-admin\/llm-providers/);
    const nav = owner.getByRole("navigation", { name: "Platform admin sections" });
    await expect(nav).toBeVisible();
    const hrefs = await nav.locator("a[href]").evaluateAll((els) => els.map((a) => (a as HTMLAnchorElement).getAttribute("href") ?? ""));
    const adminHrefs = hrefs.filter((h) => h.startsWith("/platform-admin/"));
    expect(adminHrefs.length).toBeGreaterThan(0);
    for (const h of adminHrefs) {
      expect(
        PLATFORM_ADMIN_ALLOW.some((a) => h === a || h.startsWith(`${a}/`)),
        `platform-admin rail links ${h}, outside the allowlist`
      ).toBe(true);
    }
    for (const a of PLATFORM_ADMIN_ALLOW) expect(adminHrefs, `allowlist entry ${a}`).toContain(a);
  });
});

test.describe("personal edition: the lists above match the web route table", () => {
  test.skip(!PERSONAL, SKIP_REASON);

  test("every deny row in web/app/lib/edition.ts is covered here", async () => {
    const file = path.resolve(__dirname, "../../../web/app/lib/edition.ts");
    test.skip(!fs.existsSync(file), "web/app/lib/edition.ts is not next to this suite (tests/e2e copied out of the repo)");
    const src = fs.readFileSync(file, "utf8");
    // rail("/x", ...) and hidden("/x", ...) are deny rows; row("/x", ...) rows say deny:false or redirectTo.
    const routes = [...src.matchAll(/^\s*(?:rail|hidden)\(\s*"(\/[^"]+)"/gm)].map((m) => m[1]);
    expect(routes.length, "parsed deny rows from edition.ts").toBeGreaterThan(10);
    // /features is a rail() row too, but the billing switch redirects it before the proxy's 404 can answer.
    const covered = [...DENIED_404, ...BILLING_REDIRECTS.map((b) => b.path)];
    for (const r of routes) {
      expect(covered, `edition.ts denies ${r}; add it to DENIED_404 (or BILLING_REDIRECTS)`).toContain(r);
    }
    // And the other way round: every rail destination hidden here is a row there.
    // (/billing comes from lib/billing-flag.ts, not from the edition table.)
    for (const r of HIDDEN_FROM_RAIL.filter((h) => !FOLDED_INTO_ASSISTANT.includes(h) && h !== "/billing")) {
      expect(src, `${r} is hidden from the rail here but not in edition.ts`).toContain(`"${r}"`);
    }
    const allow = [...src.matchAll(/^\s*"(\/platform-admin\/[^"]+)",?\s*$/gm)].map((m) => m[1]);
    for (const a of allow) expect(PLATFORM_ADMIN_ALLOW, `edition.ts allows ${a}`).toContain(a);
  });
});

/**
 * Every href the rail can show: the links in the Primary nav (a classic rail renders them all, even in collapsed
 * groups), plus, in the hub rail, the links of each section's flyout (opened by hovering its glyph).
 */
async function railHrefs(page: Page): Promise<string[]> {
  const nav = page.getByRole("navigation", { name: "Primary" });
  const collect = (loc: ReturnType<Page["locator"]>) =>
    loc.evaluateAll((els) => els.map((a) => (a.getAttribute("href") ?? "").split(/[?#]/)[0]).filter(Boolean));
  const found = new Set<string>(await collect(nav.locator("a[href]")));
  const glyphs = nav.locator('button[aria-haspopup="menu"]');
  const count = await glyphs.count();
  for (let i = 0; i < count; i++) {
    await glyphs.nth(i).hover();
    const menu = page.getByRole("menu").first();
    await menu.waitFor({ state: "attached", timeout: 2_000 }).catch(() => undefined);
    for (const h of await collect(page.getByRole("menu").locator("a[href]"))) found.add(h);
  }
  return [...found];
}
