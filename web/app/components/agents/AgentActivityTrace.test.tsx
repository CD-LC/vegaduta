// AG-UI-style activity trace (2026-09-09): locks in the two normalisers both
// chat surfaces feed the shared component with, and the "never crash on an
// unknown event type" contract - a newer core ships event types before this
// console knows them, and the trace is a nicety that must never fail a turn
// (see AgentActivityTrace's header and AgentStepEventEmitter's javadoc).

import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import AgentActivityTrace, {
  appendEdgeToolEvent,
  buildTraceRows,
  describeMemoryUse,
  normalizeServerStep,
  stopReasonKind,
  stopReasonText,
  turnStopKind,
  type ActivityStep,
} from "./AgentActivityTrace";

describe("normalizeServerStep", () => {
  it("passes the emitter's tool_call/tool_result/error shape through with origin server", () => {
    const call = normalizeServerStep({ type: "tool_call", id: 1, tool: "web_search", args: '{"q":"x"}' }, 99);
    expect(call).toMatchObject({ type: "tool_call", id: 1, tool: "web_search", args: '{"q":"x"}', origin: "server" });
    const result = normalizeServerStep({ type: "tool_result", id: 1, tool: "web_search", preview: "3 hits", ms: 412 }, 99);
    expect(result).toMatchObject({ type: "tool_result", id: 1, preview: "3 hits", ms: 412 });
    const error = normalizeServerStep({ type: "error", id: 2, tool: "jira", message: "boom", ms: 5 }, 99);
    expect(error).toMatchObject({ type: "error", id: 2, message: "boom", ms: 5 });
  });

  it("keeps unknown types verbatim and picks up their text under any of the plausible keys", () => {
    expect(normalizeServerStep({ type: "plan", id: 3, text: "1. look up 2. answer" }, 0)).toMatchObject({
      type: "plan",
      message: "1. look up 2. answer",
    });
    expect(normalizeServerStep({ type: "guardrail", id: 4, reason: "pii scrubbed" }, 0)).toMatchObject({
      type: "guardrail",
      message: "pii scrubbed",
    });
    expect(normalizeServerStep({ type: "handoff", id: 5, name: "billing-agent" }, 0)).toMatchObject({
      type: "handoff",
      tool: "billing-agent",
    });
    expect(normalizeServerStep({ type: "something_new", id: 6, elapsedMs: 7 }, 0)).toMatchObject({
      type: "something_new",
      ms: 7,
    });
  });

  it("never throws: a missing id falls back, a non-object becomes an unknown row", () => {
    expect(normalizeServerStep({ type: "run_started" }, 42)).toMatchObject({ type: "run_started", id: 42 });
    expect(normalizeServerStep(null, 7)).toMatchObject({ type: "unknown", id: 7 });
    expect(normalizeServerStep("garbage", 8)).toMatchObject({ type: "unknown", id: 8, message: "garbage" });
    // Non-string args (a core that sends an object) are stringified, not dropped.
    expect(normalizeServerStep({ type: "tool_call", id: 1, tool: "t", args: { a: 1 } }, 0).args).toBe('{"a":1}');
  });
});

describe("appendEdgeToolEvent", () => {
  it("maps calling -> tool_call and done -> tool_result, pairing by tool name, badged on-device", () => {
    let steps: ActivityStep[] = [];
    steps = appendEdgeToolEvent(steps, { name: "lookup_order", phase: "calling" });
    expect(steps).toEqual([{ type: "tool_call", id: "edge-1", tool: "lookup_order", phase: "calling", origin: "on-device" }]);
    steps = appendEdgeToolEvent(steps, { name: "lookup_order", phase: "done" });
    expect(steps[1]).toMatchObject({ type: "tool_result", id: "edge-1", tool: "lookup_order", origin: "on-device" });
    // A second round gets its own id and the previous, already-closed row is left alone.
    steps = appendEdgeToolEvent(steps, { name: "lookup_order", phase: "calling" });
    steps = appendEdgeToolEvent(steps, { name: "lookup_order", phase: "error" });
    expect(steps[2]).toMatchObject({ type: "tool_call", id: "edge-2" });
    expect(steps[3]).toMatchObject({ type: "error", id: "edge-2", message: "Tool call failed" });
  });

  it("does not mutate the input array and survives an orphan closing event", () => {
    const original: ActivityStep[] = [];
    const next = appendEdgeToolEvent(original, { name: "x", phase: "done" });
    expect(original).toEqual([]);
    expect(next[0]).toMatchObject({ type: "tool_result", tool: "x", origin: "on-device" });
    expect(String(next[0].id)).toMatch(/^edge-orphan-/);
  });
});

