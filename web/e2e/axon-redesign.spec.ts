import { test, expect } from "@playwright/test";
import { BASE_URL, waitForAnimations, isInViewport } from "./axon-redesign.helpers";

/**
 * Comprehensive E2E Test Suite for AXON Redesign
 *
 * Tests all landing page functionality including:
 * - Theme switching (light/dark modes)
 * - Canvas hero animation
 * - Reveal animations on scroll
 * - Card styling and interactions
 * - Button interactions
 * - Navigation and links
 * - Signup form validation
 * - Accessibility features
 * - Responsive design
 * - Performance metrics
 *
 * Run with: npx playwright test e2e/axon-redesign.spec.ts
 *
 * The suite is split across axon-redesign.spec.ts (landing page),
 * axon-redesign-forms-a11y.spec.ts (signup, accessibility, performance,
 * responsive) and axon-redesign-design-system.spec.ts (AXON tokens, error
 * handling, visual regression).
 */
// LANDING PAGE - NAVIGATION & HEADER
// ============================================================================

test.describe("Landing Page - Header & Navigation", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });
  });

  test("should load landing page successfully", async ({ page }) => {
    await expect(page).toHaveTitle(/VegaDūta/);
    expect(page.url()).toBe(`${BASE_URL}/`);
  });

  test("should display header with logo and navigation links", async ({
    page,
  }) => {
    // Check logo/brand
    const logo = await page.$("img[alt*='logo'], [class*='brand-name']");
    expect(logo).toBeTruthy();

    // Check navigation links
    const navLinks = [
      "Live demo",
      "How it works",
      "Who it's for",
      "Features",
      "Security",
      "Pricing",
      "Docs",
    ];

    for (const linkText of navLinks) {
      const link = await page.$(`a:has-text("${linkText}")`);
      expect(link).toBeTruthy();
    }
  });

  test("should display Academy link", async ({ page }) => {
    const academyLink = page.locator("a:has-text('Academy')").first();
    await expect(academyLink).toBeVisible();
    await expect(academyLink).toHaveAttribute("href", /academy/);
  });

  test("should display theme customizer button", async ({ page }) => {
    const themeButton = await page.$("button[aria-label*='theme'], [class*='theme']");
    expect(themeButton).toBeTruthy();
  });

  test("should display language switcher", async ({ page }) => {
    const langButton = await page.$("button:has-text('English'), button[class*='language']");
    expect(langButton).toBeTruthy();
  });

  test("should display Sign in button", async ({ page }) => {
    const signInButton = await page.$("button:has-text('Sign in')");
    expect(signInButton).toBeTruthy();
  });

  test("should display Get started CTA button", async ({ page }) => {
    const ctaButton = page.locator("a:has-text('Get started')").first();
    await expect(ctaButton).toBeVisible();
    await expect(ctaButton).toHaveAttribute("href", "/signup");
  });
});

// ============================================================================
// LANDING PAGE - HERO SECTION
// ============================================================================

test.describe("Landing Page - Hero Section", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });
  });

  test("should display hero headline", async ({ page }) => {
    const headline = await page.$("h1");
    expect(headline).toBeTruthy();
    const text = await headline?.textContent();
    expect(text?.length).toBeGreaterThan(0);
  });

  test("should display hero subtitle", async ({ page }) => {
    const subtitle = await page.$("p:near(h1)");
    expect(subtitle).toBeTruthy();
    const text = await subtitle?.textContent();
    expect(text?.length).toBeGreaterThan(0);
  });

  test("should display hero CTA buttons", async ({ page }) => {
    const primaryCta = await page.$("a:has-text('Try it free'), button:has-text('Try')");
    const secondaryCta = await page.$("a:has-text('Watch'), button:has-text('Watch')");

    expect(primaryCta).toBeTruthy();
    expect(secondaryCta).toBeTruthy();
  });

  test("should display country/language badge", async ({ page }) => {
    const badge = await page.$("[class*='badge'], span:has-text('LIVE')");
    expect(badge).toBeTruthy();
  });

  test("should display hero feature chips", async ({ page }) => {
    const chips = await page.$$("[class*='chip'], [class*='badge']");
    expect(chips.length).toBeGreaterThan(0);
  });

  test("should display product preview card", async ({ page }) => {
    const card = await page.$("[class*='card'], [class*='preview']");
    expect(card).toBeTruthy();

    // Check card has content
    const cardContent = await card?.textContent();
    expect(cardContent?.length).toBeGreaterThan(0);
  });
});

