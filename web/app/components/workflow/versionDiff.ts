// Version diff ("what changed between v3 and v5") - fetchers + pure helpers
// behind VersionDiffPanel.
//
// Core: WorkflowVersionController (core/workflow/.../version/)
//   GET /api/workflows/{id}/versions            -> WorkflowVersionResponse[] (newest first)
//   GET /api/workflows/{id}/versions/diff?from&to -> WorkflowVersionService#diffVersions
//
// The fetchers never throw and never toast: `unsupported` = the route is
// missing on this core (hide the feature quietly), `message` = a real failure
// the panel shows inline with a retry. Everything else in this file is pure
// so it unit-tests without a network (versionDiff.test.ts).

import { apiFetch, toJsonQuietOn } from "../../lib/api/core";

// ---------------------------------------------------------------------------
// Wire shapes
// ---------------------------------------------------------------------------

export type WorkflowVersionRow = {
  id: string;
  versionNumber: number;
  workflowName: string;
  status: string | null;
  changeSummary: string | null;
  createdBy: string | null;
  createdAt: string | null;
  /** STAGING / PRODUCTION when a release label points at this version (null otherwise or on an older core). */
  label?: "STAGING" | "PRODUCTION" | null;
};

/** One leaf-level change inside a node: path is "type", "label" or "config.a.b". */
export type DiffField = { path: string; before: string | null; after: string | null };

export type DiffNodeBrief = { id: string; type: string | null; label: string | null };
export type DiffChangedNode = DiffNodeBrief & { changes: string[]; fields: DiffField[] };
export type DiffEdgeBrief = { id: string | null; source: string | null; target: string | null; sourceHandle: string | null };

/** details_json (schedule, limits, tuning) field change. */
export type DiffDetailField = { field: string; from: string | null; to: string | null };

export type DiffVersionSummary = {
  versionNumber: number;
  workflowName: string | null;
  status: string | null;
  changeSummary: string | null;
  createdBy: string | null;
  createdAt: string | null;
};

export type WorkflowVersionDiff = {
  meta: {
    fromVersion: number;
    toVersion: number;
    createdAt: string | null;
    author: string | null;
    fromCreatedAt: string | null;
    fromAuthor: string | null;
  };
  graph: {
    addedNodes: DiffNodeBrief[];
    removedNodes: DiffNodeBrief[];
    changedNodes: DiffChangedNode[];
    addedEdges: DiffEdgeBrief[];
    removedEdges: DiffEdgeBrief[];
  };
  details: DiffDetailField[];
  nameChanged: boolean;
  descriptionChanged: boolean;
  statusChanged: boolean;
  from: DiffVersionSummary | null;
  to: DiffVersionSummary | null;
};

export type DiffFetchResult<T> =
  | { ok: true; data: T; unsupported?: undefined; message?: undefined }
  | { ok: false; unsupported: boolean; message: string | null; data?: undefined };

// ---------------------------------------------------------------------------
// Fetchers
// ---------------------------------------------------------------------------

/** Statuses answered inline by the panel instead of the global error toast. */
const QUIET = [400, 403, 404, 405, 409, 422, 429, 500, 501, 502, 503, 504];

function statusOf(err: unknown): number | null {
  if (err && typeof err === "object" && "status" in err) {
    const s = (err as { status: unknown }).status;
    return typeof s === "number" ? s : null;
  }
  return null;
}

function messageOf(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}

/**
 * Maps a fetch failure to the result union. `missingRoute` are the statuses
 * that mean "this core has no such endpoint". A non-JSON 200 (SPA/proxy
 * fallback page) surfaces as a SyntaxError from toJson and counts as missing.
 */
function classify<T>(err: unknown, missingRoute: number[]): DiffFetchResult<T> {
  const status = statusOf(err);
  if (status != null) {
    if (missingRoute.includes(status)) return { ok: false, unsupported: true, message: null };
    return { ok: false, unsupported: false, message: messageOf(err, "Could not load versions.") };
  }
  if (err instanceof SyntaxError) return { ok: false, unsupported: true, message: null };
  return { ok: false, unsupported: false, message: messageOf(err, "Could not reach the server.") };
}

/** GET /versions - newest first. A 404 here means the route (or workflow) is not there: unsupported. */
export async function fetchWorkflowVersions(workflowId: string): Promise<DiffFetchResult<WorkflowVersionRow[]>> {
  try {
    const res = await apiFetch(`/api/workflows/${encodeURIComponent(workflowId)}/versions`, { skipGetCache: true });
    const raw: unknown = await toJsonQuietOn(QUIET)(res);
    return { ok: true, data: normalizeVersions(raw) };
  } catch (err) {
    return classify(err, [404, 405, 501]);
  }
}

