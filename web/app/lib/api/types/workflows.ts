/** WorkflowResponse (core/workflow). `graph` is the WorkflowGraphDto wire shape - see fromWireGraph. */
export interface WorkflowResponse {
  id: string;
  name: string;
  description: string | null;
  status: string;
  graph: unknown;
  createdAt: string;
  updatedAt: string;
  editable: boolean;
  reasoningDepth: string | null;
  tuningMode: string | null;
  stepCachingEnabled: boolean;
  costRoutingEnabled: boolean;
  economyProviderName: string | null;
  economyModel: string | null;
  maxStepsPerRun: number;
  stepTimeoutSeconds: number;
  maxRetryAttempts: number;
}

/** WorkflowRunResponse (core/workflow). `trace` entries are NodeTraceEntry. */
export interface WorkflowRunResponse {
  id: string;
  workflowId: string;
  workflowName: string | null;
  status: string;
  requestedBy: string | null;
  errorMessage: string | null;
  trace: unknown[] | null;
  startedAt: string;
  completedAt: string | null;
  outputFileName: string | null;
  workflowVersion: number | null;
}

/** WorkflowScheduleResponse. */
export interface WorkflowScheduleResponse {
  cron: string | null;
  enabled: boolean;
  nextRunAt: string | null;
}

/** POST /api/workflows/{id}/validate. */
export interface WorkflowValidationResult {
  valid: boolean;
  errors: string[];
}

/** GenerateGraphResponse - an AI-drafted graph plus reviewer notes. */
export interface GenerateGraphResponse {
  graph: unknown;
  notes: string[] | null;
}

/** WorkflowDiagramResponse. */
export interface WorkflowDiagramResponse {
  workflowId: string;
  workflowName: string | null;
  format: string;
  source: string;
}

/** A trigger node's channel binding (GET/PUT .../trigger-channel). */
export interface NodeTriggerChannel {
  channel: string | null;
  webhookAuthMode: string | null;
  oidcTriggerPath: string | null;
}

/** A trigger node's webhook-signature state (GET/PUT .../webhook/signature) - the secret is write-only. */
export interface NodeWebhookSignature {
  nodeId: string;
  mode: string;
  configured: boolean;
}

/** A trigger node's HTTP poll binding (GET/PUT .../poll-trigger). Never carries the stored headers. */
export interface NodePollTrigger {
  nodeId: string;
  configured: boolean;
  url: string | null;
  method: string | null;
  intervalSeconds: number | null;
  itemsPath: string | null;
  idPath: string | null;
  seedOnEnable: boolean;
  enabled: boolean;
  headersConfigured: boolean;
  nextPollAt: string | null;
  lastPolledAt: string | null;
  lastStatus: string | null;
  consecutiveFailures: number;
}

/** PUT body for .../poll-trigger (headers are write-only - stored encrypted server-side). */
export interface NodePollTriggerInput {
  url: string;
  method?: string;
  headers?: Record<string, string>;
  intervalSeconds?: number;
  itemsPath?: string;
  idPath?: string;
  seedOnEnable?: boolean;
}
