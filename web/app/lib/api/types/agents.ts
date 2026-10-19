export type { MeResponse, AccountResponse, TenantSettingsResponse } from "../schemas";

/** AgentResponse (core/agents). Nested views stay `unknown` until a caller models them. */
export interface AgentResponse {
  id: string;
  name: string;
  systemPrompt: string | null;
  providerName: string | null;
  model: string | null;
  fallbackProviderName: string | null;
  fallbackModel: string | null;
  fallbackTriggeredCount: number;
  fallbackFailedCount: number;
  lastFallbackAt: string | null;
  maxTokens: number;
  temperature: number;
  topP: number;
  tuningMode: string | null;
  knowledgeBaseEnabled: boolean;
  sarvamEnabled: boolean;
  e2bEnabled: boolean;
  codeWorkspaceEnabled: boolean;
  responseCachingEnabled: boolean;
  semanticResponseCachingEnabled: boolean;
  costRoutingEnabled: boolean;
  economyProviderName: string | null;
  economyModel: string | null;
  costRoutingJevThreshold: number | null;
  shareMemoryWithTenant: boolean;
  biometricConsentEnabled: boolean;
  edgeRoutingPolicy: string | null;
  allowedToolNames: string[] | null;
  allowedKnowledgeCollectionIds: string[] | null;
  allowedCodeIntelRepoIds: string[] | null;
  experienceMode: string | null;
  reasoningDepth: string | null;
  autonomyTier: string | null;
  collaboratorAgentIds: string[] | null;
  createdAt: string;
  updatedAt: string;
  voiceModelProvider: string | null;
  voiceModel: string | null;
  videoModelProvider: string | null;
  videoModel: string | null;
  ttsProvider: string | null;
  ttsModel: string | null;
  editable: boolean;
  /** Caller's effective share level on this agent; null for the owner or an older core. */
  accessLevel?: "EDITOR" | "VIEWER" | "RUNNER" | null;
  modalities: unknown;
  inputSchemaJson: string | null;
  outputSchemaJson: string | null;
  xaiServerToolsEnabled: boolean;
  topK: number | null;
  stopSequences: string[] | null;
  frequencyPenalty: number | null;
  presencePenalty: number | null;
  seed: number | null;
}
