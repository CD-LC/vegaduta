import { test, expect } from "@playwright/test";
import { API_URL, requireUserCredentials } from "../helpers/prod-env";
import { signInToConsole } from "../helpers/prod-session";
import { collectProdErrors } from "../helpers/prod-console";
import { getBearerToken, authHeaders } from "../helpers/prod-api";

/**
 * Jev tenant BYOK (shipped 2026-09-25, core + /jev console page).
 *
 * Contract under test (core/agents/decision/JevController.java):
 *   GET    /api/jev/status            -> {enabled, available, keySource, credential}
 *   GET    /api/jev/credential/status -> 200 credential | 204 none
 *   POST   /api/jev/credential        -> validate-before-persist; a bad key is a 400, never stored
 *   DELETE /api/jev/credential        -> 204
 *   POST   /api/jev/playground        -> 400 unconfigured | 400 validation | 502 upstream | 200 {model,keySource,answers,usage}
 *
 * Why these matter as a consumer flow: this is the "bring your own decision
 * key" surface a tenant sees before trusting the platform with real routing
 * decisions. The dangerous failure modes are silent ones - a credential
 * endpoint that stores a bad key (every subsequent decision call fails
 * upstream), a status endpoint that lies about whose key is in play, or a
 * playground that swallows upstream errors instead of surfacing them (the
 * internal noul/choose/score paths are deliberately fail-open; the
 * playground contract is deliberately fail-VISIBLE - confusing the two is
 * the exact misalignment this spec watches for).
 *
 * The credential POST test uses a deliberately-invalid key: a correct
 * implementation rejects it (400) and stores nothing, so this test never
 * leaves data behind and needs no cleanup. If it ever returns 201, that IS
 * the bug being hunted (validate-before-persist broken) - and the test
 * deletes the row it created rather than leaving a poisoned credential.
 */
test.describe("Jev BYOK - anonymous edge", () => {
  test.beforeEach(({}, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "HTTP-level, no need to repeat per device");
  });

  test("the /api/jev/* surface rejects anonymous callers", async ({ request }) => {
    for (const [method, path] of [
      ["GET", "/api/jev/status"],
      ["GET", "/api/jev/credential/status"],
      ["POST", "/api/jev/credential"],
      ["DELETE", "/api/jev/credential"],
      ["POST", "/api/jev/playground"],
    ] as const) {
      const res = await request.fetch(`${API_URL}${path}`, {
        method,
        data: method === "POST" ? {} : undefined,
        failOnStatusCode: false,
      });
      expect(
        [401, 403],
        `${method} ${path} answered ${res.status()} anonymously - tenant keyring must never be public`
      ).toContain(res.status());
    }
  });

  test("the /jev console page is gated for logged-out visitors", async ({ page }) => {
    await page.goto("/jev", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});
    const body = await page.locator("body").innerText();
    // Logged-out visitors must not see the credential/playground controls -
    // the marketing landing or a sign-in prompt is correct; a Jev workbench
    // or a stack trace is not.
    expect(body).not.toMatch(/Application error: a client-side exception/i);
    // "playground" alone is unsafe here - the landing page a logged-out
    // visitor gets bounced to uses the word in its own marketing copy. Gate
    // on strings that only the Jev workbench itself renders.
    expect(body).not.toMatch(/TypeSafe API key|Jev status|System One/i);
  });
});

