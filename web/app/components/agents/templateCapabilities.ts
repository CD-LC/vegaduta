// Capabilities a console template asks for (D3: the "data-analyst" template
// needs Code execution).
//
// A console template only pre-fills the create form, and POST /api/agents has
// no capability field - per-agent capabilities are switched on afterwards
// through PATCH /api/agents/{id}/capabilities/{key}, the same call the
// workbench's Capabilities list makes (AgentWorkbench/AgentCapabilities.tsx).
// So a template that needs one declares its key (`capabilities` on the
// AGENT_TEMPLATES entry) and the workbench runs enableTemplateCapabilities()
// right after the agent is created.
//
// Honest by construction: availability is read from the server first. A
// capability that is NEEDS_SETUP is still switched on (the server allows it -
// the flag is inert until setup completes, see CapabilityAvailability) and its
// server-written hint is handed back so the person is told what is missing
// instead of meeting a silently dead tool. Nothing here throws: a failure to
// enable must never undo or mask a successful agent create.

import { AGENT_TEMPLATES } from "../agentTemplates";
import type { AgentCapabilityEntry } from "../../lib/api";

/** The two capability calls this needs - `api` satisfies it; tests pass a stub. */
export interface TemplateCapabilityApi {
  listAgentCapabilities: (agentId: string) => Promise<AgentCapabilityEntry[]>;
  setAgentCapability: (agentId: string, capabilityKey: string, enabled: boolean) => Promise<unknown>;
}

export interface TemplateCapabilityOutcome {
  /** Labels of the capabilities now on for the agent (including ones already on). */
  enabled: string[];
  /** Switched on, but the workspace still has setup to do before it works. */
  needsSetup: { label: string; hint: string | null }[];
  /**
   * Not offered for this agent, or UNAVAILABLE: left off, and there is nothing
   * the person can switch on by hand, so the note must not send them looking.
   */
  unavailable: string[];
  /** Offered, but the list or toggle call failed - a retry under Capabilities can work. */
  failed: string[];
}

const EMPTY: TemplateCapabilityOutcome = { enabled: [], needsSetup: [], unavailable: [], failed: [] };

// Shown when the server's list has no entry (so no server label) for a key a
// template declares - never print the raw key to a person.
const FALLBACK_LABELS: Record<string, string> = { "e2b-code-execution": "Code execution" };

function fallbackLabel(key: string): string {
  return FALLBACK_LABELS[key] || "This capability";
}

/** Capability keys the given console template declares; [] for none, an unknown key, or a starter template. */
export function templateCapabilityKeys(templateKey: string | null | undefined): string[] {
  if (!templateKey) return [];
  const template = AGENT_TEMPLATES.find((t) => t.key === templateKey) as { capabilities?: readonly string[] } | undefined;
  return template?.capabilities ? [...template.capabilities] : [];
}

/**
 * Switches the given capabilities on for a just-created agent. Never throws.
 * Makes no request at all when `keys` is empty, so every template without a
 * declared capability creates exactly as it did before.
 */
export async function enableTemplateCapabilities(
  capabilityApi: TemplateCapabilityApi,
  agentId: string,
  keys: readonly string[]
): Promise<TemplateCapabilityOutcome> {
  if (!agentId || keys.length === 0) return EMPTY;
  let offered: AgentCapabilityEntry[];
  try {
    const list = await capabilityApi.listAgentCapabilities(agentId);
    offered = Array.isArray(list) ? list : [];
  } catch {
    return { enabled: [], needsSetup: [], unavailable: [], failed: keys.map(fallbackLabel) };
  }
  const outcome: TemplateCapabilityOutcome = { enabled: [], needsSetup: [], unavailable: [], failed: [] };
  for (const key of keys) {
    const entry = offered.find((c) => c.key === key);
    // Not in the server's list = it does not apply to this agent (or this
    // core does not know the key). UNAVAILABLE = the server would accept the
    // flag but it can never work here; leave it off rather than pretend.
    if (!entry || entry.availability === "UNAVAILABLE") {
      outcome.unavailable.push(entry?.label || fallbackLabel(key));
      continue;
    }
    const label = entry.label || fallbackLabel(key);
    if (!entry.enabled) {
      try {
        await capabilityApi.setAgentCapability(agentId, key, true);
      } catch {
        outcome.failed.push(label);
        continue;
      }
    }
    outcome.enabled.push(label);
    if (entry.availability === "NEEDS_SETUP") {
      outcome.needsSetup.push({ label, hint: entry.availabilityHint || null });
    }
  }
  return outcome;
}

