import { apiFetch, toJson, jsonBody } from "../api";

// Tool circuit breaker (PlatformCircuitBreakerController).
export const rateControlApi = {
  getToolCircuitBreakers: () => apiFetch(`/api/platform/rate-control/circuit-breakers`).then(toJson),
  resetToolCircuitBreaker: (tenantId: string, tool: string) =>
    apiFetch(`/api/platform/rate-control/circuit-breakers/reset`, {
      method: "POST",
      ...jsonBody({ tenantId, tool }),
    }).then(toJson),
};
