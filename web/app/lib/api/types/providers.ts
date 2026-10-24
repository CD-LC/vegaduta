// GET /api/providers/modalities - the server-generated STT/TTS/video option
// catalog (core/providers ModalityCatalogService). Replaced the workbench's
// hardcoded VOICE_PROVIDERS/TTS_PROVIDERS/VIDEO_PROVIDERS arrays; each row's
// availability is resolved server-side for this tenant.
export interface ModalityProviderOption {
  /** Enum name (e.g. "SIDECAR_WHISPER") - the value round-tripped in agent PATCH bodies. */
  provider: string;
  providerId: string;
  label: string;
  description: string;
  defaultModel: string;
  /** A provider bean is registered on this deployment (false = "coming soon" row). */
  implemented: boolean;
  /** Usable right now for this tenant (credential active, sidecar healthy, ...). */
  available: boolean;
  unavailabilityReason: string | null;
  /** Paid-tier/BYOK selection - drives the free-plan filter/badge. */
  premium: boolean;
  beta: boolean;
}

export interface ModalityCatalog {
  speechToText: ModalityProviderOption[];
  textToSpeech: ModalityProviderOption[];
  videoToText: ModalityProviderOption[];
}

// GET /api/agents/{id}/capabilities - one row of the plug-and-play
// capability catalog (an AgentCapability bean's projection for this agent).
// Replaced the per-provider sarvam/e2b/xai-server-tools/code-workspace
// PATCHes; the workbench renders these verbatim.
export interface AgentCapabilityEntry {
  key: string;
  label: string;
  description: string;
  category: string;
  enabled: boolean;
  availability: "AVAILABLE" | "NEEDS_SETUP" | "UNAVAILABLE";
  availabilityHint: string | null;
}

export interface ProviderModelInfo {
  modelId: string;
  displayName?: string | null;
  contextWindow?: number | null;
  supportsImageInput?: boolean | null;
  supportsAudioInput?: boolean | null;
  supportsVideoInput?: boolean | null;
  /** LLM-generated "what this model does" blurb (deterministic fallback server-side). */
  capabilitySummary?: string | null;
  description?: string | null;
  deprecated?: boolean | null;
  source?: "LIVE" | "CATALOG";
  /**
   * $/million-token input/output rate, merged in client-side by
   * listProviderModels from GET /api/model-pricing (ModelPricingController,
   * core/chat's LlmPricingCatalog) - not part of the /models response body
   * itself. Undefined (never fetched, e.g. the pricing call failed) is
   * distinct from a genuine catalog value; ModelInfoCard's cost badge
   * renders nothing for undefined rather than a fabricated tier.
   */
  inputCostPerMillionUsd?: number | null;
  outputCostPerMillionUsd?: number | null;
  /**
   * Request parameters (temperature, top_p, max_tokens, ...) the provider ignores or rejects for this model, so
   * a value set on an agent has no effect. Optional and additive: absent means "nothing known", never "all fine".
   * The Run settings drawer reads whichever of these three the catalog sends and warns per ignored parameter.
   */
  unsupportedParams?: string[] | null;
  ignoredParams?: string[] | null;
  droppedParams?: string[] | null;
}

// One row of GET /api/model-pricing?modelIds=... (ModelPricingController).
export interface ModelPriceEntry {
  modelId: string;
  inputPerMillionUsd: number | null;
  outputPerMillionUsd: number | null;
}

// Response of POST /api/admin/llm-usage/calls/{id}/diagnose (see
// diagnoseLlmCall below, next to its sibling getLlmCalls) - a structured
// explanation of WHY a logged LLM call failed, either rule-derived
// ("RULES") or LLM-assisted ("AI"). 404 when the row is missing or the
// call actually succeeded; 403 on FREE plans like every other
// /api/admin/llm-usage/* endpoint.
export interface LlmCallDiagnosis {
  category:
    | "RETIRED_MODEL"
    | "UNKNOWN_MODEL"
    | "UNSUPPORTED_PARAM"
    | "AUTH"
    | "RATE_LIMIT"
    | "QUOTA_BILLING"
    | "MALFORMED_REQUEST"
    | "PROVIDER_OUTAGE"
    | "TIMEOUT"
    | "UNKNOWN";
  title: string;
  explanation: string;
  suggestedFix: string;
  suggestedModels: string[];
  source: "RULES" | "AI";
}