describe("buildTraceRows", () => {
  it("folds a closing event into its call, keeps server and on-device ids apart, and keeps lifecycle events as rows", () => {
    const steps: ActivityStep[] = [
      { type: "run_started", id: 0, origin: "server" },
      { type: "tool_call", id: 1, tool: "a", origin: "server" },
      { type: "tool_call", id: "edge-1", tool: "b", origin: "on-device" },
      { type: "tool_result", id: 1, tool: "a", ms: 10, origin: "server" },
      { type: "run_finished", id: 0, origin: "server" },
    ];
    const rows = buildTraceRows(steps);
    expect(rows.map((r) => (r.kind === "tool" ? `tool:${r.call.tool}:${r.closing ? "closed" : "open"}` : `event:${r.step.type}`))).toEqual([
      "event:run_started",
      "tool:a:closed",
      "tool:b:open",
      "event:run_finished",
    ]);
  });

  it("drops heartbeat liveness beats - they keep the wire alive but are not activity", () => {
    // 2026-09-25: core emits {"type":"heartbeat"} on the step channel every
    // step-heartbeat-interval to defeat idle timeouts; they must never become
    // trace rows no matter which producer feeds the component.
    const steps: ActivityStep[] = [
      { type: "run_started", id: 0, origin: "server" },
      { type: "heartbeat", id: 1, ms: 15000, origin: "server" },
      { type: "heartbeat", id: 2, ms: 30000, origin: "server" },
      { type: "run_finished", id: 3, ms: 31000, origin: "server" },
    ];
    expect(buildTraceRows(steps).map((r) => (r.kind === "event" ? r.step.type : "tool"))).toEqual([
      "run_started",
      "run_finished",
    ]);
  });
});

