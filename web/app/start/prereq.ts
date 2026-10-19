// "What does this goal honestly need, and is it there?" - the prerequisite
// state behind each goal card on /start.
//
// Reuses the template prerequisite machinery instead of re-deciding it:
// prereqsForConsoleTemplate (components/agents/templatePrereqs.ts) is the one
// place that knows what a console template needs (a web-search connector, Code
// execution, documents), and isOpenConsoleTemplate (starterTemplates.ts) is the
// one place that knows which templates stay open on the FREE plan. This file
// only joins that to what the workspace actually has (WorkspaceSignals) and
// turns the difference into one of five card states.
//
// The rule that shapes every branch: never claim a capability that is off, and
// never claim one is off when we simply could not check. A signal that is
// `null` means "not answered" (a 403 on an admin-only read, a network blip):
// that is reported as UNCHECKED, not as missing - the same distinction
// TemplateSetupGuide draws between "not checked" and "not done".

import { AGENT_TEMPLATES } from "../components/agentTemplates";
import { isOpenConsoleTemplate } from "../components/agents/starterTemplates";
import { prereqsForConsoleTemplate, type TemplatePrereqs } from "../components/agents/templatePrereqs";
import { findGoal, type GoalDef } from "./goals";

/** What the workspace has, each field independently nullable (null = could not check). */
export interface WorkspaceSignals {
  /** The loader has finished (every probe answered or failed); false = still checking. */
  readonly loaded: boolean;
  /** Tenant plan from /api/tenant/settings. */
  readonly plan: string | null;
  /** The plan read failed outright: treat as FREE (fail closed), like the templates gallery does. */
  readonly planFailed: boolean;
  /** GET /api/providers/availability canCreateAgents: a BYOK key or the hosted pool can run an agent. */
  readonly canCreateAgents: boolean | null;
  /** The personal tutor is switched on for this deployment. */
  readonly tutorOn: boolean | null;
  /** Built-in deep research is enabled AND has a search key. */
  readonly deepResearch: boolean | null;
  /** Names of connectors registered and enabled in this workspace. */
  readonly mcpEnabled: readonly string[] | null;
  /** A tenant E2B credential is active (Code execution can run). */
  readonly e2bReady: boolean | null;
}

/** Nothing answered yet - every card reads "checking" until the signals land. */
export const UNKNOWN_SIGNALS: WorkspaceSignals = {
  loaded: false,
  plan: null,
  planFailed: false,
  canCreateAgents: null,
  tutorOn: null,
  deepResearch: null,
  mcpEnabled: null,
  e2bReady: null,
};

export type MissingItem = "provider" | "web-search" | "code-execution" | "documents";

export type CardStateKind =
  /** Signals have not landed; the card is still usable. */
  | "checking"
  /** Everything it needs is there (or nothing is needed). */
  | "ready"
  /** Usable, but something it would use is missing - the card says exactly what, and what changes without it. */
  | "reduced"
  /** Cannot create an agent here at all until a model provider exists. */
  | "blocked"
  /** The template is part of the paid gallery and this workspace is on the FREE plan. */
  | "locked";

export interface CardState {
  readonly kind: CardStateKind;
  /** Items known to be missing (drive the per-item note and setup link). */
  readonly missing: readonly MissingItem[];
  /** Items we could not check (the card says so rather than guessing). */
  readonly unchecked: readonly MissingItem[];
  /**
   * What "go" does for this goal right now: an agent from this template, or a
   * page. Differs from the goal's own definition only for the tutor fallback.
   */
  readonly action:
    | { readonly type: "agent"; readonly templateKey: string }
    | { readonly type: "route"; readonly href: string }
    | { readonly type: "none" };
  /** The tutor is off, so "learn" falls back to the one-conversation tutor agent. */
  readonly fellBack: boolean;
}

/** Where each missing item is fixed - existing pages only. */
export const MISSING_SETUP_HREF: Readonly<Record<MissingItem, string>> = {
  provider: "/providers",
  // Connectors (Brave Search et al.) live on the Integrations page.
  "web-search": "/tools",
  // The E2B credential card lives with the provider keys.
  "code-execution": "/providers",
  documents: "/knowledge",
};

type TemplateLike = { key: string; category?: string; suggestKnowledgeBase?: boolean };

function templateOf(key: string): TemplateLike | null {
  return (AGENT_TEMPLATES as unknown as readonly TemplateLike[]).find((t) => t.key === key) ?? null;
}

function isFreeish(s: WorkspaceSignals): boolean {
  return s.planFailed || s.plan === "FREE";
}

