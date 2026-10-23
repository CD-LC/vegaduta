import type { AssistantTurn, ThreadSummary } from "./assistantTypes";
import { isApprovalContinuationMessage } from "../agents/approvalContinuation";
import { stripAppContext } from "./needs";

/**
 * The conversation index of the /assistant surface (design doc, gap G1): one row per session in IndexedDB -
 * id (the server session id), title (first user line), agent, last activity, pinned. It is a CACHE of the
 * server's list (GET /api/chat/threads, merged in by mergeServerThreads when the rail loads) plus what this
 * browser knows that the server does not: a local rename, the pin, and sessions of a core that stores
 * nothing (branching off). The transcript itself is never stored here; opening a thread re-reads
 * GET /api/agents/{id}/sessions/{sid}/transcript.
 *
 * Same posture as lib/api/persistentCache.ts: scoped by user id, every call best-effort (no IndexedDB in
 * SSR, jsdom or a locked-down browser degrades to an in-memory map for the page's life, never a thrown
 * error), and nothing secret is written - titles are the person's own first lines.
 */
export interface ThreadRow extends ThreadSummary {
  scope: string;
  createdAt: number;
}

const DB_NAME = "vd-assistant-threads";
const STORE = "threads";
const DB_VERSION = 1;
export const TITLE_MAX_CHARS = 60;

/** Which user's rows: the Keycloak subject when known, else one shared local scope. */
export function threadScope(userId: string | null | undefined): string {
  return userId && userId.trim() ? userId.trim() : "local";
}

/** First non-empty line of the first message, cut at a word boundary. */
export function threadTitle(text: string, max = TITLE_MAX_CHARS): string {
  const line = (text ?? "")
    .split(/\r?\n/)
    .map((l) => l.trim())
    .find((l) => l.length > 0);
  if (!line) return "New thread";
  if (line.length <= max) return line;
  const cut = line.slice(0, max);
  const space = cut.lastIndexOf(" ");
  return `${space > max / 2 ? cut.slice(0, space) : cut}…`;
}

/** Pinned first, then newest activity first. */
export function sortThreads<T extends ThreadSummary>(rows: readonly T[]): T[] {
  return [...rows].sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.lastAt - a.lastAt);
}

export type ThreadGroupKey = "pinned" | "today" | "yesterday" | "week" | "earlier";

/** The rail's sections, in display order, empty ones dropped. */
export function groupThreads<T extends ThreadSummary>(
  rows: readonly T[],
  now: number
): { key: ThreadGroupKey; rows: T[] }[] {
  const startOfToday = new Date(now);
  startOfToday.setHours(0, 0, 0, 0);
  const today = startOfToday.getTime();
  const yesterday = today - 86_400_000;
  const week = today - 6 * 86_400_000;
  const groups: Record<ThreadGroupKey, T[]> = { pinned: [], today: [], yesterday: [], week: [], earlier: [] };
  for (const row of sortThreads(rows)) {
    if (row.pinned) groups.pinned.push(row);
    else if (row.lastAt >= today) groups.today.push(row);
    else if (row.lastAt >= yesterday) groups.yesterday.push(row);
    else if (row.lastAt >= week) groups.week.push(row);
    else groups.earlier.push(row);
  }
  return (Object.keys(groups) as ThreadGroupKey[])
    .filter((k) => groups[k].length > 0)
    .map((key) => ({ key, rows: groups[key] }));
}

/** GET .../transcript (ChatTranscriptService.Transcript) as transcript entries. */
export interface ChatTranscript {
  sessionId: string;
  messages: {
    role: string | null;
    content: string | null;
    timestamp?: string | null;
    status?: string | null;
    error?: boolean;
  }[];
}

/** Server transcript -> turns. Tool steps are not part of a stored transcript, so restored turns carry none. */
export function transcriptToTurns(transcript: ChatTranscript | null | undefined, sessionId: string): AssistantTurn[] {
  if (!transcript || !Array.isArray(transcript.messages)) return [];
  const turns: AssistantTurn[] = [];
  transcript.messages.forEach((m, i) => {
    const role = m.role === "user" ? "user" : m.role === "assistant" ? "assistant" : null;
    if (!role) return;
    // The platform's own message handing a decided tool call's result back to the agent: not something the
    // person typed, and the agent's reply to it follows as an ordinary assistant entry.
    if (role === "user" && isApprovalContinuationMessage(m.content)) return;
    const at = m.timestamp ? Date.parse(m.timestamp) : NaN;
    turns.push({
      id: `${sessionId}:${i}`,
      role,
      content: role === "user" ? stripAppContext(m.content ?? "") : (m.content ?? ""),
      at: Number.isFinite(at) ? at : 0,
      ...(role === "assistant" && (m.error || m.status === "failed") ? { failed: true } : {}),
    });
  });
  return turns;
}

// ---- storage -------------------------------------------------------------------------------------

type Key = string;
const memory = new Map<Key, ThreadRow>();
let dbPromise: Promise<IDBDatabase | null> | null = null;

function keyOf(scope: string, id: string): Key {
  return `${scope}|${id}`;
}

