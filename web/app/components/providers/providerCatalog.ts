import {
  Sparkles,
  Bot,
  Cloud,
  Globe,
  Server,
  Cpu,
  Router,
  Zap,
  Languages,
  TerminalSquare,
  Wind,
  Waves,
  Gauge,
  Mic,
  Orbit,
  Layers,
  HardDrive,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";

// ACTIVE stays on the shared --success-* tokens so it keeps meaning regardless
// of the tenant's accent palette. Any other credential state (INACTIVE,
// PENDING, ...) is a quiet "needs attention" hint, not a destructive action,
// so it sits on the accent tint chip (tokens.css --accent-50/200) rather
// than the rose --danger-* box - the console uses red only for true
// destructive controls.
export const OK_BADGE = "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]";
export const WARN_BADGE = "border-[var(--accent-200)] bg-[var(--accent-50)] text-[var(--accent-text)]";

/** Locale date for an activated credential, or null when the row carries no
 * usable createdAt (a partial/empty status payload) - callers skip the
 * "activated ..." line entirely instead of ever printing "Invalid Date". */
export function formatActivatedDate(createdAt: string | undefined | null): string | null {
  if (!createdAt) return null;
  const d = new Date(createdAt);
  return Number.isNaN(d.getTime()) ? null : d.toLocaleDateString();
}

// Every ProviderName the backend enum knows about (see ProviderName.java),
// even though only some currently have a working validator + chat client
// factory - listing the rest here is honest about what's selectable vs.
// what's actually wired up yet, matching the project's "don't fake
// functionality" stance elsewhere. OPENROUTER/GROK/GEMINI added for
// "implement openrouter, grok and gemini so that consumers can use them" -
// see OpenRouterAgentChatClientFactory/GrokAgentChatClientFactory/
// GeminiAgentChatClientFactory + their matching ProviderValidators. SARVAM
// added for the Sarvam provider - see
// SarvamAgentChatClientFactory + SarvamCredentialValidator. OPENAI moved
// into IMPLEMENTED_PROVIDERS below once OpenAIAgentChatClientFactory +
// OpenAIProviderValidator existed - it had been listed here as a selectable
// card since day one but silently 404'd on activation until then (the most
// commonly expected provider for a brand-new signup, and the one that
// least looked "not implemented yet" since it was never visually
// distinguished from the working cards next to it). META (2026-09-17) is
// the Meta Model API (api.meta.ai, OpenAI-compatible Chat Completions) for
// the Muse Spark models - wired the same way as GROK/DEEPSEEK; Muse Spark
// stays reachable through OPENROUTER as meta/muse-spark-1.3 as well.
export const PROVIDERS = [
  "ANTHROPIC",
  "OPENAI",
  "OPENROUTER",
  "GROK",
  "GEMINI",
  "SARVAM",
  "E2B",
  "ELEVENLABS",
  "AZURE_OPENAI",
  "GOOGLE_VERTEX",
  "AWS_BEDROCK",
  "OLLAMA",
  "MISTRAL",
  "DEEPSEEK",
  "GROQ",
  "META",
  "LOCAL_OPENAI",
] as const;

// Every card key selectable on this panel - drives PROVIDER_INFO/
// MULTI_FIELD_PROVIDERS lookups and the providerName selection state below.
export type ProviderName = (typeof PROVIDERS)[number];
// Provider fan-out (July 2026): AZURE_OPENAI/AWS_BEDROCK/OLLAMA got a real
// ProviderValidator + AgentChatClientFactory (see ProviderName.java's
// javadoc), same for the bonus OpenAI-compatible providers MISTRAL/
// DEEPSEEK/GROQ. GOOGLE_VERTEX was already implemented before this fan-out
// (see VERTEX_SPECIAL_CASED below) and stays in this set unchanged. META
// (Meta Model API / Muse Spark) joined the OpenAI-compatible set in
// September 2026 alongside GROK/DEEPSEEK.
export const IMPLEMENTED_PROVIDERS: Set<ProviderName> = new Set([
  "ANTHROPIC",
  "OPENAI",
  "OPENROUTER",
  "GROK",
  "GEMINI",
  "GOOGLE_VERTEX",
  "SARVAM",
  "E2B",
  "ELEVENLABS",
  "AZURE_OPENAI",
  "AWS_BEDROCK",
  "OLLAMA",
  "MISTRAL",
  "DEEPSEEK",
  "GROQ",
  "META",
  "LOCAL_OPENAI",
]);

// Provider fan-out (July 2026): OLLAMA/AZURE_OPENAI/AWS_BEDROCK all need
// more than a single API-key field, the same "encode a small {field: value}
// object to one JSON string, send it as the existing apiKey param" pattern
// ProviderActivationRequest's backend javadoc documents for every
// multi-field provider (OllamaCredential/AzureOpenAiCredential/
// AwsBedrockCredential in core/providers - each has a matching
// encode()/decode() pair expecting exactly these JSON key names). Field
// `key` values below MUST match those records' component names exactly.
// GOOGLE_VERTEX deliberately does NOT go through this generic path - it
// predates it and already has its own working special-cased form below
// (VERTEX_SPECIAL_CASED/encodeVertexCredential); left as-is rather than
// migrated, to avoid touching a credential-encoding path that already works.
/** One field in a multi-field provider's activation form (OLLAMA/
 * AZURE_OPENAI/AWS_BEDROCK) - `key` mirrors the matching backend
 * ...Credential record's component name exactly (see the comment below). */
export interface MultiField {
  key: string;
  labelKey: string;
  placeholderKey: string;
  required: boolean;
  type: "text" | "password";
}

export const MULTI_FIELD_PROVIDERS: Partial<Record<ProviderName, MultiField[]>> = {
  OLLAMA: [
    {
      key: "baseUrl",
      labelKey: "ollamaBaseUrlLabel",
      placeholderKey: "ollamaBaseUrlPlaceholder",
      required: true,
      type: "text",
    },
    {
      key: "apiKey",
      labelKey: "ollamaApiKeyLabel",
      placeholderKey: "ollamaApiKeyPlaceholder",
      required: false,
      type: "password",
    },
  ],
  // Local / OpenAI-compatible server (vLLM, llama.cpp, LM Studio, Jan, LocalAI):
  // keys mirror core's LocalOpenAiCredential record (baseUrl, apiKey).
  LOCAL_OPENAI: [
    {
      key: "baseUrl",
      labelKey: "localOpenaiBaseUrlLabel",
      placeholderKey: "localOpenaiBaseUrlPlaceholder",
      required: true,
      type: "text",
    },
    {
      key: "apiKey",
      labelKey: "localOpenaiApiKeyLabel",
      placeholderKey: "localOpenaiApiKeyPlaceholder",
      required: false,
      type: "password",
    },
  ],
  AZURE_OPENAI: [
    {
      key: "endpoint",
      labelKey: "azureEndpointLabel",
      placeholderKey: "azureEndpointPlaceholder",
      required: true,
      type: "text",
    },
    {
      key: "apiKey",
      labelKey: "azureApiKeyLabel",
      placeholderKey: "azureApiKeyPlaceholder",
      required: true,
      type: "password",
    },
    {
      key: "deploymentName",
      labelKey: "azureDeploymentLabel",
      placeholderKey: "azureDeploymentPlaceholder",
      required: true,
      type: "text",
    },
    {
      key: "apiVersion",
      labelKey: "azureApiVersionLabel",
      placeholderKey: "azureApiVersionPlaceholder",
      required: false,
      type: "text",
    },
  ],
  AWS_BEDROCK: [
    {
      key: "accessKeyId",
      labelKey: "bedrockAccessKeyLabel",
      placeholderKey: "bedrockAccessKeyPlaceholder",
      required: true,
      type: "text",
    },
    {
      key: "secretAccessKey",
      labelKey: "bedrockSecretKeyLabel",
      placeholderKey: "bedrockSecretKeyPlaceholder",
      required: true,
      type: "password",
    },
    {
      key: "region",
      labelKey: "bedrockRegionLabel",
      placeholderKey: "bedrockRegionPlaceholder",
      required: true,
      type: "text",
    },
  ],
};

/** Builds the JSON blob a multi-field provider's apiKey param carries - the
 * frontend mirror of each provider's backend `record ...Credential`
 * encode(). Blank optional fields are omitted entirely (not sent as ""),
 * so the backend record's own default (e.g. AzureOpenAiCredential's
 * apiVersion) applies instead of an empty string overriding it. */
export function encodeMultiFieldCredential(fields: MultiField[], values: Record<string, string>): string {
  const obj: Record<string, string> = {};
  for (const field of fields) {
    const value = (values[field.key] || "").trim();
    if (value) obj[field.key] = value;
  }
  return JSON.stringify(obj);
}

// SARVAM is the one card in this panel that doesn't read/write through
// listProviders()/activateProvider() - its credential lives in its own
// sarvam_credential table (SarvamController), not LlmProviderCredential, so
// it can be shared with Stage 3's non-chat Sarvam clients (translate/STT/TTS)
// later without a tenant activating twice. See
// ProviderCredentialResolver#resolveActiveKey's javadoc for the backend side
// of this split.
export const SARVAM_SPECIAL_CASED = "SARVAM";

// Master Roadmap 2026 item #14 fast-follow (#1071) - E2B is special-cased
// the same way SARVAM is above: its credential lives in its own
// e2b_credential table (E2bCredentialController), not LlmProviderCredential,
// since it isn't a chat provider at all - see E2bCredentialService's
// javadoc. Unlike Sarvam, an active credential alone isn't enough for the
// run_code tool to actually resolve (AgentToolProvider#resolveE2bTools also
// requires hasCodeExecutionTemplate()), so this card additionally offers a
// "code-execution template ID" field once the API key is activated - see
// the codeExecutionTemplateId form below.
export const E2B_SPECIAL_CASED = "E2B";

// ElevenLabs voice cloning - special-cased the same way SARVAM/E2B are: its
// credential lives in its own elevenlabs_credential table
// (ElevenLabsController), not LlmProviderCredential, since it isn't a chat
// provider at all - see ElevenLabsCredentialService's javadoc. Added
// 2026-08-03 - the backend has always supported this, this card was the
// missing frontend on-ramp (a tenant could only activate it via curl/Postman
// before).
export const ELEVENLABS_SPECIAL_CASED = "ELEVENLABS";

// Vertex's credential is not a plain string key like every other provider on
// this panel - it's a project ID + region + GCP service-account JSON key
// (see core's VertexCredential.java javadoc for why it's still encoded into
// the one apiKey column POST /api/providers already accepts, rather than a
// new endpoint/table). So this IS the normal activateProvider() write path -
// unlike SARVAM/E2B above, which are special-cased because their credential
// lives in a completely different table - Vertex only needs a different
// FORM SHAPE (three fields instead of one) that gets encoded into a single
// string client-side before it's sent.
export const VERTEX_SPECIAL_CASED = "GOOGLE_VERTEX";

/** Mirrors core's VertexCredential.encode() wire format exactly - {projectId, location, serviceAccountKeyBase64}. */
export function encodeVertexCredential(projectId: string, location: string, serviceAccountKeyJson: string): string {
  const bytes = new TextEncoder().encode(serviceAccountKeyJson);
  let binary = "";
  bytes.forEach((b) => {
    binary += String.fromCharCode(b);
  });
  return JSON.stringify({
    projectId,
    location,
    serviceAccountKeyBase64: btoa(binary),
  });
}

// Same "no external brand-asset dependency, plain icon per product area"
// idiom GoogleIntegrationPanel's SCOPE_INFO and McpPanel's catalog cards
// already use - a generic lucide icon + a neutral AXON-palette tint per
// card, not a fabricated brand mark.
export interface ProviderInfoEntry {
  labelKey: string;
  descKey: string;
  icon: LucideIcon;
  tint: string;
  color: string;
}

export const PROVIDER_INFO: Record<ProviderName, ProviderInfoEntry> = {
  ANTHROPIC: {
    labelKey: "anthropicLabel",
    descKey: "anthropicDesc",
    icon: Sparkles,
    tint: "color-mix(in srgb, var(--ax-cyan) 15%, transparent)",
    color: "var(--ax-cyan)",
  },
  OPENAI: {
    labelKey: "openaiLabel",
    descKey: "openaiDesc",
    icon: Bot,
    tint: "color-mix(in srgb, var(--ax-violet) 15%, transparent)",
    color: "var(--ax-violet)",
  },
  OPENROUTER: {
    labelKey: "openrouterLabel",
    descKey: "openrouterDesc",
    icon: Router,
    tint: "color-mix(in srgb, var(--ax-green) 15%, transparent)",
    color: "var(--ax-green)",
  },
  GROK: {
    labelKey: "grokLabel",
    descKey: "grokDesc",
    icon: Zap,
    tint: "color-mix(in srgb, var(--ax-amber) 15%, transparent)",
    color: "var(--ax-amber)",
  },
  GEMINI: {
    labelKey: "geminiLabel",
    descKey: "geminiDesc",
    icon: Globe,
    tint: "color-mix(in srgb, var(--ax-violet) 15%, transparent)",
    color: "var(--ax-violet)",
  },
  AZURE_OPENAI: {
    labelKey: "azureOpenaiLabel",
    descKey: "azureOpenaiDesc",
    icon: Cloud,
    tint: "color-mix(in srgb, var(--ax-cyan) 15%, transparent)",
    color: "var(--ax-cyan)",
  },
  GOOGLE_VERTEX: {
    labelKey: "googleVertexLabel",
    descKey: "googleVertexDesc",
    // Distinct from GEMINI's Globe/violet pair (audit #24) - same Google
    // family, different card, different product (Vertex's managed model
    // hosting vs. the direct Gemini API).
    icon: Layers,
    tint: "color-mix(in srgb, var(--ax-cyan) 15%, transparent)",
    color: "var(--ax-cyan)",
  },
  AWS_BEDROCK: {
    labelKey: "awsBedrockLabel",
    descKey: "awsBedrockDesc",
    icon: Server,
    tint: "color-mix(in srgb, var(--ax-amber) 15%, transparent)",
    color: "var(--ax-amber)",
  },
  OLLAMA: {
    labelKey: "ollamaLabel",
    descKey: "ollamaDesc",
    icon: Cpu,
    tint: "color-mix(in srgb, var(--ax-green) 15%, transparent)",
    color: "var(--ax-green)",
  },
  LOCAL_OPENAI: {
    labelKey: "localOpenaiLabel",
    descKey: "localOpenaiDesc",
    icon: HardDrive,
    tint: "color-mix(in srgb, var(--ax-cyan) 15%, transparent)",
    color: "var(--ax-cyan)",
  },
  SARVAM: {
    labelKey: "sarvamLabel",
    descKey: "sarvamDesc",
    icon: Languages,
    tint: "color-mix(in srgb, var(--ax-amber) 15%, transparent)",
    color: "var(--ax-amber)",
  },
  E2B: {
    labelKey: "e2bLabel",
    descKey: "e2bDesc",
    icon: TerminalSquare,
    tint: "color-mix(in srgb, var(--ax-green) 15%, transparent)",
    color: "var(--ax-green)",
  },
  ELEVENLABS: {
    labelKey: "elevenLabsLabel",
    descKey: "elevenLabsDesc",
    icon: Mic,
    tint: "color-mix(in srgb, var(--ax-violet) 15%, transparent)",
    color: "var(--ax-violet)",
  },
  MISTRAL: {
    labelKey: "mistralLabel",
    descKey: "mistralDesc",
    icon: Wind,
    tint: "color-mix(in srgb, var(--ax-cyan) 15%, transparent)",
    color: "var(--ax-cyan)",
  },
  DEEPSEEK: {
    labelKey: "deepseekLabel",
    descKey: "deepseekDesc",
    icon: Waves,
    tint: "color-mix(in srgb, var(--ax-violet) 15%, transparent)",
    color: "var(--ax-violet)",
  },
  GROQ: {
    labelKey: "groqLabel",
    descKey: "groqDesc",
    icon: Gauge,
    tint: "color-mix(in srgb, var(--ax-amber) 15%, transparent)",
    color: "var(--ax-amber)",
  },
  META: {
    labelKey: "metaLabel",
    descKey: "metaDesc",
    icon: Orbit,
    tint: "color-mix(in srgb, var(--ax-green) 15%, transparent)",
    color: "var(--ax-green)",
  },
};
