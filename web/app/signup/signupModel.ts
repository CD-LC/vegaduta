// Shared constants and the POST /api/signup response shape for the signup flow.

// Universal tier — one workspace shape for everyone. These are the exact caps
// TenantLimitsServiceImpl (2 users, 5 agents) and AgentMemoryService (50
// memories/agent) enforce server-side; this page's one source of truth.
export const VESSEL = { seats: 2, agents: 5, memoryPerAgent: 50 };

// Shape of the POST /api/signup response (api.signup() itself returns
// Promise<any> -- toJson() is untyped -- so this is the local contract this
// page relies on). gatewaySubscriptionId/gatewayPublicKey/checkoutUrl are
// only present on the (currently unreachable) paid-tier path.
export interface SignupResult {
  organizationName: string;
  gatewaySubscriptionId?: string;
  gatewayPublicKey?: string;
  checkoutUrl?: string;
}
