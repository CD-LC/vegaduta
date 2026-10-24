import type { ToolCallTraceEntry } from "../../MediaCard";
import type { ActivityStep } from "../AgentActivityTrace";
import type { LlmRequestSnapshot } from "../RequestInspector";
import type { StagedFrame } from "../CameraPanel";
import type { HostedFallthroughReason } from "../../../lib/edge/tierRouter";

// Stage 4 (Chat file upload) - capability -> accepted file types + a short
// label for the attach button's tooltip/aria-label. Mirrors
// ChatAttachmentCategory.fromContentType's top-level "image/audio/video"
// split on the backend.
export const ATTACH_ACCEPT_BY_CAPABILITY = {
  supportsImageInput: "image/*",
  supportsAudioInput: "audio/*",
  supportsVideoInput: "video/*",
  // Documents (agent-page parity with the inline /playground path) - a full
  // allow-list, not "application/*", matching ChatAttachmentCategory
  // .DOCUMENT_CONTENT_TYPES on the backend so the picker only offers what the
  // server accepts.
  supportsDocumentInput: "application/pdf,text/plain,text/csv,application/json",
  // Data-analysis sandbox: CSV/XLSX/JSON/PDF/TXT mounted in the run_code working dir (server
  // advertises modalities.sandboxData = Code execution on + data-analysis flag on). xlsx is only
  // ever accepted this way - no chat model takes it natively.
  supportsSandboxInput:
    "text/csv,application/json,application/pdf,text/plain,.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
};

// The four modality booleans the attach button/camera panel key off -
// deliberately the same key set as ATTACH_ACCEPT_BY_CAPABILITY above, so the
// accept-types filter below can index one with the other's keys.
export type AttachCapabilityKey = keyof typeof ATTACH_ACCEPT_BY_CAPABILITY;
export type AttachCapabilities = Record<AttachCapabilityKey, boolean>;

// noImplicitAny types for this file's own data shapes. api.ts's functions are
// still untyped (Promise<any>) for everything this pane calls except
// listProviderModels, so these name only the fields THIS file actually reads
// of each server DTO - the narrow-slice convention ProviderModelInfo's own
// header in api.ts describes - rather than re-declaring the full backend
// contracts here and letting the copies drift.

/** One row of GET /api/agents/{id}/tool-calls (ToolCallLogView,
 * AgentController.java). Extends MediaCard's ToolCallTraceEntry so a turn's
 * trace feeds its fabricated-media check unchanged. */
export interface ToolCallEntry extends ToolCallTraceEntry {
  id: string;
  toolName: string;
  errorMessage?: string | null;
}

/** One row of GET /api/agents/{id}/sessions/{sessionId}/llm-usage
 * (SessionLlmCallEntry, AgentSessionLlmUsageController.java) - the narrow
 * slice this file reads, same convention as ToolCallEntry above. */
export interface SessionLlmCall {
  id: string;
  providerName: string;
  model: string;
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
  estimatedCostUsd?: number | null;
  success: boolean;
  latencyMs: number;
  startedAt: string;
}

/** api.uploadChatAttachment's response (ChatAttachmentService - see its call
 * doc in api.ts: { attachmentKey, category, mimeType, originalFilename,
 * sizeBytes }). */
export interface ChatAttachment {
  attachmentKey: string;
  category: string;
  mimeType: string;
  originalFilename?: string | null;
  sizeBytes?: number;
}

/** What framesForHistory keeps of a StagedFrame for transcript rendering -
 * thumbnail + identity only, never dataBase64 a second time (see its own
 * comment below). */
export type HistoryFrame = Pick<StagedFrame, "dataUrl" | "fileName" | "source">;

/** The per-turn metrics row. elapsedMs/toolCalls are always real (measured
 * client-side / diffed from the tool-call log - see the component doc
 * above); the edge* fields are provenance markers only ever set by the path
 * that owns them (sendViaEdge sets edgeTier/edgeDowngradeDetail, a hosted
 * turn that began as an edge attempt sets edgeFallbackReason). */
