import { test, expect } from "@playwright/test";
import {
  createDemoSession,
  seedDemoToken,
} from "../helpers/demo-session";

// requires V131 + 2743b59/6d3b474 deployed.
//
// The "Share your agent" panel inside a /try sandbox session
// (web/app/components/try/SharePanel.tsx): states the honest 5-visitor terms
// BEFORE minting anything, calls POST /api/demo/session/share, and renders a
// copyable /a/<shareId> URL.
//
// Tagged @pending-deploy: written ahead of the deploy that carries these
// features. Include with `--grep @pending-deploy`, exclude with
// `--grep-invert @pending-deploy`.
//
// The sandbox is seeded via the API helpers (one POST /api/demo/session) and
// resumed by /try through localStorage — cheaper and far faster than driving
// the provisioning UI, and it exercises the real resume path.

test.describe("Share panel in the /try sandbox", { tag: "@pending-deploy" }, () => {
  test.beforeEach(async ({}, testInfo) => {
    testInfo.skip(
      testInfo.project.name !== "chromium",
      "provisions a real rate-limited demo sandbox — run on one project only"
    );
  });

  test("states the honest 5-visitor cap, POSTs /api/demo/session/share, and renders a copyable /a/ URL", async ({
    page,
    context,
    request,
  }) => {
    const seed = await createDemoSession(request, "kirana");
    test.skip(
      !seed,
      "demo sandbox unavailable from this environment (rate limited or sandbox disabled) — not a share-flow regression"
    );

    await seedDemoToken(context, seed!.token);
    await page.goto("/try");

    // Resumed sandbox → ready phase, composer present.
    await expect(page.getByLabel("Message your sandbox agent")).toBeVisible({
      timeout: 30_000,
    });

    // Open the panel from its own toggle.
    await page.getByRole("button", { name: "Share your agent" }).click();

    // The honest terms are shown BEFORE the link exists — the visitor knows
    // what they are minting: anyone with the link, capped at 5 people, free
    // demo limits, dies with the sandbox.
    await expect(
      page.getByText(
        /Anyone with this link can chat with your agent — up to 5 people, within the free demo limits, until the sandbox expires\./
      )
    ).toBeVisible();

    // Mint the link and observe the real endpoint being called.
    const shareResponse = page.waitForResponse(
      (r) =>
        r.url().includes("/api/demo/session/share") &&
        r.request().method() === "POST"
    );
    await page.getByRole("button", { name: "Create share link" }).click();
    const res = await shareResponse;
    expect(res.ok()).toBe(true);
    const body = await res.json();
    expect(typeof body.shareUrl).toBe("string");

    // The copyable URL: a readonly input holding an absolute /a/<shareId>
    // link, with a copy button beside it.
    const urlField = page.getByLabel("Your agent's share link");
    await expect(urlField).toBeVisible();
    const value = await urlField.inputValue();
    expect(value).toMatch(/^https:\/\/[^/]+\/a\/[A-Za-z0-9_-]+$/);
    // Deployed-environment guard: a browser-facing URL must never be localhost.
    expect(value).not.toContain("localhost");
    expect(value).not.toContain("127.0.0.1");

    await expect(
      page.getByRole("button", { name: "Copy the share link" })
    ).toBeVisible();
  });
});
