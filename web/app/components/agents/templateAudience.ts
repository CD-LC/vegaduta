// "Who is it for?" - the persona axis of the template gallery, plus the
// truthful requirement chips on each card (wave 3, "templates" lane).
//
// Research behind this: the top reason people give for not using an AI agent
// is "not sure how to use it effectively". The gallery already groups by
// audience SECTION (everyone / organisations / developers / industries) and
// then by category; that answers "what is this agent about" but not "which of
// these is for someone like me". This module adds a second, flatter axis -
// student, researcher, freelancer, small business, developer - and a short
// "Start in 1 minute" list for the agents empty state.
//
// Everything here is pure data + helpers so it is unit-tested without a DOM
// (templateAudience.test.ts). User-visible text is NOT stored here: labels and
// chip text are en.json keys in the "agentTemplates" namespace, built by the
// helpers below.

import { AGENT_TEMPLATES } from "../agentTemplates";
import { QUIET_TEMPLATE_CAPABILITIES } from "./templateCapabilities";
import {
  isDraftOnlyTemplate,
  prereqsForConsoleTemplate,
  type McpServerKey,
  type McpSuggestion,
} from "./templatePrereqs";

export const TEMPLATE_AUDIENCES = ["student", "researcher", "freelancer", "smallBusiness", "developer"] as const;

export type TemplateAudience = (typeof TEMPLATE_AUDIENCES)[number];

/** en.json "agentTemplates" key for an audience's filter label, e.g. smallBusiness -> personaSmallBusinessLabel. */
export function audienceTagLabelKey(audience: TemplateAudience): string {
  return `persona${audience[0].toUpperCase()}${audience.slice(1)}Label`;
}

export function isTemplateAudience(value: unknown): value is TemplateAudience {
  return typeof value === "string" && (TEMPLATE_AUDIENCES as readonly string[]).includes(value);
}

// Older entries that were written before the persona tag existed. Tagged here
// by key rather than by editing each entry, so this lane adds to the gallery
// without rewriting 70 existing records. A new template carries its own
// `audience` field instead and does not belong in this map.
const EXISTING_TEMPLATE_AUDIENCE: Readonly<Record<string, TemplateAudience>> = {
  "homework-helper": "student",
  "exam-prep-coach": "student",
  "language-learning-buddy": "student",
  "job-search-assistant": "student",
  "research-assistant": "researcher",
  "data-analyst": "researcher",
  "meeting-notes-summarizer": "freelancer",
  "social-media-content": "smallBusiness",
  "code-review-assistant": "developer",
  "debugging-assistant": "developer",
  "technical-docs-writer": "developer",
  "git-commit-pr-writer": "developer",
  "test-case-generator": "developer",
  "incident-triage-assistant": "developer",
};

/** Keys of the older entries this module tags - exported so the test can prove each one still exists. */
export const TAGGED_EXISTING_TEMPLATE_KEYS: readonly string[] = Object.keys(EXISTING_TEMPLATE_AUDIENCE);

interface TemplateLike {
  key: string;
  category?: string;
  audience?: string;
  suggestKnowledgeBase?: boolean;
  draftOnly?: boolean;
}

// AGENT_TEMPLATES is a literal array whose element type is a union of every
// entry's own shape; viewing it through one structural interface keeps this
// module independent of which optional fields a given entry happens to carry.
const ALL_TEMPLATES = AGENT_TEMPLATES as unknown as readonly TemplateLike[];

/** The persona a template is for: its own `audience` tag, else the by-key tag of an older entry, else null. */
export function audienceOfTemplate(key: string): TemplateAudience | null {
  const own = ALL_TEMPLATES.find((t) => t.key === key)?.audience;
  if (isTemplateAudience(own)) return own;
  return EXISTING_TEMPLATE_AUDIENCE[key] ?? null;
}

/** Narrows a list of templates to one persona; "all" returns the list unchanged. */
export function templatesForAudience<T extends { key: string }>(
  templates: readonly T[],
  audience: TemplateAudience | "all"
): T[] {
  if (audience === "all") return [...templates];
  return templates.filter((t) => audienceOfTemplate(t.key) === audience);
}

/**
 * The "Start in 1 minute" row on the agents empty state - one per persona that
 * can start from nothing. Every key here must work with no knowledge base, no
 * connector, no capability and no channel (the test enforces it), because the
 * row's whole promise is "pick one and start talking".
 */
export const QUICK_START_TEMPLATE_KEYS: readonly string[] = [
  "explain-it-simply-tutor",
  "resume-cover-letter-tailor",
  "client-call-to-actions",
  "whatsapp-reply-drafter",
  "pre-pr-review-buddy",
];

/** A page a template's card links to next to "Use this template". `labelKey` is in the "agentTemplates" namespace. */
export interface TemplateRelatedLink {
  href: string;
  labelKey: string;
}

