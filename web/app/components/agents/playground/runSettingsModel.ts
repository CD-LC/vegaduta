import type { AgentRuntimeLimitsView, RuntimeLimitName } from "../../../lib/api/agentRuntimeLimits";

/**
 * Pure helpers behind the playground's "Run settings" drawer: which links it offers, how runtime limits are laid
 * out, which sampling settings a provider will silently drop, and how the tool switches map onto the agent's
 * tool allow-list. No React, no network.
 */

/**
 * The `focus` values the playground links with. One scheme: the settings-catalog key of the setting
 * ("<area>.<name>"). A setting that is not in the catalog yet (the reply-length cap and the spending cap per run
 * live on the agent record) uses the key it will have there.
 */
export const FOCUS_MAX_TOOL_STEPS = "agent.limits.maxToolSteps";
export const FOCUS_MAX_TOKENS = "agent.model.maxTokens";
export const FOCUS_RUN_BUDGET = "agent.budget.perRun";

/**
 * Where an agent's behaviour settings (reasoning depth, sampling, run limits) are edited. Kept in one place so a
 * change of route is a one-line edit. `focus` names the setting to scroll to; a page that does not know it just
 * opens at the top.
 */
export function agentBehaviorHref(agentId: string, focus?: string): string {
  const qs = new URLSearchParams({ agentId, view: "workbench", section: "behavior" });
  if (focus) qs.set("focus", focus);
  return `/agents?${qs.toString()}`;
}

/** The prompt registry page (admin), for the "see and change the wording" link next to a prompt preview. */
export const PROMPTS_ADMIN_HREF = "/admin/instructions";

// ---------------------------------------------------------------- runtime limits

export type LimitUnit = "count" | "seconds" | "chars" | "score";

export interface LimitRow {
  name: RuntimeLimitName;
  value: number;
  unit: LimitUnit;
  /** Where the value comes from: the agent's own override, the workspace, or the platform. */
  layer: "AGENT" | "TENANT" | "PLATFORM" | "REQUEST";
  /** True when the agent itself overrides the inherited value. */
  overridden: boolean;
  min?: number;
  max?: number;
}

/** Display order and unit of each limit. Bounds and values come from the server, not from here. */
export const LIMIT_ORDER: ReadonlyArray<{ name: RuntimeLimitName; unit: LimitUnit }> = [
  { name: "maxToolSteps", unit: "count" },
  { name: "turnTimeoutSeconds", unit: "seconds" },
  { name: "toolTimeoutSeconds", unit: "seconds" },
  { name: "toolOutputMaxChars", unit: "chars" },
  { name: "retrievalTopK", unit: "count" },
  { name: "retrievalMinScore", unit: "score" },
  { name: "continuationPasses", unit: "count" },
];

function layerOf(raw: unknown): LimitRow["layer"] {
  const s = typeof raw === "string" ? raw.toUpperCase() : "";
  return s === "AGENT" || s === "TENANT" || s === "REQUEST" ? s : "PLATFORM";
}

/** The effective limits of a runtime-limits view as ordered rows; skips any value the server did not send. */
export function limitRows(view: AgentRuntimeLimitsView | null | undefined): LimitRow[] {
  if (!view?.effective) return [];
  const rows: LimitRow[] = [];
  for (const { name, unit } of LIMIT_ORDER) {
    const value = view.effective[name];
    if (typeof value !== "number" || !Number.isFinite(value)) continue;
    const layer = layerOf(view.effective.layers?.[name]);
    const bounds = view.bounds?.[name];
    rows.push({
      name,
      value,
      unit,
      layer,
      overridden: layer === "AGENT" || typeof view.overrides?.[name] === "number",
      min: bounds?.min,
      max: bounds?.max,
    });
  }
  return rows;
}

// ---------------------------------------------------------------- dropped sampling parameters

export type SamplingParam =
  | "temperature"
  | "topP"
  | "maxTokens"
  | "topK"
  | "frequencyPenalty"
  | "presencePenalty"
  | "seed"
  | "stopSequences";

const SAMPLING_ALIASES: Record<string, SamplingParam> = {
  temperature: "temperature",
  topp: "topP",
  top_p: "topP",
  "top-p": "topP",
  maxtokens: "maxTokens",
  max_tokens: "maxTokens",
  max_completion_tokens: "maxTokens",
  topk: "topK",
  top_k: "topK",
  frequencypenalty: "frequencyPenalty",
  frequency_penalty: "frequencyPenalty",
  presencepenalty: "presencePenalty",
  presence_penalty: "presencePenalty",
  seed: "seed",
  stopsequences: "stopSequences",
  stop_sequences: "stopSequences",
  stop: "stopSequences",
};

/** The slice of a model-catalog row this file reads: which request parameters the provider ignores for it. */
export interface ModelParamSupport {
  unsupportedParams?: readonly string[] | null;
  ignoredParams?: readonly string[] | null;
  droppedParams?: readonly string[] | null;
}

