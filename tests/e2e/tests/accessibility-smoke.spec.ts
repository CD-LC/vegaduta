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

async function assertImagesHaveAlt(page: Page) {
  const imgs = page.locator("img");
  const count = await imgs.count();
  for (let i = 0; i < count; i++) {
    const img = imgs.nth(i);
    const alt = await img.getAttribute("alt");
    const src = await img.getAttribute("src");
    expect(alt, `<img src="${src}"> is missing an alt attribute`).not.toBeNull();
  }
}

async function assertExactlyOneH1(page: Page) {
  await expect(page.locator("h1")).toHaveCount(1);
}

async function assertButtonsHaveAccessibleNames(page: Page) {
  const buttons = page.locator("button");
  const count = await buttons.count();
  for (let i = 0; i < count; i++) {
    const button = buttons.nth(i);
    const name = await button.evaluate((el) => {
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
    });
    const outerHtmlSnippet = await button.evaluate((el) => el.outerHTML.slice(0, 120));
    expect(name, `Button has no accessible name: ${outerHtmlSnippet}`).not.toBe("");
  }
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
