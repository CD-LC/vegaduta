// Prod re-verification 2026-10-05, R5: after Run now the task badge turned "Succeeded" by itself, but the open
// run-history row kept reading "Running" until a reload (the history was read once). The history now follows a run
// to its end: it polls while a listed run is in flight and re-reads when the task's own status settles.

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

const listScheduledTaskRuns = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get listScheduledTaskRuns() {
      return listScheduledTaskRuns;
    },
  },
}));
vi.mock("../../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("../charts", () => ({ RunStrip: (): null => null }));

import ScheduledTaskRuns from "./ScheduledTaskRuns";
import { RUNNING_POLL_MAX_TICKS, RUNNING_POLL_MS } from "../../lib/scheduledTasks";
import type { ScheduledTaskRun } from "../../lib/scheduledTasks";

function run(over: Partial<ScheduledTaskRun> = {}): ScheduledTaskRun {
  return {
    id: "r1",
    taskId: "t1",
    trigger: "MANUAL",
    status: "ENQUEUED",
    attempt: 1,
    startedAt: "2026-10-05T10:00:00Z",
    finishedAt: null,
    jobId: "j1",
    costUsd: null,
    deliveryStatus: null,
    error: null,
    ...over,
  };
}

async function mount(props: { refreshKey?: number; taskStatus?: string | null } = {}) {
  const view = render(<ScheduledTaskRuns taskId="t1" timezone="UTC" refreshKey={props.refreshKey ?? 0} taskStatus={props.taskStatus} />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
  return view;
}
const tick = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });

describe("ScheduledTaskRuns - the open history follows a run to its end", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it("re-reads every 4 s while a run is in flight, shows the finished run, then stops", async () => {
    listScheduledTaskRuns
      .mockResolvedValueOnce([run()]) // mount
      .mockResolvedValueOnce([run()]) // first poll: still running
      .mockResolvedValue([run({ status: "SUCCEEDED", finishedAt: "2026-10-05T10:01:30Z", costUsd: 0.0034 })]);
    await mount();
    expect(screen.getByText("runStatus_ENQUEUED")).toBeTruthy();
    expect(listScheduledTaskRuns).toHaveBeenCalledTimes(1);

    await tick(RUNNING_POLL_MS);
    expect(listScheduledTaskRuns).toHaveBeenCalledTimes(2);
    expect(screen.getByText("runStatus_ENQUEUED")).toBeTruthy();

    await tick(RUNNING_POLL_MS);
    expect(listScheduledTaskRuns).toHaveBeenCalledTimes(3);
    expect(screen.getByText("runStatus_SUCCEEDED")).toBeTruthy();
    expect(screen.queryByText("runStatus_ENQUEUED")).toBeNull();

    await tick(RUNNING_POLL_MS * 10); // nothing in flight any more: no further requests
    expect(listScheduledTaskRuns).toHaveBeenCalledTimes(3);
  });

  it("does not poll a history with nothing in flight", async () => {
    listScheduledTaskRuns.mockResolvedValue([run({ status: "SUCCEEDED" })]);
    await mount();
    await tick(RUNNING_POLL_MS * 10);
    expect(listScheduledTaskRuns).toHaveBeenCalledTimes(1);
  });

  it("re-reads once when the task's own status settles, even if it was not polling", async () => {
    listScheduledTaskRuns.mockResolvedValueOnce([run({ status: "SUCCEEDED" })]).mockResolvedValue([run({ status: "SUCCEEDED", costUsd: 0.0034 })]);
    const view = await mount({ taskStatus: "ENQUEUED" });
    expect(listScheduledTaskRuns).toHaveBeenCalledTimes(1);
    view.rerender(<ScheduledTaskRuns taskId="t1" timezone="UTC" refreshKey={0} taskStatus="SUCCEEDED" />);
    await tick(0);
    expect(listScheduledTaskRuns).toHaveBeenCalledTimes(2);
  });

  it("stops after a failed read instead of hammering a down server", async () => {
    listScheduledTaskRuns.mockResolvedValueOnce([run()]).mockRejectedValue(new Error("down"));
    await mount();
    await tick(RUNNING_POLL_MS * 6);
    expect(listScheduledTaskRuns).toHaveBeenCalledTimes(2);
  });

  it("gives up on a run that never finishes after 5 minutes", async () => {
    listScheduledTaskRuns.mockResolvedValue([run()]);
    await mount();
    expect(RUNNING_POLL_MS * RUNNING_POLL_MAX_TICKS).toBe(5 * 60 * 1000);
    await tick(RUNNING_POLL_MS * (RUNNING_POLL_MAX_TICKS + 20));
    const before = listScheduledTaskRuns.mock.calls.length;
    expect(before).toBeLessThanOrEqual(RUNNING_POLL_MAX_TICKS + 1);
    await tick(RUNNING_POLL_MS * 5);
    expect(listScheduledTaskRuns.mock.calls.length).toBe(before);
  });
});
