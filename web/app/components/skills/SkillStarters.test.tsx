import { describe, expect, it } from "vitest";
import type { SkillSummary } from "../../lib/api";
import { SKILL_STARTERS, playgroundHref, startersFor, starterPrompt } from "./SkillStarters";

const skill = (name: string, enabled = true) => ({ name, enabled }) as unknown as SkillSummary;

describe("SkillStarters", () => {
  it("only offers starters whose skill is installed and enabled", () => {
    const shown = startersFor([skill("decision-lab"), skill("quiz-maker", false), skill("unrelated")]);
    expect(shown.map((s) => s.skill)).toEqual(["decision-lab"]);
  });

  it("has one starter per skill and a prompt that fits the playground cap", () => {
    const names = SKILL_STARTERS.map((s) => s.skill);
    expect(new Set(names).size).toBe(names.length);
    for (const s of SKILL_STARTERS) expect(starterPrompt(s).length).toBeLessThanOrEqual(600);
  });

  it("encodes the prompt into a playground link", () => {
    expect(playgroundHref("a b&c")).toBe("/playground?prompt=a%20b%26c");
  });

  it("names the skill in the prompt so the agent loads it", () => {
    for (const s of SKILL_STARTERS) expect(starterPrompt(s)).toContain(`Use the ${s.skill} skill.`);
  });
});
