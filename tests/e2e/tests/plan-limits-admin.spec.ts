import { type Locator, type Page, test, expect } from "@playwright/test";
import { requireAdminCredentials, signInAsPlatformAdmin } from "../helpers/admin-session";
import { ADMIN_BASE_URL } from "../helpers/e2e-env";

// Verifies platform-admin's combined "Tenants & Users" screen
// (web/app/platform-admin/tenants/page.tsx) end to end against the real platform:
// the per-plan "Plan Limits" editor (GET/PUT /api/platform/plan-limits,
// completely missing until the 2026-07-27 fix - this spec is the first
// verification that it actually works) and the per-tenant plan-override
// roster above it.
//
// Ground-truth seed values (sanity check only, not asserted against
// directly since production may have since diverged - see
// core/app/src/main/resources/db/migration/controlplane/V101__plan_limits.sql):
// FREE 2/5/3/1, PREMIUM 10/25/15/5, UNLIMITED -1/-1/-1/-1
// (max_users/max_agents/max_workflows/max_kb_collections).
//
// Admin-gated: requireAdminCredentials() skips this whole file cleanly when
// E2E_ADMIN_EMAIL/E2E_ADMIN_PASSWORD aren't set - no platform-admin account
// can be self-provisioned (see admin-session.ts's own comment).

const TENANTS_URL = `${ADMIN_BASE_URL}/tenants`;

// LIMIT_NUMBER_FIELDS in web/app/platform-admin/tenants/page.tsx renders, in this
// exact order: maxUsers, maxAgents, maxWorkflows, maxKbCollections - so the
// 4th (index 3) number input in a plan card is always max_kb_collections.
const KB_COLLECTIONS_FIELD_INDEX = 3;
const PLAN_LABELS = ["Free", "Premium", "Unlimited"] as const;

// Scopes to the "Plan Limits" Card (data-slot="card" is the shadcn Card
// primitive's own marker, web/app/platform-admin/components/ui/card.tsx) then to one
// plan's sub-card. Each plan sub-card is the only element at that level with
// the "rounded-lg" Tailwind class (Input/Button/switch all use rounded-md /
// rounded-full), and its label text ("Free"/"Premium"/"Unlimited") doesn't
// collide with the other two.
function getPlanLimitsCard(page: Page): Locator {
  return page.locator('[data-slot="card"]', { hasText: "Plan Limits" });
}

function getPlanCard(page: Page, label: (typeof PLAN_LABELS)[number]): Locator {
  return getPlanLimitsCard(page).locator("div.rounded-lg", { hasText: label }).first();
}

function getRosterCard(page: Page): Locator {
  return page.locator('[data-slot="card"]', { hasText: "Tenant Roster & Plans" });
}

