import { test, expect, type APIRequestContext } from "@playwright/test";
import { API_URL, requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole } from "../helpers/prod-session";
import { getBearerToken, authHeaders } from "../helpers/prod-api";

/**
 * MCP server registry contract: register / list / connection-test / acknowledge / delete (SE-43).
 *
 * API-level on purpose: these paths are deterministic and do not need the LLM. Calling a registered server's
 * tool from an agent turn needs a scriptable mock model (DEPLOY-2 / SE-40) and stays covered by
 * agent-tool-execution-live and mcp-server-registration-live.
 *
 * Every entity is prefixed `e2e-tools-mcp-` and removed in afterAll (also when a test fails midway). Nothing
 * here depends on a reachable third-party server: the registrations point at a reserved `.invalid` host / a
 * binary that does not exist, so the expected outcome is "refused with a 4xx" or "registered but not
 * connected", never a 5xx and never a hang.
 */
const PREFIX = "e2e-tools-mcp-";
const RUN = Date.now().toString(36);
const created: string[] = [];

type Registration = { id: string; name: string; enabled: boolean; connectionStatus?: string; reviewStatus?: string | null };

async function listRegistrations(request: APIRequestContext, token: string): Promise<Registration[]> {
  const res = await request.get(`${API_URL}/api/mcp/registry`, { headers: authHeaders(token) });
  expect(res.status(), "GET /api/mcp/registry").toBe(200);
  return (await res.json()) as Registration[];
}

test.describe("MCP registry API (e2e-tools-mcp-)", () => {
  test.setTimeout(3 * 60 * 1000);
  let token = "";

  test.beforeEach(async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - one project only");
    await signInToConsole(page);
    token = await getBearerToken(page);
  });

  test.afterEach(async ({ page }) => {
    // Remove whatever this run registered, even if the test failed before its own cleanup.
    if (!token) return;
    for (const id of created.splice(0)) {
      await page.request
        .delete(`${API_URL}/api/mcp/registry/${id}`, { headers: authHeaders(token), failOnStatusCode: false })
        .catch(() => {});
    }
  });

  test("a registration without a name is rejected with a 4xx, not a 5xx", async ({ request }) => {
    const res = await request.post(`${API_URL}/api/mcp/registry`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json" },
      data: { type: "STDIO", enabled: false },
    });
    expect(res.status(), "missing name must be a validation error").toBeGreaterThanOrEqual(400);
    expect(res.status(), "missing name must not crash the server").toBeLessThan(500);
  });

  test("register (disabled) -> listed -> connection test answers -> delete removes it", async ({ request }) => {
    const name = `${PREFIX}remote-${RUN}`;
    const res = await request.post(`${API_URL}/api/mcp/registry`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json" },
      data: { name, type: "STREAMABLE_HTTP", description: "e2e registry contract", enabled: false, url: "https://mcp.e2e-tools.invalid/mcp" },
    });
    // The remote-URL guard may refuse the host outright; both outcomes are valid, a 5xx is not.
    expect(res.status(), `register answered ${res.status()}: ${(await res.text()).slice(0, 200)}`).toBeLessThan(500);
    test.skip(res.status() >= 400, `registration refused with ${res.status()} (remote URL guard) - nothing to list/delete`);

    const reg = (await res.json()) as Registration;
    created.push(reg.id);
    expect(reg.name).toBe(name);
    expect(reg.enabled, "registered with enabled=false").toBe(false);

    const listed = (await listRegistrations(request, token)).find((r) => r.id === reg.id);
    expect(listed, "new registration missing from GET /api/mcp/registry").toBeTruthy();

    // The phased connection test must answer in its frozen shape instead of throwing or hanging.
    const test_ = await request.post(`${API_URL}/api/mcp/registrations/${reg.id}/test`, {
      headers: authHeaders(token),
      failOnStatusCode: false,
      timeout: 60_000,
    });
    expect(test_.status(), "connection test must not 5xx").toBeLessThan(500);
    if (test_.ok()) {
      const body = (await test_.json()) as { ok?: boolean; phase?: string };
      expect(typeof body.ok, "test result carries ok").toBe("boolean");
      expect(body.ok, "an unreachable .invalid host cannot connect").toBe(false);
      expect(body.phase, "test result names the failed phase").toBeTruthy();
    }

    const del = await request.delete(`${API_URL}/api/mcp/registry/${reg.id}`, { headers: authHeaders(token) });
    expect(del.status()).toBe(204);
    created.splice(created.indexOf(reg.id), 1);
    expect((await listRegistrations(request, token)).some((r) => r.id === reg.id), "deleted registration still listed").toBe(
      false
    );
  });

  test("a STDIO registration with a command that is not allow-listed never becomes a connected server", async ({
    request,
  }) => {
    const name = `${PREFIX}stdio-${RUN}`;
    const res = await request.post(`${API_URL}/api/mcp/registry`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json" },
      data: { name, type: "STDIO", enabled: true, command: "e2e-no-such-binary", args: ["--version"] },
    });
    expect(res.status(), `register answered ${res.status()}`).toBeLessThan(500);
    if (res.status() >= 400) return; // refused by the stdio command guard - the safe outcome
    const reg = (await res.json()) as Registration;
    created.push(reg.id);

    // Registered but must not be usable: poll briefly for the connect attempt to land, then assert it did not connect.
    await expect
      .poll(async () => (await listRegistrations(request, token)).find((r) => r.id === reg.id)?.connectionStatus ?? "", {
        timeout: 30_000,
        message: "connection status never left its initial state",
      })
      .not.toMatch(/^connected$/i);
  });

  test("acknowledge and test on an unknown registration id fail cleanly, never with a 5xx", async ({ request }) => {
    const missing = "00000000-0000-4000-8000-0000000e2e43";
    // acknowledge: a 4xx (not found).
    const ack = await request.post(`${API_URL}/api/mcp/registrations/${missing}/acknowledge`, {
      headers: authHeaders(token),
      failOnStatusCode: false,
    });
    expect(ack.status(), "acknowledge of an unknown id").toBeGreaterThanOrEqual(400);
    expect(ack.status(), "acknowledge of an unknown id must not crash").toBeLessThan(500);
    // test: answers in the frozen result shape ({ok:false, phase,...}) with 200, or a 4xx; both are clean failures.
    const probe = await request.post(`${API_URL}/api/mcp/registrations/${missing}/test`, {
      headers: authHeaders(token),
      failOnStatusCode: false,
    });
    expect(probe.status(), "connection test of an unknown id must not crash").toBeLessThan(500);
    if (probe.ok()) {
      expect(((await probe.json()) as { ok?: boolean }).ok, "an unknown registration cannot test OK").toBe(false);
    }
    const del = await request.delete(`${API_URL}/api/mcp/registry/${missing}`, {
      headers: authHeaders(token),
      failOnStatusCode: false,
    });
    expect(del.status(), "deleting an unknown registration").toBeLessThan(500);
  });
});
