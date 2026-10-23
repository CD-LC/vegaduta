import { test, expect, type Locator, type Page } from "@playwright/test";
import { ACADEMY_BASE_URL, AUTH_HOST, SITE_MODE } from "../helpers/e2e-env";

// Clicks a header nav-anchor link and asserts it lands on the right #hash
// AND that the target section is actually in the viewport - retrying the
// whole click+assert cycle (not just the final assertion) if it doesn't
// land right the first time.
//
// Why a retry of the CLICK, not just the assertion: the target section runs
// its own scroll-triggered entrance animation on top of native smooth
// scrolling, and instrumenting real runs showed the resulting scroll
// occasionally stalls partway (observed final scrollY landing anywhere from
// 0 up to the full target depending on the run) rather than reliably
// reaching the anchor - i.e. the flake is in how the click's scroll
// resolves, not merely in when we happen to sample it. A bounded number of
// full retries (re-click, re-check) converges reliably in practice and
// mirrors how an actual user would react to a nav click that visibly did
// nothing - they'd click again, not wait.
async function clickAnchorAndAssertInViewport(page: Page, link: Locator, hash: string): Promise<void> {
  await expect(async () => {
    await link.click();
    await expect(page).toHaveURL(new RegExp(`#${hash}$`), { timeout: 3_000 });
    await expect(page.locator(`#${hash}`)).toBeInViewport({ timeout: 3_000 });
  }).toPass({ timeout: 20_000 });
}

// 2026-09-17: clickScenarioTabAndAssertVisible (WhatsApp "Demo scenario"
// tablist helper) removed - the ChannelDemoTabs band it drove is unmounted.

// Logged-out root ("/") has two landings, picked by the web build's
// NEXT_PUBLIC_SITE_MODE (web/app/lib/siteMode.ts, rendered by GenesisExperience):
//   - "oss" (the default, and what the compose stack / nightly serve): OssLanding
//     (web/app/components/oss-landing/): the technical open-source page.
//   - "classic": DemoLanding (web/app/components/demo-landing/): the hosted
//     marketing page - hero-outcome, sticky nav, Capability Atlas.
// The two describes below each run only against their own mode; set
// E2E_SITE_MODE=classic when the target serves the classic landing (see
// helpers/e2e-env.ts). Everything on either page is static or a client-side
// simulation - no backend calls.
//
// The "Get started" CTA is the historically-buggy one: it once routed to the
// Keycloak sign-in instead of the self-service signup wizard.

