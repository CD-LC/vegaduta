import { apiFetch, jsonBody, toJson, triggerBrowserDownload } from "./core";

// DPDP-oriented tenant controls (core/app common/privacy, PrivacyController): /api/tenant/privacy/**.
// All endpoints need MANAGE_DATA_GOVERNANCE. Deliberately NOT spread into the `api` barrel: only
// /admin/data-privacy reads this.

export const PRIVACY_BASE = "/api/tenant/privacy";

/** Mirrors ConsentPolicyService / PurposeService / RetentionPolicyService / AuditExportService. */
export const PURPOSE_PATTERN = /^[a-z0-9][a-z0-9_-]{0,63}$/;
export const LANGUAGE_TAG_PATTERN = /^[a-z]{2,3}(-[a-z0-9]{2,8})?$/;
export const MAX_NOTICE_CHARS = 3000;
export const MAX_RETENTION_DAYS = 3650;
export const DEFAULT_RETENTION_DAYS = 365;
export const MAX_AUDIT_RANGE_DAYS = 366;
export const ALL_CHANNELS = "*";

export type RetentionDataClass = "TRANSCRIPT" | "RECORDING" | "EXTRACTED_FIELDS";
export type ConsentDecision = "PROMPTED" | "GRANTED" | "DECLINED" | "WITHDRAWN";

export interface ConsentPolicyDto {
  agentId: string;
  enabled: boolean;
  purpose: string;
  noticeVersion: number;
  defaultLanguage: string;
  notices: Record<string, string>;
  instructions: Record<string, string>;
  keywords: Record<string, string[]>;
  eraseOnWithdrawal: boolean;
  updatedBy: string | null;
  updatedAt: string | null;
}

export interface ConsentPolicyRequest {
  enabled: boolean;
  purpose: string;
  defaultLanguage: string;
  notices: Record<string, string>;
  instructions: Record<string, string>;
  keywords: Record<string, string[]>;
  eraseOnWithdrawal: boolean;
}

export interface ConsentRecordDto {
  id: string;
  channel: string;
  externalId: string;
  agentId: string | null;
  purpose: string;
  noticeVersion: number;
  noticeLanguage: string | null;
  noticeSha256: string | null;
  decision: ConsentDecision;
  decidedAt: string;
}

export interface RetentionRuleDto {
  channel: string;
  dataClass: RetentionDataClass;
  enabled: boolean;
  retentionDays: number;
  updatedBy: string | null;
  updatedAt: string | null;
}

export interface RetentionRequestDto {
  channel: string;
  dataClass: RetentionDataClass;
  enabled: boolean;
  retentionDays: number | null;
}

export interface ContactRequestDto {
  channel: string;
  externalId: string;
  includeLeads: boolean;
  keepConsentProof: boolean;
}

export interface ContactExportReportDto {
  channel: string;
  subjectMasked: string;
  generatedAt: string;
  data: Record<string, unknown>;
  notIncluded: string[];
}

export interface ContactErasureReportDto {
  channel: string;
  subjectMasked: string;
  erasedAt: string;
  complete: boolean;
  erased: Record<string, number>;
  failures: string[];
  consentProofKept: boolean;
  notReached: string[];
}

export type PurposeResource = "agents" | "knowledge-collections";
export type AuditFormat = "csv" | "json";

/** Whether a retention rule is meaningful for the channel: mirrors PrivacyDataClass.allows. */
export function retentionAllows(dataClass: RetentionDataClass, channel: string): boolean {
  if (channel === ALL_CHANNELS) return true;
  if (dataClass === "RECORDING") return channel === "VOICE";
  if (dataClass === "EXTRACTED_FIELDS") return false;
  return channel.length > 0;
}

export const privacyApi = {
  getConsentPolicy: async (agentId: string): Promise<ConsentPolicyDto | null> => {
    const res = await apiFetch(`${PRIVACY_BASE}/consent-policies/${encodeURIComponent(agentId)}`);
    if (res.status === 404) return null;
    return toJson<ConsentPolicyDto>(res);
  },
  putConsentPolicy: (agentId: string, body: ConsentPolicyRequest) =>
    apiFetch(`${PRIVACY_BASE}/consent-policies/${encodeURIComponent(agentId)}`, {
      method: "PUT",
      ...jsonBody(body),
    }).then(toJson<ConsentPolicyDto>),
  consentRecords: (channel: string, externalId: string) =>
    apiFetch(
      `${PRIVACY_BASE}/consent-records?channel=${encodeURIComponent(channel)}&externalId=${encodeURIComponent(externalId)}`,
    ).then(toJson<ConsentRecordDto[]>),
  listRetention: () => apiFetch(`${PRIVACY_BASE}/retention-policies`).then(toJson<RetentionRuleDto[]>),
  putRetention: (body: RetentionRequestDto) =>
    apiFetch(`${PRIVACY_BASE}/retention-policies`, { method: "PUT", ...jsonBody(body) }).then(
      toJson<{ rule: RetentionRuleDto; warnings: string[] }>,
    ),
  getPurposes: (resource: PurposeResource, id: string) =>
    apiFetch(`${PRIVACY_BASE}/purposes/${resource}/${encodeURIComponent(id)}`).then(toJson<string[]>),
  putPurposes: (resource: PurposeResource, id: string, purposes: string[]) =>
    apiFetch(`${PRIVACY_BASE}/purposes/${resource}/${encodeURIComponent(id)}`, {
      method: "PUT",
      ...jsonBody({ purposes }),
    }).then(toJson<string[]>),
  exportContact: (body: ContactRequestDto) =>
    apiFetch(`${PRIVACY_BASE}/contacts/export`, { method: "POST", ...jsonBody(body) }).then(
      toJson<ContactExportReportDto>,
    ),
  eraseContact: (body: ContactRequestDto) =>
    apiFetch(`${PRIVACY_BASE}/contacts/erase`, { method: "POST", ...jsonBody(body) }).then(
      toJson<ContactErasureReportDto>,
    ),
  /** Streams the audit events in [from, to) and hands the file to the browser. */
  downloadAuditExport: async (from: string, to: string, format: AuditFormat): Promise<void> => {
    const res = await apiFetch(
      `${PRIVACY_BASE}/audit-export?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&format=${format}`,
    );
    if (!res.ok) await toJson(res); // throws with the server's message
    triggerBrowserDownload(await res.blob(), `audit-events.${format}`);
  },
};
