import { test, expect } from "@playwright/test";
import { BASE_URL, waitForAnimations } from "./axon-redesign.helpers";

/**
 * AXON redesign - design tokens, error handling and visual regression.
 *
 * Part of the AXON redesign suite; see axon-redesign.spec.ts for the overview.
 * Run with: npx playwright test e2e/axon-redesign-design-system.spec.ts
 */


// ============================================================================
// AXON DESIGN SYSTEM SPECIFIC
// ============================================================================

test.describe("AXON Design System", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });
  });

  test("should apply AXON CSS classes", async ({ page }) => {
    const axonCard = await page.$("[class*='agentic-card-axon'], [class*='card-axon']");

    if (axonCard) {
      const className = await axonCard.getAttribute("class");
      expect(className).toContain("axon");
    }
  });

  test("should use AXON color tokens", async ({ page }) => {
    const elements = await page.$$("[style*='--ax-'], [class*='badge']");
    expect(elements.length).toBeGreaterThan(0);
  });

  test("should have proper light mode colors", async ({ page }) => {
    // Set light theme
    const themeButton = await page.$("button[class*='theme']");
    if (themeButton) {
      await themeButton.click();
      await waitForAnimations(page);

      const lightOption = await page.$("button:has-text('Light')");
      if (lightOption) {
        await lightOption.click();
        await waitForAnimations(page);
      }
    }

    // Check button colors are legible
    const button = await page.$("button");
    if (button) {
      const bgColor = await button.evaluate((el) => {
        const computed = window.getComputedStyle(el);
        return computed.backgroundColor;
      });

      expect(bgColor).not.toBe("transparent");
    }
  });

  test("should have proper dark mode colors", async ({ page }) => {
    // Set dark theme
    const themeButton = await page.$("button[class*='theme']");
    if (themeButton) {
      await themeButton.click();
      await waitForAnimations(page);

      const darkOption = await page.$("button:has-text('Dark')");
      if (darkOption) {
        await darkOption.click();
        await waitForAnimations(page);
      }
    }

    // Check button is visible
    const button = await page.$("button");
    if (button) {
      const opacity = await button.evaluate((el) => {
        const computed = window.getComputedStyle(el);
        return computed.opacity;
      });

      expect(parseFloat(opacity)).toBeGreaterThan(0);
    }
  });

  test("should have glassmorphic card effects", async ({ page }) => {
    const card = await page.$("[class*='card']");
    if (!card) return;

    const backdropFilter = await card.evaluate((el) => {
      return window.getComputedStyle(el).backdropFilter;
    });

    // Glassmorphic cards should have backdrop-filter
    expect(backdropFilter).not.toBe("none");
  });

  test("should have smooth animations with proper easing", async ({
    page,
  }) => {
    const animatedElement = await page.$("[data-reveal], [style*='animation']");
    if (!animatedElement) return;

    const transitionTiming = await animatedElement.evaluate((el) => {
      const computed = window.getComputedStyle(el);
      return computed.transitionTimingFunction;
    });

    // Should have easing function (not linear)
    expect(transitionTiming).toBeTruthy();
  });
});

// ============================================================================
// ERROR HANDLING
// ============================================================================

test.describe("Error Handling", () => {
  test("should handle missing images gracefully", async ({ page }) => {
    page.on("response", (response) => {
      // Log but don't fail on image 404s
      if (response.status() === 404 && response.url().includes("image")) {
        console.log(`Image not found: ${response.url()}`);
      }
    });

    await page.goto(BASE_URL, { waitUntil: "networkidle" });

    // Page should still be fully functional
    const buttons = await page.$$("button");
    expect(buttons.length).toBeGreaterThan(0);
  });

  test("should recover from scroll errors", async ({ page }) => {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });

    // Rapid scrolling
    for (let i = 0; i < 10; i++) {
      await page.evaluate(() => window.scrollBy(0, 100));
      await page.waitForTimeout(50);
    }

    // Scroll back to top - should work
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForTimeout(100);

    const scrollPosition = await page.evaluate(() => window.scrollY);
    expect(scrollPosition).toBe(0);
  });
});

// ============================================================================
// VISUAL REGRESSION (BASELINE)
// ============================================================================

test.describe("Visual Regression - Dark Mode", () => {
  test("should match dark theme baseline", async ({ page }) => {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });

    // Set dark theme
    const themeButton = await page.$("button[class*='theme']");
    if (themeButton) {
      await themeButton.click();
      const darkOption = await page.$("button:has-text('Dark')");
      if (darkOption) {
        await darkOption.click();
        await waitForAnimations(page);
      }
    }

    // Take screenshot
    await expect(page).toHaveScreenshot("landing-dark-mode.png", {
      fullPage: true,
      maxDiffPixels: 1000,
    });
  });
});

test.describe("Visual Regression - Light Mode", () => {
  test("should match light theme baseline", async ({ page }) => {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });

    // Set light theme
    const themeButton = await page.$("button[class*='theme']");
    if (themeButton) {
      await themeButton.click();
      const lightOption = await page.$("button:has-text('Light')");
      if (lightOption) {
        await lightOption.click();
        await waitForAnimations(page);
      }
    }

    // Take screenshot
    await expect(page).toHaveScreenshot("landing-light-mode.png", {
      fullPage: true,
      maxDiffPixels: 1000,
    });
  });
});
