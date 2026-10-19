import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import type { McpServerAuthOptions } from "../lib/api/mcpToolPins";

const get = vi.fn();
vi.mock("../lib/api", () => ({ api: { getMcpServerAuthOptions: (...a: unknown[]) => get(...a) } }));

import McpServerAuthOptionsCard from "./McpServerAuthOptionsCard";

const base: McpServerAuthOptions = {
  grantKeys: true,
  oauth: { enabled: false, resource: null, metadataUrl: null, issuer: null, tenantClaim: "tenant_id", grantClaim: "mcp_grant_id", scopes: ["mcp:agents:call"] },
  origin: { mode: "enforce", allowed: [] },
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("McpServerAuthOptionsCard", () => {
  it("renders nothing when the endpoint is unavailable", async () => {
    get.mockRejectedValue(new Error("404"));
    const { container } = render(<McpServerAuthOptionsCard />);
    await waitFor(() => expect(get).toHaveBeenCalled());
    expect(container.firstChild).toBeNull();
  });

  it("describes the defaults: grant keys on, OAuth off, no browser origin allowed", async () => {
    get.mockResolvedValue(base);
    render(<McpServerAuthOptionsCard />);
    expect(await screen.findByTestId("mcp-auth-options")).toBeTruthy();
    expect(screen.getByTestId("mcp-auth-oauth").textContent).toContain("off");
    expect(screen.getByTestId("mcp-auth-origin").textContent).toContain("No browser origin is allowed");
  });

  it("shows the OAuth resource, issuer, claims and the allowed origins when configured", async () => {
    get.mockResolvedValue({
      ...base,
      oauth: { ...base.oauth, enabled: true, resource: "https://mcp.example.com/mcp", issuer: "https://auth.example.com", metadataUrl: "https://mcp.example.com/.well-known/oauth-protected-resource/mcp" },
      origin: { mode: "report", allowed: ["https://app.example.com"] },
    });
    render(<McpServerAuthOptionsCard />);
    const oauth = await screen.findByTestId("mcp-auth-oauth");
    expect(oauth.textContent).toContain("https://mcp.example.com/mcp");
    expect(oauth.textContent).toContain("mcp_grant_id");
    const origin = screen.getByTestId("mcp-auth-origin");
    expect(origin.textContent).toContain("https://app.example.com");
    expect(origin.textContent).toContain("logged, not blocked");
  });
});
