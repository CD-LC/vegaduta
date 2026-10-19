// ===================================================================
// Community template gallery (2026-07-30)
// -------------------------------------------------------------------
// Tenant-facing gallery of member-submitted agent/workflow templates that are
// moderated before appearing publicly. Shapes below are the BACKEND-CONFIRMED
// surface (final routes/field names verified against the Java source; 14/14
// core tests green) - see
// docs/COMMUNITY-TEMPLATES-CONTRACT-2026-07-30.md. Enums are UPPERCASE to
// match the Java enums exactly; every response field is camelCase (there is
// no snake_case or `config_json` on the wire).
//
//   GET  /api/templates/gallery        - paginated APPROVED list (public, no auth)
//   GET  /api/templates/gallery/{id}   - one approved template + `config` (public)
//   POST /api/templates                - create a DRAFT (no submit-on-create)
//   PUT  /api/templates/{id}           - edit my own DRAFT/REJECTED
//   POST /api/templates/{id}/submit    - DRAFT/REJECTED -> PENDING
//   POST /api/templates/{id}/withdraw  - owner pulls their own
//   POST /api/templates/{id}/import    - instantiate into MY tenant
//   GET  /api/templates/mine           - my submissions with statuses
//
// Callers still degrade gracefully (loading / empty / error) on every fetch.
export type CommunityTemplateKind = "AGENT" | "WORKFLOW";
// Five states, per the contract's lifecycle diagram. WITHDRAWN is its own
// state (was public, then pulled by the owner or an admin) - NOT a reversion
// to DRAFT, and the contract does not state it is resubmittable, so the UI
// deliberately offers no resubmit affordance for it.
export type CommunityTemplateStatus = "DRAFT" | "PENDING" | "APPROVED" | "REJECTED" | "WITHDRAWN";

// Public gallery card shape. `importCount` is the ONE real usage number the
// API returns; the UI never fabricates any other count/rating. Note there is
// deliberately NO author/submitter field here - the snapshot strips
// created_by subjects, so the public surface cannot attribute a template to a
// person even if it wanted to.
export interface CommunityTemplateSummary {
  id: string;
  kind: CommunityTemplateKind;
  title: string;
  summary: string;
  category: string; // canonical category id - see components/templates/categories
  tags: string[];
  importCount: number;
  version: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

// The sanitised snapshot, as served on the detail endpoints. A plain JSON
// object (Jackson JsonNode), NOT a string to parse. Its internal key names
// are NOT part of the confirmed contract, so this stays an index signature
// and the detail view probes it defensively - an unrecognised shape renders
// as "not specified" or falls into the generic key/value listing rather than
// throwing or silently hiding content.
export interface CommunityTemplateConfig {
  [key: string]: unknown;
}

export interface CommunityTemplateDetail extends CommunityTemplateSummary {
  config?: CommunityTemplateConfig | null;
  // What an importer must activate, derived server-side from the config
  // (TemplateReadinessService#requirements) so every template reports it.
  requirements?: TemplateRequirement[];
}

export type TemplateRequirementType =
  | "MCP_SERVER"
  | "GOOGLE_CONNECTION"
  | "KNOWLEDGE_BASE"
  | "CODE_INTEL"
  | "CODE_SANDBOX"
  | "MEMORY"
  | "HUMAN_APPROVAL"
  | "MODEL_KEY"
  | "VOICE_MODEL"
  | "VIDEO_MODEL"
  | "BUILT_IN_TOOL";

export interface TemplateRequirement {
  type: TemplateRequirementType;
  id: string | null;
  label: string;
  detail: string;
  required: boolean;
  /** In-app route where the importer activates it; null when it is built in. */
  setupPath: string | null;
}

export interface TemplateIssue {
  code: string;
  principle: string;
  message: string;
  fix: string;
}

export interface TemplateReadinessReport {
  ready: boolean;
  blockers: TemplateIssue[];
  advisories: TemplateIssue[];
  requirements: TemplateRequirement[];
  derivedTags: string[];
}

export interface TemplateAiReview {
  status: "PASSED" | "ISSUES" | "UNAVAILABLE";
  summary: string;
  blockers: TemplateIssue[];
  advisories: TemplateIssue[];
}

export interface TemplateReview {
  standardVersion: number;
  rules: TemplateReadinessReport;
  ai: TemplateAiReview;
  reviewedAt: string;
}

export interface TemplateStandardRule {
  code: string;
  principle: string;
  severity: "BLOCKER" | "ADVISORY";
  checker: "RULES" | "AI";
  requirement: string;
  howToFix: string;
}

export interface TemplateStandardGuide {
  version: number;
  intro: string;
  steps: string[];
  principles: { name: string; why: string }[];
  rules: TemplateStandardRule[];
  examplePrompt: string;
  exampleSummary: string;
}

// Caller's own submission - the full record, adding the lifecycle +
// moderation fields the public gallery never exposes.
export interface CommunityTemplateSubmission extends CommunityTemplateDetail {
  status: CommunityTemplateStatus;
  rejectReason?: string | null;
  sourceTenantId?: string | null;
  submittedBy?: string | null;
  submittedAt?: string | null;
  reviewedBy?: string | null;
  reviewedAt?: string | null;
  // Rules + AI review captured when it was submitted (null for older templates).
  review?: TemplateReview | null;
}

// The gallery endpoint (GET /api/templates/gallery) returns a bare
// TemplateSummary[] - no count/total, no page envelope. This shape is
// constructed CLIENT-SIDE (see wrapGalleryPage below) so the rest of the app
// has something structured to hold onto, but `hasMore` is a heuristic
// ("we got a full page, there might be another"), not a real count - there is
// deliberately no `total`/`totalPages` field, because the API cannot honestly
// provide one without a second counting query it doesn't do. Do not add one
// back without also adding it server-side; a fabricated total is worse than
// none (see this repo's standing no-fiction rule).
export interface CommunityTemplatePage {
  items: CommunityTemplateSummary[];
  page: number;
  pageSize: number;
  hasMore: boolean;
}

export interface CommunityTemplateListParams {
  kind?: CommunityTemplateKind;
  category?: string;
  tag?: string;
  page?: number;
  pageSize?: number;
}

// POST /api/templates body. The server does the sanitising snapshot itself,
// so the client supplies only the source pointer + descriptive metadata and
// NEVER any config. There is deliberately no `submit` flag: create always
// yields a DRAFT, and submitting for review is a second explicit call to
// POST /api/templates/{id}/submit (backend-confirmed 2026-07-30).
export interface CommunityTemplateCreatePayload {
  kind: CommunityTemplateKind;
  sourceId: string;
  title: string;
  summary: string;
  category: string;
  tags: string[];
}

export interface CommunityTemplateEditPayload {
  title?: string;
  summary?: string;
  category?: string;
  tags?: string[];
}

// POST /api/templates/{id}/import response: ONE id field, whose meaning
// depends on `kind` (an agent id when kind is AGENT, a workflow id when
// WORKFLOW).
export interface CommunityTemplateImportResult {
  kind: CommunityTemplateKind;
  resourceId: string;
}
