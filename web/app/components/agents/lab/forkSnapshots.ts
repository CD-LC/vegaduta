// Agent Lab - the pre-fork tail of a conversation, kept in this browser so
// the original can be compared with the branch. A fork rewinds the ONE server
// session in place, so this snapshot is the only copy of the original that
// survives it. localStorage, per agent, newest 5, 24 hours.
//
// Every storage access is wrapped: private windows and blocked site data make
// the accessor itself throw, and a playground that stores camera frames can
// sit at quota. A snapshot that cannot be stored is kept in this module's
// memory instead - which outlives the panel (a tab switch or closing the lab
// unmounts it) but not a reload - and saveSnapshot says so (`persisted:
// false`), so the caller can tell the user before anything is rewound.
import type { LabTurn } from "./types";

export interface ForkSnapshot {
  id: string;
  sessionId: string;
  createdAt: number;
  /** Index of the forked user message in the transcript (the cut point). */
  forkUserIndex: number;
  originalMessage: string;
  sentMessage: string;
  /** turns[forkUserIndex..] as they were before the rewind. */
  originalTail: LabTurn[];
}

export type ForkSnapshotDraft = Omit<ForkSnapshot, "id" | "createdAt">;

export const FORK_SNAPSHOT_LIMIT = 5;
export const FORK_SNAPSHOT_TTL_MS = 24 * 60 * 60 * 1000;

const storageKey = (agentId: string) => `agentic:lab:forks:${agentId}`;

/** Snapshots storage refused, per agent. Lives as long as the page does. */
const memory = new Map<string, ForkSnapshot[]>();

/** Tests only: module state would otherwise leak from one test into the next. */
export function clearMemorySnapshots(): void {
  memory.clear();
}

/** Images, camera frames and the raw tool log are large and not needed to
 * redraw a graph; they never go into storage. */
function stripTurn(turn: LabTurn): LabTurn {
  const copy: Record<string, unknown> = { ...turn };
  delete copy.frames;
  delete copy.attachment;
  delete copy.trace;
  return copy as unknown as LabTurn;
}

function isSnapshot(value: unknown): value is ForkSnapshot {
  if (!value || typeof value !== "object") return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.id === "string" &&
    typeof v.sessionId === "string" &&
    typeof v.createdAt === "number" &&
    typeof v.forkUserIndex === "number" &&
    typeof v.originalMessage === "string" &&
    typeof v.sentMessage === "string" &&
    Array.isArray(v.originalTail)
  );
}

/** Newest first, nothing older than 24 hours, at most 5. */
export function pruneSnapshots(list: readonly ForkSnapshot[], now: number = Date.now()): ForkSnapshot[] {
  return list
    .filter((snapshot) => now - snapshot.createdAt <= FORK_SNAPSHOT_TTL_MS)
    .sort((a, b) => b.createdAt - a.createdAt)
    .slice(0, FORK_SNAPSHOT_LIMIT);
}

function write(agentId: string, list: readonly ForkSnapshot[]): boolean {
  try {
    if (list.length === 0) window.localStorage.removeItem(storageKey(agentId));
    else window.localStorage.setItem(storageKey(agentId), JSON.stringify(list));
    return true;
  } catch {
    return false;
  }
}

function readStored(agentId: string, now: number): ForkSnapshot[] {
  try {
    const raw = window.localStorage.getItem(storageKey(agentId));
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? pruneSnapshots(parsed.filter(isSnapshot), now) : [];
  } catch {
    return [];
  }
}

/** Stored snapshots plus the ones held in memory because storage refused them. */
export function loadSnapshots(agentId: string, now: number = Date.now()): ForkSnapshot[] {
  const stored = readStored(agentId, now);
  const held = (memory.get(agentId) ?? []).filter((snapshot) => !stored.some((other) => other.id === snapshot.id));
  return held.length === 0 ? stored : pruneSnapshots([...held, ...stored], now);
}

/**
 * Builds the snapshot and stores it. `persisted` is false when the browser
 * refused the write: the snapshot then exists in memory only and is lost
 * when the page is reloaded or closed.
 */
export function saveSnapshot(agentId: string, draft: ForkSnapshotDraft, now: number = Date.now()): { snapshot: ForkSnapshot; persisted: boolean } {
  const snapshot: ForkSnapshot = {
    ...draft,
    id: `f${now.toString(36)}${Math.random().toString(36).slice(2, 8)}`,
    createdAt: now,
    originalTail: draft.originalTail.map(stripTurn),
  };
  // Over quota: the newest snapshot matters most, so retry with it alone.
  const persisted =
    write(agentId, pruneSnapshots([snapshot, ...readStored(agentId, now)], now)) || write(agentId, [snapshot]);
  if (!persisted) memory.set(agentId, pruneSnapshots([snapshot, ...(memory.get(agentId) ?? [])], now));
  return { snapshot, persisted };
}

export function deleteSnapshot(agentId: string, id: string, now: number = Date.now()): void {
  const held = (memory.get(agentId) ?? []).filter((snapshot) => snapshot.id !== id);
  if (held.length > 0) memory.set(agentId, held);
  else memory.delete(agentId);
  const stored = readStored(agentId, now);
  if (stored.some((snapshot) => snapshot.id === id)) write(agentId, stored.filter((snapshot) => snapshot.id !== id));
}
