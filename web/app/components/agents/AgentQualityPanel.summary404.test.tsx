// Prod signed-in pass 2026-10-05, bug 12: an agent with no evaluations logged
// a 404 for /quality/summary on every visit. History answers 200 + empty for
// that state, so the panel must not ask for the summary at all.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, waitFor } from "@testing-library/react";

const api = vi.hoisted(() => ({
  getAgentQualityConfig: vi.fn(),
  getAgentQualitySummary: vi.fn(),
  getAgentQualityStatistics: vi.fn(),
  getAgentQualityHistory: vi.fn(),
}));
vi.mock("../../lib/api", () => ({ api }));
vi.mock("../../lib/academyUrl", () => ({
  academyHref: () => "",
  academyUrl: () => "",
  hasAcademy: () => false,
}));
vi.mock("../ui/upgrade-lock", () => ({ UpgradeLock: (): null => null }));

import AgentQualityPanel from "./AgentQualityPanel";

const AGENT = { id: "a1", name: "e2e-agent" };
const history = (n: number) => ({
  agentId: "a1",
  timestamps: Array.from({ length: n }, (_, i) => `2026-10-0${i + 1}T00:00:00Z`),
  overallScores: Array.from({ length: n }, () => 0.8),
  scoresByType: {},
  recordCount: n,
});

describe("AgentQualityPanel - no summary request for an agent with no evaluations", () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.getAgentQualityConfig.mockResolvedValue({ plan: "UNLIMITED", enabledEvaluations: [] });
    api.getAgentQualityStatistics.mockResolvedValue(null);
    api.getAgentQualitySummary.mockRejectedValue(Object.assign(new Error("Not found"), { status: 404 }));
  });
  afterEach(cleanup);

  it("skips the summary when history is empty", async () => {
    api.getAgentQualityHistory.mockResolvedValue(history(0));
    render(<AgentQualityPanel agent={AGENT as never} />);
    await waitFor(() => expect(api.getAgentQualityHistory).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(api.getAgentQualityStatistics).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 0));
    expect(api.getAgentQualitySummary).not.toHaveBeenCalled();
  });

  it("still loads the summary once the agent has evaluations", async () => {
    api.getAgentQualityHistory.mockResolvedValue(history(2));
    api.getAgentQualitySummary.mockResolvedValue({
      agentId: "a1",
      overallScore: 0.8,
      scoresByType: {},
      feedback: null,
      turnNumber: 2,
      evaluatedAt: "2026-10-02T00:00:00Z",
    });
    render(<AgentQualityPanel agent={AGENT as never} />);
    await waitFor(() => expect(api.getAgentQualitySummary).toHaveBeenCalledTimes(1));
  });
});
