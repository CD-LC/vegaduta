import { type Locator, type Page, type Route, expect } from "@playwright/test";

// Helpers shared by the specs that drive the agent-control screens (runtime settings, risky changes, approvals queue,
// prompt registry). Selectors use accessible names and the data-testid hooks the components already carry; every
// visible string quoted here is the English text in web/messages/en.json.

/** Imports the ready-made Personal Assistant so the fresh tenant has an agent; returns its id. */
export async function importAgent(page: Page, name: string): Promise<string> {
  await page.goto("/agents/bundles");
  await page.getByTestId("ready-personal-assistant").getByRole("button", { name: "Preview" }).click();
  await page.getByLabel("Agent name").fill(name);
  await page.getByRole("button", { name: "Import agent" }).click();
  await page.waitForURL(/\/agents\?.*agentId=/, { timeout: 30_000 });
  const id = new URL(page.url()).searchParams.get("agentId") || "";
  expect(id).not.toBe("");
  return id;
}

/** The row of one catalog setting (same testid on the workspace page and on the agent's Behavior tab). */
export const settingRow = (page: Page, key: string): Locator => page.getByTestId(`setting-${key}`);

/** The Save changes button of one settings group ("agent-limits", "guardrails", ...). */
export const groupSave = (page: Page, group: string): Locator =>
  page.getByTestId(`settings-group-${group}`).getByRole("button", { name: "Save changes" });

/** Picks an option of a Radix select by its accessible name. */
export async function pickOption(page: Page, selectName: string | RegExp, optionName: string | RegExp): Promise<void> {
  await page.getByRole("combobox", { name: selectName }).click();
  await page.getByRole("option", { name: optionName }).click();
}

/** Headers that let a fulfilled response be read by a page whose API lives on another origin (local dev: :3000 -> :8080). */
export function corsHeaders(route: Route): Record<string, string> {
  const origin = route.request().headers()["origin"];
  return origin
    ? {
        "access-control-allow-origin": origin,
        "access-control-allow-credentials": "true",
        "access-control-expose-headers": "X-Total-Count, X-Has-More",
      }
    : {};
}
