import { describe, expect, it } from "vitest";
import { AGENT_TEMPLATES, AGENT_TEMPLATE_CATEGORIES, templateKnowledgeHintKey, templateNameKey, templateTaglineKey } from "../agentTemplates";
import {
  QUICK_START_TEMPLATE_KEYS,
  TAGGED_EXISTING_TEMPLATE_KEYS,
  TEMPLATE_AUDIENCES,
  TEMPLATE_RELATED_LINKS,
  audienceOfTemplate,
  audienceTagLabelKey,
  isTemplateAudience,
  templateChips,
  templatesForAudience,
} from "./templateAudience";
import { QUIET_TEMPLATE_CAPABILITIES, templateCapabilityKeys, templateWantsFileAnalysis } from "./templateCapabilities";
import { MCP_SERVER_KEYS, isDraftOnlyTemplate, prereqsForConsoleTemplate } from "./templatePrereqs";
import { OPEN_CONSOLE_TEMPLATE_KEYS } from "./starterTemplates";
import en from "../../../messages/en.json";

interface Entry {
  key: string;
  category: string;
  audience?: string;
  icon: unknown;
  systemPrompt: string;
  suggestKnowledgeBase?: boolean;
  knowledgeHint?: boolean;
  draftOnly?: boolean;
  capabilities?: readonly string[];
  mcp?: readonly { servers: readonly string[]; requirement: string; oneOf?: boolean }[];
}

const ENTRIES = AGENT_TEMPLATES as unknown as readonly Entry[];
const tpl = (en as Record<string, any>).agentTemplates as Record<string, string>;
const page = (en as Record<string, any>).agentTemplatesPage as Record<string, string>;
const guide = (en as Record<string, any>).templateSetupGuide as Record<string, string>;

// The ten audience templates this wave added (the task's own list, by key).
const AUDIENCE_KEYS = [
  "notes-to-revision-kit",
  "explain-it-simply-tutor",
  "resume-cover-letter-tailor",
  "literature-review-assistant",
  "proposal-invoice-drafter",
  "client-call-to-actions",
  "whatsapp-reply-drafter",
  "daily-briefing-writer",
  "sales-sheet-analyst",
  "pre-pr-review-buddy",
] as const;

const byKey = (key: string): Entry => {
  const found = ENTRIES.find((e) => e.key === key);
  if (!found) throw new Error(`no template ${key}`);
  return found;
};

describe("audience templates - data", () => {
  it("has the namespaces to resolve against (guards every loop below from passing vacuously)", () => {
    expect(ENTRIES.length).toBeGreaterThan(AUDIENCE_KEYS.length);
    expect(tpl).toBeTruthy();
    expect(page).toBeTruthy();
    expect(guide).toBeTruthy();
  });

  it("adds exactly the ten templates, each with the required fields", () => {
    for (const key of AUDIENCE_KEYS) {
      const t = byKey(key);
      expect(t.key).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
      expect((AGENT_TEMPLATE_CATEGORIES as readonly string[]).includes(t.category), `${key}: unknown category ${t.category}`).toBe(true);
      expect(t.icon, `${key}: no icon`).toBeTruthy();
      expect(typeof t.systemPrompt).toBe("string");
      expect(t.systemPrompt.length, `${key}: prompt too thin`).toBeGreaterThan(500);
      expect(typeof t.suggestKnowledgeBase, `${key}: suggestKnowledgeBase must be explicit`).toBe("boolean");
      expect(isTemplateAudience(t.audience), `${key}: missing audience tag`).toBe(true);
    }
    expect(new Set(AUDIENCE_KEYS).size).toBe(10);
  });

  it("template keys are unique across the whole gallery", () => {
    const keys = ENTRIES.map((e) => e.key);
    expect(new Set(keys).size).toBe(keys.length);
  });

  it("every one has an English name and tagline, and a hint where it flags one", () => {
    for (const key of AUDIENCE_KEYS) {
      const t = byKey(key);
      expect(tpl[templateNameKey(key)], `${key} name`).toBeTruthy();
      expect(tpl[templateTaglineKey(key)], `${key} tagline`).toBeTruthy();
      if (t.knowledgeHint) expect(tpl[templateKnowledgeHintKey(key)], `${key} hint`).toBeTruthy();
      // And no hint text without the flag that shows it.
      if (!t.knowledgeHint) expect(tpl[templateKnowledgeHintKey(key)], `${key}: orphan hint`).toBeUndefined();
    }
  });

  it("names, taglines and hints carry no price literal", () => {
    for (const key of AUDIENCE_KEYS) {
      for (const text of [tpl[templateNameKey(key)], tpl[templateTaglineKey(key)], tpl[templateKnowledgeHintKey(key)] ?? ""]) {
        expect(text, `${key}: price literal in "${text}"`).not.toMatch(/[$₹]\s?\d|\bRs\.?\s?\d|\bINR\s?\d/i);
      }
    }
  });
});

