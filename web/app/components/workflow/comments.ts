// Canvas comment threads - pure types + helpers (no React, no network).
//
// Contract: core WorkflowCommentController (/api/workflows/{id}/comments).
// GET returns roots in creation order, each with replies[] (one level only).
// A reply's nodeId mirrors its root's; resolved/resolvedBy/resolvedAt on every
// comment mirror the ROOT's (resolution is per thread). PATCH returns the
// patched comment with replies: [] - never trust that to mean "no replies",
// so replaceComment keeps the local replies of a root.
//
// Mentions: the body is plain text; the mention ids travel separately. The UI
// inserts "@<label>" tokens from a picked-people list and extractMentionIds
// only ever returns ids of people that were actually picked AND whose token
// is still in the text - it never guesses a user from free text.

/* ------------------------------------------------------------------ types */

export type CommentPerson = { id: string; displayName: string | null };
export type CommentAnchor = { x: number; y: number };

export interface CommentView {
  id: string;
  parentId: string | null;
  nodeId: string | null;
  anchor: CommentAnchor | null;
  body: string | null;
  mentions: CommentPerson[];
  author: CommentPerson;
  resolved: boolean;
  resolvedBy: string | null;
  resolvedAt: string | null;
  deleted: boolean;
  orphaned: boolean;
  canEdit: boolean;
  canResolve: boolean;
  createdAt: string;
  updatedAt: string | null;
  replies: CommentView[];
  /** Local only: an optimistic row the server has not confirmed yet. */
  pending?: boolean;
}

export type CommentCounts = { open: number; resolved: number };
export type CommentFilter = "open" | "resolved" | "all";

/** Where a new root thread goes: on a node, or free on the canvas (flow coords). */
export type NewThreadTarget = { nodeId: string } | CommentAnchor;

export type CreateCommentInput = {
  body: string;
  nodeId?: string;
  x?: number;
  y?: number;
  parentId?: string;
  mentions?: string[];
};

export type UpdateCommentInput = { body?: string; mentions?: string[]; resolved?: boolean };

export const COMMENT_MAX_CHARS = 4000;
export const COMMENT_MAX_MENTIONS = 20;

/* -------------------------------------------------------------- parsing */

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function strOrNull(v: unknown): string | null {
  return typeof v === "string" && v !== "" ? v : null;
}

function idOf(v: unknown): string | null {
  if (typeof v === "string" && v !== "") return v;
  if (typeof v === "number" && Number.isFinite(v)) return String(v);
  return null;
}

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function toPerson(v: unknown): CommentPerson | null {
  if (typeof v === "string" && v) return { id: v, displayName: null };
  if (!isRecord(v)) return null;
  const id = idOf(v.id);
  return id ? { id, displayName: strOrNull(v.displayName) } : null;
}

function toAnchor(v: unknown): CommentAnchor | null {
  if (!isRecord(v)) return null;
  const x = num(v.x);
  const y = num(v.y);
  return x === null || y === null ? null : { x, y };
}

/** One CommentView from untrusted JSON, or null when it is not one. */
export function normalizeComment(v: unknown): CommentView | null {
  if (!isRecord(v)) return null;
  const id = idOf(v.id);
  if (!id) return null;
  const rawReplies = Array.isArray(v.replies) ? v.replies : [];
  const replies: CommentView[] = [];
  for (const r of rawReplies) {
    const c = normalizeComment(r);
    if (c) replies.push({ ...c, parentId: c.parentId ?? id, replies: [] });
  }
  replies.sort(byCreated);
  const mentions: CommentPerson[] = [];
  if (Array.isArray(v.mentions)) {
    for (const m of v.mentions) {
      const p = toPerson(m);
      if (p) mentions.push(p);
    }
  }
  return {
    id,
    parentId: idOf(v.parentId),
    nodeId: strOrNull(v.nodeId),
    anchor: toAnchor(v.anchor),
    body: typeof v.body === "string" ? v.body : null,
    mentions,
    author: toPerson(v.author) ?? { id: "unknown", displayName: null },
    resolved: v.resolved === true,
    resolvedBy: strOrNull(v.resolvedBy),
    resolvedAt: strOrNull(v.resolvedAt),
    deleted: v.deleted === true,
    orphaned: v.orphaned === true,
    canEdit: v.canEdit === true,
    canResolve: v.canResolve === true,
    createdAt: strOrNull(v.createdAt) ?? new Date(0).toISOString(),
    updatedAt: strOrNull(v.updatedAt),
    replies,
  };
}

