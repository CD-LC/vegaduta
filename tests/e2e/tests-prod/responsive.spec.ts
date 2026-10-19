import { test, expect } from "@playwright/test";

/**
 * Responsive + theming behaviour on the public surface.
 *
 * The horizontal-overflow check is the highest-value one here: it is the
 * single most common mobile regression on this codebase (a wide element
 * escaping its container makes the whole page pan sideways), and it is
 * invisible in any desktop-only test run.
 */
const PAGES = ["/", "/billing", "/signup", "/templates", "/ai", "/integrations", "/help/workflow-recipes"];

test.describe("Responsive layout", () => {
  for (const path of PAGES) {
    test(`${path} does not scroll horizontally on mobile`, async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 812 });
      await page.goto(path, { waitUntil: "domcontentloaded" });
      await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

      const overflow = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
      }));

      // A few px of tolerance for scrollbar/rounding differences.
      expect(
        overflow.scrollWidth,
        `${path} overflows horizontally at 375px (scrollWidth ${overflow.scrollWidth} vs viewport ${overflow.clientWidth})`
      ).toBeLessThanOrEqual(overflow.clientWidth + 2);
    });
  }

  test("the mobile landing page exposes navigation and a sign-in path", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto("/");

    const signIn = page
      .getByRole("button", { name: /sign in/i })
      .or(page.getByRole("link", { name: /sign in/i }));
    await expect(signIn.first(), "no sign-in affordance on mobile").toBeVisible();
  });

  test("tap targets on mobile are not impossibly small", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto("/");
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

    // 24px is a deliberately forgiving floor (WCAG 2.2 AA minimum) rather
    // than the 44px iOS guideline - this is a smoke test, not a design audit.
    const tiny = await page.locator("header button, header a").evaluateAll((els) =>
      els
        .filter((el) => {
          const r = el.getBoundingClientRect();
          return r.width > 0 && r.height > 0 && (r.width < 24 || r.height < 24);
        })
        .map((el) => {
          const r = el.getBoundingClientRect();
          return `${el.tagName.toLowerCase()} "${(el.textContent || "").trim().slice(0, 20)}" ${Math.round(r.width)}x${Math.round(r.height)}`;
        })
    );
    expect(tiny, `header tap targets below 24px on mobile:\n${tiny.join("\n")}`).toEqual([]);
  });
});

test.describe("Theming", () => {
  test("the page renders in dark mode without losing text contrast", async ({ page }) => {
    await page.emulateMedia({ colorScheme: "dark" });
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

    const colors = await page.evaluate(() => {
      const body = document.body;
      const style = window.getComputedStyle(body);
      return { background: style.backgroundColor, color: style.color };
    });

    // The specific bug this guards: a transparent/unset body background in
    // dark mode, which leaves dark text on a white host background.
    expect(colors.background, "body has no explicit background colour in dark mode").not.toBe("rgba(0, 0, 0, 0)");
    expect(colors.background).not.toBe("transparent");
    expect(colors.color, "body has no explicit text colour").toBeTruthy();
  });

  test("the page renders in light mode", async ({ page }) => {
    await page.emulateMedia({ colorScheme: "light" });
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  });
});