/**
 * GET /versions/diff?from&to. A 404 here is a missing version (the list call
 * already proved the route family exists), so it stays a visible message.
 */
export async function fetchWorkflowVersionDiff(
  workflowId: string,
  from: number,
  to: number,
): Promise<DiffFetchResult<WorkflowVersionDiff>> {
  try {
    const res = await apiFetch(
      `/api/workflows/${encodeURIComponent(workflowId)}/versions/diff?from=${encodeURIComponent(String(from))}&to=${encodeURIComponent(String(to))}`,
    );
    const raw: unknown = await toJsonQuietOn(QUIET)(res);
    if (!raw || typeof raw !== "object") return { ok: false, unsupported: true, message: null };
    return { ok: true, data: normalizeDiff(raw) };
  } catch (err) {
    return classify(err, [405, 501]);
  }
}

// ---------------------------------------------------------------------------
// Normalisers (defensive: older cores omit fields, never trust shapes)
// ---------------------------------------------------------------------------

type Obj = Record<string, unknown>;

const isObj = (v: unknown): v is Obj => !!v && typeof v === "object" && !Array.isArray(v);
const str = (v: unknown): string | null => (typeof v === "string" ? v : typeof v === "number" ? String(v) : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : typeof v === "string" && v.trim() !== "" && Number.isFinite(Number(v)) ? Number(v) : null);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

/** The release label the server returns on a version row; anything else (including absent) is no label. */
export function normalizeLabel(raw: unknown): "STAGING" | "PRODUCTION" | null {
  const v = typeof raw === "string" ? raw.trim().toUpperCase() : "";
  return v === "STAGING" || v === "PRODUCTION" ? v : null;
}

export function normalizeVersions(raw: unknown): WorkflowVersionRow[] {
  const rows: WorkflowVersionRow[] = [];
  for (const r of arr(raw)) {
    if (!isObj(r)) continue;
    const n = num(r.versionNumber);
    if (n == null) continue;
    rows.push({
      id: str(r.id) ?? `v${n}`,
      versionNumber: n,
      workflowName: str(r.workflowName) ?? "",
      status: str(r.status),
      changeSummary: str(r.changeSummary),
      createdBy: str(r.createdBy),
      createdAt: str(r.createdAt),
      label: normalizeLabel(r.label),
    });
  }
  return rows.sort((a, b) => b.versionNumber - a.versionNumber);
}

function nodeBrief(v: unknown): DiffNodeBrief | null {
  if (!isObj(v)) return null;
  const id = str(v.id);
  if (!id) return null;
  return { id, type: str(v.type), label: str(v.label) };
}

function edgeBrief(v: unknown): DiffEdgeBrief | null {
  if (!isObj(v)) return null;
  return { id: str(v.id), source: str(v.source), target: str(v.target), sourceHandle: str(v.sourceHandle) };
}

function field(v: unknown): DiffField | null {
  if (!isObj(v)) return null;
  const path = str(v.path);
  if (!path) return null;
  return { path, before: str(v.before), after: str(v.after) };
}

function summary(v: unknown): DiffVersionSummary | null {
  if (!isObj(v)) return null;
  const n = num(v.versionNumber);
  if (n == null) return null;
  return {
    versionNumber: n,
    workflowName: str(v.workflowName),
    status: str(v.status),
    changeSummary: str(v.changeSummary),
    createdBy: str(v.createdBy),
    createdAt: str(v.createdAt),
  };
}

const present = <T,>(v: T | null): v is T => v != null;

