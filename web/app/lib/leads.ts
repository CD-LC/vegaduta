// Pure helpers for the Leads CRM screen (/admin/leads, backend V337). No React,
// no fetch - everything here is unit-tested in leads.test.ts.

export const LEAD_STAGES = ["NEW", "CONTACTED", "REPLIED", "QUALIFIED", "PROPOSAL", "WON", "LOST"] as const;
export type LeadStage = (typeof LEAD_STAGES)[number];

export const LEAD_ACTIVITY_TYPES = ["NOTE", "EMAIL_SENT", "EMAIL_REPLY", "CALL", "TASK"] as const;
export type LeadActivityType = (typeof LEAD_ACTIVITY_TYPES)[number] | "STAGE_CHANGE";

export interface Lead {
  id: string;
  name?: string | null;
  email?: string | null;
  phone?: string | null;
  company?: string | null;
  title?: string | null;
  source?: string | null;
  stage: LeadStage;
  score: number;
  owner?: string | null;
  value?: number | null;
  tags?: string[];
  notes?: string | null;
  lastContactedAt?: string | null;
  lastRepliedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface LeadActivity {
  id: string;
  leadId: string;
  type: LeadActivityType;
  body?: string | null;
  meta?: Record<string, unknown> | null;
  createdAt: string;
}

export interface StageTotal {
  stage: LeadStage;
  count: number;
  value: number;
}

/** The form's editable state - every field a string so inputs stay controlled. */
export interface LeadDraft {
  name: string;
  email: string;
  phone: string;
  company: string;
  title: string;
  source: string;
  stage: LeadStage;
  score: string;
  owner: string;
  value: string;
  tags: string;
  notes: string;
}

export type LeadDraftErrors = Partial<Record<"nameOrEmail" | "email" | "score" | "value", true>>;

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function isValidEmail(value: string): boolean {
  return EMAIL_RE.test(value.trim());
}

export function isLeadStage(value: unknown): value is LeadStage {
  return typeof value === "string" && (LEAD_STAGES as readonly string[]).includes(value);
}

export function emptyDraft(stage: LeadStage = "NEW"): LeadDraft {
  return {
    name: "",
    email: "",
    phone: "",
    company: "",
    title: "",
    source: "",
    stage,
    score: "0",
    owner: "",
    value: "",
    tags: "",
    notes: "",
  };
}

export function draftFromLead(lead: Lead): LeadDraft {
  return {
    name: lead.name ?? "",
    email: lead.email ?? "",
    phone: lead.phone ?? "",
    company: lead.company ?? "",
    title: lead.title ?? "",
    source: lead.source ?? "",
    stage: lead.stage,
    score: String(lead.score ?? 0),
    owner: lead.owner ?? "",
    value: lead.value == null ? "" : String(lead.value),
    tags: (lead.tags ?? []).join(", "),
    notes: lead.notes ?? "",
  };
}

/** Comma / semicolon / newline separated -> trimmed, de-duplicated (case-insensitive), order kept. */
export function parseTagsInput(input: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const raw of input.split(/[,;\n]/)) {
    const tag = raw.trim();
    const key = tag.toLowerCase();
    if (tag && !seen.has(key)) {
      seen.add(key);
      out.push(tag);
    }
  }
  return out;
}

function parseNumber(raw: string): number | null {
  const cleaned = raw.replace(/[,\s$]/g, "");
  if (cleaned === "") return null;
  const n = Number(cleaned);
  return Number.isFinite(n) ? n : NaN;
}

export function validateDraft(draft: LeadDraft): LeadDraftErrors {
  const errors: LeadDraftErrors = {};
  if (!draft.name.trim() && !draft.email.trim()) errors.nameOrEmail = true;
  if (draft.email.trim() && !isValidEmail(draft.email)) errors.email = true;
  const score = parseNumber(draft.score);
  if (score !== null && (Number.isNaN(score) || score < 0 || score > 100)) errors.score = true;
  const value = parseNumber(draft.value);
  if (value !== null && (Number.isNaN(value) || value < 0)) errors.value = true;
  return errors;
}

