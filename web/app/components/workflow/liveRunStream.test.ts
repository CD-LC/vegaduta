import { describe, expect, it } from "vitest";
import {
  LIVE_TEXT_CAP,
  SseParser,
  createLiveRunState,
  liveRunReducer,
  openRunStream,
  parseLiveEvent,
  parseSseChunk,
  parseTs,
  reduceLiveEvents,
  type LiveRunEvent,
  type RunStreamStatus,
} from "./liveRunStream";

const RUN = "run-1";
const T0 = Date.parse("2026-09-29T10:00:00.000Z");

function ev(seq: number | null, type: string, data: Record<string, unknown> = {}, nodeId: string | null = null, runId: string | null = RUN): LiveRunEvent {
  return { seq, type, runId, nodeId, ts: T0 + (seq ?? 0) * 100, data };
}

function frame(seq: number, type: string, data: Record<string, unknown>, nodeId?: string): string {
  const payload = { seq, type, runId: RUN, ...(nodeId ? { nodeId } : {}), ts: new Date(T0 + seq).toISOString(), data };
  return `id: ${seq}\nevent: ${type}\ndata: ${JSON.stringify(payload)}\n\n`;
}

function snapshotFrame(run: Record<string, unknown>): string {
  return `event: snapshot\ndata: ${JSON.stringify({ type: "snapshot", runId: RUN, ts: new Date(T0).toISOString(), data: run })}\n\n`;
}

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

describe("SseParser", () => {
  it("parses a basic frame with event, id and data", () => {
    const frames = parseSseChunk('id: 7\nevent: node.token\ndata: {"a":1}\n\n');
    expect(frames).toEqual([{ event: "node.token", data: '{"a":1}', id: "7" }]);
  });

  it("joins multi-line data with newlines", () => {
    const frames = parseSseChunk("data: line one\ndata: line two\ndata:\ndata:three\n\n");
    expect(frames).toEqual([{ event: "message", data: "line one\nline two\n\nthree", id: null }]);
  });

  it("reassembles a frame split at every possible boundary", () => {
    const text = 'id: 3\nevent: run.started\ndata: {"x":"y"}\n\n';
    for (let cut = 1; cut < text.length; cut++) {
      const p = new SseParser();
      const frames = [...p.push(text.slice(0, cut)), ...p.push(text.slice(cut))];
      expect(frames, `cut at ${cut}`).toEqual([{ event: "run.started", data: '{"x":"y"}', id: "3" }]);
    }
  });

  it("handles CRLF and a CRLF split across chunks", () => {
    const p = new SseParser();
    const a = p.push("data: hi\r");
    const b = p.push("\n\r\n");
    expect([...a, ...b]).toEqual([{ event: "message", data: "hi", id: null }]);
    expect(parseSseChunk("data: a\rdata: b\r\r")).toEqual([{ event: "message", data: "a\nb", id: null }]);
  });

  it("counts comments/heartbeats and never dispatches them", () => {
    const p = new SseParser();
    expect(p.push(":hb\n\n: another\n\n")).toEqual([]);
    expect(p.commentCount).toBe(2);
  });

  it("keeps the last id across frames and resets it on an empty id", () => {
    const p = new SseParser("5");
    const frames = p.push("data: a\n\nid: 9\ndata: b\n\ndata: c\n\nid\ndata: d\n\n");
    expect(frames.map((f) => f.id)).toEqual(["5", "9", "9", null]);
    expect(p.lastEventId).toBeNull();
  });

  it("drops frames with no data, strips a BOM, reads retry, ignores unknown fields", () => {
    const p = new SseParser();
    expect(p.push("﻿event: x\n\nretry: 3000\nfoo: bar\ndata: y\n\n")).toEqual([{ event: "message", data: "y", id: null }]);
    expect(p.retryMs).toBe(3000);
  });

  it("does not dispatch a partial frame without its blank line", () => {
    const p = new SseParser();
    expect(p.push("data: partial\n")).toEqual([]);
    expect(p.push("\n")).toEqual([{ event: "message", data: "partial", id: null }]);
  });
});

