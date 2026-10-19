import type { LintIssue } from "./canvasOps";

/*
 * Client + server lint, unified. The canvas lints locally on every edit
 * (canvasOps.lintGraph) and, when core supports it, POST /workflows/{id}/lint
 * adds server-only checks. Both sides derive the same stable id, so an issue
 * found by both collapses to one row and the server copy (richer message +
 * fix) wins.
 *
 * Server id rule (core WorkflowLintService): first 16 hex chars of
 * SHA-256("code|nodeId|edgeId"), "" for nulls; invalid-config appends
 * "|" + message because one node can carry several config errors.
 */

export type LintSeverity = "error" | "warning" | "info";

export type LintFix =
  | { kind: "delete-edge"; edgeId: string }
  | { kind: "add-node"; type: "trigger" }
  | { kind: "add-error-route"; nodeId: string }
  | { kind: "set-config"; nodeId: string; path: "config.maxIterations" | "config.url"; value?: unknown }
  | { kind: "open-config"; nodeId: string; field?: string }
  | { kind: "open-url"; url: string }
  | { kind: "enable-approval-policy"; actionType: string }
  | { kind: "insert-approval"; nodeId: string; insertBeforeNodeIds: string[] };

export type LintFixKind = LintFix["kind"];

export type ServerLintIssue = {
  id: string;
  code: string;
  severity: LintSeverity;
  nodeId: string | null;
  edgeId: string | null;
  message: string;
  fix: LintFix | null;
};

export type ServerLintResult = {
  issues: ServerLintIssue[];
  summary: { errors: number; warnings: number; infos: number };
};

export type UnifiedLintIssue = ServerLintIssue & { source: "client" | "server" | "both" };

/** Codes both sides compute; the client copy is always fresher for these. */
export const PARITY_CODES: ReadonlySet<string> = new Set([
  "no-trigger",
  "disconnected-node",
  "unreachable-from-trigger",
  "missing-error-route",
  "loop-no-max-iterations",
]);

// ------------------------------------------------------------------ ids

const encoder = new TextEncoder();

function toHex(bytes: Uint8Array): string {
  let out = "";
  for (const b of bytes) out += b.toString(16).padStart(2, "0");
  return out;
}

/** The exact pre-image the server hashes. Exported for tests. */
export function issueIdInput(code: string, nodeId?: string | null, edgeId?: string | null, message?: string | null): string {
  const base = `${code}|${nodeId ?? ""}|${edgeId ?? ""}`;
  return code === "invalid-config" ? `${base}|${message ?? ""}` : base;
}

export async function issueId(
  code: string,
  nodeId?: string | null,
  edgeId?: string | null,
  message?: string | null,
): Promise<string> {
  const data = encoder.encode(issueIdInput(code, nodeId, edgeId, message));
  const subtle = typeof globalThis.crypto !== "undefined" ? globalThis.crypto.subtle : undefined;
  if (subtle) {
    try {
      const digest = await subtle.digest("SHA-256", data);
      return toHex(new Uint8Array(digest)).slice(0, 16);
    } catch {
      /* fall through: subtle exists but refused (odd embedded contexts) */
    }
  }
  // crypto.subtle is secure-context only; plain-http staging still needs dedupe.
  return toHex(sha256(data)).slice(0, 16);
}

// Compact FIPS 180-4 SHA-256, used only when WebCrypto is unavailable.
const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
  0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
  0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
  0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
  0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
  0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

export function sha256(msg: Uint8Array): Uint8Array {
  const bitLen = msg.length * 8;
  const padded = new Uint8Array(((msg.length + 9 + 63) >> 6) << 6);
  padded.set(msg);
  padded[msg.length] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, Math.floor(bitLen / 0x100000000));
  view.setUint32(padded.length - 4, bitLen >>> 0);

  const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
  const w = new Uint32Array(64);
  const rotr = (x: number, n: number) => (x >>> n) | (x << (32 - n));
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const t1 = (hh + S1 + ch + K[i] + w[i]) >>> 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) >>> 0;
      hh = g;
      g = f;
      f = e;
      e = (d + t1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) >>> 0;
    }
    h[0] += a;
    h[1] += b;
    h[2] += c;
    h[3] += d;
    h[4] += e;
    h[5] += f;
    h[6] += g;
    h[7] += hh;
  }
  const out = new Uint8Array(32);
  const ov = new DataView(out.buffer);
  for (let i = 0; i < 8; i++) ov.setUint32(i * 4, h[i]);
  return out;
}

