import { describe, expect, it } from "vitest";
import { CATALOG, eff } from "../../components/settings/settingsTestKit";
import type { EffectiveSetting } from "../../lib/api/settings";
import {
  OVERRIDES_FORMAT,
  buildExport,
  isSecurityGroup,
  orderGroups,
  parseOverridesText,
  planImport,
  planNeedsReason,
} from "./overridesModel";

const STEPS = "agent.limits.maxToolSteps";
const TIMEOUT = "agent.limits.turnTimeoutSeconds";
const EXPIRY = "approvals.expiry.ttl";

function values(over: Record<string, EffectiveSetting> = {}): Record<string, EffectiveSetting> {
  const v: Record<string, EffectiveSetting> = {};
  for (const e of CATALOG) v[e.key] = eff(e.default, "default");
  return { ...v, ...over };
}

const file = (overrides: Record<string, unknown>, extra: Record<string, unknown> = {}) =>
  JSON.stringify({ format: OVERRIDES_FORMAT, version: 1, overrides, ...extra });

describe("buildExport", () => {
  it("exports only workspace overrides, sorted, with numbers in the catalog unit", () => {
    const out = buildExport(
      CATALOG,
      values({
        [TIMEOUT]: eff("PT3M", "tenant"), // arrives as ISO text, written as seconds
        [STEPS]: eff(12, "tenant"),
        [EXPIRY]: eff(7200000, "platform"), // deployment value: not ours to export
        "chat.cache.eligibleWithTools": eff("true", "tenant"),
      }),
      new Date("2026-10-23T10:00:00Z")
    );
    expect(out).toMatchObject({ format: OVERRIDES_FORMAT, version: 1, scope: "tenant", exportedAt: "2026-10-23T10:00:00.000Z" });
    expect(out.overrides).toEqual({ [STEPS]: 12, [TIMEOUT]: 180, "chat.cache.eligibleWithTools": true });
    expect(Object.keys(out.overrides)).toEqual([STEPS, TIMEOUT, "chat.cache.eligibleWithTools"].sort());
  });

  it("skips settings that cannot be set for the whole workspace", () => {
    const catalog = CATALOG.map((e) => (e.key === STEPS ? { ...e, scopes: ["agent" as const] } : e));
    expect(buildExport(catalog, values({ [STEPS]: eff(12, "tenant") })).overrides).toEqual({});
  });
});

describe("parseOverridesText", () => {
  it("accepts the exported format", () => {
    expect(parseOverridesText(file({ [STEPS]: 3 }))).toEqual({ ok: true, overrides: { [STEPS]: 3 } });
  });
  it.each([
    ["", "empty"],
    ["   ", "empty"],
    ["{nope", "invalidJson"],
    ["[1,2]", "wrongFormat"],
    ["null", "wrongFormat"],
    [JSON.stringify({ overrides: {} }), "wrongFormat"],
    [JSON.stringify({ format: "other", version: 1, overrides: {} }), "wrongFormat"],
    [JSON.stringify({ format: OVERRIDES_FORMAT, version: 2, overrides: {} }), "unsupportedVersion"],
    [JSON.stringify({ format: OVERRIDES_FORMAT, version: 1 }), "noOverrides"],
    [JSON.stringify({ format: OVERRIDES_FORMAT, version: 1, overrides: [] }), "noOverrides"],
  ])("rejects %j as %s", (text, error) => {
    expect(parseOverridesText(text)).toEqual({ ok: false, error });
  });
});

