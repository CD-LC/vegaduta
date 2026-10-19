// Starter templates, duplicate, filter/search and the task limit on /scheduled. t() returns the key.
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
import { selectedLabel } from "../ui/test-select";
import en from "../../../messages/en.json";
import { SCHEDULED_TASK_TEMPLATES } from "../../lib/scheduledTaskTemplates";
import { validateSchedule, type ScheduledTask } from "../../lib/scheduledTasks";
import { filterTasks, taskInGroup } from "./scheduledTaskStats";

const getScheduledTasksConfig = vi.fn();
const listScheduledTasks = vi.fn();
const listAgents = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get getScheduledTasksConfig() { return getScheduledTasksConfig; },
    get listScheduledTasks() { return listScheduledTasks; },
    get listAgents() { return listAgents; },
    listScheduledTaskRuns: vi.fn().mockResolvedValue([]),
    previewScheduledTaskSchedule: vi.fn(),
  },
}));
vi.mock("../../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("../../lib/toastBus", () => ({ toast: { success: vi.fn(), info: vi.fn(), error: vi.fn() } }));

import ScheduledTasksPanel from "./ScheduledTasksPanel";

const cfg = { enabled: true, maxTasks: 10, minIntervalMinutes: 15, maxPromptChars: 2000, deliveryChannels: ["in_app", "email"] };

function task(over: Partial<ScheduledTask> = {}): ScheduledTask {
  return {
    id: "t1", agentId: "a1", name: "Morning digest", prompt: "Summarise my unread email", frequency: "weekdays",
    time: "08:00", day: null, cron: "0 0 8 * * MON-FRI", timezone: "UTC", scheduleDescription: "", delivery: "in_app",
    deliveryTo: null, enabled: true, disabledReason: null, nextRunAt: null, upcomingRuns: [], lastRunAt: null,
    lastStatus: null, lastJobId: null, consecutiveFailures: 0, runCount: 0, maxRuns: null, expiresAt: null,
    ownerSubject: "u", createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z", ...over,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  getScheduledTasksConfig.mockResolvedValue(cfg);
  listAgents.mockResolvedValue([{ id: "a1", name: "Assistant" }]);
});

describe("starter templates", () => {
  const ns = (en as any).scheduledTasks as Record<string, string>;

  it("every template has copy, a valid schedule and a prompt within the limit", () => {
    expect(SCHEDULED_TASK_TEMPLATES.length).toBeGreaterThanOrEqual(6);
    for (const tpl of SCHEDULED_TASK_TEMPLATES) {
      expect(ns[`tpl_${tpl.key}_title`], tpl.key).toBeTruthy();
      expect(ns[`tpl_${tpl.key}_hint`], tpl.key).toBeTruthy();
      expect(tpl.prompt.length, tpl.key).toBeLessThanOrEqual(2000);
      expect(tpl.name.length, tpl.key).toBeLessThanOrEqual(120);
      const problem = validateSchedule({ kind: tpl.kind, time: tpl.time, day: tpl.day ?? "MON", cron: "", timezone: "UTC" }, 15);
      expect(problem, tpl.key).toBeNull();
    }
  });

  it("shows the starters on an empty page and pre-fills the form from one", async () => {
    listScheduledTasks.mockResolvedValue([]);
    render(<ScheduledTasksPanel />);
    const starters = await screen.findByTestId("scheduled-starters");
    fireEvent.click(within(starters).getByText("tpl_weekly_review_title"));
    expect((await screen.findByLabelText("nameLabel") as HTMLInputElement).value).toBe("Weekly review");
    expect(selectedLabel("frequencyLabel")).toBe("freq_weekly");
    expect(selectedLabel("dayLabel")).toBe("day_FRI");
    expect((screen.getByLabelText("timeLabel") as HTMLInputElement).value).toBe("17:00");
  });
});

describe("duplicate, filter and limit", () => {
  it("duplicate opens a NEW task pre-filled with the original's prompt and agent", async () => {
    listScheduledTasks.mockResolvedValue([task()]);
    render(<ScheduledTasksPanel />);
    fireEvent.click(await screen.findByText("duplicate"));
    expect((screen.getByLabelText("nameLabel") as HTMLInputElement).value).toBe("copyName");
    expect((screen.getByLabelText("promptLabel") as HTMLTextAreaElement).value).toBe("Summarise my unread email");
    expect(selectedLabel("agentLabel")).toBe("Assistant");
    expect(screen.getByText("createTitle")).toBeTruthy();
  });

  it("filters by status and by search text", async () => {
    listScheduledTasks.mockResolvedValue([
      task({ id: "a", name: "Alpha" }),
      task({ id: "b", name: "Beta", enabled: false }),
      task({ id: "c", name: "Gamma", consecutiveFailures: 2 }),
    ]);
    render(<ScheduledTasksPanel />);
    await screen.findByText("Alpha");
    fireEvent.click(screen.getByText("filter_paused"));
    expect(screen.queryByText("Alpha")).toBeNull();
    expect(screen.getByText("Beta")).toBeTruthy();
    fireEvent.click(screen.getByText("filter_all"));
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "gam" } });
    expect(screen.getByText("Gamma")).toBeTruthy();
    expect(screen.queryByText("Beta")).toBeNull();
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "zzz" } });
    expect(screen.getByRole("status").textContent).toBe("noMatches");
  });

  it("at the plan limit it disables New task and Duplicate and says why", async () => {
    getScheduledTasksConfig.mockResolvedValue({ ...cfg, maxTasks: 1 });
    listScheduledTasks.mockResolvedValue([task()]);
    render(<ScheduledTasksPanel />);
    await screen.findByText("limitReached");
    expect((screen.getByText("newTask").closest("button") as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByText("duplicate").closest("button") as HTMLButtonElement).disabled).toBe(true);
  });
});

describe("filterTasks", () => {
  const tasks = [task({ id: "a", name: "Alpha" }), task({ id: "b", name: "Beta", enabled: false }), task({ id: "c", name: "Gamma", consecutiveFailures: 1, prompt: "check invoices" })];
  it("groups are disjoint, matching taskStatusCounts", () => {
    expect(tasks.map((x) => (["active", "failing", "paused"] as const).filter((g) => taskInGroup(x, g)).length)).toEqual([1, 1, 1]);
  });
  it("matches name, prompt and agent name, case-insensitively", () => {
    expect(filterTasks(tasks, "all", "INVOICE").map((x) => x.id)).toEqual(["c"]);
    expect(filterTasks(tasks, "all", "assist", new Map([["a1", "Assistant"]])).length).toBe(3);
    expect(filterTasks(tasks, "failing", "").map((x) => x.id)).toEqual(["c"]);
  });
});