// ------------------------------------------------------------------ client semantics

type NodeLike = { id: string; data?: { config?: unknown } | Record<string, unknown> };

function onErrorTarget(node: NodeLike | undefined): string | null {
  const config = (node?.data as { config?: unknown } | undefined)?.config;
  if (!config || typeof config !== "object") return null;
  const v = (config as { onErrorNodeId?: unknown }).onErrorNodeId;
  return typeof v === "string" && v.trim() !== "" ? v : null;
}

/**
 * Server treats config.onErrorNodeId as an error route, so the client must too
 * or the two sides disagree and the chip flickers between counts.
 */
export function dropSatisfiedErrorRoutes<T extends { code: string; nodeId?: string | null }>(
  issues: T[],
  nodes: NodeLike[],
): T[] {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  return issues.filter((i) => !(i.code === "missing-error-route" && i.nodeId && onErrorTarget(byId.get(i.nodeId))));
}

function clientFix(issue: LintIssue): LintFix | null {
  switch (issue.code) {
    case "no-trigger":
      return { kind: "add-node", type: "trigger" };
    case "missing-error-route":
      return issue.nodeId ? { kind: "add-error-route", nodeId: issue.nodeId } : null;
    case "loop-no-max-iterations":
      return issue.nodeId ? { kind: "set-config", nodeId: issue.nodeId, path: "config.maxIterations" } : null;
    default:
      return null;
  }
}

/** Client LintIssue -> unified shape with the server-compatible id. */
export async function toUnifiedClient(issues: LintIssue[]): Promise<UnifiedLintIssue[]> {
  return Promise.all(
    issues.map(
      async (i): Promise<UnifiedLintIssue> => ({
        id: await issueId(i.code, i.nodeId ?? null, null, i.message),
        code: i.code,
        severity: i.severity === "error" ? "error" : "warning",
        nodeId: i.nodeId ?? null,
        edgeId: null,
        message: i.message,
        fix: clientFix(i),
        source: "client",
      }),
    ),
  );
}

// ------------------------------------------------------------------ merge

const RANK: Record<LintSeverity, number> = { error: 0, warning: 1, info: 2 };
const cmp = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

export function sortLint<T extends { severity: LintSeverity; code: string; nodeId: string | null }>(issues: T[]): T[] {
  return [...issues].sort(
    (a, b) => RANK[a.severity] - RANK[b.severity] || cmp(a.code, b.code) || cmp(a.nodeId ?? "", b.nodeId ?? ""),
  );
}

export type MergeOptions = {
  /**
   * Node ids currently on the canvas. Server issues pointing at nodes that no
   * longer exist (server linted a staler graph) are dropped.
   */
  nodeIds?: ReadonlySet<string>;
  /**
   * When the server linted the SAVED graph but the canvas is dirty, parity
   * findings the client no longer sees are stale; drop them.
   */
  clientAuthoritativeParity?: boolean;
};

/** Sync merge of already-identified issues. Server wins on message/fix/severity. */
export function mergeUnified(
  client: UnifiedLintIssue[],
  server: ServerLintIssue[],
  opts: MergeOptions = {},
): UnifiedLintIssue[] {
  const out = new Map<string, UnifiedLintIssue>();
  for (const c of client) if (!out.has(c.id)) out.set(c.id, c);
  for (const s of server) {
    if (opts.nodeIds && s.nodeId && !opts.nodeIds.has(s.nodeId)) continue;
    const prev = out.get(s.id);
    if (!prev && opts.clientAuthoritativeParity && PARITY_CODES.has(s.code)) continue;
    out.set(s.id, {
      ...s,
      fix: s.fix ?? prev?.fix ?? null,
      source: prev ? "both" : "server",
    });
  }
  return sortLint([...out.values()]);
}

