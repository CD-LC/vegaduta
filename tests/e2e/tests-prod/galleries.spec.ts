import { test, expect } from "@playwright/test";
import { LOCALHOST_HOST_RE } from "../helpers/prod-env";

/**
 * The public galleries - community templates and the first-party plugin
 * marketplace. Unlike the marketing hubs these are backed by live API calls
 * (GET /api/templates/public, GET /api/plugins/catalog, both permitAll), so
 * they double as an end-to-end check that the API is reachable from the
 * browser on production: a CORS or gateway failure shows up here as an empty
 * gallery rather than as an error page.
 */
test.describe("Community templates gallery", () => {
  // /templates renders ~37 cards from a live API call and is measurably the
  // slowest public page on prod - it intermittently exceeds the suite's
  // default 45s navigation budget. Give this describe block more room rather
  // than reporting prod slowness as a functional failure.
  test.use({ navigationTimeout: 90_000 });
  test.setTimeout(120_000);

  test("loads real templates from the API", async ({ page }) => {
    await page.goto("/templates", { waitUntil: "domcontentloaded" });

    await expect(page.getByRole("heading", { level: 1 })).toHaveText(/community has built/i);

    // Cards are rendered from live data - an empty gallery means the public
    // API call failed, which is invisible from the HTTP status of the page.
    const cards = page.locator("article, [class*='card']");
    await expect(async () => {
      expect(await cards.count(), "templates gallery rendered no cards - public API call likely failed").toBeGreaterThan(0);
    }).toPass({ timeout: 30_000 });

    await expect(page.getByText(/failed to load|something went wrong/i)).toHaveCount(0);
  });

  test("category and type filters are present and change the result set", async ({ page }) => {
    await page.goto("/templates", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

    const body = await page.locator("body").innerText();
    expect(body).toMatch(/all types/i);
    expect(body).toMatch(/agents/i);
    expect(body).toMatch(/workflows/i);

    const before = await page.locator("main, body").first().innerText();
    const agentsFilter = page.getByRole("button", { name: /^agents$/i }).first();
    if ((await agentsFilter.count()) > 0) {
      await agentsFilter.click();
      await expect(async () => {
        expect(await page.locator("main, body").first().innerText()).not.toBe(before);
      }).toPass({ timeout: 20_000 });
    }
  });

  test("a logged-out visitor is prompted to sign in rather than shown an import action that silently fails", async ({
    page,
  }) => {
    await page.goto("/templates", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});
    const body = await page.locator("body").innerText();
    expect(body).toMatch(/sign in|start free/i);
  });
});

test.describe("Plugin marketplace", () => {
  test("loads the plugin catalog", async ({ page }) => {
    await page.goto("/plugins", { waitUntil: "domcontentloaded" });

    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await expect(page.getByText(/failed to load|something went wrong/i)).toHaveCount(0);

    const text = await page.locator("body").innerText();
    expect(text.replace(/\s+/g, " ").length, "plugin catalog rendered no content").toBeGreaterThan(400);
  });

  test("plugin download links do not point at localhost", async ({ page }) => {
    await page.goto("/plugins", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

    const hrefs = await page
      .locator("a[href]")
      .evaluateAll((els) => els.map((e) => (e as HTMLAnchorElement).getAttribute("href") || ""));
    expect(hrefs.filter((h) => LOCALHOST_HOST_RE.test(h))).toEqual([]);
  });
});

test.describe("Public share surfaces", () => {
  // /a/, /w/, /agent/, /workflow/ share routes are public by prefix. Without
  // a real share id the only safe, credential-free assertion is that an
  // invalid id fails cleanly instead of crashing or leaking another tenant's
  // data.
  // NOTE: "/workflow/" is deliberately NOT in this list. It is a real route
  // (web/app/workflow/[shareId]) but it is absent from PUBLIC_ROUTES, so a
  // logged-out visitor gets the marketing landing page instead of the share
  // viewer - covered as its own finding below.
  for (const prefix of ["/a", "/w", "/agent"]) {
    test(`${prefix}/<invalid-id> fails closed without a crash`, async ({ page }) => {
      await page.goto(`${prefix}/e2e-definitely-not-a-real-share-id`, { waitUntil: "domcontentloaded" });
      await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

      await expect(page.getByText(/Application error: a client-side exception/i)).toHaveCount(0);

      const body = await page.locator("body").innerText();

      // The security property: nothing from the (nonexistent) share is
      // rendered, and the viewer says so rather than showing a blank frame.
      //
      // Wording varies per viewer on prod, and one of them is misleading:
      //   /a      -> "We couldn't reach this agent just now - try again in a
      //              moment"  (TRANSIENT-sounding for a permanently bad id)
      //   /w      -> "This link doesn't point to a live workflow anymore"
      //   /agent  -> "This shared agent isn't available any more"
      // The /a wording is a real (minor) UX finding - see README-PROD.md. All
      // three fail closed, which is what this test guards.
      expect(
        /not found|expired|no longer|anymore|any more|isn't available|invalid|unavailable|couldn't find|couldn't reach|doesn't point|does not exist|try again/i.test(
          body
        ),
        `${prefix} share viewer neither reported an invalid id nor failed closed`
      ).toBe(true);
    });
  }

  test("/workflow/<shareId> serves the share viewer, not the landing page", async ({ page }) => {
    await page.goto("/workflow/e2e-definitely-not-a-real-share-id", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

    const body = await page.locator("body").innerText();
    expect(body, "/workflow/<id> fell through to the marketing landing page").not.toMatch(
      /Eyes\. Ears\. Voice\. Hands\./i
    );
  });
});
