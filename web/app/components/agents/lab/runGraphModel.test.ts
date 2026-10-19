// Run graph model: stages, tool pairing, ids and layout. The pairing cases
// matter most - only tool events carry a server id, so a plan whose fallback
// id happens to equal a tool id must never be "closed" by that tool's result.

import { describe, expect, it } from "vitest";
import type { ActivityStep } from "../AgentActivityTrace";
import { FIXTURE_TURNS } from "./labFixtures";
import {
  RUN_GRAPH_NODE_HEIGHT,
  RUN_GRAPH_NODE_WIDTH,
  buildRunGraph,
  graphOfExchange,
  toolNamesOf,
  type RunGraphInput,
} from "./runGraphModel";

const server = (step: Omit<ActivityStep, "origin">): ActivityStep => ({ ...step, origin: "server" });

function input(steps: ActivityStep[], overrides: Partial<RunGraphInput> = {}): RunGraphInput {
  return { userMessage: "Where is my order?", steps, reply: "It shipped.", pending: false, onDevice: false, ...overrides };
}

const byId = (graph: ReturnType<typeof buildRunGraph>, id: string) => graph.nodes.find((n) => n.id === id);

describe("buildRunGraph", () => {
  it("builds a linear chain: user, plan, tool, reply", () => {
    const graph = buildRunGraph(
      input([
        server({ type: "run_started", id: 1 }),
        server({ type: "plan", id: 2, message: "Look up the order." }),
        server({ type: "tool_call", id: 1, tool: "orders_lookup", args: '{"id":"A-1"}' }),
        server({ type: "tool_result", id: 1, tool: "orders_lookup", preview: '{"status":"SHIPPED"}', ms: 412 }),
        server({ type: "run_finished", id: 5, ms: 900 }),
      ])
    );
    expect(graph.nodes.map((n) => [n.id, n.kind, n.stage])).toEqual([
      ["u", "user", 0],
      ["s1", "plan", 1],
      ["s2", "tool", 2],
      ["r", "reply", 3],
    ]);
    expect(graph.edges.map((e) => e.id)).toEqual(["e:u:s1", "e:s1:s2", "e:s2:r"]);
    expect(graph.edges.every((e) => e.live === false)).toBe(true);
    const tool = byId(graph, "s2");
    expect(tool).toMatchObject({ status: "ok", title: "orders_lookup", args: '{"id":"A-1"}', preview: '{"status":"SHIPPED"}', ms: 412 });
    // run_started / run_finished make no node; run_finished.ms lands on the reply.
    expect(byId(graph, "r")).toMatchObject({ status: "ok", ms: 900, message: "It shipped." });
    expect(byId(graph, "u")).toMatchObject({ title: "Where is my order?", message: "Where is my order?" });
  });

  it("falls back to totalMs for the reply when run_finished carries no ms", () => {
    expect(byId(buildRunGraph(input([], { totalMs: 640 })), "r")?.ms).toBe(640);
    expect(byId(buildRunGraph(input([])), "r")?.ms).toBeUndefined();
  });

  it("puts parallel tool calls in one stage and a later call in a new one", () => {
    const graph = buildRunGraph(
      input([
        server({ type: "tool_call", id: 1, tool: "a" }),
        server({ type: "tool_call", id: 2, tool: "b" }),
        server({ type: "tool_result", id: 1 }),
        // b is still open, so c joins the same stage.
        server({ type: "tool_call", id: 3, tool: "c" }),
        server({ type: "tool_result", id: 2 }),
        server({ type: "tool_result", id: 3 }),
        // Nothing open any more: d starts the next stage.
        server({ type: "tool_call", id: 4, tool: "d" }),
        server({ type: "tool_result", id: 4 }),
      ])
    );
    const stageOf = (tool: string) => graph.nodes.find((n) => n.title === tool)?.stage;
    expect([stageOf("a"), stageOf("b"), stageOf("c"), stageOf("d")]).toEqual([1, 1, 1, 2]);
    // Full bipartite set between consecutive stages.
    expect(graph.edges.map((e) => e.id)).toEqual([
      "e:u:s0",
      "e:u:s1",
      "e:u:s3",
      "e:s0:s6",
      "e:s1:s6",
      "e:s3:s6",
      "e:s6:r",
    ]);
  });

  it("closes a tool with an error event", () => {
    const graph = buildRunGraph(
      input([
        server({ type: "tool_call", id: 1, tool: "billing" }),
        server({ type: "error", id: 1, tool: "billing", message: "Refund history is unavailable.", ms: 1980 }),
      ])
    );
    expect(graph.nodes).toHaveLength(3);
    expect(byId(graph, "s0")).toMatchObject({ kind: "tool", status: "error", message: "Refund history is unavailable.", ms: 1980 });
  });

  it("does not let a closing event cross origins", () => {
    const graph = buildRunGraph(
      input([
        { type: "tool_call", id: 1, tool: "server_tool", origin: "server" },
        { type: "tool_result", id: 1, tool: "edge_tool", origin: "on-device" },
      ])
    );
    expect(byId(graph, "s0")).toMatchObject({ kind: "tool", status: "interrupted" });
    expect(byId(graph, "s1")).toMatchObject({ kind: "event", title: "tool_result", onDevice: true });
  });

  it("turns a handoff inside an open tool call into a badge, and a standalone handoff into a node", () => {
    const badge = buildRunGraph(
      input([
        server({ type: "tool_call", id: 1, tool: "orders_lookup" }),
        server({ type: "tool_call", id: 2, tool: "billing_specialist" }),
        server({ type: "handoff", id: 3 }),
        server({ type: "tool_result", id: 1 }),
        server({ type: "tool_result", id: 2 }),
      ])
    );
    expect(badge.nodes.some((n) => n.kind === "handoff")).toBe(false);
    // The most recently opened call carries it.
    expect(byId(badge, "s1")?.handoff).toBe(true);
    expect(byId(badge, "s0")?.handoff).toBeUndefined();

    const standalone = buildRunGraph(
      input([
        server({ type: "tool_call", id: 1, tool: "orders_lookup" }),
        server({ type: "tool_result", id: 1 }),
        server({ type: "handoff", id: 3, message: "Passed to billing." }),
      ])
    );
    expect(byId(standalone, "s2")).toMatchObject({ kind: "handoff", status: "ok", title: "Passed to billing." });
    expect(byId(standalone, "s0")?.handoff).toBeUndefined();
  });

  it("makes a blocked guardrail node with its reason", () => {
    const graph = buildRunGraph(input([server({ type: "guardrail", id: 1, tool: "tools", message: "Removed an account number." })]));
    expect(byId(graph, "s0")).toMatchObject({ kind: "guardrail", status: "blocked", message: "Removed an account number.", tool: "tools" });
  });

  it("marks the reply as streaming while pending, even when it is still empty, and its edge as live", () => {
    const graph = buildRunGraph(input([server({ type: "tool_call", id: 1, tool: "orders_lookup" })], { reply: "", pending: true }));
    expect(byId(graph, "s0")?.status).toBe("running");
    expect(byId(graph, "r")).toMatchObject({ kind: "reply", status: "streaming", title: "" });
    expect(graph.edges.map((e) => [e.id, e.live])).toEqual([
      ["e:u:s0", true],
      ["e:s0:r", true],
    ]);
  });

  it("marks a reply that ends with the interrupted marker", () => {
    const graph = buildRunGraph(input([], { reply: "The order is\n\n[reply interrupted]" }));
    expect(byId(graph, "r")?.status).toBe("interrupted");
  });

  it("omits the reply node only when the reply is empty and the turn is not pending", () => {
    const graph = buildRunGraph(input([server({ type: "tool_call", id: 1, tool: "orders_lookup" })], { reply: "" }));
    expect(graph.nodes.map((n) => n.id)).toEqual(["u", "s0"]);
    // A finished turn cannot still be running a tool.
    expect(byId(graph, "s0")?.status).toBe("interrupted");
  });

  it("keeps a closing event with no open call as an event node", () => {
    const graph = buildRunGraph(
      input([
        server({ type: "error", id: 7, tool: "ghost", message: "No such call." }),
        server({ type: "tool_result", id: 8, tool: "ghost", preview: "late" }),
      ])
    );
    expect(byId(graph, "s0")).toMatchObject({ kind: "event", status: "error", title: "error", message: "No such call." });
    expect(byId(graph, "s1")).toMatchObject({ kind: "event", status: "ok", title: "tool_result", preview: "late" });
  });

  it("survives colliding ids: a plan is not closed by a tool's result", () => {
    // Exactly what the playground produces: run_started and plan get
    // positional fallback ids (1, 2), and the emitter numbers tools from 1.
    const graph = buildRunGraph(
      input([
        server({ type: "run_started", id: 1 }),
        server({ type: "plan", id: 2, message: "Two lookups." }),
        server({ type: "tool_call", id: 1, tool: "orders_lookup" }),
        server({ type: "tool_call", id: 2, tool: "billing_specialist" }),
        server({ type: "tool_result", id: 1, preview: "shipped", ms: 412 }),
        server({ type: "error", id: 2, message: "unavailable", ms: 1980 }),
      ])
    );
    const ids = graph.nodes.map((n) => n.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toEqual(["u", "s1", "s2", "s3", "r"]);
    const plan = byId(graph, "s1");
    expect(plan).toMatchObject({ kind: "plan", status: "ok", message: "Two lookups." });
    expect(plan?.preview).toBeUndefined();
    expect(plan?.ms).toBeUndefined();
    expect(byId(graph, "s2")).toMatchObject({ kind: "tool", status: "ok", preview: "shipped", ms: 412 });
    expect(byId(graph, "s3")).toMatchObject({ kind: "tool", status: "error", message: "unavailable", ms: 1980 });
    const edgeIds = graph.edges.map((e) => e.id);
    expect(new Set(edgeIds).size).toBe(edgeIds.length);
  });

  it("draws a swarm step, and any other unknown type, as an event node", () => {
    const graph = buildRunGraph(
      input([server({ type: "swarm", id: 1, message: "3 members dispatched" }), server({ type: "something_new", id: 2 })])
    );
    expect(byId(graph, "s0")).toMatchObject({ kind: "event", status: "ok", title: "swarm", message: "3 members dispatched" });
    expect(byId(graph, "s1")).toMatchObject({ kind: "event", title: "something_new" });
  });

  it("ignores heartbeats", () => {
    expect(buildRunGraph(input([server({ type: "heartbeat", id: 1 })])).nodes.map((n) => n.id)).toEqual(["u", "r"]);
  });

  it("flags on-device steps and on-device turns", () => {
    const graph = buildRunGraph(
      input(
        [
          { type: "tool_call", id: "edge-1", tool: "calculator", origin: "on-device" },
          { type: "tool_result", id: "edge-1", tool: "calculator", origin: "on-device" },
        ],
        { onDevice: true }
      )
    );
    expect(graph.nodes.every((n) => n.onDevice === true)).toBe(true);
    expect(byId(graph, "s0")?.status).toBe("ok");
  });

  it("clamps a long title to one line and keeps the full text in message", () => {
    const long = `first line\n${"x".repeat(300)}`;
    const node = byId(buildRunGraph(input([], { userMessage: long })), "u");
    expect(node?.title.length).toBeLessThanOrEqual(80);
    expect(node?.title).not.toContain("\n");
    expect(node?.message).toBe(long);
  });

  it("lays stages out left to right, 200x64 nodes, 72 apart, each stage centred", () => {
    const graph = buildRunGraph(
      input([
        server({ type: "tool_call", id: 1, tool: "a" }),
        server({ type: "tool_call", id: 2, tool: "b" }),
        server({ type: "tool_result", id: 1 }),
        server({ type: "tool_result", id: 2 }),
      ])
    );
    expect([RUN_GRAPH_NODE_WIDTH, RUN_GRAPH_NODE_HEIGHT]).toEqual([200, 64]);
    // Three stages: 3 * 200 + 2 * 72 wide; the tallest has two rows: 2 * 64 + 20.
    expect([graph.width, graph.height]).toEqual([744, 148]);
    expect(graph.nodes.map((n) => [n.id, n.x, n.y])).toEqual([
      ["u", 0, 42],
      ["s0", 272, 0],
      ["s1", 272, 84],
      ["r", 544, 42],
    ]);
  });

  it("is deterministic and does not mutate its input", () => {
    const steps = FIXTURE_TURNS[1].steps ?? [];
    const frozen = JSON.stringify(steps);
    const a = buildRunGraph(input([...steps]));
    const b = buildRunGraph(input([...steps]));
    expect(a).toEqual(b);
    expect(JSON.stringify(steps)).toBe(frozen);
  });
});

describe("graphOfExchange and toolNamesOf", () => {
  it("reads the fixture exchange: two parallel tools, a handoff badge, a guardrail", () => {
    const graph = graphOfExchange(FIXTURE_TURNS[0], FIXTURE_TURNS[1]);
    expect(graph.nodes.map((n) => [n.id, n.kind, n.status, n.stage])).toEqual([
      ["u", "user", "ok", 0],
      ["s1", "plan", "ok", 1],
      ["s2", "tool", "ok", 2],
      ["s3", "tool", "error", 2],
      ["s7", "guardrail", "blocked", 3],
      ["r", "reply", "ok", 4],
    ]);
    expect(byId(graph, "s3")?.handoff).toBe(true);
    expect(byId(graph, "r")?.ms).toBe(3120);
    expect(toolNamesOf(graph)).toEqual(["orders_lookup", "billing_specialist"]);
  });

  it("handles an exchange with a missing side", () => {
    expect(graphOfExchange(FIXTURE_TURNS[0], null).nodes.map((n) => n.id)).toEqual(["u"]);
    expect(graphOfExchange(null, FIXTURE_TURNS[3]).nodes.map((n) => n.id)).toEqual(["u", "r"]);
  });

  it("lists each tool name once", () => {
    const graph = buildRunGraph(
      input([
        server({ type: "tool_call", id: 1, tool: "a" }),
        server({ type: "tool_result", id: 1 }),
        server({ type: "tool_call", id: 2, tool: "a" }),
        server({ type: "tool_result", id: 2 }),
      ])
    );
    expect(toolNamesOf(graph)).toEqual(["a"]);
  });
});

describe("memory events", () => {
  it("draws no node for a memory event and keeps the other ids positional", () => {
    const graph = buildRunGraph(
      input([
        server({ type: "run_started", id: 1 }),
        server({ type: "memory", id: 2, memory: { blockLabels: ["persona"], blocks: 1, privateFacts: 2, sharedFacts: 0 } }),
        server({ type: "tool_call", id: 1, tool: "orders_lookup" }),
        server({ type: "tool_result", id: 1, tool: "orders_lookup" }),
      ])
    );
    expect(graph.nodes.map((n) => [n.id, n.kind])).toEqual([
      ["u", "user"],
      ["s2", "tool"],
      ["r", "reply"],
    ]);
  });
});
