// Typed client for the skill library: the review/learning side of agent skills
// (GET /api/skills?status=, /api/skills/{id}/approve ...). Kept apart from the
// older by-name skill editor in tools.ts so the two shapes never get mixed up:
// this one is addressed by id and carries a status and an origin.
import { apiFetch } from "./core";
import { jsonBody, toJson } from "./responses";

export type SkillStatus = "PENDING" | "ACTIVE" | "REJECTED" | "ARCHIVED";
export type SkillOrigin = "IMPORTED" | "LEARNED" | "AUTHORED";

/** The learning loop's replay of a drafted skill against its own turn; absent when it was not checked. */
export interface SkillSelfCheck {
  verdict: "PASS" | "WEAK";
  reason: string;
}

export interface LibrarySkill {
  id: string;
  name: string;
  description: string;
  status: SkillStatus;
  origin: SkillOrigin;
  version: number;
  uses: number;
  agentIds: string[];
  updatedAt: string;
  selfCheck?: SkillSelfCheck;
}

export interface LibrarySkillFile {
  path: string;
  size: number;
}

export interface LibrarySkillDetail extends LibrarySkill {
  body: string;
  files: LibrarySkillFile[];
}

export interface LibrarySkillPage {
  items: LibrarySkill[];
  total: number | null;
}

const STATUSES: SkillStatus[] = ["PENDING", "ACTIVE", "REJECTED", "ARCHIVED"];
const ORIGINS: SkillOrigin[] = ["IMPORTED", "LEARNED", "AUTHORED"];

function str(v: unknown): string {
  return typeof v === "string" ? v : "";
}

/** Defensive: a bad row from a newer or older core must not crash the page. */
export function normalizeSkill(raw: unknown): LibrarySkill | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const id = str(r.id);
  if (!id) return null;
  const status = STATUSES.includes(r.status as SkillStatus) ? (r.status as SkillStatus) : "PENDING";
  const origin = ORIGINS.includes(r.origin as SkillOrigin) ? (r.origin as SkillOrigin) : "AUTHORED";
  return {
    id,
    name: str(r.name) || id,
    description: str(r.description),
    status,
    origin,
    version: Number.isFinite(Number(r.version)) ? Number(r.version) : 1,
    uses: Number.isFinite(Number(r.uses)) ? Number(r.uses) : 0,
    agentIds: Array.isArray(r.agentIds) ? r.agentIds.filter((a): a is string => typeof a === "string") : [],
    updatedAt: str(r.updatedAt),
    ...selfCheckOf(r.selfCheck),
  };
}

/** A FAIL draft is never stored, so only PASS and WEAK are shown; anything else reads as unchecked. */
function selfCheckOf(raw: unknown): { selfCheck?: SkillSelfCheck } {
  if (!raw || typeof raw !== "object") return {};
  const c = raw as Record<string, unknown>;
  return c.verdict === "PASS" || c.verdict === "WEAK" ? { selfCheck: { verdict: c.verdict, reason: str(c.reason) } } : {};
}

export function normalizeDetail(raw: unknown): LibrarySkillDetail | null {
  const base = normalizeSkill(raw);
  if (!base) return null;
  const r = raw as Record<string, unknown>;
  const files = Array.isArray(r.files)
    ? (r.files as unknown[]).flatMap((f) => {
        const o = f as Record<string, unknown> | null;
        return o && typeof o.path === "string" ? [{ path: o.path, size: Number(o.size) || 0 }] : [];
      })
    : [];
  return { ...base, body: str(r.body), files };
}

/** Accepts a bare array or a Spring-style page ({content|items, totalElements|total}). */
export function normalizePage(raw: unknown): LibrarySkillPage {
  const obj = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  const rows = Array.isArray(raw) ? raw : Array.isArray(obj.content) ? obj.content : Array.isArray(obj.items) ? obj.items : [];
  const totalRaw = obj.totalElements ?? obj.total;
  const items = (rows as unknown[]).flatMap((r) => {
    const s = normalizeSkill(r);
    return s ? [s] : [];
  });
  const total = totalRaw != null && Number.isFinite(Number(totalRaw)) ? Number(totalRaw) : Array.isArray(raw) ? items.length : null;
  return { items, total };
}

const base = (id: string) => `/api/skills/${encodeURIComponent(id)}`;

export const skillLibraryApi = {
  listLibrarySkills: (status?: SkillStatus, page = 0, size = 100): Promise<LibrarySkillPage> => {
    const q = new URLSearchParams();
    if (status) q.set("status", status);
    q.set("page", String(page));
    q.set("size", String(size));
    return apiFetch(`/api/skills?${q.toString()}`)
      .then((res) => toJson(res))
      .then(normalizePage);
  },
  getLibrarySkill: (id: string): Promise<LibrarySkillDetail> =>
    apiFetch(base(id))
      .then((res) => toJson(res))
      .then((raw) => {
        const d = normalizeDetail(raw);
        if (!d) throw new Error("Unexpected skill response");
        return d;
      }),
  // No Content-Type: the browser sets the multipart boundary.
  importLibrarySkill: (file: File): Promise<LibrarySkill | null> => {
    const form = new FormData();
    form.append("file", file);
    return apiFetch(`/api/skills/import`, { method: "POST", body: form })
      .then((res) => toJson(res))
      .then((raw) => normalizeSkill((raw as { skill?: unknown } | null)?.skill ?? raw));
  },
  exportLibrarySkill: async (id: string): Promise<Blob> => {
    const res = await apiFetch(`${base(id)}/export`);
    if (!res.ok) await toJson(res);
    return res.blob();
  },
  updateLibrarySkill: (id: string, patch: { description?: string; body?: string }): Promise<unknown> =>
    apiFetch(base(id), { method: "PUT", ...jsonBody(patch) }).then((res) => toJson(res)),
  approveLibrarySkill: (id: string): Promise<unknown> =>
    apiFetch(`${base(id)}/approve`, { method: "POST" }).then((res) => toJson(res)),
  rejectLibrarySkill: (id: string): Promise<unknown> =>
    apiFetch(`${base(id)}/reject`, { method: "POST" }).then((res) => toJson(res)),
  archiveLibrarySkill: (id: string): Promise<unknown> =>
    apiFetch(`${base(id)}/archive`, { method: "POST" }).then((res) => toJson(res)),
  setLibrarySkillAgents: (id: string, agentIds: string[]): Promise<unknown> =>
    apiFetch(`${base(id)}/agents`, { method: "POST", ...jsonBody({ agentIds }) }).then((res) => toJson(res)),
  deleteLibrarySkill: async (id: string): Promise<void> => {
    const res = await apiFetch(base(id), { method: "DELETE" });
    if (!res.ok) await toJson(res);
  },
};
