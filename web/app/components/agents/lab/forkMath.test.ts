// Fork planning: a rewind is only ever planned when every exchange in the
// transcript has a known stored-entry count AND the server's count agrees.
// Each refusal below is a real way the two can drift apart.

import { describe, expect, it } from "vitest";
import { SERVER_MAX_STORED_ENTRIES, classifyExchange, planFork, precheckFork } from "./forkMath";
import { listExchanges } from "./labTurns";
import type { LabTurn } from "./types";

const user = (content: string): LabTurn => ({ role: "user", content });

/** A completed server turn: stores the user entry and the assistant entry. */
const cleanReply = (content = "ok"): LabTurn => ({
  role: "assistant",
  content,
  ref: `ref-${content}`,
  steps: [
    { type: "run_started", id: 1, origin: "server" },
    { type: "run_finished", id: 2, ms: 100, origin: "server" },
  ],
});
/** Answered on this device: stores nothing on the server. */
const localReply = (): LabTurn => ({ role: "assistant", content: "local", onDevice: true });
/** A server turn with no run_finished: refused (0 stored), failed before a
 * token (1) or answered from cache (2, no events) - indistinguishable. */
const unfinishedReply = (): LabTurn => ({ role: "assistant", content: "from cache", ref: "ref-cached" });
const pendingReply = (): LabTurn => ({ role: "assistant", content: "", ref: "ref-pending", pending: true, steps: [] });

const clean = (q: string): LabTurn[] => [user(q), cleanReply(q)];

describe("classifyExchange", () => {
  const kindOf = (turns: LabTurn[]) => classifyExchange(listExchanges(turns)[0]);

  it("clean: a finished server turn", () => {
    expect(kindOf(clean("a"))).toBe("clean");
  });

  it("local: answered on this device", () => {
    expect(kindOf([user("a"), localReply()])).toBe("local");
  });

  it("unknown: everything whose stored-entry count cannot be read off the transcript", () => {
    expect(kindOf([user("orphan")])).toBe("unknown");
    expect(kindOf([cleanReply("greeting")])).toBe("unknown");
    expect(kindOf([user("a"), pendingReply()])).toBe("unknown");
    expect(kindOf([user("a"), unfinishedReply()])).toBe("unknown");
    expect(kindOf([user("a"), { ...localReply(), rescuedFromServer: true }])).toBe("unknown");
    expect(kindOf([user("a"), { ...localReply(), pending: true }])).toBe("unknown");
    // A finished turn with an empty reply stored nothing for the assistant.
    expect(kindOf([user("a"), { ...cleanReply(), content: "" }])).toBe("unknown");
    // run_finished from the on-device runner says nothing about the server.
    expect(
      kindOf([user("a"), { role: "assistant", content: "x", ref: "r", steps: [{ type: "run_finished", id: 1, origin: "on-device" }] }])
    ).toBe("unknown");
  });
});

