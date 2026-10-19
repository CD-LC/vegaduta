// Prompt Knockout's splitter: the partition must be lossless whatever the
// prompt looks like (that is what makes "the prompt without section N"
// meaningful), tiny fragments must not become sections of their own, and a
// long prompt must come down to the cap by merging its smallest neighbours.

import { describe, expect, it } from "vitest";
import { MAX_PROMPT_SECTIONS, promptWithout, splitPromptSections, type PromptSection } from "./promptSections";
import { FIXTURE_AGENT } from "./labFixtures";

const joined = (sections: PromptSection[]) => sections.map((s) => s.text).join("");

function expectLossless(prompt: string, maxSections?: number) {
  const { sections } = splitPromptSections(prompt, maxSections);
  expect(joined(sections)).toBe(prompt);
  sections.forEach((section, i) => {
    expect(section.id).toBe(`s${i + 1}`);
    expect(section.index).toBe(i);
    expect(prompt.slice(section.start, section.end)).toBe(section.text);
    expect(section.start).toBe(i === 0 ? 0 : sections[i - 1].end);
  });
  if (sections.length > 0) expect(sections[sections.length - 1].end).toBe(prompt.length);
  return sections;
}

const LONG_A = "Always greet the customer by name and thank them for writing in.";
const LONG_B = "Never promise a delivery date that the order system has not confirmed.";
const LONG_C = "Refunds above the limit go to the billing team, with the order number.";

describe("splitPromptSections", () => {
  it("splits at markdown headings and titles each section by its heading", () => {
    const prompt = FIXTURE_AGENT.systemPrompt as string;
    const sections = expectLossless(prompt);
    expect(sections.map((s) => s.title)).toEqual(["Role", "Tone", "Escalation"]);
    expect(sections[1].text.startsWith("# Tone\n")).toBe(true);
    expect(splitPromptSections(prompt).merged).toBe(false);
  });

  it("splits at label lines", () => {
    const prompt = `Role:\n${LONG_A}\nTone:\n${LONG_B}\nEscalation rules:\n${LONG_C}\n`;
    const sections = expectLossless(prompt);
    expect(sections.map((s) => s.title)).toEqual(["Role", "Tone", "Escalation rules"]);
  });

  it("splits plain paragraphs at blank lines and titles them by their first six words", () => {
    const prompt = `${LONG_A}\n\n${LONG_B}\n\n\n${LONG_C}`;
    const sections = expectLossless(prompt);
    expect(sections).toHaveLength(3);
    expect(sections[0].title).toBe("Always greet the customer by name…");
    // The blank lines stay with the paragraph before them.
    expect(sections[0].text).toBe(`${LONG_A}\n\n`);
    expect(sections[1].text).toBe(`${LONG_B}\n\n\n`);
    expect(sections[2].text).toBe(LONG_C);
  });

  it("does not treat a lower-case or over-long line ending in a colon as a label", () => {
    const tooLong = `${"A".repeat(70)}:`;
    const prompt = `${LONG_A}\nfor example:\n${LONG_B}\n${tooLong}\n${LONG_C}`;
    expect(expectLossless(prompt)).toHaveLength(1);
  });

  it("stays lossless on CRLF prompts, leading blank lines and odd spacing", () => {
    expectLossless(`\r\n\r\n# Role\r\n${LONG_A}\r\n\r\n# Tone\r\n${LONG_B}\r\n`);
    expectLossless(`\n\n   \n${LONG_A}\n \n\t\n${LONG_B}\n\n`);
    expectLossless("#\n##\n###\n\n\n");
    expectLossless("   ");
    expectLossless("one line, no newline");
    const crlf = splitPromptSections(`# Role\r\n${LONG_A}\r\n\r\n# Tone\r\n${LONG_B}\r\n`).sections;
    expect(crlf.map((s) => s.title)).toEqual(["Role", "Tone"]);
  });

  it("returns no sections for an empty prompt", () => {
    expect(splitPromptSections("")).toEqual({ sections: [], merged: false });
  });

  it("folds a fragment under 40 characters into the section after it", () => {
    const prompt = `Be kind.\n\n${LONG_A}\n\n${LONG_B}`;
    const sections = expectLossless(prompt);
    expect(sections).toHaveLength(2);
    expect(sections[0].text).toBe(`Be kind.\n\n${LONG_A}\n\n`);
  });

  it("keeps a heading with the text it introduces, across a blank line and whatever its length", () => {
    const heading = "# How to handle refund requests from long-standing customers";
    const prompt = `# Role\n\n${LONG_A}\n\n${heading}\n\n${LONG_B}\n`;
    const sections = expectLossless(prompt);
    expect(sections).toHaveLength(2);
    expect(sections[0].title).toBe("Role");
    expect(sections[1].text).toBe(`${heading}\n\n${LONG_B}\n`);
    expect(sections[1].title).toBe("How to handle refund requests from long-standing customers");
  });

  it("folds a tiny last fragment into the section before it", () => {
    const prompt = `${LONG_A}\n\n${LONG_B}\n\nThanks.`;
    const sections = expectLossless(prompt);
    expect(sections).toHaveLength(2);
    expect(sections[1].text).toBe(`${LONG_B}\n\nThanks.`);
  });

  it("keeps a prompt made only of tiny fragments as one section", () => {
    const sections = expectLossless("Be kind.\n\nBe brief.\n\nBe honest.");
    expect(sections).toHaveLength(1);
  });

  it("merges the smallest neighbours down to the cap and says so", () => {
    const paragraphs = Array.from({ length: 20 }, (_, i) => `Rule ${i + 1}: ${"x".repeat(40 + i * 10)}`);
    const prompt = paragraphs.join("\n\n");
    const result = splitPromptSections(prompt);
    expect(result.sections).toHaveLength(MAX_PROMPT_SECTIONS);
    expect(result.merged).toBe(true);
    expectLossless(prompt);
    // The shortest paragraphs are at the front, so that is where merging happened.
    expect(result.sections[0].text).toContain("Rule 1:");
    expect(result.sections[0].text).toContain("Rule 2:");
    expect(result.sections[MAX_PROMPT_SECTIONS - 1].text).toBe(paragraphs[19]);
  });

  it("honours a smaller cap, down to a single section", () => {
    const prompt = [LONG_A, LONG_B, LONG_C, LONG_A, LONG_B].join("\n\n");
    expect(expectLossless(prompt, 3)).toHaveLength(3);
    const one = splitPromptSections(prompt, 1);
    expect(one.sections).toHaveLength(1);
    expect(one.sections[0].text).toBe(prompt);
    expect(one.merged).toBe(true);
    expect(splitPromptSections(prompt, 5).merged).toBe(false);
  });
});

