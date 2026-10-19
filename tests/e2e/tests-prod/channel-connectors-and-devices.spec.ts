import { test, expect } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn, API_URL } from "../helpers/prod-env";
import { signInToConsole } from "../helpers/prod-session";
import { collectProdErrors } from "../helpers/prod-console";

/**
 * Closes the audit gap: "Actual channel-connect functionality has zero e2e
 * coverage for 8 of the platform's 10 channels ... and for the MCP registry
 * UI beyond a bare FREE-tier rejection check."
 *
 * Everything here drives the REAL UI a tenant admin uses
 * (web/app/components/ChannelsPanel.tsx, McpPanel.tsx, DevicesPanel.tsx) -
 * not a raw REST call - which is exactly what was missing per the audit
 * (entitlement-gates-free.spec.ts's WhatsApp/MCP steps deliberately bypass
 * the UI; console-authenticated.spec.ts's /admin/channels /devices
 * /tools/servers checks are route-smoke only, not functional).
 *
 * Field-shape note: this environment has no real Telegram/Slack/Discord/
 * Meta/X bot credentials to submit, so the read-only section below proves
 * each channel's connect FORM actually renders its real fields (the thing
 * that had zero coverage) rather than faking a "CONNECTED" state with
 * garbage credentials, which would either silently no-op or spam a real
 * third-party API with junk auth - neither is an honest test. The MCP and
 * Mobile SDK sections below DO complete a full real round trip, because
 * both have a zero-credential path (the "filesystem" catalog entry; the
 * device-pairing handshake) that a fake-credential channel form does not.
 */

const CHANNEL_URL = (key: string) => `/admin/channels?channel=${key}`;

