import { apiFetch, toJson, toJsonQuiet, jsonBody } from "../api";

// Skill supply-chain review (core com.agenticai.core.skills.SkillSupplyChainController,
// /api/skill-security/**). Same endpoints and shapes web/app/lib/api/skillSecurity.ts
// uses in the tenant console; platform-admin satisfies the controller's
// requireWorkspaceAdmin check (ROLE_platform-admin), so approve works here too.
//
// SCOPE: the controller resolves the tenant from the caller's own TenantContext
// and accepts no tenant id, so these calls review the skills of the tenant the
// signed-in operator's account belongs to - NOT a cross-tenant queue. The page
// says so. With app.skills.supply-chain.enabled=false every endpoint is 404.

export type SkillRiskLevel = "none" | "low" | "medium" | "high";
export type SkillPinStatus = "BUILT_IN" | "UNPINNED" | "APPROVED" | "NEEDS_REVIEW";

export interface SkillFinding {
  rule: string;
  severity: "low" | "medium" | "high";
  excerpt: string;
  where: string;
}

export interface SkillScanReport {
  riskLevel: SkillRiskLevel;
  findings: SkillFinding[];
  truncated: boolean;
  scannerVersion: number;
}

export interface SkillSecurityReport {
  name: string;
  source: "BUILT_IN" | "CUSTOM";
  status: SkillPinStatus;
  blocked: boolean;
  enforcing: boolean;
  contentSha256: string;
  approvedSha256: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  changes: string[];
  scan: SkillScanReport;
  stagedScan: SkillScanReport | null;
  attachedAgentIds: string[];
  disclaimer: string;
}

export interface SkillSecuritySummary {
  name: string;
  source: "BUILT_IN" | "CUSTOM";
  status: SkillPinStatus;
  riskLevel: SkillRiskLevel;
  findingCount: number;
  blocked: boolean;
}

export interface SkillSecurityOverview {
  enabled: boolean;
  enforcing: boolean;
  skills: SkillSecuritySummary[];
}

export interface SkillReviewItem {
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
}

export const skillSecurityApi = {
  summary: (): Promise<SkillSecurityOverview> =>
    apiFetch(`/api/skill-security/summary`, { quiet: true }).then(toJsonQuiet),
  reviews: (): Promise<SkillReviewItem[]> => apiFetch(`/api/skill-security/reviews`, { quiet: true }).then(toJsonQuiet),
  report: (name: string): Promise<SkillSecurityReport> =>
    apiFetch(`/api/skill-security/skills/${encodeURIComponent(name)}/report`).then(toJson),
  /** 409 when the skill changed after the reviewer loaded it (expectedSha256 mismatch). */
  approve: (name: string, expectedSha256: string, acknowledgeHighRisk: boolean): Promise<SkillSecurityReport> =>
    apiFetch(`/api/skill-security/skills/${encodeURIComponent(name)}/approve`, {
      method: "POST",
      ...jsonBody({ expectedSha256, acknowledgeHighRisk }),
    }).then(toJson),
};
