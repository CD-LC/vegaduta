// Render tests for the workflow health strip. The translator is backed by the
// real messages/en.json, so a key the strip uses but en.json lacks shows up
// as its dotted path and fails the "no raw key" assertion in every test.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => ({
  useLocale: () => "en",
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
  },
}));

const getWorkflowEvalSummary = vi.fn();
const listWorkflowRuns = vi.fn();
vi.mock("../../lib/api", () => ({
  api: {
    getWorkflowEvalSummary: (...args: unknown[]) => getWorkflowEvalSummary(...args),
    listWorkflowRuns: (...args: unknown[]) => listWorkflowRuns(...args),
  },
}));

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import WorkflowHealthStrip from "./WorkflowHealthStrip";

const ok = (workflow: Record<string, unknown> | null, nodes: Record<string, unknown> = {}) => ({
  ok: true,
  data: { workflow, nodes, generatedAt: "2026-10-05T12:00:00Z" },
});

const node = (over: Record<string, unknown> = {}): Record<string, unknown> => ({
  runs: 10,
  visits: 10,
  successRate: 1,
  errorRate: 0,
  p50DurationMs: 100,
  avgCostUsd: null,
  lastStatus: "OK",
  ...over,
});

const FULL = ok(
  {
    runs: 40,
    days: 30,
    sampled: false,
    successRate: 0.9,
    p50DurationMs: 4200,
    avgCostUsd: 0.0123,
    lastRunAt: "2026-10-05T09:00:00Z",
    lastRunStatus: "FAILED",
  },
  {
    n1: node({ p50DurationMs: 3000, errorRate: 0.25, successRate: 0.75 }),
    n2: node({ p50DurationMs: 800 }),
    n3: node({ p50DurationMs: 50 }),
    n4: node({ p50DurationMs: 20 }),
  }
);

function expectNoRawKeys(container: HTMLElement) {
  expect(container.textContent).not.toMatch(/workflowRunsPage\.|charts\./);
  expect(container.innerHTML).not.toMatch(/NaN|Infinity|undefined/);
}

beforeEach(() => {
  getWorkflowEvalSummary.mockReset();
  listWorkflowRuns.mockReset();
});
afterEach(cleanup);

