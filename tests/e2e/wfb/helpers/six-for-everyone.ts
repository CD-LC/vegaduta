import type { TestInfo } from "@playwright/test";

/**
 * Helpers for tests-prod/six-for-everyone-live.spec.ts - the signed-in smoke
 * suite for the six "for everyone" surfaces (Research, Tutor, Memory,
 * Describe-it-automate-it workflows, Attack test, Playground header).
 *
 * Pure functions + small env gates only; sign-in, console collection and
 * bearer-token reading stay in prod-session / prod-console / prod-api so this
 * suite uses the SAME credential mechanism as every other prod spec.
 */

/**
 * SPEND GATE. A step that makes the platform call a hosted model costs real
 * money on production, so it is opt-in separately from E2E_ALLOW_MUTATIONS:
 *
 *   E2E_ALLOW_SPEND=1
 */
export const ALLOW_SPEND = process.env.E2E_ALLOW_SPEND === "1";

/** Skips (not fails) when the spend opt-in is absent. */
export function requireSpendOptIn(testInfo: TestInfo) {
  testInfo.skip(
    !ALLOW_SPEND,
    "E2E_ALLOW_SPEND=1 not set - this spec makes the platform call a hosted model " +
      "(real cost). Opt in explicitly to run it."
  );
}

/**
 * A unique, greppable marker for everything this run creates, so a leftover
 * row in production can be recognised (and removed) by hand if a cleanup ever
 * fails. Lower-case alphanumerics only, so it survives slugging/normalising.
 */
export function newRunMarker(): string {
  return `E2E-SIX-${Date.now().toString(36)}-${Math.floor(Math.random() * 36 ** 3).toString(36)}`;
}

/**
 * Feature probes that legitimately answer 401/404 (feature switched off in
 * this core build, or a plan/permission-gated read that the page handles
 * quietly - the web api clients use toJsonQuietOn for exactly these). Matched
 * against the "<status> <url>" strings ProdErrorCollector.unexpected() returns.
 * Anything else with a 4xx/5xx is a real finding.
 */
const EXPECTED_PROBE_RESPONSES: RegExp[] = [
  /^(401|404) https?:\/\/api\.[^/]+\/api\/tutor(\/|\?|$)/,
  /^(401|404|503) https?:\/\/api\.[^/]+\/api\/research(\/|\?|$)/,
  /^(401|404) https?:\/\/api\.[^/]+\/api\/memory(\/|\?|$)/,
  // Attack test / simulations / eval gate self-hide on 403/404 (older core or flag off).
  /^(401|404) https?:\/\/api\.[^/]+\/api\/agents\/[^/]+\/(simulations|lab|eval-gate|quality)(\/|\?|$)/,
  /^(401|404) https?:\/\/api\.[^/]+\/api\/quality(\/|\?|$)/,
];

/** True when a collector entry is one of the expected feature probes above. */
export function isExpectedProbe(entry: string): boolean {
  return EXPECTED_PROBE_RESPONSES.some((re) => re.test(entry));
}

/** Collector entries minus expected 401/404 feature probes. */
export function unexpectedErrors(entries: string[]): string[] {
  return entries.filter((e) => !isExpectedProbe(e));
}

// ---------------------------------------------------------------- geometry

export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * True when two boxes overlap by more than `tolerance` px on BOTH axes.
 * Boxes that merely touch, or overlap by sub-pixel rounding, do not count.
 */
export function boxesOverlap(a: Box, b: Box, tolerance = 1): boolean {
  const overlapX = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x);
  const overlapY = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
  return overlapX > tolerance && overlapY > tolerance;
}

/** Every overlapping pair among the named boxes, as "a <-> b" strings (empty = clean). */
export function overlappingPairs(named: Array<{ name: string; box: Box }>, tolerance = 1): string[] {
  const out: string[] = [];
  for (let i = 0; i < named.length; i++) {
    for (let j = i + 1; j < named.length; j++) {
      if (boxesOverlap(named[i].box, named[j].box, tolerance)) {
        out.push(`${named[i].name} <-> ${named[j].name}`);
      }
    }
  }
  return out;
}

// ------------------------------------------------- workflow graph send gate