function byCreated(a: CommentView, b: CommentView): number {
  return Date.parse(a.createdAt) - Date.parse(b.createdAt);
}

/**
 * Roots in creation order, each with its replies. Accepts the server's nested
 * shape and also a flat list (replies folded into their root), so a future
 * core that flattens does not break the canvas. Orphan replies are dropped.
 */
export function normalizeThreads(raw: unknown): CommentView[] {
  if (!Array.isArray(raw)) return [];
  const roots: CommentView[] = [];
  const flatReplies: CommentView[] = [];
  for (const item of raw) {
    const c = normalizeComment(item);
    if (!c) continue;
    if (c.parentId) flatReplies.push(c);
    else roots.push(c);
  }
  const byId = new Map(roots.map((r) => [r.id, r]));
  for (const reply of flatReplies) {
    const root = byId.get(reply.parentId as string);
    if (root && !root.replies.some((x) => x.id === reply.id)) root.replies.push({ ...reply, replies: [] });
  }
  for (const r of roots) r.replies.sort(byCreated);
  // A soft-deleted root whose replies are all gone has nothing left to show.
  return roots.filter((r) => !(r.deleted && r.replies.length === 0)).sort(byCreated);
}

/* ------------------------------------------------------------ derivation */

/** Per-node thread counts for node footers. Free-anchored threads are not counted. */
export function threadCounts(threads: CommentView[]): Record<string, CommentCounts> {
  const out: Record<string, CommentCounts> = {};
  for (const t of threads) {
    if (!t.nodeId || t.parentId) continue;
    const c = out[t.nodeId] ?? (out[t.nodeId] = { open: 0, resolved: 0 });
    if (t.resolved) c.resolved++;
    else c.open++;
  }
  return out;
}

/** 1-based number of every thread, in creation order - pins and the panel share it. */
export function threadNumbers(threads: CommentView[]): Map<string, number> {
  return new Map(threads.map((t, i) => [t.id, i + 1]));
}

export type CanvasPin = { thread: CommentView; number: number; anchor: CommentAnchor };

/** Free-anchored roots (no node, has x/y) for the pin layer. */
export function canvasPins(threads: CommentView[]): CanvasPin[] {
  const out: CanvasPin[] = [];
  threads.forEach((t, i) => {
    if (!t.parentId && !t.nodeId && t.anchor) out.push({ thread: t, number: i + 1, anchor: t.anchor });
  });
  return out;
}

export function filterThreads(threads: CommentView[], filter: CommentFilter): CommentView[] {
  if (filter === "all") return threads;
  return threads.filter((t) => (filter === "resolved" ? t.resolved : !t.resolved));
}

/** Root id of the thread holding `commentId` (deep link ?comment=), or null. */
export function threadIdOf(threads: CommentView[], commentId: string): string | null {
  for (const t of threads) {
    if (t.id === commentId || t.replies.some((r) => r.id === commentId)) return t.id;
  }
  return null;
}

export function findComment(threads: CommentView[], id: string): { root: CommentView; comment: CommentView } | null {
  for (const t of threads) {
    if (t.id === id) return { root: t, comment: t };
    const r = t.replies.find((x) => x.id === id);
    if (r) return { root: t, comment: r };
  }
  return null;
}

/** Display label; a mentioned user may have no known name, so fall back to a short id. */
export function personLabel(p: CommentPerson | null | undefined): string {
  if (!p) return "Someone";
  if (p.displayName && p.displayName.trim()) return p.displayName.trim();
  return `user ${p.id.replace(/-/g, "").slice(0, 6)}`;
}

/**
 * True when the body was edited after posting. A root's updatedAt is also
 * bumped by resolve/reopen, so a bump that lands on resolvedAt is ignored.
 * (A reopen still looks like an edit - core has no separate editedAt yet.)
 */
