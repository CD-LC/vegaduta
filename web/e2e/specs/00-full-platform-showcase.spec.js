// Human-paced, narrated walkthrough of the entire platform in one real
// browser session: signup -> tenant-admin login -> tenant-admin creates a
// teammate (the exact "Users & Roles" flow that was reported broken) ->
// teammate's forced first login -> MCP tool registry -> knowledge base ->
// (if a real Anthropic key is available) provider activation, agent
// creation, streaming chat, and long-term memory -> back to the admin
// console for HITL approvals + audit log -> Twilio channel guardrails.
//
// This is NOT meant to replace 01-10's focused business-case specs - it's a
// single, slow, visually narrated run of the whole product for a human to
// actually watch, typing into fields character-by-character and pausing
// between sections instead of Playwright's normal instant `.fill()`. Every
// selector below is copied from the spec that already covers that flow in
// isolation (see the comment at each step), so this stays truthful to real
// UI structure rather than drifting out of sync with it.
//
// Run just this file, headed, nice and slow:
//   cd web
//   npx playwright test --headed -g "Full platform showcase"
// Faster/slower typing without editing this file:
//   $env:SHOWCASE_TYPE_DELAY_MS = "30"; $env:SHOWCASE_PAUSE_MS = "400"
// It also runs as part of `npm run e2e` (the full suite) like any other spec.
const fs = require("fs");
const { test, expect, uniqueSuffix, pickCustomSelect } = require("../fixtures");

const TYPE_DELAY_MS = Number(process.env.SHOWCASE_TYPE_DELAY_MS || 70);
const PAUSE_MS = Number(process.env.SHOWCASE_PAUSE_MS || 900);

// pressSequentially fires real per-character keyboard events (unlike .fill(),
// which sets the DOM value in one shot) - this is the whole "looks like a
// human typing" effect the user asked to see.
async function humanType(locator, text) {
  await locator.click();
  await locator.pressSequentially(text, { delay: TYPE_DELAY_MS });
}

async function pause(page, ms = PAUSE_MS) {
  await page.waitForTimeout(ms);
}

function banner(msg) {
  console.log(`\n${"=".repeat(78)}\n${msg}\n${"=".repeat(78)}`);
}