describe("promptWithout", () => {
  const prompt = `\n# Role\n${LONG_A}\n\n# Tone\n${LONG_B}\n\n# Escalation\n${LONG_C}\n\n`;
  const { sections } = splitPromptSections(prompt);

  it("with nothing removed is the whole prompt, trimmed", () => {
    expect(sections).toHaveLength(3);
    expect(promptWithout(sections, [])).toBe(prompt.trim());
  });

  it("leaves out exactly the removed section", () => {
    expect(promptWithout(sections, ["s2"])).toBe(`# Role\n${LONG_A}\n\n# Escalation\n${LONG_C}`);
    expect(promptWithout(sections, ["s1"])).toBe(`# Tone\n${LONG_B}\n\n# Escalation\n${LONG_C}`);
    expect(promptWithout(sections, ["s3"])).toBe(`# Role\n${LONG_A}\n\n# Tone\n${LONG_B}`);
    expect(promptWithout(sections, ["s1", "s3"])).toBe(`# Tone\n${LONG_B}`);
  });

  it("is empty when every section is removed, and ignores unknown ids", () => {
    expect(promptWithout(sections, ["s1", "s2", "s3"])).toBe("");
    expect(promptWithout(sections, ["s9"])).toBe(prompt.trim());
  });

  it("collapses three or more newlines to one blank line, for baseline and variants alike", () => {
    const spaced = `${LONG_A}\n\n\n\n${LONG_B}\n\n\n\n${LONG_C}`;
    const parts = splitPromptSections(spaced).sections;
    expect(parts).toHaveLength(3);
    expect(promptWithout(parts, [])).toBe(`${LONG_A}\n\n${LONG_B}\n\n${LONG_C}`);
    expect(promptWithout(parts, ["s2"])).toBe(`${LONG_A}\n\n${LONG_C}`);
    const crlf = splitPromptSections(`${LONG_A}\r\n\r\n\r\n${LONG_B}`).sections;
    expect(promptWithout(crlf, [])).toBe(`${LONG_A}\r\n\r\n${LONG_B}`);
  });
});
