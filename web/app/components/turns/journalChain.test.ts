import { describe, expect, it } from "vitest";
import { epochMillis, parseSummary, sha256Hex, turnTotals, verifyChain } from "./journalChain";
import { buildChain, SPECS } from "./chainTestUtil";

describe("journalChain", () => {
  it("matches the known SHA-256 of 'abc'", async () => {
    expect(await sha256Hex("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });

  it("verifies an intact chain", async () => {
    expect(await verifyChain(await buildChain(SPECS))).toEqual({ intact: true, brokenAtSeq: -1, reason: "ok" });
  });

  it("detects an edited summary", async () => {
    const chain = await buildChain(SPECS);
    chain[2] = { ...chain[2], summary: "tool=lookup status=error" };
    expect(await verifyChain(chain)).toMatchObject({ intact: false, brokenAtSeq: 2 });
  });

  it("detects a removed event and a reorder", async () => {
    const chain = await buildChain(SPECS);
    expect(await verifyChain([chain[0], chain[2], chain[3]])).toMatchObject({ intact: false, brokenAtSeq: 2 });
    expect(await verifyChain([chain[1], chain[0]])).toMatchObject({ intact: false });
  });

  it("parses epoch seconds and ISO timestamps", () => {
    expect(epochMillis("2026-10-15T10:00:00.250Z")).toBe(Date.UTC(2026, 9, 15, 10, 0, 0, 250));
    expect(epochMillis(1760522400.25)).toBe(1760522400250);
  });

  it("reads structural summaries and totals", async () => {
    expect(parseSummary("tool=a status=ok ms=5")).toEqual({ tool: "a", status: "ok", ms: "5" });
    const totals = turnTotals(await buildChain(SPECS));
    expect(totals).toMatchObject({ wallMillis: 750, toolMillis: 120, toolCalls: 1, modelCalls: 1, costMinor: null });
  });
});