test.describe("Channel connect forms render their real fields (read-only)", () => {
  test.beforeEach(async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    testInfo.setTimeout(120_000);
    await signInToConsole(page);
  });

  const SIMPLE_CHANNELS: Array<{ key: string; labels: (string | RegExp)[] }> = [
    { key: "telegram", labels: ["Bot Token"] },
    { key: "slack", labels: ["Bot Token", "Signing Secret"] },
    { key: "discord", labels: ["Bot Token"] },
    { key: "messenger", labels: ["Page ID", "Page Access Token", "App Secret"] },
    { key: "instagram", labels: ["Page ID", "Page Access Token", "App Secret"] },
    { key: "email", labels: ["IMAP host", "Port", "Username", /^Password/] },
  ];

  for (const channel of SIMPLE_CHANNELS) {
    test(`${channel.key} connect form renders its real credential fields`, async ({ page }) => {
      const errors = collectProdErrors(page);
      await page.goto(CHANNEL_URL(channel.key), { waitUntil: "domcontentloaded" });
      await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});

      // Proves this tenant account actually reached the Channels inspector
      // rather than being bounced to "/" by AdminChannelsInner's isAdmin
      // gate (which would otherwise silently pass a form-field assertion
      // that never had a form to look at).
      expect(page.url(), `${channel.key}: did not land on /admin/channels - isAdmin gate?`).toContain("/admin/channels");

      for (const label of channel.labels) {
        await expect(page.getByLabel(label), `${channel.key}: missing field ${label}`).toBeVisible({ timeout: 15_000 });
      }
      // 2026-09-23: the Channels page was redesigned (tile grid + inspector) and
      // now carries TWO buttons matching this name, so a bare locator failed
      // with a strict-mode violation on all six channels. The form's own
      // submit is what this asserts on.
      await expect(page.getByRole("button", { name: /^(Connect|Replace)$/ }).first()).toBeVisible();

      expect(errors.unexpected(), `${channel.key} connect form: unexpected console/network errors`).toEqual([]);
    });
  }

  test("X connect form renders its real app-registration fields", async ({ page }) => {
    const errors = collectProdErrors(page);
    await page.goto(CHANNEL_URL("x"), { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});
    expect(page.url()).toContain("/admin/channels");

    await expect(page.getByLabel("Client ID")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByLabel("Client Secret")).toBeVisible();
    await expect(page.getByRole("button", { name: /^(Register app|Replace app)$/ })).toBeVisible();

    expect(errors.unexpected()).toEqual([]);
  });

  test("Voice tab renders (reuses the Twilio/WhatsApp connection, no separate form)", async ({ page }) => {
    const errors = collectProdErrors(page);
    await page.goto(CHANNEL_URL("voice"), { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});
    expect(page.url()).toContain("/admin/channels");

    // Either state is legitimate depending on whether this tenant already
    // has a Twilio WhatsApp number wired up - assert one of the two real
    // states rendered, not a crash/blank pane.
    const disconnectedHint = page.getByText(/Voice reuses your Twilio connection/i);
    const dtmfMenu = page.getByText(/DTMF/i);
    await expect(disconnectedHint.or(dtmfMenu).first()).toBeVisible({ timeout: 15_000 });

    expect(errors.unexpected()).toEqual([]);
  });

  // Mobile SDK/device pairing - real finding, not just a field check.
  // DevicePairingService's own javadoc: generateCode() is "called from an
  // authenticated console request (any signed-in tenant member, not
  // admin-only - pairing your own phone is a personal utility action ...
  // not a platform-wide credential change)". DevicesPanel.tsx's own header
  // comment repeats this: "Not gated to tenant-admin, same posture as
  // ChannelsPanel's pairing list". But the standalone /devices route
  // (web/app/devices/page.tsx) now unconditionally forwards to
  // /admin/channels?channel=mobile-sdk, and THAT page (web/app/admin/
  // channels/page.tsx's AdminChannelsInner) redirects any non-admin tenant
  // member straight to "/" before the Mobile SDK tab ever renders - so a
  // regular (non-admin) tenant member visiting /devices today cannot pair a
  // device at all, contradicting both files' own stated design. This test
  // documents the current behavior for whichever role E2E_USER_EMAIL holds;
  // if that account is a tenant admin it will pass while still being the
  // wrong test to prove the DevicesPanel javadoc's claim - see the report
  // for what re-running this with a deliberately NON-admin account would
  // need to confirm the regression.
  test("/devices reaches the Mobile SDK pairing UI without being bounced to the landing page", async ({ page }) => {
    const errors = collectProdErrors(page);
    await page.goto("/devices", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});

    // /devices forwards client-side (useEffect + router.replace), so the
    // URL check must RETRY - a bare page.url() here races the forward and
    // fails while the redirect is mid-flight (observed live 2026-09-25:
    // manual nav lands on /admin/channels fine; the sync check did not).
    await expect(
      page,
      "/devices redirected to \"/\" instead of /admin/channels - this is the isAdmin-gate-vs-personal-utility " +
        "regression documented above: a non-admin tenant member can no longer pair a phone at all."
    ).toHaveURL(/\/admin\/channels/, { timeout: 30_000 });

    // 2026-09-23: the "Pair a new device" / "Paired devices" headings are gone -
    // /devices now lands on the Channels page with the Mobile SDK panel open.
    await expect(page.getByRole("heading", { name: "Mobile SDK" })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole("button", { name: "Generate pairing code" })).toBeVisible();

    expect(errors.unexpected()).toEqual([]);
  });
});

