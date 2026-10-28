import { expect, test as base, type Page } from "@playwright/test";
import { API_BASE_URL } from "../helpers/e2e-env";

/**
 * The beta release smoke: the handful of real journeys a green unit suite cannot prove. Every check here is
 * the regression for a bug that once shipped past a fully green build —
 *
 *   - sign-in contract: /api/local-auth/config + a rendered panel (a broken proxy once showed a dead page),
 *   - the PIN hint: an unenrolled browser silently hid the PIN pad with no explanation,
 *   - the edge manifest: contextWindowSize fields drive the tool-budget scaling that once trimmed the
 *     image-generation tools out of the prompt entirely,
 *   - the edge console cards: the media-generation surface the user actually downloads from,
 *   - the on-screen keyboard: locale -> auto-open -> types into the focused input -> the close button works
 *     (a pointer-capture bug once swallowed its click),
 *   - one real assistant turn: the end-to-end sign-in -> composer -> model -> reply path.
 *
 * Run it with `npx playwright test -c playwright.personal.config.ts` against a personal stack. The signed-in
 * checks need the owner credentials (E2E_PERSONAL_EMAIL / E2E_PERSONAL_PASSWORD); without them they skip with
 * a reason rather than fail, matching the edition suite.
 */
const PERSONAL = process.env.E2E_EDITION === "personal";
const OWNER_EMAIL = process.env.E2E_PERSONAL_EMAIL || "";
const OWNER_PASSWORD = process.env.E2E_PERSONAL_PASSWORD || "";
const HAS_OWNER = !!(OWNER_EMAIL && OWNER_PASSWORD);

const SKIP_REASON =
  "personal smoke: run it with playwright.personal.config.ts against a personal stack";

/**
 * `owner`: a page signed in as the owner through the console's own same-origin /api/local-auth/login proxy
 * (the refresh token lands in an HttpOnly cookie, exactly as the sign-in form does). Identical to the fixture
 * in personal-edition.spec.ts, duplicated deliberately: the two suites must not share edits.
 */
const test = base.extend<{ owner: Page }>({
  owner: async ({ page, baseURL }, use) => {
    await page
      .context()
      .addCookies([
        {
          name: "NEXT_LOCALE",
          value: "en",
          url: baseURL || "http://localhost:3000",
        },
      ]);
    const res = await page.request.post("/api/local-auth/login", {
      data: { identifier: OWNER_EMAIL, password: OWNER_PASSWORD },
    });
    expect(
      res.status(),
      "owner sign-in through /api/local-auth/login (is first-run setup finished?)",
    ).toBe(200);
    await use(page);
  },
});

test.describe("personal smoke: sign-in contract", () => {
  test.skip(!PERSONAL, SKIP_REASON);

  test("/api/local-auth/config answers the shape the sign-in panel needs", async ({
    request,
  }) => {
    const res = await request.get("/api/local-auth/config");
    expect(res.status()).toBe(200);
    const body = (await res.json()) as {
      setupRequired?: boolean;
      credentialTypes?: unknown;
    };
    expect(body.setupRequired, "first-run setup already done").toBe(false);
    expect(
      Array.isArray(body.credentialTypes),
      "credentialTypes is a list",
    ).toBe(true);
    expect(
      body.credentialTypes,
      "a personal owner always has a password verifier",
    ).toContain("password");
  });

  test("a wrong password is rejected and creates no session", async ({
    page,
  }) => {
    const res = await page.request.post("/api/local-auth/login", {
      data: {
        identifier: "smoke-nobody@example.invalid",
        password: "definitely-wrong-password",
      },
    });
    expect(
      res.status(),
      "bad credentials must be a 4xx, never 5xx",
    ).toBeGreaterThanOrEqual(400);
    expect(res.status()).toBeLessThan(500);
    const cookies = await page.context().cookies();
    expect(
      cookies.filter((c) => c.name.toLowerCase().includes("refresh")),
      "no refresh cookie",
    ).toEqual([]);
  });

  test("/login renders the sign-in panel", async ({ page }) => {
    await page.goto("/login");
    // The main view shows the passkey button when the browser supports WebAuthn; otherwise it falls back to
    // the password form. Either is proof the panel mounted — a broken proxy once rendered a dead page here.
    const passkey = page.getByRole("button", {
      name: /sign in with a passkey/i,
    });
    const passwordForm = page.locator('input[type="password"]');
    await expect(
      passkey.or(passwordForm).first(),
      "a rendered sign-in method",
    ).toBeVisible();
  });

  test("a browser with no enrolled device is told why the PIN pad is missing", async ({
    page,
  }) => {
    // The hint only makes sense when the account actually has a PIN; the config lists registered verifiers.
    const cfg = (await (
      await page.request.get("/api/local-auth/config")
    ).json()) as { credentialTypes?: string[] };
    test.skip(
      !(cfg.credentialTypes ?? []).includes("pin"),
      "the owner has no PIN enrolled - there is nothing to hint about",
    );
    await page.goto("/login");
    // Fresh context, no device cookie: the PIN pad is correctly absent, but the reason must be on screen.
    await expect(
      page.getByText(/only works in the browser it was set up in/i),
    ).toBeVisible();
  });
});

