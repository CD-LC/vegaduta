import type { EdgeRoutingPolicy } from "../../../lib/edge/agentPolicy";


// ---- Wire types (noImplicitAny pass) ---------------------------------
// api.ts's agent/provider/collection endpoints are still untyped
// (apiFetch().then(toJson) resolves to any), so the shapes this screen
// actually renders against are declared here, mirrored field-for-field from
// the backend DTOs they deserialize from. Enum-typed Java fields
// (ProviderName, TuningMode, ExperienceMode, ReasoningDepth, the voice/video/
// TTS provider enums) arrive as plain strings over JSON and are deliberately
// typed `string` rather than string-literal unions - this screen never
// exhaustively switches on them, and a new backend enum constant must not
// become a frontend compile error. Exported so sibling agent surfaces can
// reuse them instead of re-declaring the same wire shape.

/**
 * The GET /api/agents element shape - mirrors AgentResponse.java
 * (core/agents/dto), restricted to the fields this screen reads. Nullability
 * comments follow that record's own javadoc; with strictNullChecks still off
 * they are documentation, not enforcement.
 */
export interface WorkbenchAgent {
  id: string;
  name: string;
  systemPrompt: string;
  providerName: string;
  collaboratorAgentIds?: string[] | null;
  model: string | null;
  // Both null means "no fallback configured" - AgentResponse.java's own note.
  fallbackProviderName: string | null;
  fallbackModel: string | null;
  fallbackTriggeredCount: number;
  fallbackFailedCount: number;
  lastFallbackAt: string | null;
  maxTokens: number;
  temperature: number;
  topP: number;
  tuningMode: string;
  knowledgeBaseEnabled: boolean;
  responseCachingEnabled?: boolean;
  shareMemoryWithTenant?: boolean;
  sarvamEnabled: boolean;
  e2bEnabled: boolean;
  // xAI Agent Tools API (migration V239) - optional because agent payloads
  // fetched before the field existed simply omit it; treat undefined as off.
  xaiServerToolsEnabled?: boolean;
  codeWorkspaceEnabled: boolean;
  costRoutingEnabled: boolean;
  economyProviderName: string | null;
  economyModel: string | null;
  // Optional (V334): this agent's own bar for the decision provider's "is
  // this turn simple" second opinion. null/absent = tenant policy ->
  // platform default. (Field name is a persisted backend contract.)
  costRoutingJevThreshold?: number | null;
  // Null means "no policy, device-local preferences decide" - see
  // EdgeRoutingPolicy's javadoc (lib/edge/agentPolicy re-exports the type).
  edgeRoutingPolicy: EdgeRoutingPolicy | null;
  // Empty means "no restriction", not "none" - AgentResponse.java.
  allowedToolNames: string[] | null;
  allowedKnowledgeCollectionIds: string[] | null;
  // OPPOSITE convention: empty means deny-all - AgentResponse.java.
  allowedCodeIntelRepoIds: string[] | null;
  // Null means "inherit the tenant's default" - resolved via effectiveMode().
  experienceMode: string | null;
  reasoningDepth: string | null;
  // #815 Governance. Never null server-side (AgentAutonomyTier defaults to
  // SEMI_AUTONOMOUS, migration V74 backfilled every pre-existing agent), but
  // an older cached shape may omit it - the picker falls back accordingly.
  autonomyTier?: string | null;
  // Optional (not just nullable): an undefined/older cached shape is treated
  // as editable, never locked - see the `locked` derivation below.
  editable?: boolean;
  voiceModelProvider: string | null;
  voiceModel: string | null;
  videoModelProvider: string | null;
  videoModel: string | null;
  ttsProvider: string | null;
  ttsModel: string | null;
}

/**
 * The GET /api/providers element shape, restricted to the two fields this
 * screen reads (activeProviders filter, provider dropdowns, the OPENAI
 * availability probe). The full response carries more (credentials metadata
 * etc.) - structural typing keeps those flowing through untouched.
 */
export interface WorkbenchProvider {
  providerName: string;
  status: string;
  /** Per-credential health (lib/providerHealth.ts): "ok" | "rejected" while core reports it; absent on an older core. */
  health?: unknown;
  recentFailures?: number;
  lastError?: string | null;
}

/** GET /api/knowledge/collections element shape, as read here (chips + picker). */
export interface WorkbenchCollection {
  id: string;
  name: string;
}

/**
 * GET /api/agents/{id}/reliability - mirrors AgentReliabilitySummary.java
 * (core/agents/dto); Instants serialize as ISO strings. successRatePercent/
 * avgResponseTimeMs are null (never a fabricated 100/0ms) when totalCalls is
 * 0 - that record's own javadoc.
 */
export interface AgentReliabilitySummary {
  agentId: string;
  totalCalls: number;
  failedCalls: number;
  successRatePercent: number | null;
  avgResponseTimeMs: number | null;
  firstCallAt: string | null;
  lastCallAt: string | null;
  toolCallCount: number;
  toolCallFailureCount: number;
  fallbackTriggeredCount: number;
  fallbackFailedCount: number;
  lastFallbackAt: string | null;
  recentFailures: AgentReliabilityFailure[];
}

/** AgentReliabilitySummary.RecentFailure - the "Recent failures" list rows. */
export interface AgentReliabilityFailure {
  occurredAt: string | null;
  errorMessage: string | null;
  exceptionType: string | null;
  provider: string | null;
  model: string | null;
}

/**
 * GET /api/agents/{id}/available-tools element shape (AgentToolProvider's
 * ToolDescriptor) - only the two fields ToolsGrid renders/toggles by.
 */
export interface ResolvedTool {
  name: string;
  description: string | null;
}
