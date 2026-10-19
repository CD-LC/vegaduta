// Pure helpers for the /skills authoring aids: SKILL.md lint, the "how it appears in an agent's
// prompt" preview, fork-a-built-in, starter templates and list search/filter. No React, no fetch.
//
// The lint mirrors core SkillMarkdownParser (name <= 64 chars, [a-z0-9-], reserved words,
// description <= 1024 chars with no tags, frontmatter required, non-empty body) and the prompt
// preview mirrors core SkillPromptBlock (first 12 words, 60-entry cap). The server stays the
// authority: these only tell the author early, they never replace its validation.

export const MAX_NAME_LENGTH = 64;
export const MAX_DESCRIPTION_LENGTH = 1024;
export const MAX_BODY_CHARS = 100_000;
export const MAX_PROMPT_WORDS = 12;
export const MAX_SKILLS_IN_PROMPT = 60;
export const RESERVED_WORDS = ["anthropic", "claude", "vegaduta"];

const NAME_PATTERN = /^[a-z0-9-]+$/;
const XML_TAG = /<[^>]*>/;

export type ParsedSkillMd = {
  /** The text starts with a `---` line. */
  hasFrontmatter: boolean;
  /** A closing `---` line was found. */
  closed: boolean;
  name: string;
  description: string;
  body: string;
};

function unquote(value: string): string {
  const v = value.trim();
  if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) {
    return v.slice(1, -1).replace(/\\"/g, '"').replace(/\\\\/g, "\\");
  }
  if (v.length >= 2 && v.startsWith("'") && v.endsWith("'")) return v.slice(1, -1);
  return v;
}

/** Lenient client-side read of a SKILL.md: enough to lint and preview, never to persist. */
export function parseSkillMd(raw: string): ParsedSkillMd {
  let text = (raw ?? "").replace(/\r\n?/g, "\n");
  if (text.startsWith("﻿")) text = text.slice(1);
  text = text.replace(/^\s+/, "");
  const empty: ParsedSkillMd = { hasFrontmatter: false, closed: false, name: "", description: "", body: "" };
  const lines = text.split("\n");
  if (!text.startsWith("---") || lines[0].trim() !== "---") {
    return { ...empty, hasFrontmatter: text.startsWith("---"), body: text.trim() };
  }
  let close = -1;
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === "---") {
      close = i;
      break;
    }
  }
  if (close < 0) return { ...empty, hasFrontmatter: true };
  const fields: Record<string, string> = {};
  let key: string | null = null;
  for (const line of lines.slice(1, close)) {
    if (!line.trim()) continue;
    const colon = line.indexOf(":");
    const continuation = /^\s/.test(line);
    if (!continuation && colon > 0) {
      key = line.slice(0, colon).trim().toLowerCase();
      fields[key] = unquote(line.slice(colon + 1));
    } else if (continuation && key) {
      const prev = fields[key];
      const next = line.trim();
      fields[key] = !prev || prev === ">" || prev === "|" ? next : `${prev} ${next}`;
    }
  }
  return {
    hasFrontmatter: true,
    closed: true,
    name: (fields.name ?? "").trim(),
    description: (fields.description ?? "").trim(),
    body: lines.slice(close + 1).join("\n").trim(),
  };
}

export type LintLevel = "error" | "warning" | "info";
export type LintIssue = { level: LintLevel; code: string; message: string };

const PLACEHOLDER_NAME = "my-skill-name";
const PLACEHOLDER_BODY = /First step the agent should take\./;
const TRIGGER_HINT = /\b(use (this )?(when|for|if|whenever)|when(ever)? (the )?(user|you|asked|asking)|trigger(s|ed)? (on|when))\b/i;