test.describe("Jev BYOK - authenticated contract", () => {
  test.beforeEach(async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "API-level contract - one project only");
    testInfo.setTimeout(120_000);
    await signInToConsole(page);
  });

  test("GET /api/jev/status returns the documented shape for a real tenant", async ({ page, request }) => {
    const token = await getBearerToken(page);
    const res = await request.get(`${API_URL}/api/jev/status`, { headers: authHeaders(token) });
    expect(res.status(), "jev status must be 200 for a signed-in tenant").toBe(200);

    const body = await res.json();
    // JevStatusResponse record: {enabled, configured, source, tenantCredential}
    // - source is null when nothing resolves, else a KeySource enum value
    //   (TENANT / PLATFORM / ENV per web/app/jev/page.tsx's badges).
    expect(typeof body.enabled, "status.enabled missing/not boolean").toBe("boolean");
    expect(typeof body.configured, "status.configured missing/not boolean").toBe("boolean");
    expect(
      body.source === null || typeof body.source === "string",
      `status.source had unexpected shape: ${JSON.stringify(body.source)}`
    ).toBe(true);
    expect(
      body.tenantCredential === null || typeof body.tenantCredential === "object",
      `status.tenantCredential had unexpected shape: ${JSON.stringify(body.tenantCredential)}`
    ).toBe(true);
    // The status response must NEVER carry the key material itself.
    expect(JSON.stringify(body)).not.toMatch(/sk-|api[_-]?key"\s*:\s*"[^"]{8,}/i);
  });

  test("the /jev console page renders status + key controls for a signed-in tenant", async ({ page }) => {
    const errors = collectProdErrors(page);
    await page.goto("/jev", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});

    await expect(page.getByText(/Application error: a client-side exception/i)).toHaveCount(0);
    await expect(page.getByText(/This page could not be found/i)).toHaveCount(0);

    // The page must render its real Jev surface - the key-source status and
    // the BYOK card are the features a consumer came for. Poll rather than
    // read once: the status fetch is client-side after hydration.
    await expect
      .poll(
        async () => {
          const body = await page.locator("body").innerText();
          return /jev|typesafe|key source|playground|bring your own/i.test(body);
        },
        { timeout: 25_000, message: "/jev never rendered its Jev surface for a signed-in tenant" }
      )
      .toBe(true);

    expect(errors.unexpected(), "console/network errors on /jev").toEqual([]);
  });

  test("POST /api/jev/credential rejects an invalid key and persists nothing", async ({ page, request }) => {
    const token = await getBearerToken(page);

    const before = await request.get(`${API_URL}/api/jev/credential/status`, { headers: authHeaders(token) });
    expect([200, 204]).toContain(before.status());

    const res = await request.post(`${API_URL}/api/jev/credential`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json" },
      data: { apiKey: `e2e-definitely-invalid-${Date.now()}` },
      failOnStatusCode: false,
    });

    if (res.status() === 201 || res.status() === 200) {
      // validate-before-persist accepted an obvious garbage key. Clean up the
      // row it just wrote so this tenant is not left with a poisoned
      // credential, THEN fail.
      await request.delete(`${API_URL}/api/jev/credential`, { headers: authHeaders(token) });
      expect.unreachable(
        "POST /api/jev/credential accepted a deliberately-invalid key - validate-before-persist is broken " +
          "(JevCredentialValidator is meant to reject 401/403 from the real /v1/systemone probe)"
      );
    }
    // 400 = validation rejected (correct). Anything else (500, 502) means the
    // failure surfaced at the wrong layer - a raw upstream/proxy error rather
    // than a clean "this key does not work".
    expect(res.status(), "invalid-key rejection surfaced as the wrong error class").toBe(400);

    const after = await request.get(`${API_URL}/api/jev/credential/status`, { headers: authHeaders(token) });
    expect(
      after.status(),
      "credential status changed after a rejected activation - the rejected key was persisted"
    ).toBe(before.status());
  });

  test("POST /api/jev/playground honors its bounds and never hides an upstream failure", async ({ page, request }) => {
    const token = await getBearerToken(page);

    // Bound check: >10 questions must be a clean 400 regardless of key state.
    const tooMany: Record<string, unknown> = {};
    for (let i = 0; i < 12; i++) tooMany[`q${i}`] = { type: "noul", instructions: "is this fine?" };
    const bounded = await request.post(`${API_URL}/api/jev/playground`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json" },
      data: { state: "e2e bounds check", questions: tooMany },
      failOnStatusCode: false,
    });
    // If no key is configured the endpoint correctly 400s earlier - either
    // way the status must be 400; a 500 here is a real misalignment (an
    // unhandled IllegalArgumentException reaching the generic handler).
    expect(bounded.status(), "playground bounds violation did not produce a clean 400").toBe(400);

    // Unsupported question type likewise - a client mistake, never a 500.
    const badType = await request.post(`${API_URL}/api/jev/playground`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json" },
      data: { state: "e2e", questions: { q1: { type: "telepathy", instructions: "guess" } } },
      failOnStatusCode: false,
    });
    expect(badType.status(), "unknown question type did not produce a clean 400").toBe(400);

    // A valid call: either the tenant/platform has a key (200 with the full
    // {model,keySource,answers,usage} contract, or an honest 502 when the
    // upstream rejects the key) or there is no key at all (400 with the
    // JevNotConfigured message). Anything else - a bare 500, a 200 with a
    // missing answers object, a silent fallback - is the fail-open/
    // fail-visible misalignment this spec exists to catch.
    const valid = await request.post(`${API_URL}/api/jev/playground`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json" },
      data: {
        state: "e2e playground probe - a visitor asked about pricing",
        questions: {
          intent: {
            type: "choice",
            instructions: "Classify the visitor's intent.",
            criteria: { sales: "asking about buying or pricing", support: "asking for help" },
          },
        },
      },
      failOnStatusCode: false,
    });

    if (valid.status() === 200) {
      const body = await valid.json();
      expect(body.keySource, "200 without keySource - the caller cannot tell whose key answered").toBeTruthy();
      expect(body.answers, "200 without an answers object").toBeTruthy();
      expect(body.answers.intent, "answers lacks the asked question key").toBeTruthy();
    } else {
      expect(
        [400, 502],
        `playground answered ${valid.status()} - expected 200 (ok), 400 (not configured / bad request) or 502 (honest upstream failure)`
      ).toContain(valid.status());
      const body = await valid.json().catch(() => ({}));
      expect(
        JSON.stringify(body),
        "error body leaked key material or internals"
      ).not.toMatch(/sk-|stack|\.java:/i);
    }
  });
});
