// What a Consistency check sends: k identical cells that use the agent's
// stored prompt, judged for similarity only - and the conversation history it
// may carry, which must be the part of the transcript the server actually
// holds, inside the server's size limits.

import { describe, expect, it } from "vitest";
import { buildConsistencyRequest, historyBefore, type ConsistencyContext } from "./consistencyPlan";
import { LAB_MAX_HISTORY_CHARS, LAB_MAX_HISTORY_MESSAGES, plannedCalls, type LabMessage } from "./labApi";
import { FIXTURE_TURNS } from "./labFixtures";
import type { LabTurn } from "./types";

const HISTORY: LabMessage[] = [
  { role: "user", content: "Where is my order?" },
  { role: "assistant", content: "It shipped yesterday." },
];

function contextOf(overrides: Partial<ConsistencyContext> = {}): ConsistencyContext {
  return { message: "Can I get a refund?", history: HISTORY, k: 5, usedTools: false, ...overrides };
}

/** n complete server exchanges: "q1"/"a1" ... "qn"/"an". */
function serverExchanges(n: number): LabTurn[] {
  const turns: LabTurn[] = [];
  for (let i = 1; i <= n; i++) {
    turns.push({ role: "user", content: `q${i}` });
    turns.push({ role: "assistant", content: `a${i}`, ref: `r${i}` });
  }
  return turns;
}

describe("buildConsistencyRequest", () => {
  it("asks for similarity only, on the agent's stored prompt", () => {
    const request = buildConsistencyRequest(contextOf());
    expect(request.kind).toBe("CONSISTENCY");
    expect(request.systemPrompts).toEqual([]);
    expect(request.scoring).toBe("NONE");
    expect(request.similarity).toBe("JUDGE");
  });

  it("builds k cells keyed k0.., each with the same message and history and no prompt index", () => {
    const request = buildConsistencyRequest(contextOf({ k: 4 }));
    expect(request.cells.map((cell) => cell.key)).toEqual(["k0", "k1", "k2", "k3"]);
    for (const cell of request.cells) {
      expect(cell.promptIndex).toBeNull();
      expect(cell.message).toBe("Can I get a refund?");
      expect(cell.history).toEqual(HISTORY);
      expect(cell.expected).toBeUndefined();
    }
  });

  it("sends an empty history when there is none", () => {
    const request = buildConsistencyRequest(contextOf({ history: [], k: 3 }));
    expect(request.cells).toHaveLength(3);
    expect(request.cells.every((cell) => Array.isArray(cell.history) && cell.history.length === 0)).toBe(true);
  });

  it("plans k samples plus one comparison per pair", () => {
    expect(plannedCalls(buildConsistencyRequest(contextOf({ k: 3 })))).toBe(3 + 3);
    expect(plannedCalls(buildConsistencyRequest(contextOf({ k: 5 })))).toBe(5 + 10);
    expect(plannedCalls(buildConsistencyRequest(contextOf({ k: 8 })))).toBe(8 + 28);
  });

  it("survives the JSON round trip useLabRun puts the context through", () => {
    const context = contextOf({ usedTools: true });
    const restored = JSON.parse(JSON.stringify(context)) as ConsistencyContext;
    expect(restored).toEqual(context);
    expect(buildConsistencyRequest(restored)).toEqual(buildConsistencyRequest(context));
  });
});

describe("historyBefore", () => {
  it("is empty for the first exchange", () => {
    expect(historyBefore(FIXTURE_TURNS, 0)).toEqual([]);
  });

  it("returns the exchanges before the turn, oldest first, as user/assistant messages", () => {
    expect(historyBefore(FIXTURE_TURNS, 2)).toEqual([
      { role: "user", content: FIXTURE_TURNS[0].content },
      { role: "assistant", content: FIXTURE_TURNS[1].content },
    ]);
    // Steps, refs and metrics never travel with the history.
    expect(Object.keys(historyBefore(FIXTURE_TURNS, 2)[1]).sort()).toEqual(["content", "role"]);
  });

  it("stops at the selected turn: that exchange and everything after it is left out", () => {
    const turns = serverExchanges(4);
    // The user turn of exchange 3 sits at index 4.
    expect(historyBefore(turns, 4).map((message) => message.content)).toEqual(["q1", "a1", "q2", "a2"]);
  });

  it("keeps complete server exchanges only", () => {
    const turns: LabTurn[] = [
      { role: "assistant", content: "watching the camera", ref: "w1", watch: true }, // no user turn
      { role: "user", content: "on device question" },
      { role: "assistant", content: "on device answer", onDevice: true }, // no server ref
      { role: "user", content: "unanswered" }, // no reply
      { role: "user", content: "empty reply" },
      { role: "assistant", content: "   ", ref: "r1" }, // blank content
      { role: "user", content: "still streaming" },
      { role: "assistant", content: "partial", ref: "r2", pending: true }, // pending
      { role: "user", content: "   " }, // blank question
      { role: "assistant", content: "answer to nothing", ref: "r3" },
      { role: "user", content: "kept question" },
      { role: "assistant", content: "kept answer", ref: "r4" },
      { role: "user", content: "the one being sampled" },
      { role: "assistant", content: "its answer", ref: "r5" },
    ];
    expect(historyBefore(turns, 12)).toEqual([
      { role: "user", content: "kept question" },
      { role: "assistant", content: "kept answer" },
    ]);
  });

  it("keeps at most 20 messages by default: the most recent ten exchanges, whole", () => {
    const turns = serverExchanges(14);
    const history = historyBefore(turns, turns.length - 2);
    expect(history).toHaveLength(LAB_MAX_HISTORY_MESSAGES);
    expect(history[0]).toEqual({ role: "user", content: "q4" });
    expect(history[history.length - 1]).toEqual({ role: "assistant", content: "a13" });
  });

  it("never opens on an answer when the message limit is odd", () => {
    const history = historyBefore(serverExchanges(6), 10, 5);
    expect(history.map((message) => message.content)).toEqual(["q4", "a4", "q5", "a5"]);
    expect(historyBefore(serverExchanges(6), 10, 1)).toEqual([]);
    expect(historyBefore(serverExchanges(6), 10, 0)).toEqual([]);
  });

  it("clamps each content to 4000 characters by default, and to maxChars when given", () => {
    const long = "x".repeat(LAB_MAX_HISTORY_CHARS + 500);
    const turns: LabTurn[] = [
      { role: "user", content: long },
      { role: "assistant", content: long, ref: "r1" },
      { role: "user", content: "next" },
      { role: "assistant", content: "reply", ref: "r2" },
    ];
    const history = historyBefore(turns, 2);
    expect(history).toHaveLength(2);
    for (const message of history) {
      expect(message.content).toHaveLength(LAB_MAX_HISTORY_CHARS);
      expect(message.content.endsWith("…")).toBe(true);
    }
    expect(historyBefore(turns, 2, 20, 10).map((message) => message.content.length)).toEqual([10, 10]);
  });

  it("does not change the transcript it reads", () => {
    const turns = serverExchanges(3);
    const before = JSON.stringify(turns);
    historyBefore(turns, 4);
    expect(JSON.stringify(turns)).toBe(before);
  });
});