export function normalizeDiff(raw: unknown): WorkflowVersionDiff {
  const r: Obj = isObj(raw) ? raw : {};
  const g: Obj = isObj(r.graph) ? r.graph : {};
  const m: Obj = isObj(r.meta) ? r.meta : {};
  const from = summary(r.from);
  const to = summary(r.to);
  return {
    meta: {
      fromVersion: num(m.fromVersion) ?? from?.versionNumber ?? 0,
      toVersion: num(m.toVersion) ?? to?.versionNumber ?? 0,
      createdAt: str(m.createdAt) ?? to?.createdAt ?? null,
      author: str(m.author) ?? to?.createdBy ?? null,
      fromCreatedAt: str(m.fromCreatedAt) ?? from?.createdAt ?? null,
      fromAuthor: str(m.fromAuthor) ?? from?.createdBy ?? null,
    },
    graph: {
      addedNodes: arr(g.addedNodes).map(nodeBrief).filter(present),
      removedNodes: arr(g.removedNodes).map(nodeBrief).filter(present),
      changedNodes: arr(g.changedNodes)
        .map((c): DiffChangedNode | null => {
          const b = nodeBrief(c);
          if (!b || !isObj(c)) return null;
          return {
            ...b,
            changes: arr(c.changes).map(str).filter(present),
            fields: arr(c.fields).map(field).filter(present),
          };
        })
        .filter(present),
      addedEdges: arr(g.addedEdges).map(edgeBrief).filter(present),
      removedEdges: arr(g.removedEdges).map(edgeBrief).filter(present),
    },
    details: arr(r.details)
      .map((d): DiffDetailField | null => {
        if (!isObj(d)) return null;
        const f = str(d.field);
        return f ? { field: f, from: str(d.from), to: str(d.to) } : null;
      })
      .filter(present),
    nameChanged: r.nameChanged === true,
    descriptionChanged: r.descriptionChanged === true,
    statusChanged: r.statusChanged === true,
    from,
    to,
  };
}

// ---------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------

/**
 * Default comparison: previous vs latest. With `currentVersion` (and it is in
 * the list) that version is the "to" side and the one before it the "from".
 * null when fewer than two versions exist.
 */
export function defaultPair(versions: WorkflowVersionRow[], currentVersion?: number | null): { from: number; to: number } | null {
  const nums = Array.from(new Set(versions.map((v) => v.versionNumber))).sort((a, b) => b - a);
  if (nums.length < 2) return null;
  let toIdx = 0;
  if (currentVersion != null) {
    const i = nums.indexOf(currentVersion);
    if (i >= 0 && i < nums.length - 1) toIdx = i;
  }
  return { from: nums[toIdx + 1], to: nums[toIdx] };
}

export type DiffSummary = {
  nodesAdded: number;
  nodesRemoved: number;
  nodesChanged: number;
  edgesAdded: number;
  edgesRemoved: number;
  /** name/description/status flags plus details_json rows. */
  workflowChanges: number;
  total: number;
};

export function summarizeDiff(diff: WorkflowVersionDiff): DiffSummary {
  const g = diff.graph;
  const workflowChanges =
    (diff.nameChanged ? 1 : 0) + (diff.descriptionChanged ? 1 : 0) + (diff.statusChanged ? 1 : 0) + diff.details.length;
  const s = {
    nodesAdded: g.addedNodes.length,
    nodesRemoved: g.removedNodes.length,
    nodesChanged: g.changedNodes.length,
    edgesAdded: g.addedEdges.length,
    edgesRemoved: g.removedEdges.length,
    workflowChanges,
  };
  return { ...s, total: s.nodesAdded + s.nodesRemoved + s.nodesChanged + s.edgesAdded + s.edgesRemoved + workflowChanges };
}

/** Stable edge key: the edge id, else "source->target" (+ ":handle"). Canvas edges should be matched with the same function. */
export function edgeKey(e: { id?: string | null; source?: string | null; target?: string | null; sourceHandle?: string | null }): string {
  if (e.id) return e.id;
  return `${e.source ?? "?"}->${e.target ?? "?"}${e.sourceHandle ? `:${e.sourceHandle}` : ""}`;
}

export type NodeDecoration = "added" | "removed" | "changed";
export type EdgeDecoration = "added" | "removed";
export type DiffDecorations = {
  nodes: Record<string, NodeDecoration>;
  edges: Record<string, EdgeDecoration>;
};

/**
 * Canvas overlay map. Node keys are node ids; edge keys come from edgeKey().
 * "removed" entries name nodes/edges that do NOT exist in the "to" graph -
 * an overlay that only paints what is on the canvas can ignore them. A
 * rewired edge (same id, new endpoints) arrives as removed+added; "added"
 * wins because that id is the one on the canvas.
 */
export function diffDecorations(diff: WorkflowVersionDiff): DiffDecorations {
  const nodes: Record<string, NodeDecoration> = {};
  const edges: Record<string, EdgeDecoration> = {};
  for (const n of diff.graph.removedNodes) nodes[n.id] = "removed";
  for (const n of diff.graph.changedNodes) nodes[n.id] = "changed";
  for (const n of diff.graph.addedNodes) nodes[n.id] = "added";
  for (const e of diff.graph.removedEdges) edges[edgeKey(e)] = "removed";
  for (const e of diff.graph.addedEdges) edges[edgeKey(e)] = "added";
  return { nodes, edges };
}

