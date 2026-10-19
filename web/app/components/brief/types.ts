// Typed mirror of docs/plans/brief-schema.v1.json plus the row/job shapes in
// docs/plans/EARNED-AUTONOMY-CONTRACT.md. Refs (a1, w1, t1, ...) are never
// tenant ids; the apply engine resolves them and reports entity ids back
// through the apply job and `bindings`.

export type Rung = "UNDER_WATCH" | "TRUSTED" | "AUTONOMOUS";
export const RUNGS: readonly Rung[] = ["UNDER_WATCH", "TRUSTED", "AUTONOMOUS"];

export type ReasoningDepth = "FAST" | "CAREFUL" | "RIGOROUS" | "PLANNED";

export interface BriefClarification {
  question: string;
  options?: string[];
}

export interface BriefTrust {
  rung: Rung;
  irreversible: string[];
  humanMustDecide?: string[];
}

export interface BriefAgent {
  ref: string;
  name: string;
  description?: string;
  systemPrompt: string;
  reasoningDepth: ReasoningDepth;
  tuningMode?: "SIMPLE" | "MANUAL";
  generation?: { temperature?: number; topP?: number; maxTokens?: number };
  toolAllowList?: string[];
  knowledgeRefs?: string[];
  procedureRefs?: string[];
  guardrails?: { kind: string; config?: Record<string, unknown> }[];
  edgeRoutingPolicy?: string;
  shareMemoryWithTenant?: boolean;
}

export interface BriefProcedure {
  ref: string;
  name: string;
  steps: string[];
  source?: "USER_GOAL" | "PLANNER";
}

// WorkflowGraphDto shape: agent/knowledge/tool ids inside node config are refs.
export interface BriefGraphNode {
  id: string;
  type: string;
  position?: { x: number; y: number };
  data: { label?: string; config?: Record<string, any> };
}
export interface BriefGraphEdge {
  id?: string;
  source: string;
  target: string;
  label?: string;
}

export interface BriefWorkflow {
  ref: string;
  name: string;
  graph: { nodes: BriefGraphNode[]; edges: BriefGraphEdge[] };
  triggers?: { kind: "MANUAL" | "SCHEDULE" | "WEBHOOK" | "CHANNEL"; cron?: string; channelRef?: string }[];
  sampleInput?: string;
}

export interface BriefTool {
  ref: string;
  kind: "OPENAPI" | "CODE";
  name?: string;
  specUrl?: string;
  auth?: { type?: "NONE" | "API_KEY_HEADER" | "BEARER" | "OAUTH2_CLIENT_CREDENTIALS"; secretRef?: string };
  codeDescription?: string;
}

export interface BriefMcp {
  ref: string;
  registryId: string;
  name?: string;
  env?: Record<string, { secretRef: string }>;
}

export interface BriefKnowledge {
  ref: string;
  name: string;
  sources: { kind: "UPLOAD_LATER" | "URL" | "CONNECTOR"; url?: string; connectorType?: string; hint?: string }[];
}

export interface BriefSecret {
  ref: string;
  name: string;
  scope: "TENANT" | "USER";
  why: string;
  obtainAt?: string;
}

export type ChannelKind = "WHATSAPP" | "TELEGRAM" | "SLACK" | "DISCORD" | "TEAMS" | "EMAIL" | "SMS" | "VOICE" | "WEB";

export interface BriefChannel {
  ref: string;
  kind: ChannelKind;
  bindTo: string;
  optional?: boolean;
}

export interface BriefEvalSet {
  targetAgent?: string;
  cases: { input: string; expected?: string; rubric: string }[];
  gate?: { minScore?: number };
}

export interface BriefNarrativeEntry {
  why: string;
  tune: string;
  proof: string;
}

export interface Brief {
  briefVersion: 1;
  title: string;
  goal: string;
  locale: string;
  clarifications?: BriefClarification[];
  trust: BriefTrust;
  agents: BriefAgent[];
  procedures?: BriefProcedure[];
  workflows: BriefWorkflow[];
  tools?: BriefTool[];
  mcpServers?: BriefMcp[];
  knowledge?: BriefKnowledge[];
  secrets?: BriefSecret[];
  channels?: BriefChannel[];
  evalSet?: BriefEvalSet;
  rehearsal?: { personas?: { name: string; traits: string[]; opening?: string }[] };
  narrative: { whyAgentic: string; perNode: Record<string, BriefNarrativeEntry> };
}

// ---- readiness ----------------------------------------------------------