/**
 * One short sentence (or null when there is nothing to say) to append to the
 * "agent created" notice. `t` is the "agentTemplates" namespace translator.
 * Silent on plain success: the capability simply shows as on in the list.
 */
export function describeCapabilityOutcome(
  outcome: TemplateCapabilityOutcome,
  t: (key: string, params?: Record<string, string | number>) => string
): string | null {
  const parts: string[] = [];
  for (const item of outcome.needsSetup) {
    parts.push(
      item.hint
        ? t("capabilityNeedsSetupNote", { capability: item.label, hint: item.hint })
        : t("capabilityNeedsSetupNoHint", { capability: item.label })
    );
  }
  for (const label of outcome.unavailable) {
    parts.push(t("capabilityUnavailableNote", { capability: label }));
  }
  for (const label of outcome.failed) {
    parts.push(t("capabilityEnableFailedNote", { capability: label }));
  }
  return parts.length > 0 ? parts.join(" ") : null;
}

/** True when the template's job is analysing attached files in the code sandbox (it declares Code execution). */
export function templateWantsFileAnalysis(templateKey: string | null | undefined): boolean {
  return templateCapabilityKeys(templateKey).includes("e2b-code-execution");
}

/**
 * The honest post-create line for a file-analysis template whose agent cannot
 * take data files. Core's data-analysis option is off by default, and then the
 * agent response has modalities.sandboxData=false and NO reason - so nothing
 * else on the page would say why a spreadsheet cannot be attached.
 * `agent` is the agent as re-read AFTER its capabilities were switched on.
 * Null when file analysis is available, when the server sent its own reason
 * (SandboxDataHint shows that under the composer), or when the agent could
 * not be re-read (no claim either way).
 */
export function fileAnalysisUnavailableNote(
  agent: { modalities?: { sandboxData?: boolean; sandboxDataUnavailableReason?: string | null } | null } | null | undefined,
  t: (key: string, params?: Record<string, string | number>) => string
): string | null {
  if (!agent) return null;
  const m = agent.modalities;
  if (m?.sandboxData === true) return null;
  const reason = typeof m?.sandboxDataUnavailableReason === "string" ? m.sandboxDataUnavailableReason.trim() : "";
  if (reason) return null;
  return t("fileAnalysisUnavailableNote");
}

// ---------------------------------------------------------------------------
// Quiet variant (research-assistant -> deep-research). For capabilities that
// are a bonus rather than the template's whole point: switched on only when
// the server lists them AVAILABLE, and nothing is shown otherwise. Callers
// pass the QUIET client methods (researchApi.listAgentCapabilitiesQuiet /
// setAgentCapabilityQuiet) so a refusal raises no global error toast.
// ---------------------------------------------------------------------------

/** Template key -> capability keys (server-defined, see GET /api/agents/{id}/capabilities). */
export const QUIET_TEMPLATE_CAPABILITIES: Readonly<Record<string, readonly string[]>> = {
  "research-assistant": ["deep-research"],
  // The literature review reads papers the person uploads; deep research only
  // widens it, so it stays a quiet bonus and the template works without it.
  "literature-review-assistant": ["deep-research"],
};

export interface TemplateCapabilityDeps {
  list: (agentId: string) => Promise<AgentCapabilityEntry[]>;
  set: (agentId: string, capabilityKey: string, enabled: boolean) => Promise<unknown>;
}

/**
 * Enable the capabilities `templateKey` asks for on a freshly created agent.
 * Never throws; resolves to the keys that were actually switched on.
 */
export async function enableQuietTemplateCapabilities(
  templateKey: string | null | undefined,
  agentId: string | null | undefined,
  deps: TemplateCapabilityDeps
): Promise<string[]> {
  const wanted = templateKey ? QUIET_TEMPLATE_CAPABILITIES[templateKey] : undefined;
  if (!wanted?.length || !agentId) return [];
  const enabled: string[] = [];
  try {
    const listed = await deps.list(agentId);
    for (const key of wanted) {
      const entry = Array.isArray(listed) ? listed.find((c) => c?.key === key) : undefined;
      if (!entry || entry.availability !== "AVAILABLE" || entry.enabled) continue;
      try {
        await deps.set(agentId, key, true);
        enabled.push(key);
      } catch {
        // Leave it off - the switch is still there in the agent's Capabilities section.
      }
    }
  } catch {
    // The capability list could not be read: create the agent without it.
  }
  return enabled;
}
