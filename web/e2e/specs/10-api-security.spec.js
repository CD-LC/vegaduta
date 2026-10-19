// API-only business cases with no browser surface at all: webhook signature
// enforcement and bearer-token gating. Uses Playwright's `request` fixture
// directly against core - no page/browser involved, but still exercised via
// `npx playwright test` alongside the UI specs so "run the whole business-
// case suite" is one command, not two.
const { test, expect, signupTenant, createConsoleUser, keycloakPasswordGrantToken, API_BASE } = require("../fixtures");

test.describe("API security (no UI surface)", () => {
  test("inbound Twilio webhook without a valid signature is rejected", async ({ request }) => {
    const res = await request.post(`${API_BASE}/webhook/twilio/whatsapp`, {
      form: {
        From: "whatsapp:+15005550006",
        To: "whatsapp:+14155238886",
        Body: "hello",
        MessageSid: "SMxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
      },
      headers: { "X-Twilio-Signature": "not-a-real-signature" },
    });
    expect(res.status(), "an unsigned/incorrectly-signed webhook must never be accepted").toBeGreaterThanOrEqual(400);
    expect(res.status()).toBeLessThan(500);
  });

  test("protected endpoints reject requests with no bearer token", async ({ request }) => {
    // These 401 (not 404) only when the "security" Spring profile is
    // active - see NoSecurityConfig's javadoc for the permitAll() fallback
    // when it isn't. If this test fails with a 200 instead, the stack under
    // test is running without the security profile - check e2e/README.md.
    for (const path of ["/api/agents", "/api/admin/users", "/api/audit-log", "/api/approvals"]) {
      const res = await request.get(`${API_BASE}${path}`);
      expect(res.status(), `${path} without a token`).toBe(401);
    }
  });

  test("tenant-admin-only endpoints reject a token from a non-admin tenant member", async ({ request }) => {
    const tenant = await signupTenant(request);
    const consoleUser = await createConsoleUser(request, tenant);
    // Console users get a temporary Keycloak password - the password grant
    // still succeeds with it (Keycloak doesn't block the grant itself on
    // "temporary", only the browser's required-action flow enforces the
    // change), which is exactly what's needed here: a valid, non-admin
    // token to probe with. keycloakPasswordGrantToken takes a
    // { adminEmail, adminPassword } shape - consoleUser's fields line up
    // with that directly (username/temporaryPassword play the same role).
    const nonAdminToken = await keycloakPasswordGrantToken(request, {
      adminEmail: consoleUser.username,
      adminPassword: consoleUser.temporaryPassword,
    });

    const res = await request.get(`${API_BASE}/api/audit-log`, {
      headers: { Authorization: `Bearer ${nonAdminToken}` },
    });
    expect(res.status()).toBe(403);
  });
});
