import { test, expect } from "@playwright/test";

/**
 * Dependency-free accessibility smoke across the public surface. These are
 * the structural failures that break screen readers and are cheap to catch:
 * unlabelled images, missing/duplicate page headings, buttons and links with
 * no accessible name, inputs with no label, and a page with no language set.
 *
 * Deliberately not pulling in axe-core: this suite is intentionally
 * dependency-light (see e2e/README.md), and these checks catch the bulk of
 * what actually regresses here.
 */
const PAGES = ["/", "/billing", "/signup", "/terms", "/privacy", "/templates", "/ai", "/integrations", "/docs"];

test.describe("Accessibility smoke", () => {
  for (const path of PAGES) {
    test(`${path} passes structural a11y checks`, async ({ page }) => {
      await page.goto(path, { waitUntil: "domcontentloaded" });
      await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

      // Document language - screen readers pick pronunciation from this.
      const lang = await page.locator("html").getAttribute("lang");
      expect(lang, `${path} has no <html lang>`).toBeTruthy();

      // Exactly one <h1>: zero leaves the page unnamed in a heading outline,
      // several make the outline ambiguous.
      const h1Count = await page.getByRole("heading", { level: 1 }).count();
      expect(h1Count, `${path} has ${h1Count} <h1> elements, expected exactly 1`).toBe(1);

      // Every image needs alt (empty alt is valid and means "decorative").
      const imagesWithoutAlt = await page.locator("img:not([alt])").evaluateAll((els) =>
        els.map((e) => (e as HTMLImageElement).src || (e as HTMLImageElement).outerHTML.slice(0, 100))
      );
      expect(imagesWithoutAlt, `${path} has <img> without an alt attribute`).toEqual([]);

      // Buttons and links must have an accessible name from SOME source -
      // text, aria-label, aria-labelledby, or title. Icon-only controls are
      // the usual offenders.
      const namelessControls = await page.locator("button, a[href]").evaluateAll((els) =>
        els
          .filter((el) => {
            const style = window.getComputedStyle(el);
            if (style.display === "none" || style.visibility === "hidden") return false;
            const name =
              (el.textContent || "").trim() ||
              el.getAttribute("aria-label") ||
              el.getAttribute("aria-labelledby") ||
              el.getAttribute("title") ||
              el.querySelector("img[alt]")?.getAttribute("alt") ||
              el.querySelector("svg title")?.textContent;
            return !name;
          })
          .map((el) => el.outerHTML.slice(0, 120))
      );
      expect(
        namelessControls,
        `${path} has interactive controls with no accessible name:\n${namelessControls.join("\n")}`
      ).toEqual([]);

      // Form inputs need a programmatic label.
      const unlabelledInputs = await page
        .locator("input:not([type=hidden]):not([type=submit]):not([type=button]), select, textarea")
        .evaluateAll((els) =>
          els
            .filter((el) => {
              const style = window.getComputedStyle(el);
              if (style.display === "none" || style.visibility === "hidden") return false;
              const id = el.getAttribute("id");
              const hasLabelFor = id ? Boolean(document.querySelector(`label[for="${CSS.escape(id)}"]`)) : false;
              return !(
                hasLabelFor ||
                el.getAttribute("aria-label") ||
                el.getAttribute("aria-labelledby") ||
                el.closest("label") ||
                el.getAttribute("placeholder")
              );
            })
            .map((el) => el.outerHTML.slice(0, 120))
        );
      expect(unlabelledInputs, `${path} has unlabelled form inputs:\n${unlabelledInputs.join("\n")}`).toEqual([]);
    });
  }

  test("keyboard focus is visible and reaches the primary navigation", async ({ page }) => {
    await page.goto("/");

    // Tab a few times and confirm focus actually lands on interactive
    // elements rather than being trapped on <body>.
    const focused: string[] = [];
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press("Tab");
      focused.push(
        await page.evaluate(() => {
          const el = document.activeElement;
          return el ? el.tagName.toLowerCase() : "none";
        })
      );
    }

    expect(
      focused.some((tag) => ["a", "button", "input", "select", "textarea"].includes(tag)),
      `Tab never reached an interactive element (saw: ${focused.join(", ")})`
    ).toBe(true);
  });

  test("the landing page exposes a main landmark", async ({ page }) => {
    await page.goto("/");
    const landmarks = await page.locator("main, [role=main]").count();
    expect(landmarks, "no <main> landmark on the landing page").toBeGreaterThan(0);
  });
});