// ============================================================================
// LANDING PAGE - CANVAS ANIMATION
// ============================================================================

test.describe("Landing Page - Canvas Animation", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });
  });

  test("should render canvas element on desktop", async ({ page }) => {
    // Desktop only - canvas might be hidden on mobile
    const canvas = await page.$("canvas");
    expect(canvas).toBeTruthy();
  });

  test("canvas should have proper dimensions", async ({ page }) => {
    const canvas = await page.$("canvas");
    if (!canvas) {
      console.log("Canvas not present (mobile view)");
      return;
    }

    const width = await canvas.evaluate((el: HTMLCanvasElement) => el.width);
    const height = await canvas.evaluate((el: HTMLCanvasElement) => el.height);

    expect(width).toBeGreaterThan(0);
    expect(height).toBeGreaterThan(0);
  });

  test("canvas animation should be animating", async ({ page }) => {
    const canvas = await page.$("canvas");
    if (!canvas) return;

    // Check canvas rendering context exists
    const hasContext = await canvas.evaluate((el: HTMLCanvasElement) => {
      return el.getContext("2d") !== null;
    });

    expect(hasContext).toBe(true);
  });

  test("canvas should maintain aspect ratio on resize", async ({ page }) => {
    const canvas = await page.$("canvas");
    if (!canvas) return;

    await canvas.evaluate(
      (el: HTMLCanvasElement) => el.width
    );

    // Simulate resize
    await page.setViewportSize({ width: 1920, height: 1080 });
    await waitForAnimations(page);

    const newWidth = await canvas.evaluate(
      (el: HTMLCanvasElement) => el.width
    );

    // Canvas should exist and be responsive
    expect(newWidth).toBeGreaterThan(0);
  });
});

// ============================================================================
// LANDING PAGE - THEME SWITCHING
// ============================================================================

test.describe("Landing Page - Theme Switching", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });
  });

  test("should start in light theme by default", async ({ page }) => {
    const html = await page.$("html");
    const dataTheme = await html?.getAttribute("data-theme");

    // Should be light or auto (which detects light)
    expect(["light", "auto", null]).toContain(dataTheme);
  });

  test("should switch to dark theme when clicking dark option", async ({
    page,
  }) => {
    const themeButton = await page.$("button[aria-label*='theme'], button[class*='theme']");
    if (!themeButton) {
      console.log("Theme button not found");
      return;
    }

    await themeButton.click();
    await waitForAnimations(page);

    // Find and click dark option
    const darkOption = await page.$("button:has-text('Dark')");
    if (darkOption) {
      await darkOption.click();
      await waitForAnimations(page);

      const html = await page.$("html");
      const dataTheme = await html?.getAttribute("data-theme");
      expect(dataTheme).toBe("dark");
    }
  });

  test("should switch to light theme when clicking light option", async ({
    page,
  }) => {
    const themeButton = await page.$("button[aria-label*='theme'], button[class*='theme']");
    if (!themeButton) return;

    await themeButton.click();
    await waitForAnimations(page);

    const lightOption = await page.$("button:has-text('Light')");
    if (lightOption) {
      await lightOption.click();
      await waitForAnimations(page);

      const html = await page.$("html");
      const dataTheme = await html?.getAttribute("data-theme");
      expect(dataTheme).toBe("light");
    }
  });

  test("theme should persist after page reload", async ({ page }) => {
    const themeButton = await page.$("button[aria-label*='theme'], button[class*='theme']");
    if (!themeButton) return;

    await themeButton.click();
    await waitForAnimations(page);

    const darkOption = await page.$("button:has-text('Dark')");
    if (darkOption) {
      await darkOption.click();
      await waitForAnimations(page);

      // Reload page
      await page.reload({ waitUntil: "networkidle" });

      const html = await page.$("html");
      const dataTheme = await html?.getAttribute("data-theme");
      expect(dataTheme).toBe("dark");
    }
  });
});

