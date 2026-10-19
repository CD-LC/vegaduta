// Platform-admin API surface. The fetch core (apiFetch/toJson/jsonBody, caching,
// network-failure handling) lives in apiCore.ts; the endpoint wrappers live in
// apiEndpointsPlatform.ts and apiEndpointsGovernance.ts. Everything stays
// importable from "./api" exactly as before.
import { platformEndpointsA } from "./apiEndpointsPlatform";
import { platformEndpointsB } from "./apiEndpointsGovernance";

export * from "./apiCore";

export const api = {
  ...platformEndpointsA,
  ...platformEndpointsB,
};