test.describe("personal smoke: edge model surface", () => {
  test.skip(!PERSONAL, SKIP_REASON);

  test("the edge manifest lists models with the fields the tool budget reads", async ({
    request,
  }) => {
    const res = await request.get(`${API_BASE_URL}/api/edge/manifest`);
    expect(res.status()).toBe(200);
    const body = (await res.json()) as {
      models?: Array<{
        id?: unknown;
        kind?: unknown;
        contextWindowSize?: unknown;
      }>;
    };
    expect(Array.isArray(body.models)).toBe(true);
    expect(body.models!.length).toBeGreaterThan(0);
    for (const m of body.models!) {
      expect(typeof m.id, `model id: ${JSON.stringify(m)}`).toBe("string");
      expect(typeof m.kind, `model kind: ${JSON.stringify(m)}`).toBe("string");
      expect(
        "contextWindowSize" in m,
        `${m.id} carries the contextWindowSize field`,
      ).toBe(true);
    }
    // contextWindowSize drives how many tools the edge prompt advertises; every model being null once let
    // the budget trim the media tools away entirely.
    expect(
      body.models!.some(
        (m) =>
          typeof m.contextWindowSize === "number" &&
          (m.contextWindowSize as number) > 0,
      ),
      "at least one model advertises a real context window",
    ).toBe(true);
  });
});

test.describe("personal smoke: signed in as the owner", () => {
  test.skip(!PERSONAL, SKIP_REASON);
  test.skip(
    !HAS_OWNER,
    "set E2E_PERSONAL_EMAIL and E2E_PERSONAL_PASSWORD (the owner after first-run setup)",
  );

  test("the edge console advertises on-device image and audio generation", async ({
    owner,
  }) => {
    await owner.goto("/edge");
    await expect(owner.getByText(/On-device image generation/)).toBeVisible();
    await expect(owner.getByText(/On-device audio generation/)).toBeVisible();
  });

  test("picking a non-Latin language opens its on-screen keyboard; it types into the composer and closes", async ({
    owner,
    baseURL,
  }) => {
    await owner
      .context()
      .addCookies([
        {
          name: "NEXT_LOCALE",
          value: "hi",
          url: baseURL || "http://localhost:3000",
        },
      ]);
    await owner.goto("/assistant");
    // Out of the box means the board opens itself when a layout language is selected — no toggle hunting.
    const board = owner.getByRole("dialog", { name: "On-screen keyboard" });
    await expect(
      board,
      "the Hindi keyboard should open itself once the locale is Hindi",
    ).toBeVisible({ timeout: 15_000 });
    // Focus the composer, press one letter key (क is in the Hindi default layer), prove it was typed into the
    // input that had focus — the board's pointerdown keeps focus there.
    const composer = owner.locator("textarea:visible").first();
    await composer.focus();
    await board.getByRole("button", { name: "क" }).click();
    await expect(composer).toHaveValue(/क/);
    // Regression for the pointer-capture bug that swallowed the close click.
    await board.getByRole("button", { name: "Close keyboard" }).click();
    await expect(board).toBeHidden();
  });

  test("one assistant turn comes back through the configured model", async ({
    owner,
  }) => {
    // A cold local model can take well over the default 60s to answer its first turn.
    test.setTimeout(180_000);
    await owner.goto("/assistant");
    const composer = owner.getByLabel(/ask or tell me what to do/i).first();
    await expect(
      composer,
      "the assistant composer never became ready - is a model configured for the assistant?",
    ).toBeEnabled({ timeout: 30_000 });
    await composer.fill("Reply with exactly the word READY and nothing else.");
    await composer.press("Enter");
    const reply = owner.locator('[data-role="assistant"]').last();
    await expect(reply, "an assistant reply turn").toBeVisible({
      timeout: 150_000,
    });
    await expect(reply).toContainText(/\S/);
  });
});
