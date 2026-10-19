// Run cost card + stop notice (trust T3). Pins the honesty rules: nothing is
// rendered without real numbers, a FREE-plan 403 is silent unless the host
// asks for the one-line note, unpriced calls are called out, and the figure
// is re-read after each turn.

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const getSessionLlmUsageSummary = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get getSessionLlmUsageSummary() {
      return getSessionLlmUsageSummary;
    },
  },
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, string | number>) =>
    v ? `${key}:${Object.values(v).join(",")}` : key,
}));

import RunCostCard, { formatRunCost, recentSteps } from "./RunCostCard";
import TurnStopNotice from "./TurnStopNotice";
import type { RunCostStep, RunCostSummary } from "../../lib/api";

const step = (index: number, cumulativeCostUsd: number | null, over: Partial<RunCostStep> = {}): RunCostStep => ({
  index,
  id: `call-${index}`,
  offsetMs: index * 100,
  latencyMs: 50,
  providerName: "ANTHROPIC",
  model: "model-x",
  success: true,
  promptTokens: 100,
  completionTokens: 20,
  costUsd: 0.001,
  cumulativeCostUsd,
  cacheReadTokens: null,
  cacheWriteTokens: null,
  cacheHitRatio: null,
  agentVersionId: null,
  traceId: null,
  spanId: null,
  startedAt: "2026-10-03T10:00:00Z",
  ...over,
});

const summary = (over: Partial<RunCostSummary> = {}): RunCostSummary => ({
  sessionId: "s1",
  agentId: "a1",
  callCount: 2,
  failedCalls: 0,
  unpricedCalls: 0,
  promptTokens: 200,
  completionTokens: 40,
  totalTokens: 1240,
  totalCostUsd: 0.0123,
  cacheReadTokens: 0,
  cacheWriteTokens: 0,
  cacheAccountedCalls: 0,
  cacheHitRatio: null,
  startedAt: "2026-10-03T10:00:00Z",
  endedAt: "2026-10-03T10:00:05Z",
  wallClockMs: 5000,
  summedLatencyMs: 100,
  steps: [step(0, 0.005), step(1, 0.0123, { success: false })],
  ...over,
});

