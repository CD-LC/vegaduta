import { test, expect, type Page } from "@playwright/test";

// Deliberately lightweight - no axe-core or any other a11y-scanning
// dependency (the brief asks for "light checks only, no new deps"). Three
// checks that catch real, common regressions cheaply:
//   1. every <img> has an `alt` attribute (missing alt = screen readers get
//      nothing, or in some browsers the filename/URL is read aloud)
//   2. exactly one <h1> (zero = no page landmark for assistive tech /
//      SEO; more than one = a broken document outline)
//   3. every <button> has a non-empty accessible name (an icon-only
//      button with no aria-label is unusable by a screen-reader user)

// Both element checks run in ONE page.evaluate over a snapshot of the DOM, not as a
// locator.count() followed by locator.nth(i) round trips: the page re-renders as it
// hydrates, so an index that existed at count() time could be gone by the time it was
// read and the check timed out on a locator instead of reporting a real defect.
async function assertImagesHaveAlt(page: Page) {
  const missing = await page.evaluate(() =>
    Array.from(document.querySelectorAll("img"))
      .filter((img) => !img.hasAttribute("alt"))
      .map((img) => img.getAttribute("src") ?? "(no src)")
  );
  expect(missing, `<img> without an alt attribute: ${missing.join(", ")}`).toEqual([]);
}

async function assertExactlyOneH1(page: Page) {
  await expect(page.locator("h1")).toHaveCount(1);
}

async function assertButtonsHaveAccessibleNames(page: Page) {
  const unnamed = await page.evaluate(() => {
    const nameOf = (el: Element): string => {
      const ariaLabel = el.getAttribute("aria-label");
      if (ariaLabel && ariaLabel.trim()) return ariaLabel.trim();

      const labelledBy = el.getAttribute("aria-labelledby");
      if (labelledBy) {
        const labelText = labelledBy
          .split(/\s+/)
          .map((id) => document.getElementById(id)?.textContent?.trim() || "")
          .join(" ")
          .trim();
        if (labelText) return labelText;
      }

      const text = el.textContent?.trim();
      if (text) return text;

      const title = el.getAttribute("title");
      if (title && title.trim()) return title.trim();

      return "";
    };
    return Array.from(document.querySelectorAll("button"))
      .filter((button) => nameOf(button) === "")
      .map((button) => button.outerHTML.slice(0, 120));
  });
  expect(unnamed, `Buttons with no accessible name: ${unnamed.join(" | ")}`).toEqual([]);
}

const PAGES = [
  { name: "landing (/)", path: "/" },
  { name: "signup (/signup)", path: "/signup" },
  { name: "terms (/terms)", path: "/terms" },
  { name: "privacy (/privacy)", path: "/privacy" },
];

for (const { name, path } of PAGES) {
  test.describe(`Accessibility smoke - ${name}`, () => {
    test.beforeEach(async ({ page }) => {
      await page.goto(path);
    });

    test("every image has an alt attribute", async ({ page }) => {
      await assertImagesHaveAlt(page);
    });

    test("exactly one h1", async ({ page }) => {
      await assertExactlyOneH1(page);
    });

    test("every button has an accessible name", async ({ page }) => {
      await assertButtonsHaveAccessibleNames(page);
    });
  });
}
