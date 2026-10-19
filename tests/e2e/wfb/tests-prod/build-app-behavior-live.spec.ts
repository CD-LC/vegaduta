import { test, expect, type Page, type FrameLocator } from "@playwright/test";
import { requireUserCredentials } from "../helpers/prod-env";
import { signInToConsole } from "../helpers/prod-session";
import { collectConsoleErrors } from "../helpers/console-errors";

/**
 * BEHAVIOR tests for the /build "AI app studio" (phone app studio), driven the
 * way a real maker uses it: pick or describe an app, LOOK at the running app in
 * the preview iframe, use it (add a task, log an expense, start a timer),
 * restyle it, edit its code, ask for a follow-up change, and check the running
 * app actually changed.
 *
 * Starter apps are hand-written and offline, so tests 1-6 are fully
 * deterministic (no LLM). Tests 7-8 ask the cloud model to build/adapt an app;
 * their assertions are tolerant (regex, structure, "something changed") and
 * retry once with a sharper prompt.
 *
 * Studio projects live only in this browser's localStorage, so nothing is
 * written to the tenant; each test uses a fresh browser context.
 */

const PREVIEW = 'iframe[title$=" preview"]';

/** The console shows a "page guide" popover on first visit that can sit over the tabs; a user would dismiss it. */
async function selectStudioTab(page: Page) {
  const tab = page.getByRole("tab", { name: /AI app studio/i });
  await expect(tab).toBeVisible({ timeout: 30_000 });
  await page.keyboard.press("Escape");
  const guide = page.locator('[id^="page-guide"]');
  if (await guide.isVisible().catch(() => false)) {
    await guide.getByRole("button", { name: /got it|close|dismiss|skip|not now/i }).first().click({ timeout: 3_000 }).catch(() => {});
  }
  await tab.dispatchEvent("click");
}

/** The first-visit "page guide" popover (auto-opens a moment after mount) floats over the studio; a user closes it with Escape. */
async function dismissGuide(page: Page) {
  const guide = page.locator('[role="dialog"][id^="page-guide"]');
  await guide.first().waitFor({ state: "visible", timeout: 4_000 }).catch(() => {});
  for (let i = 0; i < 3 && (await guide.first().isVisible().catch(() => false)); i++) {
    await page.keyboard.press("Escape");
    await page.waitForTimeout(300);
  }
}

async function openStudio(page: Page) {
  await signInToConsole(page);
  // mark the /build page guides as seen (the first-visit popover otherwise floats over the studio controls)
  await page.evaluate(() => {
    try {
      const k = "vd_page_guides_seen";
      const seen: string[] = JSON.parse(localStorage.getItem(k) || "[]");
      for (const id of ["build", "build-center", "build-studio", "build-apps"]) if (!seen.includes(id)) seen.push(id);
      localStorage.setItem(k, JSON.stringify(seen));
    } catch {
      /* ignore */
    }
  });
  await page.goto("/build");
  await selectStudioTab(page);
  await expect(page.getByRole("heading", { name: /Describe an app/i })).toBeVisible({ timeout: 30_000 });
}

async function startTemplate(page: Page, name: RegExp): Promise<FrameLocator> {
  await dismissGuide(page);
  await page.getByRole("button", { name }).first().click();
  await expect(page.locator(PREVIEW)).toBeVisible({ timeout: 20_000 });
  await dismissGuide(page);
  return page.frameLocator(PREVIEW);
}

async function openPanel(page: Page, label: string) {
  await page.getByRole("button", { name: new RegExp(`^${label}$`) }).dispatchEvent("click");
}

/**
 * Polls a condition on the built app for up to `timeoutMs`. Fails FAST, with the studio's own activity log,
 * if the studio tells the user the build failed (e.g. "Cloud model unavailable (HTTP 400)") - a real user
 * would give up there too, so waiting minutes would only hide the defect.
 */
