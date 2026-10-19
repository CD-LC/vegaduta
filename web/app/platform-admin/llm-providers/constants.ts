// Static option lists, presets and draft types for the LLM & Providers screen.
// Split out of page.tsx to keep that file under the 1000-line limit.

export const PROVIDER_OPTIONS = [
  { value: "ANTHROPIC", label: "Anthropic" },
  { value: "OPENAI", label: "OpenAI" },
  { value: "AZURE_OPENAI", label: "Azure OpenAI" },
  { value: "GOOGLE_VERTEX", label: "Google Vertex" },
  { value: "AWS_BEDROCK", label: "AWS Bedrock" },
  { value: "OLLAMA", label: "Ollama" },
  { value: "OPENROUTER", label: "OpenRouter" },
  { value: "GROK", label: "Grok (xAI)" },
  { value: "GEMINI", label: "Gemini" },
  // Meta Model API (api.meta.ai) - OpenAI-compatible bearer-token endpoint
  // serving the Muse Spark family (muse-spark-1.3 recommended). Distinct from
  // the OpenRouter route (meta/muse-spark-1.3), which keeps working as-is.
  // The "-contributor" variants are cheap because Meta trains on that
  // traffic - never a default, label them as such wherever they surface.
  { value: "META", label: "Meta (Muse Spark)" },
];

export const BLANK_HOSTED_PROVIDER_DRAFT = {
  mode: "custom", // "custom" (raw baseUrl - Groq/Together/...) | "provider" (generic providerName+key, like tenant BYOK)
  label: "",
  baseUrl: "",
  model: "",
  apiKey: "",
  enabled: true,
  priority: 100,
  region: "",
  completionsPath: "",
  providerName: "",
  authType: "API_KEY", // "API_KEY" (default, unchanged) | "VERTEX_ADC" | "VERTEX_SERVICE_ACCOUNT"
  vertexProjectId: "",
  vertexLocation: "us-central1",
  // V484 - a model the operator runs themselves (Ollama, vLLM, llama.cpp): priced at $0,
  // never debited, first in the failover order; a pool made only of such rows is unmetered.
  zeroCost: false,
  // V500 - constrained tool router override: "" = decide from the measured grade, "on", "off".
  toolRouterEnabled: "",
  // V501 - small-model mode override: "" = decide from the model's size (<= 8B), "on", "off".
  smallModel: "",
};

/**
 * The per-row small-model overrides (V500 router) as the API carries them: a tri-state Boolean
 * (null = derived). Shared by the edit-draft loader and both save payloads in page.tsx.
 */
const triState = (v: unknown) => (v === true ? "on" : v === false ? "off" : "");
const triBool = (v: unknown) => (v === "on" ? true : v === "off" ? false : null);
export function hostedRowOverridesToDraft(row: HostedProviderRow) {
  return { toolRouterEnabled: triState(row.toolRouterEnabled), smallModel: triState(row.smallModel) };
}
export function hostedRowOverridesToPayload(draft: { toolRouterEnabled?: string; smallModel?: string }) {
  return { toolRouterEnabled: triBool(draft.toolRouterEnabled), smallModel: triBool(draft.smallModel) };
}
export function hostedRowOverridesFromRow(row: HostedProviderRow) {
  return {
    toolRouterEnabled: typeof row.toolRouterEnabled === "boolean" ? row.toolRouterEnabled : null,
    smallModel: typeof row.smallModel === "boolean" ? row.smallModel : null,
  };
}

// Credential Type selector - lets a Gemini/Vertex row authenticate via Vertex
// AI (project + either Application Default Credentials or a pasted
// service-account JSON key) instead of only a plain API key. Mirrors the
// backend's PlatformHostedLlmProviderUpsertRequest#authType exactly - the
// mode/baseUrl/providerName toggle above stays API_KEY-only, since neither
// "custom base URL" nor "provider + key" (OpenAI-compat bearer-token auth)
// concepts apply to Vertex.
export const CREDENTIAL_TYPE_OPTIONS = [
  { value: "API_KEY", label: "API Key" },
  { value: "VERTEX_ADC", label: "Vertex AI — Application Default Credentials" },
  { value: "VERTEX_SERVICE_ACCOUNT", label: "Vertex AI — Service Account JSON" },
];
export const CREDENTIAL_TYPE_LABELS: Record<string, string> = {
  VERTEX_ADC: "Vertex ADC",
  VERTEX_SERVICE_ACCOUNT: "Vertex Service Account",
};

// Draft of the per-task LLM routing form (taskDrafts) - provider + model +
// the not-yet-saved apiKey field, which is never echoed back by the API.
export type LlmTaskDraft = { providerName: string; model: string; apiKey: string };

// Draft of the translation-job reference form - every field is a plain
// string the admin edits in place (see setTranslationDraft's construction
// from the GET response a few hundred lines below).
export type TranslationJobDraft = {
  cronScheduleDisplay: string;
  providerName: string;
  model: string;
  workflowPathHint: string;
  notes: string;
};

// A hosted-LLM-pool row (PlatformHostedLlmProviderController). Fields beyond
// id/label are read straight through the index signature - the payload is
// wide (baseUrl/model/authType/vertex*/region/completionsPath/...) and new
// fields show up here without a type change.
export type HostedProviderRow = { id: string; label?: string; [key: string]: any };