/** What the template needs, minus what the workspace is known to have. */
function gapsFor(prereqs: TemplatePrereqs, s: WorkspaceSignals): { missing: MissingItem[]; unchecked: MissingItem[] } {
  const missing: MissingItem[] = [];
  const unchecked: MissingItem[] = [];

  if (prereqs.e2b) {
    if (s.e2bReady === false) missing.push("code-execution");
    else if (s.e2bReady === null) unchecked.push("code-execution");
  }

  // Required connectors: interchangeable alternatives (github OR gitlab) count
  // once, and the group is satisfied by ANY enabled member.
  const groups = new Map<string, string[]>();
  prereqs.mcp.forEach((m, i) => {
    if (m.necessity !== "required") return;
    const id = m.alternativeGroup ?? `server:${m.server}:${i}`;
    groups.set(id, [...(groups.get(id) ?? []), m.server]);
  });
  if (groups.size > 0) {
    if (s.mcpEnabled === null) {
      unchecked.push("web-search");
    } else {
      const enabled = new Set(s.mcpEnabled);
      for (const members of groups.values()) {
        if (!members.some((m) => enabled.has(m))) {
          missing.push("web-search");
          break;
        }
      }
    }
  }

  // No signal exists for "has documents" (and none of the starting goals needs
  // one), so a template that REQUIRES them is reported as missing outright:
  // the honest default for "its whole job is answering from your documents".
  if (prereqs.knowledge === "required") missing.push("documents");

  return { missing, unchecked };
}

/**
 * The card state for one goal given what the workspace has. Pure.
 *
 * `deepResearchAvailable` is passed through to the prerequisite machinery so a
 * deployment that offers built-in deep research relaxes the web-search
 * connector to optional for the research template, exactly as the setup guide
 * does.
 */
export function evaluateGoal(goalOrId: GoalDef | string, signals: WorkspaceSignals): CardState {
  const goal = typeof goalOrId === "string" ? findGoal(goalOrId) : goalOrId;
  if (!goal) return { kind: "blocked", missing: [], unchecked: [], action: { type: "none" }, fellBack: false };

  // ---- route goals ----------------------------------------------------
  if (goal.kind === "route") {
    const href = goal.href ?? "/home";
    // A route that can be switched off on the deployment (the tutor) falls
    // back to an agent template ONLY once the probe says it is off. Until it
    // answers, the route is offered: the page explains itself if it is off.
    if (goal.fallbackTemplateKey && signals.tutorOn === false) {
      return evaluateTemplate(goal.fallbackTemplateKey, signals, true);
    }
    return {
      // Only a route with a fallback depends on a probe; the others are always offered.
      kind: goal.fallbackTemplateKey && !signals.loaded ? "checking" : "ready",
      missing: [],
      unchecked: [],
      action: { type: "route", href },
      fellBack: false,
    };
  }

  // ---- agent goals ----------------------------------------------------
  if (!goal.templateKey) {
    return { kind: "blocked", missing: [], unchecked: [], action: { type: "none" }, fellBack: false };
  }
  return evaluateTemplate(goal.templateKey, signals, false);
}

function evaluateTemplate(templateKey: string, signals: WorkspaceSignals, fellBack: boolean): CardState {
  const action = { type: "agent", templateKey } as const;
  const template = templateOf(templateKey);
  if (!template) return { kind: "blocked", missing: [], unchecked: [], action: { type: "none" }, fellBack };

  // The full gallery is a paid capability in the UI; a FREE workspace keeps
  // only the open keys (starterTemplates.ts). Respect that here instead of
  // quietly creating a template the gallery would show as locked.
  if (isFreeish(signals) && !isOpenConsoleTemplate(templateKey)) {
    return { kind: "locked", missing: [], unchecked: [], action, fellBack };
  }

  if (signals.canCreateAgents === false) {
    return { kind: "blocked", missing: ["provider"], unchecked: [], action, fellBack };
  }

  const prereqs = prereqsForConsoleTemplate(templateKey, {
    suggestKnowledgeBase: template.suggestKnowledgeBase,
    category: template.category,
    deepResearchAvailable: signals.deepResearch === true,
  });
  const { missing, unchecked } = gapsFor(prereqs, signals);
  // A research template's connector need depends on deep research; if that
  // probe has not answered, the web-search need could be relaxed - say it was
  // not checked rather than "missing".
  if (templateKey === "research-assistant" && signals.deepResearch === null && missing.includes("web-search")) {
    missing.splice(missing.indexOf("web-search"), 1);
    if (!unchecked.includes("web-search")) unchecked.push("web-search");
  }

  if (missing.length > 0) return { kind: "reduced", missing, unchecked, action, fellBack };
  if (!signals.loaded) return { kind: "checking", missing: [], unchecked, action, fellBack };
  return { kind: "ready", missing: [], unchecked, action, fellBack };
}