describe("parseLiveEvent / parseTs", () => {
  it("reads the payload envelope", () => {
    const [f] = parseSseChunk(frame(4, "node.token", { delta: "hi" }, "n1"));
    expect(parseLiveEvent(f)).toEqual({ seq: 4, type: "node.token", runId: RUN, nodeId: "n1", ts: T0 + 4, data: { delta: "hi" } });
  });

  it("gives snapshots no seq and lifts error messages into data", () => {
    const [s] = parseSseChunk(snapshotFrame({ id: RUN, status: "RUNNING" }));
    expect(parseLiveEvent(s)?.seq).toBeNull();
    const [e] = parseSseChunk(`event: error\ndata: ${JSON.stringify({ type: "error", message: "nope" })}\n\n`);
    expect(parseLiveEvent(e)).toMatchObject({ type: "error", seq: null, data: { message: "nope" } });
  });

  it("returns null for non-JSON data", () => {
    expect(parseLiveEvent({ event: "x", data: "not json", id: null })).toBeNull();
  });

  it("parses ISO, epoch ms and epoch seconds", () => {
    expect(parseTs("2026-09-29T10:00:00.000Z")).toBe(T0);
    expect(parseTs(T0)).toBe(T0);
    expect(parseTs(T0 / 1000)).toBe(T0);
    expect(parseTs("garbage")).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Reducer
// ---------------------------------------------------------------------------

describe("liveRunReducer", () => {
  const base = () => createLiveRunState(RUN);

  it("applies a snapshot, deriving totals and awaiting", () => {
    const s = liveRunReducer(
      base(),
      ev(null, "snapshot", {
        id: RUN,
        status: "AWAITING_APPROVAL",
        trace: [
          { nodeId: "a", status: "OK", tokensIn: 10, tokensOut: 5, costUsd: 0.01 },
          { nodeId: "b", label: "Approve", status: "AWAITING_APPROVAL", summary: "needs ok" },
        ],
      }),
    );
    expect(s.run?.status).toBe("AWAITING_APPROVAL");
    expect(s.runTotals).toEqual({ tokensIn: 10, tokensOut: 5, costUsd: 0.01 });
    expect(s.awaiting).toMatchObject({ nodeId: "b", label: "Approve", summary: "needs ok" });
    expect(s.finished).toBeNull();
    expect(s.version).toBe(1);
  });

  it("accepts a JSON-string trace in a snapshot and marks terminal snapshots finished", () => {
    const s = liveRunReducer(base(), ev(null, "snapshot", { id: RUN, status: "FAILED", errorMessage: "boom", trace: JSON.stringify([{ nodeId: "a" }]) }));
    expect(Array.isArray(s.run?.trace)).toBe(true);
    expect(s.finished).toEqual({ status: "FAILED", error: "boom" });
  });

  it("run.started creates a minimal run and run.resumed clears awaiting", () => {
    let s = liveRunReducer(base(), ev(1, "run.started"));
    expect(s.run).toMatchObject({ id: RUN, status: "RUNNING", trace: [] });
    s = liveRunReducer(s, ev(2, "run.awaiting", { status: "AWAITING_INPUT", nodeId: "h", label: "Ask", summary: "?", requestId: "r9" }));
    expect(s.run?.status).toBe("AWAITING_INPUT");
    expect(s.awaiting).toEqual({ status: "AWAITING_INPUT", nodeId: "h", label: "Ask", summary: "?", requestId: "r9" });
    s = liveRunReducer(s, ev(3, "run.resumed"));
    expect(s.awaiting).toBeNull();
    expect(s.run?.status).toBe("RUNNING");
  });

  it("node.started opens a fresh buffer and tracks active nodes; a retry resets it", () => {
    let s = liveRunReducer(base(), ev(1, "node.started", { nodeType: "agent", label: "Writer", attempt: 1 }, "n1"));
    s = liveRunReducer(s, ev(2, "node.token", { delta: "draft" }, "n1"));
    expect(s.nodes.n1).toMatchObject({ nodeType: "agent", label: "Writer", attempt: 1, phase: "running", text: "draft", startedAt: T0 + 100 });
    expect(s.activeNodeIds).toEqual(["n1"]);
    s = liveRunReducer(s, ev(3, "node.started", { nodeType: "agent", label: "Writer", attempt: 2 }, "n1"));
    expect(s.nodes.n1.attempt).toBe(2);
    expect(s.nodes.n1.text).toBe("");
    expect(s.activeNodeIds).toEqual(["n1"]);
  });

  it("node.token applies delta, reset and capped", () => {
    let s = reduceLiveEvents(base(), [
      ev(1, "node.token", { delta: "Hel" }, "n"),
      ev(2, "node.token", { delta: "lo" }, "n"),
    ]);
    expect(s.nodes.n.text).toBe("Hello");
    s = liveRunReducer(s, ev(3, "node.token", { reset: true, text: "Hi" }, "n"));
    expect(s.nodes.n.text).toBe("Hi");
    s = liveRunReducer(s, ev(4, "node.token", { capped: true }, "n"));
    expect(s.nodes.n.capped).toBe(true);
    expect(s.nodes.n.text).toBe("Hi");
  });

  it("caps the text buffer at LIVE_TEXT_CAP, keeping the tail", () => {
    const big = "a".repeat(LIVE_TEXT_CAP - 2);
    let s = liveRunReducer(base(), ev(1, "node.token", { delta: big }, "n"));
    expect(s.nodes.n.textTruncated).toBe(false);
    s = liveRunReducer(s, ev(2, "node.token", { delta: "WXYZ" }, "n"));
    expect(s.nodes.n.text.length).toBe(LIVE_TEXT_CAP);
    expect(s.nodes.n.text.endsWith("WXYZ")).toBe(true);
    expect(s.nodes.n.textTruncated).toBe(true);
    s = liveRunReducer(s, ev(3, "node.token", { reset: true, text: "b".repeat(LIVE_TEXT_CAP + 10) }, "n"));
    expect(s.nodes.n.text.length).toBe(LIVE_TEXT_CAP);
  });

  it("pairs tool calls with results and errors by id (numeric ids too)", () => {
    const s = reduceLiveEvents(base(), [
      ev(1, "node.tool_call", { id: 1, tool: "search", args: '{"q":"x"}' }, "n"),
      ev(2, "node.tool_call", { id: 2, tool: "fetch", args: "{}" }, "n"),
      ev(3, "node.tool_result", { id: 1, tool: "search", preview: "3 hits", ms: 120 }, "n"),
      ev(4, "node.tool_error", { id: 2, tool: "fetch", message: "timeout", ms: 5000 }, "n"),
    ]);
    expect(s.nodes.n.toolCalls).toEqual([
      { id: "1", tool: "search", args: '{"q":"x"}', preview: "3 hits", error: null, ms: 120, status: "ok", startedAt: T0 + 100 },
      { id: "2", tool: "fetch", args: "{}", preview: null, error: "timeout", ms: 5000, status: "error", startedAt: T0 + 200 },
    ]);
  });

  it("a result without a prior call still makes a row", () => {
    const s = liveRunReducer(base(), ev(1, "node.tool_result", { id: "z", tool: "calc", preview: "4", ms: 3 }, "n"));
    expect(s.nodes.n.toolCalls[0]).toMatchObject({ id: "z", tool: "calc", status: "ok", preview: "4" });
  });

  it("records agent events but drops heartbeats", () => {
    let s = liveRunReducer(base(), ev(1, "node.agent_event", { kind: "plan", steps: 3 }, "n"));
    s = liveRunReducer(s, ev(2, "node.agent_event", { kind: "heartbeat" }, "n"));
    expect(s.nodes.n.agentEvents).toEqual([{ kind: "plan", ts: T0 + 100, data: { kind: "plan", steps: 3 } }]);
  });

  it("node.completed merges the entry into the trace, sets totals and closes open tools", () => {
    let s = reduceLiveEvents(base(), [
      ev(1, "run.started"),
      ev(2, "node.started", { nodeType: "agent", label: "A" }, "n"),
      ev(3, "node.tool_call", { id: 1, tool: "t" }, "n"),
    ]);
    const entry = { nodeId: "n", nodeType: "agent", status: "OK", startedAt: "2026-09-29T10:00:00.200Z", completedAt: "2026-09-29T10:00:01.000Z", tokensIn: 12, costUsd: 0.002 };
    s = liveRunReducer(s, ev(4, "node.completed", { entry, runTotals: { tokensIn: 12, tokensOut: null, costUsd: 0.002 } }, "n"));
    expect(s.run?.trace).toEqual([entry]);
    expect(s.nodes.n).toMatchObject({ phase: "done", entryStatus: "OK", completedAt: Date.parse(entry.completedAt) });
    expect(s.nodes.n.toolCalls[0].status).toBe("error");
    expect(s.runTotals).toEqual({ tokensIn: 12, tokensOut: null, costUsd: 0.002 });
    expect(s.activeNodeIds).toEqual([]);
  });

  it("node.completed replayed after a snapshot that already holds it replaces, not appends", () => {
    const entry = { nodeId: "n", status: "OK", startedAt: "2026-09-29T10:00:00.200Z", summary: "old" };
    let s = liveRunReducer(base(), ev(null, "snapshot", { id: RUN, status: "RUNNING", trace: [entry] }));
    s = liveRunReducer(s, ev(5, "node.completed", { entry: { ...entry, summary: "new" } }, "n"));
    expect(s.run?.trace).toEqual([{ ...entry, summary: "new" }]);
    // A second visit (loop) with a different start is appended.
    s = liveRunReducer(s, ev(6, "node.completed", { entry: { ...entry, startedAt: "2026-09-29T10:00:02.000Z" } }, "n"));
    expect((s.run?.trace as unknown[]).length).toBe(2);
  });

  it("run.finished sets status, error and clears awaiting/active nodes", () => {
    let s = reduceLiveEvents(base(), [
      ev(1, "run.started"),
      ev(2, "node.started", {}, "n"),
      ev(3, "run.awaiting", { status: "PAUSED" }),
    ]);
    s = liveRunReducer(s, ev(4, "run.finished", { status: "FAILED", error: "bad" }));
    expect(s.finished).toEqual({ status: "FAILED", error: "bad" });
    expect(s.run).toMatchObject({ status: "FAILED", errorMessage: "bad" });
    expect(s.awaiting).toBeNull();
    expect(s.activeNodeIds).toEqual([]);
  });

  it("error events record the message", () => {
    const s = liveRunReducer(base(), ev(null, "error", { message: "Run not found for this workflow." }));
    expect(s.error).toBe("Run not found for this workflow.");
  });

  it("ignores duplicate / out-of-order seq and returns the same state", () => {
    const s1 = reduceLiveEvents(base(), [ev(1, "node.token", { delta: "a" }, "n"), ev(2, "node.token", { delta: "b" }, "n")]);
    const s2 = liveRunReducer(s1, ev(2, "node.token", { delta: "b" }, "n"));
    const s3 = liveRunReducer(s2, ev(1, "node.token", { delta: "a" }, "n"));
    expect(s3).toBe(s1);
    expect(s1.nodes.n.text).toBe("ab");
    expect(s1.lastSeq).toBe(2);
  });

  it("ignores events from another run (race after a run change)", () => {
    const s = base();
    expect(liveRunReducer(s, ev(1, "node.token", { delta: "x" }, "n", "other-run"))).toBe(s);
  });

  it("ignores unknown types and node events without a node id", () => {
    const s = base();
    expect(liveRunReducer(s, ev(1, "something.new", {}))).toBe(s);
    expect(liveRunReducer(s, ev(1, "node.token", { delta: "x" }))).toBe(s);
  });
});

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

type Scripted = { status?: number; contentType?: string; chunks?: string[]; error?: boolean; hang?: boolean };

function fakeFetch(script: Scripted[]) {
  const calls: { url: string; headers: Record<string, string> }[] = [];
  let i = 0;
  const impl = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), headers: { ...(init?.headers as Record<string, string>) } });
    const step = script[Math.min(i++, script.length - 1)];
    if (step.error) throw new TypeError("Failed to fetch");
    const enc = new TextEncoder();
    const chunks = step.chunks ?? [];
    const signal = init?.signal;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const c of chunks) controller.enqueue(enc.encode(c));
        if (step.hang) {
          signal?.addEventListener("abort", () => controller.error(new DOMException("aborted", "AbortError")));
          return;
        }
        controller.close();
      },
    });
    return new Response(body, {
      status: step.status ?? 200,
      headers: { "content-type": step.contentType ?? "text/event-stream;charset=UTF-8" },
    });
  }) as typeof fetch;
  return { impl, calls };
}