test.describe("Landing page (classic site mode)", () => {
  test.skip(SITE_MODE !== "classic", "serves the classic landing only (E2E_SITE_MODE=classic)");

  // The nav's text links (Live demo / How it works / Docs, LandingNav.tsx
  // NAV_LINK_ITEMS) are NOT a fixed-breakpoint `hidden lg:flex`
  // row - they're measured (DemoLanding.tsx's linksFit useLayoutEffect)
  // against the real rendered width of the row, and only shown inline when
  // they verifiably fit; anything else falls back to a "Menu" hamburger
  // button. A 2026-08-30 E2E-triage audit found the row did NOT fit at this
  // exact 1280x800 viewport (a measured 217px/36% shortfall) - that was
  // fixed the same day (DemoLanding.tsx: tighter nav-item padding/gap, the
  // Academy pill's mobile-compact treatment applied at every width, "Docs"
  // folded to icon-only) and reverified live: at 1280x800 the row now fits
  // and NO hamburger button renders at all. The tests below assert the
  // inline row directly - do not reintroduce a hamburger-click path here
  // without first confirming live (this file's own history shows why:
  // asserting against whichever layout happens to be broken today, instead
  // of the documented design intent, just re-breaks the next time someone
  // fixes it). Pin this describe block to a desktop viewport regardless, so
  // these tests exercise one deterministic layout regardless of which
  // Playwright project (desktop/mobile) runs the file - mobile-specific
  // rendering is covered
  // separately in visual.spec.ts.
  test.use({ viewport: { width: 1280, height: 800 } });

  test.beforeEach(async ({ page }) => {
    await page.goto("/");
  });

  test("renders the hero and sticky nav", async ({ page }) => {
    await expect(page.getByRole("link", { name: "VegaDūta home" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    // HeroOutcome.tsx: its testid and the h1 copy (messages/en.json landing h5_h1).
    await expect(page.getByTestId("hero-outcome")).toBeVisible();
    await expect(page.getByRole("heading", { level: 1 })).toContainText(/Agents that act on their own/i);
    // Header row is Live demo / How it works / Docs (LandingNav.tsx NAV_LINK_ITEMS;
    // Docs is icon-only with an sr-only label); "Who it's for" and "Security" live only in the Menu
    // sheet now, so they are no longer asserted here. Scoped to the header so
    // we don't also match identical-text footer links. No hamburger click
    // needed - the row fits inline at this viewport (see the describe block's
    // own comment); no "Menu" button renders at all when it does.
    const header = page.locator("header");
    await expect(header.getByRole("button", { name: "Menu" })).toHaveCount(0);
    await expect(header.getByRole("link", { name: "Live demo" })).toBeVisible();
    await expect(header.getByRole("link", { name: "How it works" })).toBeVisible();
    await expect(header.getByRole("link", { name: "Docs" })).toBeVisible();
  });

  test("nav anchors scroll to their sections", async ({ page }) => {
    const header = page.locator("header");

    // 2026-09-17: #audience-showcase / #senses / #security were unmounted on
    // 2026-09-12 and left the header row; "How it works" now targets
    // OutcomesBand (#outcomes) and "Live demo" targets #live-studio.
    await clickAnchorAndAssertInViewport(page, header.getByRole("link", { name: "How it works" }), "outcomes");
    await clickAnchorAndAssertInViewport(page, header.getByRole("link", { name: "Live demo" }), "live-studio");
  });

  test("Academy link points at the academy host, not this app", async ({ page }) => {
    const academyLink = page.locator("header").getByRole("link", { name: /academy/i });
    await expect(academyLink).toBeVisible();
    const href = await academyLink.getAttribute("href");
    expect(href).toBeTruthy();
    // The Academy is a separate deployable on its own host (E2E_ACADEMY_URL).
    expect(href!.replace(/\/$/, "")).toBe(ACADEMY_BASE_URL);
    // Guard against any accidental localhost fallback leaking into a build
    // meant for a real domain.
    expect(href).not.toContain("localhost");
  });

  // 2026-09-17: replaces the two WhatsApp "Demo scenario" tab tests - the
  // ChannelDemoTabs band is unmounted. The Capability Atlas
  // (CapabilityAtlas.tsx) is now the page's interactive, content-switching
  // widget: typing in the search box filters the tile grid, and clicking a
  // tile swaps the #atlas-detail card. Same intent as before: the control
  // must genuinely change the rendered content, not just relabel itself.
  test("capability atlas filters and opens a tile", async ({ page }) => {
    const atlas = page.getByTestId("capability-atlas");
    const tiles = page.locator("[data-testid^=atlas-tile-]");
    const search = page.getByTestId("atlas-search");
    await expect(atlas).toBeVisible();
    // An unfiltered grid shows every tile in CapabilityAtlas.tsx's TILES; read the count rather than
    // pinning it, so adding a tile does not break this test.
    await expect(tiles.first()).toBeVisible();
    const total = await tiles.count();
    expect(total).toBeGreaterThan(1);

    // Filter: "guardrail" matches a strict subset (title/body/where/category
    // are all searched - at least the "guardrails" tile itself).
    // The fill is repeated inside the retry: on a cold dev server React can
    // hydrate the atlas AFTER the first fill, which leaves the DOM value set
    // but React's state empty (2026-09-17, seen with workers=2 on localhost).
    await expect(async () => {
      await search.fill("guardrail");
      const n = await tiles.count();
      expect(n).toBeGreaterThanOrEqual(1);
      expect(n).toBeLessThan(total);
    }).toPass({ timeout: 30_000 });

    // Clear the filter, then open the "Learning from feedback" tile
    // (atlas_learning_title in messages/en.json) and check the detail card
    // actually switched to it.
    await search.fill("");
    await expect(tiles).toHaveCount(total);
    await page.getByTestId("atlas-tile-learning").click();
    await expect(page.locator("#atlas-detail")).toContainText("Learning from feedback");
  });

  test('"Get started" goes to the signup wizard, not the sign-in page', async ({ page }) => {
    // Regression test: this CTA once routed to Keycloak sign-in instead of
    // the self-service signup wizard. Assert both the URL AND that a signup
    // form (not a login form) actually rendered.
    // 2026-09-17: header CTA reads "Create free account" (nav_getStarted) or
    // "Sign up" (nav_getStartedShort at compact widths) - still routes to /signup.
    await page.locator("header").getByRole("link", { name: /Create free account|Sign up/ }).click();

    await expect(page).toHaveURL(/\/signup$/);
    // Must NOT have bounced to the Keycloak-hosted login page.
    expect(page.url()).not.toContain(AUTH_HOST);
    expect(page.url()).not.toContain("/protocol/openid-connect/");

    // 2026-09-17: signup/page.tsx renders "Create your free workspace." (intro
    // step) or "Name your workspace." (details step) as the step h2 - accept
    // either step; the old "Establish the" / "Register now" copy is gone.
    await expect(page.getByRole("heading", { name: /Create your free workspace|Name your workspace/i })).toBeVisible();
  });

  test("footer renders legal links", async ({ page }) => {
    const footer = page.locator("footer");
    await expect(footer.getByRole("link", { name: "Terms of Service" })).toBeVisible();
    await expect(footer.getByRole("link", { name: "Privacy Policy" })).toBeVisible();
  });
});

// The default landing (OssLanding.tsx + OssChrome.tsx).
test.describe("Landing page (oss site mode)", () => {
  test.skip(SITE_MODE !== "oss", "serves the OSS landing only (E2E_SITE_MODE unset or oss)");
  // The header nav is `hidden md:flex`: pin a desktop viewport so one layout is exercised in both projects.
  test.use({ viewport: { width: 1280, height: 800 } });

  test.beforeEach(async ({ page }) => {
    await page.goto("/");
  });

  test("renders the hero, the install commands and the header nav", async ({ page }) => {
    await expect(page.getByRole("link", { name: "VegaDūta home" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 1 })).toContainText(/the complete suite for AI agents/i);
    // The hero's install block (ossSite.ts INSTALL_COMMANDS).
    await expect(page.locator("main pre").first()).toContainText("scripts/vegaduta up --lite");
    const nav = page.getByRole("navigation", { name: "Primary" });
    for (const name of ["Docs", "Downloads", "Dependencies", "Plugins & apps", "Changelog", "About", "GitHub"]) {
      await expect(nav.getByRole("link", { name }), name).toBeVisible();
    }
  });

  test("the Dependencies link scrolls to its section", async ({ page }) => {
    const nav = page.getByRole("navigation", { name: "Primary" });
    await clickAnchorAndAssertInViewport(page, nav.getByRole("link", { name: "Dependencies" }), "dependencies");
  });

  test("Academy link points at the academy host, not this app", async ({ page }) => {
    const academyLink = page.locator("header").getByRole("link", { name: /academy/i });
    await expect(academyLink).toBeVisible();
    const href = await academyLink.getAttribute("href");
    expect(href).toBeTruthy();
    // The Academy is a separate deployable on its own host (E2E_ACADEMY_URL).
    expect(href!.replace(/\/$/, "")).toBe(ACADEMY_BASE_URL);
    expect(href).not.toContain("localhost");
  });

  test('"Get started" goes to the signup wizard, not the sign-in page', async ({ page }) => {
    await page.locator("header").getByRole("link", { name: "Get started" }).click();

    await expect(page).toHaveURL(/\/signup$/);
    // Must NOT have bounced to the Keycloak-hosted login page.
    expect(page.url()).not.toContain(AUTH_HOST);
    expect(page.url()).not.toContain("/protocol/openid-connect/");
    await expect(page.getByRole("heading", { name: /Create your free workspace|Name your workspace/i })).toBeVisible();
  });

  test("footer renders legal links", async ({ page }) => {
    const footer = page.locator("footer");
    await expect(footer.getByRole("link", { name: "Terms", exact: true })).toBeVisible();
    await expect(footer.getByRole("link", { name: "Privacy", exact: true })).toBeVisible();
  });
});
