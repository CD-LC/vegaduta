import { api } from "../../lib/api";
import type { LlmRequestSnapshot } from "../agents/RequestInspector";

/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * Request X-ray: attach exactly what a finished turn sent to the model.
 * Turns are sequential, so "snapshots not seen yet" are this turn's calls.
 * Fire-and-forget - a bonus surface that must never fail the turn.
 */
export async function attachRequestSnapshots(
  agentId: string,
  sessionId: string | null,
  turnRef: string,
  seenRequestIds: Set<string>,
  setTurns: (update: (turns: any[]) => any[]) => void
): Promise<void> {
  try {
    const all: LlmRequestSnapshot[] = await api.getAgentSessionLlmRequests(agentId, sessionId);
    if (!Array.isArray(all)) return;
    const fresh = all.filter((s) => !seenRequestIds.has(s.id));
    for (const s of all) seenRequestIds.add(s.id);
    if (fresh.length === 0) return;
    setTurns((t) => {
      const copy = [...t];
      const last = copy.length - 1;
      if (last >= 0 && copy[last].ref === turnRef) copy[last] = { ...copy[last], requests: fresh };
      return copy;
    });
  } catch {
    /* quiet - no inspector beats an error toast */
  }
}