async function waitWithLog(page: Page, fn: () => Promise<void>, what: string, timeoutMs = 200_000) {
  const end = Date.now() + timeoutMs;
  let last: unknown;
  while (Date.now() < end) {
    try {
      await fn();
      return;
    } catch (e) {
      last = e;
    }
    const log = await page.locator("div.max-h-64").first().innerText().catch(() => "");
    if (/unavailable \(HTTP|couldn't|hit an error|Sign in to use|wasn't a usable/i.test(log)) {
      throw new Error(`${what}. The studio told the user the build failed. Activity log:
${log}`);
    }
    await page.waitForTimeout(2_000);
  }
  const log = await page.locator("div.max-h-64").first().innerText().catch(() => "(no activity log)");
  throw new Error(`${what} within ${timeoutMs / 1000}s. Activity log:
${log}
--
${String(last).slice(0, 300)}`);
}

/** Reads the current app source out of the Code panel. */
async function readSource(page: Page): Promise<string> {
  await openPanel(page, "Code");
  const ta = page.getByLabel("App source code");
  await expect(ta).toBeVisible();
  return ta.inputValue();
}

test.describe("Build / AI app studio - real user behavior", () => {
  test.beforeEach(async ({}, testInfo) => {
    requireUserCredentials(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "one project only - LLM cost + auth rate limits");
  });

  test("1. to-do starter: add, complete, filter and delete tasks actually work", async ({ page }) => {
    const errs = collectConsoleErrors(page);
    const bad: string[] = [];
    page.on("requestfailed", (r) => bad.push(`FAILED ${r.url().slice(0, 120)} ${r.failure()?.errorText}`));
    page.on("response", (r) => { if (r.status() >= 500) bad.push(`${r.status()} ${r.url().slice(0, 120)}`); });
    await openStudio(page);
    const app = await startTemplate(page, /To-do list/i);

    await expect(app.locator("h1")).toHaveText(/Tasks/i);
    await expect(app.locator("#left")).toHaveText("2 to do");

    const title = `E2E buy oat milk ${Date.now() % 10000}`;
    await expect(async () => {
      await app.locator("#text").fill(title);
      await app.getByRole("button", { name: "Add" }).click();
      await expect(app.locator("#list")).toContainText(title, { timeout: 3_000 });
    }).toPass({ timeout: 25_000 });
    await expect(app.locator("#left")).toHaveText("3 to do");
    // newest first
    await expect(app.locator("#list .task").first()).toContainText(title);

    // complete it
    await app.locator("#list .task").first().locator("input[type=checkbox]").check();
    await expect(app.locator("#left")).toHaveText("2 to do");
    await expect(app.locator("#list .task").first()).toHaveClass(/done/);

    // filters: Done shows only completed, To do hides them
    await app.locator('[data-f="done"]').click();
    await expect(app.locator("#list")).toContainText(title);
    await expect(app.locator("#list")).toContainText("Plan the week");
    await expect(app.locator("#list")).not.toContainText("Call the plumber");
    await app.locator('[data-f="open"]').click();
    await expect(app.locator("#list")).not.toContainText(title);
    await expect(app.locator("#list")).toContainText("Call the plumber");

    // delete
    await app.locator('[data-f="all"]').click();
    await app.locator("#list .task").filter({ hasText: title }).getByRole("button", { name: "Delete" }).click();
    await expect(app.locator("#list")).not.toContainText(title);

    const body = await app.locator("body").innerText();
    expect(body).not.toMatch(/undefined|NaN|\[object Object\]|Traceback/);
    // Third-party beacon (blocked/unresolvable in CI) and aborted RSC prefetches are noise; a 5xx from OUR api is a defect.
    const serverErrors = bad.filter((b) => /^5\d\d /.test(b));
    expect(serverErrors, `5xx responses while using /build: ${serverErrors.join(" | ")}`).toEqual([]);
    const realErrors = errs.errors.filter((e) => !/ERR_NAME_NOT_RESOLVED|ERR_ABORTED/.test(e));
    expect(realErrors, `console errors: ${realErrors.join(" | ")}`).toEqual([]);
  });

  test("2. expense starter: logging an expense updates the total and category filter", async ({ page }) => {
    await openStudio(page);
    const app = await startTemplate(page, /Expense tracker/i);

    const parse = async () => Number((await app.locator("#total").innerText()).replace(/[^0-9.]/g, "")) || 0;
    // seed rows are dated relative to now: 180 (today) is always in this month; older rows may not be
    await expect(app.locator("#list")).toContainText("Lunch thali");
    const before = await parse();

    await app.getByRole("button", { name: "Add expense" }).click();
    await app.locator("#amount").fill("250");
    await app.locator("#note").fill("E2E filter coffee");
    await app.locator("#cat").selectOption("Food");
    await app.getByRole("button", { name: "Save" }).click();

    await expect(app.locator("#list")).toContainText("E2E filter coffee");
    await expect(app.locator("#list")).toContainText("250");
    expect(await parse()).toBe(before + 250);

    // category filter: Travel must hide the Food expense, Food must show it
    // (toPass: the preview iframe can re-mount once under a slow load, which resets the in-app filter)
    await expect(async () => {
      await app.locator('[data-c="Travel"]').click();
      await expect(app.locator("#list")).not.toContainText("E2E filter coffee", { timeout: 2_500 });
    }).toPass({ timeout: 20_000 });
    await expect(app.locator("#list")).toContainText("Auto to office");
    await expect(async () => {
      await app.locator('[data-c="Food"]').click();
      await expect(app.locator("#list")).toContainText("E2E filter coffee", { timeout: 2_500 });
      await expect(app.locator("#list")).not.toContainText("Auto to office", { timeout: 2_500 });
    }).toPass({ timeout: 20_000 });

    // delete restores the total
    await app.locator('[data-c="All"]').click();
    await app.locator("#list .item").filter({ hasText: "E2E filter coffee" }).getByRole("button", { name: "Delete" }).click();
    await expect(app.locator("#list")).not.toContainText("E2E filter coffee");
    expect(await parse()).toBe(before);
  });

  test("3. focus timer starter: counts down, switches mode, resets", async ({ page }) => {
    await openStudio(page);
    const app = await startTemplate(page, /Focus timer/i);

    await expect(app.locator("#time")).toHaveText("25:00");
    await app.locator("#go").click();
    await expect(app.locator("#go")).toHaveText("Pause");
    await expect(app.locator("#time")).not.toHaveText("25:00", { timeout: 6_000 });
    const t = await app.locator("#time").innerText();
    expect(t).toMatch(/^24:5\d$/);

    await app.locator("#go").click(); // pause
    await expect(app.locator("#go")).toHaveText("Resume");
    const frozen = await app.locator("#time").innerText();
    await page.waitForTimeout(2200);
    await expect(app.locator("#time")).toHaveText(frozen);

    await expect(async () => {
      await app.locator("#reset").click();
      await expect(app.locator("#time")).toHaveText("25:00", { timeout: 2_500 });
    }).toPass({ timeout: 20_000 });
    await app.locator("[data-m]").nth(1).click(); // a break mode
    await expect(app.locator("#time")).not.toHaveText("25:00");
    expect(await app.locator("#time").innerText()).toMatch(/^\d{2}:00$/);
  });

  test("4. design panel: a theme preset restyles the running app and the source, device toggle resizes", async ({ page }) => {
    await openStudio(page);
    const app = await startTemplate(page, /To-do list/i);

    const accent = () => app.locator("body").evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--primary").trim());
    const before = await accent();
    expect(before).toMatch(/^#[0-9a-f]{3,8}$/i);

    await openPanel(page, "Design");
    const presets = page.locator('button[title]').filter({ has: page.locator("span.rounded-full") });
    const n = await presets.count();
    expect(n).toBeGreaterThan(3);
    let after = before;
    for (let i = 0; i < n && after === before; i++) {
      await presets.nth(i).dispatchEvent("click"); // a console-shell notification popover can cover the panel
      await page.waitForTimeout(500);
      after = await accent();
    }
    expect(after, "no theme preset changed the app's accent colour").not.toBe(before);

    const src = await readSource(page);
    expect(src.length).toBeGreaterThan(1500);
    expect(src).toContain("studio:theme:start");
    expect(src.toLowerCase()).toContain(after.toLowerCase());

    // a new version was recorded
    await expect(page.getByText(/^Theme: /).first()).toBeVisible();

    // device toggle
    const w = async () => (await page.locator(PREVIEW).boundingBox())!.width;
    await page.getByRole("button", { name: "Desktop", exact: true }).click();
    await page.waitForTimeout(400);
    const desktopW = await w();
    await page.getByRole("button", { name: "Phone", exact: true }).click();
    await page.waitForTimeout(400);
    expect(desktopW).toBeGreaterThan(await w());
  });

  test("5. code panel: hand edit changes the running app; undo restores it; work survives reload", async ({ page }) => {
    await openStudio(page);
    let app = await startTemplate(page, /To-do list/i);

    const marker = `E2E Renamed ${Date.now() % 10000}`;
    const src = await readSource(page);
    expect(src).toMatch(/<h1>Tasks<\/h1>/);
    await page.getByLabel("App source code").fill(src.replace("<h1>Tasks</h1>", `<h1>${marker}</h1>`));
    await page.getByRole("button", { name: "Apply", exact: true }).click();
    await expect(app.locator("h1")).toHaveText(marker);
    await expect(page.getByText("Edited the code by hand").first()).toBeVisible();

    // a broken document is rejected, app keeps running
    await page.getByLabel("App source code").fill("<div>oops <script>");
    await page.getByRole("button", { name: "Apply", exact: true }).click();
    await expect(page.getByText(/doesn't look like a complete app/i)).toBeVisible();
    await expect(app.locator("h1")).toHaveText(marker);
    await page.getByRole("button", { name: "Discard" }).click();

    // reload: project persisted in "Your apps"; reopening shows the edited app
    await page.reload();
    await selectStudioTab(page);
    await expect(page.getByRole("heading", { name: "Your apps" })).toBeVisible({ timeout: 30_000 });
    await page.getByRole("button", { name: /versions?/i }).first().click();
    app = page.frameLocator(PREVIEW);
    await expect(app.locator("h1")).toHaveText(marker);

    // undo -> original heading
    await page.getByRole("button", { name: "Undo" }).click();
    await expect(app.locator("h1")).toHaveText("Tasks");
  });

  test("6. element panel: tap a heading in the preview and rename it directly", async ({ page }) => {
    await openStudio(page);
    const app = await startTemplate(page, /To-do list/i);
    await openPanel(page, "Element");
    await page.getByRole("button", { name: /Start selecting/i }).click();
    await expect(page.getByText(/Tap any part of the app to change it/i)).toBeVisible();
    await app.locator("header.top h1").click();
    const text = page.locator("label", { hasText: /^Text/ }).locator("input[type=text]");
    await expect(text).toBeVisible({ timeout: 10_000 });
    const marker = `E2E Direct ${Date.now() % 10000}`;
    await text.fill(marker);
    await page.getByRole("button", { name: "Set", exact: true }).click();
    await expect(app.locator("h1")).toHaveText(marker);
    // leave select mode so taps go to the app again
    const sel = page.getByRole("button", { name: /Tap an element/i });
    if (await sel.isVisible().catch(() => false)) await sel.click();
    // change is in the source, and the rest of the app still works
    expect(await readSource(page)).toContain(marker);
    await app.locator("#text").fill("still works");
    await app.getByRole("button", { name: "Add" }).click();
    await expect(app.locator("#list")).toContainText("still works");
  });

  test("7. describe a NEW app in words: the built app matches the request and a follow-up edit changes it", async ({ page }) => {
    test.setTimeout(420_000);
    const errs = collectConsoleErrors(page);
    await openStudio(page);
    await page.getByLabel("Describe your app").fill(
      "A simple tip calculator: one number input for the bill amount, a button labelled Calculate Tip, and a result line that shows 15% of the bill. Title it Tip Buddy."
    );
    await page.getByRole("button", { name: /Build it/i }).click();
    await expect(page.locator(PREVIEW)).toBeVisible({ timeout: 20_000 });
    const app = page.frameLocator(PREVIEW);

    // generation runs; wait for the app to leave the blank skeleton (a heading mentioning tip)
    await waitWithLog(page, () => expect(app.locator("body")).toContainText(/tip/i, { timeout: 1_500 }), "app never became a tip calculator");
    await expect(page.getByText(/Writing your app/i)).toBeHidden({ timeout: 240_000 });
    await expect(page.getByText(/couldn't|hit an error/i)).toHaveCount(0);

    const bodyText = await app.locator("body").innerText();
    expect(bodyText).toMatch(/tip buddy/i);
    expect(bodyText).not.toMatch(/undefined|NaN|\[object Object\]|<script/i);
    const src = await readSource(page);
    expect(src.length).toBeGreaterThan(800);
    expect(src).toMatch(/<!doctype html>/i);
    expect(src).toMatch(/<script/i);

    // the built app computes: 200 -> 30
    const input = app.locator("input").first();
    await expect(input).toBeVisible();
    await input.fill("200");
    await app.getByRole("button", { name: /calculate/i }).first().click();
    await expect(app.locator("body")).toContainText(/\b30(\.00?)?\b/, { timeout: 5_000 });

    // follow-up edit: must change the output
    const token = `E2E-FOLLOW-${Date.now() % 100000}`;
    await page.getByLabel("Ask for a change").fill(`Add a small footer line at the bottom of the app that says exactly: ${token}`);
    await page.getByRole("button", { name: /^Change it$|^Send$/ }).last().click();
    await expect(app.locator("body")).toContainText(token, { timeout: 240_000 });
    expect(await readSource(page)).toContain(token);
    // and the original behaviour still works after the edit
    await app.locator("input").first().fill("100");
    await app.getByRole("button", { name: /calculate/i }).first().click();
    await expect(app.locator("body")).toContainText(/\b15(\.00?)?\b/, { timeout: 5_000 });
    // version history grew
    await expect(page.getByText(/Versions/).first()).toBeAttached();
    expect(errs.errors.filter((e) => !/Failed to load resource|WebGPU|webllm|wasm/i.test(e)), "console errors").toEqual([]);
  });

  test("8. describe an idea that matches a starter: the starter is ADAPTED to the request", async ({ page }) => {
    test.setTimeout(420_000);
    await openStudio(page);
    const brand = `Zenith${Date.now() % 1000}`;
    await page.getByLabel("Describe your app").fill(
      `A to-do list for my family grocery shopping. Rename the heading to exactly: ${brand} Groceries, and pre-fill the sample tasks with grocery items like milk and eggs.`
    );
    await page.getByRole("button", { name: /Build it/i }).click();
    const app = page.frameLocator(PREVIEW);
    await expect(app.locator("h1")).toBeVisible({ timeout: 30_000 });

    await waitWithLog(page, () => expect(app.locator("body")).toContainText(new RegExp(`${brand}\\s+Groceries`, "i"), { timeout: 1_500 }), "starter was never adapted");
    await expect(app.locator("body")).toContainText(/milk|eggs|bread|vegetables|rice/i);
    // it stayed a working to-do app
    await app.locator("#text").fill("E2E extra item");
    await app.getByRole("button", { name: "Add" }).click();
    await expect(app.locator("body")).toContainText("E2E extra item");
    // the sample tasks were actually replaced, not just decorated
    const src = await readSource(page);
    expect(src).toContain(`${brand} Groceries`);
    expect(await app.locator("body").innerText()).not.toContain("Call the plumber");
  });
});
