import { describe, expect, it } from "vitest";
import en from "../../messages/en.json";
import { allStartFlowKeys, goalBodyKey, goalTitleKey, promptKey } from "./copy";
import { GOALS } from "./goals";

const ns = (en as unknown as Record<string, Record<string, string>>).startFlow;

describe("startFlow copy", () => {
  it("defines every key the page can ask for (a missing key renders its dotted path)", () => {
    const missing = allStartFlowKeys().filter((key) => typeof ns[key] !== "string" || ns[key].trim() === "");
    expect(missing).toEqual([]);
  });

  it("has no dead keys: everything in the namespace is reachable from the catalogue", () => {
    const used = new Set(allStartFlowKeys());
    const dead = Object.keys(ns).filter((key) => !used.has(key));
    expect(dead).toEqual([]);
  });

  it("states no price: no currency literal in any string", () => {
    for (const [key, value] of Object.entries(ns)) {
      expect(value, key).not.toMatch(/[$₹£€]\s?\d/);
      expect(value, key).not.toMatch(/\bRs\.?\s?\d/i);
    }
  });

  it("uses only placeholders the page supplies", () => {
    const allowed: Record<string, string[]> = {
      stepAnnounce: ["n", "label"],
      createHeading: ["name"],
      createBody: ["name"],
      agentReady: ["name"],
      celebrationBody: ["time"],
      elapsedMinSec: ["min", "sec"],
      elapsedSec: ["sec"],
    };
    for (const [key, value] of Object.entries(ns)) {
      const found = [...value.matchAll(/\{(\w+)\}/g)].map((m) => m[1]);
      expect(found.sort(), key).toEqual([...(allowed[key] ?? [])].sort());
    }
  });

  it("gives every goal plain-language title and body, and a sample prompt where the goal ends in a chat", () => {
    for (const goal of GOALS) {
      expect(ns[goalTitleKey(goal.id)], goal.id).toBeTruthy();
      expect(ns[goalBodyKey(goal.id)], goal.id).toBeTruthy();
      if (goal.kind === "agent" || goal.fallbackTemplateKey) {
        expect(ns[promptKey(goal.id)].length, goal.id).toBeGreaterThan(20);
      }
    }
  });

  it("never claims a capability is on: the copy that mentions one says 'not set up' or 'could not check'", () => {
    expect(ns.missingWebSearch).toMatch(/not set up/i);
    expect(ns.missingCodeExecution).toMatch(/not set up/i);
    expect(ns.uncheckedWebSearch).toMatch(/could not check/i);
    expect(ns.uncheckedCodeExecution).toMatch(/could not check/i);
  });

  it("promises nothing is sent before the person presses Send", () => {
    expect(ns.subtitle).toMatch(/until you press Send/i);
    expect(ns.tryIntro).toMatch(/has not been sent/i);
  });
});
