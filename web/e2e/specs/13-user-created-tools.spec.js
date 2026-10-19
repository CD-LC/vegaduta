// Business case: user-created tools (#424) - sandboxed Spring Expression
// Language (SpEL) expressions a tenant can register as a callable tool for
// their own agents, with no general-purpose script engine involved (see
// UserToolScript's javadoc: SimpleEvaluationContext has no method/
// constructor/bean/type resolution, and SpEL's own grammar has no loop
// construct, so it's sandboxed by construction rather than by a denylist).
// Renders directly below the MCP marketplace on the same /tools screen
// (UserToolScriptsPanel.js) - covered as its own spec rather than folded
// into 03-mcp-registry.spec.js since it's a functionally separate feature
// (own CRUD, own backend module) that happens to share a page.
const { test, expect, signupTenant, keycloakPasswordGrantToken, API_BASE } = require("../fixtures");

test.describe("User-created tools (SpEL sandbox)", () => {
  test("create a tool, test it live, then toggle and delete it", async ({ consolePage: page }) => {
    await page.goto("/tools");

    const toolName = `e2e_discount_tier_${Date.now()}`;
    await page.locator(".ax-tl-card-add", { hasText: "New tool" }).click();

    await page.fill('input[placeholder^="tool name"]', toolName);
    await page.fill('input[placeholder="display name"]', "E2E Discount Tier");
    await page.fill(
      'input[placeholder^="description"]',
      "Returns a discount tier string for a given price - created by the Playwright suite."
    );
    // Same expression shown as the field's own placeholder example - a
    // three-way ternary over a `price` field on the sample input JSON.
    await page.fill(
      "textarea.ax-tl-form-input >> nth=0",
      "price > 100 ? 'gold' : (price > 50 ? 'silver' : 'standard')"
    );

    // TRY IT - sampleInput already defaults to {"example": 42}; override it
    // to match the expression's expected `price` field before testing.
    const sampleInputBox = page.locator('textarea[placeholder^="sample input JSON"]');
    await sampleInputBox.fill('{"price": 75}');
    await page.click('button:has-text("Run test")');
    await expect(page.locator(".ax-tl-add-section pre")).toContainText("silver", { timeout: 15_000 });

    await page.click('button:has-text("Create tool")');
    await expect(page.locator(".ax-tl-alert-success")).toContainText(`"E2E Discount Tier" created`, { timeout: 15_000 });

    const card = page.locator(".ax-tl-card", { hasText: "E2E Discount Tier" });
    await expect(card).toBeVisible();
    await expect(card.locator(".ax-tl-card-meta")).toContainText(toolName);

    // Enabled by default - toggle off, then back on.
    const toggle = card.locator('button[role="switch"]');
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-checked", "false", { timeout: 15_000 });
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-checked", "true", { timeout: 15_000 });

    // Delete - icon button with an aria-label scoped to this tool's name
    // (UserToolScriptsPanel.js), not a generic "Delete" that would collide
    // if more than one tool card is on screen.
    await card.getByRole("button", { name: `Delete E2E Discount Tier` }).click();
    await expect(page.locator(".ax-tl-card", { hasText: "E2E Discount Tier" })).toHaveCount(0, { timeout: 15_000 });
  });

  test("submitting the new-tool form with required fields blank is rejected client-side", async ({ consolePage: page }) => {
    await page.goto("/tools");
    await page.locator(".ax-tl-card-add", { hasText: "New tool" }).click();
    await page.click('button:has-text("Create tool")');
    await expect(page.getByText(/Name, tool name .* and expression are all required/)).toBeVisible();
  });
});

test.describe("User-created tools (API-level)", () => {
  test("the tool-script endpoints reject requests with no bearer token", async ({ request }) => {
    const res = await request.get(`${API_BASE}/api/tools/scripts`);
    expect(res.status()).toBe(401);
  });

  test("a malformed SpEL expression is rejected with a clear error, not a 500", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const res = await request.post(`${API_BASE}/api/tools/scripts/test`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { expression: "this is not valid SpEL &&& (((", sampleInputJson: "{}" },
    });
    // Whether this is modeled as a 200 with an error payload or a 4xx, it
    // must never be a raw 500 - the sandbox is expected to contain a bad
    // expression, not crash the request.
    expect(res.status()).toBeLessThan(500);
  });
});
