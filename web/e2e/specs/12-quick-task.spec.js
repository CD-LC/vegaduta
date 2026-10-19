// Business case: Quick Task (#355/#470) - "let them type what they want
// without worrying about the workflow, on button click it should get
// executed either through whatsapp, sms or on giving a call." Uses
// `consolePage`, same as the Providers/Agents/Playground specs - Quick Task
// is a non-admin console feature, and a tenant-admin never lands on "/".
//
// The UI tests below deliberately stop short of actually pressing "Get it
// done" / confirming a run: SimpleTaskRunner.js auto-selects the tenant's
// agent (QuickTaskController), which requires both an existing agent AND an
// active LLM provider - the same real-upstream-LLM dependency
// 02-providers-agents-chat.spec.js gates behind E2E_ANTHROPIC_API_KEY. What
// IS reliably testable with no external dependency - the delivery-number
// gate, the example chips, and the History page's empty state - is covered
// here as pure frontend behavior; the run itself is covered at the API
// level below (auth + empty-history shape), consistent with
// 10-api-security.spec.js's pattern for endpoints that need real upstream
// credentials to exercise end to end.
const { test, expect, signupTenant, keycloakPasswordGrantToken, API_BASE } = require("../fixtures");

test.describe("Quick Task", () => {
  test("a fresh tenant is asked for a delivery number before Quick Task will run anything", async ({ consolePage: page }) => {
    await page.goto("/quick-task");

    // needsPhone === true for a brand-new console user - SimpleTaskRunner.js
    // renders the phone-capture card instead of letting Run be pressed blind.
    const phoneCard = page.locator(".ax-qt-phonecard");
    await expect(phoneCard).toBeVisible({ timeout: 15_000 });

    await page.fill("#qt-phone", "+14155552671");
    await phoneCard.getByRole("button", { name: "Save" }).click();
    await expect(phoneCard).toBeHidden({ timeout: 15_000 });
  });

  test("quick-fill example chips populate the task description, and Run stays disabled until there's text", async ({ consolePage: page }) => {
    await page.goto("/quick-task");
    await page.fill("#qt-phone", "+14155552671");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.locator(".ax-qt-phonecard")).toBeHidden({ timeout: 15_000 });

    const runButton = page.getByRole("button", { name: /Get it done/ });
    await expect(runButton).toBeDisabled();

    await page.locator(".ax-qt-example-chip", { hasText: "Translate my latest announcement" }).click();
    await expect(page.locator(".ax-qt-textarea")).toHaveValue(/Translate my latest announcement/);
    await expect(runButton).toBeEnabled();
  });

  test("Task History is reachable from Quick Task and shows the empty state for a fresh tenant", async ({ consolePage: page }) => {
    await page.goto("/quick-task");
    await page.click('a:has-text("View task history")');
    await expect(page).toHaveURL(/\/quick-task\/history/);
    await expect(page.getByRole("heading", { name: "Task History" })).toBeVisible();
    await expect(page.locator(".ax-mnt-appr-empty")).toContainText("No past tasks yet");

    // The page's own "return to Quick Task" link closes the loop.
    await page.click('a:has-text("Quick Task")');
    await expect(page).toHaveURL(/\/quick-task$/);
  });
});

test.describe("Quick Task (API-level)", () => {
  test("Quick Task endpoints reject requests with no bearer token", async ({ request }) => {
    for (const path of ["/api/quick-task", "/api/quick-task/preview", "/api/quick-task/recent"]) {
      const res = await request.get(`${API_BASE}${path}`);
      expect(res.status(), path).toBe(401);
    }
  });

  test("a fresh tenant's task history is empty", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const res = await request.get(`${API_BASE}/api/quick-task/recent`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(res.status(), await res.text()).toBe(200);
    expect(await res.json()).toEqual([]);
  });

  test("looking up a task id that doesn't exist for this tenant is rejected, not silently empty", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const res = await request.get(`${API_BASE}/api/quick-task/00000000-0000-0000-0000-000000000000`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(res.status()).toBeGreaterThanOrEqual(400);
    expect(res.status()).toBeLessThan(500);
  });
});
