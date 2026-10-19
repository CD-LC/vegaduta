// The one-click "create the right agent" step of /start.
//
// Deliberately THIN: the template data (components/agentTemplates.ts) and the
// capability helpers (components/agents/templateCapabilities.ts) already exist
// and are what the workbench uses; this only assembles the create payload the
// workbench would send for an untouched draft and then runs the same
// post-create steps in the same order:
//
//   1. POST /api/agents                          (api.createAgent)
//   2. enableTemplateCapabilities(...)           - the template's declared
//      capabilities (data-analyst -> Code execution). A capability that is
//      NEEDS_SETUP is still switched on and its hint is returned, so the person
//      is told what is missing instead of meeting a silently dead tool.
//   3. enableQuietTemplateCapabilities(...)      - bonuses (research ->
//      deep research), only when the server lists them AVAILABLE.
//
// Steps 2 and 3 never throw: a failure to switch a capability on must not undo
// or mask a successful create (same rule as the workbench). Only step 1 can
// throw, and its message is already a plain-language sentence from the API
// layer (agent limit, plan gate, validation).

import { AGENT_TEMPLATES } from "../components/agentTemplates";
import {
  enableQuietTemplateCapabilities,
  enableTemplateCapabilities,
  templateCapabilityKeys,
  type TemplateCapabilityApi,
  type TemplateCapabilityDeps,
  type TemplateCapabilityOutcome,
} from "../components/agents/templateCapabilities";

/** The slice of `api` this needs - `api` satisfies it; tests pass a stub. */
export interface StartAgentApi extends TemplateCapabilityApi {
  createAgent: (payload: Record<string, unknown>) => Promise<{ id: string; name?: string }>;
  listAgentCapabilitiesQuiet: TemplateCapabilityDeps["list"];
  setAgentCapabilityQuiet: TemplateCapabilityDeps["set"];
}

export interface CreatedStartAgent {
  id: string;
  name: string;
  capabilityOutcome: TemplateCapabilityOutcome;
}

/** Same fallback the workbench uses (`providers[0]?.providerName || "ANTHROPIC"`); the server ignores it for a tenant on the hosted pool. */
export const DEFAULT_PROVIDER_NAME = "ANTHROPIC";

/** First ACTIVE BYOK provider, else the first listed, else the default. */
export function pickProviderName(providers: unknown): string {
  if (!Array.isArray(providers)) return DEFAULT_PROVIDER_NAME;
  const rows = providers.filter((p): p is { providerName?: unknown; status?: unknown } => Boolean(p) && typeof p === "object");
  const active = rows.find((p) => p.status === "ACTIVE" && typeof p.providerName === "string");
  const any = rows.find((p) => typeof p.providerName === "string");
  const name = (active ?? any)?.providerName;
  return typeof name === "string" && name ? name : DEFAULT_PROVIDER_NAME;
}

type TemplateRecord = { key: string; systemPrompt: string };

export function findConsoleTemplate(key: string): TemplateRecord | null {
  return (AGENT_TEMPLATES as unknown as readonly TemplateRecord[]).find((t) => t.key === key) ?? null;
}

/**
 * The create body for an untouched draft of this template - field for field
 * what AgentWorkbench.handleCreate sends with its defaults (SIMPLE experience
 * mode, which turns the knowledge base on; manual tuning at 1024 / 0.7 / 1.0).
 * No `model`: the server picks a real id from the provider's catalogue.
 */
export function buildStartAgentPayload(
  template: TemplateRecord,
  opts: { name: string; providerName: string }
): Record<string, unknown> {
  return {
    name: opts.name.trim(),
    systemPrompt: template.systemPrompt,
    providerName: opts.providerName,
    maxTokens: 1024,
    temperature: 0.7,
    topP: 1.0,
    knowledgeBaseEnabled: true,
    experienceMode: "SIMPLE",
    tuningMode: "MANUAL",
  };
}

/**
 * Creates the agent for `templateKey` and runs the template's capability
 * auto-enable. Throws only if the create itself fails (or the key is not a
 * gallery template - a bug the goals test guards against).
 */
export async function createStartAgent(
  templateKey: string,
  opts: { name: string; providerName: string },
  api: StartAgentApi
): Promise<CreatedStartAgent> {
  const template = findConsoleTemplate(templateKey);
  if (!template) throw new Error(`Unknown agent template: ${templateKey}`);
  if (!opts.name.trim()) throw new Error("An agent name is required.");

  const created = await api.createAgent(buildStartAgentPayload(template, opts));
  if (!created || typeof created.id !== "string" || !created.id) {
    throw new Error("The agent was created but the server did not return its id.");
  }

  const capabilityOutcome = await enableTemplateCapabilities(api, created.id, templateCapabilityKeys(templateKey));
  await enableQuietTemplateCapabilities(templateKey, created.id, {
    list: api.listAgentCapabilitiesQuiet,
    set: api.setAgentCapabilityQuiet,
  });

  return { id: created.id, name: created.name || opts.name.trim(), capabilityOutcome };
}
