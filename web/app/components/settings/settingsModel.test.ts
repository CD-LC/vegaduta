import { describe, expect, it } from "vitest";
import {
  bestDurationUnit,
  convertDuration,
  draftFromValue,
  editability,
  humanize,
  isDirty,
  parseDraft,
  parseIsoDurationMs,
  resolveSettingsRole,
  sameValue,
} from "./settingsModel";
import { CATALOG, eff } from "./settingsTestKit";

const byKey = (k: string) => CATALOG.find((e) => e.key === k)!;
const steps = byKey("agent.limits.maxToolSteps");
const timeout = byKey("agent.limits.turnTimeoutSeconds");
const ttl = byKey("approvals.expiry.ttl");

describe("parseDraft", () => {
  it("accepts an int inside the catalog bounds and rejects outside / non-numeric / fractional input", () => {
    expect(parseDraft(steps, { value: "12" })).toEqual({ ok: true, value: 12 });
    expect(parseDraft(steps, { value: " 7 " })).toEqual({ ok: true, value: 7 });
    expect(parseDraft(steps, { value: "0" })).toEqual({ ok: false, error: { code: "belowMin", min: 1, max: 50 } });
    expect(parseDraft(steps, { value: "51" })).toEqual({ ok: false, error: { code: "aboveMax", min: 1, max: 50 } });
    expect(parseDraft(steps, { value: "abc" })).toMatchObject({ ok: false, error: { code: "notNumber" } });
    expect(parseDraft(steps, { value: "2.5" })).toMatchObject({ ok: false, error: { code: "notInteger" } });
    expect(parseDraft(steps, { value: "" })).toMatchObject({ ok: false, error: { code: "required" } });
  });

  it("validates decimals and percents against min/max", () => {
    const temp = byKey("quality.judge.temperature");
    expect(parseDraft(temp, { value: "0.7" })).toEqual({ ok: true, value: 0.7 });
    expect(parseDraft(temp, { value: "2.1" })).toMatchObject({ ok: false, error: { code: "aboveMax", max: 2 } });
    const pct = byKey("guardrails.toxicity.threshold");
    expect(parseDraft(pct, { value: "101" })).toMatchObject({ ok: false, error: { code: "aboveMax" } });
    expect(parseDraft(pct, { value: "55" })).toEqual({ ok: true, value: 55 });
  });

  it("converts a duration typed in another unit into the catalog unit and bounds-checks the result", () => {
    expect(parseDraft(timeout, { value: "2", unit: "min" })).toEqual({ ok: true, value: 120 });
    expect(parseDraft(timeout, { value: "11", unit: "min" })).toMatchObject({ ok: false, error: { code: "aboveMax", max: 600 } });
    expect(parseDraft(ttl, { value: "2", unit: "h" })).toEqual({ ok: true, value: 7_200_000 });
    expect(parseDraft(ttl, { value: "30", unit: "s" })).toMatchObject({ ok: false, error: { code: "belowMin", min: 60000 } });
    // 0.5 ms cannot be stored in a whole-millisecond setting
    expect(parseDraft(ttl, { value: "0.0005", unit: "s" })).toMatchObject({ ok: false });
    expect(parseDraft(timeout, { value: "1.5", unit: "s" })).toMatchObject({ ok: false, error: { code: "precision" } });
  });

  it("validates enum membership, bool, and string length bounds from the catalog", () => {
    const mode = byKey("guardrails.failMode");
    expect(parseDraft(mode, { value: "open" })).toEqual({ ok: true, value: "open" });
    expect(parseDraft(mode, { value: "maybe" })).toMatchObject({ ok: false, error: { code: "notAllowed" } });
    expect(parseDraft(byKey("chat.cache.eligibleWithTools"), { value: true })).toEqual({ ok: true, value: true });
    const model = byKey("aux.model");
    expect(parseDraft(model, { value: "   " })).toMatchObject({ ok: false, error: { code: "required" } });
    expect(parseDraft(model, { value: "x".repeat(41) })).toMatchObject({ ok: false, error: { code: "tooLong", max: 40 } });
    expect(parseDraft(byKey("aux.note"), { value: "" })).toEqual({ ok: true, value: "" });
  });
});

