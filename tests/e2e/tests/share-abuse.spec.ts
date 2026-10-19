import { test, expect } from "@playwright/test";
import {
  createDemoSession,
  createShareLink,
  sharePath,
} from "../helpers/demo-session";

// requires V131 + 2743b59/6d3b474 deployed.
//
// Abuse-handling on the shared-agent viewer (/a/<shareId>): the "Report this
// agent" valve is always visible, opens a reason picker, and POSTs
// /api/demo/share/<shareId>/report with the chosen reason.
//
// Tagged @pending-deploy: include with `--grep @pending-deploy`, exclude with
// `--grep-invert @pending-deploy`.
//
// OUT OF AUTOMATED SCOPE — the 5-distinct-visitor (IP) cap:
// DemoShareService enforces its viewer cap per distinct source IP, and every
// request this suite makes leaves the same machine, so a Playwright run can
// never present as 5 different visitors (short of proxy infrastructure this
// suite deliberately doesn't have). Verifying that the 6th distinct IP gets
// the "reached its 5-visitor limit" state — and that existing viewers keep
// working — is a MANUAL/DEPLOY-TIME check: hit one share link from 5+
// networks (e.g. phones on mobile data) and confirm the labelled viewer_cap
// sentence on the 6th. The frontend half of that state (ERROR_COPY.viewer_cap
// rendering) doesn't need 5 IPs to exist, but provoking it honestly does.

test.describe("Shared-agent abuse reporting", { tag: "@pending-deploy" }, () => {
  test.beforeEach(async ({}, testInfo) => {
    testInfo.skip(
      testInfo.project.name !== "chromium",
      "provisions a real rate-limited demo sandbox — run on one project only"
    );
  });

  test("the report link opens the reason picker and POSTs the chosen reason", async ({
    page,
    request,
  }) => {
    const seed = await createDemoSession(request, "salon");
    test.skip(
      !seed,
      "demo sandbox unavailable from this environment (rate limited or sandbox disabled) — not a reporting regression"
    );
    const shareUrl = await createShareLink(request, seed!.token);
    test.skip(!shareUrl, "share link could not be minted — check the share endpoints first");

    await page.goto(sharePath(shareUrl!));

    // The valve is a quiet always-visible link…
    const reportLink = page.getByRole("button", { name: "Report this agent" });
    await expect(reportLink).toBeVisible();
    await reportLink.click();

    // …that opens a tiny reason picker with the four fixed reasons.
    await expect(page.getByText("What's wrong with this agent?")).toBeVisible();
    for (const label of [
      "Spam or scam",
      "Abusive or harmful",
      "Impersonating someone",
      "Something else",
    ]) {
      await expect(page.getByRole("button", { name: label })).toBeVisible();
    }
    await expect(page.getByRole("button", { name: "Cancel" })).toBeVisible();

    // One tap files it. Observe the real POST and its payload (reason ids are
    // the REPORT_REASONS ids in /a/[shareId]/page.tsx — "spam" for this one).
    const reportRequest = page.waitForRequest(
      (r) =>
        /\/api\/demo\/share\/[^/]+\/report$/.test(r.url()) &&
        r.method() === "POST"
    );
    const reportResponse = page.waitForResponse(
      (r) =>
        /\/api\/demo\/share\/[^/]+\/report$/.test(r.url()) &&
        r.request().method() === "POST"
    );
    await page.getByRole("button", { name: "Spam or scam" }).click();

    const req = await reportRequest;
    expect(req.postDataJSON()).toMatchObject({ reason: "spam" });
    const res = await reportResponse;
    expect(res.ok()).toBe(true);

    // The thank-you replaces the picker, and the flow doesn't ask the
    // reporter to do anything further.
    await expect(
      page.getByText(/Thanks — your report has been logged and this agent will be reviewed\./)
    ).toBeVisible();
    await expect(page.getByText("What's wrong with this agent?")).toHaveCount(0);
  });
});
