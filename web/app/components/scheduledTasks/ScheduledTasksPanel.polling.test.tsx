// Prod signed-in pass 2026-10-05, bug 14 (web half): after a run finished the task badge still read "Running"
// until the next click. The panel re-reads the list while any task's last run is in flight, and stops when none is.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

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
import { act, cleanup, render, screen } from "@testing-library/react";

const getScheduledTasksConfig = vi.fn();
const listScheduledTasks = vi.fn();
const listAgents = vi.fn();
const listScheduledTaskRuns = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get getScheduledTasksConfig() { return getScheduledTasksConfig; },
    get listScheduledTasks() { return listScheduledTasks; },
    get listAgents() { return listAgents; },
    get listScheduledTaskRuns() { return listScheduledTaskRuns; },
  },
}));
vi.mock("../../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("../../lib/toastBus", () => ({ toast: { success: vi.fn(), info: vi.fn(), error: vi.fn() } }));

import ScheduledTasksPanel, { RUNNING_POLL_MAX_TICKS, RUNNING_POLL_MS, isRunInFlight } from "./ScheduledTasksPanel";
import type { ScheduledTask } from "../../lib/scheduledTasks";

function task(over: Partial<ScheduledTask> = {}): ScheduledTask {
  return {
    id: "t1", agentId: "a1", name: "Morning digest", prompt: "p", frequency: "weekdays", time: "08:00", day: null,
    cron: "0 0 8 * * MON-FRI", timezone: "UTC", scheduleDescription: "Every weekday", delivery: "in_app",
    deliveryTo: null, enabled: true, disabledReason: null, nextRunAt: "2026-10-06T08:00:00Z", upcomingRuns: [],
    lastRunAt: null, lastStatus: null, lastJobId: null, consecutiveFailures: 0, runCount: 1, maxRuns: null,
    expiresAt: null, ownerSubject: "u1", createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z",
    ...over,
  };
}

async function mount() {
  render(<ScheduledTasksPanel />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}
const tick = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });

describe("ScheduledTasksPanel - status badge follows a run to its end", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    getScheduledTasksConfig.mockResolvedValue({ enabled: true, maxTasks: 10, minIntervalMinutes: 15, maxPromptChars: 2000, deliveryChannels: ["in_app"] });
    listAgents.mockResolvedValue([]);
    listScheduledTaskRuns.mockResolvedValue([]);
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it("knows which last statuses are still moving", () => {
    expect(isRunInFlight("ENQUEUED")).toBe(true);
    expect(isRunInFlight("RUNNING")).toBe(true);
    for (const s of ["SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED", null, undefined]) expect(isRunInFlight(s)).toBe(false);
  });

  it("polls while a run is in flight, updates the badge when it finishes, then stops", async () => {
    listScheduledTasks
      .mockResolvedValueOnce([task({ lastStatus: "ENQUEUED" })]) // initial load
      .mockResolvedValueOnce([task({ lastStatus: "ENQUEUED" })]) // first poll: still running
      .mockResolvedValue([task({ lastStatus: "SUCCEEDED", runCount: 2 })]);
    await mount();
    expect(screen.getByText("runStatus_ENQUEUED")).toBeTruthy();
    expect(listScheduledTasks).toHaveBeenCalledTimes(1);

    await tick(RUNNING_POLL_MS);
    expect(listScheduledTasks).toHaveBeenCalledTimes(2);
    expect(screen.getByText("runStatus_ENQUEUED")).toBeTruthy();

    await tick(RUNNING_POLL_MS);
    expect(listScheduledTasks).toHaveBeenCalledTimes(3);
    expect(screen.getByText("runStatus_SUCCEEDED")).toBeTruthy();
    expect(screen.queryByText("runStatus_ENQUEUED")).toBeNull();

    await tick(RUNNING_POLL_MS * 10); // nothing running any more: no further requests
    expect(listScheduledTasks).toHaveBeenCalledTimes(3);
  });

  it("never polls when nothing is running", async () => {
    listScheduledTasks.mockResolvedValue([task({ lastStatus: "SUCCEEDED" })]);
    await mount();
    await tick(RUNNING_POLL_MS * 10);
    expect(listScheduledTasks).toHaveBeenCalledTimes(1);
  });

  it("stops after a failed poll instead of hammering a down server", async () => {
    listScheduledTasks.mockResolvedValueOnce([task({ lastStatus: "RUNNING" })]).mockRejectedValue(new Error("down"));
    await mount();
    await tick(RUNNING_POLL_MS * 6);
    expect(listScheduledTasks).toHaveBeenCalledTimes(2);
  });

  it("gives up on a run that never finishes after the tick cap", async () => {
    listScheduledTasks.mockResolvedValue([task({ lastStatus: "RUNNING" })]);
    await mount();
    await tick(RUNNING_POLL_MS * (RUNNING_POLL_MAX_TICKS + 20));
    expect(listScheduledTasks.mock.calls.length).toBeLessThanOrEqual(RUNNING_POLL_MAX_TICKS + 1);
    const before = listScheduledTasks.mock.calls.length;
    await tick(RUNNING_POLL_MS * 5);
    expect(listScheduledTasks.mock.calls.length).toBe(before);
  });
});
