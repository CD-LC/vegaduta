import { test, expect, type Page } from "@playwright/test";

/**
 * Stage-only (kind, plain-http nip.io) coverage for the tools/MCP area.
 * The stage web image is built with http:// public URLs, so https:// gives
 * mixed-content blocks; this spec uses http and treats those origins as secure
 * (keycloak-js needs crypto.subtle). Entities created here use the
 * "e2e-tools-mcp-" prefix. Read-mostly: no shared state is changed.
 */
const D = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const APP = `http://app.${D}`;
test.use({
  baseURL: APP,
  launchOptions: {
    args: [`--unsafely-treat-insecure-origin-as-secure=http://app.${D},http://auth.${D},http://api.${D}`],
  },
});

async function login(page: Page) {
  await page.goto(`${APP}/login`);
  await page.getByLabel(/email/i).fill(process.env.E2E_USER_EMAIL!);
  await page.getByRole("button", { name: /continue to secure/i }).click();
  await page.locator("#password").fill(process.env.E2E_USER_PASSWORD!);
  await page.locator("#kc-login").click();
  await page.waitForURL(new RegExp(`app\\.${D.replace(/\./g, "\\.")}/`), { timeout: 45_000 });
  // The signed-in tenant name differs per stack ("Load Test Org" on the stage this spec was
  // written against, "A2A Test Org" on the local lite stack) - assert the app shell instead.
  await expect(page.locator("header, nav").first()).toBeVisible({ timeout: 30_000 });
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => { try { localStorage.setItem("pageGuideSeen", "[]"); } catch {} });
  await login(page);
});

test("/tools renders catalog, filters and pagination", async ({ page }) => {
  const bad: string[] = [];
  page.on("response", (r) => { if (r.url().includes("/api/") && r.status() >= 400) bad.push(`${r.status()} ${r.url()}`); });
  await page.goto("/tools");
  await expect(page.getByRole("heading", { name: "Integrations" })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText("filesystem", { exact: true }).first()).toBeVisible();
  for (const tab of ["Built-in", "Google Workspace", "MCP Servers", "Custom", "All"]) {
    const t = page.getByRole("tab", { name: tab, exact: true });
    await t.click();
    await expect(t).toHaveAttribute("aria-selected", "true");
  }
  for (const top of ["Classification", "Custom Code", "OpenAPI Sources", "Servers"]) {
    const t = page.getByRole("tab", { name: top, exact: true });
    await t.click();
    await expect(t).toHaveAttribute("aria-selected", "true");
  }
  await expect(page.getByRole("button", { name: "Next page" })).toBeVisible();
  expect(bad, bad.join("\n")).toEqual([]);
});

test("/tools sub-pages load", async ({ page }) => {
  for (const [path, heading] of [
    ["/tools/servers", /Enabled MCP servers/],
    ["/tools/code", /Custom Code Tools/],
    ["/tools/credentials", /MCP Server Credentials/],
    ["/capabilities/decision", /Decision providers/],
  ] as const) {
    await page.goto(path);
    await expect(page.getByRole("heading", { name: heading }).first(), path).toBeVisible({ timeout: 20_000 });
  }
});

test("public /integrations and /plugins render and catalog loads", async ({ page }) => {
  await page.goto("/integrations");
  await expect(page.getByRole("heading", { name: "WhatsApp" }).first()).toBeVisible();
  const cat = page.waitForResponse((r) => r.url().includes("/api/plugins/catalog"));
  await page.goto("/plugins");
  expect((await cat).status()).toBe(200);
  await expect(page.getByText(/couldn't load the plugin catalog/i)).toHaveCount(0);
});

test.fixme("DEFECT: /capabilities is a 404 although it is the area name in nav docs", async ({ page }) => {
  const r = await page.goto("/capabilities");
  expect(r?.status()).not.toBe(404);
});

test.fixme("DEFECT: /code-intel calls http://localhost:8090 from the browser (compose default baked into the web image)", async ({ page }) => {
  const hits: string[] = [];
  page.on("request", (q) => { if (/localhost:8090/.test(q.url())) hits.push(q.url()); });
  await page.goto("/code-intel");
  await page.waitForTimeout(3000);
  expect(hits).toEqual([]);
});

test.fixme("DEFECT: /plugins lists VS Code/JetBrains as COMING SOON yet 'Available' below", async ({ page }) => {
  await page.goto("/plugins");
  await expect(page.getByText("No build published yet")).toHaveCount(0);
});
