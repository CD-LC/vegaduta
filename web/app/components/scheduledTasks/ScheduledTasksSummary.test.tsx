// The /scheduled header, the per-task run strip and the explainer, rendered with the real English strings so the
// sentences a user reads are what is asserted.

import { beforeEach, describe, expect, it, vi } from "vitest";

// The run history reads through runsPaged (it needs the paging headers); adapt it onto the mocked api.listScheduledTaskRuns.
vi.mock("./runsPaged", async () => {
  const { api } = await import("../../lib/api");
  return {
    listTaskRunsPage: async (...args: unknown[]) => {
      const list = await (api as any).listScheduledTaskRuns(...args);
      return { rows: Array.isArray(list) ? list : [], total: undefined as number | undefined, hasMore: false };
    },
  };
});
import { fireEvent, render, screen, within } from "@testing-library/react";
import en from "../../../messages/en.json";

const listScheduledTaskRuns = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get listScheduledTaskRuns() { return listScheduledTaskRuns; },
  },
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key] ?? `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
  },
}));

import ScheduledTaskExplainer from "./ScheduledTaskExplainer";
import ScheduledTaskRuns from "./ScheduledTaskRuns";
import ScheduledTasksSummary from "./ScheduledTasksSummary";
import type { ScheduledTask, ScheduledTaskRun } from "../../lib/scheduledTasks";

function task(over: Partial<ScheduledTask>): ScheduledTask {
  return {
    id: "t1",
    name: "Morning digest",
    timezone: "UTC",
    enabled: true,
    consecutiveFailures: 0,
    upcomingRuns: [],
    runCount: 0,
    ...over,
  } as ScheduledTask;
}

function run(over: Partial<ScheduledTaskRun>): ScheduledTaskRun {
  return {
    id: "r1",
    taskId: "t1",
    trigger: "SCHEDULE",
    status: "SUCCEEDED",
    attempt: 1,
    startedAt: "2026-10-05T08:00:00Z",
    finishedAt: null,
    jobId: null,
    costUsd: null,
    deliveryStatus: "IN_APP",
    error: null,
    ...over,
  };
}

const soon = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString();

beforeEach(() => {
  vi.clearAllMocks();
});

describe("ScheduledTasksSummary", () => {
  it("renders nothing when there are no tasks (the page's own empty state stands alone)", () => {
    const { container } = render(<ScheduledTasksSummary tasks={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it("says the split in one sentence and draws it as one bar with a text legend", () => {
    const { container } = render(
      <ScheduledTasksSummary tasks={[task({ id: "a" }), task({ id: "b" }), task({ id: "c", enabled: false })]} />
    );
    const sentence = "3 tasks: 2 active, 1 paused, none failing.";
    expect(screen.getByText(sentence)).toBeTruthy();
    expect(container.querySelector("svg")?.getAttribute("aria-label")).toBe(sentence);
    // Zero-count groups draw no segment but stay in the legend.
    expect(container.querySelectorAll("[data-segment]")).toHaveLength(2);
    expect(screen.getByText("Failing")).toBeTruthy();
  });

  it("uses the singular for one task and counts a failing task apart from the healthy ones", () => {
    render(<ScheduledTasksSummary tasks={[task({ consecutiveFailures: 2 })]} />);
    expect(screen.getByText("1 task: none active, none paused, 1 failing.")).toBeTruthy();
  });

  it("offers the same figures as a table", () => {
    render(<ScheduledTasksSummary tasks={[task({ id: "a" }), task({ id: "b", enabled: false })]} />);
    fireEvent.click(screen.getByRole("button", { name: "View as table" }));
    const table = screen.getByRole("table");
    expect(within(table).getAllByRole("row")).toHaveLength(4);
    expect(within(table).getByText("Switched on and not currently failing.")).toBeTruthy();
  });

  it("lists what is due in the next 24 hours, soonest first, in the task's own time zone", () => {
    render(
      <ScheduledTasksSummary
        tasks={[
          task({ id: "a", name: "Later task", upcomingRuns: [soon(600), soon(60 * 30), soon(60 * 54)] }),
          task({ id: "b", name: "Sooner task", timezone: "Asia/Kolkata", upcomingRuns: [soon(30)] }),
        ]}
      />
    );
    expect(screen.getByText("2 runs are due in the next 24 hours. Each time is in its task's own time zone.")).toBeTruthy();
    const items = within(screen.getByTestId("scheduled-upcoming-list")).getAllByRole("listitem");
    expect(items.map((li) => li.querySelector("span")?.textContent)).toEqual(["Sooner task", "Later task"]);
    expect(items[0].querySelector("time")?.textContent).toMatch(/GMT\+5:30|IST/);
    expect(screen.queryByTestId("scheduled-upcoming-partial")).toBeNull();
  });

  it("admits the list may be incomplete for a task that runs more often than the server reports", () => {
    render(<ScheduledTasksSummary tasks={[task({ upcomingRuns: [soon(15), soon(30), soon(45)] })]} />);
    expect(screen.getByTestId("scheduled-upcoming-partial").textContent).toContain("next 3 runs");
  });

  it("has a calm empty line when nothing is due, and says why when every task is paused", () => {
    const { unmount } = render(<ScheduledTasksSummary tasks={[task({ upcomingRuns: [soon(60 * 48)] })]} />);
    expect(screen.getByTestId("scheduled-upcoming-empty").textContent).toBe("Nothing is due in the next 24 hours.");
    unmount();
    render(<ScheduledTasksSummary tasks={[task({ enabled: false })]} />);
    expect(screen.getByTestId("scheduled-upcoming-empty").textContent).toBe("Nothing is due: every task is paused.");
  });
});

describe("ScheduledTaskRuns sample", () => {
  it("titles the strip with the number of runs returned, oldest first, and labels cost and lifetime honestly", async () => {
    listScheduledTaskRuns.mockResolvedValue([
      run({ id: "new", status: "FAILED", startedAt: "2026-10-05T08:00:00Z", costUsd: 0.5 }),
      run({ id: "mid", status: "SUCCEEDED", startedAt: "2026-10-04T08:00:00Z", costUsd: 0.25 }),
      run({ id: "old", status: "SKIPPED", startedAt: "2026-10-03T08:00:00Z" }),
    ]);
    render(<ScheduledTaskRuns taskId="t1" timezone="UTC" refreshKey={0} runCount={41} />);

    const strip = await screen.findByRole("list", { name: "Last 3 runs, oldest first" });
    const marks = within(strip).getAllByRole("listitem");
    expect(marks.map((li) => li.getAttribute("data-run-status"))).toEqual(["warning", "success", "error"]);
    expect(within(strip).getAllByRole("img")[2].getAttribute("aria-label")).toMatch(/: Failed$/);

    const sample = screen.getByTestId("scheduled-task-run-sample");
    expect(sample.textContent).toContain("Runs shown: 3. Succeeded: 1. Failed: 1. Other (skipped, cancelled or still running): 1.");
    expect(sample.textContent).toContain("Estimated cost of the runs shown: $0.75 (2 of 3 runs reported a cost)");
    expect(sample.textContent).toContain("Runs since this task was created: 41 (automatic retries are not counted)");
    // The run table is still there: it is the strip's table fallback.
    expect(screen.getByTestId("scheduled-task-runs")).toBeTruthy();
  });

  it("shows a dash, not $0.00, when no run reported a cost", async () => {
    listScheduledTaskRuns.mockResolvedValue([run({})]);
    render(<ScheduledTaskRuns taskId="t1" timezone="UTC" refreshKey={0} />);
    await screen.findByRole("list", { name: "Last run" });
    const sample = screen.getByTestId("scheduled-task-run-sample");
    expect(sample.textContent).toContain("Estimated cost of the runs shown: -");
    expect(sample.textContent).not.toContain("Runs since this task was created");
  });

  it("draws no strip for a task that has never run", async () => {
    listScheduledTaskRuns.mockResolvedValue([]);
    render(<ScheduledTaskRuns taskId="t1" timezone="UTC" refreshKey={0} runCount={0} />);
    expect(await screen.findByText(en.scheduledTasks.historyEmpty)).toBeTruthy();
    expect(screen.queryByTestId("scheduled-task-run-sample")).toBeNull();
  });
});

describe("ScheduledTaskExplainer", () => {
  it("is a closed 'How this works' disclosure with the four steps in order", () => {
    const { container } = render(<ScheduledTaskExplainer deliveryChannels={["in_app", "email", "slack"]} />);
    const details = container.querySelector("details")!;
    expect(details.hasAttribute("open")).toBe(false);
    expect(details.querySelector("summary")?.textContent).toBe("How this works");
    expect([...container.querySelectorAll("ol > li")].map((li) => li.querySelector("p")?.textContent)).toEqual([
      "The schedule fires",
      "The agent runs your prompt unattended",
      "The result is delivered",
      "Repeated failures pause the task",
    ]);
  });

  it("names only the delivery channels this deployment allows", () => {
    const { container, unmount } = render(<ScheduledTaskExplainer deliveryChannels={["in_app", "email", "slack"]} />);
    const labels = [...container.querySelectorAll("[data-flow-outcome] .font-semibold")].map((n) => n.textContent);
    expect(labels).toEqual([
      en.scheduledTasks.delivery_in_app,
      `${en.scheduledTasks.delivery_email}, ${en.scheduledTasks.delivery_slack}`,
    ]);
    unmount();
    const only = render(<ScheduledTaskExplainer deliveryChannels={["in_app"]} />);
    expect(only.container.querySelectorAll("[data-flow-outcome]")).toHaveLength(1);
  });
});