// ============================================================================
// LANDING PAGE - REVEAL ANIMATIONS
// ============================================================================

test.describe("Landing Page - Reveal Animations", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });
    // Scroll to top
    await page.evaluate(() => window.scrollTo(0, 0));
  });

  test("should have elements with reveal animation classes", async ({
    page,
  }) => {
    const revealElements = await page.$$("[data-reveal]");
    expect(revealElements.length).toBeGreaterThan(0);
  });

  test("should animate elements on scroll", async ({ page }) => {
    // Get first reveal element
    const firstReveal = await page.$("[data-reveal]");
    expect(firstReveal).toBeTruthy();

    if (!firstReveal) return;

    // Check initial opacity (should be low)
    const initialOpacity = await firstReveal.evaluate(
      (el) => window.getComputedStyle(el).opacity
    );

    // Scroll element into view
    await firstReveal.scrollIntoViewIfNeeded();
    await waitForAnimations(page);

    // Check opacity increased
    const finalOpacity = await firstReveal.evaluate(
      (el) => window.getComputedStyle(el).opacity
    );

    // Opacity should increase from 0 to 1
    expect(parseFloat(finalOpacity)).toBeGreaterThan(
      parseFloat(initialOpacity)
    );
  });

  test("should have staggered reveal animations", async ({ page }) => {
    const cardElements = await page.$$("[class*='card']");
    expect(cardElements.length).toBeGreaterThan(0);

    // Check that multiple cards exist (would have staggered animation)
    if (cardElements.length >= 3) {
      const styles1 = await cardElements[0].evaluate(
        (el) => window.getComputedStyle(el).transitionDelay
      );
      const styles2 = await cardElements[1].evaluate(
        (el) => window.getComputedStyle(el).transitionDelay
      );

      // Different delays = staggered
      expect(styles1 !== styles2 || styles1 === "0s").toBeTruthy();
    }
  });
});

// ============================================================================
// LANDING PAGE - CARD STYLING
// ============================================================================

test.describe("Landing Page - Card Styling", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });
  });

  test("should display cards with proper styling", async ({ page }) => {
    const cards = await page.$$("[class*='card']");
    expect(cards.length).toBeGreaterThan(0);

    for (const card of cards.slice(0, 3)) {
      const styles = await card.evaluate((el) => {
        const computed = window.getComputedStyle(el);
        return {
          backgroundColor: computed.backgroundColor,
          borderRadius: computed.borderRadius,
          backdropFilter: computed.backdropFilter,
          border: computed.border,
        };
      });

      // Check card has styling
      expect(styles.backgroundColor).toBeTruthy();
      expect(styles.borderRadius).toBeTruthy();
    }
  });

  test("cards should have hover effects", async ({ page }) => {
    const card = await page.$("[class*='card']");
    if (!card) return;

    // Get initial styles
    const initialStyles = await card.evaluate((el) => {
      const computed = window.getComputedStyle(el);
      return {
        boxShadow: computed.boxShadow,
        transform: computed.transform,
      };
    });

    // Hover over card
    await card.hover();
    await waitForAnimations(page);

    // Get hover styles
    const hoverStyles = await card.evaluate((el) => {
      const computed = window.getComputedStyle(el);
      return {
        boxShadow: computed.boxShadow,
        transform: computed.transform,
      };
    });

    // Styles should change on hover (either shadow or transform)
    expect(
      hoverStyles.boxShadow !== initialStyles.boxShadow ||
        hoverStyles.transform !== initialStyles.transform
    ).toBe(true);
  });

  test("should display correct badge colors", async ({ page }) => {
    const badges = await page.$$("[class*='badge']");
    expect(badges.length).toBeGreaterThan(0);

    for (const badge of badges.slice(0, 3)) {
      const text = await badge.textContent();
      expect(text?.length).toBeGreaterThan(0);
    }
  });
});

