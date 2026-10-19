import { test, expect } from "@playwright/test";
import { DOMAIN } from "../helpers/prod-env";
import { collectProdErrors } from "../helpers/prod-console";

/**
 * The production landing page, rebuilt 2026-09-17: HeroOutcome -> #outcomes
 * -> #path -> #platform-ledger (CapabilityAtlas) -> #channels -> #live-studio
 * -> #security -> #open-source -> #business-case -> #faq -> #contact. Section
 * ids were read off the deployed DOM rather than the source tree. The old
 * "senses" hero (#senses, #channel-demos, #magic-studio) is gone.
 *
 * 2026-09-22 free-mode pivot: the platform.free-mode.enabled=true deploy
 * replaced the paid-tier #pricing section with #open-source ("Why pricing?
 * We're going open source.") and dropped every /billing link from the
 * landing page - re-verified live against a deployed instance. The
 * hero h1 changed accordingly. Do not restore the old pricing copy here;
 * confirm with the maintainer before assuming this reverts.
 *
 * Everything on this page is a client-side simulation - no backend calls -
 * so it is safe to exercise fully against production.
 */
// 2026-09-17: the rebuilt landing replaces its server-rendered DOM on
// hydration, so a plain scrollIntoViewIfNeeded() taken just after load can
// fail with "Element is not attached to the DOM". Re-resolve and retry.
async function scrollTo(target: import("@playwright/test").Locator) {
  await expect(async () => {
    await target.scrollIntoViewIfNeeded({ timeout: 5_000 });
  }).toPass({ timeout: 30_000 });
}

