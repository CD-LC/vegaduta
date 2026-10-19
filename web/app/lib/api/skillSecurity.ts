import { apiFetch, jsonBody, toJson, toJsonQuietOn } from "./core";
import { errorMessage } from "../errors";

// Skill supply-chain API (core com.agenticai.core.skills.SkillSupplyChainController,
// base path /api/skill-security). A skill is third-party text that ends up in an
// agent's context, so each custom skill is statically scanned and its content hash
// is pinned when an admin approves it. A scan reduces risk; it never proves safety.
//
// Reads are quiet: 403/404/501 mean "feature off or not available to you", so the
// getters resolve to null instead of toasting - the skills page simply shows no
// security UI. Writes (approve, previews) use the normal toasting toJson.

export type SkillRiskLevel = "none" | "low" | "medium" | "high";
export type SkillFindingSeverity = "low" | "medium" | "high";

export type SkillFinding = {
  rule: string;
  severity: SkillFindingSeverity;
  /** At most 120 characters, control/invisible characters already shown as \u{XXXX}. */
  excerpt: string;
  /** "name" | "description" | "body" | "file:<path>" | "file-name:<path>" */
  where: string;
};

export type SkillScanReport = {
  riskLevel: SkillRiskLevel;
  findings: SkillFinding[];
  truncated: boolean;
  scannerVersion: number;
};

export type SkillPinStatus = "BUILT_IN" | "UNPINNED" | "APPROVED" | "NEEDS_REVIEW";

export type SkillSecurityReport = {
  name: string;
  source: "BUILT_IN" | "CUSTOM";
  status: SkillPinStatus;
  /** True when enforcement is on and load_skill currently refuses this skill. */
  blocked: boolean;
  enforcing: boolean;
  contentSha256: string;
  approvedSha256: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  /** What differs from the approved version; empty unless status is NEEDS_REVIEW. */
  changes: string[];
  scan: SkillScanReport;
  /** Scan of an agent-proposed rewrite waiting for review, if one is staged. */
  stagedScan: SkillScanReport | null;
  attachedAgentIds: string[];
  disclaimer: string;
};

export type SkillSecuritySummary = {
  name: string;
  source: "BUILT_IN" | "CUSTOM";
  status: SkillPinStatus;
  riskLevel: SkillRiskLevel;
  findingCount: number;
  blocked: boolean;
};

export type SkillSecurityOverview = {
  enabled: boolean;
  enforcing: boolean;
  skills: SkillSecuritySummary[];
};

export type SkillReviewItem = {
  skillId: string;
  name: string;
  reason: "CHANGED" | "HIGH_RISK";
  status: SkillPinStatus;
  riskLevel: SkillRiskLevel;
  changes: string[];
  approvedAt: string | null;
  approvedBy: string | null;
  enabled: boolean;
  attachedAgentIds: string[];
};

/** Scan of a candidate skill (draft, upload or registry) before anything is saved. */
export type SkillScanPreview = {
  name: string;
  replacesExisting: boolean;
  scan: SkillScanReport;
  disclaimer: string;
  /** Canonical hash of what was scanned. The registry install sends it back; a source that changed in between is refused (409). */
  contentSha256: string | null;
};

/**
 * Flat on purpose (tsconfig is not strict, so boolean-discriminated unions do not narrow):
 * exactly one of `preview` / `disabled` / `error` is meaningful.
 */
export type SkillPreviewOutcome = {
  preview: SkillScanPreview | null;
  /** Scanning is switched off server-side (404): the caller proceeds without a scan step. */
  disabled: boolean;
  error: string | null;
};

const QUIET_STATUSES = [403, 404, 501];

async function runPreview(call: () => Promise<Response>): Promise<SkillPreviewOutcome> {
  try {
    const res = await call();
    if (res.status === 404) return { preview: null, disabled: true, error: null };
    const preview = await toJson<SkillScanPreview>(res);
    return { preview, disabled: false, error: null };
  } catch (e: unknown) {
    return { preview: null, disabled: false, error: errorMessage(e, "Could not scan this skill.") };
  }
}

export const skillSecurityApi = {
  /** Status + risk for every skill, for list badges. null when scanning is off or unavailable. */
  getSkillSecurityOverview: async (): Promise<SkillSecurityOverview | null> => {
    try {
      const overview: SkillSecurityOverview | null = await apiFetch(`/api/skill-security/summary`).then(
        toJsonQuietOn<SkillSecurityOverview | null>(QUIET_STATUSES),
      );
      return overview && overview.enabled ? overview : null;
    } catch {
      return null;
    }
  },

  getSkillSecurityReport: async (name: string): Promise<SkillSecurityReport | null> => {
    try {
      return await apiFetch(`/api/skill-security/skills/${encodeURIComponent(name)}/report`).then(
        toJsonQuietOn<SkillSecurityReport | null>(QUIET_STATUSES),
      );
    } catch {
      return null;
    }
  },

  listSkillReviews: async (): Promise<SkillReviewItem[]> => {
    try {
      const items: SkillReviewItem[] | null = await apiFetch(`/api/skill-security/reviews`).then(
        toJsonQuietOn<SkillReviewItem[] | null>(QUIET_STATUSES),
      );
      return Array.isArray(items) ? items : [];
    } catch {
      return [];
    }
  },

  /** Admin only. 409 = the skill changed again while the reviewer was looking; 400 = high risk needs the acknowledgement. */
  approveSkill: (
    name: string,
    body: { expectedSha256?: string; acknowledgeHighRisk?: boolean }
  ): Promise<SkillSecurityReport> =>
    apiFetch(`/api/skill-security/skills/${encodeURIComponent(name)}/approve`, {
      method: "POST",
      ...jsonBody(body),
    }).then(toJson<SkillSecurityReport>),

  previewSkillDraft: (skillMd: string): Promise<SkillPreviewOutcome> =>
    runPreview(() => apiFetch(`/api/skill-security/preview/draft`, { method: "POST", ...jsonBody({ skillMd }) })),

  // No Content-Type header on purpose: the browser must set the multipart boundary itself.
  previewSkillArchive: (file: File): Promise<SkillPreviewOutcome> =>
    runPreview(() => {
      const form = new FormData();
      form.append("file", file);
      return apiFetch(`/api/skill-security/preview/archive`, { method: "POST", body: form });
    }),

  previewRegistrySkill: (githubUrl: string): Promise<SkillPreviewOutcome> =>
    runPreview(() =>
      apiFetch(`/api/skill-security/preview/registry`, { method: "POST", ...jsonBody({ githubUrl }) })
    ),
};