describe("audience templates - prompts are honest", () => {
  it("no price literal anywhere in a prompt", () => {
    for (const key of AUDIENCE_KEYS) {
      expect(byKey(key).systemPrompt, key).not.toMatch(/[$₹]\s?\d|\bRs\.?\s?\d|\bINR\s?\d/i);
    }
  });

  it("no prompt claims a power the platform lacks", () => {
    // Affirmative claims only: "you cannot send email" is the honest phrasing.
    const claims =
      /\byou (can|will|are able to) (send|book|schedule|pay|post|publish|call|text|email|file|submit)\b|\bi (can|will) (send|book|schedule|pay|post|publish)\b|\b(connected to|access to) (your|their|the user's) (inbox|gmail|calendar|bank|crm|whatsapp account)\b|\bautomatically (send|post|reply|pay|book)/i;
    for (const key of AUDIENCE_KEYS) {
      expect(byKey(key).systemPrompt, `${key}: unsupported capability claim`).not.toMatch(claims);
    }
  });

  it("the drafting templates say plainly that they send nothing", () => {
    for (const key of ["whatsapp-reply-drafter", "proposal-invoice-drafter", "client-call-to-actions"]) {
      expect(byKey(key).systemPrompt, key).toMatch(/\b(never send|cannot send|can't send)\b/i);
    }
  });

  it("every tool or capability a prompt names is one the template can actually have", () => {
    const named = (key: string) => byKey(key).systemPrompt;
    // run_code only where Code execution is declared; deep_research only
    // where the quiet capability (or a literal 'when available') guards it.
    for (const key of AUDIENCE_KEYS) {
      if (/\brun_code\b/.test(named(key))) {
        expect(templateCapabilityKeys(key), `${key}: names run_code without declaring it`).toContain("e2b-code-execution");
      }
      if (/\bdeep_research\b/.test(named(key))) {
        expect(QUIET_TEMPLATE_CAPABILITIES[key], `${key}: names deep_research without the capability`).toContain("deep-research");
        expect(named(key), `${key}: deep_research must be conditional`).toMatch(/when the deep_research tool is available/i);
      }
    }
  });

  it("safe defaults: the only capability any of them switches on by name is Code execution", () => {
    for (const key of AUDIENCE_KEYS) {
      for (const cap of templateCapabilityKeys(key)) expect(cap, key).toBe("e2b-code-execution");
    }
    expect(templateCapabilityKeys("sales-sheet-analyst")).toEqual(["e2b-code-execution"]);
    expect(templateWantsFileAnalysis("sales-sheet-analyst")).toBe(true);
  });
});

describe("audience templates - prerequisites are truthful", () => {
  it("every server a template's prerequisites name is in the curated 18, and every reason has a string", () => {
    for (const key of AUDIENCE_KEYS) {
      const t = byKey(key);
      const prereqs = prereqsForConsoleTemplate(key, t);
      for (const s of prereqs.mcp) {
        expect((MCP_SERVER_KEYS as readonly string[]).includes(s.server), `${key}: unknown server ${s.server}`).toBe(true);
        expect(guide[s.reasonKey], `${key}: templateSetupGuide.${s.reasonKey}`).toBeTruthy();
      }
    }
  });

  it("the entry's own mcp field and the live prerequisite layer agree", () => {
    for (const key of AUDIENCE_KEYS) {
      const t = byKey(key);
      const declared = new Set((t.mcp ?? []).flatMap((m) => [...m.servers]));
      const live = new Set(prereqsForConsoleTemplate(key, t).mcp.map((m) => m.server as string));
      expect([...live].sort(), `${key}: mcp drift`).toEqual([...declared].sort());
    }
  });

  it("nothing here asks for a Google connection or an indexed repository (the console cannot enable either)", () => {
    for (const key of AUDIENCE_KEYS) {
      const p = prereqsForConsoleTemplate(key, byKey(key));
      expect(p.google, key).toBe(false);
      expect(p.codeIntel, key).toBe(false);
    }
  });

  it("only the spreadsheet analyst needs the code sandbox", () => {
    for (const key of AUDIENCE_KEYS) {
      expect(prereqsForConsoleTemplate(key, byKey(key)).e2b, key).toBe(key === "sales-sheet-analyst");
    }
  });

  it("the WhatsApp drafter is draft-only: flagged, no channel step, and the chip says it never sends", () => {
    const t = byKey("whatsapp-reply-drafter");
    expect(t.draftOnly).toBe(true);
    expect(isDraftOnlyTemplate("whatsapp-reply-drafter")).toBe(true);
    // The data flag and the prerequisite layer's own set must agree.
    for (const key of AUDIENCE_KEYS) expect(isDraftOnlyTemplate(key), key).toBe(Boolean(byKey(key).draftOnly));
    expect(prereqsForConsoleTemplate("whatsapp-reply-drafter", t).channel).toBe(false);
    const chips = templateChips("whatsapp-reply-drafter", t);
    expect(chips.map((c) => c.id)).toContain("draftOnly");
    expect(chips.map((c) => c.id)).not.toContain("channel");
    // ...while an ordinary customer-facing template in the same category still asks for one.
    expect(prereqsForConsoleTemplate("customer-support-triage", byKey("customer-support-triage")).channel).toBe(true);
  });

  it("the literature review: deep research and search are bonuses, never requirements", () => {
    const t = byKey("literature-review-assistant");
    expect(QUIET_TEMPLATE_CAPABILITIES["literature-review-assistant"]).toEqual(["deep-research"]);
    const chips = templateChips("literature-review-assistant", t);
    const search = chips.find((c) => c.id.startsWith("mcp:"));
    expect(search?.tone).toBe("optional");
    expect(search?.params?.server).toBe("Brave Search");
    expect(chips.find((c) => c.id === "deepResearch")?.tone).toBe("optional");
    expect(chips.some((c) => c.tone === "required" && c.id.startsWith("mcp:"))).toBe(false);
  });

  it("the spreadsheet analyst says it needs code execution, the briefing writer only offers search", () => {
    expect(templateChips("sales-sheet-analyst", byKey("sales-sheet-analyst")).map((c) => c.id)).toContain("e2b");
    const briefing = templateChips("daily-briefing-writer", byKey("daily-briefing-writer"));
    expect(briefing.find((c) => c.id.startsWith("mcp:"))?.tone).toBe("optional");
    expect(briefing.some((c) => c.tone === "required" && c.id !== "knowledge")).toBe(false);
  });
});

describe("persona filter", () => {
  it("every persona has an English label, and the filter heading exists", () => {
    for (const a of TEMPLATE_AUDIENCES) expect(tpl[audienceTagLabelKey(a)], a).toBeTruthy();
    expect(page.forWhoLabel).toBeTruthy();
    expect(page.everything).toBeTruthy();
  });

  it("the ten new templates cover all five personas", () => {
    const covered = new Set(AUDIENCE_KEYS.map((k) => byKey(k).audience));
    for (const a of TEMPLATE_AUDIENCES) expect(covered.has(a), `no new template for ${a}`).toBe(true);
  });

  it("audienceOfTemplate reads the entry's tag, falls back to the by-key tag of an older entry, else null", () => {
    expect(audienceOfTemplate("notes-to-revision-kit")).toBe("student");
    expect(audienceOfTemplate("whatsapp-reply-drafter")).toBe("smallBusiness");
    expect(audienceOfTemplate("code-review-assistant")).toBe("developer");
    expect(audienceOfTemplate("clinic-appointment-assistant")).toBeNull();
    expect(audienceOfTemplate("no-such-template")).toBeNull();
  });

  it("every older entry tagged by key still exists in the gallery", () => {
    const keys = new Set(ENTRIES.map((e) => e.key));
    for (const k of TAGGED_EXISTING_TEMPLATE_KEYS) expect(keys.has(k), `tagged key ${k} is not a template`).toBe(true);
  });

  it("templatesForAudience filters by persona and returns everything for 'all'", () => {
    expect(templatesForAudience(ENTRIES, "all")).toHaveLength(ENTRIES.length);
    for (const a of TEMPLATE_AUDIENCES) {
      const rows = templatesForAudience(ENTRIES, a);
      expect(rows.length, a).toBeGreaterThan(0);
      for (const r of rows) expect(audienceOfTemplate(r.key)).toBe(a);
    }
    expect(templatesForAudience(ENTRIES, "student").map((t) => t.key)).toContain("homework-helper");
  });
});

describe("Start in 1 minute", () => {
  it("only names real templates, and each needs no setup at all", () => {
    expect(QUICK_START_TEMPLATE_KEYS.length).toBeGreaterThan(0);
    for (const key of QUICK_START_TEMPLATE_KEYS) {
      const t = byKey(key);
      expect(tpl[templateNameKey(key)], `${key} label`).toBeTruthy();
      const p = prereqsForConsoleTemplate(key, t);
      expect(p.knowledge, `${key}: needs documents`).not.toBe("required");
      expect(p.channel, `${key}: needs a channel`).toBe(false);
      expect(p.e2b || p.google || p.codeIntel, `${key}: needs a capability or connection`).toBe(false);
      expect(p.mcp.filter((m) => m.necessity === "required"), `${key}: needs a connector`).toEqual([]);
      expect(templateCapabilityKeys(key), `${key}: declares a capability`).toEqual([]);
      expect(QUIET_TEMPLATE_CAPABILITIES[key], `${key}: declares a quiet capability`).toBeUndefined();
    }
  });

  it("stays open on every plan, in step with the gallery's open list", () => {
    for (const key of QUICK_START_TEMPLATE_KEYS) {
      expect(OPEN_CONSOLE_TEMPLATE_KEYS, key).toContain(key);
    }
  });
});

describe("chips and related links", () => {
  it("every chip any template can show has an English string, and {server} chips carry their param", () => {
    for (const t of ENTRIES) {
      for (const chip of templateChips(t.key, t)) {
        const text = tpl[chip.labelKey];
        expect(text, `${t.key}: missing agentTemplates.${chip.labelKey}`).toBeTruthy();
        if (/\{server\}/.test(text)) expect(chip.params?.server, `${t.key}: ${chip.id}`).toBeTruthy();
        expect(["required", "optional", "info"]).toContain(chip.tone);
      }
      const ids = templateChips(t.key, t).map((c) => c.id);
      expect(new Set(ids).size, `${t.key}: duplicate chip ids`).toBe(ids.length);
    }
  });

  it("an interchangeable pair (GitHub or GitLab) is one chip, and required beats optional in a group", () => {
    const chips = templateChips("code-review-assistant", byKey("code-review-assistant"));
    const repo = chips.filter((c) => c.id.startsWith("mcp:"));
    expect(repo).toHaveLength(1);
    expect(repo[0].params?.server).toBe("GitHub or GitLab");
    expect(repo[0].tone).toBe("required");
  });

  it("related links point at in-app routes and have a string", () => {
    for (const [key, link] of Object.entries(TEMPLATE_RELATED_LINKS)) {
      expect(ENTRIES.some((e) => e.key === key), `link for unknown template ${key}`).toBe(true);
      expect(link.href.startsWith("/"), `${key}: href must be in-app`).toBe(true);
      expect(link.href).not.toMatch(/localhost|127\.0\.0\.1/);
      expect(tpl[link.labelKey], `${key}: ${link.labelKey}`).toBeTruthy();
    }
    expect(TEMPLATE_RELATED_LINKS["explain-it-simply-tutor"].href).toBe("/learn/tutor");
  });
});
