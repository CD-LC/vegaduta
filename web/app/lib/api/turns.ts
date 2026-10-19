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
