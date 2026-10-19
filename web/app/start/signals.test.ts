import { describe, expect, it, vi } from "vitest";
import { loadWorkspaceSignals, type SignalsDeps } from "./signals";

function deps(over: Partial<SignalsDeps> = {}): SignalsDeps {
  return {
    getTenantSettings: vi.fn().mockResolvedValue({ plan: "PREMIUM" }),
    getProviderAvailability: vi.fn().mockResolvedValue({ canCreateAgents: true }),
    tutorAvailable: vi.fn().mockResolvedValue(true),
    getResearchStatus: vi.fn().mockResolvedValue({ enabled: true, searchConfigured: true }),
    listMcpServers: vi.fn().mockResolvedValue([{ name: "brave-search", enabled: true }, { name: "slack", enabled: false }]),
    getE2bCredential: vi.fn().mockResolvedValue({ codeExecutionTemplateId: "t1" }),
    ...over,
  };
}

describe("loadWorkspaceSignals", () => {
  it("maps every probe", async () => {
    const s = await loadWorkspaceSignals(deps());
    expect(s).toEqual({
      loaded: true,
      plan: "PREMIUM",
      planFailed: false,
      canCreateAgents: true,
      tutorOn: true,
      deepResearch: true,
      mcpEnabled: ["brave-search"],
      e2bReady: true,
    });
  });

  it("deep research needs BOTH the feature and a search key", async () => {
    const s = await loadWorkspaceSignals(deps({ getResearchStatus: vi.fn().mockResolvedValue({ enabled: true, searchConfigured: false }) }));
    expect(s.deepResearch).toBe(false);
  });

  it("an absent feature (404/503) is a definite no; any other failure is 'could not check'", async () => {
    const absent = await loadWorkspaceSignals(deps({ getResearchStatus: vi.fn().mockRejectedValue({ status: 404 }) }));
    expect(absent.deepResearch).toBe(false);
    const unavailable = await loadWorkspaceSignals(deps({ getResearchStatus: vi.fn().mockRejectedValue({ status: 503 }) }));
    expect(unavailable.deepResearch).toBe(false);
    const blip = await loadWorkspaceSignals(deps({ getResearchStatus: vi.fn().mockRejectedValue(new Error("network")) }));
    expect(blip.deepResearch).toBeNull();
  });

  it("one failed probe does not blank out the rest", async () => {
    const s = await loadWorkspaceSignals(
      deps({
        listMcpServers: vi.fn().mockRejectedValue({ status: 403 }),
        getE2bCredential: vi.fn().mockRejectedValue({ status: 403 }),
      })
    );
    expect(s.mcpEnabled).toBeNull();
    expect(s.e2bReady).toBeNull();
    expect(s.canCreateAgents).toBe(true);
    expect(s.plan).toBe("PREMIUM");
    expect(s.loaded).toBe(true);
  });

  it("no E2B credential (a 204 -> null) is a definite 'not ready'", async () => {
    const s = await loadWorkspaceSignals(deps({ getE2bCredential: vi.fn().mockResolvedValue(null) }));
    expect(s.e2bReady).toBe(false);
  });

  it("fails closed on the plan: an unreadable plan is flagged, a non-FREE answer counts as paid", async () => {
    const failed = await loadWorkspaceSignals(deps({ getTenantSettings: vi.fn().mockRejectedValue(new Error("x")) }));
    expect(failed.planFailed).toBe(true);
    expect(failed.plan).toBeNull();
    const free = await loadWorkspaceSignals(deps({ getTenantSettings: vi.fn().mockResolvedValue({ plan: "FREE" }) }));
    expect(free.planFailed).toBe(false);
    expect(free.plan).toBe("FREE");
  });

  it("keeps a missing or non-boolean availability answer as unknown rather than guessing", async () => {
    const s = await loadWorkspaceSignals(deps({ getProviderAvailability: vi.fn().mockResolvedValue({}) }));
    expect(s.canCreateAgents).toBeNull();
    const refused = await loadWorkspaceSignals(deps({ getProviderAvailability: vi.fn().mockRejectedValue(new Error("x")) }));
    expect(refused.canCreateAgents).toBeNull();
  });

  it("reports the tutor as unchecked when its probe throws", async () => {
    const s = await loadWorkspaceSignals(deps({ tutorAvailable: vi.fn().mockRejectedValue(new Error("x")) }));
    expect(s.tutorOn).toBeNull();
  });
});