export function isEdited(c: CommentView): boolean {
  if (c.deleted || !c.updatedAt || c.pending) return false;
  const created = Date.parse(c.createdAt);
  const updated = Date.parse(c.updatedAt);
  if (!Number.isFinite(created) || !Number.isFinite(updated) || updated - created < 1500) return false;
  if (!c.parentId && c.resolvedAt) {
    const res = Date.parse(c.resolvedAt);
    if (Number.isFinite(res) && Math.abs(updated - res) < 1500) return false;
  }
  return true;
}

/** "just now", "5m ago", "3h ago", "2d ago", then a short date. `now` is injectable for tests. */
export function relativeTime(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return "";
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return "";
  const s = Math.round((now - t) / 1000);
  if (s < 45) return "just now";
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  if (d < 7) return `${d}d ago`;
  const date = new Date(t);
  const sameYear = date.getFullYear() === new Date(now).getFullYear();
  return date.toLocaleDateString(undefined, sameYear ? { day: "numeric", month: "short" } : { day: "numeric", month: "short", year: "numeric" });
}

/* -------------------------------------------------------------- mentions */

/** The "@query" being typed at `caret`, or null. "@" must start the text or follow whitespace. */
export function activeMention(text: string, caret: number): { start: number; query: string } | null {
  const before = text.slice(0, Math.max(0, caret));
  const at = before.lastIndexOf("@");
  if (at < 0) return null;
  if (at > 0 && !/\s/.test(before[at - 1])) return null;
  const query = before.slice(at + 1);
  // Names may hold one space ("Ada Lo"), never a newline or a long run.
  if (/[\n\r]/.test(query) || query.length > 40 || /\s{2,}/.test(query) || /^\s/.test(query)) return null;
  return { start: at, query };
}

export function filterPeople<P extends CommentPerson>(people: P[], query: string, limit = 8): P[] {
  const q = query.trim().toLowerCase();
  const scored: { p: P; score: number }[] = [];
  for (const p of people) {
    const label = personLabel(p).toLowerCase();
    if (!q) scored.push({ p, score: 1 });
    else if (label.startsWith(q)) scored.push({ p, score: 0 });
    else if (label.split(/\s+/).some((w) => w.startsWith(q))) scored.push({ p, score: 1 });
    else if (label.includes(q)) scored.push({ p, score: 2 });
  }
  return scored
    .sort((a, b) => a.score - b.score || personLabel(a.p).localeCompare(personLabel(b.p)))
    .slice(0, limit)
    .map((s) => s.p);
}

/** Replace the active "@query" (start..caret) with "@Label " and return the new caret. */
export function insertMention(text: string, start: number, caret: number, person: CommentPerson): { text: string; caret: number } {
  const token = `@${personLabel(person)} `;
  const next = text.slice(0, start) + token + text.slice(caret).replace(/^ /, "");
  return { text: next, caret: start + token.length };
}

/**
 * Ids of picked people whose "@Label" token is still in the text, in pick
 * order, de-duplicated and capped at the server's limit. Never guesses.
 */
export function extractMentionIds(text: string, picked: CommentPerson[]): string[] {
  const out: string[] = [];
  for (const p of picked) {
    if (out.includes(p.id)) continue;
    if (hasToken(text, personLabel(p))) out.push(p.id);
    if (out.length >= COMMENT_MAX_MENTIONS) break;
  }
  return out;
}

function hasToken(text: string, label: string): boolean {
  const token = `@${label}`;
  let i = text.indexOf(token);
  while (i >= 0) {
    const before = i === 0 ? " " : text[i - 1];
    const after = text[i + token.length] ?? " ";
    if (/\s/.test(before) && !/[\p{L}\p{N}_]/u.test(after)) return true;
    i = text.indexOf(token, i + 1);
  }
  return false;
}

/**
 * Every label a stored mention may appear under in a body: the people-list
 * name the author picked, the name core returned, and the short-id fallback.
 * Core fills displayName from the user's latest comment (null if they never
 * commented), so it can differ from what was actually typed.
 */
