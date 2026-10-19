// Pure logic + types for two agent-workbench additions: the health chip
// (GET /api/agents/{id}/improvements/health) and the specialist-roles card
// (GET/PUT /api/agents/{id}/specialties). Kept free of React/i18n so it is
// unit-testable; the components own all user-visible copy.

export type AgentHealthStatus = "HEALTHY" | "AT_RISK" | "DEGRADED";

/** Mirrors core AgentHealthScore (only the fields the chip uses). */
export interface AgentHealthScore {
  score: number;
  status: AgentHealthStatus;
  openIssueCount: number;
  pendingSuggestionCount: number;
  lastSweptAt?: string | null;
}

export interface AgentSpecialty {
  capabilityTag: string;
  description?: string | null;
}

export interface AgentSpecialtiesResponse {
  agentId: string;
  specialties: AgentSpecialty[];
}

/** Maps a status to an existing state token name (see --state-* in globals). */
export function healthTone(status: string | null | undefined): "success" | "warning" | "danger" | "neutral" {
  switch (status) {
    case "HEALTHY":
      return "success";
    case "AT_RISK":
      return "warning";
    case "DEGRADED":
      return "danger";
    default:
      return "neutral";
  }
}

/** Clamp to a displayable 0-100 integer; non-finite input becomes null. */
export function displayScore(score: unknown): number | null {
  if (typeof score !== "number" || !Number.isFinite(score)) return null;
  return Math.max(0, Math.min(100, Math.round(score)));
}

// Keep in sync with core AgentSpecialtyService.
export const MAX_SPECIALTIES = 20;
export const MAX_TAG_LENGTH = 50;
export const MAX_DESCRIPTION_LENGTH = 500;
const TAG_FORMAT = /^[a-z0-9]+(?:[-_.][a-z0-9]+)*$/;

/** Same normalisation as the server: trim + lower-case, and spaces become '-'. */
export function normalizeSpecialtyTag(raw: string): string {
  return raw.trim().toLowerCase().replace(/\s+/g, "-");
}

export type TagProblem = "empty" | "format" | "tooLong" | "duplicate" | "tooMany";

/** Returns why `raw` cannot be added to `existing`, or null if it can. */
export function checkNewTag(raw: string, existing: string[]): { tag: string; problem: TagProblem | null } {
  const tag = normalizeSpecialtyTag(raw);
  if (!tag) return { tag, problem: "empty" };
  if (tag.length > MAX_TAG_LENGTH) return { tag, problem: "tooLong" };
  if (!TAG_FORMAT.test(tag)) return { tag, problem: "format" };
  if (existing.includes(tag)) return { tag, problem: "duplicate" };
  if (existing.length >= MAX_SPECIALTIES) return { tag, problem: "tooMany" };
  return { tag, problem: null };
}

/** True when two specialty lists hold the same tags with the same descriptions, order aside. */
export function sameSpecialties(a: AgentSpecialty[], b: AgentSpecialty[]): boolean {
  if (a.length !== b.length) return false;
  const norm = (list: AgentSpecialty[]) =>
    list
      .map((s) => `${s.capabilityTag}\u0000${(s.description ?? "").trim()}`)
      .sort()
      .join("\u0001");
  return norm(a) === norm(b);
}
