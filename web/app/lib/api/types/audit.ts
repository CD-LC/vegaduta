/** One row of GET /api/agents/{id}/tool-calls (ToolCallLogView). */
export interface ToolCallLogEntry {
  id: string;
  sessionId: string | null;
  toolName: string;
  toolInput: string | null;
  toolOutput: string | null;
  errorMessage: string | null;
  startedAt: string;
  completedAt: string | null;
  mcpGrantId: string | null;
}

/** One LLM call of a chat session / quick task (SessionLlmCallEntry). */
export interface SessionLlmCallEntry {
  id: string;
  agentId: string | null;
  sessionId: string;
  providerName: string;
  model: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  estimatedCostUsd: number | null;
  success: boolean;
  errorMessage: string | null;
  latencyMs: number;
  startedAt: string;
}

/**
 * One row of GET /api/agents/{agentId}/sessions/{sessionId}/llm-usage
 * (AgentSessionLlmUsageController.SessionLlmCallEntry). Leaner than the admin row above on
 * purpose: no agentId/sessionId (both are in the path) and no errorMessage (failure detail
 * stays on the admin surface).
 */
export type AgentSessionLlmCallEntry = Omit<SessionLlmCallEntry, "agentId" | "sessionId" | "errorMessage">;

/** ApprovalRequestResponse (core/governance). */
export interface ApprovalRequestResponse {
  id: string;
  actionType: string;
  summary: string | null;
  status: string;
  requestedBy: string | null;
  decidedBy: string | null;
  rejectionReason: string | null;
  executionError: string | null;
  createdAt: string;
  decidedAt: string | null;
  retryCount: number;
  nextRetryAt: string | null;
  payload: string | null;
  executionResult: string | null;
}

/** One audit-log row. */
export interface AuditLogEntry {
  id: string;
  createdAt: string;
  actorId: string | null;
  actionType: string;
  targetType: string | null;
  targetId: string | null;
  outcome: string;
  detail: string | null;
}

/** A workflow human-input request (Input node). */
export interface InputRequestResponse {
  id: string;
  runId: string;
  workflowId: string;
  workflowName: string | null;
  nodeId: string | null;
  nodeLabel: string | null;
  formSchema: {
    message?: string;
    fields?: { key: string; label?: string; type?: string; required?: boolean; options?: string[] }[];
  } | null;
  status: string;
  requestedBy: string | null;
  createdAt: string | null;
  submittedValues: Record<string, unknown> | null;
  submittedBy: string | null;
  submittedAt: string | null;
}

/** GuardrailRuleResponse (core/guardrails). */
export interface GuardrailRuleResponse {
  id: string;
  tenantId: string | null;
  ruleType: "DEFAULT_TEMPLATE" | "CUSTOM";
  name: string;
  description: string | null;
  pattern: string;
  action: "BLOCK" | "REDACT";
  enabled: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

/** GET/PATCH /api/agents/{id}/guardrails. */
export interface AgentGuardrailsResponse {
  enabledGuardrails: string[];
  availableGuardrails: string[];
  lockedGuardrails: string[];
  guardrailDescriptions: Record<string, string>;
}

/** One RL bandit policy (RlController policy DTO). */
export interface RlPolicy {
  dimension: string;
  enabled: boolean;
  explorationRate: number;
  algorithm?: string | null;
}
