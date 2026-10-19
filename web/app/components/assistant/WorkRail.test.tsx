import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import WorkRail from "./WorkRail";
import type { AgentJobSummaryResponse } from "../../lib/api/types";
import type { WorkCounts } from "./useWorkCounts";

const job = (id: string, status: string, over: Partial<AgentJobSummaryResponse> = {}): AgentJobSummaryResponse => ({
  id,
  agentId: "a1",
  agentName: "Assistant",
  status,
  goalPreview: `goal ${id}`,
  progressNote: null,
  createdAt: new Date().toISOString(),
  startedAt: new Date().toISOString(),
  finishedAt: null,
  errorCode: null,
  parkedAt: null,
  approvalDeadlineAt: null,
  ...over,
});

const counts = (jobs: AgentJobSummaryResponse[]): WorkCounts => ({
  pendingApprovals: 0,
  routines: [],
  runningJobs: jobs.filter((j) => j.status === "RUNNING").length,
  parkedJobs: 0,
  reachable: true,
  jobs,
});

describe("WorkRail background tasks", () => {
  it("lists every task with its state and the right actions", () => {
    const onStop = vi.fn();
    const onShow = vi.fn();
    const jobs = [
      job("run", "RUNNING", { progressNote: "step 2 of 5" }),
      job("done", "SUCCEEDED", { finishedAt: new Date().toISOString() }),
      job("wait", "AWAITING_APPROVAL"),
    ];
    render(<WorkRail counts={counts(jobs)} onStopJob={onStop} onShowJob={onShow} stoppingId={null} />);

    const running = screen.getByTestId("task-run");
    expect(running.textContent).toContain("Running");
    expect(running.textContent).toContain("step 2 of 5");
    fireEvent.click(screen.getByRole("button", { name: /Stop/ }));
    expect(onStop).toHaveBeenCalledWith(jobs[0]);

    const done = screen.getByTestId("task-done");
    expect(done.textContent).toContain("Done");
    fireEvent.click(screen.getByRole("button", { name: /Show in chat/ }));
    expect(onShow).toHaveBeenCalledWith(jobs[1]);

    expect(screen.getByTestId("task-wait").textContent).toContain("Needs your approval");
    expect(screen.getAllByRole("link", { name: /Details/ })[0].getAttribute("href")).toBe("/jobs/a1/run");
  });

  it("says so when there are no tasks", () => {
    render(<WorkRail counts={counts([])} onStopJob={vi.fn()} onShowJob={vi.fn()} stoppingId={null} />);
    expect(screen.getByText(en.assistant.nothingRunning)).toBeTruthy();
  });
});
