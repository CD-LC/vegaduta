import { test, expect } from "@playwright/test";
import { API_URL, APP_URL, AUTH_URL, KEYCLOAK_REALM, DOMAIN, LOCALHOST_HOST_RE, SCHEME } from "../helpers/prod-env";

/**
 * The production API edge (api.<E2E_DOMAIN>) and the identity provider.
 *
 * All of this is verifiable without credentials, and it covers the failure
 * modes that actually take the platform down: an unprotected endpoint, a
 * missing/incorrect issuer, a CORS policy that blocks the real app origin,
 * or a stack trace leaking out of an error response.
 */
test.describe("API edge", () => {
  // HTTP-level assertions - no value in repeating them under the mobile
  // project. NOTE: test.skip(condition) passes ONLY fixtures to its callback,
  // never testInfo - reaching for testInfo there throws and fails every test
  // in the describe block. beforeEach is where testInfo is available.
  test.beforeEach(({}, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "HTTP-level, no need to repeat per device");
  });

  test("protected endpoints reject anonymous requests", async ({ request }) => {
    // These must never answer 200 without a bearer token. A 200 here is a
    // tenant-data breach, so the assertion is deliberately strict.
    const protectedPaths = [
      "/api/agents",
      "/api/workflows",
      "/api/knowledge/collections",
      "/api/tenants/me",
      "/api/usage/summary",
      "/api/platform/tenants",
      "/api/platform/plan-limits",
    ];

    for (const path of protectedPaths) {
      const res = await request.get(`${API_URL}${path}`, { failOnStatusCode: false });
      expect([401, 403, 404], `${path} answered ${res.status()} to an anonymous request`).toContain(res.status());

      // Whatever the rejection is, it must not carry tenant data.
      const body = await res.text();
      expect(body, `${path} leaked data in its rejection`).not.toMatch(/"tenantId"|"apiKey"|"secret"/i);
    }
  });

  test("error responses do not leak stack traces or internals", async ({ request }) => {
    const res = await request.get(`${API_URL}/api/definitely-not-a-real-endpoint-${Date.now()}`, {
      failOnStatusCode: false,
    });
    expect(res.status()).toBeGreaterThanOrEqual(400);

    const body = await res.text();
    expect(body, "API error leaked a Java stack trace").not.toMatch(
      /at (org|com|java)\.[a-z.]+\(|Exception in thread|SQLException|org\.springframework\.[a-zA-Z.]+Exception/
    );
    expect(body, "API error leaked an internal hostname").not.toMatch(new RegExp(`${LOCALHOST_HOST_RE.source}|postgres|keycloak:8080`, "i"));
  });

  test("the actuator surface is not publicly exposed", async ({ request }) => {
    // Health may legitimately be open; env/beans/configprops must never be.
    for (const path of ["/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/heapdump"]) {
      const res = await request.get(`${API_URL}${path}`, { failOnStatusCode: false });
      expect(res.status(), `${path} is publicly readable on production`).toBeGreaterThanOrEqual(400);
    }
  });

  test("CORS does not allow arbitrary origins", async ({ request }) => {
    const res = await request.fetch(`${API_URL}/api/agents`, {
      method: "OPTIONS",
      headers: {
        Origin: "https://evil.example.com",
        "Access-Control-Request-Method": "GET",
      },
      failOnStatusCode: false,
    });

    const allowOrigin = res.headers()["access-control-allow-origin"];
    expect(allowOrigin, "API reflects an arbitrary Origin back - CORS is wide open").not.toBe(
      "https://evil.example.com"
    );
    expect(allowOrigin === "*" && res.headers()["access-control-allow-credentials"] === "true").toBe(false);
  });
});