describe("planImport", () => {
  const plan = (overrides: Record<string, unknown>, v = values(), resetMissing = false) =>
    planImport(CATALOG, v, overrides, { resetMissing });

  it("lists a change with the value in force now and the validated new value", () => {
    const p = plan({ [STEPS]: 12, [TIMEOUT]: 300 });
    expect(p.problems).toEqual([]);
    expect(p.changes.map((c) => [c.entry.key, c.from, c.to, c.fromSource])).toEqual([
      [STEPS, 8, 12, "default"],
      [TIMEOUT, 120, 300, "default"],
    ]);
  });

  it("treats a value that is already in force as unchanged", () => {
    const p = plan({ [STEPS]: 8 });
    expect(p.changes).toEqual([]);
    expect(p.unchanged).toEqual([STEPS]);
  });

  it("reports every entry that cannot be applied, with the catalog's own bounds", () => {
    const v = values({ [EXPIRY]: eff(3600000, "platform", { locked: true, lockedReason: "pinned" }) });
    const p = plan(
      {
        "no.such.key": 1,
        [STEPS]: 99, // above max 50
        [TIMEOUT]: 1, // below min 5
        "guardrails.failMode": "sideways", // not an option
        "chat.cache.eligibleWithTools": "yes", // string for a bool
        [EXPIRY]: 120000, // locked by the operator
        "quality.judge.temperature": "0.5", // number sent as text
        "aux.model": "x".repeat(41),
      },
      v
    );
    const byKey = Object.fromEntries(p.problems.map((x) => [x.key, x]));
    expect(byKey["no.such.key"].code).toBe("unknownKey");
    expect(byKey[STEPS]).toMatchObject({ code: "aboveMax", max: 50 });
    expect(byKey[TIMEOUT]).toMatchObject({ code: "belowMin", min: 5 });
    expect(byKey["guardrails.failMode"].code).toBe("notAllowed");
    expect(byKey["chat.cache.eligibleWithTools"].code).toBe("wrongType");
    expect(byKey[EXPIRY].code).toBe("locked");
    expect(byKey["quality.judge.temperature"].code).toBe("wrongType");
    expect(byKey["aux.model"]).toMatchObject({ code: "tooLong", max: 40 });
    expect(p.changes).toEqual([]);
  });

  it("refuses a whole-number setting given a fraction, and a setting without workspace scope", () => {
    const catalog = CATALOG.map((e) => (e.key === "aux.model" ? { ...e, scopes: ["agent" as const] } : e));
    const p = planImport(catalog, values(), { [STEPS]: 2.5, "aux.model": "other" }, { resetMissing: false });
    expect(p.problems.map((x) => [x.key, x.code])).toEqual([
      [STEPS, "notInteger"],
      ["aux.model", "notWorkspaceSetting"],
    ]);
  });

  it("only proposes removing overrides that are missing from the file when asked", () => {
    const v = values({ [STEPS]: eff(12, "tenant"), [TIMEOUT]: eff(300, "tenant"), [EXPIRY]: eff(7200000, "platform") });
    expect(plan({ [STEPS]: 12 }, v).resets).toEqual([]);
    const withReset = plan({ [STEPS]: 12 }, v, true);
    expect(withReset.resets.map((r) => r.entry.key)).toEqual([TIMEOUT]); // not the platform value, not the one in the file
    expect(withReset.unchanged).toEqual([STEPS]);
  });

  it("does not propose removing an override the operator has pinned", () => {
    const v = values({ [STEPS]: eff(12, "tenant", { locked: true }) });
    expect(plan({}, v, true).resets).toEqual([]);
  });
});

describe("planNeedsReason", () => {
  it("is false for low and medium risk, true once a high or security change is in the plan", () => {
    const low = planImport(CATALOG, values(), { "quality.judge.temperature": 0.4, [STEPS]: 12 }, { resetMissing: false });
    expect(planNeedsReason(low)).toBe(false);
    const high = planImport(CATALOG, values(), { [EXPIRY]: 120000 }, { resetMissing: false });
    expect(planNeedsReason(high)).toBe(true);
    const sec = planImport(CATALOG, values(), { "guardrails.failMode": "open" }, { resetMissing: false });
    expect(planNeedsReason(sec)).toBe(true);
  });
});

describe("group helpers", () => {
  it("orders known groups first in the documented order, then unknown ones as they came", () => {
    expect(orderGroups(["zzz", "guardrails", "agent", "aaa", "approvals"])).toEqual(["agent", "approvals", "guardrails", "zzz", "aaa"]);
  });
  it("treats a group with any security setting as a security group", () => {
    expect(isSecurityGroup(CATALOG.filter((e) => e.group === "guardrails"))).toBe(true);
    expect(isSecurityGroup(CATALOG.filter((e) => e.group === "agent"))).toBe(false);
  });
});
