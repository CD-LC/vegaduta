// The playground's limits read from the settings catalog: the wording, unit, allowed range and the layer each
// value comes from are the server's, a pinned value says it is locked and why, and an older core without the
// catalog still shows the limits the old way. Real components and the real English messages; only the API
// clients are replaced.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import type { AgentRuntimeLimitsView } from "../../../../lib/api/agentRuntimeLimits";
import { CATALOG, eff, makeClient, renderSettings } from "../../../settings/settingsTestKit";
import type { PlaygroundAgent } from "../types";

const net = vi.hoisted(() => ({
  getAgentRuntimeLimits: vi.fn(),
  getAgentRunBudget: vi.fn(),
}));

vi.mock("../../../../lib/api", () => ({ api: { getAgentRunBudget: net.getAgentRunBudget } }));
vi.mock("../../../../lib/api/agentRuntimeLimits", () => ({
  agentRuntimeLimitsApi: { getAgentRuntimeLimits: net.getAgentRuntimeLimits },
}));

import { LimitsSection } from "./LimitsSection";

const AGENT: PlaygroundAgent = { id: "agent-1", name: "Mailer", providerName: "OPENAI", model: "o3-mini" };

const LEGACY: AgentRuntimeLimitsView = {
  overrides: {},
  effective: {
    maxToolSteps: 9,
    turnTimeoutSeconds: 120,
    toolTimeoutSeconds: 0,
    toolOutputMaxChars: 8000,
    retrievalTopK: 5,
    retrievalMinScore: 0.25,
    continuationPasses: 1,
    layers: { maxToolSteps: "TENANT" },
  },
  bounds: {},
};

const EFFECTIVE = {
  values: {
    "agent.limits.maxToolSteps": eff(12, "agent"),
    "agent.limits.turnTimeoutSeconds": eff(300, "tenant"),
    "agent.limits.toolOutputMaxChars": eff(20000, "platform", { locked: true, lockedReason: "Pinned by the platform team" }),
  },
};

beforeEach(() => {
  net.getAgentRuntimeLimits.mockReset().mockResolvedValue(LEGACY);
  net.getAgentRunBudget.mockReset().mockRejectedValue(new Error("not found"));
});
afterEach(cleanup);

describe("LimitsSection from the settings catalog", () => {
  it("shows the catalog's wording, value with its unit, allowed range and source for the agent", async () => {
    const client = makeClient({ effective: EFFECTIVE });
    renderSettings(<LimitsSection agent={AGENT} settingsClient={client} />);

    const steps = await screen.findByTestId("rs-limit-maxToolSteps");
    expect(within(steps).getByText("Max tool steps")).toBeTruthy();
    expect(within(steps).getByText("12")).toBeTruthy();
    expect(within(steps).getByText("Allowed: 1 to 50.")).toBeTruthy();
    expect(within(screen.getByTestId("rs-limit-maxToolSteps-layer")).getByTestId("setting-source").getAttribute("data-source")).toBe("agent");
    expect(within(steps).getByText("How many tool calls one turn may make.")).toBeTruthy();

    // A value the agent inherits says so; a duration is shown in its unit.
    const timeout = screen.getByTestId("rs-limit-turnTimeoutSeconds");
    expect(within(timeout).getByText("5 minutes")).toBeTruthy();
    expect(within(screen.getByTestId("rs-limit-turnTimeoutSeconds-layer")).getByText("Inherited from workspace")).toBeTruthy();

    expect(client.getEffective).toHaveBeenCalledWith("agent-1");
    expect(net.getAgentRuntimeLimits).not.toHaveBeenCalled();
  });

  it("shows only the runtime limits, not the other settings of the catalog", async () => {
    renderSettings(<LimitsSection agent={AGENT} settingsClient={makeClient({ effective: EFFECTIVE })} />);
    await screen.findByTestId("rs-limit-maxToolSteps");
    expect(screen.queryByText("Judge temperature")).toBeNull();
    expect(screen.getAllByTestId(/^rs-limit-[A-Za-z]+$/)).toHaveLength(3);
  });

  it("says a pinned limit is locked and why, and offers no change link for it", async () => {
    renderSettings(<LimitsSection agent={{ ...AGENT, accessLevel: "EDITOR" }} settingsClient={makeClient({ effective: EFFECTIVE })} />);
    const chars = await screen.findByTestId("rs-limit-toolOutputMaxChars");
    expect(within(chars).getByTestId("rs-limit-toolOutputMaxChars-locked").textContent).toBe(
      "Locked by your administrator: Pinned by the platform team"
    );
    expect(screen.queryByTestId("rs-limit-toolOutputMaxChars-change")).toBeNull();
    // An unlocked one links straight to that setting, by its catalog key.
    expect(screen.getByTestId("rs-limit-maxToolSteps-change").getAttribute("href")).toContain("focus=agent.limits.maxToolSteps");
  });

  it("offers a viewer no change links, only the read-only link to the limits", async () => {
    renderSettings(<LimitsSection agent={{ ...AGENT, accessLevel: "VIEWER" }} settingsClient={makeClient({ effective: EFFECTIVE })} />);
    await screen.findByTestId("rs-limit-maxToolSteps");
    expect(screen.queryByTestId("rs-limit-maxToolSteps-change")).toBeNull();
    expect(screen.getByTestId("rs-limits-link").textContent).toBe("See limits");
  });

  it("shows a timeout of zero as no limit", async () => {
    const effective = { values: { ...EFFECTIVE.values, "agent.limits.turnTimeoutSeconds": eff(0, "default") } };
    renderSettings(<LimitsSection agent={AGENT} settingsClient={makeClient({ effective })} />);
    expect(within(await screen.findByTestId("rs-limit-turnTimeoutSeconds")).getByText("No limit")).toBeTruthy();
  });
});

describe("LimitsSection without the catalog", () => {
  it("falls back to the runtime-limits view when the catalog cannot be read", async () => {
    const client = makeClient();
    client.getCatalog.mockRejectedValue(new Error("404"));
    renderSettings(<LimitsSection agent={AGENT} settingsClient={client} />);
    const row = await screen.findByTestId("rs-limit-maxToolSteps");
    expect(within(row).getByText("9")).toBeTruthy();
    expect(screen.getByTestId("rs-limit-maxToolSteps-layer").textContent).toBe("Workspace default");
    expect(net.getAgentRuntimeLimits).toHaveBeenCalledWith("agent-1");
  });

  it("falls back when the catalog has none of the limits", async () => {
    const client = makeClient({ catalog: CATALOG.filter((e) => !e.key.startsWith("agent.limits.")) });
    renderSettings(<LimitsSection agent={AGENT} settingsClient={client} />);
    expect(await screen.findByTestId("rs-limit-maxToolSteps")).toBeTruthy();
    expect(net.getAgentRuntimeLimits).toHaveBeenCalled();
  });

  it("shows an error and recovers on retry when neither source works", async () => {
    const client = makeClient({ effective: EFFECTIVE });
    client.getCatalog.mockRejectedValueOnce(new Error("404"));
    net.getAgentRuntimeLimits.mockRejectedValueOnce(new Error("You do not have access to this agent."));
    renderSettings(<LimitsSection agent={AGENT} settingsClient={client} />);
    expect(await screen.findByText("You do not have access to this agent.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    await waitFor(() => expect(screen.getByTestId("rs-limit-maxToolSteps")).toBeTruthy());
  });
});