export type FieldGroupKey = "type" | "label" | "config" | "other";
export type FieldGroup = { key: FieldGroupKey; title: string; fields: (DiffField & { display: string })[] };

const GROUP_TITLES: Record<FieldGroupKey, string> = { type: "Type", label: "Label", config: "Settings", other: "Other" };
const GROUP_ORDER: FieldGroupKey[] = ["type", "label", "config", "other"];

/** Groups node field changes by top-level segment; `display` drops the group prefix ("config.url" -> "url"). */
export function groupFields(fields: DiffField[]): FieldGroup[] {
  const buckets: Record<FieldGroupKey, FieldGroup["fields"]> = { type: [], label: [], config: [], other: [] };
  for (const f of fields) {
    const dot = f.path.indexOf(".");
    const head = dot < 0 ? f.path : f.path.slice(0, dot);
    const key: FieldGroupKey = head === "type" || head === "label" || head === "config" ? head : "other";
    const rest = key === "other" ? f.path : dot < 0 ? "" : f.path.slice(dot + 1);
    buckets[key].push({ ...f, display: rest || GROUP_TITLES[key] });
  }
  return GROUP_ORDER.filter((k) => buckets[k].length > 0).map((k) => ({ key: k, title: GROUP_TITLES[k], fields: buckets[k] }));
}

export const REDACTED = "[redacted]";

/** True when the whole value is the server's credential mask. */
export function isRedacted(v: string | null | undefined): boolean {
  return typeof v === "string" && v.trim() === REDACTED;
}

/** True when the value contains a mask anywhere (payload redaction inside a longer string). */
export function hasRedaction(v: string | null | undefined): boolean {
  return typeof v === "string" && v.includes(REDACTED);
}

// ---------------------------------------------------------------------------
// Word-level diff for short strings
// ---------------------------------------------------------------------------

export type WordSegment = { text: string; changed: boolean };

/** Longest string (either side) that gets inline highlighting; longer values show plain. */
export const WORD_DIFF_MAX_CHARS = 400;

// \p{M}: combining marks (Devanagari matras, accents) belong to the word.
const TOKEN_RE = /[\p{L}\p{M}\p{N}_]+|\s+|[^\p{L}\p{M}\p{N}_\s]/gu;

export function tokenize(s: string): string[] {
  return s.match(TOKEN_RE) ?? [];
}

function pushSeg(out: WordSegment[], text: string, changed: boolean) {
  const last = out[out.length - 1];
  if (last && last.changed === changed) last.text += text;
  else out.push({ text, changed });
}

/**
 * LCS over word/space/punctuation tokens. Returns null when either side is
 * absent, redacted, or too long to be worth highlighting.
 */
export function wordDiff(before: string | null, after: string | null): { before: WordSegment[]; after: WordSegment[] } | null {
  if (before == null || after == null) return null;
  if (before.length > WORD_DIFF_MAX_CHARS || after.length > WORD_DIFF_MAX_CHARS) return null;
  if (isRedacted(before) || isRedacted(after)) return null;
  const a = tokenize(before);
  const b = tokenize(after);
  const n = a.length;
  const m = b.length;
  const w = m + 1;
  const dp = new Uint16Array((n + 1) * w);
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i * w + j] = a[i] === b[j] ? dp[(i + 1) * w + j + 1] + 1 : Math.max(dp[(i + 1) * w + j], dp[i * w + j + 1]);
    }
  }
  const outA: WordSegment[] = [];
  const outB: WordSegment[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      pushSeg(outA, a[i], false);
      pushSeg(outB, b[j], false);
      i++;
      j++;
    } else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) {
      pushSeg(outA, a[i++], true);
    } else {
      pushSeg(outB, b[j++], true);
    }
  }
  while (i < n) pushSeg(outA, a[i++], true);
  while (j < m) pushSeg(outB, b[j++], true);
  return { before: outA, after: outB };
}

// ---------------------------------------------------------------------------
// Display helpers
// ---------------------------------------------------------------------------

export function formatWhen(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  try {
    return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(d);
  } catch {
    return d.toLocaleString();
  }
}

/** "ACTIVE" -> "Active". */
export function prettyStatus(s: string | null | undefined): string {
  if (!s) return "Unknown";
  const t = s.toLowerCase().replace(/_/g, " ");
  return t.charAt(0).toUpperCase() + t.slice(1);
}
