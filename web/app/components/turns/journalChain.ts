import type { TurnJournalEvent } from "../../lib/api/turns";

// Client-side mirror of core TurnHashChain (ADR-0020). eventHash = SHA-256 hex of
// prevHash|turnId|agentId|seq|type|payloadHash|summary|createdAtEpochMillis.

export const GENESIS_HASH = "0".repeat(64);

export interface ChainCheck {
  intact: boolean;
  brokenAtSeq: number;
  reason: string;
}

export function epochMillis(createdAt: string | number): number {
  if (typeof createdAt === "number") {
    // Jackson writes Instants as epoch seconds with a fraction when timestamps are on.
    return createdAt < 1e11 ? Math.floor(createdAt * 1000) : Math.floor(createdAt);
  }
  return Date.parse(createdAt);
}

export async function sha256Hex(text: string): Promise<string | null> {
  const subtle = typeof globalThis !== "undefined" ? globalThis.crypto?.subtle : undefined;
  if (!subtle) return null;
  const digest = await subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

export async function eventHash(e: TurnJournalEvent): Promise<string | null> {
  return sha256Hex(
    [e.prevHash, e.turnId, e.agentId, String(e.seq), e.type, e.payloadHash, e.summary, String(epochMillis(e.createdAt))].join("|")
  );
}

/** Recomputes every hash. Returns null when the browser cannot hash (no Web Crypto), so callers fall back to the server flag. */
export async function verifyChain(events: TurnJournalEvent[]): Promise<ChainCheck | null> {
  let expectedPrev = GENESIS_HASH;
  let expectedSeq = 0;
  for (const e of events) {
    if (e.seq !== expectedSeq) return { intact: false, brokenAtSeq: e.seq, reason: "sequence gap or reorder" };
    if (e.prevHash !== expectedPrev) {
      return { intact: false, brokenAtSeq: e.seq, reason: "prevHash does not match previous event" };
    }
    const recomputed = await eventHash(e);
    if (recomputed === null) return null;
    if (recomputed !== e.hash) {
      return { intact: false, brokenAtSeq: e.seq, reason: "event content does not match its hash" };
    }
    expectedPrev = e.hash;
    expectedSeq++;
  }
  return { intact: true, brokenAtSeq: -1, reason: "ok" };
}

/** Summaries are structural "key=value key=value" strings; this reads them back (first value of a repeated key wins). */
export function parseSummary(summary: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const token of (summary || "").split(/\s+/)) {
    const at = token.indexOf("=");
    if (at > 0 && !(token.slice(0, at) in out)) out[token.slice(0, at)] = token.slice(at + 1);
  }
  return out;
}

/** Milliseconds a tool reported (ms=), when the summary carries it. */
export function toolMillis(e: TurnJournalEvent): number | null {
  const n = Number(parseSummary(e.summary).ms);
  return Number.isFinite(n) && parseSummary(e.summary).ms !== undefined ? n : null;
}

export interface TurnTotals {
  wallMillis: number | null;
  toolMillis: number;
  toolCalls: number;
  modelCalls: number;
  /** The journal records no token or cost figures; this stays null until a later slice adds them. */
  costMinor: number | null;
}

export function turnTotals(events: TurnJournalEvent[]): TurnTotals {
  const times = events.map((e) => epochMillis(e.createdAt)).filter((n) => Number.isFinite(n));
  const tools = events.filter((e) => e.type === "TOOL_CALL");
  const costs = events.map((e) => Number(parseSummary(e.summary).costMinor)).filter((n) => Number.isFinite(n));
  return {
    wallMillis: times.length > 1 ? Math.max(...times) - Math.min(...times) : null,
    toolMillis: tools.reduce((sum, e) => sum + (toolMillis(e) ?? 0), 0),
    toolCalls: tools.length,
    modelCalls: events.filter((e) => e.type === "MODEL_CALL").length,
    costMinor: costs.length ? costs.reduce((a, b) => a + b, 0) : null,
  };
}

/** Gap in ms from the previous event (null for the first). */
export function gapFromPrevious(events: TurnJournalEvent[], index: number): number | null {
  if (index === 0) return null;
  const d = epochMillis(events[index].createdAt) - epochMillis(events[index - 1].createdAt);
  return Number.isFinite(d) ? d : null;
}
