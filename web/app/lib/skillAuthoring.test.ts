import { describe, expect, it } from "vitest";
import {
  SKILL_TEMPLATES,
  agentsUsingSkill,
  briefDescription,
  filterSkills,
  forkName,
  forkSkillMd,
  hasLintErrors,
  lintSkillMd,
  parseSkillMd,
  promptLine,
  renderPromptBlock,
  renderSkillMd,
} from "./skillAuthoring";

const good = `---
name: quarterly-report
description: Builds the quarterly report. Use when the user asks for the quarterly numbers summary.
---

# Report

## Instructions
1. Do it.
`;

const codes = (md: string, names: string[] = [], editing: string | null = null) =>
  lintSkillMd(md, names, editing).map((i) => i.code);

describe("parseSkillMd", () => {
  it("reads quoted, CRLF and BOM input", () => {
    const p = parseSkillMd("﻿" + good.replace(/\n/g, "\r\n").replace("description: Builds", 'description: "Builds').replace("summary.\r\n---", 'summary."\r\n---'));
    expect(p.closed).toBe(true);
    expect(p.name).toBe("quarterly-report");
    expect(p.description.startsWith("Builds the quarterly report")).toBe(true);
    expect(p.body).toContain("# Report");
  });

  it("flags missing and unclosed frontmatter", () => {
    expect(parseSkillMd("# just markdown").hasFrontmatter).toBe(false);
    const open = parseSkillMd("---\nname: a\n");
    expect(open.hasFrontmatter).toBe(true);
    expect(open.closed).toBe(false);
  });

  it("folds continuation lines into the description", () => {
    const p = parseSkillMd("---\nname: a\ndescription: >\n  first part\n  second part\n---\nbody");
    expect(p.description).toBe("first part second part");
  });
});

describe("lintSkillMd", () => {
  it("accepts a good skill with no errors", () => {
    expect(hasLintErrors(lintSkillMd(good))).toBe(false);
  });

  it("every starter template is valid (no errors) - the blank one used to carry an angle-bracket placeholder core rejects", () => {
    for (const t of SKILL_TEMPLATES) {
      expect(lintSkillMd(t.skillMd).filter((i) => i.level === "error"), t.id).toEqual([]);
    }
    expect(SKILL_TEMPLATES[0].skillMd).not.toMatch(/<[^>]*>/);
  });

  it("mirrors the server's hard rules", () => {
    expect(codes("")).toEqual(["empty"]);
    expect(codes("# no fm")).toEqual(["no-frontmatter"]);
    expect(codes("---\nname: a\n")).toEqual(["unclosed-frontmatter"]);
    expect(codes(good.replace("quarterly-report", "Quarterly_Report"))).toContain("name-chars");
    expect(codes(good.replace("quarterly-report", "my-claude-helper"))).toContain("name-reserved");
    expect(codes(good.replace("quarterly-report", "a".repeat(65)))).toContain("name-long");
    expect(codes(good.replace("Builds the", "Builds <the> "))).toContain("description-tags");
    expect(codes(good.replace(/description:.*\n/, ""))).toContain("description-missing");
    expect(codes("---\nname: a-b\ndescription: Use when asked to do things for me.\n---\n")).toContain("body-missing");
  });

  it("warns on weak descriptions, placeholders and name clashes", () => {
    expect(codes(good.replace(/description:.*\n/, "description: Reports.\n"))).toEqual(
      expect.arrayContaining(["description-short", "description-trigger"])
    );
    expect(codes(SKILL_TEMPLATES[0].skillMd)).toEqual(
      expect.arrayContaining(["name-placeholder", "description-placeholder", "body-placeholder"])
    );
    expect(codes(good, ["quarterly-report"])).toContain("name-taken");
    expect(codes(good, ["quarterly-report"], "quarterly-report")).not.toContain("name-taken");
    expect(codes(good, [], "other-name")).toContain("name-immutable");
  });

  it("notes when the description will be cut in the agent's list", () => {
    const long = good.replace("Builds the quarterly report.", "one two three four five six seven eight nine ten eleven twelve thirteen. Use when asked.");
    expect(codes(long)).toContain("description-truncated");
  });
});