test.describe("Identity provider", () => {
  // HTTP-level assertions - no value in repeating them under the mobile
  // project. NOTE: test.skip(condition) passes ONLY fixtures to its callback,
  // never testInfo - reaching for testInfo there throws and fails every test
  // in the describe block. beforeEach is where testInfo is available.
  test.beforeEach(({}, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "HTTP-level, no need to repeat per device");
  });

  test("the realm's OIDC discovery document is correct for this environment", async ({ request }) => {
    const res = await request.get(`${AUTH_URL}/realms/${KEYCLOAK_REALM}/.well-known/openid-configuration`);
    expect(res.status()).toBe(200);

    const config = await res.json();

    // The issuer is what every token is validated against. If this drifts to
    // localhost or to the other environment, every domain-minted token stops
    // validating - the documented way sign-in breaks here.
    expect(config.issuer, "issuer is not this environment's Keycloak").toBe(`${AUTH_URL}/realms/${KEYCLOAK_REALM}`);
    expect(config.issuer).not.toMatch(LOCALHOST_HOST_RE);

    for (const endpoint of [config.authorization_endpoint, config.token_endpoint, config.jwks_uri]) {
      expect(endpoint).toContain(`auth.${DOMAIN}`);
      expect(endpoint).toMatch(new RegExp(`^${SCHEME}://`));
    }

    expect(config.code_challenge_methods_supported, "PKCE S256 not advertised").toContain("S256");
  });

  test("the realm publishes usable signing keys", async ({ request }) => {
    const res = await request.get(`${AUTH_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/certs`);
    expect(res.status()).toBe(200);

    const jwks = await res.json();
    expect(Array.isArray(jwks.keys)).toBe(true);
    expect(jwks.keys.length, "realm publishes no signing keys").toBeGreaterThan(0);
    expect(jwks.keys.some((k: { use?: string }) => k.use === "sig")).toBe(true);
  });

  test("the Keycloak admin console exposes no data anonymously", async ({ request }) => {
    // NOTE: a 200 here is NORMAL and not a finding - Keycloak serves the
    // admin console's static SPA shell to anyone and enforces auth inside it
    // via the admin REST API. So assert on the API, which is the surface
    // that actually holds data, rather than on the shell's status code.
    const res = await request.get(`${AUTH_URL}/admin/realms/${KEYCLOAK_REALM}/users`, { failOnStatusCode: false });
    expect(res.status(), "Keycloak admin REST API answered an anonymous caller").toBeGreaterThanOrEqual(400);

    const body = await res.text();
    expect(body, "Keycloak admin API leaked user data anonymously").not.toMatch(/"username"|"email"/i);
  });
});

test.describe("Transport security", () => {
  // HTTP-level assertions - no value in repeating them under the mobile
  // project. NOTE: test.skip(condition) passes ONLY fixtures to its callback,
  // never testInfo - reaching for testInfo there throws and fails every test
  // in the describe block. beforeEach is where testInfo is available.
  test.beforeEach(({}, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "HTTP-level, no need to repeat per device");
  });

  test("the app is served over HTTPS with HSTS", async ({ request }) => {
    const res = await request.get(`${APP_URL}/`);
    expect(res.status()).toBeLessThan(400);

    const headers = res.headers();
    expect(headers["strict-transport-security"], "no HSTS header on production").toBeTruthy();
  });

  test("responses carry basic hardening headers", async ({ request }) => {
    const res = await request.get(`${APP_URL}/`);
    const headers = res.headers();

    // X-Frame-Options or a CSP frame-ancestors directive - either satisfies
    // clickjacking protection.
    const framingProtected =
      Boolean(headers["x-frame-options"]) || /frame-ancestors/i.test(headers["content-security-policy"] || "");
    expect(framingProtected, "no clickjacking protection (X-Frame-Options / frame-ancestors)").toBe(true);

    expect(headers["x-content-type-options"], "missing X-Content-Type-Options: nosniff").toMatch(/nosniff/i);
  });

  test("no server-side secrets leak into the served HTML", async ({ request }) => {
    const res = await request.get(`${APP_URL}/`);
    const html = await res.text();

    const secretPatterns: Array<[RegExp, string]> = [
      [/sk-[A-Za-z0-9]{20,}/, "OpenAI-style secret key"],
      [/rzp_live_[A-Za-z0-9]+/, "Razorpay live key"],
      [/AIza[0-9A-Za-z_-]{30,}/, "Google API key"],
      [/-----BEGIN (RSA |EC )?PRIVATE KEY-----/, "private key"],
      [/"client_secret"\s*:/, "OAuth client secret"],
    ];

    for (const [pattern, label] of secretPatterns) {
      expect(pattern.test(html), `${label} appears in the production HTML`).toBe(false);
    }
  });
});