export function mentionLabels(id: string, names: (string | null | undefined)[]): string[] {
  const out: string[] = [];
  for (const n of [...names, null]) {
    const l = personLabel({ id, displayName: n ?? null });
    if (!out.includes(l)) out.push(l);
  }
  return out;
}

/**
 * Mention ids to send with an edit, or null to leave `mentions` out of the
 * PATCH (core then keeps its stored list, which it replaces wholesale when
 * sent). Mentions are only sent when the author picked someone during this
 * edit or deleted a mention token that was verifiably in the old body; an
 * original mention whose token can't be found in the old body under any
 * known label is carried over rather than silently dropped.
 */
export function editMentionIds(input: {
  before: string;
  after: string;
  original: { id: string; labels: string[] }[];
  /** extractMentionIds(after, composerPicked) */
  pickedIds: string[];
  pickedDuringEdit: boolean;
}): string[] | null {
  const present = (body: string, labels: string[]) => labels.some((l) => hasToken(body, l));
  const removed = input.original.some((o) => present(input.before, o.labels) && !present(input.after, o.labels));
  if (!input.pickedDuringEdit && !removed) return null;
  const out: string[] = [];
  for (const o of input.original) {
    if (out.includes(o.id)) continue;
    if (!present(input.before, o.labels) || present(input.after, o.labels)) out.push(o.id);
  }
  for (const id of input.pickedIds) if (!out.includes(id)) out.push(id);
  return out.slice(0, COMMENT_MAX_MENTIONS);
}

export type BodySegment = { text: string; mention?: CommentPerson };

/** Split a body into plain text and "@Label" mention segments for rendering. */
export function splitMentions(body: string, mentions: CommentPerson[]): BodySegment[] {
  const labels = mentions
    .map((m) => ({ m, token: `@${personLabel(m)}` }))
    .sort((a, b) => b.token.length - a.token.length);
  if (!labels.length || !body) return body ? [{ text: body }] : [];
  const out: BodySegment[] = [];
  let buf = "";
  let i = 0;
  while (i < body.length) {
    const hit = body[i] === "@" && (i === 0 || /\s/.test(body[i - 1]))
      ? labels.find(({ token }) => body.startsWith(token, i) && !/[\p{L}\p{N}_]/u.test(body[i + token.length] ?? " "))
      : undefined;
    if (hit) {
      if (buf) out.push({ text: buf });
      buf = "";
      out.push({ text: hit.token, mention: hit.m });
      i += hit.token.length;
    } else {
      buf += body[i];
      i++;
    }
  }
  if (buf) out.push({ text: buf });
  return out;
}

/* ------------------------------------------------- optimistic reducers */
// All return a new array and never mutate their input.

/** Add a root (appended) or a reply (appended to its root's replies). */
export function insertComment(threads: CommentView[], c: CommentView): CommentView[] {
  if (!c.parentId) {
    if (threads.some((t) => t.id === c.id)) return replaceComment(threads, c.id, c);
    return [...threads, c];
  }
  return threads.map((t) => {
    if (t.id !== c.parentId) return t;
    if (t.replies.some((r) => r.id === c.id)) return { ...t, replies: t.replies.map((r) => (r.id === c.id ? c : r)) };
    return { ...t, replies: [...t.replies, { ...c, replies: [] }] };
  });
}

/**
 * Replace the comment `id` (also used to swap a temp id for the server row).
 * A root keeps its local replies when `next.replies` is empty (PATCH shape).
 */
export function replaceComment(threads: CommentView[], id: string, next: CommentView): CommentView[] {
  return threads.map((t) => {
    if (t.id === id) return { ...next, replies: next.replies.length ? next.replies : t.replies };
    if (!t.replies.some((r) => r.id === id)) return t;
    return { ...t, replies: t.replies.map((r) => (r.id === id ? { ...next, replies: [] } : r)) };
  });
}

/** Shallow-merge fields into the comment `id`. */
export function patchComment(threads: CommentView[], id: string, patch: Partial<CommentView>): CommentView[] {
  const hit = findComment(threads, id);
  if (!hit) return threads;
  return replaceComment(threads, id, { ...hit.comment, ...patch, replies: hit.comment.replies });
}

