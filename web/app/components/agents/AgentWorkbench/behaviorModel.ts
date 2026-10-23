import type {
  EffectiveSetting,
  SettingCatalogEntry,
  SettingRole,
} from "../../../lib/api/settings";
import { numericValue } from "../../settings/settingsModel";

// Pure model behind the agent's "Behavior & limits" tab: which catalog keys feed the summary card, the plain
// sentences that explain what the current values mean, and the small mapping helpers. No React and no i18n here:
// sentences are returned as message keys + params and the components localize them.

/** The catalog keys the summary card and the impact sentences read. Everything else is shown by the panel only. */
export const BEHAVIOR_KEYS = {
  maxSteps: "agent.limits.maxToolSteps",
  turnTimeout: "agent.limits.turnTimeoutSeconds",
  toolTimeout: "agent.limits.toolTimeoutSeconds",
  outputCap: "agent.limits.toolOutputMaxChars",
  retrievalTopK: "agent.limits.retrievalTopK",
  retrievalMinScore: "agent.limits.retrievalMinScore",
  continuation: "agent.limits.continuationPasses",
  approvalContinuation: "approvals.continuation.enabled",
  judgeModel: "quality.judge.model",
  guardFailMode: "guardrails.failMode",
  maxBranches: "delegation.maxBranches",
} as const;

/** DOM ids the tab links to. The two sections carry them as their CollapsibleSection anchorId. */
export const BEHAVIOR_ANCHORS = {
  generation: "agent-behavior-generation",
  autonomy: "agent-behavior-autonomy",
} as const;

/** DOM ids of the workbench tabs and their panels (aria-controls / aria-labelledby wiring). */
export const WORKBENCH_TAB_IDS = {
  setupTab: "workbench-tab-setup",
  behaviorTab: "workbench-tab-behavior",
  setupPanel: "workbench-panel-setup",
  behaviorPanel: "workbench-panel-behavior",
} as const;

/**
 * The workspace-wide page for the same settings. It is a single constant so a change of route is a one-line edit;
 * BehaviorLimitsTab also takes it as a prop.
 */
export const TENANT_RUNTIME_SETTINGS_HREF = "/admin/runtime-settings";

export type WorkbenchTab = "setup" | "behavior";

/** ?section=behavior (the link the playground's run settings use) opens the Behavior tab; anything else, Setup. */
export function tabFromSection(section: string | null | undefined): WorkbenchTab {
  return section === "behavior" ? "behavior" : "setup";
}

/** Catalog entries that can be overridden for one agent. */
export function agentScopedEntries(entries: SettingCatalogEntry[]): SettingCatalogEntry[] {
  return entries.filter((e) => e.scopes.includes("agent"));
}

/** Keys this agent overrides itself (source "agent"), in catalog order. */
export function overriddenKeys(entries: SettingCatalogEntry[], values: Record<string, EffectiveSetting>): string[] {
  return agentScopedEntries(entries)
    .filter((e) => values[e.key]?.source === "agent")
    .map((e) => e.key);
}

/**
 * The role to hand the settings panel. The workbench knows the agent access level: a viewer or runner cannot change
 * anything. For someone who can edit the agent, the keys that need a workspace admin (every security key) are
 * read-only unless the caller is one: pass `isTenantAdmin` (from the signed-in session) and an editor gets
 * EDITOR, an admin TENANT_ADMIN. Without it the answer is undefined, which leaves the decision to the panel (it reads
 * the session itself) and to the server.
 */
export function roleForAgentAccess(
  accessLevel: string | null | undefined,
  isTenantAdmin?: boolean
): SettingRole | undefined {
  if (accessLevel === "VIEWER" || accessLevel === "RUNNER") return "VIEWER";
  if (isTenantAdmin === undefined) return undefined;
  return isTenantAdmin ? "TENANT_ADMIN" : "EDITOR";
}

export interface ImpactLine {
  id: string;
  /** Message key under the "agentBehavior" namespace. */
  messageKey: string;
  params: Record<string, string | number>;
}

export interface ImpactInput {
  /** The agent's effective values, keyed by catalog key. */
  values: Record<string, EffectiveSetting>;
  /** Catalog entries by key (units and types decide how a value reads). */
  entries: Record<string, SettingCatalogEntry>;
  /** A value the way people should read it (units, separators); supplied by useSettingsText().formatValue. */
  format: (entry: SettingCatalogEntry, value: unknown) => string;
  /** The agent's autonomy tier, when known. */
  autonomyTier?: string | null;
}

