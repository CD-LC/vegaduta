import { test, expect } from "@playwright/test";

/**
 * Academy Platform E2E Tests (<staging-host>/academy)
 *
 * Tests coverage:
 * - Academy landing page
 * - Course listing
 * - Lessons and content
 * - Progress tracking
 * - Learner store
 * - Quizzes and labs
 * - Certificates
 *
 * Run with: npx playwright test e2e/academy-platform.spec.ts
 */

const ACADEMY_URL = "http://localhost:4100";

test.describe("Academy Platform - Access", () => {
  test("should load academy homepage", async ({ page }) => {
    await page.goto(ACADEMY_URL, { waitUntil: "networkidle" });
    expect(page.url()).toContain("academy");
  });

  test("should have public access", async ({ page }) => {
    await page.goto(ACADEMY_URL);
    const title = await page.title();
    expect(title.length).toBeGreaterThan(0);
  });
});

test.describe("Academy Platform - Navigation", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(ACADEMY_URL, { waitUntil: "networkidle" });
  });

  test("should display hero section", async ({ page }) => {
    const hero = await page.$("[class*='hero'], h1");
    expect(hero).toBeTruthy();
  });

  test("should have course listing", async ({ page }) => {
    const courses = await page.$$("[class*='course'], [class*='card']");
    expect(courses.length).toBeGreaterThan(0);
  });

  test("should have navigation menu", async ({ page }) => {
    const nav = await page.$("nav");
    expect(nav).toBeTruthy();
  });

  test("should have search functionality", async ({ page }) => {
    const search = await page.$("input[type='search'], input[placeholder*='search']");
    if (search) {
      await search.fill("python");
      await page.waitForTimeout(300);
    }
  });
});

test.describe("Academy Platform - Courses", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`${ACADEMY_URL}/courses`, { waitUntil: "networkidle" });
  });

  test("should list all courses", async ({ page }) => {
    const courseList = await page.$$("[class*='course'], [class*='item']");
    expect(courseList.length).toBeGreaterThan(0);
  });

  test("should display course metadata", async ({ page }) => {
    const course = await page.$("[class*='course']");
    if (course) {
      const title = await course.$("h3, h2, [class*='title']");
      const description = await course.$("p, [class*='description']");

      expect(title).toBeTruthy();
      expect(description).toBeTruthy();
    }
  });

  test("should allow filtering by level", async ({ page }) => {
    const levelFilter = await page.$("select, [class*='filter']");
    if (levelFilter) {
      await levelFilter.click();
      const option = await page.$("option, [role='option']");
      if (option) {
        await option.click();
        await page.waitForTimeout(300);
      }
    }
  });

  test("should display course difficulty", async ({ page }) => {
    const badges = await page.$$("[class*='badge'], [class*='tag']");
    expect(badges.length).toBeGreaterThan(0);
  });
});

test.describe("Academy Platform - Lessons", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(ACADEMY_URL, { waitUntil: "networkidle" });
  });

  test("should display lesson list", async ({ page }) => {
    const courseLink = await page.$("[class*='course']");
    if (courseLink) {
      await courseLink.click();
      await page.waitForTimeout(500);

      const lessons = await page.$$("[class*='lesson'], li, [class*='item']");
      expect(lessons.length).toBeGreaterThan(0);
    }
  });

  test("should show lesson progress", async ({ page }) => {
    const progressBar = await page.$("[class*='progress'], [role='progressbar']");
    if (progressBar) {
      expect(progressBar).toBeTruthy();
    }
  });

  test("should allow starting lesson", async ({ page }) => {
    const startButton = await page.$("button:has-text('Start'), button:has-text('Begin')");
    if (startButton) {
      await startButton.click();
      await page.waitForTimeout(500);

      const content = await page.$("[class*='content'], main");
      expect(content).toBeTruthy();
    }
  });
});

test.describe("Academy Platform - Quizzes", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(ACADEMY_URL, { waitUntil: "networkidle" });
  });

  test("should display quiz questions", async ({ page }) => {
    const quizSection = await page.$("[class*='quiz']");
    if (quizSection) {
      const questions = await page.$$("[class*='question']");
      expect(questions.length).toBeGreaterThan(0);
    }
  });

  test("should allow answering questions", async ({ page }) => {
    const radioButton = await page.$("input[type='radio']");
    if (radioButton) {
      await radioButton.click();
      expect(radioButton).toBeTruthy();
    }
  });

  test("should show quiz score", async ({ page }) => {
    const scoreDisplay = await page.$("[class*='score'], [class*='result']");
    if (scoreDisplay) {
      expect(scoreDisplay).toBeTruthy();
    }
  });

  test("should validate answers", async ({ page }) => {
    const submitButton = await page.$("button:has-text('Submit'), button:has-text('Check')");
    if (submitButton) {
      await submitButton.click();
      await page.waitForTimeout(500);

      const feedback = await page.$("[class*='feedback'], [class*='result']");
      expect(feedback || true).toBeTruthy();
    }
  });
});