describe("<AgentActivityTrace />", () => {
  it("renders nothing for an empty trace", () => {
    const { container } = render(<AgentActivityTrace steps={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders unknown types generically (humanised label) and the on-device badge, without crashing", () => {
    const steps: ActivityStep[] = [
      { type: "run_started", id: 0, origin: "server" },
      { type: "tool_call", id: 1, tool: "web_search", args: '{"q":"vega"}', origin: "server" },
      { type: "tool_result", id: 1, tool: "web_search", preview: "3 hits", ms: 120, origin: "server" },
      { type: "tool_call", id: "edge-1", tool: "lookup_order", origin: "on-device" },
      { type: "brand_new_thing", id: 9, message: "from a newer core", origin: "server" },
    ];
    render(<AgentActivityTrace steps={steps} defaultOpen />);
    expect(screen.getByText("4 steps")).toBeInTheDocument();
    expect(screen.getByText("Run started")).toBeInTheDocument();
    expect(screen.getByText("web_search")).toBeInTheDocument();
    expect(screen.getByText("3 hits")).toBeInTheDocument();
    expect(screen.getByText("120 ms")).toBeInTheDocument();
    expect(screen.getByText("on-device")).toBeInTheDocument();
    expect(screen.getByText("running…")).toBeInTheDocument();
    expect(screen.getByText("Brand new thing")).toBeInTheDocument();
    expect(screen.getByText("from a newer core")).toBeInTheDocument();
  });

  it("is collapsed by default and expands on click", () => {
    render(<AgentActivityTrace steps={[{ type: "tool_call", id: 1, tool: "jira", origin: "server" }]} />);
    expect(screen.queryByText("jira")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { expanded: false }));
    expect(screen.getByText("jira")).toBeInTheDocument();
  });
});

describe("stop reasons", () => {
  const STEP_LIMIT =
    "Agent 7d0c (session s1) exceeded the maximum of 15 tool calls for a single turn - stopped to prevent a runaway loop.";

  it("recognises the run-budget token and the tool-call ceiling sentence, and nothing else", () => {
    expect(stopReasonKind("run_budget_exceeded")).toBe("run_budget");
    expect(stopReasonKind('{"error":"...","stopReason":"run_budget_exceeded"}')).toBe("run_budget");
    expect(stopReasonKind(STEP_LIMIT)).toBe("step_limit");
    expect(stopReasonKind("pii scrubbed")).toBeNull();
    expect(stopReasonKind("")).toBeNull();
    expect(stopReasonKind(undefined)).toBeNull();
  });

  it("turns a known reason into a sentence with a next step and leaves unknown reasons alone", () => {
    expect(stopReasonText("run_budget_exceeded")).toMatch(/^Stopped: this run reached its spending cap/);
    expect(stopReasonText(STEP_LIMIT)).toMatch(/^Stopped: too many tool calls in one turn/);
    expect(stopReasonText("pii scrubbed")).toBeNull();
  });

  it("finds a turn's stop reason on guardrail/error steps only", () => {
    expect(turnStopKind(null)).toBeNull();
    expect(turnStopKind([{ type: "tool_result", id: 1, preview: "run_budget_exceeded", origin: "server" }])).toBeNull();
    expect(
      turnStopKind([
        { type: "tool_call", id: 1, tool: "delegate_parallel", origin: "server" },
        { type: "guardrail", id: 2, tool: "delegate_parallel", message: "run_budget_exceeded", origin: "server" },
      ])
    ).toBe("run_budget");
    expect(turnStopKind([{ type: "error", id: 3, message: STEP_LIMIT, origin: "server" }])).toBe("step_limit");
    expect(turnStopKind([{ type: "guardrail", id: 4, message: "pii scrubbed", origin: "server" }])).toBeNull();
  });

  it("renders the sentence instead of the raw token in the trace, and unknown reasons as before", () => {
    render(
      <AgentActivityTrace
        defaultOpen
        steps={[
          { type: "guardrail", id: 1, tool: "ask_agent_billing", message: "run_budget_exceeded", origin: "server" },
          { type: "guardrail", id: 2, tool: "input", message: "pii scrubbed", origin: "server" },
        ]}
      />
    );
    expect(screen.getByText(/this run reached its spending cap/)).toBeInTheDocument();
    expect(screen.queryByText("run_budget_exceeded")).not.toBeInTheDocument();
    expect(screen.getByText("pii scrubbed")).toBeInTheDocument();
  });
});

// Memory-used event (core AgentStepEventEmitter.memoryRecall): counts and note
// labels only, never the remembered text.
describe("memory step", () => {
  const event = {
    type: "memory",
    blocks: 2,
    blockLabels: ["user_profile", "project:acme"],
    privateFacts: 1,
    sharedFacts: 1,
    factIds: ["f1", "f2"],
  };

  it("normalises the event's labels and counts onto the step", () => {
    expect(normalizeServerStep(event, 3)).toMatchObject({
      type: "memory",
      id: 3,
      origin: "server",
      memory: { blockLabels: ["user_profile", "project:acme"], blocks: 2, privateFacts: 1, sharedFacts: 1 },
    });
    // Only memory events carry the extra field.
    expect(normalizeServerStep({ type: "plan", text: "x" }, 1).memory).toBeUndefined();
  });

  it("never throws on a malformed memory event", () => {
    expect(normalizeServerStep({ type: "memory", blockLabels: "persona", privateFacts: "2", sharedFacts: -1 }, 1).memory).toEqual({
      blockLabels: [],
      blocks: 0,
      privateFacts: 0,
      sharedFacts: 0,
    });
    expect(normalizeServerStep({ type: "memory", blocks: 3, blockLabels: [null, 7] }, 1).memory).toMatchObject({
      blockLabels: [],
      blocks: 3,
    });
  });

  it("describes what was used in one sentence", () => {
    expect(describeMemoryUse({ blockLabels: ["user_profile"], blocks: 1, privateFacts: 1, sharedFacts: 1 })).toBe(
      "Used your profile note and 2 saved memories"
    );
    expect(describeMemoryUse({ blockLabels: ["persona", "project:acme"], blocks: 2, privateFacts: 0, sharedFacts: 0 })).toBe(
      "Used your persona note and acme project note"
    );
    expect(describeMemoryUse({ blockLabels: [], blocks: 0, privateFacts: 1, sharedFacts: 0 })).toBe("Used 1 saved memory");
    expect(describeMemoryUse({ blockLabels: [], blocks: 2, privateFacts: 0, sharedFacts: 3 })).toBe(
      "Used your 2 notes and 3 saved memories"
    );
    expect(describeMemoryUse({ blockLabels: [], blocks: 0, privateFacts: 0, sharedFacts: 0 })).toBe("Used your memory");
    expect(describeMemoryUse(undefined)).toBe("Used your memory");
  });

  it("shows a chip linking to /memory without expanding the trace, and a Memory row once expanded", () => {
    const steps: ActivityStep[] = [
      { type: "run_started", id: 1, origin: "server" },
      normalizeServerStep(event, 2),
    ];
    render(<AgentActivityTrace steps={steps} />);
    const chip = screen.getByTestId("memory-used-chip");
    expect(chip).toHaveAttribute("href", "/memory");
    expect(chip).toHaveAttribute(
      "aria-label",
      "Used your profile note, acme project note and 2 saved memories - opens Memory in a new tab"
    );
    // The memory event is shown by the chip, so it is not counted as a step.
    expect(screen.getByText("1 step")).toBeInTheDocument();
    expect(chip).toHaveTextContent("Used your profile note, acme project note and 2 saved memories");
    expect(screen.queryByText("Memory")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { expanded: false }));
    expect(screen.getByText("Memory")).toBeInTheDocument();
  });

  it("shows no chip for a turn that used no memory", () => {
    render(<AgentActivityTrace steps={[{ type: "tool_call", id: 1, tool: "jira", origin: "server" }]} />);
    expect(screen.queryByTestId("memory-used-chip")).not.toBeInTheDocument();
  });
});

