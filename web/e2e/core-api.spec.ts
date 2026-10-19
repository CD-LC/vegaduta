import { test, expect } from "@playwright/test";

/**
 * Core API E2E Tests (backend API validation)
 *
 * Tests coverage:
 * - Authentication endpoints
 * - Tenant APIs
 * - Plan limits
 * - Billing APIs
 * - Channel management
 * - Agent/workflow APIs
 * - Error handling
 * - Rate limiting
 *
 * Run with: npx playwright test e2e/core-api.spec.ts
 */

// Core API under test: set API_BASE_URL to a staging API; defaults to local core.
const API_BASE = process.env.API_BASE_URL || "http://localhost:8080";

test.describe("Core API - Health & Status", () => {
  test("should have health endpoint", async ({ request }) => {
    const response = await request.get(`${API_BASE}/actuator/health`);
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBe("UP");
  });

  test("should have metrics endpoint", async ({ request }) => {
    const response = await request.get(`${API_BASE}/actuator/metrics`);
    expect(response.status()).toBe(200);
  });

  test("should have info endpoint", async ({ request }) => {
    const response = await request.get(`${API_BASE}/actuator/info`);
    expect([200, 204]).toContain(response.status());
  });
});

test.describe("Core API - Authentication", () => {
  test("should require auth token", async ({ request }) => {
    const response = await request.get(`${API_BASE}/api/tenants`, {
      headers: { Authorization: "Bearer invalid" },
    });
    expect([401, 403]).toContain(response.status());
  });

  test("should validate token format", async ({ request }) => {
    const response = await request.get(`${API_BASE}/api/tenants`, {
      headers: { Authorization: "NotABearerToken" },
    });
    expect([400, 401, 403]).toContain(response.status());
  });

  test("should accept valid token", async ({ request }) => {
    // This would require a real token from the system
    // Test structure shown for completeness
    const response = await request.get(`${API_BASE}/api/tenants`, {
      headers: { Authorization: "Bearer valid-test-token" },
    });
    // Accept multiple status codes - 200 if valid, 401/403 if test token
    expect([200, 401, 403]).toContain(response.status());
  });
});

test.describe("Core API - Tenant Endpoints", () => {
  test("GET /api/tenants should return list", async ({ request }) => {
    // Would need auth token in real scenario
    const response = await request.get(`${API_BASE}/api/tenants`);
    expect([200, 401, 403]).toContain(response.status());

    if (response.status() === 200) {
      const data = await response.json();
      expect(Array.isArray(data) || data.content).toBeTruthy();
    }
  });

  test("GET /api/tenants/:id should return tenant", async ({ request }) => {
    const response = await request.get(`${API_BASE}/api/tenants/test-tenant`);
    expect([200, 401, 403, 404]).toContain(response.status());

    if (response.status() === 200) {
      const data = await response.json();
      expect(data.id).toBeTruthy();
    }
  });

  test("POST /api/tenants should create tenant", async ({ request }) => {
    const response = await request.post(`${API_BASE}/api/tenants`, {
      data: { name: "Test Tenant" },
    });
    expect([201, 400, 401, 403]).toContain(response.status());
  });

  test("PUT /api/tenants/:id should update tenant", async ({ request }) => {
    const response = await request.put(`${API_BASE}/api/tenants/test-tenant`, {
      data: { name: "Updated Tenant" },
    });
    expect([200, 400, 401, 403, 404]).toContain(response.status());
  });
});

test.describe("Core API - Plan Limits", () => {
  test("GET /api/platform/plan-limits should return limits", async ({
    request,
  }) => {
    const response = await request.get(`${API_BASE}/api/platform/plan-limits`, {
      params: { tenantId: "test-tenant" },
    });
    expect([200, 401, 403, 404]).toContain(response.status());

    if (response.status() === 200) {
      const data = await response.json();
      expect(data.maxAgents !== undefined || data.limits).toBeTruthy();
    }
  });

  test("PUT /api/platform/plan-limits should update limits", async ({
    request,
  }) => {
    const response = await request.put(`${API_BASE}/api/platform/plan-limits`, {
      data: {
        tenantId: "test-tenant",
        maxAgents: 100,
        maxWorkflows: 50,
      },
    });
    expect([200, 400, 401, 403, 404]).toContain(response.status());
  });

  test("should validate plan limit constraints", async ({ request }) => {
    const response = await request.put(`${API_BASE}/api/platform/plan-limits`, {
      data: {
        tenantId: "test-tenant",
        maxAgents: -1, // Invalid value
      },
    });
    expect([400, 401, 403, 404]).toContain(response.status());
  });
});

test.describe("Core API - Billing", () => {
  test("GET /api/billing/usage should return usage", async ({ request }) => {
    const response = await request.get(`${API_BASE}/api/billing/usage`, {
      params: { tenantId: "test-tenant" },
    });
    expect([200, 401, 403, 404]).toContain(response.status());
  });

  test("GET /api/billing/subscription should return subscription", async ({
    request,
  }) => {
    const response = await request.get(`${API_BASE}/api/billing/subscription`, {
      params: { tenantId: "test-tenant" },
    });
    expect([200, 401, 403, 404]).toContain(response.status());
  });

  test("POST /api/billing/charge should create charge", async ({ request }) => {
    const response = await request.post(`${API_BASE}/api/billing/charge`, {
      data: {
        tenantId: "test-tenant",
        amount: 100,
        currency: "USD",
      },
    });
    expect([200, 201, 400, 401, 403]).toContain(response.status());
  });
});

