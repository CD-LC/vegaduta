// Business case: scoping what an agent can touch (Autonomy/Guardrails
// Strategy Stage A tool allow-list + Knowledge Stage F1 collection
// allow-list), exercised entirely through AgentsPanel's inline editors.
//
// Agent/collection creation itself doesn't require an active LLM provider
// server-side (AgentService.create has no such check - only AgentsPanel's
// client-side validate() does, as a UX nicety), so setup here goes straight
// through the API with the tenant-admin's token rather than fighting the
// UI's "activate a provider first" form gate just to get a row to edit.
//
// Now lives on its own /agents route (previously stacked on "/"). The tool
// access test also switched from a free-text "comma-separated names" field
// to a checkbox list sourced live from GET /api/agents/{id}/available-tools
// (Stage B - see AgentToolProvider#listAvailableToolsFor's javadoc) - it
// selects "remember_fact" since that's the one built-in tool that's always
// resolvable with no extra setup (no knowledgeBaseEnabled flag, no MCP
// server registration needed), unlike search_knowledge_base/MCP tools which
// depend on per-test fixtures this spec doesn't set up.
const { test, expect, keycloakPasswordGrantToken, API_BASE } = require("../fixtures");

async function apiCreate(request, token, path, data) {
  const res = await request.post(`${API_BASE}${path}`, { headers: { Authorization: `Bearer ${token}` }, data });
  if (!res.ok()) throw new Error(`${path} failed: ${res.status()} ${await res.text()}`);
  return res.json();
}

test.describe("Agent tool and knowledge access control", () => {
  test("scope an agent's knowledge access to one collection and clear it again", async ({
    consolePage: page,
    request,
    tenant,
  }) => {
    const token = await keycloakPasswordGrantToken(request, tenant);
    const collectionA = await apiCreate(request, token, "/api/knowledge/collections", {
      name: "Scope Test A",
      description: "first collection",
    });
    await apiCreate(request, token, "/api/knowledge/collections", { name: "Scope Test B", description: "second" });
    await apiCreate(request, token, "/api/agents", {
      name: "Scoped Agent",
      systemPrompt: "test",
      providerName: "ANTHROPIC",
      knowledgeBaseEnabled: true,
    });

    await page.goto("/agents");
    const agentRow = page.locator(".list-item", { hasText: "Scoped Agent" });
    await agentRow.getByRole("button", { name: "Edit knowledge access" }).click();
    await agentRow.locator(`label:has-text("Scope Test A") input[type="checkbox"]`).check();
    await agentRow.getByRole("button", { name: "Save" }).click();
    await expect(page.locator(".success-box")).toContainText("Knowledge access updated");
    await expect(agentRow).toContainText("Scope Test A");
    await expect(agentRow).not.toContainText("unrestricted");

    await agentRow.getByRole("button", { name: "Edit knowledge access" }).click();
    await agentRow.locator(`label:has-text("Scope Test A") input[type="checkbox"]`).uncheck();
    await agentRow.getByRole("button", { name: "Save" }).click();
    await expect(agentRow).toContainText("unrestricted");
  });

  test("scope an agent's tool access via the checkbox picker and clear it again", async ({
    consolePage: page,
    request,
    tenant,
  }) => {
    const token = await keycloakPasswordGrantToken(request, tenant);
    await apiCreate(request, token, "/api/agents", {
      name: "Tool Scoped Agent",
      systemPrompt: "test",
      providerName: "ANTHROPIC",
    });

    await page.goto("/agents");
    const agentRow = page.locator(".list-item", { hasText: "Tool Scoped Agent" });
    await agentRow.getByRole("button", { name: "Edit tool access" }).click();

    // Checklist is fetched live from GET /api/agents/{id}/available-tools
    // (Stage B picker) rather than typed in - "remember_fact" is the one
    // built-in tool resolvable with zero extra setup (no knowledgeBaseEnabled
    // flag, no MCP server) as long as core runs with the "knowledge" profile,
    // which the rest of this suite (spec 04) already assumes is active.
    const rememberFactOption = agentRow.locator('label:has-text("remember_fact")');
    await expect(rememberFactOption).toBeVisible({ timeout: 15_000 });
    await rememberFactOption.locator('input[type="checkbox"]').check();
    await agentRow.getByRole("button", { name: "Save" }).click();
    await expect(page.locator(".success-box")).toContainText("Tool access updated");
    await expect(agentRow).toContainText("remember_fact");
    await expect(agentRow).not.toContainText("unrestricted");

    await agentRow.getByRole("button", { name: "Edit tool access" }).click();
    await agentRow.locator('label:has-text("remember_fact") input[type="checkbox"]').uncheck();
    await agentRow.getByRole("button", { name: "Save" }).click();
    await expect(agentRow).toContainText("unrestricted");
  });
});