describe("MCP app pointer (flag-gated)", () => {
  const app = { appId: "abcdefghijklmnopqrstuvwx", server: "weather", tool: "forecast", uri: "ui://weather/forecast" };

  it("keeps a plain tool_result key-for-key unchanged and carries a valid app pointer through", () => {
    const plain = normalizeServerStep({ type: "tool_result", id: 1, tool: "t", preview: "p", ms: 3 }, 9);
    expect("app" in plain).toBe(false);
    const withApp = normalizeServerStep({ type: "tool_result", id: 1, tool: "t", preview: "p", ms: 3, app }, 9);
    expect(withApp.app).toEqual(app);
  });

  it("drops an invalid pointer instead of passing hostile data along", () => {
    for (const bad of [{ ...app, uri: "https://evil.example/x" }, { ...app, appId: "x" }, "str", 5, [app], null]) {
      const step = normalizeServerStep({ type: "tool_result", id: 1, tool: "t", app: bad }, 9);
      expect("app" in step).toBe(false);
    }
    // only a tool_result may carry one
    expect("app" in normalizeServerStep({ type: "tool_call", id: 1, tool: "t", app }, 9)).toBe(false);
  });

  it("mounts no app card with the flag off (the default), even when a pointer and host are present", () => {
    const steps: ActivityStep[] = [
      { type: "tool_call", id: 1, tool: "mcp__weather__forecast", origin: "server" },
      { type: "tool_result", id: 1, tool: "mcp__weather__forecast", origin: "server", app },
    ];
    const { container } = render(<AgentActivityTrace steps={steps} appHost={{ agentId: "agent-1" }} />);
    expect(container.querySelector('[data-testid="mcp-app-card"]')).toBeNull();
    expect(container.querySelector("iframe")).toBeNull();
  });
});