export interface TurnMetrics {
  elapsedMs: number;
  toolCalls: number;
  edgeTier?: 0 | 1 | 2;
  edgeDowngradeDetail?: string | null;
  edgeFallbackReason?: string | null;
  // Real per-turn LLM usage (AgentSessionLlmUsageController) - summed over
  // the llm_call_log rows this turn added to the session. Absent when the
  // usage read was unavailable (FREE-plan 403 / fetch failure / zero calls
  // recorded), never fabricated - see the component doc above.
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  estimatedCostUsd?: number;
  // "customers should know what is powering" each turn - the unique
  // provider/model pairs the turn's llm_call_log delta recorded (hosted
  // turns only; an edge turn's engine is edgeTier + edgeModel instead).
  // Same provenance rule as the token fields: absent when the usage read
  // was unavailable, never fabricated.
  poweredBy?: { providerName: string | null; model: string | null }[];
  // The on-device model that actually answered this edge turn (the real
  // modelId off the EdgeActivity bus - tier 1's manifest id, tier 2's).
  // Absent for tier 0 (the browser's built-in model has no model id - see
  // TIER0_ASSET_ID) and for turns taken before this field existed.
  edgeModel?: string | null;
}

/** One transcript entry. The optional flags mirror which send path minted
 * the turn: `ref` only on server turns (TurnFeedback keys RL reward on it),
 * `edgeAttempt`/`onDevice`/`watch`/`rescuedFromServer` are the provenance
 * badges the render below shows. */
export interface PlaygroundTurn {
  role: "user" | "assistant";
  content: string;
  attachment?: ChatAttachment | null;
  frames?: HistoryFrame[];
  trace?: ToolCallEntry[];
  /** Live activity trace (server step events + on-device tool rounds),
   * accumulated DURING the turn. `trace` above stays the post-hoc
   * tool_call_log diff that feeds the metrics row and MediaCard's
   * fabricated-media check; when `steps` is non-empty it is the one shown. */
  steps?: ActivityStep[];
  /** What this turn actually sent to the model (final prompt, options, tools) -
   * the "Request X-ray". Absent for edge turns and when the read failed. */
  requests?: LlmRequestSnapshot[];
  pending?: boolean;
  /** The reply failed (provider error, rejected key...): `content` is the plain-language reason and the turn
   * renders as an inline error bubble with "Try again". Persisted with the transcript, so it survives a reload. */
  failed?: boolean;
  ref?: string;
  /** The reply to a turn core continued after a tool approval was decided -
   * badged "after approval" so the person can tell it is the agent's final
   * answer, not another step. */
  continuation?: boolean;
  watch?: boolean;
  onDevice?: boolean;
  edgeAttempt?: boolean;
  rescuedFromServer?: boolean;
  metrics?: TurnMetrics;
}

/** The slice of core/agents' AgentResponse this pane reads off its `agent`
 * prop (handed down untyped from the agents page - api.listAgents is
 * Promise<any>). */
export interface PlaygroundAgent {
  id: string;
  name: string;
  providerName?: string | null;
  model?: string | null;
  systemPrompt?: string | null;
  instructions?: string | null;
  modalities?: { document?: boolean; sandboxData?: boolean; sandboxDataUnavailableReason?: string | null } | null;
  allowedKnowledgeCollectionIds?: string[] | null;
  biometricConsentEnabled?: boolean;
  // What the "Run settings" drawer shows (all optional: an older core, or a stub agent in a test, omits them).
  /** FAST / PLANNED / CAREFUL / RIGOROUS. */
  reasoningDepth?: string | null;
  temperature?: number | null;
  topP?: number | null;
  maxTokens?: number | null;
  topK?: number | null;
  frequencyPenalty?: number | null;
  presencePenalty?: number | null;
  seed?: number | null;
  stopSequences?: string[] | null;
  /** Tools the agent may call; a tool is on when it is in this list. */
  allowedToolNames?: string[] | null;
  /** EDITOR / VIEWER / RUNNER; null or absent means the caller owns the agent. */
  accessLevel?: string | null;
}

/** sendViaEdge's result contract for handleSend: routeTurn's own
 * HostedFallthroughReason vocabulary plus this component's two local-only
 * outcomes ("agent-switched": the person changed agents mid-turn;
 * "fallback-refused": the per-browser fallback policy declined hosted). */
export type EdgeSendResult =
  | { handled: true }
  | {
      handled: false;
      reason: HostedFallthroughReason | "agent-switched" | "fallback-refused";
      detail?: string;
      /** Edge Wave 2 (EDGE-A): the router's PII-scrubbed copy of the input
       * for guardrail-enabled agents — the hosted re-send must use this,
       * not the raw text (see RouteTurnResult.scrubbedInput). */
      hostedMessage?: string;
    };

/** The pending "on-device snapshot analysis declined - send to the server
 * instead?" offer rendered above the composer. */
export interface VisionFallbackOffer {
  message: string;
  frames: StagedFrame[];
  reason: string;
}
