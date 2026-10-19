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
  /** Creator's auth subject (JWT sub); null on rows that predate V151. Audit trail only. */
  createdBy?: string | null;
  /** Last versioned editor's auth subject; null until the first save after V393. */
  updatedBy?: string | null;
  /**
   * Caller's effective share level on this agent - EDITOR covers owner, tenant-admin and EDITOR
   * grants alike, so it cannot distinguish "mine" from "shared with me editable"; use `createdBy`
   * for that. Null on an older core or when the level cannot be resolved.
   */
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