/**
 * Plain-language consequences of the values in force ("this agent may call up to 8 tools per turn"). Only lines
 * whose setting is in the catalog and has a value are returned, so an older core simply shows fewer sentences.
 */
export function impactLines(input: ImpactInput): ImpactLine[] {
  const { values, entries, format } = input;
  const lines: ImpactLine[] = [];
  const entry = (key: string) => entries[key];
  const raw = (key: string) => (values[key] ? values[key].value : undefined);
  const num = (key: string): number | null => (entry(key) ? numericValue(entry(key), raw(key)) : null);
  const text = (key: string) => (entry(key) ? format(entry(key), raw(key)) : "");

  const steps = num(BEHAVIOR_KEYS.maxSteps);
  if (steps != null) {
    lines.push({ id: "steps", messageKey: "impact.steps", params: { count: steps, steps: text(BEHAVIOR_KEYS.maxSteps) } });
    // The autonomy tier scales an inherited base figure at run time; a value this agent sets itself is used as is.
    const scaled = values[BEHAVIOR_KEYS.maxSteps]?.source !== "agent";
    if (scaled && (input.autonomyTier === "SUPERVISED" || input.autonomyTier === "FULLY_AUTONOMOUS")) {
      lines.push({
        id: "stepsTier",
        messageKey: input.autonomyTier === "SUPERVISED" ? "impact.stepsTierSupervised" : "impact.stepsTierFull",
        params: {},
      });
    }
  }

  const turn = num(BEHAVIOR_KEYS.turnTimeout);
  if (turn != null) {
    lines.push(
      turn > 0
        ? { id: "turnTimeout", messageKey: "impact.turnTimeout", params: { duration: text(BEHAVIOR_KEYS.turnTimeout) } }
        : { id: "turnTimeout", messageKey: "impact.turnTimeoutNone", params: {} }
    );
  }

  const tool = num(BEHAVIOR_KEYS.toolTimeout);
  if (tool != null) {
    lines.push(
      tool > 0
        ? { id: "toolTimeout", messageKey: "impact.toolTimeout", params: { duration: text(BEHAVIOR_KEYS.toolTimeout) } }
        : { id: "toolTimeout", messageKey: "impact.toolTimeoutNone", params: {} }
    );
  }

  const cap = num(BEHAVIOR_KEYS.outputCap);
  if (cap != null) {
    lines.push({ id: "outputCap", messageKey: "impact.outputCap", params: { chars: text(BEHAVIOR_KEYS.outputCap) } });
  }

  const passes = num(BEHAVIOR_KEYS.continuation);
  if (passes != null) {
    lines.push(
      passes > 0
        ? { id: "continuation", messageKey: "impact.continuation", params: { count: passes } }
        : { id: "continuation", messageKey: "impact.continuationNone", params: {} }
    );
  }

  const topK = num(BEHAVIOR_KEYS.retrievalTopK);
  const minScore = num(BEHAVIOR_KEYS.retrievalMinScore);
  if (topK != null) {
    lines.push(
      minScore != null && minScore > 0
        ? {
            id: "retrieval",
            messageKey: "impact.retrievalScored",
            params: { count: topK, score: text(BEHAVIOR_KEYS.retrievalMinScore) },
          }
        : { id: "retrieval", messageKey: "impact.retrieval", params: { count: topK } }
    );
  }

  const approval = raw(BEHAVIOR_KEYS.approvalContinuation);
  if (entry(BEHAVIOR_KEYS.approvalContinuation) && approval !== undefined) {
    lines.push({
      id: "approvalContinuation",
      messageKey:
        approval === true || approval === "true" ? "impact.approvalContinuationOn" : "impact.approvalContinuationOff",
      params: {},
    });
  }

  const failMode = raw(BEHAVIOR_KEYS.guardFailMode);
  if (entry(BEHAVIOR_KEYS.guardFailMode) && (failMode === "open" || failMode === "closed")) {
    lines.push({
      id: "guardFailMode",
      messageKey: failMode === "closed" ? "impact.guardClosed" : "impact.guardOpen",
      params: {},
    });
  }

  const branches = num(BEHAVIOR_KEYS.maxBranches);
  if (branches != null) {
    lines.push({ id: "branches", messageKey: "impact.branches", params: { count: branches } });
  }

  return lines;
}
