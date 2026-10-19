// AgentGlanceCard: honest numbers from the reliability summary the workbench already holds plus the job counts
// endpoint. The translator is backed by the real en.json, so a missing key shows up as its dotted path and
// fails the "no raw key" check.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
  },
}));

const mockApi = vi.hoisted(() => ({ getAgentJobCounts: vi.fn() }));
vi.mock("../../lib/api", () => ({ api: mockApi }));

import AgentGlanceCard, { normalizeJobCounts, windowPhrase } from "./AgentGlanceCard";
import type { AgentReliabilitySummary } from "./AgentWorkbench/types";

function reliability(over: Partial<AgentReliabilitySummary> = {}): AgentReliabilitySummary {
  return {
    agentId: "a1",
    totalCalls: 50,
    failedCalls: 5,
    successRatePercent: 90,
    avgResponseTimeMs: 1840,
    firstCallAt: "2026-09-01T10:00:00Z",
    lastCallAt: "2026-10-03T10:00:00Z",
    toolCallCount: 20,
    toolCallFailureCount: 2,
    fallbackTriggeredCount: 3,
    fallbackFailedCount: 0,
    lastFallbackAt: null,
    recentFailures: [],
    ...over,
  };
}

const EMPTY = reliability({
  totalCalls: 0,
  failedCalls: 0,
  successRatePercent: null,
  avgResponseTimeMs: null,
  firstCallAt: null,
  lastCallAt: null,
  toolCallCount: 0,
  toolCallFailureCount: 0,
  fallbackTriggeredCount: 0,
});

function jobs(over: Record<string, unknown> = {}) {
  return {
    queued: 1,
    running: 2,
    awaitingApproval: 0,
    live: 3,
    succeededInWindow: 7,
    failedInWindow: 1,
    cancelledInWindow: 0,
    window: "PT168H",
    windowStart: "2026-09-28T12:00:00Z",
    asOf: "2026-10-05T12:00:00Z",
    agentId: "a1",
    tenantWide: false,
    ...over,
  };
}

const NO_JOBS = jobs({ queued: 0, running: 0, awaitingApproval: 0, live: 0, succeededInWindow: 0, failedInWindow: 0, cancelledInWindow: 0 });

function noRawKeysOrNaN(container: HTMLElement) {
  expect(container.innerHTML).not.toMatch(/agentGlance\.|charts\.|NaN|Infinity|\{\w+\}/);
}