test.describe("MCP registry: activate, toggle, and remove via the real UI (writes to production)", () => {
  test("register the zero-credential 'filesystem' catalog server, toggle it, then remove it", async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes a real MCP registry row - one project only");
    testInfo.setTimeout(120_000);

    await signInToConsole(page);
    const errors = collectProdErrors(page);

    await page.goto("/tools", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});

    // "filesystem" is McpCatalogService's zero-credential catalog entry -
    // the same one the (now stale, pre-AXON-restyle) web/e2e/specs/
    // 03-mcp-registry.spec.js exercised against staging; this is the
    // equivalent real round trip against production, on the CURRENT
    // ShadCN-based markup (no .ax-tl-* classes exist any more - see
    // McpPanel.tsx's 2026-08-04 restyle comment).
    // 2026-09-23: catalog cards are no longer role=button (the old locator found
    // nothing), so the card is found by its visible name, and the per-server
    // switch by its aria-label ("Toggle server <name>").
    const filesystemCard = page.getByRole("button", { name: "filesystem" }).first();
    await expect(filesystemCard).toBeVisible({ timeout: 20_000 });
    await filesystemCard.click();

    // Start from a clean slate: another spec (mcp-server-registration-live)
    // deliberately leaves this server registered, so if it already is, remove
    // it first - otherwise there is no Activate button to click.
    const activate = page.getByRole("button", { name: "Activate" }).locator("visible=true").first();
    if (!(await activate.isVisible({ timeout: 5_000 }).catch(() => false))) {
      await page.getByRole("button", { name: "Delete server" }).click();
      await page.getByRole("button", { name: "Confirm delete" }).click();
      await expect(page.getByRole("alert").filter({ hasText: /filesystem.*deleted/i })).toBeVisible({ timeout: 20_000 });
      await page.getByRole("button", { name: "filesystem" }).first().click();
    }

    await page.getByRole("button", { name: "Activate" }).locator("visible=true").first().click();
    await expect(page.getByRole("alert").filter({ hasText: /Activated "filesystem"/i })).toBeVisible({ timeout: 20_000 });

    const toggle = page.getByRole("switch", { name: /^toggle (server )?filesystem$/i });
    await expect(toggle).toHaveAttribute("aria-checked", "true", { timeout: 20_000 });

    await toggle.click();
    await expect(page.getByRole("alert").filter({ hasText: /filesystem.*disabled/i })).toBeVisible({ timeout: 20_000 });
    await expect(toggle).toHaveAttribute("aria-checked", "false");

    // Clean up regardless of the assertions above having already proven the
    // point - leaving a disabled duplicate registration behind on every run
    // would make this spec non-idempotent.
    await page.getByRole("button", { name: "Delete server" }).click();
    await page.getByRole("button", { name: "Confirm delete" }).click();
    await expect(page.getByRole("alert").filter({ hasText: /filesystem.*deleted/i })).toBeVisible({ timeout: 20_000 });

    expect(errors.unexpected(), "MCP registry UI: unexpected console/network errors").toEqual([]);
  });
});

