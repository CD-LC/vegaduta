import type { SimulationRun } from "./simulation";

export type OpenApiAuthType = "NONE" | "API_KEY_HEADER" | "BEARER" | "OAUTH2_CLIENT_CREDENTIALS";

/** An imported OpenAPI tool source. */
export interface OpenApiToolSource {
  id: string;
  name: string;
  toolPrefix: string;
  specUrl?: string | null;
  specTitle?: string | null;
  specVersion?: string | null;
  baseUrl: string;
  authType: OpenApiAuthType;
  authHeaderName?: string | null;
  oauth2TokenUrl?: string | null;
  oauth2ClientId?: string | null;
  oauth2Scope?: string | null;
  oauth2TokenStyle?: string | null;
  hasSecret: boolean;
  secretPreview?: string | null;
  enabled: boolean;
  toolCount: number;
  enabledToolCount: number;
  lastRefreshedAt?: string | null;
  lastError?: string | null;
}

/** One operation of an OpenAPI tool source, exposed as a tool. */
export interface OpenApiTool {
  id: string;
  sourceId: string;
  toolName: string;
  operationKey: string;
  operationId?: string | null;
  httpMethod: string;
  pathTemplate: string;
  summary?: string | null;
  description?: string | null;
  bodyMode: string;
  inputSchemaJson?: string | null;
  enabled: boolean;
  deprecated: boolean;
}

/** Dry-run parse of an OpenAPI spec before import. */
export interface OpenApiSpecPreview {
  title?: string | null;
  version?: string | null;
  description?: string | null;
  serverUrl?: string | null;
  suggestedBaseUrl?: string | null;
  toolPrefix: string;
  operations: {
    operationKey: string;
    suggestedToolName: string;
    operationId?: string | null;
    method: string;
    path: string;
    summary?: string | null;
    description?: string | null;
    deprecated: boolean;
    bodyMode: string;
    inputSchemaJson?: string | null;
  }[];
}

/** A tool test run: the JSON the tool returned (OpenAPI tools, user tool scripts). */
export interface ToolTestResult {
  resultJson: string | null;
  [key: string]: unknown;
}

/** A custom code tool test execution. */
export interface CodeToolTestResult {
  ok: boolean;
  result: unknown;
  stdout: string;
  error: string;
  errorType: string;
  durationMs: number;
}

/** SimulationDtos.RunDetailResponse - a run plus its transcript. */
export interface SimulationRunDetail extends SimulationRun {
  transcriptJson: string | null;
  agentVersionId: string | null;
}

/** A tenant MCP server registration (GET /api/mcp/registry). */
export interface McpServerResponse {
  id: string;
  name: string;
  type: string;
  description?: string;
  enabled: boolean;
  dynamic: boolean;
  connectionStatus?: string;
  usageStatus?: string;
  command?: string;
  url?: string;
  args?: string;
}

/** A channel contact group. */
export interface ContactGroup {
  id: string;
  name: string;
}

/** FeatureFlagEvaluationResponse - the tenant's variant for one flag. */
export interface FeatureFlagEvaluation {
  key: string;
  variant: string;
}