export type ReadinessKind = "SECRET" | "CONNECTOR" | "KNOWLEDGE" | "CHANNEL" | "PLAN_LIMIT" | "PERMISSION" | "GUARDRAIL";
export type ReadinessState = "READY" | "MISSING" | "WAITING_ON_ADMIN" | "GATED" | "SIMULATED";

export interface ReadinessRow {
  kind: ReadinessKind;
  ref: string;
  label: string;
  state: ReadinessState;
  reason: string;
  actionHref?: string | null;
  unlocksAt?: string | null;
}

// ---- API shapes ---------------------------------------------------------

export interface PlanRequest {
  prompt: string;
  locale?: string;
  context?: PlanContext;
  /** A ReasoningDepth name; core applies it to workflow-graph generation (default FAST). */
  depth?: ReasoningDepth;
}

/** Optional chips under the brief box, folded into `context` for the planner. */
export interface PlanContext {
  audience?: "ME" | "TEAM" | "CUSTOMERS";
  channels?: ChannelKind[];
  mustNeverDo?: string;
  onDeviceOnly?: boolean;
}

export interface PlanResult {
  brief: Brief;
  readiness: ReadinessRow[];
  warnings: string[];
}

export type PlanStreamEvent =
  | { type: "thinking"; text: string }
  | { type: "section"; name: string; data?: unknown }
  | { type: "done"; result: PlanResult }
  | { type: "error"; message: string };

export interface ReadinessSummary {
  ready: number;
  missing: number;
  waitingOnAdmin: number;
  gated: number;
  simulated: number;
}

export type BriefStatus = "DRAFT" | "APPLIED" | "FAILED" | "ARCHIVED";

export interface BriefListItem {
  id: string;
  title: string;
  rung: Rung;
  status: BriefStatus;
  currentVersion?: number;
  /** First eval score; null unless measured. */
  baseline: number | null;
  /** Last run minus the run before it; null unless measured. Not qualified as significant here. */
  lastDelta: number | null;
  /** Core always sends null (it needs a live vault lookup per row); the card shows no dots then. */
  readinessSummary: ReadinessSummary | null;
  updatedAt?: string;
}

export interface BriefBinding {
  ref: string;
  kind: string;
  entityId: string;
}

export interface BriefVersionSummary {
  version: number;
  parentVersion: number | null;
  note?: string | null;
  createdBy?: string | null;
  createdAt: string;
}

export interface BriefDetail {
  id?: string;
  brief: Brief;
  readiness: ReadinessRow[];
  rung: Rung;
  status?: BriefStatus;
  versions?: BriefVersionSummary[];
  /** Once applied: which real entity each ref became. */
  bindings?: BriefBinding[];
  ledgerSummary?: LedgerSummary | null;
}

export type ApplyStepStatus = "PENDING" | "DONE" | "FAILED" | "COMPENSATED";

export interface ApplyStep {
  seq: number;
  kind: string;
  ref: string;
  entityId?: string | null;
  status: ApplyStepStatus;
  /** `SKIPPED: ...` on a DONE step = degraded (not created); `NOTE: ...` = informational. */
  error?: string | null;
}

export type ApplyJobStatus = "RUNNING" | "SUCCEEDED" | "FAILED";

export interface ApplyJob {
  jobId?: string;
  status: ApplyJobStatus;
  version?: number;
  error?: string | null;
  failedStep?: ApplyStep | null;
  steps: ApplyStep[];
  /** Entities a failed apply could not remove again. */
  orphans?: ApplyStep[];
}

// ---- proof ledger, tuning, trust (core as-built, contract section 6) -----

/** RFC 6902 operation as returned in `tune.diff`. */
export interface JsonPatchOp {
  op: "add" | "remove" | "replace" | "move" | "copy" | "test";
  path: string;
  from?: string;
  value?: unknown;
}

export interface TuneResult {
  proposedBrief: Brief;
  diff: JsonPatchOp[];
  /** Planner prose in the user's locale; rendered as written, never through t(). */
  narrative: string;
  /** Sent back with `proposedBrief` to apply, so a stale proposal is refused (409). */
  baseVersion: number;
}

/** Only COLLECTING (thumbs) and READY carry numbers; the other states carry a reason and nothing else. */
export type LedgerState = "LOCKED" | "NOT_APPLIED" | "NO_EVAL_SET" | "COLLECTING" | "READY";

