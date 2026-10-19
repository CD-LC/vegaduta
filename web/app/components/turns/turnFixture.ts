import type { TurnJournalView } from "../../lib/api/turns";
import { parseSummary, type ChainCheck } from "./journalChain";

// "Export as test fixture": a JSON file the Agent SDK testkit loads to assert a replayed turn behaves like
// the recorded one. Shape documented in docs/architecture/ADR-0021-turn-inspector.md. Payloads stay as
// returned by core (hashes and redacted summaries); nothing is un-redacted or enriched here.

export const TURN_FIXTURE_SCHEMA = "vegaduta.turn-fixture/1";

export interface TurnFixture {
  schema: typeof TURN_FIXTURE_SCHEMA;
  exportedAt: string;
  source: { agentId: string; turnId: string };
  chain: { serverIntact: boolean; brokenAtSeq: number; verification: string; clientVerified: boolean | null };
  events: Array<{
    seq: number;
    type: string;
    payloadHash: string;
    summary: string;
    prevHash: string;
    hash: string;
    createdAt: string | number;
  }>;
  /** What a replay must reproduce; mirrors core TurnReplay.sameBehaviour (tool sequence plus final-answer hash). */
  expected: {
    eventTypes: string[];
    toolSequence: string[];
    finalAnswerHash: string | null;
    finalStatus: string | null;
  };
}

export function buildTurnFixture(view: TurnJournalView, clientCheck: ChainCheck | null, now: Date = new Date()): TurnFixture {
  const events = view.events.map((e) => ({
    seq: e.seq,
    type: String(e.type),
    payloadHash: e.payloadHash,
    summary: e.summary,
    prevHash: e.prevHash,
    hash: e.hash,
    createdAt: e.createdAt,
  }));
  const commits = view.events.filter((e) => e.type === "COMMIT");
  const lastCommit = commits.length ? commits[commits.length - 1] : null;
  return {
    schema: TURN_FIXTURE_SCHEMA,
    exportedAt: now.toISOString(),
    source: { agentId: view.agentId, turnId: view.turnId },
    chain: {
      serverIntact: view.chainIntact,
      brokenAtSeq: view.brokenAtSeq,
      verification: view.verification,
      clientVerified: clientCheck ? clientCheck.intact : null,
    },
    events,
    expected: {
      eventTypes: events.map((e) => e.type),
      toolSequence: view.events
        .filter((e) => e.type === "TOOL_CALL")
        .map((e) => `${parseSummary(e.summary).tool ?? ""}|${e.payloadHash}`),
      finalAnswerHash: lastCommit ? lastCommit.payloadHash : null,
      finalStatus: lastCommit ? (parseSummary(lastCommit.summary).status ?? null) : null,
    },
  };
}

export function fixtureFilename(view: Pick<TurnJournalView, "turnId">): string {
  return `turn-${view.turnId.replace(/[^A-Za-z0-9-]/g, "").slice(0, 36)}.fixture.json`;
}

export function serializeFixture(fixture: TurnFixture): string {
  return `${JSON.stringify(fixture, null, 2)}\n`;
}