// "I need something generic like what we have in tenant - should be able to
// add any provider, and leave blank to select any best model from the
// provider" (2026-07-26 feedback). Mirrors
// PlatformHostedLlmProviderService.HOSTED_POOL_COMPATIBLE_PROVIDERS exactly -
// the hosted pool's client factory only ever authenticates with a plain
// bearer token, which rules out SARVAM (custom api-subscription-key header)
// and every non-OpenAI-compat provider (Anthropic, Azure OpenAI, Vertex,
// Bedrock, Ollama). MISTRAL/DEEPSEEK/GROQ added 2026-08-03 - confirmed
// OpenAI-compat bearer-token providers, same as the original four. META added
// 2026-09-17 - the Meta Model API (api.meta.ai, Muse Spark) is a plain
// "Authorization: Bearer <key>" OpenAI-compatible endpoint, so it qualifies;
// core's HOSTED_POOL_COMPATIBLE_PROVIDERS gained META in the same change.
export const HOSTED_POOL_GENERIC_PROVIDERS = [
  { value: "OPENAI", label: "OpenAI" },
  { value: "GROK", label: "Grok (xAI)" },
  { value: "OPENROUTER", label: "OpenRouter" },
  { value: "GEMINI", label: "Gemini" },
  { value: "MISTRAL", label: "Mistral AI" },
  { value: "DEEPSEEK", label: "DeepSeek" },
  { value: "GROQ", label: "Groq" },
  { value: "META", label: "Meta (Muse Spark)" },
];

export const HOSTED_PROVIDER_PRESETS = [
  {
    value: "https://api.groq.com/openai",
    label: "Groq",
    // Current GroqCloud lineup (mixtral-8x7b-32768 and gemma2-9b-it were
    // decommissioned upstream). The openai/gpt-oss-* ids are OpenAI's
    // open-weight models served by Groq - the fastest hosted way to run
    // gpt-oss on a free key.
    models: ["llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-120b", "openai/gpt-oss-20b"],
  },
  {
    value: "https://api.together.xyz",
    label: "Together AI",
    models: [
      "meta-llama/Llama-3.3-70B-Instruct-Turbo",
      "meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo",
      "mistralai/Mixtral-8x7B-Instruct-v0.1",
    ],
  },
  {
    value: "https://api.fireworks.ai/inference",
    label: "Fireworks AI",
    models: ["accounts/fireworks/models/llama-v3p3-70b-instruct", "accounts/fireworks/models/mixtral-8x7b-instruct"],
  },
  {
    value: "https://api.deepinfra.com/v1/openai",
    label: "DeepInfra",
    models: ["meta-llama/Llama-3.3-70B-Instruct", "mistralai/Mixtral-8x7B-Instruct-v0.1"],
  },
  {
    value: "https://api.cerebras.ai",
    label: "Cerebras",
    models: ["llama-3.3-70b", "llama-3.1-8b"],
  },
  {
    value: "https://api.sambanova.ai",
    label: "SambaNova",
    models: ["Meta-Llama-3.3-70B-Instruct", "Meta-Llama-3.1-8B-Instruct"],
  },
  {
    // Google Gemini exposes an OpenAI-compatible endpoint, but NOT at the
    // conventional "{baseUrl}/v1/chat/completions" - it lives under
    // /v1beta/openai/. Selecting this preset auto-fills the Completions path
    // field below so the row targets the right endpoint (see
    // PlatformHostedLlmProviderUpsertRequest#completionsPath).
    value: "https://generativelanguage.googleapis.com",
    label: "Google Gemini",
    models: ["gemini-2.0-flash-lite", "gemini-2.0-flash", "gemini-2.5-flash", "gemini-1.5-flash"],
    completionsPath: "/v1beta/openai/chat/completions",
  },
  {
    // Meta Model API - conventional "{baseUrl}/v1/chat/completions", so no
    // completionsPath override. 1M-token context, tools, structured output,
    // streaming, prompt caching. Contributor variants are deliberately NOT
    // listed here: they cost ~10x less because Meta trains on the traffic,
    // which is never an acceptable default for the shared hosted pool.
    value: "https://api.meta.ai",
    label: "Meta Muse Spark",
    models: ["muse-spark-1.3", "muse-spark-1.2", "muse-spark-1.1"],
  },
];
export const HOSTED_PROVIDER_REGION_OPTIONS = [
  { value: "us-east", label: "US East" },
  { value: "us-west", label: "US West" },
  { value: "eu-west", label: "EU West" },
  { value: "eu-central", label: "EU Central" },
  { value: "apac", label: "APAC" },
  { value: "global", label: "Global" },
];
export const HOSTED_PROVIDER_PRIORITY_OPTIONS = [
  { value: "10", label: "10 - Primary" },
  { value: "50", label: "50 - Secondary" },
  { value: "100", label: "100 - Default" },
  { value: "200", label: "200 - Fallback" },
];

export function withCurrentValue(options: { value: any; label: string }[], current: any) {
  if (!current || options.some((o) => String(o.value) === String(current))) return options;
  return [...options, { value: current, label: current }];
}

export const TASK_INFO: Record<string, { label: string; description: string }> = {
  TRANSLATION_REFRESH: {
    label: "Translation Refresh",
    description:
      "The LLM call web/scripts/translate-messages.mjs makes when the weekly translate-messages.yml GitHub Action runs.",
  },
  ASK_AI_HELPER: {
    label: "Ask-AI Helper",
    description:
      "The in-console \"help me write this description/prompt\" assistant (AssistController/AssistService), when a super-admin wants to point it at a specific provider/model/key rather than a tenant's own active provider.",
  },
};