export function lintSkillMd(raw: string, existingNames: string[] = [], editingName: string | null = null): LintIssue[] {
  const issues: LintIssue[] = [];
  const add = (level: LintLevel, code: string, message: string) => issues.push({ level, code, message });
  if (!raw || !raw.trim()) {
    add("error", "empty", "SKILL.md is empty.");
    return issues;
  }
  const p = parseSkillMd(raw);
  if (!p.hasFrontmatter) {
    add("error", "no-frontmatter", "Start the file with a --- line, then name: and description:, then another --- line.");
    return issues;
  }
  if (!p.closed) {
    add("error", "unclosed-frontmatter", "The frontmatter is never closed. Add a line containing only ---.");
    return issues;
  }

  if (!p.name) add("error", "name-missing", "Add a name: line. Lowercase letters, numbers and hyphens only.");
  else {
    if (p.name.length > MAX_NAME_LENGTH) add("error", "name-long", `Name is ${p.name.length} characters; the maximum is ${MAX_NAME_LENGTH}.`);
    if (!NAME_PATTERN.test(p.name)) add("error", "name-chars", "Name may only contain lowercase letters, numbers and hyphens, for example quarterly-report.");
    for (const word of RESERVED_WORDS) {
      if (p.name.includes(word)) add("error", "name-reserved", `Name cannot contain the reserved word "${word}".`);
    }
    if (p.name === PLACEHOLDER_NAME) add("warning", "name-placeholder", "Rename the skill; my-skill-name is the template placeholder.");
    if (existingNames.includes(p.name) && p.name !== editingName) {
      add("warning", "name-taken", `A skill named ${p.name} already exists in this workspace. Saving a new one with this name will be refused.`);
    }
    if (editingName && p.name !== editingName) {
      add("warning", "name-immutable", `The name cannot be changed once saved; the server keeps ${editingName}.`);
    }
  }

  if (!p.description) {
    add("error", "description-missing", "Add a description: say what the skill does AND when to use it. It is all the agent sees until it loads the skill.");
  } else {
    if (p.description.length > MAX_DESCRIPTION_LENGTH) {
      add("error", "description-long", `Description is ${p.description.length} characters; the maximum is ${MAX_DESCRIPTION_LENGTH}.`);
    }
    if (XML_TAG.test(p.description)) add("error", "description-tags", "Description cannot contain XML or HTML tags (for example a placeholder in angle brackets).");
    if (/describe the situations/i.test(p.description)) add("warning", "description-placeholder", "Replace the placeholder in the description with the real trigger situations.");
    if (p.description.length < 40) add("warning", "description-short", "A very short description rarely triggers the skill. Say what it does and when to use it.");
    if (!TRIGGER_HINT.test(p.description)) {
      add("warning", "description-trigger", 'Add a trigger such as "Use when the user asks for ...". Agents pick skills from the description alone.');
    }
    const words = p.description.trim().split(/\s+/).length;
    if (words > MAX_PROMPT_WORDS) {
      add("info", "description-truncated", `Agents see only the first ${MAX_PROMPT_WORDS} of ${words} description words in their skill list. Put the trigger early.`);
    }
  }

  if (!p.body) add("error", "body-missing", "There are no instructions after the frontmatter.");
  else {
    if (p.body.length > MAX_BODY_CHARS) add("error", "body-long", `Instructions are ${p.body.length.toLocaleString("en-US")} characters; the maximum is ${MAX_BODY_CHARS.toLocaleString("en-US")}.`);
    else if (p.body.length > 20_000) add("warning", "body-heavy", `Instructions are about ${estimateTokens(p.body).toLocaleString("en-US")} tokens every time the skill loads. Move reference material into bundled files.`);
    if (PLACEHOLDER_BODY.test(p.body)) add("warning", "body-placeholder", "The template placeholder steps are still in the instructions.");
    if (!/^#{1,6}\s/m.test(p.body)) add("info", "body-headings", "Headings (## Instructions, ## Example) make long skills easier for the agent to follow.");
  }
  return issues;
}

export function hasLintErrors(issues: LintIssue[]): boolean {
  return issues.some((i) => i.level === "error");
}

export function estimateTokens(text: string): number {
  return Math.ceil((text ?? "").length / 4);
}

function singleLine(text: string): string {
  return (text ?? "").replace(/\s+/g, " ").trim();
}

/** At most 12 words, one line - what an agent's prompt carries for a skill. */
export function briefDescription(text: string): string {
  const words = singleLine(text).split(" ").filter(Boolean);
  if (words.length <= MAX_PROMPT_WORDS) return words.join(" ");
  return words.slice(0, MAX_PROMPT_WORDS).join(" ") + "...";
}

export function promptLine(name: string, description: string): string {
  return `- ${name}: ${briefDescription(description)}`;
}

/** The Level 1 "## Skills" block an agent's system prompt gets (core SkillPromptBlock.render). */
export function renderPromptBlock(skills: { name: string; description: string }[]): string {
  if (!skills.length) return "";
  const head =
    "\n\n## Skills\n" +
    "Call load_skill(name) first when the request matches one of these, then follow it. " +
    "Read bundled files with read_skill_file only if needed. Skip irrelevant skills and " +
    "do not mention skills to the user unless asked.\n";
  return head + skills.slice(0, MAX_SKILLS_IN_PROMPT).map((s) => promptLine(s.name, s.description) + "\n").join("");
}

/** Canonical SKILL.md text (core SkillMarkdownParser.render). */
export function renderSkillMd(name: string, description: string, body: string): string {
  const escaped = description.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
  return `---\nname: ${name}\ndescription: "${escaped}"\n---\n\n${body.trim()}\n`;
}

/** A valid, unused name for a copy: `<name>-custom`, then `-custom-2`... trimmed to 64 chars. */
export function forkName(name: string, taken: string[], suffix = "custom"): string {
  let base = name.toLowerCase().replace(/[^a-z0-9-]/g, "-");
  for (const w of RESERVED_WORDS) base = base.split(w).join("");
  base = base.replace(/-+/g, "-").replace(/^-|-$/g, "") || "skill";
  const used = new Set(taken);
  for (let n = 1; n < 1000; n++) {
    const tail = n === 1 ? `-${suffix}` : `-${suffix}-${n}`;
    const candidate = base.slice(0, MAX_NAME_LENGTH - tail.length).replace(/-$/, "") + tail;
    if (!used.has(candidate)) return candidate;
  }
  return `${base.slice(0, 50)}-${Date.now().toString(36)}`;
}

/** Draft SKILL.md for a copy of an existing skill under a fresh name. */
export function forkSkillMd(source: { name: string; description: string; body: string }, taken: string[]): { name: string; skillMd: string } {
  const name = forkName(source.name, taken);
  return { name, skillMd: renderSkillMd(name, source.description, source.body) };
}

// ------------------------------------------------------------------ starter templates

export type SkillTemplate = { id: string; title: string; blurb: string; skillMd: string };

export const SKILL_TEMPLATES: SkillTemplate[] = [
  {
    id: "blank",
    title: "Blank",
    blurb: "The minimal skeleton: instructions plus one example.",
    skillMd: `---
name: my-skill-name
description: What this skill does. Use when the user asks for (describe the situations that should trigger it).
---

# My skill

## Instructions
1. First step the agent should take.
2. Next step.

## Example
Show one good example of the result you want.
`,
  },
  {
    id: "checklist",
    title: "Checklist runner",
    blurb: "Walk through a fixed checklist and report pass/fail per item.",
    skillMd: `---
name: release-checklist
description: Runs the team's pre-release checklist and reports pass or fail for each item. Use when the user asks if a release is ready to ship or asks for a go/no-go check.
---

# Release checklist

## Instructions
1. Ask which release or version is being checked if the user has not said.
2. Go through every item below in order. Mark each PASS, FAIL or UNKNOWN, and give one line of evidence.
3. Never mark an item PASS without evidence from the user or a tool result.
4. End with a go/no-go recommendation and the list of blocking items.

## Checklist
- Tests pass on the release branch
- Changelog is updated
- Database migrations reviewed
- Rollback plan written down
- Owner on call during rollout
`,
  },
  {
    id: "reviewer",
    title: "Rubric reviewer",
    blurb: "Score a draft against a rubric and suggest concrete fixes.",
    skillMd: `---
name: draft-reviewer
description: Reviews a document against a scoring rubric and suggests specific edits. Use when the user pastes a draft and asks for feedback, a review or a critique.
---

# Draft reviewer

## Instructions
1. Read the whole draft before commenting.
2. Score each rubric criterion from 1 to 5 with one sentence of reasoning.
3. Quote the exact passage you are commenting on, then propose a rewrite.
4. Finish with the three changes that would improve the draft the most.

## Rubric
- Clarity: can a new reader follow it in one pass?
- Evidence: is every claim backed by a fact or example?
- Structure: does each paragraph have one job?
- Tone: does it fit the audience?
`,
  },
  {
    id: "style-guide",
    title: "House style guide",
    blurb: "Rewrite text so it always follows your voice and formatting rules.",
    skillMd: `---
name: house-style
description: Applies the company's writing style to any text the user wants written or rewritten. Use when the user asks to draft, polish or rewrite customer-facing copy, emails or announcements.
---

# House style

## Instructions
1. Write in plain language: short sentences, active voice, no jargon.
2. Address the reader as "you". Refer to the company as "we".
3. Lead with the outcome for the reader, then the detail.
4. After rewriting, list the style rules you applied in one line.

## Rules
- No exclamation marks, no emoji.
- Dates as 12 March 2026. Numbers under ten are spelled out.
- Headlines in sentence case.
`,
  },
  {
    id: "triage",
    title: "Triage and route",
    blurb: "Classify incoming requests and decide who handles them.",
    skillMd: `---
name: request-triage
description: Classifies an incoming request by type and urgency and recommends who should handle it. Use when the user pastes a support ticket, bug report or customer message and asks what to do with it.
---

# Request triage

## Instructions
1. Classify the request: bug, question, feature request or complaint.
2. Rate urgency: P1 (service down or data loss), P2 (blocked), P3 (annoying), P4 (nice to have).
3. Name the team that should own it and why, in one sentence.
4. Draft a two-line acknowledgement the user can send back.

## Output format
Type, Urgency, Owner, Reason, Draft reply.
`,
  },
  {
    id: "research",
    title: "Research brief",
    blurb: "Turn a question into a sourced, structured brief.",
    skillMd: `---
name: research-brief
description: Produces a short research brief with findings, sources and open questions. Use when the user asks to look into a topic, compare options or prepare background for a decision.
---

# Research brief

## Instructions
1. Restate the question in one sentence and confirm any ambiguity with the user.
2. Gather evidence with the available tools. Prefer primary sources.
3. Write the brief: summary, key findings, what the sources disagree on, open questions.
4. Cite every factual claim. Say plainly when you could not find something.

## Length
One page unless the user asks for more.
`,
  },
];

// ------------------------------------------------------------------ list search / filter

export type SkillListFilter = "all" | "custom" | "builtin" | "on" | "off" | "attention";

export const SKILL_FILTERS: { id: SkillListFilter; label: string }[] = [
  { id: "all", label: "All" },
  { id: "custom", label: "Custom" },
  { id: "builtin", label: "Built-in" },
  { id: "on", label: "On" },
  { id: "off", label: "Off" },
  { id: "attention", label: "Needs attention" },
];

type FilterableSkill = {
  name: string;
  description: string;
  source: "BUILT_IN" | "CUSTOM";
  enabled: boolean;
  createdBy?: string | null;
  pendingImprovement?: boolean;
};

/** Agent drafts, staged rewrites, or anything the supply-chain scan queued for a person. */
export function skillNeedsAttention(s: FilterableSkill, flaggedNames: ReadonlySet<string> = new Set()): boolean {
  return !!s.pendingImprovement || (!!s.createdBy && s.createdBy.startsWith("agent:") && !s.enabled) || flaggedNames.has(s.name);
}

export function filterSkills<T extends FilterableSkill>(
  skills: T[],
  query: string,
  filter: SkillListFilter,
  flaggedNames: ReadonlySet<string> = new Set()
): T[] {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
  return skills.filter((s) => {
    if (filter === "custom" && s.source !== "CUSTOM") return false;
    if (filter === "builtin" && s.source !== "BUILT_IN") return false;
    if (filter === "on" && !s.enabled) return false;
    if (filter === "off" && s.enabled) return false;
    if (filter === "attention" && !skillNeedsAttention(s, flaggedNames)) return false;
    if (!terms.length) return true;
    const hay = `${s.name} ${s.description}`.toLowerCase();
    return terms.every((t) => hay.includes(t));
  });
}

// ------------------------------------------------------------------ usage

/** Resolve attached agent ids to display names, keeping unknown ids out of the UI entirely. */
export function agentsUsingSkill(
  attachedAgentIds: string[] | null | undefined,
  agents: { id: string; name?: string | null }[]
): { id: string; name: string }[] {
  const byId = new Map(agents.map((a) => [String(a.id), a.name?.trim() || "Untitled agent"]));
  const out: { id: string; name: string }[] = [];
  for (const id of attachedAgentIds ?? []) {
    const name = byId.get(String(id));
    if (name) out.push({ id: String(id), name });
  }
  return out.sort((a, b) => a.name.localeCompare(b.name));
}