describe("AgentGlanceCard", () => {
  beforeEach(() => {
    mockApi.getAgentJobCounts.mockReset();
  });

  it("asks for this agent's job counts over a 7 day window and reuses the reliability it is given", async () => {
    mockApi.getAgentJobCounts.mockResolvedValue(jobs());
    const { container } = render(<AgentGlanceCard agentId="a1" reliability={reliability()} />);
    await screen.findByTestId("agent-glance-jobs");
    expect(mockApi.getAgentJobCounts).toHaveBeenCalledTimes(1);
    expect(mockApi.getAgentJobCounts).toHaveBeenCalledWith("a1", "P7D");
    expect(Object.keys(mockApi)).toEqual(["getAgentJobCounts"]);
    noRawKeysOrNaN(container);
  });

  it("shows the success ring, a plain summary sentence with the date range, latency, fallbacks and the tool bar", async () => {
    mockApi.getAgentJobCounts.mockResolvedValue(jobs());
    const { container } = render(<AgentGlanceCard agentId="a1" reliability={reliability()} />);
    await screen.findByTestId("agent-glance-jobs");

    const summary = container.querySelector("[data-chart-summary]")!;
    expect(summary.textContent).toContain("45 of 50 model calls succeeded (90%)");
    expect(summary.textContent).toContain("2026");
    expect(container.querySelector("[data-ring-value]")!.textContent).toBe("90%");
    expect(screen.getByTestId("agent-glance-latency").textContent).toContain("1.8 s");
    expect(screen.getByTestId("agent-glance-fallbacks").textContent).toContain("3");
    expect(screen.getByTestId("agent-glance-tools").textContent).toContain("18 of 20 tool calls succeeded (90%)");
    // every chart has an accessible name that is a sentence
    for (const img of screen.getAllByRole("img")) expect(img.getAttribute("aria-label")).toMatch(/\w+ \w+/);
    noRawKeysOrNaN(container);
  });

  it("captions job counts with the window the server echoed, and keeps 'right now' counts out of the bar", async () => {
    mockApi.getAgentJobCounts.mockResolvedValue(jobs({ windowStart: "2026-10-04T00:00:00Z", asOf: "2026-10-05T12:00:00Z" }));
    render(<AgentGlanceCard agentId="a1" reliability={reliability()} />);
    const block = await screen.findByTestId("agent-glance-jobs");
    expect(block.textContent).toContain("In the last 36 hours: 7 succeeded, 1 failed, 0 cancelled.");
    expect(screen.getByTestId("agent-glance-jobs-live").textContent).toBe("Right now: 1 queued, 2 running, 0 waiting for approval.");
    const legend = within(block).getAllByRole("listitem").map((li) => li.textContent);
    expect(legend).toEqual(["Succeeded7", "Failed1", "Cancelled0"]);
  });

  it("offers the same data as a table", async () => {
    mockApi.getAgentJobCounts.mockResolvedValue(jobs());
    const { container } = render(<AgentGlanceCard agentId="a1" reliability={reliability()} />);
    await screen.findByTestId("agent-glance-jobs");
    fireEvent.click(screen.getByRole("button", { name: "View as table" }));
    const table = screen.getByRole("table");
    expect(within(table).getByText("45 of 50")).toBeInTheDocument();
    expect(within(table).getByText("18 of 20")).toBeInTheDocument();
    expect(within(table).getByText("Jobs succeeded in the last 7 days")).toBeInTheDocument();
    expect(within(table).getAllByRole("row")).toHaveLength(13);
    noRawKeysOrNaN(container);
  });

  it("a brand-new agent gets the friendly empty state, not zeros", async () => {
    mockApi.getAgentJobCounts.mockResolvedValue(NO_JOBS);
    const { container } = render(<AgentGlanceCard agentId="a1" reliability={EMPTY} />);
    expect(await screen.findByText("Send your first message to see how this agent is doing.")).toBeInTheDocument();
    expect(container.querySelector("[data-chart-state='empty']")).not.toBeNull();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(container.textContent).not.toMatch(/0%|100%/);
    noRawKeysOrNaN(container);
  });

  it("draws a null rate as '-', never 0% or 100%, when only jobs have run", async () => {
    mockApi.getAgentJobCounts.mockResolvedValue(jobs());
    const { container } = render(<AgentGlanceCard agentId="a1" reliability={EMPTY} />);
    await screen.findByTestId("agent-glance-jobs");
    expect(container.querySelector("[data-ring-state]")!.getAttribute("data-ring-state")).toBe("none");
    expect(container.querySelector("[data-ring-value]")!.textContent).toBe("-");
    expect(screen.getByTestId("agent-glance-latency").textContent).toContain("-");
    expect(screen.getByTestId("agent-glance-tools").textContent).toContain("has not called a tool yet");
    expect(container.textContent).not.toMatch(/\b0%|100%/);
    noRawKeysOrNaN(container);
  });

  it("leaves the jobs block out when the counts call fails, and still shows the rest", async () => {
    mockApi.getAgentJobCounts.mockRejectedValue(new Error("404"));
    const { container } = render(<AgentGlanceCard agentId="a1" reliability={reliability()} />);
    await waitFor(() => expect(container.querySelector("[data-chart-summary]")).not.toBeNull());
    expect(screen.queryByTestId("agent-glance-jobs")).not.toBeInTheDocument();
    expect(screen.getByTestId("agent-glance-tools")).toBeInTheDocument();
  });

  it("lists at most three recent failure reasons as plain text", async () => {
    mockApi.getAgentJobCounts.mockResolvedValue(NO_JOBS);
    const failure = (errorMessage: string | null, exceptionType: string | null = null): AgentReliabilitySummary["recentFailures"][number] => ({
      occurredAt: null,
      errorMessage,
      exceptionType,
      provider: null,
      model: null,
    });
    render(
      <AgentGlanceCard
        agentId="a1"
        reliability={reliability({
          recentFailures: [failure("Rate limit reached"), failure(null, "TimeoutException"), failure("x".repeat(500)), failure("fourth")],
        })}
      />
    );
    const block = await screen.findByTestId("agent-glance-failures");
    const items = within(block).getAllByRole("listitem");
    expect(items).toHaveLength(3);
    expect(items[0].textContent).toBe("Rate limit reached");
    expect(items[1].textContent).toBe("TimeoutException");
    expect(items[2].textContent!.length).toBeLessThanOrEqual(200);
    expect(block.textContent).not.toContain("fourth");
    expect(block.textContent).not.toContain("%");
  });

  it("shows loading, then an error state when the reliability fetch failed", async () => {
    mockApi.getAgentJobCounts.mockResolvedValue(jobs());
    const { container, rerender } = render(<AgentGlanceCard agentId="a1" reliability="loading" />);
    expect(container.querySelector("[data-chart-state='loading']")).not.toBeNull();
    rerender(<AgentGlanceCard agentId="a1" reliability="error" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load this agent's numbers");
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });
});

describe("normalizeJobCounts / windowPhrase", () => {
  const t = (key: string, values?: Record<string, string | number>) => `${key}${values ? `:${Object.values(values).join(",")}` : ""}`;

  it("rejects a payload that is not a counts response", () => {
    expect(normalizeJobCounts(null)).toBeNull();
    expect(normalizeJobCounts({})).toBeNull();
    expect(normalizeJobCounts({ succeededInWindow: "7", failedInWindow: 1 })).toBeNull();
  });

  it("derives whole hours from the echoed window and never invents one", () => {
    expect(normalizeJobCounts(jobs())!.windowHours).toBe(168);
    expect(normalizeJobCounts(jobs({ windowStart: undefined }))!.windowHours).toBeNull();
    expect(normalizeJobCounts(jobs({ queued: -4, running: Number.NaN }))).toMatchObject({ queued: 0, running: 0 });
  });

  it("phrases the window in days, hours, or a generic phrase", () => {
    expect(windowPhrase(168, t)).toBe("windowDays:7");
    expect(windowPhrase(24, t)).toBe("windowOneDay");
    expect(windowPhrase(36, t)).toBe("windowHours:36");
    expect(windowPhrase(1, t)).toBe("windowOneHour");
    expect(windowPhrase(null, t)).toBe("windowUnknown");
  });
});
