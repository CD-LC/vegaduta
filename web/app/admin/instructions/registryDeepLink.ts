import type { PromptScopePath } from "../../lib/api/promptRegistry";

// Pure helpers for the prompt registry's deep link: /admin/instructions?agent=<agentId> opens the registry at agent
// scope for that agent. The query parameter is also kept in step with the scope picker so the address can be shared
// or reloaded. No React, no network.

export const REGISTRY_AGENT_PARAM = "agent";

/**
 * The agent id from ?agent=, or null when absent or not shaped like an id. The shape check only keeps junk out of
 * the picker lookup; whether the agent exists and is visible is decided against the agent list the person can see.
 */
export function agentFromParam(raw: string | null | undefined): string | null {
  const id = raw?.trim() ?? "";
  return /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(id) ? id : null;
}

export type AgentResolution = { kind: "agent"; agentId: string } | { kind: "tenant"; missing: boolean };

/** Decides the starting scope once the visible agents are known. A requested but unknown agent falls back to the workspace. */
export function resolveRequestedAgent(requested: string | null, visibleAgentIds: readonly string[]): AgentResolution {
  if (!requested) return { kind: "tenant", missing: false };
  return visibleAgentIds.includes(requested) ? { kind: "agent", agentId: requested } : { kind: "tenant", missing: true };
}

/**
 * The query string (with its leading "?", or "" when empty) for the given scope, other parameters untouched:
 * agent scope with an agent sets ?agent=, any other scope removes it.
 */
export function registrySearch(currentSearch: string, scope: PromptScopePath, agentId: string | null): string {
  const params = new URLSearchParams(currentSearch);
  if (scope === "agent" && agentId) params.set(REGISTRY_AGENT_PARAM, agentId);
  else params.delete(REGISTRY_AGENT_PARAM);
  const qs = params.toString();
  return qs ? `?${qs}` : "";
}