export async function mergeLint(
  clientIssues: LintIssue[],
  serverIssues: ServerLintIssue[],
  opts: MergeOptions = {},
): Promise<UnifiedLintIssue[]> {
  return mergeUnified(await toUnifiedClient(clientIssues), serverIssues, opts);
}

// ------------------------------------------------------------------ parsing

const isStr = (v: unknown): v is string => typeof v === "string";
const strOrNull = (v: unknown): string | null => (isStr(v) && v !== "" ? v : null);

function parseFix(raw: unknown): LintFix | null {
  if (!raw || typeof raw !== "object") return null;
  const f = raw as Record<string, unknown>;
  switch (f.kind) {
    case "delete-edge":
      return isStr(f.edgeId) ? { kind: "delete-edge", edgeId: f.edgeId } : null;
    case "add-node":
      return f.type === "trigger" ? { kind: "add-node", type: "trigger" } : null;
    case "add-error-route":
      return isStr(f.nodeId) ? { kind: "add-error-route", nodeId: f.nodeId } : null;
    case "set-config":
      if (!isStr(f.nodeId) || (f.path !== "config.maxIterations" && f.path !== "config.url")) return null;
      return f.value === undefined || f.value === null
        ? { kind: "set-config", nodeId: f.nodeId, path: f.path }
        : { kind: "set-config", nodeId: f.nodeId, path: f.path, value: f.value };
    case "open-config":
      if (!isStr(f.nodeId)) return null;
      return isStr(f.field) && f.field !== "" ? { kind: "open-config", nodeId: f.nodeId, field: f.field } : { kind: "open-config", nodeId: f.nodeId };
    case "open-url":
      // Only same-origin paths or http(s); never let a payload smuggle javascript:.
      return isStr(f.url) && /^(https?:\/\/|\/(?!\/))/i.test(f.url) ? { kind: "open-url", url: f.url } : null;
    case "enable-approval-policy":
      // Only the action type is trusted; the call goes through the existing
      // governance wrapper, never to a server-supplied url/method.
      return isStr(f.actionType) && /^workflow\.node\.[a-z0-9_]+$/.test(f.actionType)
        ? { kind: "enable-approval-policy", actionType: f.actionType }
        : null;
    case "insert-approval": {
      // send-without-approval: the node ids an approval step goes in front of
      // (the send itself, or the loop / fan-out / parallel it is the body of).
      if (!isStr(f.nodeId) || f.nodeId === "") return null;
      const before = Array.isArray(f.insertBeforeNodeIds) ? f.insertBeforeNodeIds.filter((v): v is string => isStr(v) && v !== "") : [];
      return { kind: "insert-approval", nodeId: f.nodeId, insertBeforeNodeIds: before.length ? before : [f.nodeId] };
    }
    default:
      return null; // unknown future kinds: show the issue, hide the button
  }
}

/** Defensive parse of the /lint response; null when the shape is unrecognised. */
export function parseLintResponse(json: unknown): ServerLintResult | null {
  if (!json || typeof json !== "object") return null;
  const raw = (json as { issues?: unknown }).issues;
  if (!Array.isArray(raw)) return null;
  const issues: ServerLintIssue[] = [];
  for (const r of raw) {
    if (!r || typeof r !== "object") continue;
    const o = r as Record<string, unknown>;
    if (!isStr(o.id) || !isStr(o.code) || !isStr(o.message)) continue;
    const severity: LintSeverity = o.severity === "error" || o.severity === "info" ? o.severity : "warning";
    issues.push({
      id: o.id,
      code: o.code,
      severity,
      nodeId: strOrNull(o.nodeId),
      edgeId: strOrNull(o.edgeId),
      message: o.message,
      fix: parseFix(o.fix),
    });
  }
  return { issues, summary: summarizeLint(issues) };
}

export function summarizeLint(issues: { severity: LintSeverity }[]): ServerLintResult["summary"] {
  const s = { errors: 0, warnings: 0, infos: 0 };
  for (const i of issues) {
    if (i.severity === "error") s.errors++;
    else if (i.severity === "warning") s.warnings++;
    else s.infos++;
  }
  return s;
}
