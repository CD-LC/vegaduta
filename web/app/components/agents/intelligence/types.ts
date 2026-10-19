import type { RoutingPreview } from "../../../lib/edge/routingState";

export interface IntelligenceSourceControlProps {
  /** FREE tenants never activate a BYOK provider — "Your own provider"
   * renders locked with an upgrade hint, same gate as the model picker's
   * own FREE-tier treatment elsewhere in this file. */
  isFreePlan: boolean;
  /** isFreePlan || tenantUsePlatformLlm — whether this tenant's chat is
   * CURRENTLY served by the platform-hosted pool (AgentWorkbench's existing
   * signal, computed once from GET /api/tenant/settings). */
  onPlatformLlm: boolean;
  /** How many of the tenant's BYOK provider credentials are ACTIVE right
   * now — "Your own provider" is disabled (not broken-looking, just
   * unavailable-with-a-reason) until there is at least one. */
  activeProviderCount: number;
  switchToByokBusy: boolean;
  switchToPoolBusy: boolean;
  /** Existing handler (AgentWorkbench) — flips the tenant-wide toggle OFF
   * platform pool via the already-shipped PUT usePlatformLlm=false call. */
  onSwitchToByok: () => void;
  /** New symmetric handler (AgentWorkbench) — same call, usePlatformLlm=true. */
  onSwitchToPool: () => void;
  /** A3: the hosted model currently serving this agent (the pool's model on
   * platform LLM, the agent's own on BYOK) — shown in-context whenever a
   * hosted source is selected, so "what model am I on" never needs a hunt. */
  hostedModelLabel?: string | null;
  /** A3: opens the existing provider/model editor (AgentWorkbench's
   * openModelEditor). Only passed when changing is actually allowed
   * (saved agent, paid plan, not edit-locked). */
  onChangeHostedModel?: (() => void) | null;
  /** Bug B fix: surfaces the panel's own computed 3-way selection
   * ("platform" | "on-device" | "own-provider") to the parent, so
   * AgentWorkbench can grey its own hosted-model header when on-device is
   * selected (that header previously had no signal at all — `selected` was
   * computed entirely inside this component and never left it). */
  onSourceSelected?: (source: "platform" | "on-device" | "own-provider") => void;
  /** Wave 2 fix (audit finding P0): the saved agent's id, so this panel can
   * tell whether a server-declared per-agent routing policy could even
   * exist. Null/undefined (a draft agent with no id yet) simply means no
   * divergence note below can ever apply — this component does NOT fetch
   * the policy itself (no second network round trip); it reuses
   * AgentWorkbench's own already-computed `routingPreview` below. */
  agentId?: string | null;
  /** Wave 2 fix (audit finding P0): AgentWorkbench's own
   * previewRouting(tenant, agentId) result (routingState.ts), passed down
   * read-only. previewRouting() consults the agent's server policy BEFORE
   * device/tenant preference (see its own doc comment) — when its answer
   * diverges from the raw device-preference selection below (e.g. a
   * HOSTED_ONLY policy overriding "On-device" picked here, or an EDGE_ONLY
   * policy refusing a turn "Platform pool" would otherwise serve), this
   * panel renders an honest note so the 3-way selector never confidently
   * implies something the next turn will not actually do. */
  routingPreview?: RoutingPreview | null;
}
