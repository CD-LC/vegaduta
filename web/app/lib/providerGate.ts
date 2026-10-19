// "Does this tenant have any way to run an agent?" - the decision behind
// AgentWorkbench's "No active LLM provider yet" banner and its create-gate
// (2026-09-19). Pure, so the rule can be tested without mounting a
// 2,900-line component.
//
// The bug this exists to prevent: the client used to decide "on the hosted
// pool" as FREE || the usePlatformLlm toggle. Core's rule
// (TenantLimits#usesPlatformPool) has a third arm - a live complimentary
// grant - and every new signup holds one. So a brand-new consumer, whose chat
// works, was told at login that they had no provider and was refused agent
// creation. `platformPoolAvailable` is the server's own answer
// (GET /api/providers/availability) and is what closes that gap.

export interface ProviderGateInput {
  /** Tenant plan from /api/tenant/settings; null until it has loaded. */
  tenantPlan: string | null;
  /** Settings fetch AND its retry failed - fail closed, treat as FREE. */
  tenantSettingsError: boolean;
  /** The tenant's own raw "Use platform LLM" opt-in. */
  usePlatformLlmToggle: boolean;
  /** Server says it routes this tenant to a non-empty hosted pool; null = not answered yet. */
  platformPoolAvailable: boolean | null;
  /** BYOK providers with status ACTIVE. */
  activeProviderCount: number;
  /** routingState.ts preview tier: 0/1 = the next turn runs on-device. */
  routingTier: 0 | 1 | 3 | null | undefined;
}

export interface ProviderGate {
  isFreePlan: boolean;
  /** Model/provider pickers are ignored by the server for this tenant. */
  onPlatformLlm: boolean;
  /** Every input has an answer; until then nothing may claim "no provider". */
  settled: boolean;
  /** True only when every route is genuinely closed. */
  needsProvider: boolean;
}

export function resolveProviderGate(i: ProviderGateInput): ProviderGate {
  const isFreePlan = i.tenantSettingsError || i.tenantPlan === "FREE";
  const onPlatformLlm = isFreePlan || i.usePlatformLlmToggle || i.platformPoolAvailable === true;
  const settled = (i.tenantPlan !== null || i.tenantSettingsError) && i.platformPoolAvailable !== null;
  const servedOnDevice = i.routingTier === 0 || i.routingTier === 1;
  return {
    isFreePlan,
    onPlatformLlm,
    settled,
    needsProvider: settled && !onPlatformLlm && i.activeProviderCount === 0 && !servedOnDevice,
  };
}
