import { describe, expect, it } from "vitest";
import { buildTurnFixture, fixtureFilename, serializeFixture, TURN_FIXTURE_SCHEMA } from "./turnFixture";
import { buildChain, SPECS } from "./chainTestUtil";
import type { TurnJournalView } from "../../lib/api/turns";

async function view(): Promise<TurnJournalView> {
  const events = await buildChain(SPECS);
  return { agentId: events[0].agentId, turnId: events[0].turnId, chainIntact: true, brokenAtSeq: -1, verification: "ok", events };
}

describe("buildTurnFixture", () => {
  it("captures events verbatim and the expected behaviour", async () => {
    const v = await view();
    const f = buildTurnFixture(v, { intact: true, brokenAtSeq: -1, reason: "ok" }, new Date("2026-10-15T12:00:00Z"));
    expect(f.schema).toBe(TURN_FIXTURE_SCHEMA);
    expect(f.exportedAt).toBe("2026-10-15T12:00:00.000Z");
    expect(f.events).toHaveLength(4);
    expect(f.events[2]).toMatchObject({ seq: 2, type: "TOOL_CALL", summary: v.events[2].summary, hash: v.events[2].hash });
    expect(f.expected.eventTypes).toEqual(["INTAKE", "MODEL_CALL", "TOOL_CALL", "COMMIT"]);
    expect(f.expected.toolSequence).toEqual([`lookup|${v.events[2].payloadHash}`]);
    expect(f.expected.finalAnswerHash).toBe(v.events[3].payloadHash);
    expect(f.expected.finalStatus).toBe("completed");
    expect(f.chain.clientVerified).toBe(true);
  });

  it("records an unknown client verdict and a turn without commit", async () => {
    const v = await view();
    v.events = v.events.slice(0, 3);
    const f = buildTurnFixture(v, null);
    expect(f.chain.clientVerified).toBeNull();
    expect(f.expected.finalAnswerHash).toBeNull();
    expect(f.expected.finalStatus).toBeNull();
  });

  it("serializes as JSON and names the file safely", async () => {
    const v = await view();
    expect(JSON.parse(serializeFixture(buildTurnFixture(v, null))).source.turnId).toBe(v.turnId);
    expect(fixtureFilename({ turnId: "../x/22222222-2222" })).toBe("turn-x22222222-2222.fixture.json");
  });
});
