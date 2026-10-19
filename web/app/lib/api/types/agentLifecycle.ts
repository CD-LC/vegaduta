/** AgentVersionResponse (core/agents). `config` is the AgentConfigSnapshot. */
export interface AgentVersionResponse {
  id: string;
  agentId: string;
  versionNumber: number;
  agentName: string;
  providerName: string;
  model: string | null;
  changeSummary: string | null;
  label: "STAGING" | "PRODUCTION" | null;
  createdBy: string | null;
  createdAt: string;
  config?: unknown;
}

/** Public share-link state of an agent or workflow (ShareStatusResponse). */
export interface ShareStatusResponse {
  active: boolean;
  shareId: string | null;
  shareUrl: string | null;
  viewerCount: number;
  reportCount: number;
}

/** A user row of the tenant admin's user list (UserAdminController). */
export interface AdminUserResponse {
  id: string;
  username: string;
  email?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  enabled: boolean;
  roles: string[];
  permissions?: string[];
  /** Has not chosen a password yet (an open invite, or an unused temporary password). */
  invitePending?: boolean;
  /** Only on an invite / re-issue where there is no mail server: the one-time link to pass on. */
  inviteLink?: string | null;
}

/** One AI Model Control row (AiModelPreferenceResponse). */
export interface AiModelPreferenceResponse {
  featureKey: string;
  featureLabel?: string;
  providerName?: string;
  model?: string;
}

/** AgentJobResponse (core/agents jobs). */
export interface AgentJobResponse {
  id: string;
  agentId: string;
  goal: string;
  status: string;
  terminal: boolean;
  origin: string | null;
  originRef: string | null;
  sessionId: string | null;
  requestedBy: string | null;
  progressNote: string | null;
  progressUpdatedAt: string | null;
  resultText: string | null;
  resultJson: unknown;
  schemaAttempts: number | null;
  costUsd: number | null;
  errorCode: string | null;
  errorMessage: string | null;
  timeoutSeconds: number;
  deadlineAt: string | null;
  turnsUsed: number;
  maxTurns: number;
  cancelRequested: boolean;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  scheduleId: string | null;
  scheduleOccurrenceAt: string | null;
  parkedAt: string | null;
  approvalDeadlineAt: string | null;
  parkCount: number;
}

/** AgentJobSummaryResponse: a job list row (goal cut to a 160-character goalPreview, no result). */
export interface AgentJobSummaryResponse {
  id: string;
  agentId: string;
  agentName: string | null;
  status: string;
  goalPreview: string | null;
  progressNote: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  errorCode: string | null;
  parkedAt: string | null;
  approvalDeadlineAt: string | null;
}

/** AgentScheduleRunResponse. */
export interface AgentScheduleRunResponse {
  id: string;
  trigger: string;
  targetType: string;
  status: string;
  jobStatus: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  jobId: string | null;
  workflowRunId: string | null;
  error: string | null;
}

/** An MCP server grant (the platform-as-MCP-server feature). */
export interface McpServerGrantResponse {
  id: string;
  name: string;
  enabled: boolean;
  apiKeyDisplayPrefix: string;
  exposedAgentIds: string[];
  exposedWorkflowIds: string[];
  exposedKnowledgeCollectionIds: string[];
  createdAt: string;
  lastUsedAt: string | null;
}

/** Create/rotate result: the grant plus its one-time raw key. */
export interface McpServerGrantCreatedResponse {
  grant: McpServerGrantResponse;
  rawApiKey: string;
}

/** An OAuth "connect" redirect. */
export interface AuthorizeUrlResponse {
  url: string;
}

/** FaceEnrollmentController row - a stored face embedding for one person. */
export interface FaceEnrollmentResponse {
  id: string;
  personName: string;
  embedding: number[];
  createdAt: string;
}
