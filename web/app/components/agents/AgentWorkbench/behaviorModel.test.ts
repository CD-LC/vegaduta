import { describe, expect, it } from "vitest";
import type { EffectiveSetting, SettingCatalogEntry } from "../../../lib/api/settings";
import {
  BEHAVIOR_KEYS,
  agentScopedEntries,
  impactLines,
  overriddenKeys,
  roleForAgentAccess,
  tabFromSection,
} from "./behaviorModel";

function entry(key: string, type: SettingCatalogEntry["type"], extra: Partial<SettingCatalogEntry> = {}): SettingCatalogEntry {
  return {
    key,
    group: key.split(".")[0],
    labelKey: `settings.${key}.label`,
    descriptionKey: `settings.${key}.description`,
    type,
    risk: "low",
    scopes: ["tenant", "agent"],
    default: 0,
    minRole: "EDITOR",
    restartRequired: false,
    ...extra,
  };
}

const ENTRIES: SettingCatalogEntry[] = [
  entry(BEHAVIOR_KEYS.maxSteps, "int", { unit: "count" }),
  entry(BEHAVIOR_KEYS.turnTimeout, "duration", { unit: "s" }),
  entry(BEHAVIOR_KEYS.toolTimeout, "duration", { unit: "s" }),
  entry(BEHAVIOR_KEYS.outputCap, "int", { unit: "chars" }),
  entry(BEHAVIOR_KEYS.continuation, "int", { unit: "count" }),
  entry(BEHAVIOR_KEYS.retrievalTopK, "int", { unit: "count" }),
  entry(BEHAVIOR_KEYS.retrievalMinScore, "decimal"),
  entry(BEHAVIOR_KEYS.approvalContinuation, "bool"),
  entry(BEHAVIOR_KEYS.guardFailMode, "enum", { enumValues: ["open", "closed"] }),
  entry(BEHAVIOR_KEYS.maxBranches, "int", { unit: "count" }),
  entry("approvals.expiry.ttl", "duration", { unit: "s", scopes: ["tenant"] }),
];
const BY_KEY = Object.fromEntries(ENTRIES.map((e) => [e.key, e]));
const fmt = (e: SettingCatalogEntry, v: unknown) => `<${e.key}=${String(v)}>`;

function values(over: Record<string, EffectiveSetting["value"]>, source: EffectiveSetting["source"] = "tenant") {
  const out: Record<string, EffectiveSetting> = {};
  for (const [k, v] of Object.entries(over)) out[k] = { value: v, source, locked: false };
  return out;
}

