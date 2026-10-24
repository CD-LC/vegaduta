import { describe, expect, it } from "vitest";
import type { AgentRuntimeLimitsView } from "../../../lib/api/agentRuntimeLimits";
import {
  agentBehaviorHref,
  droppedSamplingParams,
  limitRows,
  type AgentSamplingValues,
  normalizeSamplingParam,
  isUnrestricted,
  agentToolNames,
  agentToolsHref,
  effectiveRunTools,
  flipRunTool,
  sameNames,
  toolRows,
} from "./runSettingsModel";

const VIEW: AgentRuntimeLimitsView = {
  overrides: { maxToolSteps: 12 },
  effective: {
    maxToolSteps: 12,
    turnTimeoutSeconds: 300,
    toolTimeoutSeconds: 0,
    toolOutputMaxChars: 8000,
    retrievalTopK: 5,
    retrievalMinScore: 0.25,
    continuationPasses: 1,
    layers: { maxToolSteps: "AGENT", turnTimeoutSeconds: "TENANT", toolTimeoutSeconds: "PLATFORM" },
  },
  bounds: { maxToolSteps: { min: 1, max: 500 } },
};

describe("agentBehaviorHref", () => {
  it("links to the agent's behavior section and optionally a setting", () => {
    expect(agentBehaviorHref("a 1")).toBe("/agents?agentId=a+1&view=workbench&section=behavior");
    expect(agentBehaviorHref("a1", "agent.limits.maxToolSteps")).toContain("focus=agent.limits.maxToolSteps");
  });
});

describe("limitRows", () => {
  it("lists every limit in a fixed order with its layer, unit and bounds", () => {
    const rows = limitRows(VIEW);
    expect(rows.map((r) => r.name)).toEqual([
      "maxToolSteps",
      "turnTimeoutSeconds",
      "toolTimeoutSeconds",
      "toolOutputMaxChars",
      "retrievalTopK",
      "retrievalMinScore",
      "continuationPasses",
    ]);
    expect(rows[0]).toMatchObject({ value: 12, layer: "AGENT", overridden: true, min: 1, max: 500, unit: "count" });
    expect(rows[1]).toMatchObject({ layer: "TENANT", overridden: false, unit: "seconds" });
    // A limit with no layer reported is the platform's.
    expect(rows[3].layer).toBe("PLATFORM");
  });

  it("skips values the server did not send and survives a missing view", () => {
    expect(limitRows(null)).toEqual([]);
    const partial = { ...VIEW, effective: { ...VIEW.effective, retrievalTopK: undefined as unknown as number } };
    expect(limitRows(partial).map((r) => r.name)).not.toContain("retrievalTopK");
  });
});

describe("droppedSamplingParams", () => {
  const agent: AgentSamplingValues = { temperature: 0.2, topP: 0.9, maxTokens: null, stopSequences: ["END"], seed: null };

  it("names only parameters the agent actually set and the model ignores", () => {
    expect(droppedSamplingParams(agent, { unsupportedParams: ["temperature", "top_p", "max_tokens", "seed"] })).toEqual([
      "temperature",
      "topP",
    ]);
  });

  it("reads any spelling and any of the three catalog fields", () => {
    expect(droppedSamplingParams(agent, { ignoredParams: ["Top-P"], droppedParams: ["stop"] })).toEqual([
      "topP",
      "stopSequences",
    ]);
  });

  it("says nothing when the catalog does not say", () => {
    expect(droppedSamplingParams(agent, {})).toEqual([]);
    expect(droppedSamplingParams(agent, undefined)).toEqual([]);
    expect(droppedSamplingParams(undefined, { unsupportedParams: ["temperature"] })).toEqual([]);
    expect(normalizeSamplingParam("nonsense")).toBeNull();
  });
});

describe("tool switches", () => {
  it("marks a tool on when it is in the allow-list and ignores duplicates", () => {
    const rows = toolRows(
      [{ name: "a", description: "A" }, { name: "b" }, { name: "a" }],
      ["a"]
    );
    expect(rows).toEqual([
      { name: "a", description: "A", enabled: true },
      { name: "b", description: null, enabled: false },
    ]);
  });

  it("reads an empty allow-list as unrestricted: every tool is on", () => {
    expect(isUnrestricted([])).toBe(true);
    expect(isUnrestricted(null)).toBe(true);
    expect(isUnrestricted(["a"])).toBe(false);
    const rows = toolRows([{ name: "a" }, { name: "b" }], []);
    expect(rows.map((r) => r.enabled)).toEqual([true, true]);
    expect(toolRows([{ name: "a" }], undefined)[0].enabled).toBe(true);
  });

  it("lists the tools the saved agent has on, in row order", () => {
    const rows = toolRows([{ name: "a" }, { name: "b" }, { name: "c" }], ["c", "a"]);
    expect(agentToolNames(rows)).toEqual(["a", "c"]);
  });

  it("compares tool lists as sets", () => {
    expect(sameNames(["a", "b"], ["b", "a"])).toBe(true);
    expect(sameNames(["a"], ["a", "b"])).toBe(false);
    expect(sameNames(["a", "a"], ["a", "b"])).toBe(false);
  });

  it("uses the agent's tools when the run has no selection, and drops a selected tool the agent no longer has", () => {
    expect(effectiveRunTools(["a", "b"], null)).toEqual(["a", "b"]);
    expect(effectiveRunTools(["a", "b"], undefined)).toEqual(["a", "b"]);
    expect(effectiveRunTools(["a", "b"], ["b", "gone"])).toEqual(["b"]);
    expect(effectiveRunTools(["a", "b"], [])).toEqual([]);
  });
});

describe("flipRunTool", () => {
  const agent = ["a", "b", "c"];

  it("turning one tool off selects the others, in the agent's order, without mutating the input", () => {
    const current = ["a", "b", "c"];
    expect(flipRunTool(agent, null, "b")).toEqual(["a", "c"]);
    expect(flipRunTool(agent, current, "b")).toEqual(["a", "c"]);
    expect(current).toEqual(["a", "b", "c"]);
  });

  it("turning the tool back on returns to no selection once it equals the agent's own set", () => {
    expect(flipRunTool(agent, ["a", "c"], "b")).toBeNull();
    expect(flipRunTool(agent, ["a"], "c")).toEqual(["a", "c"]);
  });

  it("allows every tool off: an empty selection, not no selection", () => {
    expect(flipRunTool(["a"], null, "a")).toEqual([]);
    expect(flipRunTool(["a"], [], "a")).toBeNull();
  });

  it("never adds a tool the agent does not have", () => {
    expect(flipRunTool(agent, null, "not_an_agent_tool")).toBeNull();
    expect(flipRunTool(agent, ["a"], "not_an_agent_tool")).toEqual(["a"]);
  });
});

describe("agentToolsHref", () => {
  it("opens the workbench for the agent", () => {
    expect(agentToolsHref("agent-1")).toBe("/agents?agentId=agent-1&view=workbench");
  });
});