describe("planFork", () => {
  it("forks the last exchange: keeps everything before it", () => {
    const turns = [...clean("a"), ...clean("b"), ...clean("c")];
    expect(planFork(turns, 4, 6)).toEqual({ ok: true, keepTurns: 4, tailExchanges: 1 });
  });

  it("forks an earlier exchange: the whole tail goes", () => {
    const turns = [...clean("a"), ...clean("b"), ...clean("c")];
    expect(planFork(turns, 2, 6)).toEqual({ ok: true, keepTurns: 2, tailExchanges: 2 });
    expect(planFork(turns, 0, 6)).toEqual({ ok: true, keepTurns: 0, tailExchanges: 3 });
  });

  it("accepts an on-device exchange in the prefix and counts it as 0 stored entries", () => {
    const turns = [...clean("a"), user("b"), localReply(), ...clean("c")];
    // Transcript index 4, but only one clean exchange (2 entries) precedes it on the server.
    expect(planFork(turns, 4, 4)).toEqual({ ok: true, keepTurns: 2, tailExchanges: 1 });
    expect(planFork(turns, 4, 6)).toEqual({ ok: false, reason: "mismatch", server: 6, local: 4 });
  });

  it("refuses to fork an on-device exchange", () => {
    const turns = [...clean("a"), user("b"), localReply()];
    expect(planFork(turns, 2, 2)).toEqual({ ok: false, reason: "on-device" });
  });

  it("refuses when the chosen exchange or a later one is not a finished server turn", () => {
    // Refused / cached / failed turn at the fork point.
    expect(planFork([...clean("a"), user("b"), unfinishedReply()], 2, 4)).toEqual({ ok: false, reason: "tail-unclean" });
    // ... or later in the tail.
    expect(planFork([...clean("a"), user("b"), unfinishedReply()], 0, 4)).toEqual({ ok: false, reason: "tail-unclean" });
    // Still running.
    expect(planFork([...clean("a"), user("b"), pendingReply()], 0, 3)).toEqual({ ok: false, reason: "tail-unclean" });
    expect(planFork([...clean("a"), user("b"), pendingReply()], 2, 3)).toEqual({ ok: false, reason: "tail-unclean" });
    // An on-device turn after the fork point cannot be rewound either.
    expect(planFork([...clean("a"), user("b"), localReply()], 0, 2)).toEqual({ ok: false, reason: "tail-unclean" });
  });

  it("refuses when an earlier exchange has an unknown stored size", () => {
    // Orphan user turn (its send failed): 0 or 1 entries.
    expect(planFork([user("orphan"), ...clean("b")], 1, 3)).toEqual({ ok: false, reason: "prefix-unclean" });
    // A server turn without run_finished.
    expect(planFork([user("a"), unfinishedReply(), ...clean("b")], 2, 4)).toEqual({ ok: false, reason: "prefix-unclean" });
    // A reply with no user turn before it.
    expect(planFork([cleanReply("greeting"), ...clean("b")], 1, 2)).toEqual({ ok: false, reason: "prefix-unclean" });
  });

  it("refuses when the server holds more entries than the transcript accounts for (a tool approval note)", () => {
    const turns = [...clean("a"), ...clean("b")];
    expect(planFork(turns, 2, 5)).toEqual({ ok: false, reason: "mismatch", server: 5, local: 4 });
  });

  it("refuses when the server holds fewer entries (expired or trimmed session)", () => {
    const turns = [...clean("a"), ...clean("b")];
    expect(planFork(turns, 2, 2)).toEqual({ ok: false, reason: "mismatch", server: 2, local: 4 });
    expect(planFork(turns, 2, 0)).toEqual({ ok: false, reason: "mismatch", server: 0, local: 4 });
  });

  it("refuses at the store's trim limit even when the counts agree", () => {
    const turns: LabTurn[] = [];
    for (let i = 0; i < SERVER_MAX_STORED_ENTRIES / 2; i++) turns.push(...clean(`q${i}`));
    expect(SERVER_MAX_STORED_ENTRIES).toBe(400);
    expect(planFork(turns, 398, 400)).toEqual({ ok: false, reason: "mismatch", server: 400, local: 400 });
    // One exchange fewer is still inside the limit.
    expect(planFork(turns.slice(0, 398), 396, 398)).toEqual({ ok: true, keepTurns: 396, tailExchanges: 1 });
  });

  it("refuses an index that is not the user turn of an exchange", () => {
    const turns = [...clean("a"), ...clean("b")];
    expect(planFork(turns, 1, 4)).toEqual({ ok: false, reason: "tail-unclean" });
    expect(planFork(turns, -1, 4)).toEqual({ ok: false, reason: "tail-unclean" });
    expect(planFork(turns, 9, 4)).toEqual({ ok: false, reason: "tail-unclean" });
    expect(planFork([], 0, 0)).toEqual({ ok: false, reason: "tail-unclean" });
  });
});

describe("precheckFork", () => {
  it("answers the transcript-only part and says how many entries the server should hold", () => {
    const turns = [...clean("a"), user("b"), localReply(), ...clean("c"), ...clean("d")];
    expect(precheckFork(turns, 4)).toEqual({ ok: true, keepTurns: 2, tailExchanges: 2, expectedEntries: 6 });
    expect(precheckFork(turns, 2)).toEqual({ ok: false, reason: "on-device" });
  });
});
