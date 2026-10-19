// Per-project chat thread + composer draft, kept in localStorage so switching
// the /build surface tabs (which unmounts the project view) or reloading does
// not throw away a conversation or a half-typed message. Every access is
// try/catch-wrapped (private windows, blocked storage, quota) and the stored
// size is capped. A tiny in-memory pub/sub lets a turn that finishes AFTER its
// ChatPane unmounted still land in the thread the next mount reads.

import { isViewAsActive } from "../lib/viewAs";

const THREAD_PREFIX = "vd.build.chat.";
const DRAFT_PREFIX = "vd.build.draft.";

export const MAX_ENTRIES = 40;
export const MAX_DIFF_CHARS = 20_000;
export const MAX_UNDO_CHARS = 120_000;
export const MAX_DRAFT_CHARS = 8_000;
const MAX_TOTAL_CHARS = 400_000;

export type ChatEntry = Record<string, any> & { kind: "user" | "result" };

function store(): Storage | null {
  // View-as (read-only impersonation): the viewed user's threads/drafts are never
  // read from or written to this browser's shared localStorage.
  if (isViewAsActive()) return null;
  try {
    return typeof window !== "undefined" ? window.localStorage : null;
  } catch {
    return null;
  }
}

/** Drop what must not (or cannot usefully) be persisted and cap the rest. */
export function sanitizeThread(thread: unknown): ChatEntry[] {
  if (!Array.isArray(thread)) return [];
  const clean: ChatEntry[] = [];
  const valid = thread.filter((raw) => raw && (raw.kind === "user" || raw.kind === "result"));
  for (const raw of valid.slice(-MAX_ENTRIES)) {
    const e: ChatEntry = { ...raw };
    if (typeof e.diff === "string" && e.diff.length > MAX_DIFF_CHARS) {
      e.diff = e.diff.slice(0, MAX_DIFF_CHARS);
      e.diffTruncated = true;
    }
    if (typeof e.error === "string" && e.error.length > 2000) e.error = e.error.slice(0, 2000);
    clean.push(e);
  }
  // Undo snapshots hold whole file contents: keep only the newest within budget.
  let undoBudget = MAX_UNDO_CHARS;
  for (let i = clean.length - 1; i >= 0; i--) {
    const u = clean[i].undo;
    if (!u) continue;
    const size = (u.files || []).reduce((n: number, f: { content?: string }) => n + (f.content?.length || 0), 0);
    if (size > undoBudget) delete clean[i].undo;
    else undoBudget -= size;
  }
  // Overall cap: shed oldest entries until the payload fits.
  while (clean.length > 1 && JSON.stringify(clean).length > MAX_TOTAL_CHARS) clean.shift();
  return clean;
}

export function loadThread(projectId: string): ChatEntry[] {
  const s = store();
  if (!s || !projectId) return [];
  try {
    const raw = s.getItem(THREAD_PREFIX + projectId);
    return raw ? sanitizeThread(JSON.parse(raw)) : [];
  } catch {
    return [];
  }
}

export function saveThread(projectId: string, thread: ChatEntry[]): void {
  const s = store();
  if (!s || !projectId) return;
  try {
    if (!thread.length) {
      s.removeItem(THREAD_PREFIX + projectId);
      return;
    }
    s.setItem(THREAD_PREFIX + projectId, JSON.stringify(sanitizeThread(thread)));
  } catch {
    /* quota / blocked: the in-memory thread still works */
  }
}

export function loadDraft(projectId: string): string {
  const s = store();
  if (!s || !projectId) return "";
  try {
    return (s.getItem(DRAFT_PREFIX + projectId) || "").slice(0, MAX_DRAFT_CHARS);
  } catch {
    return "";
  }
}

export function saveDraft(projectId: string, text: string): void {
  const s = store();
  if (!s || !projectId) return;
  try {
    if (!text) s.removeItem(DRAFT_PREFIX + projectId);
    else s.setItem(DRAFT_PREFIX + projectId, text.slice(0, MAX_DRAFT_CHARS));
  } catch {
    /* ignore */
  }
}

export function clearChat(projectId: string): void {
  const s = store();
  if (!s || !projectId) return;
  try {
    s.removeItem(THREAD_PREFIX + projectId);
    s.removeItem(DRAFT_PREFIX + projectId);
  } catch {
    /* ignore */
  }
}

type Listener = () => void;
const listeners = new Map<string, Set<Listener>>();

export function subscribeThread(projectId: string, cb: Listener): () => void {
  let set = listeners.get(projectId);
  if (!set) listeners.set(projectId, (set = new Set()));
  set.add(cb);
  return () => {
    set!.delete(cb);
    if (!set!.size) listeners.delete(projectId);
  };
}

/** Append straight to storage (a turn finished while no ChatPane was mounted),
 * then tell any ChatPane that mounted since to reload. */
export function appendStored(projectId: string, entry: ChatEntry): void {
  saveThread(projectId, [...loadThread(projectId), entry]);
  listeners.get(projectId)?.forEach((cb) => cb());
}

/* ------------------------------------------------------------------ */
/* Undo: restore the pre-turn content of every file a turn modified.   */

export interface UndoSnapshot {
  files: { path: string; content: string }[];
  /** Files the turn created. There is no delete endpoint, so they stay. */
  created: string[];
}

/** `before` is the project's file set captured before the turn; `written` is
 * what the turn wrote. Returns null when nothing restorable changed. */
export function buildUndo(
  before: Map<string, string> | null,
  written: { path: string; content: string }[] | null | undefined
): UndoSnapshot | null {
  if (!before || !written?.length) return null;
  const files: UndoSnapshot["files"] = [];
  const created: string[] = [];
  for (const w of written) {
    if (!before.has(w.path)) created.push(w.path);
    else if (before.get(w.path) !== w.content) files.push({ path: w.path, content: before.get(w.path) as string });
  }
  return files.length ? { files, created } : null;
}

/** An undo is only safe while no LATER successful turn touched the same files
 * (restoring would silently discard that newer work). */
export function canUndo(thread: ChatEntry[], index: number): boolean {
  const e = thread[index];
  if (!e || e.kind !== "result" || !e.undo || e.undone) return false;
  const mine = new Set<string>((e.undo.files || []).map((f: { path: string }) => f.path));
  for (let j = index + 1; j < thread.length; j++) {
    const later = thread[j];
    if (later.kind !== "result" || later.success === false) continue;
    const touched: string[] = later.touched || later.filesChanged || [];
    if (touched.some((p) => mine.has(p))) return false;
  }
  return true;
}