interface WireNode {
  id?: string;
  type?: string;
  data?: { label?: string; config?: Record<string, unknown> };
}
interface WireEdge {
  source?: string;
  target?: string;
}
export interface WireGraph {
  nodes?: WireNode[];
  edges?: WireEdge[];
}

// Mirrors core WorkflowSendApprovalGuard (core/workflow/.../guidance/) - the
// server-side definition of "this node sends something out" and "this node is
// a human gate". Kept as a small copy on purpose: this suite only reads the
// generated graph over HTTP and must not import from web/ or core/.
const MESSAGE_CHANNELS = new Set(["email", "telegram", "whatsapp", "teams"]);
const READ_ONLY_HTTP = new Set(["GET", "HEAD", "OPTIONS"]);

function text(v: unknown): string {
  return v == null ? "" : String(v).trim();
}

function configOf(node: WireNode): Record<string, unknown> {
  return node.data?.config ?? {};
}

/** True when running the node delivers a message or mutates an external system. */
export function isOutboundSend(node: WireNode): boolean {
  const type = text(node.type).toLowerCase();
  const config = configOf(node);
  switch (type) {
    case "output":
      return MESSAGE_CHANNELS.has(text(config.channel).toLowerCase());
    case "bulk_email":
      return true;
    case "http": {
      const method = text(config.method).toUpperCase();
      return method !== "" && !READ_ONLY_HTTP.has(method);
    }
    case "tool":
      return text(config.toolName).toLowerCase().startsWith("send_");
    case "sub_flow": {
      const body = config.body as WireGraph | undefined;
      return Array.isArray(body?.nodes) && body!.nodes!.some(isOutboundSend);
    }
    default:
      return false;
  }
}

/** A human gate: approval, human_input, or any node flagged requiresApproval. */
export function isHumanGate(node: WireNode): boolean {
  const type = text(node.type).toLowerCase();
  return type === "approval" || type === "human_input" || configOf(node).requiresApproval === true;
}

export interface SendGateReport {
  /** Labels of every outbound-send node in the graph. */
  sends: string[];
  /** Sends a Trigger can reach WITHOUT passing a human gate first. */
  ungatedSends: string[];
  /** Number of approval / human_input nodes in the graph. */
  approvalNodes: number;
}

/**
 * Walks the graph from every Trigger (or, if there is none, every node with no
 * incoming edge) and reports which outbound sends are reachable without first
 * passing a human gate. The walk stops at a gate, so anything behind one is
 * gated by construction. Sends no Trigger can reach never run and are ignored,
 * exactly as the server guard does.
 */
export function analyseSendGates(graph: WireGraph | null | undefined): SendGateReport {
  const nodes = Array.isArray(graph?.nodes) ? graph!.nodes! : [];
  const edges = Array.isArray(graph?.edges) ? graph!.edges! : [];
  const byId = new Map<string, WireNode>();
  for (const n of nodes) if (n.id) byId.set(n.id, n);

  const outgoing = new Map<string, string[]>();
  const hasIncoming = new Set<string>();
  for (const e of edges) {
    if (!e.source || !e.target) continue;
    (outgoing.get(e.source) ?? outgoing.set(e.source, []).get(e.source)!).push(e.target);
    hasIncoming.add(e.target);
  }

  let starts = nodes.filter((n) => text(n.type).toLowerCase() === "trigger" && n.id).map((n) => n.id!);
  if (starts.length === 0) starts = nodes.filter((n) => n.id && !hasIncoming.has(n.id)).map((n) => n.id!);

  const label = (n: WireNode) => n.data?.label || n.id || n.type || "?";
  const ungated = new Set<string>();
  const seen = new Set<string>();
  const queue = [...starts];
  while (queue.length) {
    const id = queue.shift()!;
    if (seen.has(id)) continue;
    seen.add(id);
    const node = byId.get(id);
    if (!node) continue;
    if (isHumanGate(node)) continue; // everything behind this node is gated
    if (isOutboundSend(node)) ungated.add(label(node));
    for (const next of outgoing.get(id) ?? []) queue.push(next);
  }

  return {
    sends: nodes.filter(isOutboundSend).map(label),
    ungatedSends: [...ungated],
    approvalNodes: nodes.filter((n) => ["approval", "human_input"].includes(text(n.type).toLowerCase())).length,
  };
}