function openDb(): Promise<IDBDatabase | null> {
  if (dbPromise !== null) return dbPromise;
  dbPromise = new Promise((resolve) => {
    try {
      if (typeof indexedDB === "undefined") return resolve(null);
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(STORE)) {
          db.createObjectStore(STORE, { keyPath: "key" }).createIndex("scope", "scope");
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
      req.onblocked = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
  return dbPromise;
}

function settled(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
    tx.onabort = () => resolve();
  });
}

function stripKey(row: ThreadRow & { key?: string }): ThreadRow {
  const { key: _key, ...rest } = row;
  return rest;
}

export async function listThreads(scope: string): Promise<ThreadRow[]> {
  const db = await openDb();
  if (!db) return sortThreads([...memory.values()].filter((r) => r.scope === scope));
  try {
    const tx = db.transaction(STORE, "readonly");
    const rows = await new Promise<ThreadRow[]>((resolve) => {
      const req = tx.objectStore(STORE).index("scope").getAll(scope);
      req.onsuccess = () => resolve((req.result as (ThreadRow & { key: string })[]).map(stripKey));
      req.onerror = () => resolve([]);
    });
    await settled(tx);
    return sortThreads(rows);
  } catch {
    return [];
  }
}

export async function getThread(scope: string, id: string): Promise<ThreadRow | null> {
  const db = await openDb();
  if (!db) return memory.get(keyOf(scope, id)) ?? null;
  try {
    const tx = db.transaction(STORE, "readonly");
    const row = await new Promise<ThreadRow | null>((resolve) => {
      const req = tx.objectStore(STORE).get(keyOf(scope, id));
      req.onsuccess = () => resolve(req.result ? stripKey(req.result as ThreadRow & { key: string }) : null);
      req.onerror = () => resolve(null);
    });
    await settled(tx);
    return row;
  } catch {
    return null;
  }
}

export async function putThread(row: ThreadRow): Promise<void> {
  const db = await openDb();
  if (!db) {
    memory.set(keyOf(row.scope, row.id), row);
    return;
  }
  try {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).put({ ...row, key: keyOf(row.scope, row.id) });
    await settled(tx);
  } catch {
    // quota / closed connection - the rail just will not list this thread after a reload
  }
}

/** Merge `patch` into an existing row; returns the new row, or null when there is none. */
export async function patchThread(
  scope: string,
  id: string,
  patch: Partial<Pick<ThreadRow, "title" | "lastAt" | "pinned" | "agentId">>
): Promise<ThreadRow | null> {
  const current = await getThread(scope, id);
  if (!current) return null;
  const next = { ...current, ...patch };
  await putThread(next);
  return next;
}

/** Create the row for a session the first time a turn in it settles, or bump its activity after that. */
export async function recordThreadActivity(
  scope: string,
  id: string,
  agentId: string,
  firstUserLine: string,
  at: number
): Promise<ThreadRow> {
  const existing = await getThread(scope, id);
  const row: ThreadRow = existing
    ? { ...existing, lastAt: Math.max(existing.lastAt, at), agentId }
    : { id, scope, agentId, title: threadTitle(firstUserLine), lastAt: at, createdAt: at, pinned: false };
  await putThread(row);
  return row;
}

/** What the server knows about one session (lib/api/assistant ServerThread), as the merge needs it. */
export interface ServerThreadLike {
  sessionId: string;
  agentId: string;
  title: string | null;
  createdAt: string;
  updatedAt: string;
}

function ms(iso: string | null | undefined): number {
  const n = iso ? Date.parse(iso) : NaN;
  return Number.isFinite(n) ? n : 0;
}

/**
 * One server session folded into the local index. A session the browser has never seen becomes a row
 * (title from the server's first user line, not pinned). A row that exists keeps what is local - its
 * title (a rename, or the same line the server would give), its pin - and only moves forward in time:
 * lastAt = max(local, server), the agent the server says. Returns null when nothing would change.
 */
export function mergeServerThread(local: ThreadRow | null, server: ServerThreadLike, scope: string): ThreadRow | null {
  const updatedAt = ms(server.updatedAt);
  if (!local) {
    return {
      id: server.sessionId,
      scope,
      agentId: server.agentId,
      title: threadTitle(server.title ?? ""),
      lastAt: updatedAt,
      createdAt: ms(server.createdAt) || updatedAt,
      pinned: false,
    };
  }
  const next: ThreadRow = {
    ...local,
    lastAt: Math.max(local.lastAt, updatedAt),
    agentId: server.agentId || local.agentId,
  };
  return next.lastAt === local.lastAt && next.agentId === local.agentId ? null : next;
}

/** Folds a page of server sessions into the index for `scope`; returns how many rows were written. */
export async function mergeServerThreads(scope: string, rows: readonly ServerThreadLike[]): Promise<number> {
  let written = 0;
  for (const server of rows) {
    if (!server || !server.sessionId || !server.agentId) continue;
    const merged = mergeServerThread(await getThread(scope, server.sessionId), server, scope);
    if (merged) {
      await putThread(merged);
      written++;
    }
  }
  return written;
}

export async function deleteThread(scope: string, id: string): Promise<void> {
  const db = await openDb();
  if (!db) {
    memory.delete(keyOf(scope, id));
    return;
  }
  try {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).delete(keyOf(scope, id));
    await settled(tx);
  } catch {
    // nothing to do
  }
}

/** Test hook: forget the in-memory rows and any opened database handle. */
export function resetThreadIndexForTests(): void {
  memory.clear();
  dbPromise = null;
}
