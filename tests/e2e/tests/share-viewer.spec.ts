import { test, expect } from "@playwright/test";
import {
  createDemoSession,
  createShareLink,
  sharePath,
  DEMO_API_BASE,
} from "../helpers/demo-session";

// requires V131 + 2743b59/6d3b474 deployed.
//
// /a/<shareId> — the public viewer for a shared sandbox agent
// (web/app/a/[shareId]/page.tsx + layout.tsx). The audience is a stranger who
// was handed a link: the page must say what it is (provenance banner), keep
// its abuse valve visible, convert honestly ("Built with VegaDūta" footer),
// and map every backend failure to a labelled sentence — never a raw error,
// never a redirect to a console page.
//
// Tagged @pending-deploy: include with `--grep @pending-deploy`, exclude with
// `--grep-invert @pending-deploy`.

test.describe("Shared-agent public viewer (/a/<shareId>)", { tag: "@pending-deploy" }, () => {
  test.beforeEach(async ({}, testInfo) => {
    testInfo.skip(
      testInfo.project.name !== "chromium",
      "the happy-path test provisions a real rate-limited demo sandbox — run this file on one project only"
    );
  });

  test("a fresh share renders name, provenance, report link and footer, and a message gets a reply bubble", async ({
    page,
    request,
  }) => {
    test.setTimeout(120_000);

    const seed = await createDemoSession(request, "kirana");
    test.skip(
      !seed,
      "demo sandbox unavailable from this environment (rate limited or sandbox disabled) — not a viewer regression"
    );
    const shareUrl = await createShareLink(request, seed!.token);
    test.skip(!shareUrl, "share link could not be minted — check the share endpoints before blaming the viewer");

    // Canonical agent name straight from the public share endpoint — the same
    // source the page itself renders from.
    const infoRes = await request.get(
      `${DEMO_API_BASE}/api/demo/share/${sharePath(shareUrl!).split("/").pop()}`
    );
    expect(infoRes.ok()).toBe(true);
    const info = await infoRes.json();
    expect(typeof info.agentName).toBe("string");
    const agentName: string = info.agentName;

    await page.goto(sharePath(shareUrl!));

    // Header badge + the provenance banner: a stranger must know this is
    // user-generated demo content, not something the platform wrote.
    await expect(page.getByText("Shared agent", { exact: true })).toBeVisible();
    await expect(
      page.getByText(/is a demo agent someone built in the VegaDūta sandbox/)
    ).toBeVisible();
    await expect(page.getByText(agentName).first()).toBeVisible();

    // The abuse valve and the provenance footer are both present.
    await expect(
      page.getByRole("button", { name: "Report this agent" })
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: /Built with VegaDūta — create yours free/ })
    ).toBeVisible();

    // Send a message as the anonymous visitor and expect a real reply bubble.
    const composer = page.getByLabel(`Message ${agentName}`);
    await expect(composer).toBeVisible();
    await composer.fill("Hello! Are you open tomorrow morning?");

    const chatResponse = page.waitForResponse(
      (r) =>
        /\/api\/demo\/share\/[^/]+\/chat/.test(r.url()) &&
        r.request().method() === "POST",
      { timeout: 60_000 }
    );
    await page.getByRole("button", { name: "Send", exact: true }).click();

    // The visitor's own bubble appears immediately.
    await expect(
      page.getByText("Hello! Are you open tomorrow morning?")
    ).toBeVisible();

    const res = await chatResponse;
    expect(res.ok()).toBe(true);
    const body = await res.json();
    expect(typeof body.reply).toBe("string");
    // The reply body is rendered as an agent bubble. Match on its first line
    // (bubbles render whitespace-pre-wrap, so a multi-line reply is split
    // across lines in the DOM).
    const snippet = (body.reply as string)
      .split("\n")
      .map((l) => l.trim())
      .filter(Boolean)[0]
      ?.slice(0, 60);
    expect(snippet && snippet.length > 0).toBe(true);
    await expect(page.getByText(snippet!, { exact: false }).first()).toBeVisible({
      timeout: 15_000,
    });
  });

  test("an invalid shareId shows the labelled invalid-link state — not a raw error, not a redirect", async ({
    page,
  }) => {
    // No sandbox burned: the route always renders, and the backend answers
    // 404 {"error":"invalid"} for an unknown id, which the page maps to its
    // labelled "invalid" sentence (see ERROR_COPY in page.tsx).
    const bogus = `e2e-invalid-${Date.now()}`;
    await page.goto(`/a/${bogus}`);

    await expect(
      page.getByText(/This shared agent isn't available any more — its owner may have turned sharing off, or the link is wrong\./)
    ).toBeVisible({ timeout: 20_000 });
    // The honest next step, not a dead end.
    await expect(
      page.getByRole("link", { name: /Build your own free agent — no signup/ })
    ).toBeVisible();
    // Still on the share URL — no console redirect, no error page swap.
    expect(page.url()).toContain(`/a/${bogus}`);
    // The abuse valve stays visible even in the failure state.
    await expect(
      page.getByRole("button", { name: "Report this agent" })
    ).toBeVisible();
  });

  test("every /a/ page carries robots noindex — user-generated content stays out of the index", async ({
    page,
  }) => {
    // The meta comes from /a/[shareId]/layout.tsx and is identical for every
    // shareId, so an invalid id (which burns no sandbox) proves the rule.
    await page.goto(`/a/e2e-noindex-${Date.now()}`);
    const robots = page.locator('head meta[name="robots"]');
    await expect(robots).toHaveCount(1);
    const content = ((await robots.getAttribute("content")) || "").toLowerCase();
    expect(content).toContain("noindex");
    expect(content).toContain("nofollow");
  });
});