beforeAll(() => {
  // Radix Popover's positioning observes element size; jsdom has no ResizeObserver.
  if (!("ResizeObserver" in globalThis)) {
    (globalThis as any).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});

beforeEach(() => {
  getSessionLlmUsageSummary.mockReset();
  // The $ figures only exist where billing is on - the hosted posture.
  process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
});

afterEach(() => {
  delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
  delete window.__ENV;
});

describe("formatRunCost / recentSteps", () => {
  it("never rounds a small real cost to zero and shows unknown as a dash", () => {
    expect(formatRunCost(1.234)).toBe("$1.23");
    expect(formatRunCost(0.0421)).toBe("$0.042");
    expect(formatRunCost(0.00042)).toBe("$0.00042");
    expect(formatRunCost("0.5")).toBe("$0.500");
    expect(formatRunCost(0)).toBe("$0");
    expect(formatRunCost(null)).toBe("—");
    expect(formatRunCost("abc")).toBe("—");
  });

  it("keeps the most recent steps, oldest first", () => {
    const steps = [step(0, 1), step(1, 2), step(2, 3)];
    expect(recentSteps(steps, 2).map((s) => s.index)).toEqual([1, 2]);
    expect(recentSteps(null, 2)).toEqual([]);
  });
});

describe("RunCostCard", () => {
  it("renders nothing without a session and does not call the API", () => {
    const { container } = render(<RunCostCard agentId="a1" sessionId={null} />);
    expect(container.firstChild).toBeNull();
    expect(getSessionLlmUsageSummary).not.toHaveBeenCalled();
  });

  it("renders nothing for a session with no recorded calls", async () => {
    getSessionLlmUsageSummary.mockResolvedValue(summary({ callCount: 0, steps: [], totalCostUsd: 0 }));
    const { container } = render(<RunCostCard agentId="a1" sessionId="s1" />);
    await waitFor(() => expect(getSessionLlmUsageSummary).toHaveBeenCalledWith("a1", "s1"));
    expect(container.firstChild).toBeNull();
  });

  it("shows the total on the trigger and totals, tokens and cumulative steps when opened", async () => {
    getSessionLlmUsageSummary.mockResolvedValue(summary());
    render(<RunCostCard agentId="a1" sessionId="s1" />);
    const trigger = await screen.findByTestId("run-cost-trigger");
    expect(trigger.textContent).toContain("$0.012");
    expect(screen.queryByTestId("run-cost-panel")).toBeNull();

    fireEvent.click(trigger);
    expect(await screen.findByTestId("run-cost-panel")).toBeTruthy();
    expect(screen.getByTestId("run-cost-total").textContent).toBe("$0.012");
    expect(screen.getByTestId("run-cost-calls").textContent).toBe("2");
    expect(screen.getByTestId("run-cost-tokens").textContent).toBe((1240).toLocaleString());
    const rows = screen.getAllByTestId("run-cost-step");
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain("$0.00500");
    expect(rows[1].textContent).toContain("stepFailed");
    expect(screen.queryByTestId("run-cost-unpriced")).toBeNull();
  });

  it("shows thinking tokens only when a provider reported them outside completion tokens", async () => {
    getSessionLlmUsageSummary.mockResolvedValue(summary({ reasoningTokens: 800 }));
    const { unmount } = render(<RunCostCard agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("run-cost-trigger"));
    expect((await screen.findByTestId("run-cost-reasoning")).textContent).toBe((800).toLocaleString());
    unmount();

    getSessionLlmUsageSummary.mockResolvedValue(summary({ reasoningTokens: null }));
    render(<RunCostCard agentId="a1" sessionId="s2" />);
    fireEvent.click(await screen.findByTestId("run-cost-trigger"));
    await screen.findByTestId("run-cost-panel");
    expect(screen.queryByTestId("run-cost-reasoning")).toBeNull();
  });

  it("calls out unpriced calls, because the total is then a floor", async () => {
    getSessionLlmUsageSummary.mockResolvedValue(summary({ unpricedCalls: 3 }));
    render(<RunCostCard agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("run-cost-trigger"));
    expect((await screen.findByTestId("run-cost-unpriced")).textContent).toBe("unpricedMany:3");
  });

  it("on a no-billing install (self-hosted) shows tokens, never a dollar figure", async () => {
    delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
    getSessionLlmUsageSummary.mockResolvedValue(summary());
    render(<RunCostCard agentId="a1" sessionId="s1" />);
    const trigger = await screen.findByTestId("run-cost-trigger");
    expect(trigger.textContent).toContain("1,240");
    expect(trigger.textContent).not.toContain("$");

    fireEvent.click(trigger);
    expect(await screen.findByTestId("run-cost-panel")).toBeTruthy();
    expect(screen.getByTestId("run-cost-total").textContent).toBe("1,240 tok");
    const rows = screen.getAllByTestId("run-cost-step");
    expect(rows[0].textContent).not.toContain("$");
    expect(screen.queryByTestId("run-cost-unpriced")).toBeNull();
  });

  it("is silent on a FREE-plan 403 unless the host asks for the note", async () => {
    getSessionLlmUsageSummary.mockRejectedValue(Object.assign(new Error("Premium"), { status: 403 }));
    const quiet = render(<RunCostCard agentId="a1" sessionId="s1" />);
    await waitFor(() => expect(getSessionLlmUsageSummary).toHaveBeenCalledTimes(1));
    expect(quiet.container.firstChild).toBeNull();
    quiet.unmount();

    render(<RunCostCard agentId="a1" sessionId="s1" showPlanNote />);
    expect((await screen.findByTestId("run-cost-plan-note")).textContent).toBe("premiumNote");
    expect(screen.queryByTestId("run-cost-trigger")).toBeNull();
  });

  it("hides itself on 404 (older core) even when the plan note is enabled", async () => {
    getSessionLlmUsageSummary.mockRejectedValue(Object.assign(new Error("Not found"), { status: 404 }));
    const { container } = render(<RunCostCard agentId="a1" sessionId="s1" showPlanNote />);
    await waitFor(() => expect(getSessionLlmUsageSummary).toHaveBeenCalledTimes(1));
    expect(container.firstChild).toBeNull();
  });

  it("re-reads the summary when a turn finishes", async () => {
    getSessionLlmUsageSummary.mockResolvedValue(summary({ totalCostUsd: 0.5 }));
    const { rerender } = render(<RunCostCard agentId="a1" sessionId="s1" turnInFlight />);
    expect((await screen.findByTestId("run-cost-trigger")).textContent).toContain("$0.500");

    getSessionLlmUsageSummary.mockResolvedValue(summary({ totalCostUsd: 2 }));
    rerender(<RunCostCard agentId="a1" sessionId="s1" turnInFlight={false} />);
    await waitFor(() => expect(screen.getByTestId("run-cost-trigger").textContent).toContain("$2.00"));
    expect(getSessionLlmUsageSummary).toHaveBeenCalledTimes(2);
  });
});

describe("TurnStopNotice", () => {
  it("renders nothing for an ordinary turn or an unknown guardrail reason", () => {
    const { container } = render(
      <TurnStopNotice steps={[{ type: "guardrail", id: 1, message: "pii scrubbed", origin: "server" }]} message={null} />
    );
    expect(container.firstChild).toBeNull();
  });

  it("explains a spending-cap stop found in the turn's steps", () => {
    render(<TurnStopNotice steps={[{ type: "guardrail", id: 1, message: "run_budget_exceeded", origin: "server" }]} />);
    expect(screen.getByTestId("turn-stop-run_budget").textContent).toBe("stopRunBudget");
  });

  it("explains the tool-call ceiling when it arrives as the turn's error text", () => {
    render(
      <TurnStopNotice message="Agent a1 (session s1) exceeded the maximum of 15 tool calls for a single turn - stopped to prevent a runaway loop." />
    );
    expect(screen.getByTestId("turn-stop-step_limit").textContent).toBe("stopStepLimit");
  });
});
