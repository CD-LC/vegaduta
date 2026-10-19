import { test, expect } from "@playwright/test";
import { RECIPE_INDUSTRIES } from "../helpers/prod-routes";
import { DOMAIN, skipIfOssSite, LOCALHOST_HOST_RE } from "../helpers/prod-env";

/**
 * The four SEO/AEO documentation hubs (/integrations, /ai, /platform,
 * /tutorials), the comparison pages, and the industry workflow-recipe
 * library. These are the crawlable, server-rendered marketing surface -
 * their whole value is that a crawler and an LLM can read real content, so
 * "renders a shell" is a failure even when it returns 200.
 */
test.describe("SEO/AEO content hubs", () => {
  const HUBS = [
    { path: "/integrations", h1: /Every channel and service/i },
    { path: "/ai", h1: /AI stack behind every VegaD/i },
    { path: "/platform", h1: /.+/ },
    { path: "/tutorials", h1: /.+/ },
  ];

  for (const hub of HUBS) {
    test(`${hub.path} hub renders substantive content and links to its children`, async ({ page }) => {
      await page.goto(hub.path, { waitUntil: "domcontentloaded" });

      await expect(page.getByRole("heading", { level: 1 })).toHaveText(hub.h1);

      // Substance check: a hub page with a couple of hundred characters is a
      // shell, not content.
      const text = await page.locator("main, body").first().innerText();
      expect(text.replace(/\s+/g, " ").length, `${hub.path} rendered too little content to be a real hub`).toBeGreaterThan(800);

      // Every hub is an index - it must link somewhere within itself.
      const childLinks = await page
        .locator(`a[href^="${hub.path}/"]`)
        .evaluateAll((els) => els.map((e) => (e as HTMLAnchorElement).getAttribute("href")));
      expect(childLinks.length, `${hub.path} links to no child pages`).toBeGreaterThan(0);
    });
  }

  test("the /ai hub does not swallow the authenticated /ai-models route", async ({ page }) => {
    // Segment-aware public-route matching (2026-08-14): under the old bare
    // startsWith rule "/ai" would have made "/ai-models" public too. This is
    // the regression guard for that exact bug.
    await page.goto("/ai-models", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

    const body = await page.locator("body").innerText();
    expect(body, "/ai-models rendered the public /ai hub - public-route matching regressed").not.toMatch(
      /The AI stack behind every VegaD/i
    );
  });

  test("comparison pages render a real comparison", async ({ page }, testInfo) => {
    skipIfOssSite(testInfo);
    await page.goto("/compare", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

    const links = await page
      .locator('a[href^="/compare/"]')
      .evaluateAll((els) => els.map((e) => (e as HTMLAnchorElement).getAttribute("href")!));
    expect(links.length, "/compare lists no comparison pages").toBeGreaterThan(0);

    await page.goto(links[0], { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    const text = await page.locator("body").innerText();
    expect(text.length).toBeGreaterThan(600);
  });
});

test.describe("Workflow recipe library", () => {
  test("the recipe index renders its industry catalogue", async ({ page }) => {
    await page.goto("/help/workflow-recipes", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

    const body = await page.locator("body").innerText();
    expect(body, "recipe index does not advertise its industry count").toMatch(/\d+\s+INDUSTRIES/i);
    expect(body, "recipe index names no industries").toMatch(/accounting|telecom|aviation|automotive/i);
  });

  test("the recipe index links to its industry pages", async ({ page }) => {
    await page.goto("/help/workflow-recipes", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

    const links = await page
      .locator('a[href*="/help/workflow-recipes/"]')
      .evaluateAll((els) => els.map((e) => (e as HTMLAnchorElement).getAttribute("href")!));
    expect(links.length, "recipe index lists no crawlable industry links").toBeGreaterThan(5);
  });

  for (const industry of RECIPE_INDUSTRIES) {
    test(`recipe page /${industry} renders`, async ({ page }) => {
      const response = await page.goto(`/help/workflow-recipes/${industry}`, { waitUntil: "domcontentloaded" });
      expect(response!.status()).toBeLessThan(400);

      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      await expect(page.getByText(/This page could not be found/i)).toHaveCount(0);

      const text = await page.locator("body").innerText();
      expect(text.replace(/\s+/g, " ").length, `${industry} recipe page is a stub`).toBeGreaterThan(500);
    });
  }

  test("recipe pages render standalone chrome without an auth crash", async ({ page }) => {
    // These are public routes rendered outside AppShell (no AuthContext) -
    // the documented failure mode is a crash when a component reaches for
    // auth that isn't there.
    await page.goto("/help/workflow-recipes/healthcare", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});
    await expect(page.getByText(/Application error: a client-side exception/i)).toHaveCount(0);
  });
});

test.describe("Standalone tools", () => {
  test("the WhatsApp cost calculator computes a result", async ({ page }, testInfo) => {
    skipIfOssSite(testInfo);
    await page.goto("/whatsapp-cost-calculator", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

    // The calculator's headline output is a monthly rupee figure. Capture
    // the rendered figures, change an input to a value it does NOT already
    // hold, and assert the figures moved.
    //
    // (The earlier version of this test diffed whole-body text after filling
    // an input with its existing default - nothing changed, so it timed out
    // while the calculator was working perfectly.)
    const readFigures = async () => ((await page.locator("body").innerText()).match(/₹[\d,]+/g) || []).join("|");

    const numberInput = page.locator('input[type="number"]').first();
    await expect(numberInput).toBeVisible();
    let before = "";
    // Under parallel load hydration can stall or drop the first events. Each
    // attempt re-reads the baseline, uses real Playwright fill() (native
    // events), and reloads the page between attempts so a wedged hydration is
    // retried from scratch rather than polled forever.
    let attempt = 0;
    await expect(async () => {
      attempt++;
      if (attempt > 1) await page.reload({ waitUntil: "domcontentloaded" });
      await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});
      before = await readFigures();
      expect(before, "calculator rendered no rupee figures at all").not.toBe("");
      await numberInput.fill(String(7000 + attempt * 100));
      await expect
        .poll(readFigures, { timeout: 4_000, message: "changing conversation volume did not change any figure" })
        .not.toBe(before);
    }).toPass({ timeout: 60_000, intervals: [500] });

    const body = await page.locator("body").innerText();
    expect(body, "calculator shows no currency figure").toMatch(/₹|\$/);
    expect(body).not.toMatch(/NaN|undefined/);
  });

  test("the docs surface renders and stays on-domain", async ({ page }) => {
    await page.goto("/docs", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

    const hrefs = await page
      .locator("a[href]")
      .evaluateAll((els) => els.map((e) => (e as HTMLAnchorElement).getAttribute("href") || ""));
    const bad = hrefs.filter((h) => LOCALHOST_HOST_RE.test(h));
    expect(bad, "docs links to localhost").toEqual([]);
    expect(hrefs.some((h) => h.startsWith("/") || h.includes(DOMAIN))).toBe(true);
  });
});