describe("prompt preview", () => {
  it("keeps the first 12 words on one line", () => {
    expect(briefDescription("a b c d e f g h i j k l")).toBe("a b c d e f g h i j k l");
    expect(briefDescription("a b c d e f g h i j k l m")).toBe("a b c d e f g h i j k l...");
    expect(briefDescription("a\n b\t c")).toBe("a b c");
    expect(promptLine("x", "does y")).toBe("- x: does y");
  });

  it("renders nothing for no skills and caps at 60", () => {
    expect(renderPromptBlock([])).toBe("");
    const many = Array.from({ length: 70 }, (_, i) => ({ name: `s${i}`, description: "d" }));
    const block = renderPromptBlock(many);
    expect(block).toContain("## Skills");
    expect(block.split("\n").filter((l) => l.startsWith("- ")).length).toBe(60);
  });
});

describe("fork", () => {
  it("renders canonical SKILL.md that parses back", () => {
    const md = renderSkillMd("a-b", 'He said "hi": ok', "# Body\n");
    const p = parseSkillMd(md);
    expect(p.name).toBe("a-b");
    expect(p.description).toBe('He said "hi": ok');
    expect(p.body).toBe("# Body");
  });

  it("picks a valid unused name", () => {
    expect(forkName("docx", [])).toBe("docx-custom");
    expect(forkName("docx", ["docx-custom"])).toBe("docx-custom-2");
    expect(forkName("claude-api", [])).toBe("api-custom");
    expect(forkName("Weird Name!", [])).toBe("weird-name-custom");
    const long = forkName("x".repeat(64), []);
    expect(long.length).toBeLessThanOrEqual(64);
    expect(long.endsWith("-custom")).toBe(true);
  });

  it("fork output lints clean of errors", () => {
    const { name, skillMd } = forkSkillMd(
      { name: "decision-lab", description: "Weighs options. Use when the user must choose between options.", body: "# Steps\n1. Go\n" },
      []
    );
    expect(name).toBe("decision-lab-custom");
    expect(lintSkillMd(skillMd).filter((i) => i.level === "error")).toEqual([]);
  });
});

describe("filterSkills", () => {
  const skills = [
    { name: "decision-lab", description: "Weigh options", source: "BUILT_IN" as const, enabled: true },
    { name: "quiz-maker", description: "Quiz me on topics", source: "BUILT_IN" as const, enabled: false },
    { name: "my-notes", description: "Notes style", source: "CUSTOM" as const, enabled: true },
    { name: "draft-one", description: "Agent drafted", source: "CUSTOM" as const, enabled: false, createdBy: "agent:skill-distiller" },
    { name: "staged", description: "Has rewrite", source: "CUSTOM" as const, enabled: true, pendingImprovement: true },
  ];
  const names = (r: typeof skills) => r.map((s) => s.name);

  it("searches name and description, all terms", () => {
    expect(names(filterSkills(skills, "quiz topics", "all"))).toEqual(["quiz-maker"]);
    expect(names(filterSkills(skills, "NOTES", "all"))).toEqual(["my-notes"]);
    expect(filterSkills(skills, "", "all")).toHaveLength(5);
  });

  it("filters by source, state and attention", () => {
    expect(names(filterSkills(skills, "", "custom"))).toEqual(["my-notes", "draft-one", "staged"]);
    expect(names(filterSkills(skills, "", "builtin"))).toEqual(["decision-lab", "quiz-maker"]);
    expect(names(filterSkills(skills, "", "off"))).toEqual(["quiz-maker", "draft-one"]);
    expect(names(filterSkills(skills, "", "on"))).toEqual(["decision-lab", "my-notes", "staged"]);
    expect(names(filterSkills(skills, "", "attention"))).toEqual(["draft-one", "staged"]);
    expect(names(filterSkills(skills, "", "attention", new Set(["my-notes"])))).toEqual(["my-notes", "draft-one", "staged"]);
  });
});

describe("agentsUsingSkill", () => {
  it("maps ids to names, drops unknown ids, sorts", () => {
    const agents = [
      { id: "2", name: "Zed" },
      { id: "1", name: "Amy" },
      { id: "3", name: null },
    ];
    expect(agentsUsingSkill(["2", "1", "9", "3"], agents).map((a) => a.name)).toEqual(["Amy", "Untitled agent", "Zed"]);
    expect(agentsUsingSkill(null, agents)).toEqual([]);
  });
});
