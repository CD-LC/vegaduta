import { apiFetch, toJsonQuietOn } from "./core";

// GET /api/agents/{id}/runtime-limits (AgentRuntimeLimitsController, RuntimeLimitsView): what one agent's turns
// run under (steps, timeouts, output and retrieval limits), the layer each value comes from, and the range each
// may be set to. Read-only here; the editor lives on the agent's Behavior tab.

export type RuntimeLimitName =
  | "maxToolSteps"
  | "turnTimeoutSeconds"
  | "toolTimeoutSeconds"
  | "toolOutputMaxChars"
  | "retrievalTopK"
  | "retrievalMinScore"
  | "continuationPasses";

/** Which layer decided a value. REQUEST is a per-call override; the others are the stored layers. */
export type RuntimeLayerName = "PLATFORM" | "TENANT" | "AGENT" | "REQUEST";

export interface AgentRuntimeLimitsView {
  /** The agent's own overrides; only the fields set at agent level are present. */
  overrides: Partial<Record<RuntimeLimitName, number>>;
  effective: Record<RuntimeLimitName, number> & { layers: Partial<Record<RuntimeLimitName, RuntimeLayerName | string>> };
  bounds: Partial<Record<RuntimeLimitName, { min: number; max: number }>>;
}

export const agentRuntimeLimitsApi = {
  // Quiet on the statuses a read-only side panel should explain itself (403 no access, 404 older core): the
  // panel shows its own inline message, a global toast on top would say the same thing twice.
  getAgentRuntimeLimits: (agentId: string) =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/runtime-limits`, { skipGetCache: true }).then(
      toJsonQuietOn<AgentRuntimeLimitsView>([403, 404, 500, 502, 503, 504])
    ),
};
