import { describe, expect, it } from "vitest";
import { blueprintFromGoal } from "../lib/build/blueprint";
import { parseBlueprintJson, serializeBlueprint } from "./blueprintJson";

const bp = () => blueprintFromGoal("Answer customer questions about pricing and pass refunds to me");

describe("blueprint JSON round trip", () => {
  it("serialize then parse returns an equal blueprint", () => {
    const original = bp();
    const r = parseBlueprintJson(serializeBlueprint(original));
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.blueprint).toEqual(original);
  });

  it("round-trips a blueprint with a workflow and edited fields", () => {
    const o = bp();
    const edited = { ...o, agent: { ...o.agent, name: "Edited", temperature: 0.3, seed: 7 }, sources: { ...o.sources, "agent.name": "you" as const } };
    const r = parseBlueprintJson(serializeBlueprint(edited));
    expect(r.ok && r.blueprint.agent.name).toBe("Edited");
    expect(r.ok && r.blueprint.agent.seed).toBe(7);
  });

  it("fills omitted optional sections from a safe default", () => {
    const r = parseBlueprintJson(JSON.stringify({ goal: "x", agent: { name: "Mini" } }));
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.blueprint.agent.name).toBe("Mini");
      expect(r.blueprint.agent.maxTokens).toBeGreaterThan(0);
      expect(r.blueprint.wiring.channels).toBeDefined();
      expect(r.blueprint.output).toBe("agent");
    }
  });
});

describe("blueprint JSON rejection", () => {
  const bad = (t: string) => {
    const r = parseBlueprintJson(t);
    expect(r.ok).toBe(false);
    return r.ok ? "" : r.error;
  };

  it("rejects empty, malformed and non-object input with a message", () => {
    expect(bad("")).toMatch(/Paste/);
    expect(bad("{nope")).toMatch(/not valid JSON/);
    expect(bad("[1,2]")).toMatch(/object/);
    expect(bad("42")).toMatch(/object/);
  });

  it("rejects objects that are not blueprints", () => {
    expect(bad('{"hello":"world"}')).toMatch(/does not look like a blueprint/);
  });

  it("rejects wrong types and unknown enum values", () => {
    expect(bad(JSON.stringify({ agent: { name: "A", temperature: "hot" } }))).toMatch(/agent\.temperature/);
    expect(bad(JSON.stringify({ agent: { name: "A", autonomyTier: "YOLO" } }))).toMatch(/autonomyTier/);
    expect(bad(JSON.stringify({ output: "robot", agent: { name: "A" } }))).toMatch(/output/);
    expect(bad(JSON.stringify({ agent: { name: "A" }, wiring: { channels: ["CARRIER_PIGEON"] } }))).toMatch(/channels/);
  });

  it("rejects a malformed workflow", () => {
    expect(bad(JSON.stringify({ agent: { name: "A" }, workflow: { name: "w", steps: [{ id: "1", kind: "teleport", label: "x" }] } }))).toMatch(/step 1/);
  });

  it("rejects oversized input", () => {
    expect(bad("x".repeat(300_000))).toMatch(/too large/);
  });
});