/** The agent's sampling values, narrowed to what the drawer shows. */
export interface AgentSamplingValues {
  temperature?: number | null;
  topP?: number | null;
  maxTokens?: number | null;
  topK?: number | null;
  frequencyPenalty?: number | null;
  presencePenalty?: number | null;
  seed?: number | null;
  stopSequences?: readonly string[] | null;
}

export function normalizeSamplingParam(name: string): SamplingParam | null {
  return SAMPLING_ALIASES[name.trim().toLowerCase()] ?? null;
}

/**
 * The sampling parameters this agent has set that its model's provider will drop. Reads whichever of
 * unsupportedParams / ignoredParams / droppedParams the catalog row carries (each an array of parameter names in
 * any common spelling). A row without any of them says nothing: no warning is invented.
 */
export function droppedSamplingParams(
  agent: AgentSamplingValues | null | undefined,
  model: ModelParamSupport | null | undefined
): SamplingParam[] {
  if (!agent || !model) return [];
  const names = [...(model.unsupportedParams ?? []), ...(model.ignoredParams ?? []), ...(model.droppedParams ?? [])];
  const dropped = new Set<SamplingParam>();
  for (const n of names) {
    const p = typeof n === "string" ? normalizeSamplingParam(n) : null;
    if (p) dropped.add(p);
  }
  return [...dropped].filter((p) => {
    const v = agent[p];
    return Array.isArray(v) ? v.length > 0 : v != null;
  });
}

// ---------------------------------------------------------------- tool switches

export interface ToolRow {
  name: string;
  description: string | null;
  enabled: boolean;
}

/**
 * Core's meaning of the agent's tool allow-list: an EMPTY list is "no restriction, every tool is allowed", not
 * "no tools". The switches must show that state truthfully, so every consumer goes through this.
 */
export function isUnrestricted(allowed: readonly string[] | null | undefined): boolean {
  return !allowed || allowed.length === 0;
}

/**
 * One row per tool the agent could be granted. A tool is on when it is in the agent's allow-list, or when the
 * list is empty (unrestricted: every tool is usable, so every switch reads on).
 */
export function toolRows(
  available: ReadonlyArray<{ name: string; description?: string | null }> | null | undefined,
  allowed: readonly string[] | null | undefined
): ToolRow[] {
  const unrestricted = isUnrestricted(allowed);
  const on = new Set(allowed ?? []);
  const seen = new Set<string>();
  const rows: ToolRow[] = [];
  for (const tool of available ?? []) {
    if (!tool?.name || seen.has(tool.name)) continue;
    seen.add(tool.name);
    rows.push({ name: tool.name, description: tool.description ?? null, enabled: unrestricted || on.has(tool.name) });
  }
  return rows;
}

/**
 * The tools the saved agent itself has on, in row order. A run can only be narrowed to these: a tool the agent does
 * not have cannot be switched on for one run.
 */
export function agentToolNames(rows: readonly ToolRow[]): string[] {
  return rows.filter((r) => r.enabled).map((r) => r.name);
}

/** True when both lists hold the same names, whatever their order. */
export function sameNames(a: readonly string[], b: readonly string[]): boolean {
  if (a.length !== b.length) return false;
  const set = new Set(a);
  return set.size === new Set(b).size && b.every((n) => set.has(n));
}

/**
 * The tools a run uses: its own selection when it has one, otherwise everything the agent has. A selection keeps
 * only names the agent has, so a tool removed from the agent since the selection was made drops out of it.
 */
export function effectiveRunTools(agentNames: readonly string[], runTools: readonly string[] | null | undefined): string[] {
  if (!runTools) return [...agentNames];
  const have = new Set(agentNames);
  return runTools.filter((n) => have.has(n));
}

/**
 * The run's tool selection after the switch of `name` is flipped. Narrowing only: a tool the agent does not have
 * is never added (the selection is returned as it was). Turning a tool off leaves the others in the agent's own
 * order; turning every tool off is allowed and means "no tools for this run" (an empty list, which is different
 * from no selection). Returns `null` when the result is the agent's own set again, i.e. nothing is overridden.
 */
export function flipRunTool(
  agentNames: readonly string[],
  runTools: readonly string[] | null | undefined,
  name: string
): string[] | null {
  if (!agentNames.includes(name)) return runTools ? [...runTools] : null;
  const current = effectiveRunTools(agentNames, runTools);
  const next = current.includes(name) ? current.filter((n) => n !== name) : [...current, name];
  const ordered = agentNames.filter((n) => next.includes(n));
  return sameNames(ordered, agentNames) ? null : ordered;
}

/** Where the agent's saved tool access is edited (the workbench Tools section), for the link next to the run-only switches. */
export function agentToolsHref(agentId: string): string {
  const qs = new URLSearchParams({ agentId, view: "workbench" });
  return `/agents?${qs.toString()}`;
}