describe("WorkflowHealthStrip", () => {
  it("shows the server's numbers, a plain summary sentence, and resolved node names", async () => {
    getWorkflowEvalSummary.mockResolvedValue(FULL);
    const { container } = render(<WorkflowHealthStrip workflowId="wf1" nodeLabels={{ n1: "Draft reply", n2: "Fetch order" }} />);

    await screen.findByText("90% of the runs that completed or failed succeeded. Runs in the last 30 days: 40.");
    expect(getWorkflowEvalSummary).toHaveBeenCalledWith("wf1", 30);
    expect(container.querySelector("[data-ring-value]")?.textContent).toBe("90%");
    expect(screen.getByText("Needs attention")).toBeTruthy();
    expect(screen.getByText("4.2s")).toBeTruthy();
    expect(screen.getByText("Average estimated cost")).toBeTruthy();
    expect(screen.getByText("$0.01")).toBeTruthy();
    expect(screen.getByText("Failed")).toBeTruthy();
    expect(screen.getByText("3 hours ago")).toBeTruthy();

    const slowest = within(screen.getByTestId("health-slowest"));
    expect(slowest.getAllByRole("listitem").map((li) => li.textContent)).toEqual(["Draft reply3.0s", "Fetch order800ms", "n350ms"]);
    const failing = within(screen.getByTestId("health-failing"));
    expect(failing.getAllByRole("listitem").map((li) => li.textContent)).toEqual(["Draft reply25% failed"]);

    expect(container.querySelector("[data-health-sampled]")).toBeNull();
    expectNoRawKeys(container);
  });

  it("never reads the unbounded run list", async () => {
    getWorkflowEvalSummary.mockResolvedValue(FULL);
    render(<WorkflowHealthStrip workflowId="wf1" />);
    await screen.findByText("Workflow health");
    await waitFor(() => expect(getWorkflowEvalSummary).toHaveBeenCalledTimes(1));
    expect(listWorkflowRuns).not.toHaveBeenCalled();
  });

  it("says so when the server sampled the window", async () => {
    getWorkflowEvalSummary.mockResolvedValue(ok({ runs: 500, days: 90, sampled: true, successRate: 0.99, p50DurationMs: 10, avgCostUsd: null }));
    const { container } = render(<WorkflowHealthStrip workflowId="wf1" />);
    const note = await waitFor(() => {
      const el = container.querySelector("[data-health-sampled]");
      expect(el).not.toBeNull();
      return el as HTMLElement;
    });
    expect(note.textContent).toBe("Based on the latest 500 runs in this period. Older runs in the period are not counted.");
    expectNoRawKeys(container);
  });

  it("renders a null rate, duration and cost as '-' (never 0% or 100%)", async () => {
    getWorkflowEvalSummary.mockResolvedValue(
      ok({ runs: 2, days: 30, sampled: false, successRate: null, p50DurationMs: null, avgCostUsd: null, lastRunAt: null, lastRunStatus: "RUNNING" })
    );
    const { container } = render(<WorkflowHealthStrip workflowId="wf1" />);
    await screen.findByText("Runs in the last 30 days: 2. None has completed or failed yet, so there is no success rate.");
    expect(container.querySelector("[data-ring-state]")?.getAttribute("data-ring-state")).toBe("none");
    expect(container.querySelector("[data-ring-value]")?.textContent).toBe("-");
    expect(container.textContent).not.toMatch(/\b(0|100)%/);
    expect(screen.getByText("No completed or failed runs yet")).toBeTruthy();
    expect(screen.getByText("No step timings were recorded in this period.")).toBeTruthy();
    expect(screen.getByText("No step failed in this period.")).toBeTruthy();
    expect(screen.getAllByText("-").length).toBeGreaterThanOrEqual(3);
    expectNoRawKeys(container);
  });

  it("shows an empty state with a link to the canvas when there are no runs", async () => {
    getWorkflowEvalSummary.mockResolvedValue(ok({ runs: 0, days: 30, sampled: false, successRate: null, p50DurationMs: null, avgCostUsd: null }));
    const { container } = render(<WorkflowHealthStrip workflowId="wf1" />);
    await screen.findByText("No runs in the last 30 days");
    const link = screen.getByRole("link", { name: "Open the canvas to run it" });
    expect(link.getAttribute("href")).toBe("/workflows/wf1");
    expect(container.querySelector("[data-ring-value]")).toBeNull();
    // The period switch stays, so a longer window is one click away.
    expect(container.querySelectorAll("[data-health-period]")).toHaveLength(3);
    expectNoRawKeys(container);
  });

  it("re-fetches with the chosen period", async () => {
    getWorkflowEvalSummary.mockResolvedValue(FULL);
    const { container } = render(<WorkflowHealthStrip workflowId="wf1" />);
    await screen.findByText("Success rate");
    const seven = container.querySelector('[data-health-period="7"]') as HTMLElement;
    expect(seven.getAttribute("aria-pressed")).toBe("false");
    fireEvent.click(seven);
    await waitFor(() => expect(getWorkflowEvalSummary).toHaveBeenLastCalledWith("wf1", 7));
    await waitFor(() => expect((container.querySelector('[data-health-period="7"]') as HTMLElement).getAttribute("aria-pressed")).toBe("true"));
  });

  it("offers the same data as a table, one row for the workflow plus one per visited node", async () => {
    getWorkflowEvalSummary.mockResolvedValue(FULL);
    const { container } = render(<WorkflowHealthStrip workflowId="wf1" nodeLabels={{ n1: "Draft reply" }} />);
    await screen.findByText("Success rate");
    fireEvent.click(container.querySelector("[data-chart-view-toggle]") as HTMLElement);
    const rows = within(screen.getByRole("table")).getAllByRole("row");
    expect(rows).toHaveLength(1 + 1 + 4);
    expect(rows[1].textContent).toContain("Whole workflow");
    expect(rows[2].textContent).toContain("Draft reply");
    expect(rows[2].textContent).toContain("25%");
    expectNoRawKeys(container);
  });

  it("shows an error with a retry on a failed call", async () => {
    getWorkflowEvalSummary.mockResolvedValueOnce({ ok: false, unsupported: false }).mockResolvedValueOnce(FULL);
    render(<WorkflowHealthStrip workflowId="wf1" />);
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("Could not load workflow health.");
    fireEvent.click(within(alert).getByRole("button", { name: "Try again" }));
    await screen.findByText("Success rate");
    expect(getWorkflowEvalSummary).toHaveBeenCalledTimes(2);
  });

  it("renders nothing when core does not have the endpoint", async () => {
    getWorkflowEvalSummary.mockResolvedValue({ ok: false, unsupported: true });
    const { container } = render(<WorkflowHealthStrip workflowId="wf1" />);
    await waitFor(() => expect(container.innerHTML).toBe(""));
  });
});
