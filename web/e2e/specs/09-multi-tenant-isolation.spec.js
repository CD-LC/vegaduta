// Business case, and the single highest-value spec in this suite: two
// completely separate tenants must never see each other's data. This exact
// bug class has been found and fixed THREE times in this project's history
// (knowledge-base cross-tenant leak, admin-users cross-tenant leak, and the
// TenantContext-lost-across-reactive-thread-hop bug fixed via
// TenantContextPropagatingToolCallback) - this spec exists specifically so
// a fourth occurrence gets caught by a test run instead of a support
// ticket.
const { test, expect, signupTenant, loginViaKeycloak, keycloakPasswordGrantToken, uniqueSuffix, API_BASE } = require("../fixtures");

test.describe("Multi-tenant isolation", () => {
  test("tenant B cannot see tenant A's agents, collections, or users", async ({ browser, request }) => {
    const suffix = uniqueSuffix();
    const tenantA = await signupTenant(request, { orgName: `Isolation Tenant A ${suffix}` });
    const tenantB = await signupTenant(request, { orgName: `Isolation Tenant B ${suffix}` });

    const distinctiveAgentName = `TenantA-Only-Agent-${suffix}`;
    const distinctiveCollectionName = `TenantA-Only-Collection-${suffix}`;

    // Tenant A creates data with names distinctive enough that finding them
    // in tenant B's view would be unambiguous proof of a leak.
    const contextA = await browser.newContext();
    const pageA = await contextA.newPage();
    await loginViaKeycloak(pageA, tenantA);
    await expect(pageA).toHaveURL(/\/admin/);

    // Reach the main console as tenant A's admin isn't possible directly
    // (tenant-admin is redirected to /admin) - create the agent/collection
    // via the API using this session's own token instead, which is exactly
    // what a delegated console user would cause to exist either way.
    const bearerA = await keycloakPasswordGrantToken(request, tenantA);
    const collectionRes = await request.post(`${API_BASE}/api/knowledge/collections`, {
      headers: { Authorization: `Bearer ${bearerA}` },
      data: { name: distinctiveCollectionName, description: "should never be visible to tenant B" },
    });
    if (!collectionRes.ok()) throw new Error(`Tenant A collection create failed: ${collectionRes.status()}`);
    const agentRes = await request.post(`${API_BASE}/api/agents`, {
      headers: { Authorization: `Bearer ${bearerA}` },
      data: { name: distinctiveAgentName, systemPrompt: "test", providerName: "ANTHROPIC" },
    });
    if (!agentRes.ok()) throw new Error(`Tenant A agent create failed: ${agentRes.status()}`);

    // Tenant A's own admin user list (its own /admin/users route now, not
    // stacked on /admin) should show exactly the signup admin - sanity
    // check before we go looking for what's absent in tenant B.
    await pageA.goto("/admin/users");
    await expect(pageA.locator(".list-item", { hasText: tenantA.adminEmail })).toBeVisible();
    await contextA.close();

    // Tenant B logs in independently and must see none of the above.
    const contextB = await browser.newContext();
    const pageB = await contextB.newPage();
    await loginViaKeycloak(pageB, tenantB);
    await expect(pageB).toHaveURL(/\/admin/);

    // Agents/collections aren't rendered on /admin/users at all, so checking
    // there specifically would prove nothing - a tenant-admin's own sidebar
    // also reaches /agents and /knowledge now (unlike the old single-page
    // layout, where a tenant-admin never saw those panels at all), so check
    // there directly; the direct API probes further below remain the real
    // authoritative check either way.
    await pageB.goto("/agents");
    await expect(pageB.getByText(distinctiveAgentName)).toHaveCount(0);
    await pageB.goto("/knowledge");
    await expect(pageB.getByText(distinctiveCollectionName)).toHaveCount(0);
    await pageB.goto("/admin/users");
    await expect(pageB.getByText(tenantA.adminEmail)).toHaveCount(0);
    await expect(pageB.locator(".list-item", { hasText: tenantB.adminEmail })).toBeVisible();

    // Direct API probe with tenant B's own token, in case the UI simply
    // never renders the leaked row but the API itself still returns it -
    // this is exactly the shape of bug that slipped past a UI-only check
    // before (see TENANT-CONTEXT-TOOLCALL-FIX-VERIFICATION.md).
    const bearerB = await keycloakPasswordGrantToken(request, tenantB);
    const agentsAsB = await request.get(`${API_BASE}/api/agents`, { headers: { Authorization: `Bearer ${bearerB}` } });
    const agentNamesAsB = (await agentsAsB.json()).map((a) => a.name);
    expect(agentNamesAsB).not.toContain(distinctiveAgentName);

    const collectionsAsB = await request.get(`${API_BASE}/api/knowledge/collections`, {
      headers: { Authorization: `Bearer ${bearerB}` },
    });
    const collectionNamesAsB = (await collectionsAsB.json()).map((c) => c.name);
    expect(collectionNamesAsB).not.toContain(distinctiveCollectionName);

    await contextB.close();
  });
});