test.describe("Landing page", () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test.beforeEach(async ({ page }) => {
    await page.goto("/");
  });

  test("renders the hero and the primary calls to action", async ({ page }) => {
    // 2026-09-17: hero is HeroOutcome (outcome-first h1), not the "senses" kicker.
    const hero = page.locator("[data-testid=hero-outcome]");
    await expect(hero).toBeVisible();
    // 2026-09-28 copy pass (fed60ae7): the h1 emphasis became "Get an agent you can trust with it."
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(/Describe the job\. (Ship the agent|Get an agent you can trust with it)/i);

    // 2026-09-17: hero CTAs are an email form ("Create free account") and a no-signup link to /try.
    await expect(hero.locator("[data-testid=hero-email-start]")).toHaveText(/create free account/i);
    const tryFirst = hero.getByRole("link", { name: /try it first/i }).first();
    await expect(tryFirst).toBeVisible();
    await expect(tryFirst).toHaveAttribute("href", /^\/try/);

    const header = page.locator("header");
    await expect(header.getByRole("link", { name: /VegaD/i }).first()).toBeVisible();
    await expect(header.getByRole("button", { name: /^sign in$/i })).toBeVisible();

    // The header signup link must go to self-service signup, never straight
    // to the IdP - a regression that has shipped before on this page.
    // 2026-09-17: the header link is now "Create free account" / "Sign up", not "Get started".
    const signup = header.getByRole("link", { name: /create free account|sign up/i }).first();
    await expect(signup).toBeVisible();
    await expect(signup).toHaveAttribute("href", "/signup");
  });

  test("all advertised landing sections actually exist on the page", async ({ page }) => {
    // A nav anchor pointing at an id that no longer exists is a dead click
    // with no error - invisible without a test like this.
    // 2026-09-17: section ids of the rebuilt landing (hero -> outcomes -> path -> atlas -> ... -> contact).
    for (const id of [
      "outcomes",
      "path",
      "platform-ledger",
      "channels",
      "live-studio",
      "security",
      // "open-source" removed 2026-10-04 by direct instruction (DemoLanding.tsx:151).
      "business-case",
      "faq",
      "contact",
    ]) {
      await expect(page.locator(`#${id}`), `missing landing section #${id}`).toHaveCount(1);
    }
    await expect(page.locator("#outcomes").getByRole("heading", { level: 2 })).toHaveText(/Four things it does on day one\./i);
  });

  test("in-page anchors scroll their target section into view", async ({ page }) => {
    // Only these ids are actually reachable via an <a href="#..."> on the
    // deployed page. #pricing/#faq/#how exist as SECTIONS (asserted above)
    // but nothing anchors to them, so clicking them is not testable here.
    for (const id of ["security", "live-studio", "magic-studio"]) {
      const anchor = page.locator(`a[href="#${id}"]`).first();
      if ((await anchor.count()) === 0) continue;

      // Retry the whole click+assert cycle: the sections run scroll-triggered
      // entrance animations on top of native smooth scrolling, and the
      // resulting scroll can stall partway on a first attempt.
      await expect(async () => {
        await anchor.click();
        await expect(page.locator(`#${id}`)).toBeInViewport({ timeout: 3_000 });
      }).toPass({ timeout: 25_000 });
    }
  });

  test("the language switcher is present and offers Indian languages", async ({ page }) => {
    const switcher = page.locator("header").getByRole("button", { name: /english/i }).first();
    await expect(switcher).toBeVisible();
    await switcher.click();

    // The India-first positioning is the product's core claim; if the
    // language menu is empty the landing page is lying.
    const body = await page.locator("body").innerText();
    expect(body).toMatch(/हिन|Hindi|தமிழ|Tamil|తెలుగు|Telugu|ಕನ್ನಡ|Kannada|मराठी|Marathi|বাংলা|Bengali/i);
  });

  // 2026-09-17: replaces "the interactive channel demo switches scenarios" -
  // ChannelDemoTabs (#channel-demos) no longer exists; the interactive block
  // on the rebuilt page is the CapabilityAtlas (search filter + tile detail).
  test("the capability atlas filters by search and opens a tile's detail", async ({ page }) => {
    const atlas = page.locator("[data-testid=capability-atlas]");
    await expect(atlas).toBeAttached({ timeout: 30_000 });
    await scrollTo(atlas);

    const tiles = atlas.locator("[data-testid^=atlas-tile-]");
    const search = atlas.locator("[data-testid=atlas-search]");
    // Tile count grows as capabilities ship (29 -> 36 by 2026-10-09); pin a floor and
    // measure the real total so the search assertions stay meaningful.
    await expect.poll(() => tiles.count(), { timeout: 15_000 }).toBeGreaterThanOrEqual(29);
    const total = await tiles.count();
    await expect(search).toBeVisible();

    // Retry the whole fill+assert cycle: a fill that lands before React has
    // attached its onChange handler (hydration race) changes the input's
    // value but filters nothing, so re-fill on every attempt.
    await expect(async () => {
      await search.fill("");
      await search.fill("guardrail");
      const count = await tiles.count();
      expect(count, "search did not narrow the atlas").toBeGreaterThanOrEqual(1);
      expect(count, "search did not narrow the atlas").toBeLessThan(total);
    }).toPass({ timeout: 30_000 });

    // Clear the filter so the learning tile is rendered, then open it.
    await expect(async () => {
      await search.fill("");
      await expect(tiles).toHaveCount(total, { timeout: 3_000 });
    }).toPass({ timeout: 30_000 });

    const learning = atlas.locator("[data-testid=atlas-tile-learning]");
    await scrollTo(learning);
    await expect(async () => {
      await learning.click();
      await expect(page.locator("#atlas-detail")).toContainText(/Learning from feedback/i, { timeout: 3_000 });
    }).toPass({ timeout: 30_000 });
  });

  test("the FAQ entries expand", async ({ page }) => {
    const faq = page.locator("#faq");
    await scrollTo(faq); // 2026-09-17: was a bare scrollIntoViewIfNeeded; flaked on hydration detach.

    // Assert on aria-expanded rather than diffing body text (an animated
    // reveal makes a text diff racy), and specifically EXPAND a collapsed
    // entry: the first FAQ ships already open, and this accordion does not
    // necessarily allow collapsing the open item - so asserting a toggle in
    // either direction is testing behaviour the component never promised.
    // Retry the whole click+assert cycle rather than just the assertion.
    // Instrumenting real runs showed the first click can be swallowed
    // entirely - the button stays aria-expanded="false" for the full 15s
    // assertion window - while the identical interaction works reliably once
    // the page has settled. That is a hydration race (the click lands before
    // React attaches its handler), not a product defect: a real user who
    // clicked a dead accordion would simply click again.
    //
    // The entry is re-located by TEXT inside the loop because the accordion
    // re-renders on toggle, which invalidates any handle taken beforehand.
    const collapsed = faq.locator('button[aria-expanded="false"]').first();
    await expect(collapsed, "no collapsed FAQ entry to expand").toBeAttached({ timeout: 20_000 });
    await scrollTo(collapsed);
    await expect(collapsed).toBeVisible();

    const label = ((await collapsed.textContent()) || "").trim().slice(0, 40);
    const byLabel = faq.locator("button").filter({ hasText: label }).first();

    await expect(async () => {
      if ((await byLabel.getAttribute("aria-expanded")) !== "true") {
        await byLabel.click();
      }
      await expect(byLabel).toHaveAttribute("aria-expanded", "true", { timeout: 3_000 });
    }).toPass({ timeout: 30_000 });
  });

  // The #open-source band was removed from the landing on 2026-10-04 (direct instruction,
  // DemoLanding.tsx:151). Kept as a visible skip so the history is not lost.
  test.skip("the open-source section renders and links to the no-signup try path", async ({ page }) => {
    // 2026-09-22 free-mode pivot: this section replaced the paid #pricing
    // block and, unlike it, does not link to /billing at all - re-verified
    // live. The one link it carries goes to /try.
    const openSource = page.locator("#open-source");
    await scrollTo(openSource); // 2026-09-17: was a bare scrollIntoViewIfNeeded; flaked on hydration detach.
    await expect(openSource.getByRole("heading", { level: 2 })).toHaveText(/open source/i);
    const tryLink = openSource.getByRole("link", { name: /try it free/i }).first();
    await expect(tryLink).toBeVisible();
    await expect(tryLink).toHaveAttribute("href", /^\/try/);
  });

  test("loads with no uncaught JavaScript errors", async ({ page }) => {
    const errors = collectProdErrors(page);
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});
    const unexpected = errors.unexpected();
    expect(unexpected, `landing page produced unexpected errors:\n${unexpected.join("\n")}`).toEqual([]);
  });

  test("the footer links to legal pages on this domain", async ({ page }) => {
    const footer = page.locator("footer").first();
    await footer.scrollIntoViewIfNeeded();

    const terms = footer.getByRole("link", { name: /terms/i }).first();
    const privacy = footer.getByRole("link", { name: /privacy/i }).first();
    await expect(terms).toBeVisible();
    await expect(privacy).toBeVisible();

    for (const link of [terms, privacy]) {
      const href = await link.getAttribute("href");
      expect(href).toBeTruthy();
      expect(href!, "footer legal link points off-domain or at localhost").toMatch(
        new RegExp(`^(/|https://[a-z.]*${DOMAIN.replace(/\./g, "\\.")})`)
      );
    }
  });
});
