import { test, expect } from "@playwright/test";

// requires V131 + 2743b59/6d3b474 deployed.
//
// The Guided Lab coach on /try (web/app/components/try/GuidedLab.tsx): a
// docked, dismissible step-by-step tour for first-time sandbox visitors.
// Steps tick themselves only when their REAL action happens (persona picked,
// message sent, ...), and all state persists in localStorage
// ("vegaduta.guidedLab") so dismissal and progress survive a reload.
//
// Tagged @pending-deploy: written ahead of the deploy that carries these
// features. Include with `--grep @pending-deploy`, exclude with
// `--grep-invert @pending-deploy` until the deploy lands.
//
// Selector conventions (matching the rest of this suite — no data-testids
// exist on this surface): role/accessible-name and visible copy. The coach's
// container carries aria-label="Guided tour".

const COACH = { name: "Guided tour" };

test.describe("Guided Lab coach on /try", { tag: "@pending-deploy" }, () => {
  test("fresh visit shows the welcome coach card alongside the persona picker", async ({
    page,
  }) => {
    // A fresh context has no demo token, so /try lands on the "choosing"
    // phase (persona picker) WITHOUT provisioning anything — this test burns
    // no sandbox slot.
    await page.goto("/try");

    const coach = page.getByRole("region", COACH).or(page.locator('[aria-label="Guided tour"]'));
    await expect(coach.first()).toBeVisible();

    // The exact welcome copy from the brief.
    await expect(
      page.getByText(/Build a working AI agent for your business in 5 minutes/)
    ).toBeVisible();

    // Both welcome affordances, and the persona picker underneath — the coach
    // directs, it never blocks.
    await expect(page.getByRole("button", { name: "Show me" })).toBeVisible();
    await expect(
      page.getByRole("button", { name: /No thanks, I'll explore/ })
    ).toBeVisible();
    await expect(page.getByRole("heading", { name: /I run a/ })).toBeVisible();
  });

  test("picking a persona completes step 1, and the suggested-question chip fills the composer", async ({
    page,
  }, testInfo) => {
    // Provisions ONE real sandbox (a genuine tenant + schema). Run once, on
    // chromium only — each run spends a rate-limited sandbox slot for this IP.
    testInfo.skip(
      testInfo.project.name !== "chromium",
      "provisions a real rate-limited demo sandbox — run on one project only"
    );
    // Tenant provisioning genuinely takes several seconds (Postgres schema +
    // ~90 migrations) — see TrySandbox's boot narration.
    test.setTimeout(120_000);

    await page.goto("/try");
    await expect(page.getByRole("button", { name: "Show me" })).toBeVisible();
    await page.getByRole("button", { name: "Show me" }).click();

    // One tap on a persona card provisions the sandbox and — because the real
    // action happened — ticks the coach's first step by itself.
    await page.getByRole("button", { name: "Kirana store" }).click();

    // Ready phase: the composer exists. Generous timeout for provisioning.
    const composer = page.getByLabel("Message your sandbox agent");
    await expect(composer).toBeVisible({ timeout: 90_000 });

    // Step 1 ("Pick your business") is done: progress reads "1 of N done"
    // (N is 4 or 5 depending on whether this deployment offers images), and
    // the checklist chip for it is rendered.
    await expect(page.getByText(/1 of [45] done/)).toBeVisible();
    await expect(page.getByText("Pick your business")).toBeVisible();

    // The current step is now "Ask a question", whose one-click action chip
    // carries the suggested question wrapped in curly quotes. Clicking it
    // must INSERT the text into the composer (not send it).
    await expect(page.getByText(/Ask your agent something a real customer would/)).toBeVisible();
    const chip = page.getByRole("button", { name: /^“.+”$/ });
    await expect(chip).toBeVisible();
    const label = (await chip.innerText()).trim();
    const question = label.replace(/^“/, "").replace(/”$/, "");
    expect(question.length).toBeGreaterThan(0);

    await chip.click();
    await expect(composer).toHaveValue(question);
    // Inserting is not sending — the step must NOT have ticked itself.
    await expect(page.getByText(/1 of [45] done/)).toBeVisible();
  });

  test('dismissing with "No thanks" hides the coach, and the dismissal survives a reload', async ({
    page,
  }) => {
    // Dismissal happens on the choosing phase — no sandbox is provisioned.
    await page.goto("/try");
    await expect(
      page.getByRole("button", { name: /No thanks, I'll explore/ })
    ).toBeVisible();

    await page.getByRole("button", { name: /No thanks, I'll explore/ }).click();

    await expect(page.locator('[aria-label="Guided tour"]')).toHaveCount(0);
    // The page itself stays fully usable — the persona picker is untouched.
    await expect(page.getByRole("heading", { name: /I run a/ })).toBeVisible();

    // The decision is persisted (localStorage "vegaduta.guidedLab").
    const stored = await page.evaluate(() => {
      try {
        return window.localStorage.getItem("vegaduta.guidedLab");
      } catch {
        return null;
      }
    });
    expect(stored).toBeTruthy();
    expect(JSON.parse(stored as string).dismissed).toBe(true);

    // A dismissed coach never comes back.
    await page.reload();
    await expect(page.getByRole("heading", { name: /I run a/ })).toBeVisible();
    await expect(page.locator('[aria-label="Guided tour"]')).toHaveCount(0);
    await expect(
      page.getByText(/Build a working AI agent for your business in 5 minutes/)
    ).toHaveCount(0);
  });
});
