import { apiFetch, toJson, jsonBody } from "./core";
import type { Lead, LeadActivity, StageTotal } from "../leads";

// Built-in Leads CRM (backend V337, /api/leads/**, gated by MANAGE_LEADS).
export interface LeadListParams {
  stage?: string;
  source?: string;
  q?: string;
  page?: number;
  size?: number;
}

export interface LeadPage {
  items: Lead[];
  total: number;
  page: number;
  size: number;
}

export interface LeadImportResult {
  created: number;
  updated: number;
  skipped: number;
  errors: string[];
}

export const leadsApi = {
  listLeads: (params: LeadListParams = {}): Promise<LeadPage> => {
    const qs = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== "") qs.set(key, String(value));
    }
    const suffix = qs.toString();
    return apiFetch(`/api/leads${suffix ? `?${suffix}` : ""}`).then(toJson<LeadPage>);
  },
  getLeadsPipeline: (): Promise<StageTotal[]> => apiFetch(`/api/leads/pipeline`).then(toJson<StageTotal[]>),
  createLead: (payload: object): Promise<Lead> =>
    apiFetch(`/api/leads`, { method: "POST", ...jsonBody(payload) }).then(toJson<Lead>),
  updateLead: (id: string, payload: object): Promise<Lead> =>
    apiFetch(`/api/leads/${encodeURIComponent(id)}`, { method: "PUT", ...jsonBody(payload) }).then(toJson<Lead>),
  deleteLead: (id: string) =>
    apiFetch(`/api/leads/${encodeURIComponent(id)}`, { method: "DELETE" }).then((res) => (res.ok ? null : toJson(res))),
  setLeadStage: (id: string, stage: string, note?: string): Promise<Lead> =>
    apiFetch(`/api/leads/${encodeURIComponent(id)}/stage`, { method: "POST", ...jsonBody({ stage, note }) }).then(toJson<Lead>),
  listLeadActivities: (id: string): Promise<LeadActivity[]> =>
    apiFetch(`/api/leads/${encodeURIComponent(id)}/activities`).then(toJson<LeadActivity[]>),
  addLeadActivity: (id: string, type: string, body: string): Promise<LeadActivity> =>
    apiFetch(`/api/leads/${encodeURIComponent(id)}/activities`, { method: "POST", ...jsonBody({ type, body }) }).then(toJson<LeadActivity>),
  importLeadsCsv: (file: File): Promise<LeadImportResult> => {
    const formData = new FormData();
    formData.append("file", file);
    return apiFetch(`/api/leads/import`, { method: "POST", body: formData }).then(toJson<LeadImportResult>);
  },
  exportLeadsCsv: async (): Promise<Blob> => {
    const res = await apiFetch(`/api/leads/export.csv`);
    if (!res.ok) await toJson(res);
    return res.blob();
  },
};
