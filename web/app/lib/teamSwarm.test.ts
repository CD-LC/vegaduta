import { describe, expect, it, vi } from "vitest";
import {
  SWARM_LIMITS,
  TEAM_PRESETS,
  applyPreset,
  memberName,
  planPreset,
  swarmLimitsHint,
} from "./teamSwarm";

const leader = { id: "L", name: "Lead" };
const research = TEAM_PRESETS.find((p) => p.id === "research")!;

describe("planPreset", () => {
  it("plans to create every member when none exist, keeping existing collaborators", () => {
    const plan = planPreset(research, leader, [leader, { id: "X", name: "Other" }], ["X"]);
    expect(plan.toCreate.map((m) => m.name)).toEqual([
      "Lead · Researcher",
      "Lead · Critic",
      "Lead · Writer",
    ]);
    expect(plan.toReuse).toEqual([]);
    expect(plan.collaboratorIds).toEqual(["X"]);
  });

  it("reuses an agent that already has the member name (case-insensitive) instead of duplicating", () => {
    const agents = [
      leader,
      { id: "C1", name: memberName("Lead", "Critic").toUpperCase() },
    ];
    const plan = planPreset(research, leader, agents, []);
    expect(plan.toReuse.map((a) => a.id)).toEqual(["C1"]);
    expect(plan.toCreate.map((m) => m.name)).toEqual(["Lead · Researcher", "Lead · Writer"]);
    expect(plan.collaboratorIds).toEqual(["C1"]);
  });

  it("does not duplicate a reused agent that is already a collaborator, and never adds the leader", () => {
    const agents = [leader, { id: "C1", name: "Lead · Critic" }];
    const plan = planPreset(research, leader, agents, ["C1", "L", "C1"]);
    expect(plan.collaboratorIds).toEqual(["C1"]);
  });
});

describe("applyPreset", () => {
  const plan = () =>
    planPreset(research, leader, [leader, { id: "X", name: "Other" }], ["X"]);

  it("creates missing members then links existing + new ids in one call", async () => {
    let n = 0;
    const createAgent = vi.fn(async (p: Record<string, unknown>) => ({ id: `N${++n}`, name: p.name as string }));
    const setCollaborators = vi.fn(async () => ({}));
    const r = await applyPreset(plan(), "L", { providerName: "ANTHROPIC" }, { createAgent, setCollaborators });
    expect(r.ok).toBe(true);
    expect(createAgent).toHaveBeenCalledTimes(3);
    expect(createAgent.mock.calls[0][0]).toMatchObject({ providerName: "ANTHROPIC", name: "Lead · Researcher" });
    expect(setCollaborators).toHaveBeenCalledTimes(1);
    expect(setCollaborators).toHaveBeenCalledWith("L", ["X", "N1", "N2", "N3"]);
    expect(r.collaboratorIds).toEqual(["X", "N1", "N2", "N3"]);
  });

  it("skips creation entirely when everything exists", async () => {
    const full = planPreset(
      research,
      leader,
      [
        leader,
        { id: "A", name: "Lead · Researcher" },
        { id: "B", name: "Lead · Critic" },
        { id: "C", name: "Lead · Writer" },
      ],
      [],
    );
    const createAgent = vi.fn();
    const setCollaborators = vi.fn(async () => ({}));
    const r = await applyPreset(full, "L", {}, { createAgent, setCollaborators });
    expect(createAgent).not.toHaveBeenCalled();
    expect(setCollaborators).toHaveBeenCalledWith("L", ["A", "B", "C"]);
    expect(r.ok).toBe(true);
  });

  it("on a create failure stops, reports created agents, and leaves collaborators untouched", async () => {
    const createAgent = vi
      .fn()
      .mockResolvedValueOnce({ id: "N1", name: "Lead · Researcher" })
      .mockRejectedValueOnce(new Error("Agent limit reached"));
    const setCollaborators = vi.fn(async () => ({}));
    const r = await applyPreset(plan(), "L", {}, { createAgent, setCollaborators });
    expect(r.ok).toBe(false);
    expect(createAgent).toHaveBeenCalledTimes(2);
    expect(setCollaborators).not.toHaveBeenCalled();
    expect(r.created.map((c) => c.id)).toEqual(["N1"]);
    expect(r.failed).toEqual([{ name: "Lead · Critic", error: "Agent limit reached" }]);
  });

  it("reports a link failure but still lists the agents that now exist", async () => {
    let n = 0;
    const createAgent = vi.fn(async (p: Record<string, unknown>) => ({ id: `N${++n}`, name: p.name as string }));
    const setCollaborators = vi.fn().mockRejectedValue(new Error("403 locked"));
    const r = await applyPreset(plan(), "L", {}, { createAgent, setCollaborators });
    expect(r.ok).toBe(false);
    expect(r.linkError).toBe("403 locked");
    expect(r.created).toHaveLength(3);
  });

  it("retry after a partial failure reuses the already-created agent (no duplicates)", () => {
    const retry = planPreset(
      research,
      leader,
      [leader, { id: "X", name: "Other" }, { id: "N1", name: "Lead · Researcher" }],
      ["X"],
    );
    expect(retry.toReuse.map((a) => a.id)).toEqual(["N1"]);
    expect(retry.toCreate).toHaveLength(2);
    expect(retry.collaboratorIds).toEqual(["X", "N1"]);
  });
});

describe("swarmLimitsHint", () => {
  it("is not a team below 2 members", () => {
    expect(swarmLimitsHint(0).active).toBe(false);
    expect(swarmLimitsHint(1).active).toBe(false);
    expect(swarmLimitsHint(2).active).toBe(true);
  });
  it("caps parallel branches and estimates tokens from the per-branch figure", () => {
    const h = swarmLimitsHint(10);
    expect(h.parallelBranches).toBe(SWARM_LIMITS.maxParallelBranches);
    expect(h.overCap).toBe(2);
    expect(h.estTokensPerCall).toBe(8 * 8000);
    expect(swarmLimitsHint(3).estTokensPerCall).toBe(24000);
  });
});
