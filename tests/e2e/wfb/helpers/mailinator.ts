import { type Page } from "@playwright/test";
import { AUTH_HOST, escapeRe } from "./e2e-env";

// Keycloak's realm enforces email verification on every new signup
// (core/admin/.../KeycloakAdminClient.java: emailVerified=false + a
// VERIFY_EMAIL required action, deliberate as of 2026-07-25, real SMTP
// configured - "do NOT flip back to emailVerified=true without a working
// mail path"). A throwaway @example.com address can never receive that
// email, so this suite can't complete a real login without actually reading
// one. Mailinator's public inboxes (no signup/API key needed, receivable via
// a plain scrapable web page) are the standard no-credentials way to test a
// real email-verification flow end-to-end - every test-tenant email this
// suite generates uses @mailinator.com specifically so this works.
//
// Verified manually against a real delivered email (2026-07-29): the inbox
// list is NOT a semantic <table><tr> (no ARIA "row" role - it's a
// CSS-styled layout), so `getByRole("row", ...)` never matches; match by the
// visible subject text instead. Real SMTP delivery to Mailinator took ~3
// minutes in that manual check, comfortably longer than this file's
// previous 2-minute poll window - hence the longer window below. Once the
// message is open, Mailinator's own "LINKS" tab lists every URL found in the
// email as plain hrefs - reading that list directly is far more robust than
// entering the message body's iframe and hunting for a "Click here" anchor.

export async function verifyEmailViaMailinator(page: Page, mailinatorInbox: string): Promise<void> {
  const mailPage = await page.context().newPage();
  const inboxUrl = `https://www.mailinator.com/v4/public/inboxes.jsp?to=${mailinatorInbox}`;

  const POLL_ATTEMPTS = 80;
  const POLL_INTERVAL_MS = 15_000; // 80 * 15s = 20min ceiling - real delivery latency observed as variable (~3min to ~8min across two manual checks 2026-07-29, likely sending-side queuing under this session's test load), so this is deliberately generous

  let opened = false;
  for (let attempt = 0; attempt < POLL_ATTEMPTS && !opened; attempt++) {
    await mailPage.goto(inboxUrl, { waitUntil: "domcontentloaded" });
    const subjectCell = mailPage.getByText(/verify your.*email/i).first();
    const visible = await subjectCell.isVisible({ timeout: 5_000 }).catch(() => false);
    if (visible) {
      await subjectCell.click();
      opened = true;
      break;
    }
    await mailPage.waitForTimeout(POLL_INTERVAL_MS);
  }

  if (!opened) {
    await mailPage.close();
    throw new Error(
      `No Keycloak verification email arrived at ${mailinatorInbox}@mailinator.com within ~${
        (POLL_ATTEMPTS * POLL_INTERVAL_MS) / 60_000
      } minutes. Inspect ${inboxUrl} manually to confirm.`
    );
  }

  // The "LINKS" tab lists every href found in the message body as plain
  // text/anchors - grab the one pointing at this realm's login-actions
  // endpoint (the actual verification link) rather than parsing the
  // rendered HTML body.
  await mailPage.getByRole("tab", { name: /^links$/i }).click();
  const verifyLinkLocator = mailPage
    .getByRole("link", { name: new RegExp(`${escapeRe(AUTH_HOST)}/realms/.*login-actions`, "i") })
    .first();
  const linkVisible = await verifyLinkLocator.isVisible({ timeout: 10_000 }).catch(() => false);
  if (!linkVisible) {
    await mailPage.close();
    throw new Error(
      `Opened the verification email at ${mailinatorInbox}@mailinator.com but couldn't find an ` +
        "Keycloak login-actions link on its LINKS tab - Mailinator's layout or the email " +
        "template may have changed."
    );
  }
  const verifyLink = await verifyLinkLocator.getAttribute("href");
  await mailPage.close();

  if (!verifyLink) {
    throw new Error("Verification link element was visible but had no href attribute.");
  }

  await page.goto(verifyLink);
}