test.describe("platform-admin Plan Limits editor + tenant plan override (Tenants & Users)", () => {
  test("Plan Limits section loads real, non-blank data for all three plan cards", async ({ page }, testInfo) => {
    requireAdminCredentials(testInfo);
    await signInAsPlatformAdmin(page);
    await page.goto(TENANTS_URL);

    const planLimitsCard = getPlanLimitsCard(page);
    await expect(planLimitsCard).toBeVisible();

    // Loading state must resolve, and the "no rows" empty state must not
    // fire - both would mean GET /api/platform/plan-limits didn't actually
    // return the three seeded rows.
    await expect(planLimitsCard.getByText("Loading plan limits…")).toBeHidden({ timeout: 20_000 });
    await expect(planLimitsCard.getByText("No plan limit rows returned.")).toHaveCount(0);

    for (const label of PLAN_LABELS) {
      const card = getPlanCard(page, label);
      await expect(card, `${label} plan card should render`).toBeVisible();

      // 4 numeric fields (max users/agents/workflows/kb collections).
      const numberInputs = card.locator('input[type="number"]');
      await expect(numberInputs).toHaveCount(4);
      for (let i = 0; i < 4; i++) {
        const raw = await numberInputs.nth(i).inputValue();
        expect(raw.trim(), `${label} plan numeric field #${i} should not be blank`).not.toBe("");
        expect(Number.isFinite(Number(raw)), `${label} plan numeric field #${i} ("${raw}") should be a real number`).toBe(
          true
        );
      }

      // 3 boolean toggles (BYOK / WhatsApp / white-label), each a real
      // switch with a determinate aria-checked state.
      const switches = card.locator('button[role="switch"]');
      await expect(switches).toHaveCount(3);
      for (let i = 0; i < 3; i++) {
        const ariaChecked = await switches.nth(i).getAttribute("aria-checked");
        expect(["true", "false"], `${label} plan toggle #${i} should have a determinate state`).toContain(ariaChecked);
      }

      // Its own per-card Save button renders, starting disabled (nothing
      // dirty yet on a fresh load).
      const saveButton = card.getByRole("button", { name: "Save" });
      await expect(saveButton).toBeVisible();
      await expect(saveButton).toBeDisabled();
    }
  });

  test("PREMIUM max-KB-collections: edit persists, then is restored exactly", async ({ page }, testInfo) => {
    // Single writer only - two Playwright projects (chromium, mobile-chrome)
    // both PUTting/restoring the same platform-wide row would race.
    testInfo.skip(
      testInfo.project.name !== "chromium",
      "writes a real platform-wide plan_limits row - run on one project only"
    );
    requireAdminCredentials(testInfo);
    await signInAsPlatformAdmin(page);

    let originalValue: string | null = null;
    let restoredAndVerified = false;

    try {
      await page.goto(TENANTS_URL);
      const premiumCard = getPlanCard(page, "Premium");
      await expect(premiumCard).toBeVisible();

      const kbInput = premiumCard.locator('input[type="number"]').nth(KB_COLLECTIONS_FIELD_INDEX);
      await expect(kbInput).toBeVisible();
      originalValue = await kbInput.inputValue();
      const original = Number(originalValue);
      expect(Number.isFinite(original), `PREMIUM max_kb_collections ("${originalValue}") should be numeric`).toBe(true);

      // -1 means "no cap". Bumping it to 0 would impose a real, if brief,
      // zero-collection cap on every PREMIUM tenant - not the "safe field
      // edit" this test is meant to be, so skip rather than risk that.
      test.skip(original < 0, "PREMIUM max_kb_collections is currently \"no cap\" (-1) - incrementing it would impose a real cap, skipping.");

      const changedValue = String(original + 1);

      await kbInput.fill(changedValue);
      const saveButton = premiumCard.getByRole("button", { name: "Save" });
      await expect(saveButton).toBeEnabled();
      await saveButton.click();

      // Success confirmation (toast.success(`${plan} plan limits saved.`)
      // in web/app/platform-admin/tenants/page.tsx's handleSaveLimits).
      await expect(page.getByText("PREMIUM plan limits saved.")).toBeVisible({ timeout: 15_000 });

      // Reload from scratch - re-fetches from the backend, proving the PUT
      // actually persisted rather than just updating local React state.
      await page.reload();
      const premiumCardAfterSave = getPlanCard(page, "Premium");
      const kbInputAfterSave = premiumCardAfterSave.locator('input[type="number"]').nth(KB_COLLECTIONS_FIELD_INDEX);
      await expect(kbInputAfterSave).toHaveValue(changedValue, { timeout: 15_000 });
    } finally {
      // Runs even if an assertion above threw - production's real PREMIUM
      // plan limits must never be left altered by this test.
      if (originalValue !== null) {
        await page.goto(TENANTS_URL);
        const premiumCardRestore = getPlanCard(page, "Premium");
        const kbInputRestore = premiumCardRestore.locator('input[type="number"]').nth(KB_COLLECTIONS_FIELD_INDEX);
        await expect(kbInputRestore).toBeVisible({ timeout: 15_000 });
        const currentValue = await kbInputRestore.inputValue();

        if (currentValue !== originalValue) {
          await kbInputRestore.fill(originalValue);
          const restoreSaveButton = premiumCardRestore.getByRole("button", { name: "Save" });
          await expect(restoreSaveButton).toBeEnabled({ timeout: 10_000 });
          await restoreSaveButton.click();
          // Best-effort: the "saved" toast may be de-duped if it repeats
          // the exact same message within toastBus's 4s window - the
          // reload-and-compare check below is the real proof of restore,
          // not this toast.
          await expect(restoreSaveButton).toBeDisabled({ timeout: 15_000 }).catch(() => {});
        }

        await page.reload();
        const premiumCardFinal = getPlanCard(page, "Premium");
        const kbInputFinal = premiumCardFinal.locator('input[type="number"]').nth(KB_COLLECTIONS_FIELD_INDEX);
        await expect(kbInputFinal).toHaveValue(originalValue, { timeout: 15_000 });
        restoredAndVerified = true;
      }
    }

    // Only reached if the try block completed without throwing - if it did
    // throw, the finally block above already ran (and this assertion is
    // skipped as the exception continues propagating, which is fine: the
    // restore itself, not this line, is what has to have happened).
    expect(restoredAndVerified).toBe(true);
  });

  test("Per-tenant plan override: search box and per-row controls render and are interactive", async ({
    page,
  }, testInfo) => {
    requireAdminCredentials(testInfo);
    await signInAsPlatformAdmin(page);
    await page.goto(TENANTS_URL);

    const rosterCard = getRosterCard(page);
    await expect(rosterCard).toBeVisible();

    const noTenantsMessage = rosterCard.getByText("No tenants found.");
    const searchBox = rosterCard.getByPlaceholder(/search tenants/i);

    // The search box only renders once tenants.length > 0 (see the page's
    // own guard) - wait for whichever of the two initial states resolves.
    await expect(searchBox.or(noTenantsMessage)).toBeVisible({ timeout: 15_000 });

    if (await noTenantsMessage.isVisible().catch(() => false)) {
      test.info().annotations.push({
        type: "note",
        description: "No tenants exist in this environment - search/plan-override interactivity not exercised.",
      });
      return;
    }

    await expect(searchBox).toBeVisible();
    await expect(searchBox).toBeEditable();

    // Empty/no-match state.
    const nonsenseQuery = `zzz-e2e-plan-limits-admin-nonexistent-${Date.now()}`;
    await searchBox.fill(nonsenseQuery);
    await expect(rosterCard.getByText(/No tenants match/i)).toBeVisible({ timeout: 10_000 });

    // Clear the search - results state. If a tenant happens to be present
    // (e.g. from another parallel test run), verify its plan control and
    // Save button render and are interactive, WITHOUT clicking Save (this
    // suite doesn't own/recognize that tenant).
    await searchBox.fill("");
    await expect(rosterCard.getByText(/No tenants match/i)).toHaveCount(0);

    const firstRow = rosterCard.locator("div.rounded-lg", { hasText: "Plan" }).first();
    const rowVisible = await firstRow.isVisible().catch(() => false);

    if (!rowVisible) {
      // Genuinely zero tenants (message just didn't say "No tenants found."
      // for some other reason) - nothing further to interact with.
      return;
    }

    // The plan control is platform-admin's own custom Select
    // (web/app/platform-admin/components/Select.tsx) - a button[aria-haspopup="listbox"]
    // driving a popover, NOT a native <select> element.
    const planTrigger = firstRow.locator(".select-trigger");
    await expect(planTrigger).toBeVisible();
    await expect(planTrigger).toBeEnabled();

    const saveButton = firstRow.getByRole("button", { name: /^save$/i });
    await expect(saveButton).toBeVisible();
    // Deliberately not clicked - this row belongs to a tenant this suite
    // doesn't own or recognize.
  });
});