describe("impactLines", () => {
  it("states what each limit means in terms of its current value", () => {
    const lines = impactLines({
      values: values({
        [BEHAVIOR_KEYS.maxSteps]: 8,
        [BEHAVIOR_KEYS.turnTimeout]: 120,
        [BEHAVIOR_KEYS.toolTimeout]: 30,
        [BEHAVIOR_KEYS.outputCap]: 20000,
        [BEHAVIOR_KEYS.continuation]: 2,
        [BEHAVIOR_KEYS.retrievalTopK]: 5,
        [BEHAVIOR_KEYS.retrievalMinScore]: 0.3,
        [BEHAVIOR_KEYS.approvalContinuation]: true,
        [BEHAVIOR_KEYS.guardFailMode]: "closed",
        [BEHAVIOR_KEYS.maxBranches]: 4,
      }),
      entries: BY_KEY,
      format: fmt,
    });
    const byId = Object.fromEntries(lines.map((l) => [l.id, l]));
    expect(byId.steps).toMatchObject({ messageKey: "impact.steps", params: { count: 8 } });
    expect(byId.turnTimeout.params.duration).toBe(`<${BEHAVIOR_KEYS.turnTimeout}=120>`);
    expect(byId.outputCap.params.chars).toBe(`<${BEHAVIOR_KEYS.outputCap}=20000>`);
    expect(byId.continuation).toMatchObject({ messageKey: "impact.continuation", params: { count: 2 } });
    expect(byId.retrieval.messageKey).toBe("impact.retrievalScored");
    expect(byId.approvalContinuation.messageKey).toBe("impact.approvalContinuationOn");
    expect(byId.guardFailMode.messageKey).toBe("impact.guardClosed");
    expect(byId.branches.params.count).toBe(4);
  });

  it("uses the 'none' wording for zero values", () => {
    const lines = impactLines({
      values: values({
        [BEHAVIOR_KEYS.turnTimeout]: 0,
        [BEHAVIOR_KEYS.toolTimeout]: 0,
        [BEHAVIOR_KEYS.continuation]: 0,
        [BEHAVIOR_KEYS.retrievalTopK]: 3,
        [BEHAVIOR_KEYS.retrievalMinScore]: 0,
        [BEHAVIOR_KEYS.approvalContinuation]: false,
        [BEHAVIOR_KEYS.guardFailMode]: "open",
      }),
      entries: BY_KEY,
      format: fmt,
    });
    const keys = lines.map((l) => l.messageKey);
    expect(keys).toEqual(
      expect.arrayContaining([
        "impact.turnTimeoutNone",
        "impact.toolTimeoutNone",
        "impact.continuationNone",
        "impact.retrieval",
        "impact.approvalContinuationOff",
        "impact.guardOpen",
      ])
    );
  });

  it("leaves out a sentence when the setting is not in the catalog or has no value", () => {
    const lines = impactLines({
      values: values({ [BEHAVIOR_KEYS.maxSteps]: 8, [BEHAVIOR_KEYS.maxBranches]: 4 }),
      entries: { [BEHAVIOR_KEYS.maxSteps]: BY_KEY[BEHAVIOR_KEYS.maxSteps] },
      format: fmt,
    });
    expect(lines.map((l) => l.id)).toEqual(["steps"]);
  });

  it("explains that the autonomy tier adjusts an inherited step ceiling, but not one the agent set itself", () => {
    const inherited = impactLines({
      values: values({ [BEHAVIOR_KEYS.maxSteps]: 8 }, "tenant"),
      entries: BY_KEY,
      format: fmt,
      autonomyTier: "SUPERVISED",
    });
    expect(inherited.map((l) => l.messageKey)).toContain("impact.stepsTierSupervised");
    const full = impactLines({ values: values({ [BEHAVIOR_KEYS.maxSteps]: 8 }, "default"), entries: BY_KEY, format: fmt, autonomyTier: "FULLY_AUTONOMOUS" });
    expect(full.map((l) => l.messageKey)).toContain("impact.stepsTierFull");
    const own = impactLines({ values: values({ [BEHAVIOR_KEYS.maxSteps]: 8 }, "agent"), entries: BY_KEY, format: fmt, autonomyTier: "SUPERVISED" });
    expect(own.map((l) => l.id)).toEqual(["steps"]);
    const semi = impactLines({ values: values({ [BEHAVIOR_KEYS.maxSteps]: 8 }), entries: BY_KEY, format: fmt, autonomyTier: "SEMI_AUTONOMOUS" });
    expect(semi.map((l) => l.id)).toEqual(["steps"]);
  });
});

describe("helpers", () => {
  it("lists only agent-scoped entries and the keys the agent overrides", () => {
    expect(agentScopedEntries(ENTRIES).map((e) => e.key)).not.toContain("approvals.expiry.ttl");
    const v = values({ [BEHAVIOR_KEYS.maxSteps]: 3 }, "agent");
    v[BEHAVIOR_KEYS.outputCap] = { value: 1, source: "tenant", locked: false };
    v["approvals.expiry.ttl"] = { value: 1, source: "agent", locked: false };
    expect(overriddenKeys(ENTRIES, v)).toEqual([BEHAVIOR_KEYS.maxSteps]);
  });

  it("only proves a viewer, otherwise leaves the role to the server", () => {
    expect(roleForAgentAccess("VIEWER")).toBe("VIEWER");
    expect(roleForAgentAccess("RUNNER")).toBe("VIEWER");
    expect(roleForAgentAccess("EDITOR")).toBeUndefined();
    expect(roleForAgentAccess(null)).toBeUndefined();
  });

  it("makes the workspace-admin keys read-only for an agent editor who is not a workspace admin", () => {
    expect(roleForAgentAccess("EDITOR", false)).toBe("EDITOR");
    expect(roleForAgentAccess("OWNER", false)).toBe("EDITOR");
    expect(roleForAgentAccess("EDITOR", true)).toBe("TENANT_ADMIN");
    // a viewer stays a viewer whoever else they are
    expect(roleForAgentAccess("VIEWER", true)).toBe("VIEWER");
    expect(roleForAgentAccess("RUNNER", false)).toBe("VIEWER");
  });

  it("opens the Behavior tab only for section=behavior", () => {
    expect(tabFromSection("behavior")).toBe("behavior");
    expect(tabFromSection(null)).toBe("setup");
    expect(tabFromSection("other")).toBe("setup");
  });
});
