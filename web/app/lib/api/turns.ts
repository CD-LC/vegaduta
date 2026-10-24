import { apiFetch, toJsonQuietOn } from "./core";

// Turn journal read (ADR-0020 / ADR-0021): core/agents TurnJournalController.
// GET /api/agents/{agentId}/turns/{turnId}/journal returns the recorded, hash-chained events of
// one agent turn plus the server's own chain verification. Payloads are never stored: each event
// carries the SHA-256 of its payload and a short redacted summary. 403/404 are quiet (no toast)
// because "not allowed" and "nothing recorded" are states the inspector renders inline.

export type TurnEventType = "INTAKE" | "GUARD" | "MODEL_CALL" | "TOOL_CALL" | "APPROVAL" | "VERIFY" | "COMMIT";

export interface TurnJournalEvent {
  turnId: string;
  agentId: string;
  seq: number;
  type: TurnEventType | string;
  payloadHash: string;
  summary: string;
  prevHash: string;
  hash: string;
  /** Jackson Instant: ISO-8601 string, or epoch seconds (with fraction) when timestamps are enabled. */
  createdAt: string | number;
}

export interface TurnJournalView {
  agentId: string;
  turnId: string;
  chainIntact: boolean;
  brokenAtSeq: number;
  verification: string;
  events: TurnJournalEvent[];
}

export function getTurnJournal(agentId: string, turnId: string): Promise<TurnJournalView> {
  return apiFetch(`/api/agents/${encodeURIComponent(agentId)}/turns/${encodeURIComponent(turnId)}/journal`).then(
    toJsonQuietOn<TurnJournalView>([403, 404, 405])
  );
}

// Turns of one chat session (TurnJournalController GET /api/agents/{agentId}/turn-journal?sessionId=...&limit=...).
// Newest first. An empty list - not a 404 - is the ordinary answer when the journal is off or the session recorded
// nothing; 403 (audit-log permission / agent viewer) and 404/405 (older core without the route) are rendered inline,
// so they raise no toast.

export interface TurnSummary {
  turnId: string;
  agentId: string;
  /** Jackson Instant: ISO-8601 string, or epoch seconds (with fraction) when timestamps are enabled. */
  startedAt: string | number;
  lastEventAt: string | number;
  events: number;
  /** Status of the turn's last COMMIT (completed, cancelled, error:..., refused, ...) or "open" while uncommitted. */
  status: string;
}

export interface SessionTurnsView {
  agentId: string;
  sessionId: string;
  turns: TurnSummary[];
}

export function listSessionTurns(agentId: string, sessionId: string, limit?: number): Promise<SessionTurnsView> {
  const query = new URLSearchParams({ sessionId });
  if (limit !== undefined) query.set("limit", String(limit));
  return apiFetch(`/api/agents/${encodeURIComponent(agentId)}/turn-journal?${query.toString()}`).then(
    toJsonQuietOn<SessionTurnsView>([403, 404, 405])
  );
}