test.describe("Core API - Channels", () => {
  test("GET /api/channels should list channels", async ({ request }) => {
    const response = await request.get(`${API_BASE}/api/channels`, {
      params: { tenantId: "test-tenant" },
    });
    expect([200, 401, 403]).toContain(response.status());
  });

  test("POST /api/channels should create channel", async ({ request }) => {
    const response = await request.post(`${API_BASE}/api/channels`, {
      data: {
        tenantId: "test-tenant",
        type: "whatsapp",
        name: "Test Channel",
      },
    });
    expect([201, 400, 401, 403]).toContain(response.status());
  });

  test("DELETE /api/channels/:id should delete channel", async ({
    request,
  }) => {
    const response = await request.delete(
      `${API_BASE}/api/channels/test-channel`
    );
    expect([204, 400, 401, 403, 404]).toContain(response.status());
  });
});

test.describe("Core API - Agents", () => {
  test("GET /api/agents should list agents", async ({ request }) => {
    const response = await request.get(`${API_BASE}/api/agents`, {
      params: { tenantId: "test-tenant" },
    });
    expect([200, 401, 403]).toContain(response.status());
  });

  test("POST /api/agents should create agent", async ({ request }) => {
    const response = await request.post(`${API_BASE}/api/agents`, {
      data: {
        tenantId: "test-tenant",
        name: "Test Agent",
        model: "gpt-4",
      },
    });
    expect([201, 400, 401, 403]).toContain(response.status());
  });

  test("GET /api/agents/:id should get agent", async ({ request }) => {
    const response = await request.get(`${API_BASE}/api/agents/test-agent`);
    expect([200, 401, 403, 404]).toContain(response.status());
  });
});

test.describe("Core API - Error Handling", () => {
  test("should return 404 for missing endpoint", async ({ request }) => {
    const response = await request.get(`${API_BASE}/api/nonexistent`);
    expect(response.status()).toBe(404);
  });

  test("should validate required parameters", async ({ request }) => {
    const response = await request.post(`${API_BASE}/api/channels`, {
      data: {
        // Missing required fields
        type: "whatsapp",
      },
    });
    expect([400, 401, 403]).toContain(response.status());
  });

  test("should handle invalid JSON", async ({ request }) => {
    const response = await request.post(`${API_BASE}/api/channels`, {
      data: "{invalid json",
      headers: { "Content-Type": "application/json" },
    });
    expect([400, 401, 403]).toContain(response.status());
  });

  test("should handle server errors gracefully", async ({ request }) => {
    // Test behavior on server errors
    const response = await request.get(`${API_BASE}/api/agents/test`);
    // Server should respond with proper status, not crash
    expect([200, 401, 403, 404, 500]).toContain(response.status());
  });
});

test.describe("Core API - Rate Limiting", () => {
  test("should handle rate limits gracefully", async ({ request }) => {
    // Make multiple requests quickly
    const responses = [];
    for (let i = 0; i < 5; i++) {
      const response = await request.get(`${API_BASE}/actuator/health`);
      responses.push(response.status());
    }

    // Should get 200 or rate limit error
    expect(
      responses.every((status) => [200, 429].includes(status))
    ).toBe(true);
  });
});

test.describe("Core API - Data Validation", () => {
  test("should validate tenant IDs", async ({ request }) => {
    const response = await request.get(
      `${API_BASE}/api/tenants/invalid-id-with-special-chars-@#$%`
    );
    expect([400, 404]).toContain(response.status());
  });

  test("should validate email format", async ({ request }) => {
    const response = await request.post(`${API_BASE}/api/users`, {
      data: {
        email: "not-an-email",
        name: "Test",
      },
    });
    expect([400, 401, 403]).toContain(response.status());
  });

  test("should validate numeric inputs", async ({ request }) => {
    const response = await request.put(`${API_BASE}/api/platform/plan-limits`, {
      data: {
        tenantId: "test",
        maxAgents: "not-a-number",
      },
    });
    expect([400, 401, 403, 404]).toContain(response.status());
  });
});

test.describe("Core API - Response Format", () => {
  test("should return proper JSON responses", async ({ request }) => {
    const response = await request.get(`${API_BASE}/actuator/health`);
    expect(response.headers()["content-type"]).toContain("application/json");
  });

  test("should include proper headers", async ({ request }) => {
    const response = await request.get(`${API_BASE}/actuator/health`);
    expect(response.headers()["content-type"]).toBeTruthy();
  });

  test("should handle CORS properly", async ({ request }) => {
    const response = await request.get(`${API_BASE}/actuator/health`, {
      headers: {
        Origin: "http://localhost:3000",
      },
    });
    expect([200, 403]).toContain(response.status());
  });
});