export const TEMPLATE_RELATED_LINKS: Readonly<Record<string, TemplateRelatedLink>> = {
  // /learn/tutor builds a full multi-lesson course; this agent explains one
  // thing in one conversation. The tutor is behind a server flag and says so
  // itself when it is off, so the link makes no promise about availability.
  "explain-it-simply-tutor": { href: "/learn/tutor", labelKey: "relatedTutorLink" },
  // The channel-connected, approval-gated version of the drafter.
  "whatsapp-reply-drafter": { href: "/workflows/templates", labelKey: "relatedWhatsappApprovalLink" },
  "daily-briefing-writer": { href: "/workflows/templates", labelKey: "relatedDailyBriefingLink" },
};

// ---------------------------------------------------------------- chips
//
// Derived from the SAME prerequisite data the setup guide reads
// (prereqsForConsoleTemplate), so a card can never promise less or more than
// the guide behind its button. "required" = the agent is not worth creating
// without it; "optional" = it works without and gets better with it; "info" =
// a fact about how the agent behaves.

export type TemplateChipTone = "required" | "optional" | "info";

export interface TemplateChip {
  id: string;
  tone: TemplateChipTone;
  /** en.json "agentTemplates" key. */
  labelKey: string;
  params?: Record<string, string>;
}

// Product names, not translatable copy - substituted into {server}.
const SERVER_CHIP_NAMES: Readonly<Record<McpServerKey, string>> = {
  filesystem: "Filesystem",
  github: "GitHub",
  postgres: "Postgres",
  "brave-search": "Brave Search",
  slack: "Slack",
  puppeteer: "Puppeteer",
  "playwright-vision": "Playwright",
  gitlab: "GitLab",
  "google-maps": "Google Maps",
  "google-drive": "Google Drive",
  sqlite: "SQLite",
  memory: "Memory",
  "sequential-thinking": "Sequential Thinking",
  sentry: "Sentry",
  "code-intel": "Code intelligence",
  "home-assistant": "Home Assistant",
  atlassian: "Atlassian",
};

function mcpChips(suggestions: readonly McpSuggestion[]): TemplateChip[] {
  // Interchangeable servers (github OR gitlab) read as one chip.
  const order: string[] = [];
  const groups = new Map<string, McpSuggestion[]>();
  for (const s of suggestions) {
    const id = s.alternativeGroup ? `group:${s.alternativeGroup}` : `server:${s.server}`;
    const existing = groups.get(id);
    if (existing) {
      existing.push(s);
    } else {
      groups.set(id, [s]);
      order.push(id);
    }
  }
  return order.map((id) => {
    const members = groups.get(id) ?? [];
    const required = members.some((m) => m.necessity === "required");
    return {
      id: `mcp:${id}`,
      tone: required ? ("required" as const) : ("optional" as const),
      labelKey: required ? "chipMcpRequired" : "chipMcpOptional",
      params: { server: members.map((m) => SERVER_CHIP_NAMES[m.server] ?? m.server).join(" or ") },
    };
  });
}

/**
 * The requirement chips for one console template, most important first.
 * `template` is the gallery entry (read for its knowledge flag and category,
 * exactly as the setup guide does).
 */
export function templateChips(
  key: string,
  template?: { suggestKnowledgeBase?: boolean; category?: string }
): TemplateChip[] {
  const prereqs = prereqsForConsoleTemplate(key, template);
  const chips: TemplateChip[] = [];
  if (isDraftOnlyTemplate(key)) chips.push({ id: "draftOnly", tone: "info", labelKey: "chipDraftOnly" });
  if (prereqs.knowledge === "required") {
    chips.push({ id: "knowledge", tone: "required", labelKey: "chipKnowledgeRequired" });
  } else if (prereqs.knowledge === "recommended") {
    chips.push({ id: "knowledge", tone: "optional", labelKey: "chipKnowledgeRecommended" });
  }
  if (prereqs.e2b) chips.push({ id: "e2b", tone: "required", labelKey: "chipCodeExecution" });
  if (prereqs.google) chips.push({ id: "google", tone: "required", labelKey: "chipGoogle" });
  if (prereqs.codeIntel) chips.push({ id: "codeIntel", tone: "required", labelKey: "chipCodeIntel" });
  if (prereqs.channel) chips.push({ id: "channel", tone: "required", labelKey: "chipChannel" });
  chips.push(...mcpChips(prereqs.mcp));
  // A bonus the workbench switches on after create only when the server lists
  // it available (templateCapabilities.ts) - so "optional", never "required".
  if ((QUIET_TEMPLATE_CAPABILITIES[key] ?? []).includes("deep-research")) {
    chips.push({ id: "deepResearch", tone: "optional", labelKey: "chipDeepResearch" });
  }
  return chips;
}
