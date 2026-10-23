import { test, expect } from "@playwright/test";
import { signUpFreshTenant } from "../helpers/test-tenant";
import { groupSave, settingRow } from "../helpers/console-controls";

// A risky change needs a typed reason (and, for a security setting, an acknowledgment), and the reason is kept in the
// setting's history. Uses "Toxicity score that blocks" (guardrails.toxicity.blockScore): a SECURITY setting that can
// only be made stricter than the platform value (a lower score blocks more), so the spec only ever lowers it.
// Real side effects: it provisions a tenant on whatever E2E_BASE_URL points at. No model or provider is needed.

const KEY = "guardrails.toxicity.blockScore";
const LABEL = "Toxicity score that blocks";

test.describe("Risky settings need a reason", () => {
  test("a security change asks for a reason and an acknowledgment, can be cancelled, and records the reason", async ({ page }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "provisions a real tenant - run on one project only");
    test.setTimeout(10 * 60 * 1000);

    await signUpFreshTenant(page, "risky");
    await page.goto("/admin/runtime-settings");

    const row = settingRow(page, KEY);
    await expect(row).toBeVisible({ timeout: 20_000 });
    const input = row.getByRole("spinbutton", { name: LABEL });
    const before = Number(await input.inputValue());
    test.skip(!(before >= 10), "the platform value is already at the strictest end, nothing stricter to set");
    const stricter = String(before - 10);

    // The security section says what the rule is.
    await expect(page.getByTestId("security-note-guardrails")).toContainText("can only be made stricter");

    await test.step("saving opens the confirmation instead of saving", async () => {
      await input.fill(stricter);
      await groupSave(page, "guardrails").click();
      const dialog = page.getByRole("alertdialog");
      await expect(dialog).toBeVisible();
      await expect(dialog).toContainText("Confirm risky changes (1)");
      await expect(dialog).toContainText(LABEL);
      await expect(dialog).toContainText("What this changes:");
      // Nothing was written yet.
      await expect(row.getByTestId("setting-source")).not.toHaveAttribute("data-source", "tenant");
    });

    const dialog = page.getByRole("alertdialog");
    const confirm = dialog.getByRole("button", { name: "Confirm and save" });
    const reason = dialog.getByLabel("Reason for this change");

    await test.step("confirm stays disabled until there is a reason and the acknowledgment", async () => {
      await expect(reason).toBeFocused();
      await expect(confirm).toBeDisabled();
      await reason.fill("ab");
      await expect(confirm).toBeDisabled();
      await reason.fill("Tightening after the safety review");
      await expect(confirm).toBeDisabled(); // security: the acknowledgment is still missing
      await dialog.getByLabel("I understand the consequences of this security change.").check();
      await expect(confirm).toBeEnabled();
    });

    await test.step("cancel changes nothing", async () => {
      await dialog.getByRole("button", { name: "Cancel" }).click();
      await expect(page.getByRole("alertdialog")).toHaveCount(0);
      await page.reload();
      await expect(settingRow(page, KEY).getByRole("spinbutton", { name: LABEL })).toHaveValue(String(before), { timeout: 20_000 });
    });

    await test.step("confirming with a reason saves it, and the history shows who, the values and the reason", async () => {
      const again = settingRow(page, KEY);
      await again.getByRole("spinbutton", { name: LABEL }).fill(stricter);
      await groupSave(page, "guardrails").click();
      const confirmDialog = page.getByRole("alertdialog");
      await confirmDialog.getByLabel("Reason for this change").fill("Tightening after the safety review");
      await confirmDialog.getByLabel("I understand the consequences of this security change.").check();
      await confirmDialog.getByRole("button", { name: "Confirm and save" }).click();
      await expect(page.getByRole("alertdialog")).toHaveCount(0, { timeout: 20_000 });
      await expect(again.getByTestId("setting-effective")).toContainText(stricter);
      await expect(again.getByTestId("setting-source")).toHaveAttribute("data-source", "tenant");

      await again.getByTestId("setting-history-open").click();
      const history = page.getByRole("dialog", { name: `History: ${LABEL}` });
      await expect(history).toBeVisible();
      await expect(history).toContainText("Tightening after the safety review");
      await expect(history).toContainText(stricter);
    });

    await test.step("making it weaker than the platform value is refused by the server and shown inline", async () => {
      test.skip(before >= 100, "the platform value is already at the top of the range, nothing weaker to try");
      await page.keyboard.press("Escape"); // closes the history drawer
      const again = settingRow(page, KEY);
      await again.getByRole("spinbutton", { name: LABEL }).fill(String(before + 1));
      await groupSave(page, "guardrails").click();
      const confirmDialog = page.getByRole("alertdialog");
      await confirmDialog.getByLabel("Reason for this change").fill("Trying to weaken it");
      await confirmDialog.getByLabel("I understand the consequences of this security change.").check();
      await confirmDialog.getByRole("button", { name: "Confirm and save" }).click();
      await expect(again.getByTestId("setting-server-error")).toBeVisible({ timeout: 20_000 });
      // The stricter value is still the one in force.
      await page.reload();
      await expect(settingRow(page, KEY).getByRole("spinbutton", { name: LABEL })).toHaveValue(stricter, { timeout: 20_000 });
    });
  });
});
