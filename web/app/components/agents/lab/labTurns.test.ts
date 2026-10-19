// Agent Lab transcript helpers: how the flat playground transcript is paired
// into exchanges (the unit every instrument addresses a turn by), and the
// clamp that keeps previews inside a hard length.

import { describe, expect, it } from "vitest";
import { clampText, listExchanges, promptOf } from "./labTurns";
import { FIXTURE_AGENT, FIXTURE_TURNS } from "./labFixtures";
import type { LabTurn } from "./types";

describe("listExchanges", () => {
  it("pairs each user turn with the assistant turn that follows it", () => {
    const exchanges = listExchanges(FIXTURE_TURNS);
    expect(exchanges).toHaveLength(2);
    expect(exchanges[0]).toMatchObject({ n: 1, userIndex: 0, assistantIndex: 1, isServer: true, pending: false });
    expect(exchanges[1]).toMatchObject({ n: 2, userIndex: 2, assistantIndex: 3, isServer: true, pending: false });
    expect(exchanges[0].user).toBe(FIXTURE_TURNS[0]);
    expect(exchanges[0].assistant).toBe(FIXTURE_TURNS[1]);
  });

  it("keeps a user turn with no reply as its own exchange", () => {
    const turns: LabTurn[] = [
      { role: "user", content: "first" },
      { role: "user", content: "second" },
      { role: "assistant", content: "reply", ref: "r1" },
      { role: "user", content: "unanswered" },
    ];
    const exchanges = listExchanges(turns);
    expect(exchanges.map((e) => [e.n, e.userIndex, e.assistantIndex])).toEqual([
      [1, 0, -1],
      [2, 1, 2],
      [3, 3, -1],
    ]);
    expect(exchanges[0].assistant).toBeNull();
    expect(exchanges[0].isServer).toBe(false);
    expect(exchanges[2].user).toBe(turns[3]);
  });

  it("keeps an assistant turn with no user turn before it as its own exchange", () => {
    const turns: LabTurn[] = [
      { role: "assistant", content: "watching", watch: true, ref: "w1" },
      { role: "user", content: "hello" },
      { role: "assistant", content: "hi", onDevice: true },
      { role: "assistant", content: "one more" },
    ];
    const exchanges = listExchanges(turns);
    expect(exchanges.map((e) => [e.userIndex, e.assistantIndex])).toEqual([
      [-1, 0],
      [1, 2],
      [-1, 3],
    ]);
    expect(exchanges[0].user).toBeNull();
    expect(exchanges[0].isServer).toBe(true);
    // An on-device reply has no server ref.
    expect(exchanges[1].isServer).toBe(false);
  });

  it("reports a reply that is still streaming as pending", () => {
    const exchanges = listExchanges([
      { role: "user", content: "q" },
      { role: "assistant", content: "", pending: true, ref: "r1" },
    ]);
    expect(exchanges[0].pending).toBe(true);
  });

  it("returns an empty list for an empty transcript", () => {
    expect(listExchanges([])).toEqual([]);
  });
});

describe("promptOf", () => {
  it("prefers systemPrompt, then instructions, then an empty string", () => {
    expect(promptOf(FIXTURE_AGENT)).toBe(FIXTURE_AGENT.systemPrompt);
    expect(promptOf({ id: "a", name: "A", systemPrompt: "", instructions: "legacy" })).toBe("legacy");
    expect(promptOf({ id: "a", name: "A", systemPrompt: null, instructions: null })).toBe("");
    expect(promptOf({ id: "a", name: "A" })).toBe("");
  });
});

describe("clampText", () => {
  it("leaves text at or under the limit unchanged", () => {
    expect(clampText("short", 10)).toBe("short");
    expect(clampText("exactly-10", 10)).toBe("exactly-10");
    expect(clampText("", 5)).toBe("");
  });

  it("cuts to exactly max characters, ending in an ellipsis", () => {
    const cut = clampText("abcdefghijklmnop", 10);
    expect(cut).toHaveLength(10);
    expect(cut).toBe("abcdefghi…");
  });

  it("never returns more than max characters, even for a tiny max", () => {
    expect(clampText("abc", 1)).toBe("…");
    expect(clampText("abc", 0)).toBe("");
  });
});
