import { describe, expect, it } from "vitest";
import { decorateNode, entryToolCalls, internNodeOverlay, liveSliceFromBuffer, liveSliceFromEntry, runTraceEntries } from "./runOverlay";
import { createLiveRunState, liveRunReducer, type LiveRunEvent } from "./liveRunStream";

const ev = (seq: number, type: LiveRunEvent["type"], nodeId: string | null, data: Record<string, unknown>): LiveRunEvent => ({
  seq,
  type,
  runId: "r1",
  nodeId,
  ts: 1_000 + seq,
  data,
});

const overlay = internNodeOverlay({
  trace: undefined,
  running: true,
  tourFocus: false,
  visitCount: undefined,
  replay: undefined,
  inspected: false,
  lens: undefined,
});

describe("live node slices", () => {
  it("keeps a node's slice identity until that node's buffer changes", () => {
    let s = createLiveRunState("r1");
    s = liveRunReducer(s, ev(1, "node.started", "a", { nodeType: "agent" }));
    s = liveRunReducer(s, ev(2, "node.started", "b", { nodeType: "agent" }));
    const a1 = liveSliceFromBuffer(s.nodes.a, true);
    const b1 = liveSliceFromBuffer(s.nodes.b, true);
    s = liveRunReducer(s, ev(3, "node.token", "a", { delta: "Hel" }));
    expect(liveSliceFromBuffer(s.nodes.b, true)).toBe(b1);
    const a2 = liveSliceFromBuffer(s.nodes.a, true);
    expect(a2).not.toBe(a1);
    expect(a2.text).toBe("Hel");
    // Same buffer, same streaming flag -> same object (memo'd node skips render).
    expect(liveSliceFromBuffer(s.nodes.a, true)).toBe(a2);
    expect(liveSliceFromBuffer(s.nodes.a, false).streaming).toBe(false);
  });

  it("maps streamed tool calls (null -> undefined) for NodeLivePanel", () => {
    let s = createLiveRunState("r1");
    s = liveRunReducer(s, ev(1, "node.started", "a", {}));
    s = liveRunReducer(s, ev(2, "node.tool_call", "a", { id: "t1", tool: "search", args: '{"q":"x"}' }));
    s = liveRunReducer(s, ev(3, "node.tool_result", "a", { id: "t1", tool: "search", preview: "3 hits", ms: 120 }));
    const slice = liveSliceFromBuffer(s.nodes.a, false);
    expect(slice.toolCalls).toEqual([
      { id: "t1", tool: "search", args: '{"q":"x"}', preview: "3 hits", error: undefined, ms: 120, status: "ok" },
    ]);
  });

  it("reads finished tool calls off a trace entry, once per entry", () => {
    const entry = {
      nodeId: "a",
      toolCalls: [
        { toolName: "search", input: "{}", output: "ok", durationMs: 40 },
        { toolName: "fetch", input: null as string | null, error: "timeout", durationMs: "900" },
        null,
      ],
    };
    expect(entryToolCalls(entry)).toEqual([
      { tool: "search", input: "{}", output: "ok", error: null, startedAt: null, durationMs: 40 },
      { tool: "fetch", input: null, output: null, error: "timeout", startedAt: null, durationMs: 900 },
    ]);
    const slice = liveSliceFromEntry(entry);
    expect(slice?.toolCalls?.map((c) => c.status)).toEqual(["ok", "error"]);
    expect(liveSliceFromEntry(entry)).toBe(slice);
    expect(liveSliceFromEntry({ nodeId: "b" })).toBeUndefined();
    expect(entryToolCalls({ toolCalls: null })).toEqual([]);
  });

  it("parses a run's trace once and tolerates a JSON-string trace", () => {
    const run = { id: "r", trace: JSON.stringify([{ nodeId: "a" }]) };
    const first = runTraceEntries(run);
    expect(first).toHaveLength(1);
    expect(runTraceEntries(run)).toBe(first);
    expect(runTraceEntries(null)).toEqual([]);
  });

  it("decorateNode reuses its output until overlay, live or eval input changes", () => {
    const node = { id: "a", data: { label: "A" } };
    const live = { streaming: true, text: "hi" };
    const out1 = decorateNode(node, overlay, live, undefined);
    expect(decorateNode(node, overlay, live, undefined)).toBe(out1);
    const out2 = decorateNode(node, overlay, { streaming: true, text: "hi!" }, undefined);
    expect(out2).not.toBe(out1);
    const stats = { runs: 3, successRate: 1, p50DurationMs: 10, avgCostUsd: null as number | null };
    const out3 = decorateNode(node, overlay, undefined, stats);
    expect((out3.data as { evalStats?: unknown }).evalStats).toBe(stats);
  });
});