/** Resolution is per thread: write it to the root and mirror it on every reply. */
export function setThreadResolved(
  threads: CommentView[],
  rootId: string,
  resolved: boolean,
  resolvedBy: string | null,
  resolvedAt: string | null,
): CommentView[] {
  const f = { resolved, resolvedBy: resolved ? resolvedBy : null, resolvedAt: resolved ? resolvedAt : null };
  return threads.map((t) => (t.id !== rootId ? t : { ...t, ...f, replies: t.replies.map((r) => ({ ...r, ...f })) }));
}

/**
 * Mirror of core's delete: a reply goes away; a root with live replies is
 * blanked (deleted, body null); any other root goes away.
 */
export function removeComment(threads: CommentView[], id: string): CommentView[] {
  const out: CommentView[] = [];
  for (const t of threads) {
    if (t.id === id) {
      if (t.replies.length) out.push({ ...t, deleted: true, body: null, mentions: [], canEdit: false });
      continue;
    }
    if (t.replies.some((r) => r.id === id)) {
      const replies = t.replies.filter((r) => r.id !== id);
      // A blanked root loses its last reason to exist with its last reply.
      if (t.deleted && replies.length === 0) continue;
      out.push({ ...t, replies });
    } else {
      out.push(t);
    }
  }
  return out;
}

/** Put a whole thread snapshot back (rollback), at `index` when it was removed. */
export function restoreThread(threads: CommentView[], snapshot: CommentView, index: number): CommentView[] {
  if (threads.some((t) => t.id === snapshot.id)) return threads.map((t) => (t.id === snapshot.id ? snapshot : t));
  const out = threads.slice();
  out.splice(Math.max(0, Math.min(index, out.length)), 0, snapshot);
  return out;
}

let tempSeq = 0;

/** A local placeholder shown until the server confirms the create. */
export function optimisticComment(input: {
  body: string;
  mentions: CommentPerson[];
  author: CommentPerson;
  root: CommentView | null;
  now?: string;
}): CommentView {
  tempSeq++;
  const now = input.now ?? new Date().toISOString();
  const root = input.root;
  return {
    id: `temp-${Date.now().toString(36)}-${tempSeq}`,
    parentId: root ? root.id : null,
    nodeId: root ? root.nodeId : null,
    anchor: null,
    body: input.body,
    mentions: input.mentions,
    author: input.author,
    resolved: root ? root.resolved : false,
    resolvedBy: root ? root.resolvedBy : null,
    resolvedAt: root ? root.resolvedAt : null,
    deleted: false,
    orphaned: root ? root.orphaned : false,
    canEdit: false,
    canResolve: false,
    createdAt: now,
    updatedAt: now,
    replies: [],
    pending: true,
  };
}

/**
 * A server refresh replaces the list, but a reply still in flight (pending,
 * temp id) must not blink away: carry local pending rows onto the fresh list.
 */
export function mergePending(server: CommentView[], local: CommentView[]): CommentView[] {
  let out = server;
  for (const t of local) {
    for (const r of t.replies) {
      if (r.pending && out.some((s) => s.id === t.id)) out = insertComment(out, r);
    }
  }
  return out;
}

/**
 * The comment to send a resolve/reopen PATCH to. Core resolves the root
 * through any reply, but a blanked (deleted) root answers 410, so route
 * through the first live, confirmed reply. Null = nothing can carry it.
 */
export function resolveTargetId(thread: CommentView): string | null {
  if (!thread.deleted) return thread.id;
  return thread.replies.find((r) => !r.pending && !r.deleted && !isTempId(r.id))?.id ?? null;
}

export function isTempId(id: string): boolean {
  return id.startsWith("temp-");
}

/** Client-side body check that mirrors core's 400s, so most never leave the browser. */
export function validateBody(body: string): string | null {
  const b = body.trim();
  if (!b) return "A comment can't be empty.";
  if (b.length > COMMENT_MAX_CHARS) return `A comment can be at most ${COMMENT_MAX_CHARS} characters.`;
  return null;
}