/**
 * Request body for create/update. Blank text fields are sent as "" (the API
 * treats blank as "clear"), which is what an edit that emptied a field means.
 */
export function draftToPayload(draft: LeadDraft) {
  const score = parseNumber(draft.score);
  const value = parseNumber(draft.value);
  return {
    name: draft.name.trim(),
    email: draft.email.trim(),
    phone: draft.phone.trim(),
    company: draft.company.trim(),
    title: draft.title.trim(),
    source: draft.source.trim(),
    stage: draft.stage,
    score: score === null || Number.isNaN(score) ? 0 : Math.round(score),
    owner: draft.owner.trim(),
    value: value === null || Number.isNaN(value) ? null : value,
    tags: parseTagsInput(draft.tags),
    notes: draft.notes.trim(),
  };
}

export function leadDisplayName(lead: Pick<Lead, "name" | "email" | "company">): string {
  return lead.name?.trim() || lead.email?.trim() || lead.company?.trim() || "-";
}

export function initials(lead: Pick<Lead, "name" | "email" | "company">): string {
  const base = leadDisplayName(lead);
  const parts = base.split(/[\s@._-]+/).filter(Boolean);
  const letters = parts.length >= 2 ? parts[0][0] + parts[1][0] : base.slice(0, 2);
  return letters.toUpperCase();
}

export function groupLeadsByStage(leads: Lead[]): Record<LeadStage, Lead[]> {
  const out = Object.fromEntries(LEAD_STAGES.map((s) => [s, [] as Lead[]])) as Record<LeadStage, Lead[]>;
  for (const lead of leads) {
    (out[isLeadStage(lead.stage) ? lead.stage : "NEW"] ??= []).push(lead);
  }
  return out;
}

/** Fill the API's per-stage totals out to every stage, in board order. */
export function normalizeTotals(totals: Array<Partial<StageTotal>> | null | undefined): StageTotal[] {
  const byStage = new Map<string, Partial<StageTotal>>();
  for (const t of totals ?? []) if (t.stage) byStage.set(t.stage, t);
  return LEAD_STAGES.map((stage) => ({
    stage,
    count: Number(byStage.get(stage)?.count ?? 0),
    value: Number(byStage.get(stage)?.value ?? 0),
  }));
}

/** Open pipeline = everything not yet WON or LOST. */
export function openPipelineValue(totals: StageTotal[]): number {
  return totals.filter((t) => t.stage !== "WON" && t.stage !== "LOST").reduce((sum, t) => sum + t.value, 0);
}

export function formatValue(value: number | null | undefined, locale?: string): string {
  if (value == null || !Number.isFinite(value)) return "";
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(value);
}

export type ScoreTone = "high" | "mid" | "low";

export function scoreTone(score: number): ScoreTone {
  if (score >= 70) return "high";
  if (score >= 40) return "mid";
  return "low";
}

/** Optimistic board move: returns a new array with the lead's stage swapped (unknown id = unchanged). */
export function moveLeadLocally(leads: Lead[], id: string, stage: LeadStage): Lead[] {
  return leads.map((l) => (l.id === id ? { ...l, stage } : l));
}

/** Coarse "how long ago" bucket so the caller can pick a translated string. */
export function ageBucket(iso: string | null | undefined, now: number = Date.now()): { unit: "now" | "m" | "h" | "d"; count: number } | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return null;
  const mins = Math.max(0, Math.floor((now - t) / 60000));
  if (mins < 1) return { unit: "now", count: 0 };
  if (mins < 60) return { unit: "m", count: mins };
  const hours = Math.floor(mins / 60);
  if (hours < 24) return { unit: "h", count: hours };
  return { unit: "d", count: Math.floor(hours / 24) };
}
