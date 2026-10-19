// Business case: a tenant-admin creates a teammate account and assigns
// roles, and that teammate's first login goes through Keycloak's forced
// password-change flow before ever reaching the app - real onboarding path
// for anyone created via this panel (KeycloakCredentialRepresentation
// .temporaryPassword), not simulated.
const { test, expect, loginAndSetPermanentPassword, uniqueSuffix, pickCustomSelect } = require("../fixtures");

test.describe("Admin: users and roles", () => {
  test("create a user, assign a role, and the new user logs in for the first time", async ({
    adminPage: page,
  }) => {
    const suffix = uniqueSuffix();
    const username = `e2e-teammate-${suffix}`;
    const email = `${username}@example.test`;

    // Users & Roles now lives on its own /admin/users route - `adminPage`
    // lands on the /admin overview instead (cards linking to Users/
    // Approvals/Audit/Channels), not this panel directly.
    await page.goto("/admin/users");
    await page.fill('section:has-text("Users & Roles") input[type="text"] >> nth=0', username);
    await page.fill('section:has-text("Users & Roles") input[type="text"] >> nth=1', email);
    await page.fill('section:has-text("Users & Roles") input[type="text"] >> nth=2', "Teammate");
    await page.fill('section:has-text("Users & Roles") input[type="text"] >> nth=3', "One");
    await page.fill('section:has-text("Users & Roles") input[type="password"]', "TempPassw0rd!");
    await pickCustomSelect(page, page.getByTestId("admin-create-user-role-select"), "agent-builder");
    await page.click('button:has-text("Create user")');

    await expect(page.locator(".success-box")).toContainText(`User "${username}" created`);
    // Task #112 (fixed): the new row now appears immediately from the
    // create response itself (AdminUsersPanel's onCreated optimistic
    // update), no reload needed - asserting that directly here rather than
    // reloading past it.
    const userRow = page.locator(".list-item", { hasText: username });
    await expect(userRow).toBeVisible();

    await userRow.getByRole("button", { name: "Edit roles" }).click();
    await userRow.locator('label:has-text("delegated-user") input[type="checkbox"]').check();
    await userRow.getByRole("button", { name: "Save" }).click();
    await expect(page.locator(".success-box")).toContainText("Roles updated");
    await expect(userRow).toContainText("agent-builder");
    await expect(userRow).toContainText("delegated-user");

    // The teammate's real first login: forced password change, then landing
    // on "/" (not "/admin" - agent-builder isn't the tenant-admin role).
    const teammatePage = await page.context().browser().newContext().then((ctx) => ctx.newPage());
    await loginAndSetPermanentPassword(teammatePage, {
      username,
      temporaryPassword: "TempPassw0rd!",
      newPassword: "TeammatePermanent1!",
    });
    await expect(teammatePage).toHaveURL(/^(?!.*\/admin).*$/);
    await expect(teammatePage.getByRole("heading", { name: "Agentic AI Platform" })).toBeVisible();
    await teammatePage.close();
  });

  test("creating a user with a too-short temporary password is blocked client-side", async ({ adminPage: page }) => {
    const suffix = uniqueSuffix();
    await page.goto("/admin/users");
    await page.fill('section:has-text("Users & Roles") input[type="text"] >> nth=0', `short-pw-${suffix}`);
    await page.fill('section:has-text("Users & Roles") input[type="text"] >> nth=1', `short-pw-${suffix}@example.test`);
    await page.fill('section:has-text("Users & Roles") input[type="text"] >> nth=2', "A");
    await page.fill('section:has-text("Users & Roles") input[type="text"] >> nth=3', "B");
    await page.fill('section:has-text("Users & Roles") input[type="password"]', "short");
    const isInvalid = await page
      .locator('section:has-text("Users & Roles") input[type="password"]')
      .evaluate((el) => !el.checkValidity());
    expect(isInvalid).toBe(true);
  });
});
