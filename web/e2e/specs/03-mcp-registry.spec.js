// Business case: registering and toggling an MCP tool server (Stage 4).
// Requires core running with the "mcp" profile - if it isn't, /api/mcp/registry
// errors and the page's own try/catch renders an empty list rather than
// crashing, so this spec's assertions would fail on the "no servers yet"
// state; see e2e/README.md for the profile list this whole suite expects.
//
// Lives on its own /tools route now (previously stacked on "/" with every
// other console panel) - see AppShell.js's "Connect" nav section. The
// primary path is now the curated catalog gallery (GET /api/mcp/registry/catalog,
// see McpCatalogService.java) rather than typing a name/type/description by
// hand - this spec exercises that path directly since it's what a real
// person actually does now. The old free-text form still exists behind the
// "Register a custom server" add-tile for servers the catalog doesn't cover -
// covered by the second test below.
//
// Selectors updated for the task #546 AXON rebuild of this screen
// (Tools.dc.html) - the panel used to render plain `.mcp-card`/`.list-item`/
// `.badge`/`.success-box` elements; it's now a card grid (`.ax-tl-card`) on
// the left with a selection-driven inspector (`.ax-tl-inspector`) on the
// right (see McpPanel.js) - selecting a card is what reveals its
// Activate/Connect/toggle controls, there's no longer a persistent list row
// per server.
const { test, expect } = require("../fixtures");

test.describe("MCP tool registry", () => {
  test("activate the built-in filesystem server from the catalog and toggle it", async ({ consolePage: page }) => {
    await page.goto("/tools");

    // McpCatalogService's "filesystem" entry is `ready: true` (no
    // credentials needed) - selecting its card shows a one-click Activate
    // button in the inspector rather than a credential form.
    const filesystemCard = page.locator(".ax-tl-card", { hasText: "filesystem" }).first();
    await expect(filesystemCard).toBeVisible({ timeout: 15_000 });
    await filesystemCard.click();

    const inspector = page.locator(".ax-tl-inspector");
    await inspector.getByRole("button", { name: "Activate" }).click();
    await expect(page.locator(".ax-tl-alert-success")).toContainText('Activated "filesystem"', { timeout: 15_000 });

    // Once registered, the same card (selKey is stable across activation -
    // see McpPanel.js's `cards` useMemo comment) now shows a live enabled
    // toggle instead of the Activate button.
    const registeredCard = page.locator(".ax-tl-card", { hasText: "filesystem" }).first();
    const toggle = registeredCard.locator(".ax-toggle");
    await expect(toggle).toHaveClass(/ax-toggle-on/);

    await toggle.click();
    await expect(page.locator(".ax-tl-alert-success")).toContainText('"filesystem" disabled', { timeout: 15_000 });
    await expect(registeredCard.locator(".ax-toggle")).not.toHaveClass(/ax-toggle-on/);

    await registeredCard.locator(".ax-toggle").click();
    await expect(page.locator(".ax-tl-alert-success")).toContainText('"filesystem" enabled', { timeout: 15_000 });
  });

  test("registering a custom server without a name is blocked client-side", async ({ consolePage: page }) => {
    await page.goto("/tools");
    await page.locator(".ax-tl-card-add", { hasText: "Register a custom server" }).click();
    await page.click('button:has-text("Register")');
    await expect(page.getByText(/Server name is required/)).toBeVisible();
  });
});