test.describe("Mobile SDK device pairing: full lifecycle (writes to production)", () => {
  test("generate code -> claim -> device appears -> poll works -> revoke -> poll rejected", async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes a real device row - one project only");
    testInfo.setTimeout(120_000);

    await signInToConsole(page);
    const errors = collectProdErrors(page);

    await page.goto("/admin/channels?channel=mobile-sdk", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});
    expect(page.url(), "bounced off /admin/channels before pairing could start").toContain("/admin/channels");

    // ---- generate a real pairing code via the UI --------------------
    await page.getByRole("button", { name: "Generate pairing code" }).click();
    // 2026-09-23: the code is rendered in a monospace <div>, no longer a <strong>.
    const codeLocator = page.locator("div.font-mono").filter({ hasText: /^\d{6}$/ }).first();
    await expect(codeLocator).toBeVisible({ timeout: 20_000 });
    const code = (await codeLocator.textContent())?.trim();
    expect(code, "pairing code UI did not render a 6-digit code").toMatch(/^\d{6}$/);

    // ---- simulate the phone claiming it (DeviceGatewayController#claim -
    // permitAll(), no JWT, exactly what the Mobile SDK app itself sends -
    // there is no browser-drivable equivalent, so this is the same
    // "hit the real endpoint directly" pattern entitlement-gates-free.spec.ts
    // uses for its own backend-only steps). ------------------------------
    const deviceName = `E2E Prod Device ${Date.now()}`;
    const claimRes = await page.request.post(`${API_URL}/api/devices/claim`, {
      data: { code, deviceName, platform: "ANDROID" },
    });
    expect(claimRes.status(), `claim failed: ${await claimRes.text()}`).toBe(200);
    const claimBody = await claimRes.json();
    const deviceToken = claimBody.deviceToken;
    expect(deviceToken, "claim response carried no deviceToken").toBeTruthy();

    // ---- verify the device shows up in the console's paired-devices list
    await page.reload({ waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});
    // A reload drops the ?channel= selection (WhatsApp panel shows) - reopen the Mobile SDK tile.
    await page.getByText("Mobile SDK", { exact: true }).first().click();
    // Scoped to exactly THIS device's card, not just "any div containing
    // this text": walk up from the device's own name to the nearest
    // ancestor <div> that also has a Revoke button among its descendants
    // (the min-w-0 name block and the button row are siblings under the
    // same Card, so a plain hasText filter on "div" would otherwise match
    // either too broad a container - the whole paired-devices list,
    // matching every other real device's Revoke button too - or too
    // narrow one with no Revoke button in it at all).
    const deviceNameEl = page.getByText(deviceName, { exact: true }).first();
    await expect(deviceNameEl, "claimed device never appeared in the paired-devices list").toBeVisible({ timeout: 20_000 });
    const deviceCard = deviceNameEl.locator("xpath=ancestor::div[.//button[contains(., 'Revoke')]][1]");
    // A freshly claimed device now reads "never polled" (the old "Active" badge is gone).
    await expect(deviceCard.getByText(/never polled/i)).toBeVisible();

    // ---- a freshly-claimed, non-revoked device can poll ----------------
    const pollBeforeRevoke = await page.request.post(`${API_URL}/api/devices/poll`, {
      headers: { "X-Device-Token": deviceToken },
    });
    expect([200, 204], `poll before revoke returned ${pollBeforeRevoke.status()}`).toContain(pollBeforeRevoke.status());

    // ---- revoke it via the real UI (two-click confirm) ------------------
    await deviceCard.getByRole("button", { name: "Revoke" }).click();
    await deviceCard.getByRole("button", { name: "Confirm revoke" }).click();
    // the revoke banner renders in two panels, so .first()
    await expect(page.getByText(/Device revoked/i).first()).toBeVisible({ timeout: 20_000 });
    // Once revoked the card loses its Revoke button (so deviceCard no longer resolves) and
    // the badge renders as "REVOKED" - re-scope by the surviving "Command history" button.
    const revokedCard = page
      .getByText(deviceName, { exact: true })
      .first()
      .locator("xpath=ancestor::div[.//button[contains(., 'Command history')]][1]");
    await expect(revokedCard.getByText(/^revoked$/i)).toBeVisible({ timeout: 20_000 });

    // ---- the real finding this closes: a revoked device must be
    // rejected on its very next poll, not just show "Revoked" in the UI
    // while its token keeps working server-side. DeviceAuthResolver#run
    // throws DeviceException("Unknown or revoked device token.") ->
    // GlobalExceptionHandler maps it to 400. ------------------------------
    const pollAfterRevoke = await page.request.post(`${API_URL}/api/devices/poll`, {
      headers: { "X-Device-Token": deviceToken },
    });
    expect(pollAfterRevoke.status(), "a REVOKED device's token still authenticated a /poll call").toBe(400);
    const revokedBody = await pollAfterRevoke.json();
    expect(String(revokedBody.error)).toMatch(/unknown or revoked device token/i);

    testInfo.annotations.push({
      type: "data-left-behind",
      description: `Paired and then revoked one device ("${deviceName}") in the production tenant - left in place, revoked, as a normal audit trail row (same posture as other prod specs' agent/workflow leftovers).`,
    });

    expect(errors.unexpected(), "device pairing lifecycle: unexpected console/network errors").toEqual([]);
  });
});
