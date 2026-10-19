import { existsSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { AGENT_TEMPLATES, templateNameKey, templateTaglineKey } from "../components/agentTemplates";
import { isOpenConsoleTemplate } from "../components/agents/starterTemplates";
import en from "../../messages/en.json";
import { GOALS, GOAL_IDS, findGoal, goalTemplateKeys, isGoalId } from "./goals";

const templates = en as unknown as Record<string, Record<string, string>>;

describe("goal catalogue", () => {
  it("has the six goals the brief names, once each", () => {
    expect([...GOAL_IDS]).toEqual(["learn", "research", "analyze", "automate", "draft", "build"]);
    expect(GOALS.map((g) => g.id)).toEqual([...GOAL_IDS]);
  });

  it("agent goals point at a template and route goals at a page, never both or neither", () => {
    for (const goal of GOALS) {
      if (goal.kind === "agent") {
        expect(goal.templateKey, goal.id).toBeTruthy();
        expect(goal.href, goal.id).toBeUndefined();
      } else {
        expect(goal.href, goal.id).toMatch(/^\//);
        expect(goal.templateKey, goal.id).toBeUndefined();
      }
    }
  });

  it("reuses real gallery templates (one-click create goes through the existing helpers)", () => {
    const keys = new Set((AGENT_TEMPLATES as unknown as Array<{ key: string }>).map((t) => t.key));
    for (const key of goalTemplateKeys()) expect(keys.has(key), key).toBe(true);
  });

  it("has a display name and tagline for every template it can create", () => {
    for (const key of goalTemplateKeys()) {
      expect(templates.agentTemplates[templateNameKey(key)], `${key} name`).toBeTruthy();
      expect(templates.agentTemplates[templateTaglineKey(key)], `${key} tagline`).toBeTruthy();
    }
  });

  it("opens only pages that exist", () => {
    const appDir = path.resolve(__dirname, "..");
    for (const goal of GOALS) {
      if (!goal.href) continue;
      const page = path.join(appDir, ...goal.href.split("/").filter(Boolean), "page.tsx");
      expect(existsSync(page), `${goal.href} -> ${page}`).toBe(true);
    }
  });

  it("keeps the no-setup goals on templates that stay open on every plan", () => {
    // draft and the tutor fallback must work for a FREE workspace; analyze is open too.
    for (const key of ["client-call-to-actions", "explain-it-simply-tutor", "data-analyst"]) {
      expect(isOpenConsoleTemplate(key), key).toBe(true);
    }
  });

  it("finds goals by id and rejects everything else", () => {
    expect(findGoal("research")?.templateKey).toBe("research-assistant");
    expect(findGoal("nope")).toBeNull();
    expect(findGoal(null)).toBeNull();
    expect(isGoalId("build")).toBe(true);
    expect(isGoalId("BUILD")).toBe(false);
  });
});