describe("durations", () => {
  it("converts without float noise and picks the most readable unit", () => {
    expect(convertDuration(1.1, "min", "ms")).toBe(66000);
    expect(convertDuration(90, "s", "min")).toBe(1.5);
    expect(bestDurationUnit(ttl, 3_600_000)).toBe("h");
    expect(bestDurationUnit(ttl, 90_000)).toBe("s");
    expect(bestDurationUnit(timeout, 120)).toBe("min");
    expect(bestDurationUnit(timeout, 45)).toBe("s");
  });
  it("reads ISO-8601 durations", () => {
    expect(parseIsoDurationMs("PT15M")).toBe(900_000);
    expect(parseIsoDurationMs("PT1H30M")).toBe(5_400_000);
    expect(parseIsoDurationMs("nope")).toBeNull();
    expect(draftFromValue(ttl, "PT1H", "min")).toEqual({ value: "60", unit: "min" });
  });
});

describe("dirty tracking", () => {
  it("is clean when the draft equals the saved value, dirty when it differs or is invalid", () => {
    expect(isDirty(steps, undefined, 8)).toBe(false);
    expect(isDirty(steps, { value: "8" }, 8)).toBe(false);
    expect(isDirty(steps, { value: "9" }, 8)).toBe(true);
    expect(isDirty(steps, { value: "banana" }, 8)).toBe(true);
    expect(isDirty(timeout, { value: "2", unit: "min" }, 120)).toBe(false);
    expect(sameValue(byKey("chat.cache.eligibleWithTools"), true, "true")).toBe(true);
  });
});

describe("editability", () => {
  it("explains why a viewer cannot edit", () => {
    const o = { scope: "tenant" as const };
    expect(editability(steps, eff(8), { ...o, role: "TENANT_ADMIN" })).toBe("none");
    expect(editability(steps, eff(8), { ...o, role: "EDITOR" })).toBe("role"); // tenant writes need an admin
    expect(editability(steps, eff(8), { scope: "agent", role: "EDITOR" })).toBe("none");
    expect(editability(steps, eff(8), { scope: "agent", role: "VIEWER" })).toBe("role");
    expect(editability(byKey("guardrails.failMode"), eff("closed"), { scope: "agent", role: "TENANT_ADMIN" })).toBe("scope");
    expect(editability(steps, eff(8, "platform", { locked: true }), { ...o, role: "TENANT_ADMIN" })).toBe("locked");
    expect(editability(steps, eff(8), { ...o, role: "TENANT_ADMIN", readOnly: true })).toBe("readOnly");
    // no role given: the server decides
    expect(editability(steps, eff(8), o)).toBe("none");
  });
});

describe("humanize", () => {
  it("makes a readable fallback label from a key", () => {
    expect(humanize("agent.limits.maxToolSteps")).toBe("Max tool steps");
    expect(humanize("tools_output")).toBe("Tools output");
  });
});

describe("resolveSettingsRole", () => {
  it("keeps a role the page passed", () => {
    expect(resolveSettingsRole("VIEWER", "agent", { isAdmin: true })).toBe("VIEWER");
    expect(resolveSettingsRole("TENANT_ADMIN", "tenant", null)).toBe("TENANT_ADMIN");
  });
  it("at agent scope reads the session: workspace or platform admin, else editor", () => {
    expect(resolveSettingsRole(undefined, "agent", { isAdmin: true })).toBe("TENANT_ADMIN");
    expect(resolveSettingsRole(undefined, "agent", { isAdmin: false, isPlatformAdmin: true })).toBe("TENANT_ADMIN");
    expect(resolveSettingsRole(undefined, "agent", { isAdmin: false, isPlatformAdmin: false })).toBe("EDITOR");
    // the security key is then read-only for the editor
    expect(editability(byKey("guardrails.toxicity.threshold"), eff(80), { scope: "agent", role: "EDITOR" })).toBe("role");
  });
  it("assumes nothing without a session or at tenant scope", () => {
    expect(resolveSettingsRole(undefined, "agent", null)).toBeUndefined();
    expect(resolveSettingsRole(undefined, "tenant", { isAdmin: false })).toBeUndefined();
  });
});
