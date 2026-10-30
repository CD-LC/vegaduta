import { expect, test as base, type Page } from "@playwright/test";

/**
 * Agent-quality suite: proves the agent's real behaviour end-to-end, not just that surfaces render.
 * Complements personal-smoke (which checks the sign-in → composer → reply path exists):
 *
 *   - answer quality: a question with one correct answer must come back with that answer,
 *   - turn inspector: a completed reply exposes the model calls, prompt blocks and token use,
 *   - turn feedback: the owner can rate a completed turn (the label the agent learns from),
 *   - the workbench: /agents renders the agent surface the turn actually ran against,
 *   - tools: /tools lists the tool surface the model calls may invoke,
 *   - automation: /workflows renders the automation surface (schedules, triggers, approvals live here),
 *   - usage: /usage renders where turn cost lands.
 *
 * Run it with `npx playwright test -c playwright.personal.config.ts` against a personal stack. Needs the
 * owner credentials (E2E_PERSONAL_EMAIL / E2E_PERSONAL_PASSWORD); without them every check skips.
 */
const PERSONAL = process.env.E2E_EDITION === "personal";
const OWNER_EMAIL = process.env.E2E_PERSONAL_EMAIL || "";
const OWNER_PASSWORD = process.env.E2E_PERSONAL_PASSWORD || "";
const HAS_OWNER = !!(OWNER_EMAIL && OWNER_PASSWORD);

const SKIP_REASON =
  "personal agent suite: run it with playwright.personal.config.ts against a personal stack";

const test = base.extend<{ owner: Page }>({
  owner: async ({ page, baseURL }, use) => {
    await page
      .context()
      .addCookies([
        {
          name: "NEXT_LOCALE",
          value: "en",
          url: baseURL || "http://localhost:3000",
        },
      ]);
    const res = await page.request.post("/api/local-auth/login", {
      data: { identifier: OWNER_EMAIL, password: OWNER_PASSWORD },
    });
    expect(res.status(), "owner sign-in (is first-run setup finished?)").toBe(
      200,
    );
    await use(page);
  },
});

test.describe("personal agent: behaviour", () => {
  test.skip(!PERSONAL, SKIP_REASON);
  test.skip(
    !HAS_OWNER,
    "set E2E_PERSONAL_EMAIL and E2E_PERSONAL_PASSWORD (the owner after first-run setup)",
  );

  test("the agent answers a question with one correct answer correctly", async ({
    owner,
  }) => {
    // A cold local model can take well over 60s on its first turn.
    test.setTimeout(180_000);
    await owner.goto("/assistant");
    const composer = owner
      .getByLabel(/ask or tell me what to do/i)
      .first();
    await expect(composer).toBeEnabled({ timeout: 30_000 });
    await composer.fill(
      "What is 7 times 6? Reply with only the number, no other words.",
    );
    await composer.press("Enter");
    const reply = owner.locator('[data-role="assistant"]').last();
    await expect(reply).toBeVisible({ timeout: 150_000 });
    // Quality, not just presence: the model must answer the actual question.
    await expect(reply).toContainText(/42/);
  });

  test("the turn inspector exposes the model calls and prompt blocks behind a reply", async ({
    owner,
  }) => {
    test.setTimeout(180_000);
    await owner.goto("/assistant");
    const composer = owner
      .getByLabel(/ask or tell me what to do/i)
      .first();
    await expect(composer).toBeEnabled({ timeout: 30_000 });
    await composer.fill("Say hi.");
    await composer.press("Enter");
    const reply = owner.locator('[data-role="assistant"]').last();
    await expect(reply).toBeVisible({ timeout: 150_000 });
    // The RequestInspector renders a calls+tokens summary; opening it must show the breakdown.
    const inspector = owner
      .getByRole("button", { name: /model call|call.*token/i })
      .last();
    await expect(
      inspector,
      "no turn inspector - reply carried no request snapshots",
    ).toBeVisible({ timeout: 15_000 });
    await inspector.click();
    await expect(
      owner.getByText(/Made of these prompt blocks|Request breakdown/i),
    ).toBeVisible();
  });

  test("a completed turn offers feedback controls", async ({ owner }) => {
    test.setTimeout(180_000);
    await owner.goto("/assistant");
    const composer = owner
      .getByLabel(/ask or tell me what to do/i)
      .first();
    await expect(composer).toBeEnabled({ timeout: 30_000 });
    await composer.fill("Say hi.");
    await composer.press("Enter");
    const reply = owner.locator('[data-role="assistant"]').last();
    await expect(reply).toBeVisible({ timeout: 150_000 });
    await expect(
      owner
        .getByRole("button", { name: /good|helpful|thumbs|bad|unhelpful/i })
        .first(),
      "no feedback control on the completed turn",
    ).toBeVisible({ timeout: 15_000 });
  });
});

test.describe("personal agent: surfaces", () => {
  test.skip(!PERSONAL, SKIP_REASON);
  test.skip(
    !HAS_OWNER,
    "set E2E_PERSONAL_EMAIL and E2E_PERSONAL_PASSWORD (the owner after first-run setup)",
  );

  test("/agents renders the agent workbench", async ({ owner }) => {
    await owner.goto("/agents");
    await expect(owner.locator("main, [role='main']").first()).toBeVisible();
    await expect(owner).not.toHaveURL(/404/);
    // The workbench lists or creates agents; either state must show.
    await expect(
      owner.getByText(/agent|workbench/i).first(),
    ).toBeVisible({ timeout: 15_000 });
  });

  test("/tools renders the tool inventory", async ({ owner }) => {
    await owner.goto("/tools");
    await expect(owner).not.toHaveURL(/404/);
    await expect(owner.locator("main, [role='main']").first()).toBeVisible();
  });

  test("/workflows renders the automation surface", async ({ owner }) => {
    await owner.goto("/workflows");
    await expect(owner).not.toHaveURL(/404/);
    await expect(owner.locator("main, [role='main']").first()).toBeVisible();
  });

  test("/usage renders where turn cost lands", async ({ owner }) => {
    await owner.goto("/usage");
    await expect(owner).not.toHaveURL(/404/);
    await expect(owner.locator("main, [role='main']").first()).toBeVisible();
  });

  test("/devices renders the enrolled-device surface", async ({ owner }) => {
    await owner.goto("/devices");
    await expect(owner).not.toHaveURL(/404/);
    await expect(owner.locator("main, [role='main']").first()).toBeVisible();
  });
});
