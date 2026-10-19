import type { PlaygroundTurn } from "./types";

// Audit finding (2026-07-29): turns/sessionId were pure React state with no
// persistence at all - a page refresh silently lost the visible conversation
// even though the backend's own session history (SessionMemoryService,
// Redis-backed, 24h TTL) was still there. This is a same-browser recovery
// fix, not full cross-device/searchable history - that would need a real
// backend transcript store + a "list my past conversations" endpoint
// (neither exists today), which is a bigger, separate piece of work.
export const PLAYGROUND_STORAGE_PREFIX = "agentic:playground:";

/** Tell the rest of the page a playground turn for `agentId` completed.
 * Listeners: the call panel (drops a parked voice recording so it is never
 * replayed after the person typed the message instead) and other surfaces
 * that track playground activity. Never throws - a listener's problem or a
 * missing CustomEvent must not break the send that just succeeded. */
export function announcePlaygroundTurn(agentId: string | undefined) {
  try {
    if (typeof window === "undefined" || !agentId) return;
    window.dispatchEvent(new CustomEvent("vd:playground-turn", { detail: { agentId } }));
  } catch {
    // Best-effort.
  }
}
export const PLAYGROUND_STORAGE_TTL_MS = 24 * 60 * 60 * 1000; // matches SessionMemoryService's own TTL

/** The localStorage record savePlaygroundState writes - JSON.parse hands
 * back `any`, so loadPlaygroundState's return annotation is what re-types a
 * restored transcript for the rest of the component. */
export interface PersistedPlaygroundState {
  sessionId: string;
  turns: PlaygroundTurn[];
  savedAt: number;
}

export function loadPlaygroundState(agentId: string | undefined): PersistedPlaygroundState | null {
  if (typeof window === "undefined" || !agentId) return null;
  try {
    const raw = window.localStorage.getItem(PLAYGROUND_STORAGE_PREFIX + agentId);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || Date.now() - (parsed.savedAt || 0) > PLAYGROUND_STORAGE_TTL_MS) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function savePlaygroundState(agentId: string | undefined, sessionId: string | null, turns: PlaygroundTurn[]) {
  if (typeof window === "undefined" || !agentId) return;
  try {
    if (!sessionId || turns.length === 0) {
      window.localStorage.removeItem(PLAYGROUND_STORAGE_PREFIX + agentId);
      return;
    }
    window.localStorage.setItem(
      PLAYGROUND_STORAGE_PREFIX + agentId,
      JSON.stringify({ sessionId, turns, savedAt: Date.now() })
    );
  } catch {
    // Best-effort - storage full/disabled should never break the playground.
  }
}
