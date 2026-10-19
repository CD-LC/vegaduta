import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

const policy = {
  egress: { restricted: true, allowList: ["api.example.com", "*.partner.io"], privateHostExceptions: ["ollama:11434"] },
  a2aClient: { enabled: true, allowedHosts: ["agents.example.com"], requireSignedCard: true, maxCallsPerTurn: 5 },
  a2aServer: { enabled: true, bindings: ["JSONRPC", "HTTP+JSON"], jsonRpcPath: "/api/a2a", restPath: "/api/a2a/rest" },
};

vi.mock("../../lib/api", () => ({
  apiFetch: vi.fn(),
  toJson: vi.fn(),
}));

import { apiFetch, toJson } from "../../lib/api";
import { OutboundPolicyPanel } from "./outbound-policy";

describe("OutboundPolicyPanel", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockResolvedValue({} as Response);
  });

  it("shows the allow-list, exceptions and A2A settings", async () => {
    vi.mocked(toJson).mockResolvedValue(policy);
    render(<OutboundPolicyPanel />);
    await waitFor(() => expect(screen.getByText("api.example.com")).toBeTruthy());
    expect(screen.getByText("*.partner.io")).toBeTruthy();
    expect(screen.getByText("ollama:11434")).toBeTruthy();
    expect(screen.getByText("Restricted to the list below")).toBeTruthy();
    expect(screen.getByText("agents.example.com")).toBeTruthy();
    expect(screen.getByText("/api/a2a/rest", { exact: false })).toBeTruthy();
    expect(screen.getByText("5")).toBeTruthy();
    expect(apiFetch).toHaveBeenCalledWith("/api/platform/security/outbound-policy");
  });

  it("says plainly when no list is set and no cap applies", async () => {
    vi.mocked(toJson).mockResolvedValue({
      ...policy,
      egress: { restricted: false, allowList: [], privateHostExceptions: [] },
      a2aClient: { ...policy.a2aClient, enabled: false, allowedHosts: [], maxCallsPerTurn: 0 },
    });
    render(<OutboundPolicyPanel />);
    await waitFor(() => expect(screen.getByText("Any public host (no list set)")).toBeTruthy());
    expect(screen.getByText("No cap")).toBeTruthy();
    expect(screen.getByText(/no remote agent is reachable/)).toBeTruthy();
  });

  it("shows the error and a refresh button when the call fails", async () => {
    vi.mocked(toJson).mockRejectedValue(new Error("boom"));
    render(<OutboundPolicyPanel />);
    await waitFor(() => expect(screen.getByText("boom")).toBeTruthy());
    expect(screen.getByLabelText("Refresh outbound policy")).toBeTruthy();
  });
});
