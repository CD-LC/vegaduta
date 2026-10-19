import { test, expect } from "@playwright/test";
import { PUBLIC_PAGES, DETAIL_PAGES } from "../helpers/prod-routes";
import { collectProdErrors } from "../helpers/prod-console";
import { assertLocalhostFree, FOREIGN_DOMAIN, DOMAIN } from "../helpers/prod-env";

/**
 * Every public page on production renders its OWN content.
 *
 * The title+h1 pair is the real assertion here. A Next.js SPA returns HTTP
 * 200 for essentially any path (the shell always renders), so a status-code
 * check proves nothing - and this app has a specific historical failure mode
 * where a route served the correct <title> in <head> but the LANDING PAGE as
 * its body (the 2026-07-30 content-onpage audit on /help and /features).
 * Asserting both catches that class of bug; asserting either alone does not.
 */
// 2026-09-17: the landing was rebuilt (HeroOutcome); its h1 no longer reads
// "Eyes. Ears. Voice. Hands." Overridden here rather than in the shared
// helper, which is outside this change's scope.
// 2026-09-22 free-mode pivot (platform.free-mode.enabled=true): the hero h1
// changed again, and /billing became a single free-tier page with its own
// title - both re-verified live against a deployed instance.
const H1_OVERRIDES: Record<string, RegExp> = {
  "/": /Describe the job\. (Ship the agent|Get an agent you can trust with it)/i, // 2026-09-28 copy pass
  // 2026-09-25: /billing copy changed again - "Open source. Free for
  // everyone." now (re-verified live; same free-mode page).
  // Free or paid mode (PUBLIC_FREE_MODE) - accept either.
  "/billing": /open source|free for everyone|everything is free|pricing/i,
};
const TITLE_OVERRIDES: Record<string, RegExp> = {
  "/billing": /Free.*open source|Pricing/i,
};

test.describe("Public pages render their own content", () => {
  for (const pageDef of PUBLIC_PAGES.map((p) => ({
    ...p,
    ...(H1_OVERRIDES[p.path] ? { h1: H1_OVERRIDES[p.path] } : {}),
    ...(TITLE_OVERRIDES[p.path] ? { title: TITLE_OVERRIDES[p.path] } : {}),
  }))) {
    test(`${pageDef.path} renders`, async ({ page }) => {
      const errors = collectProdErrors(page);

      const response = await page.goto(pageDef.path, { waitUntil: "domcontentloaded" });
      expect(response, `no response for ${pageDef.path}`).toBeTruthy();
      expect(response!.status(), `${pageDef.path} HTTP status`).toBeLessThan(400);

      await expect(page).toHaveTitle(pageDef.title);

      // Some routes hydrate their heading client-side; under parallel load
      // against real production, domcontentloaded alone can sample too early.
      await page.waitForLoadState("load", { timeout: 15_000 }).catch(() => {});

      const h1 = page.getByRole("heading", { level: 1 }).first();
      await expect(h1, `${pageDef.path} must render an <h1>`).toBeVisible();
      await expect(h1).toHaveText(pageDef.h1);

      // Next.js error boundaries / framework crash screens.
      await expect(page.getByText(/Application error: a client-side exception/i)).toHaveCount(0);
      await expect(page.getByText(/This page could not be found/i)).toHaveCount(0);

      // Known-open production issues are excluded (and documented) in
      // helpers/prod-console.ts, so this stays red for NEW regressions
      // rather than being permanently red for already-reported ones.
      const unexpected = errors.unexpected();
      expect(unexpected, `${pageDef.path} produced unexpected errors:\n${unexpected.join("\n")}`).toEqual([]);
    });
  }

  for (const path of DETAIL_PAGES) {
    test(`${path} (detail page) renders`, async ({ page }) => {
      const response = await page.goto(path, { waitUntil: "domcontentloaded" });
      expect(response!.status()).toBeLessThan(400);
      await expect(page.getByRole("heading", { level: 1 }).first()).toBeVisible();
      await expect(page.getByText(/This page could not be found/i)).toHaveCount(0);
    });
  }
});

test.describe("Domain hygiene (RULE 0)", () => {
  // A base-only `docker compose build web` bakes localhost into NEXT_PUBLIC_*
  // and re-breaks sign-in. This is the automated guard against that image
  // ever reaching production unnoticed.
  test("no page links to localhost or the other environment's domain", async ({ page }) => {
    const sampled = ["/", "/billing", "/signup", "/templates", "/ai", "/docs"];

    for (const path of sampled) {
      await page.goto(path, { waitUntil: "domcontentloaded" });

      const hrefs = await page.locator("a[href]").evaluateAll((els) =>
        els.map((el) => (el as HTMLAnchorElement).getAttribute("href") || "")
      );
      const joined = hrefs.join("\n");

      assertLocalhostFree(joined, `${path} anchor hrefs`);

      const foreign = hrefs.filter((h) => h.includes(FOREIGN_DOMAIN));
      expect(
        foreign,
        `${path} links to ${FOREIGN_DOMAIN} while serving ${DOMAIN} - wrong-environment build?`
      ).toEqual([]);
    }
  });

  test("the Academy link points at this environment's academy host", async ({ page }) => {
    await page.goto("/");
    // Header nav collapses into a menu on mobile, so assert on the link's
    // presence anywhere in the page chrome rather than its visibility.
    const academyLink = page.locator('header a[href*="academy."], footer a[href*="academy."]').first();
    await expect(academyLink).toBeAttached({ timeout: 15_000 });

    const href = await academyLink.getAttribute("href");
    expect(href).toContain(`academy.${DOMAIN}`);
    assertLocalhostFree(href, "Academy link");
  });
});