export type LedgerChange = "BASELINE" | "SIGNIFICANT_UP" | "SIGNIFICANT_DOWN" | "WITHIN_NOISE" | "UNKNOWN_NOISE";

export interface LedgerRun {
  version: number;
  runId?: string;
  at?: string;
  /** 0..1. */
  score: number;
  passThreshold?: number;
  verdict?: "PASSED" | "FAILED" | "INCONCLUSIVE";
  /** Measured run-to-run noise, in score units; null when not measured. */
  noiseFloor: number | null;
  /** Score minus previous run; null for the first run. */
  deltaVsPrev: number | null;
  /** True only when a noise floor was measured AND the delta is larger than it. */
  significant: boolean;
  change?: LedgerChange;
}

export interface LedgerThumbs {
  up: number;
  down: number;
  rated: number;
  /** null when nothing was rated. */
  ratio: number | null;
}

export interface ExperimentArm {
  agentId: string;
  calls: number;
  feedback: number;
  thumbsUpRatePct: number | null;
  errorRatePct: number | null;
}

export interface LedgerExperiment {
  id: string;
  agentRef?: string;
  agentId?: string;
  name?: string;
  status: string;
  createdAt?: string;
  control?: ExperimentArm | null;
  treatment?: ExperimentArm | null;
  /** Only when both arms have enough rated turns; delta = control minus treatment thumbs-up rate, as a fraction. */
  comparison?: { significant: boolean; delta: number; summary: string } | null;
}

export interface TrustEvidenceLine {
  key: string;
  /** Sentence core wrote for this check, shown as written. */
  label: string;
  met: boolean;
  detail?: string | null;
}

export interface TrustEvent {
  kind: "PROPOSED" | "PROMOTED" | "DEMOTED" | "REJECTED";
  fromRung: Rung | null;
  toRung: Rung | null;
  version?: number | null;
  /** "system" for an automatic demotion. */
  actor?: string | null;
  at: string;
  /** `{ lines: TrustEvidenceLine[], note?, snapshot? }`; read through trustEventView(). */
  evidence?: unknown;
}

export interface LedgerBaseline {
  runId?: string;
  score: number;
  at?: string;
  version?: number;
}

export type EvalDimension = "helpfulness" | "accuracy" | "tone" | "length" | "relevance" | "safety";

/** Core does not serve a per-dimension breakdown yet; the coach uses it only if one ever arrives. */
export interface LedgerBreakdownItem {
  agentRef: string;
  dimension: EvalDimension;
  score: number;
  n?: number;
}

export interface BriefLedger {
  briefId?: string;
  state: LedgerState;
  /** A human sentence for every state but READY. */
  reason?: string | null;
  rung?: Rung;
  baseline: LedgerBaseline | null;
  noiseFloor: number | null;
  /** Oldest first. */
  runs: LedgerRun[];
  thumbs: LedgerThumbs | null;
  experiments: LedgerExperiment[];
  /** Oldest first. */
  trustEvents: TrustEvent[];
  breakdown?: LedgerBreakdownItem[];
}

export interface LedgerSummary {
  state: LedgerState;
  reason?: string | null;
  baseline: number | null;
  latest: number | null;
  lastDelta: number | null;
  lastDeltaSignificant: boolean;
  runs: number;
  noiseFloor: number | null;
}

export interface TrustProposal {
  eligible: boolean;
  currentRung?: Rung;
  /** null when there is nothing to propose (at the top, plan has no eval runs, no ledger, not applied). */
  targetRung: Rung | null;
  evidence: TrustEvidenceLine[];
  /** RFC 6902 patch the promotion would make to the manifest. */
  diff: JsonPatchOp[];
  /** Plain sentences on what changes in the running envoy. */
  effects: string[];
}

export interface TrustChange {
  kind: "PROMOTED" | "DEMOTED";
  fromRung: Rung;
  toRung: Rung;
  version: number;
  effects: string[];
  evidence: TrustEvidenceLine[];
}

export interface RehearsalStarted {
  label: "rehearsal";
  agentRef: string;
  agentId: string;
  sessionIds: string[];
  personas: string[];
  note?: string;
}

export interface RehearsalRunView {
  runId: string;
  persona: string;
  status: string;
  verdict: "PASSED" | "FAILED" | "INCONCLUSIVE" | null;
  verdictReason: string | null;
  turns: number;
}

export interface RehearsalSession {
  label: "rehearsal";
  sessionId: string;
  status: string;
  createdAt: string;
  runs: RehearsalRunView[];
}
