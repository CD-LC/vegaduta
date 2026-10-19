// Run Graph panel: the turn selector, the inspector (by click and by the
// Previous/Next buttons), and the fork flow - above all its ORDER: probe, then
// plan, then snapshot, then rewind, then the re-send, and no rewind at all
// when the plan is refused. The React Flow canvas and the HTTP seam are mocked;
// the graph model, fork maths and snapshot store are real.
//
// Split by area: this file covers the selector, the graph and the inspector, plus the real
// React Flow canvas; RunGraphPanel.fork.test.tsx and RunGraphPanel.compare.test.tsx cover the rest.

import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { choose, optionsOf, selectedIndex } from "./selectTestUtils";
import { fireEvent, render, screen, within } from "@testing-library/react";

const probeSessionEntryCount = vi.hoisted(() => vi.fn());
const rewindChatSession = vi.hoisted(() => vi.fn());

vi.mock("../../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
// Dollar figures only render where billing is on (personal installs hide them).
const billingOn = vi.hoisted(() => ({ value: true }));
vi.mock("../../../lib/billing-flag", () => ({ isBillingEnabled: () => billingOn.value }));
vi.mock("./labApi", () => ({
  LAB_MAX_MESSAGE_CHARS: 8000,
  labApi: {
    probeSessionEntryCount: (...a: unknown[]) => probeSessionEntryCount(...a),
    rewindChatSession: (...a: unknown[]) => rewindChatSession(...a),
  },
}));
vi.mock("./RunGraphCanvas", async () => ({
  default: (await import("./runGraphTestKit")).MockRunGraphCanvas,
}));

import RunGraphPanel from "./RunGraphPanel";
import { clearMemorySnapshots } from "./forkSnapshots";
import { graphOfExchange } from "./runGraphModel";
import { FIXTURE_TURNS } from "./labFixtures";
import type { LabTurn } from "./types";
import { cleanReply, followBox, inspector, nodeIds, props, turnSelect } from "./runGraphTestKit";

beforeEach(() => {
  window.localStorage.clear();
  clearMemorySnapshots();
  probeSessionEntryCount.mockReset();
  rewindChatSession.mockReset();
});

describe("RunGraphPanel - selector and graph", () => {
  it("shows the empty state for an empty transcript", () => {
    render(<RunGraphPanel {...props({ turns: [] })} />);
    expect(screen.getByText("graphEmpty")).toBeInTheDocument();
    expect(screen.queryByTestId("mock-canvas")).not.toBeInTheDocument();
  });

  it("lists every exchange and starts on the latest one, following it", () => {
    render(<RunGraphPanel {...props()} />);
    expect(optionsOf(turnSelect())).toHaveLength(2);
    expect(optionsOf(turnSelect())[0].textContent).toContain('"n":1');
    expect(optionsOf(turnSelect())[0].textContent).toContain("Where is order A-10432");
    expect(selectedIndex(turnSelect()) + 1).toBe(2);
    expect(followBox().checked).toBe(true);
    expect(screen.getByTestId("mock-canvas")).toHaveAttribute("aria-label", "graphCanvasLabel");
    // Exchange 2 answered directly: just the user and reply nodes.
    expect(nodeIds()).toEqual(["node-u", "node-r"]);
    expect(screen.getByText("graphNoSteps")).toBeInTheDocument();
  });

  it("pins a picked exchange, and goes back to the latest when follow is ticked again", () => {
    render(<RunGraphPanel {...props()} />);
    choose(turnSelect(), 0);
    expect(followBox().checked).toBe(false);
    expect(nodeIds()).toEqual(["node-u", "node-s1", "node-s2", "node-s3", "node-s7", "node-r"]);
    expect(screen.queryByText("graphNoSteps")).not.toBeInTheDocument();
    fireEvent.click(followBox());
    expect(selectedIndex(turnSelect()) + 1).toBe(2);
    expect(nodeIds()).toEqual(["node-u", "node-r"]);
  });

  it("grows with the transcript: follows a new exchange, live, step by step", () => {
    const { rerender } = render(<RunGraphPanel {...props()} />);
    const pending: LabTurn[] = [
      ...FIXTURE_TURNS,
      { role: "user", content: "And on Sundays?" },
      { role: "assistant", content: "", ref: "ref-live", pending: true, steps: [{ type: "run_started", id: 1, origin: "server" }] },
    ];
    rerender(<RunGraphPanel {...props({ turns: pending, busy: true })} />);
    expect(selectedIndex(turnSelect()) + 1).toBe(3);
    expect(screen.getByText("graphLive")).toBeInTheDocument();
    expect(nodeIds()).toEqual(["node-u", "node-r"]);
    expect(screen.getByTestId("node-r")).toHaveAttribute("data-status", "streaming");

    const withTool = pending.slice();
    withTool[5] = {
      ...pending[5],
      steps: [
        { type: "run_started", id: 1, origin: "server" },
        { type: "tool_call", id: 1, tool: "hours_lookup", origin: "server" },
      ],
    };
    rerender(<RunGraphPanel {...props({ turns: withTool, busy: true })} />);
    expect(nodeIds()).toEqual(["node-u", "node-s1", "node-r"]);
    expect(screen.getByTestId("node-s1")).toHaveAttribute("data-status", "running");
  });

  it("stays on a pinned exchange while the transcript grows", () => {
    const { rerender } = render(<RunGraphPanel {...props()} />);
    choose(turnSelect(), 0);
    rerender(<RunGraphPanel {...props({ turns: [...FIXTURE_TURNS, { role: "user", content: "More" }, cleanReply("Sure.")] })} />);
    expect(optionsOf(turnSelect())).toHaveLength(3);
    expect(selectedIndex(turnSelect()) + 1).toBe(1);
    expect(screen.queryByText("graphLive")).not.toBeInTheDocument();
  });

  it("offers Cancel turn only while a turn is actually running", () => {
    const onCancelTurn = vi.fn();
    const { rerender } = render(<RunGraphPanel {...props({ onCancelTurn })} />);
    expect(screen.queryByRole("button", { name: "graphCancelTurn" })).not.toBeInTheDocument();
    // Busy with no turn in flight (an attachment upload): nothing to cancel.
    rerender(<RunGraphPanel {...props({ onCancelTurn, busy: true })} />);
    expect(screen.queryByRole("button", { name: "graphCancelTurn" })).not.toBeInTheDocument();
    const running: LabTurn[] = [...FIXTURE_TURNS, { role: "user", content: "More" }, { role: "assistant", content: "", pending: true }];
    rerender(<RunGraphPanel {...props({ onCancelTurn, busy: true, turns: running })} />);
    fireEvent.click(screen.getByRole("button", { name: "graphCancelTurn" }));
    expect(onCancelTurn).toHaveBeenCalledTimes(1);
  });

  it("does not say 'answered directly' about a message that was never answered", () => {
    // The send failed before the first token: the playground drops the
    // pending reply and the transcript ends on a lone user message.
    const orphan: LabTurn[] = [...FIXTURE_TURNS, { role: "user", content: "Are you there?" }];
    const { rerender } = render(<RunGraphPanel {...props({ turns: orphan })} />);
    expect(nodeIds()).toEqual(["node-u"]);
    expect(screen.queryByText("graphNoSteps")).not.toBeInTheDocument();
    expect(screen.getByText("graphNoReply")).toBeInTheDocument();
    // Not an alert: nothing was forked here.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    // While the turn is still running it is neither.
    rerender(<RunGraphPanel {...props({ busy: true, turns: [...orphan, { role: "assistant", content: "", ref: "p", pending: true }] })} />);
    expect(screen.queryByText("graphNoSteps")).not.toBeInTheDocument();
    expect(screen.queryByText("graphNoReply")).not.toBeInTheDocument();
    // An empty reply is not "answered directly" either.
    rerender(<RunGraphPanel {...props({ turns: [...orphan, { role: "assistant", content: "", ref: "p" }] })} />);
    expect(screen.queryByText("graphNoSteps")).not.toBeInTheDocument();
  });

  it("shows tokens and cost only when the turn reported them", () => {
    const { rerender } = render(<RunGraphPanel {...props()} />);
    const metrics = () => within(screen.getByTestId("lab-graph-metrics"));
    expect(metrics().getByText("graphMetricElapsed")).toBeInTheDocument();
    expect(metrics().getByText("640 ms")).toBeInTheDocument();
    expect(metrics().getByText("graphMetricTokens")).toBeInTheDocument();
    expect(metrics().getByText("1228")).toBeInTheDocument();
    expect(metrics().getByText("$0.0003")).toBeInTheDocument();
    billingOn.value = false;
    rerender(<RunGraphPanel {...props()} />);
    expect(metrics().queryByText("$0.0003")).not.toBeInTheDocument();
    billingOn.value = true;
    rerender(<RunGraphPanel {...props()} />);

    rerender(<RunGraphPanel {...props({ turns: [{ role: "user", content: "Hi" }, cleanReply("Hello.")] })} />);
    expect(metrics().getByText("graphMetricTools")).toBeInTheDocument();
    expect(metrics().queryByText("graphMetricTokens")).not.toBeInTheDocument();
    expect(metrics().queryByText("graphMetricCost")).not.toBeInTheDocument();
  });
});

describe("RunGraphPanel - inspector", () => {
  it("shows a hint until a step is selected, then that step's fields", () => {
    render(<RunGraphPanel {...props()} />);
    choose(turnSelect(), 0);
    expect(inspector().getByText("graphInspectHint")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("node-s2"));
    expect(screen.getByTestId("mock-canvas")).toHaveAttribute("data-selected", "s2");
    expect(inspector().queryByText("graphInspectHint")).not.toBeInTheDocument();
    expect(inspector().getByText("graphNodeTool")).toBeInTheDocument();
    expect(inspector().getByText("graphStatusOk")).toBeInTheDocument();
    expect(inspector().getByText("orders_lookup")).toBeInTheDocument();
    expect(inspector().getByText('{"orderId":"A-10432"}')).toBeInTheDocument();
    expect(inspector().getByText('{"status":"SHIPPED","eta":"2026-09-25"}')).toBeInTheDocument();
    expect(inspector().getByText("412 ms")).toBeInTheDocument();
    expect(inspector().getByText("graphPreviewNote")).toBeInTheDocument();

    // The failed call, which also carried the handoff.
    fireEvent.click(screen.getByTestId("node-s3"));
    expect(inspector().getByText("graphStatusError")).toBeInTheDocument();
    expect(inspector().getByText("graphInspectError")).toBeInTheDocument();
    expect(inspector().getByText("Refund history is unavailable right now.")).toBeInTheDocument();
    expect(inspector().getByText("graphHandoffBadge")).toBeInTheDocument();
    expect(inspector().getByText("2.0 s")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("node-s7"));
    expect(inspector().getByText("graphStatusBlocked")).toBeInTheDocument();
    expect(inspector().getByText("graphInspectReason")).toBeInTheDocument();
    expect(inspector().getByText("Removed an account number from a tool result.")).toBeInTheDocument();
    expect(inspector().queryByText("graphPreviewNote")).not.toBeInTheDocument();
  });

  it("reaches every node with Next and Previous, wrapping at both ends", () => {
    render(<RunGraphPanel {...props()} />);
    choose(turnSelect(), 0);
    const selected = () => screen.getByTestId("mock-canvas").getAttribute("data-selected");
    const next = inspector().getByRole("button", { name: "graphInspectNext" });
    const prev = inspector().getByRole("button", { name: "graphInspectPrev" });

    const seen: (string | null)[] = [];
    for (let i = 0; i < 6; i++) {
      fireEvent.click(next);
      seen.push(selected());
    }
    expect(seen).toEqual(["u", "s1", "s2", "s3", "s7", "r"]);
    expect(inspector().getByText("6 / 6")).toBeInTheDocument();
    fireEvent.click(next);
    expect(selected()).toBe("u");
    // The user node shows its text and the fork control.
    expect(inspector().getByText(FIXTURE_TURNS[0].content)).toBeInTheDocument();
    expect(inspector().getByRole("button", { name: "graphFork" })).toBeEnabled();
    fireEvent.click(prev);
    expect(selected()).toBe("r");
    expect(inspector().getByText("graphNodeReply")).toBeInTheDocument();
    expect(inspector().queryByRole("button", { name: "graphFork" })).not.toBeInTheDocument();
  });

  it("Previous with nothing selected starts from the last node", () => {
    render(<RunGraphPanel {...props()} />);
    fireEvent.click(inspector().getByRole("button", { name: "graphInspectPrev" }));
    expect(screen.getByTestId("mock-canvas")).toHaveAttribute("data-selected", "r");
  });

  it("announces which step is selected, and keeps the step's text out of the live region", () => {
    const streaming: LabTurn[] = [
      ...FIXTURE_TURNS,
      { role: "user", content: "Tell me more" },
      { role: "assistant", content: "We are", ref: "p", pending: true, steps: [{ type: "run_started", id: 1, origin: "server" }] },
    ];
    const { rerender } = render(<RunGraphPanel {...props({ busy: true, turns: streaming })} />);
    const status = () => screen.getByTestId("lab-graph-inspector-status");
    expect(status()).toHaveAttribute("role", "status");
    expect(status()).toHaveTextContent("");
    fireEvent.click(screen.getByTestId("node-r"));
    expect(status()).toHaveTextContent("graphNodeReply, graphStatusStreaming, 2 / 2");
    // The reply text is shown, but not inside anything that announces itself.
    const text = inspector().getByText("We are");
    expect(text.closest("[aria-live], [role='status'], [role='alert']")).toBeNull();

    // More tokens arrive: the announcement does not change, so nothing is re-read.
    const before = status().textContent;
    rerender(
      <RunGraphPanel
        {...props({
          busy: true,
          turns: [...streaming.slice(0, -1), { ...streaming[streaming.length - 1], content: "We are here Monday to Saturday" }],
        })}
      />
    );
    expect(inspector().getByText("We are here Monday to Saturday")).toBeInTheDocument();
    expect(status().textContent).toBe(before);
    // The user node has no status of its own.
    fireEvent.click(screen.getByTestId("node-u"));
    expect(status()).toHaveTextContent("graphNodeUser, 1 / 2");
  });

  it("drops the selection when another exchange is shown", () => {
    render(<RunGraphPanel {...props()} />);
    fireEvent.click(screen.getByTestId("node-r"));
    choose(turnSelect(), 0);
    expect(screen.getByTestId("mock-canvas")).toHaveAttribute("data-selected", "");
    expect(inspector().getByText("graphInspectHint")).toBeInTheDocument();
  });
});

describe("RunGraphCanvas (real React Flow)", () => {
  beforeAll(() => {
    // React Flow measures its container.
    (globalThis as any).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
    (globalThis as any).DOMMatrixReadOnly = class {
      m22 = 1;
    };
  });

  it("draws one div node per step, with a text status, and selects on click", async () => {
    const { default: RealCanvas } = await vi.importActual<typeof import("./RunGraphCanvas")>("./RunGraphCanvas");
    const graph = graphOfExchange(FIXTURE_TURNS[0], FIXTURE_TURNS[1]);
    const onSelect = vi.fn();
    const { container, rerender } = render(
      <div style={{ width: 900, height: 400 }}>
        <RealCanvas graph={graph} selectedId={null} onSelect={onSelect} ariaLabel="graph of turn 1" />
      </div>
    );
    expect(screen.getByRole("group", { name: "graph of turn 1" })).toBeInTheDocument();
    const nodes = Array.from(container.querySelectorAll<HTMLElement>(".alab-node"));
    expect(nodes.map((n) => [n.tagName, n.dataset.kind, n.dataset.status])).toEqual([
      ["DIV", "user", "ok"],
      ["DIV", "plan", "ok"],
      ["DIV", "tool", "ok"],
      ["DIV", "tool", "error"],
      ["DIV", "guardrail", "blocked"],
      ["DIV", "reply", "ok"],
    ]);
    // The node surface is never a button (the global button rule adds a press transform).
    expect(container.querySelector(".react-flow__node button")).toBeNull();
    // Status in words, not colour alone; the handoff is a badge on its tool.
    expect(within(nodes[3]).getByText("graphStatusError")).toBeInTheDocument();
    expect(within(nodes[3]).getByText("graphHandoffBadge")).toBeInTheDocument();
    expect(within(nodes[3]).getByText("billing_specialist")).toBeInTheDocument();
    expect(within(nodes[4]).getByText("graphStatusBlocked")).toBeInTheDocument();
    expect(within(nodes[0]).queryByText("graphStatusOk")).not.toBeInTheDocument();
    expect(nodes.every((n) => n.dataset.selected === "false")).toBe(true);

    fireEvent.click(nodes[3]);
    expect(onSelect).toHaveBeenCalledWith("s3");

    rerender(
      <div style={{ width: 900, height: 400 }}>
        <RealCanvas graph={graph} selectedId="s3" onSelect={onSelect} ariaLabel="graph of turn 1" />
      </div>
    );
    const selected = Array.from(container.querySelectorAll<HTMLElement>(".alab-node")).filter((n) => n.dataset.selected === "true");
    expect(selected.map((n) => n.dataset.kind)).toEqual(["tool"]);
  });
});
