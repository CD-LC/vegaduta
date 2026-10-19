// Shared TypeScript types for the Community Templates moderation queue
// (docs/COMMUNITY-TEMPLATES-CONTRACT-2026-07-30.md). Backend (core-3,
// controlplane.community_template) confirmed live and verified 2026-07-30
// (14/14 tests, read directly from source by the coordinator) - camelCase
// throughout, no snake_case anywhere in the actual API. The full-detail field
// is named `config` (NOT `configJson` - an earlier guess corrected once the
// real backend landed).
//
// Field-name choices for the config's inner shape (systemPrompt,
// providerName, allowedToolNames, enabledGuardrails, etc.) follow the
// existing camelCase Jackson convention this codebase already uses for
// Agent/Workflow DTOs - see core/agents/.../AgentResponse.java and
// core/workflow's WorkflowGraphDto/WorkflowNodeDto/WorkflowEdgeDto, which
// this file's Workflow types mirror directly since the workflow graph is
// deliberately React-Flow-shaped. Those inner field names are still this
// file's own best-effort mirror (not directly confirmed the way the outer
// TemplateSummary/admin-route shapes were) - if the real config shape lands
// differently, only this file and ConfigViewer.tsx's field lookups need to
// change.

export type TemplateKind = "AGENT" | "WORKFLOW";

export type TemplateStatus = "DRAFT" | "PENDING" | "APPROVED" | "REJECTED" | "WITHDRAWN";

// A sanitised snapshot of an Agent's design - never keys/secrets/tenant ids/
// PII (the snapshot allow-list is the security boundary per the contract).
export interface AgentTemplateConfig {
  systemPrompt?: string;
  providerName?: string;
  model?: string;
  textModelProvider?: string;
  textModel?: string;
  voiceModelProvider?: string;
  voiceModel?: string;
  videoModelProvider?: string;
  videoModel?: string;
  fallbackProviderName?: string;
  fallbackModel?: string;
  economyProviderName?: string;
  economyModel?: string;
  allowedToolNames?: string[];
  enabledGuardrails?: string[];
  // Shape only (name/description), never the tenant's actual documents -
  // per the contract's "knowledge-collection shape (not the documents)".
  knowledgeCollections?: { name: string; description?: string }[];
  autonomyTier?: string;
  reasoningDepth?: string;
  tuningMode?: string;
  experienceMode?: string;
  temperature?: number;
  topP?: number;
  maxTokens?: number;
}

// Mirrors core/workflow's WorkflowNodeDto/WorkflowEdgeDto verbatim (the graph
// is deliberately React-Flow-shaped) - node-specific behavior lives at
// data.config, a free-form map, and data.label is the human-readable name.
export interface WorkflowTemplateNode {
  id: string;
  type: string;
  position?: { x?: number; y?: number } | Record<string, number>;
  data?: { label?: string; config?: Record<string, any> } & Record<string, any>;
}

export interface WorkflowTemplateEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string | null;
  targetHandle?: string | null;
}

export interface WorkflowTemplateConfig {
  name?: string;
  description?: string;
  graph?: {
    nodes: WorkflowTemplateNode[];
    edges: WorkflowTemplateEdge[];
  };
}

export type TemplateConfig = AgentTemplateConfig | WorkflowTemplateConfig | Record<string, any>;

// A row from GET /api/admin/templates/pending - the full moderation-queue
// detail, config included. Every field beyond id/kind/title/status is
// optional so the UI degrades gracefully against a partial backend response
// rather than throwing on a missing key.
export interface CommunityTemplate {
  id: string;
  kind: TemplateKind;
  title: string;
  summary?: string;
  category?: string;
  tags?: string[];
  status: TemplateStatus;
  sourceTenantId?: string;
  sourceTenantName?: string;
  submittedBy?: string;
  submittedAt?: string;
  reviewedBy?: string;
  reviewedAt?: string;
  rejectReason?: string;
  importCount?: number;
  version?: number;
  createdAt?: string;
  updatedAt?: string;
  config?: TemplateConfig;
  // Submit-time review (rules + AI) and what importers must activate. Absent on templates that pre-date it.
  review?: TemplateReview | null;
  requirements?: TemplateRequirement[];
}

export interface TemplateIssue {
  code: string;
  principle: string;
  message: string;
  fix: string;
}

export interface TemplateRequirement {
  type: string;
  id: string | null;
  label: string;
  detail: string;
  required: boolean;
  setupPath: string | null;
}

export interface TemplateReview {
  standardVersion: number;
  rules: {
    ready: boolean;
    blockers: TemplateIssue[];
    advisories: TemplateIssue[];
    requirements: TemplateRequirement[];
    derivedTags: string[];
  };
  ai: {
    status: "PASSED" | "ISSUES" | "UNAVAILABLE";
    summary: string;
    blockers: TemplateIssue[];
    advisories: TemplateIssue[];
  };
  reviewedAt: string;
}

// A row from GET /api/admin/templates/approved - confirmed exact shape
// (coordinator read it directly from source): deliberately leaner than
// CommunityTemplate above. No config, no status/submitter/tenant/review
// fields - just enough to identify a published template before withdrawing
// it. Drives the Published tab's list.
export interface TemplateSummary {
  id: string;
  kind: TemplateKind;
  title: string;
  summary?: string;
  category?: string;
  tags?: string[];
  importCount?: number;
  version?: number;
  createdAt?: string;
  updatedAt?: string;
}