async function run(script: Scripted[], extra: { signal?: AbortSignal; lastEventId?: string } = {}) {
  const { impl, calls } = fakeFetch(script);
  const events: LiveRunEvent[] = [];
  const statuses: RunStreamStatus[] = [];
  const outcome = await openRunStream({
    workflowId: "wf 1",
    runId: RUN,
    getToken: async () => "tok",
    fetchImpl: impl,
    baseUrl: "https://api.example.test",
    backoffMs: () => 0,
    onEvent: (e) => events.push(e),
    onStatus: (s) => statuses.push(s),
    ...extra,
  });
  return { outcome, events, statuses, calls };
}

describe("openRunStream", () => {
  it("streams events split across chunks and finishes on run.finished", async () => {
    const body = snapshotFrame({ id: RUN, status: "RUNNING", trace: [] }) + frame(1, "run.started", {}) + ":hb\n\n" + frame(2, "run.finished", { status: "COMPLETED", error: null });
    const mid = Math.floor(body.length / 2);
    const r = await run([{ chunks: [body.slice(0, 7), body.slice(7, mid), body.slice(mid)] }]);
    expect(r.outcome).toBe("finished");
    expect(r.events.map((e) => e.type)).toEqual(["snapshot", "run.started", "run.finished"]);
    expect(r.calls[0].url).toBe(`https://api.example.test/api/workflows/wf%201/runs/${RUN}/events`);
    expect(r.calls[0].headers.Authorization).toBe("Bearer tok");
    expect(r.calls[0].headers["Last-Event-ID"]).toBeUndefined();
    expect(r.statuses.some((s) => s.kind === "heartbeat")).toBe(true);
    const state = reduceLiveEvents(createLiveRunState(RUN), r.events);
    expect(state.finished?.status).toBe("COMPLETED");
  });

  it("finishes on a terminal snapshot (run already over, server closes)", async () => {
    const r = await run([{ chunks: [snapshotFrame({ id: RUN, status: "COMPLETED", trace: [] })] }]);
    expect(r.outcome).toBe("finished");
    expect(r.calls.length).toBe(1);
  });

  it("reconnects with Last-Event-ID after a dropped stream", async () => {
    const r = await run([
      { chunks: [frame(1, "run.started", {}), frame(2, "node.started", {}, "n")] },
      { chunks: [frame(3, "run.finished", { status: "COMPLETED" })] },
    ]);
    expect(r.outcome).toBe("finished");
    expect(r.calls.length).toBe(2);
    expect(r.calls[1].headers["Last-Event-ID"]).toBe("2");
    expect(r.statuses.some((s) => s.kind === "reconnecting")).toBe(true);
  });

  it("sends a caller-supplied lastEventId on the first connect", async () => {
    const r = await run([{ chunks: [frame(8, "run.finished", { status: "COMPLETED" })] }], { lastEventId: "7" });
    expect(r.calls[0].headers["Last-Event-ID"]).toBe("7");
  });

  it.each([404, 405, 406, 501])("reports unsupported on HTTP %i without retrying", async (status) => {
    const r = await run([{ status, contentType: "application/json" }]);
    expect(r.outcome).toBe("unsupported");
    expect(r.calls.length).toBe(1);
    expect(r.statuses).toContainEqual({ kind: "unsupported", unsupported: true, httpStatus: status });
  });

  it("reports unsupported when the body is not an event stream", async () => {
    const r = await run([{ status: 200, contentType: "text/html", chunks: ["<html></html>"] }]);
    expect(r.outcome).toBe("unsupported");
  });

  it("gives up after 5 consecutive network failures", async () => {
    const r = await run([{ error: true }]);
    expect(r.outcome).toBe("gave-up");
    expect(r.calls.length).toBe(6);
    expect(r.statuses.filter((s) => s.kind === "reconnecting").length).toBe(5);
  });

  it("gives up when every connect delivers only the snapshot and then closes", async () => {
    const r = await run([{ chunks: [snapshotFrame({ id: RUN, status: "RUNNING", trace: [] })] }]);
    expect(r.outcome).toBe("gave-up");
    expect(r.calls.length).toBe(6);
  });

  it("caps total reconnects even when each connection delivers live events", async () => {
    const { impl, calls } = fakeFetch([{ chunks: [frame(1, "run.started", {})] }]);
    const outcome = await openRunStream({
      workflowId: "wf",
      runId: RUN,
      getToken: async () => null,
      fetchImpl: impl,
      backoffMs: () => 0,
      maxReconnects: 3,
      onEvent: () => undefined,
    });
    expect(outcome).toBe("gave-up");
    expect(calls.length).toBe(4);
  });

  it("retries 5xx and 401, then succeeds", async () => {
    const r = await run([{ status: 503 }, { status: 401 }, { chunks: [frame(1, "run.finished", { status: "COMPLETED" })] }]);
    expect(r.outcome).toBe("finished");
    expect(r.calls.length).toBe(3);
  });

  it("stops without retrying on 403 or an error frame", async () => {
    expect((await run([{ status: 403 }])).outcome).toBe("error");
    const r = await run([{ chunks: [`event: error\ndata: ${JSON.stringify({ type: "error", message: "nope" })}\n\n`] }]);
    expect(r.outcome).toBe("error");
    expect(r.calls.length).toBe(1);
    expect(r.events[0].data.message).toBe("nope");
  });

  it("aborts cleanly when the signal fires mid-stream", async () => {
    const ctrl = new AbortController();
    const p = run([{ chunks: [frame(1, "run.started", {})], hang: true }], { signal: ctrl.signal });
    await new Promise((res) => setTimeout(res, 10));
    ctrl.abort();
    const r = await p;
    expect(r.outcome).toBe("aborted");
    expect(r.events.map((e) => e.type)).toEqual(["run.started"]);
  });

  it("reconnects when the connection goes idle past the timeout", async () => {
    const { impl, calls } = fakeFetch([
      { chunks: [frame(1, "run.started", {})], hang: true },
      { chunks: [frame(2, "run.finished", { status: "COMPLETED" })] },
    ]);
    const outcome = await openRunStream({
      workflowId: "wf",
      runId: RUN,
      getToken: async () => null,
      fetchImpl: impl,
      backoffMs: () => 0,
      idleTimeoutMs: 20,
      onEvent: () => undefined,
    });
    expect(outcome).toBe("finished");
    expect(calls[0].headers.Authorization).toBeUndefined();
    expect(calls[1].headers["Last-Event-ID"]).toBe("1");
  });
});