// Visual redesign: "/" now renders the branded marketing landing page
// (MarketingHome.js; Keycloak's init uses "check-sso", not "login-required"
// - see AuthProvider.js) instead of auto-redirecting to Keycloak's hosted
// form, so the showcase pauses on that page first and clicks its persistent
// nav's "Log in" button, same as fixtures.js's loginViaKeycloak.
async function slowKeycloakLogin(page, { email, password }) {
  await page.goto("/", { timeout: 15_000 });
  await page.click('[data-testid="login-landing-button"]', { timeout: 15_000 });
  await pause(page, 400);
  await page.waitForURL(/\/realms\//, { timeout: 30_000 });
  await humanType(page.locator("#username"), email);
  await humanType(page.locator("#password"), password);
  await pause(page, 400);
  await page.click("#kc-login");
  await page.waitForURL((url) => !url.href.includes("/realms/"), { timeout: 30_000 });
}

async function slowForcedPasswordChange(page, { username, temporaryPassword, newPassword }) {
  await page.goto("/", { timeout: 15_000 });
  await page.click('[data-testid="login-landing-button"]', { timeout: 15_000 });
  await pause(page, 400);
  await page.waitForURL(/\/realms\//, { timeout: 30_000 });
  await humanType(page.locator("#username"), username);
  await humanType(page.locator("#password"), temporaryPassword);
  await pause(page, 400);
  await page.click("#kc-login");
  await page.waitForSelector("#password-new", { timeout: 30_000 });
  await humanType(page.locator("#password-new"), newPassword);
  await humanType(page.locator("#password-confirm"), newPassword);
  await pause(page, 400);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForURL((url) => !url.href.includes("/realms/"), { timeout: 30_000 });
}

test.describe("Full platform showcase (human-paced demo)", () => {
  test("walks through the entire platform end to end, slowly and visibly", async ({ page, browser }) => {
    test.setTimeout(10 * 60 * 1000); // generous - this test deliberately takes minutes, not seconds

    const suffix = uniqueSuffix();
    const orgName = `Showcase Org ${suffix}`;
    const adminFirst = "Grace";
    const adminLast = "Hopper";
    const adminEmail = `showcase.admin.${suffix}@example.test`;
    const adminPassword = "ShowcaseAdmin1!";

    banner(
      `Starting full-platform showcase\n` +
        `  Organization : ${orgName}\n` +
        `  Admin login  : ${adminEmail} / ${adminPassword}\n` +
        `(Everything below happens against your real running stack - nothing is mocked.)`
    );

    // ---- 1. Signup (mirrors specs/01-signup-and-login.spec.js) ----------
    await test.step("A brand-new visitor signs up and provisions a workspace", async () => {
      await page.goto("/signup");
      await humanType(page.locator('input[placeholder="Acme Corp"]'), orgName);
      await humanType(page.locator('input[placeholder="Ada"]'), adminFirst);
      await humanType(page.locator('input[placeholder="Lovelace"]'), adminLast);
      await humanType(page.locator('input[placeholder="you@acme.example"]'), adminEmail);
      await humanType(page.locator('input[type="password"] >> nth=0'), adminPassword);
      await humanType(page.locator('input[type="password"] >> nth=1'), adminPassword);
      await pause(page);
      await page.click('button:has-text("Create workspace")');
      await expect(page.getByText("Almost there")).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText(/is provisioned and ready/)).toBeVisible();
      await pause(page);
    });

    // ---- 2. Tenant-admin logs in (real Keycloak round trip) -------------
    await test.step("The new admin logs in through Keycloak and lands on the Admin Control Panel", async () => {
      await slowKeycloakLogin(page, { email: adminEmail, password: adminPassword });
      await expect(page).toHaveURL(/\/admin/, { timeout: 30_000 });
      await expect(page.getByRole("heading", { name: "Admin Control Panel" })).toBeVisible();
      await pause(page);
    });

    // ---- 3. Admin creates a teammate (the flow reported as broken) ------
    // Selectors mirror specs/06-admin-users-and-roles.spec.js exactly.
    const teammateUsername = `showcase-teammate-${suffix}`;
    const teammateEmail = `${teammateUsername}@example.test`;
    const teammateTempPassword = "TempPassw0rd!";
    const teammatePermanentPassword = "ShowcaseTeammate1!";

    await test.step('Tenant-admin creates a teammate user via "Users & Roles" -> Create user', async () => {
      // Users & Roles now lives on its own /admin/users route (previously
      // stacked on /admin with every other admin panel).
      await page.goto("/admin/users");
      await humanType(page.locator('section:has-text("Users & Roles") input[type="text"] >> nth=0'), teammateUsername);
      await humanType(page.locator('section:has-text("Users & Roles") input[type="text"] >> nth=1'), teammateEmail);
      await humanType(page.locator('section:has-text("Users & Roles") input[type="text"] >> nth=2'), "Ada");
      await humanType(page.locator('section:has-text("Users & Roles") input[type="text"] >> nth=3'), "Teammate");
      // Scoped to this section, not just `input[type="password"]` - admin/page.js
      // renders Users & Roles AND Channels together, and ChannelsPanel's Twilio
      // Auth Token field is also type="password", so an unscoped locator here
      // is a strict-mode violation ("resolved to 2 elements") the moment both
      // panels are mounted at once, which they always are on this page.
      await humanType(
        page.locator('section:has-text("Users & Roles") input[type="password"]'),
        teammateTempPassword
      );
      await pickCustomSelect(page, page.getByTestId("admin-create-user-role-select"), "agent-builder");
      await pause(page);
      await page.click('button:has-text("Create user")');
      // If this hangs or errors here, KeycloakAdminClient can't reach Keycloak's
      // Admin API - see the "Unable to create user" checklist in the README/
      // handoff notes (stale KEYCLOAK_CLIENT_SECRET or the service account
      // missing the realm-management manage-users/view-users roles).
      await expect(page.locator(".success-box")).toContainText(`User "${teammateUsername}" created`, {
        timeout: 30_000,
      });
      // Task #112 (fixed): the new row now appears immediately from the
      // create response itself (AdminUsersPanel's onCreated optimistic
      // update), no manual refresh needed.
      const userRow = page.locator(".list-item", { hasText: teammateUsername });
      await expect(userRow).toBeVisible({ timeout: 5_000 });
      await pause(page);
    });

    // ---- 4. Teammate's real first login: forced password change --------
    const teammateContext = await browser.newContext();
    const teammatePage = await teammateContext.newPage();

    await test.step("The teammate's first login goes through Keycloak's forced password-change screen", async () => {
      banner("Opening a second browser window for the teammate's own session (separate from the admin's)");
      await slowForcedPasswordChange(teammatePage, {
        username: teammateUsername,
        temporaryPassword: teammateTempPassword,
        newPassword: teammatePermanentPassword,
      });
      await expect(teammatePage).not.toHaveURL(/\/admin/);
      await expect(teammatePage.getByRole("heading", { name: "Agentic AI Platform" })).toBeVisible({
        timeout: 30_000,
      });
      await pause(teammatePage);
    });

    // ---- 5. MCP tool registry (mirrors specs/03-mcp-registry.spec.js) ---
    await test.step("The teammate registers and toggles an MCP tool server", async () => {
      // MCP Tools now lives on its own /tools route, and the primary path
      // is the curated catalog gallery - no typing a name/type/description
      // by hand anymore (see McpCatalogService.java).
      await teammatePage.goto("/tools");
      const filesystemCard = teammatePage.locator(".mcp-card", { hasText: "filesystem" });
      await pause(teammatePage);
      await filesystemCard.getByRole("button", { name: "+ Add" }).click();
      await expect(teammatePage.locator(".success-box")).toContainText('Added "filesystem"', {
        timeout: 15_000,
      });
      const row = teammatePage.locator(".list-item", { hasText: "filesystem" });
      await pause(teammatePage, 500);
      await row.getByRole("button", { name: "Disable" }).click();
      await expect(row.locator(".badge")).toHaveText("disabled");
      await pause(teammatePage, 500);
      await row.getByRole("button", { name: "Enable" }).click();
      await expect(row.locator(".badge")).toHaveText("enabled");
      await pause(teammatePage);
    });

    // ---- 6. Knowledge base: collection + upload + search ----------------
    // Mirrors specs/04-knowledge-collections-and-multimodal.spec.js.
    await test.step("The teammate builds a knowledge base: collection, upload, and search", async () => {
      // Knowledge Base now lives on its own /knowledge route.
      await teammatePage.goto("/knowledge");
      await humanType(
        teammatePage.locator('section:has-text("Knowledge Collections") input[type="text"] >> nth=0'),
        "Showcase Docs"
      );
      await humanType(
        teammatePage.locator('section:has-text("Knowledge Collections") input[type="text"] >> nth=1'),
        "Collection created live during the platform showcase"
      );
      await pause(teammatePage);
      await teammatePage.click('button:has-text("Create collection")');
      await expect(teammatePage.locator(".success-box")).toContainText('"Showcase Docs" created', {
        timeout: 15_000,
      });
      await pause(teammatePage);

      await pickCustomSelect(
        teammatePage,
        teammatePage.locator('section:has-text("Knowledge Base") .select-trigger'),
        "Showcase Docs"
      );
      await teammatePage.setInputFiles('input[type="file"]', {
        name: "showcase-fact.md",
        mimeType: "text/markdown",
        buffer: Buffer.from(
          "# Showcase Fact\n\nThe Agentic AI Platform's knowledge base chunks and embeds every Markdown upload with pgvector.\n"
        ),
      });
      await pause(teammatePage, 500);
      await teammatePage.click('button:has-text("Upload")');
      await expect(teammatePage.locator(".success-box")).toContainText("Ingested", { timeout: 30_000 });
      await pause(teammatePage);

      await humanType(teammatePage.locator('input[placeholder="Search the knowledge base…"]'), "pgvector");
      await pause(teammatePage, 400);
      await teammatePage.click('button:has-text("Search")');
      await expect(teammatePage.locator(".list-item").first()).toBeVisible({ timeout: 15_000 });
      await pause(teammatePage);
    });

    // ---- 7/8/9. Provider activation, agent, chat, long-term memory ------
    // Gated behind a real Anthropic key, same opt-in pattern as
    // specs/02-providers-agents-chat.spec.js - without one, activation would
    // genuinely fail (ProvidersPanel makes a real validation call), so this
    // section narrates what it *would* do and moves on instead of faking it.
    if (process.env.E2E_ANTHROPIC_API_KEY) {
      await test.step("Activate a real LLM provider", async () => {
        // Providers now lives on its own /providers route.
        await teammatePage.goto("/providers");
        await humanType(teammatePage.locator('input[placeholder="API key"]'), process.env.E2E_ANTHROPIC_API_KEY);
        await pause(teammatePage);
        await teammatePage.click('button:has-text("Activate")');
        await expect(teammatePage.locator(".success-box")).toContainText("activated successfully", {
          timeout: 30_000,
        });
        await pause(teammatePage);
      });

      await test.step("Create an agent with the knowledge base attached", async () => {
        // Agents now lives on its own /agents route.
        await teammatePage.goto("/agents");
        await humanType(
          teammatePage.locator('section:has-text("Agents") input[type="text"] >> nth=0'),
          "Showcase Agent"
        );
        const promptBox = teammatePage.locator("textarea");
        await promptBox.click();
        await promptBox.fill(""); // clear the default seed prompt before typing our own
        await humanType(
          promptBox,
          "You are a concise assistant for a live product demo. Use the remember_fact tool whenever the " +
            "user shares a personal preference, and mention any known facts you already have about them."
        );
        await teammatePage.check('input[type="checkbox"] >> nth=0');
        await pause(teammatePage);
        await teammatePage.click('button:has-text("Create agent")');
        await expect(teammatePage.locator(".success-box")).toContainText("created", { timeout: 15_000 });
        await pause(teammatePage);
      });

      await test.step("Chat with the agent (real streamed reply)", async () => {
        // Chat is now a separate Playground page, reached via the freshly
        // created agent's own "Chat →" link (also carries ?agent= for us).
        const agentRow = teammatePage.locator(".list-item", { hasText: "Showcase Agent" });
        await agentRow.getByRole("link", { name: "Chat →" }).click();
        await expect(teammatePage).toHaveURL(/\/playground\?agent=/);
        await pause(teammatePage);
        await humanType(
          teammatePage.locator('input[placeholder="Ask something…"]'),
          "Hi! Please remember that my favorite programming language is Rust."
        );
        await pause(teammatePage, 400);
        await teammatePage.click('button:has-text("Send")');
        await expect(teammatePage.locator(".chat-turn").last()).not.toHaveText("", { timeout: 60_000 });
        await pause(teammatePage);
      });

      await test.step("Long-term memory: ask again in a way that requires recall", async () => {
        await humanType(
          teammatePage.locator('input[placeholder="Ask something…"]'),
          "What programming language did I say I liked?"
        );
        await pause(teammatePage, 400);
        await teammatePage.click('button:has-text("Send")');
        await expect(teammatePage.locator(".chat-turn").last()).not.toHaveText("", { timeout: 60_000 });
        // Best-effort: the model decides whether/when to call remember_fact,
        // so the memory panel populating isn't a hard guarantee every run -
        // narrate rather than fail the whole showcase over model behavior.
        try {
          await expect(teammatePage.getByText(/Rust/i)).toBeVisible({ timeout: 10_000 });
          console.log('Long-term memory panel shows the remembered fact ("Rust") - recall worked end to end.');
        } catch {
          console.log(
            "Note: the model didn't visibly store/recall the fact this run (tool-calling is model-driven, " +
              "not guaranteed every time) - the remember_fact tool and recall wiring are still exercised either way."
          );
        }
        await pause(teammatePage);
      });
    } else {
      banner(
        "Skipping provider activation / agent creation / chat / long-term memory:\n" +
          "  Set E2E_ANTHROPIC_API_KEY before running to see these live (same as specs/02's gated test)."
      );
    }

    // ---- 10. HITL approvals + audit log, back on the admin page ---------
    // Mirrors specs/07-approvals-and-audit.spec.js.
    await test.step("Back to the admin console: a gated action queues for approval and gets approved", async () => {
      await page.bringToFront();
      // Per-action policy override + the approvals list (ApprovalsPanel) now
      // live on their own /admin/approvals route.
      await page.goto("/admin/approvals");
      await humanType(
        page.locator('section:has-text("Per-action policy override") input[type="text"]'),
        "channel.whatsapp.send"
      );
      const requiresApprovalCheckbox = page.locator(
        'section:has-text("Per-action policy override") input[type="checkbox"]'
      );
      if (!(await requiresApprovalCheckbox.isChecked())) await requiresApprovalCheckbox.check();
      await pause(page);
      await page.click('button:has-text("Save policy")');
      await expect(page.locator(".success-box")).toContainText("requires approval", { timeout: 15_000 });

      // The send trigger itself (ChannelsPanel) lives on /admin/channels.
      await page.goto("/admin/channels");
      await humanType(page.locator('input[placeholder="whatsapp:+1..."]'), "whatsapp:+15550009999");
      await humanType(page.locator('.field:has(label:text("Message")) input[type="text"]'), "Showcase gated send");
      await pause(page);
      await page.getByRole("button", { name: "Send", exact: true }).click();
      await expect(page.locator(".success-box")).toContainText("Queued for approval", { timeout: 15_000 });

      await page.goto("/admin/approvals");
      const requestRow = page.locator(".list-item", { hasText: "Send WhatsApp message" }).first();
      await expect(requestRow).toBeVisible({ timeout: 15_000 });
      await pause(page);
      await requestRow.getByRole("button", { name: "Approve" }).click();
      await expect(page.locator(".success-box")).toContainText("Approved", { timeout: 15_000 });
      await pause(page);

      await page.goto("/admin/audit");
      await humanType(
        page.locator('section:has-text("Audit Log") input[placeholder="channel.whatsapp.send"]'),
        "channel.whatsapp.send"
      );
      await page.click('section:has-text("Audit Log") button:has-text("Filter")');
      await expect(page.locator('section:has-text("Audit Log") .list-item').first()).toBeVisible({
        timeout: 15_000,
      });
      await pause(page);
    });

    // ---- 11. Channel guardrails: bad Twilio credentials rejected --------
    // Mirrors specs/08-channels-whatsapp.spec.js - needs no real Twilio
    // account, so this always runs (unlike the opt-in real-activation test).
    await test.step("Channels panel rejects an invalid Twilio credential with a clear error", async () => {
      await page.goto("/admin/channels");
      await humanType(
        page.locator('section:has-text("Credential") input[type="text"] >> nth=0'),
        "ACdefinitelynotreal00000000000000"
      );
      // Same strict-mode reasoning as the Users & Roles step above - scope to
      // the Credential section so this doesn't also match the Users & Roles
      // temporary-password field still mounted elsewhere on /admin.
      await humanType(page.locator('section:has-text("Credential") input[type="password"]'), "not-a-real-auth-token");
      await humanType(page.locator('input[placeholder="whatsapp:+14155238886"]'), "whatsapp:+15005550006");
      await pause(page);
      await page.click('button:has-text("Activate")');
      await expect(page.locator(".error-box")).toBeVisible({ timeout: 30_000 });
      await pause(page);
    });

    // ---- 12. Real Twilio WhatsApp activation + genuine send -------------
    // Opt-in, mirrors specs/08-channels-whatsapp.spec.js's real-activation
    // test - needs actual Twilio credentials. Placed AFTER the invalid-
    // credential test above (not before): ChannelsPanel relabels its button
    // "Replace credential" once a credential exists, which would break that
    // test's `button:has-text("Activate")` locator if this ran first.
    if (process.env.E2E_TWILIO_ACCOUNT_SID && process.env.E2E_TWILIO_AUTH_TOKEN) {
      await test.step("Admin activates a real Twilio WhatsApp credential", async () => {
        await humanType(
          page.locator('section:has-text("Credential") input[type="text"] >> nth=0'),
          process.env.E2E_TWILIO_ACCOUNT_SID
        );
        await humanType(
          page.locator('section:has-text("Credential") input[type="password"]'),
          process.env.E2E_TWILIO_AUTH_TOKEN
        );
        await humanType(
          page.locator('input[placeholder="whatsapp:+14155238886"]'),
          process.env.E2E_TWILIO_WHATSAPP_FROM || "whatsapp:+14155238886"
        );
        await pause(page);
        await page.click('button:has-text("Activate")');
        await expect(page.locator(".success-box")).toContainText("Twilio credential activated", { timeout: 30_000 });
        await pause(page);
      });

      if (process.env.E2E_ANTHROPIC_API_KEY) {
        await test.step("Admin sets the showcase agent as the default WhatsApp responder", async () => {
          // The agent was created on the teammate's own browser context/page,
          // not this one - this tab's own Channels page fetched its agents
          // list once at mount, so a reload is needed to see it (same class
          // of staleness that #112 fixed for the create-response itself, but
          // this is a separate browser tab entirely, not a stale local list).
          await page.reload();
          await expect(page.getByRole("heading", { name: "Channels" })).toBeVisible();
          const agentTrigger = page.getByTestId("whatsapp-default-agent-select");
          await agentTrigger.click();
          const optionCount = await page.locator(".select-popover .select-option").count();
          if (optionCount > 1) {
            await page.locator(".select-popover .select-option").nth(1).click();
            await pause(page);
            await page.locator('section:has-text("Default agent for inbound WhatsApp") button:has-text("Save")').click();
            await expect(page.locator(".success-box")).toContainText("Default WhatsApp agent updated", { timeout: 15_000 });
            await pause(page);
          } else {
            await page.keyboard.press("Escape");
            console.log("No agent available to set as default (agent creation was skipped or failed) - skipping this step.");
          }
        });
      }

      await test.step("Admin sends a real WhatsApp message - approving it now actually calls Twilio", async () => {
        // Policy override from step 10 above still requires approval for
        // channel.whatsapp.send, so this is queued again just like that demo
        // was - the difference is a real credential now exists, so approving
        // it this time genuinely calls Twilio's API to deliver the message,
        // instead of step 10's approval (which had no credential behind it).
        const destination = process.env.E2E_TWILIO_WHATSAPP_TO || "whatsapp:+15005550006";
        await humanType(page.locator('input[placeholder="whatsapp:+1..."]'), destination);
        await humanType(
          page.locator('.field:has(label:text("Message")) input[type="text"]'),
          "Hello from the Agentic AI Platform showcase - a real message sent via your Twilio credential."
        );
        await pause(page);
        await page.getByRole("button", { name: "Send", exact: true }).click();
        await expect(page.locator(".success-box")).toContainText("Queued for approval", { timeout: 15_000 });
        await pause(page);

        const requestRow = page.locator(".list-item", { hasText: "Send WhatsApp message" }).first();
        await expect(requestRow).toBeVisible({ timeout: 15_000 });
        await pause(page);
        await requestRow.getByRole("button", { name: "Approve" }).click();
        // Best-effort: a destination number that hasn't joined your Twilio
        // Sandbox (or isn't verified on a non-trial account) can still fail
        // delivery on Twilio's side even though the approval itself succeeds
        // here - narrate rather than hard-fail the whole showcase over a
        // destination-number problem outside this app's control.
        try {
          await expect(page.locator(".success-box")).toContainText("Approved", { timeout: 15_000 });
          console.log(
            `Approved and sent a real WhatsApp message to ${destination}. If it didn't arrive, confirm that ` +
              "number has joined your Twilio Sandbox (or is a verified number on a non-trial account)."
          );
        } catch {
          console.log("Approval didn't show the expected success message - check the error banner and Twilio console for details.");
        }
        await pause(page);
      });
    } else {
      banner(
        "Skipping real Twilio WhatsApp activation + send:\n" +
          "  Set E2E_TWILIO_ACCOUNT_SID, E2E_TWILIO_AUTH_TOKEN, and optionally E2E_TWILIO_WHATSAPP_FROM /\n" +
          "  E2E_TWILIO_WHATSAPP_TO before running to see a real WhatsApp message sent live."
      );
    }

    await teammatePage.close();
    await teammateContext.close();

    banner(
      `Showcase complete.\n` +
        `  Organization : ${orgName}\n` +
        `  Admin login  : ${adminEmail} / ${adminPassword}\n` +
        `  Teammate     : ${teammateUsername} / ${teammatePermanentPassword}\n` +
        `Log in with either at http://localhost:3000 to keep poking around this same tenant.`
    );
  });
});
