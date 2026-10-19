import { apiFetch, toJson, jsonBody } from "./core";
import type {
  CapabilityCredential,
  CapabilityDescriptor,
  DecisionPlaygroundResponse,
  DecisionPolicy,
  DecisionQuestion,
  DecisionStatus,
} from "./types";

// The generic capability surface - CapabilityController in core/providers
// plus DecisionCapabilityController's decision-specific slice in core/agents.
// Catalog + per-provider tenant BYOK credentials + capability policy are
// fully data-driven: a newly onboarded provider shows up in listCapabilities
// and needs no frontend change. The DECISION capability additionally exposes
// key-resolution status and a bounded fail-visible playground.

const cap = (capability: string) => encodeURIComponent(capability);
const prov = (provider: string) => encodeURIComponent(provider);

export const capabilitiesApi = {
  // The registry catalog: every capability and the providers that serve it,
  // each with the tenant's stored credential status (null = none).
  listCapabilities: (): Promise<CapabilityDescriptor[]> =>
    apiFetch(`/api/capabilities`).then(toJson<CapabilityDescriptor[]>),

  // Whose key the tenant's decision calls resolve to right now
  // (TENANT/PLATFORM/ENV) plus the active provider's stored credential.
  getDecisionStatus: (): Promise<DecisionStatus> =>
    apiFetch(`/api/capabilities/decision/status`).then(toJson<DecisionStatus>),

  // 201 on success; the key is validated by the provider's server-side
  // validator before it is persisted, encrypted at rest, never echoed back.
  activateCapabilityCredential: (
    capability: string,
    provider: string,
    apiKey: string
  ): Promise<CapabilityCredential> =>
    apiFetch(`/api/capabilities/${cap(capability)}/credential`, {
      method: "POST",
      ...jsonBody({ provider, apiKey }),
    }).then(toJson<CapabilityCredential>),

  // Deactivate-not-delete; the provider must be named when a capability is
  // served by more than one (a 400 otherwise, same rule as POST).
  deactivateCapabilityCredential: async (
    capability: string,
    provider: string
  ): Promise<void> => {
    await apiFetch(
      `/api/capabilities/${cap(capability)}/credential?provider=${prov(provider)}`,
      { method: "DELETE" }
    ).then(toJson<void>);
  },

  // The tenant's policy for the capability: raw overrides - an absent key
  // means "inherit the platform default".
  getCapabilityPolicy: (capability: string): Promise<DecisionPolicy> =>
    apiFetch(`/api/capabilities/${cap(capability)}/policy`).then(toJson<DecisionPolicy>),

  // PUT replaces the whole overrides map - omit a key to reset it to inherit.
  updateCapabilityPolicy: (
    capability: string,
    overrides: Record<string, unknown>
  ): Promise<DecisionPolicy> =>
    apiFetch(`/api/capabilities/${cap(capability)}/policy`, {
      method: "PUT",
      ...jsonBody(overrides),
    }).then(toJson<DecisionPolicy>),

  // Fail-VISIBLE by design (unlike the internal decision paths): upstream
  // errors surface as real HTTP errors so an experimenting tenant sees
  // whether their key actually worked.
  runDecisionPlayground: (
    state: string,
    questions: Record<string, DecisionQuestion>
  ): Promise<DecisionPlaygroundResponse> =>
    apiFetch(`/api/capabilities/decision/playground`, {
      method: "POST",
      ...jsonBody({ state, questions }),
    }).then(toJson<DecisionPlaygroundResponse>),
};