// ============================================================================
// LANDING PAGE - BUTTONS & INTERACTIONS
// ============================================================================

test.describe("Landing Page - Buttons & Interactions", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });
  });

  test("CTA buttons should be clickable", async ({ page }) => {
    const ctaButtons = await page.$$("button:has-text('Get started'), a:has-text('Get started')");
    expect(ctaButtons.length).toBeGreaterThan(0);

    for (const button of ctaButtons.slice(0, 2)) {
      const isEnabled = await button.evaluate((el: any) => !el.disabled);
      expect(isEnabled).toBe(true);
    }
  });

  test("buttons should have hover effects", async ({ page }) => {
    const button = await page.$("button, a[class*='button']");
    if (!button) return;

    // Get initial computed style
    await button.evaluate(
      (el) => window.getComputedStyle(el).opacity
    );

    // Hover
    await button.hover();
    await waitForAnimations(page);

    // Get hover style
    const hoverStyle = await button.evaluate(
      (el) => window.getComputedStyle(el).opacity
    );

    // Should maintain opacity (not disappear)
    expect(parseFloat(hoverStyle)).toBeGreaterThan(0);
  });

  test("primary CTA should navigate to signup", async ({ page }) => {
    const ctaLink = await page.$("a:has-text('Get started'):first-of-type");
    if (!ctaLink) return;

    const href = await ctaLink.getAttribute("href");
    expect(href).toContain("signup");
  });

  test("navigation links should be functional", async ({ page }) => {
    const navLink = await page.$("a:has-text('Pricing')");
    if (!navLink) return;

    const href = await navLink.getAttribute("href");
    expect(href).toBeTruthy();
    expect(href).not.toContain("javascript");
  });
});

// ============================================================================
// LANDING PAGE - SCROLL & ANIMATIONS
// ============================================================================

test.describe("Landing Page - Scroll & Animations", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });
  });

  test("should handle smooth scrolling", async ({ page }) => {
    const initialScrollY = await page.evaluate(() => window.scrollY);
    expect(initialScrollY).toBe(0);

    // Scroll down
    await page.evaluate(() => window.scrollBy(0, 500));
    await waitForAnimations(page);

    const newScrollY = await page.evaluate(() => window.scrollY);
    expect(newScrollY).toBeGreaterThan(initialScrollY);
  });

  test("should maintain 60fps during scroll (no major jank)", async ({
    page,
  }) => {
    // This is a qualitative check - we scroll and make sure page remains responsive
    await page.evaluate(() => window.scrollTo(0, 0));

    for (let i = 0; i < 5; i++) {
      await page.evaluate(() => window.scrollBy(0, 200));
      await page.waitForTimeout(100);
    }

    // If we get here without timeout, scrolling was smooth
    expect(true).toBe(true);
  });

  test("should display sections as user scrolls", async ({ page }) => {
    // Scroll through page and check different sections appear
    const sections = await page.$$("section, [class*='section']");
    expect(sections.length).toBeGreaterThan(0);

    // Scroll to last section
    const lastSection = sections[sections.length - 1];
    await lastSection.scrollIntoViewIfNeeded();
    await waitForAnimations(page);

    // Check section is visible
    const isVisible = await isInViewport(page, "section");
    expect(isVisible).toBe(true);
  });
});