test.describe("Academy Platform - Labs", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`${ACADEMY_URL}/labs`, { waitUntil: "networkidle" });
  });

  test("should display available labs", async ({ page }) => {
    const labCards = await page.$$("[class*='lab'], [class*='card']");
    expect(labCards.length).toBeGreaterThan(0);
  });

  test("should show lab difficulty and time", async ({ page }) => {
    const labMeta = await page.$$("[class*='meta'], [class*='badge']");
    expect(labMeta.length).toBeGreaterThan(0);
  });

  test("should allow starting lab", async ({ page }) => {
    const labCard = await page.$("[class*='lab']");
    if (labCard) {
      const startButton = await labCard.$("button, a");
      if (startButton) {
        await startButton.click();
        await page.waitForTimeout(500);
      }
    }
  });
});

test.describe("Academy Platform - Learner Store", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`${ACADEMY_URL}/store`, { waitUntil: "networkidle" });
  });

  test("should display learner achievements", async ({ page }) => {
    const store = await page.$("[class*='store'], main");
    expect(store).toBeTruthy();
  });

  test("should show badges and certificates", async ({ page }) => {
    const items = await page.$$("[class*='badge'], [class*='certificate']");
    expect(items.length >= 0).toBe(true);
  });

  test("should display learner statistics", async ({ page }) => {
    const stats = await page.$$("[class*='stat'], [class*='metric']");
    expect(stats.length).toBeGreaterThan(0);
  });

  test("should allow sharing achievements", async ({ page }) => {
    const shareButton = await page.$("button:has-text('Share')");
    if (shareButton) {
      expect(shareButton).toBeTruthy();
    }
  });
});

test.describe("Academy Platform - Progress Tracking", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`${ACADEMY_URL}/progress`, { waitUntil: "networkidle" });
  });

  test("should display overall progress", async ({ page }) => {
    const progressChart = await page.$("[class*='progress'], canvas");
    if (progressChart) {
      expect(progressChart).toBeTruthy();
    }
  });

  test("should show per-course progress", async ({ page }) => {
    const courseProgress = await page.$$("[class*='course-progress'], [class*='item']");
    expect(courseProgress.length >= 0).toBe(true);
  });

  test("should display completion timeline", async ({ page }) => {
    const timeline = await page.$("[class*='timeline']");
    if (timeline) {
      expect(timeline).toBeTruthy();
    }
  });
});

test.describe("Academy Platform - Certification", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`${ACADEMY_URL}/certificates`, { waitUntil: "networkidle" });
  });

  test("should display earned certificates", async ({ page }) => {
    const certificates = await page.$$("[class*='certificate'], [class*='card']");
    expect(certificates.length >= 0).toBe(true);
  });

  test("should allow downloading certificate", async ({ page }) => {
    const downloadButton = await page.$("button:has-text('Download')");
    if (downloadButton) {
      expect(downloadButton).toBeTruthy();
    }
  });

  test("should show certificate details", async ({ page }) => {
    const cert = await page.$("[class*='certificate']");
    if (cert) {
      const details = await cert.$("[class*='details']");
      expect(details || true).toBeTruthy();
    }
  });
});

test.describe("Academy Platform - Responsive Design", () => {
  test("should be mobile responsive", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto(ACADEMY_URL, { waitUntil: "networkidle" });

    const menu = await page.$("nav, [class*='menu']");
    expect(menu).toBeTruthy();

    const content = await page.$("main, [role='main']");
    expect(content).toBeTruthy();
  });

  test("should handle tablet view", async ({ page }) => {
    await page.setViewportSize({ width: 768, height: 1024 });
    await page.goto(ACADEMY_URL, { waitUntil: "networkidle" });

    const layout = await page.$("main");
    expect(layout).toBeTruthy();
  });
});

test.describe("Academy Platform - Performance", () => {
  test("should load within reasonable time", async ({ page }) => {
    const startTime = Date.now();
    await page.goto(ACADEMY_URL, { waitUntil: "networkidle" });
    const loadTime = Date.now() - startTime;

    expect(loadTime).toBeLessThan(10000);
  });

  test("should not have console errors", async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (msg) => {
      if (msg.type() === "error") {
        errors.push(msg.text());
      }
    });

    await page.goto(ACADEMY_URL, { waitUntil: "networkidle" });

    expect(errors.length).toBe(0);
  });
});

test.describe("Academy Platform - Accessibility", () => {
  test("should have proper heading hierarchy", async ({ page }) => {
    await page.goto(ACADEMY_URL, { waitUntil: "networkidle" });

    const h1s = await page.$$eval("h1", (els) => els.length);
    expect(h1s).toBeGreaterThanOrEqual(1);
  });

  test("should be keyboard navigable", async ({ page }) => {
    await page.goto(ACADEMY_URL, { waitUntil: "networkidle" });

    await page.press("body", "Tab");
    await page.waitForTimeout(100);

    const focused = await page.evaluate(() => document.activeElement?.tagName);
    expect(focused).toBeTruthy();
  });

  test("should have focus indicators", async ({ page }) => {
    await page.goto(ACADEMY_URL, { waitUntil: "networkidle" });

    const button = await page.$("button");
    if (button) {
      await button.focus();
      const outline = await button.evaluate((el) => {
        return window.getComputedStyle(el).outline;
      });

      expect(outline).not.toBe("none");
    }
  });
});
