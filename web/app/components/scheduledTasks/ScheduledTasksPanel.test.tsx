// The Scheduled tasks console: which state the page shows for what the server says (flag off, a core that predates
// the feature, a load failure, usable), the task list actions (pause / resume / run now / delete) and the create form
// (schedule validation, live next-runs preview, delivery recipient). t() returns the key so assertions bind to keys,
// not to English copy.

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
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { optionLabels, pickOption } from "../ui/test-select";

const getScheduledTasksConfig = vi.fn();
const listScheduledTasks = vi.fn();
const listAgents = vi.fn();
const createScheduledTask = vi.fn();
const updateScheduledTask = vi.fn();
const pauseScheduledTask = vi.fn();
const resumeScheduledTask = vi.fn();
const runScheduledTaskNow = vi.fn();
const deleteScheduledTask = vi.fn();
const listScheduledTaskRuns = vi.fn();
const previewScheduledTaskSchedule = vi.fn();
const toastSuccess = vi.fn();
const toastInfo = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get getScheduledTasksConfig() {
      return getScheduledTasksConfig;
    },
    get listScheduledTasks() {
      return listScheduledTasks;
    },
    get listAgents() {
      return listAgents;
    },
    get createScheduledTask() {
      return createScheduledTask;
    },
    get updateScheduledTask() {
      return updateScheduledTask;
    },
    get pauseScheduledTask() {
      return pauseScheduledTask;
    },
    get resumeScheduledTask() {
      return resumeScheduledTask;
    },
    get runScheduledTaskNow() {
      return runScheduledTaskNow;
    },
    get deleteScheduledTask() {
      return deleteScheduledTask;
    },
    get listScheduledTaskRuns() {
      return listScheduledTaskRuns;
    },
    get previewScheduledTaskSchedule() {
      return previewScheduledTaskSchedule;
    },
  },
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock("../../lib/toastBus", () => ({
  toast: {
    get success() {
      return toastSuccess;
    },
    get info() {
      return toastInfo;
    },
    error: vi.fn(),
  },
}));

import ScheduledTasksPanel from "./ScheduledTasksPanel";
import type { ScheduledTask, ScheduledTasksConfig } from "../../lib/scheduledTasks";

function config(over: Partial<ScheduledTasksConfig> = {}): ScheduledTasksConfig {
  return {
    enabled: true,
    maxTasks: 10,
    minIntervalMinutes: 15,
    maxPromptChars: 2000,
    deliveryChannels: ["email", "in_app", "slack", "telegram", "whatsapp"],
    ...over,
  };
}

function task(over: Partial<ScheduledTask> = {}): ScheduledTask {
  return {
    id: "t1",
    agentId: "a1",
    name: "Morning digest",
    prompt: "Summarise my unread email",
    frequency: "weekdays",
    time: "08:00",
    day: null,
    cron: "0 0 8 * * MON-FRI",
    timezone: "UTC",
    scheduleDescription: "Every weekday",
    delivery: "in_app",
    deliveryTo: null,
    enabled: true,
    disabledReason: null,
    nextRunAt: "2026-10-05T08:00:00Z",
    upcomingRuns: [],
    lastRunAt: null,
    lastStatus: null,
    lastJobId: null,
    consecutiveFailures: 0,
    runCount: 0,
    maxRuns: null,
    expiresAt: null,
    ownerSubject: "u1",
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    ...over,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  listAgents.mockResolvedValue([{ id: "a1", name: "Assistant" }]);
  listScheduledTaskRuns.mockResolvedValue([]);
});

describe("ScheduledTasksPanel states", () => {
  it("says so when the feature is not enabled and never lists tasks", async () => {
    getScheduledTasksConfig.mockResolvedValue(config({ enabled: false }));
    render(<ScheduledTasksPanel />);

    expect(await screen.findByText("disabledTitle")).toBeTruthy();
    expect(listScheduledTasks).not.toHaveBeenCalled();
    expect(screen.queryByText("newTask")).toBeNull();
  });

  it("treats a core that predates the feature (404) as not enabled, not as an error", async () => {
    getScheduledTasksConfig.mockRejectedValue(Object.assign(new Error("nope"), { status: 404 }));
    render(<ScheduledTasksPanel />);

    expect(await screen.findByText("disabledTitle")).toBeTruthy();
    expect(screen.queryByText("loadFailed")).toBeNull();
  });

  it("offers a retry when the config cannot be loaded", async () => {
    getScheduledTasksConfig.mockRejectedValueOnce(new Error("boom")).mockResolvedValue(config());
    listScheduledTasks.mockResolvedValue([]);
    render(<ScheduledTasksPanel />);

    expect(await screen.findByText("loadFailed")).toBeTruthy();
    fireEvent.click(screen.getByText("retry"));
    expect(await screen.findByText("emptyTitle")).toBeTruthy();
  });

  it("reports readiness once, after the first load", async () => {
    getScheduledTasksConfig.mockResolvedValue(config());
    listScheduledTasks.mockResolvedValue([]);
    const onLoaded = vi.fn();
    render(<ScheduledTasksPanel onLoaded={onLoaded} />);

    await screen.findByText("emptyTitle");
    expect(onLoaded).toHaveBeenCalledTimes(1);
  });

  it("lists the tasks with their state and next run", async () => {
    getScheduledTasksConfig.mockResolvedValue(config());
    listScheduledTasks.mockResolvedValue([
      task(),
      task({
        id: "t2",
        name: "Weekly report",
        enabled: false,
        nextRunAt: null,
        disabledReason: "Paused automatically after 3 failed runs in a row.",
      }),
    ]);
    render(<ScheduledTasksPanel />);

    expect(await screen.findByText("Morning digest")).toBeTruthy();
    expect(screen.getByText("Weekly report")).toBeTruthy();
    expect(screen.getAllByTestId("scheduled-task-list")).toHaveLength(1);
    expect(screen.getByText("Paused automatically after 3 failed runs in a row.")).toBeTruthy();
    expect(screen.getAllByText("stateActive")).toHaveLength(1);
    expect(screen.getAllByText("statePaused")).toHaveLength(1);
  });
});

describe("task actions", () => {
  beforeEach(() => {
    getScheduledTasksConfig.mockResolvedValue(config());
    listScheduledTasks.mockResolvedValue([task()]);
  });

  it("pauses a task and offers resume instead", async () => {
    pauseScheduledTask.mockResolvedValue(task({ enabled: false, nextRunAt: null }));
    render(<ScheduledTasksPanel />);

    fireEvent.click(await screen.findByText("pause"));

    await waitFor(() => expect(pauseScheduledTask).toHaveBeenCalledWith("t1"));
    expect(await screen.findByText("resume")).toBeTruthy();
    expect(toastSuccess).toHaveBeenCalledWith("paused");
  });

  it("resumes a paused task", async () => {
    listScheduledTasks.mockResolvedValue([task({ enabled: false, nextRunAt: null })]);
    resumeScheduledTask.mockResolvedValue(task());
    render(<ScheduledTasksPanel />);

    fireEvent.click(await screen.findByText("resume"));

    await waitFor(() => expect(resumeScheduledTask).toHaveBeenCalledWith("t1"));
    expect(await screen.findByText("pause")).toBeTruthy();
  });

  it("runs a task now, reloads it and opens the run history", async () => {
    runScheduledTaskNow.mockResolvedValue({ id: "r1", status: "ENQUEUED", error: null });
    render(<ScheduledTasksPanel />);

    fireEvent.click(await screen.findByText("runNow"));

    await waitFor(() => expect(runScheduledTaskNow).toHaveBeenCalledWith("t1"));
    await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith("runNowStarted"));
    await waitFor(() => expect(listScheduledTaskRuns).toHaveBeenCalled());
    expect(listScheduledTasks.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it("explains a run-now the overlap guard refused instead of pretending it started", async () => {
    runScheduledTaskNow.mockResolvedValue({
      id: "r1",
      status: "SKIPPED",
      error: "The previous run is still in progress (RUNNING).",
    });
    render(<ScheduledTasksPanel />);

    fireEvent.click(await screen.findByText("runNow"));

    await waitFor(() => expect(toastInfo).toHaveBeenCalledWith("The previous run is still in progress (RUNNING)."));
    expect(toastSuccess).not.toHaveBeenCalledWith("runNowStarted");
  });

  it("deletes a task only after the confirmation", async () => {
    deleteScheduledTask.mockResolvedValue(null);
    render(<ScheduledTasksPanel />);

    fireEvent.click(await screen.findByRole("button", { name: "delete" }));
    expect(deleteScheduledTask).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole("button", { name: "deleteConfirm" }));

    await waitFor(() => expect(deleteScheduledTask).toHaveBeenCalledWith("t1"));
    await waitFor(() => expect(screen.queryByText("Morning digest")).toBeNull());
  });

  it("shows the run history of a task", async () => {
    listScheduledTaskRuns.mockResolvedValue([
      {
        id: "r1",
        taskId: "t1",
        trigger: "SCHEDULE",
        status: "SUCCEEDED",
        attempt: 1,
        startedAt: "2026-10-05T08:00:00Z",
        finishedAt: "2026-10-05T08:01:00Z",
        jobId: "j1",
        costUsd: 0.1234,
        deliveryStatus: "DELIVERED",
        error: null,
      },
      {
        id: "r2",
        taskId: "t1",
        trigger: "RETRY",
        status: "FAILED",
        attempt: 2,
        startedAt: "2026-10-04T08:00:00Z",
        finishedAt: null,
        jobId: "j2",
        costUsd: null,
        deliveryStatus: null,
        error: "EXECUTION_FAILED: model timeout",
      },
    ]);
    render(<ScheduledTasksPanel />);

    fireEvent.click(await screen.findByText("historyShow"));

    expect(await screen.findByTestId("scheduled-task-runs")).toBeTruthy();
    expect(screen.getByText("runStatus_SUCCEEDED")).toBeTruthy();
    expect(screen.getByText("runDelivery_DELIVERED")).toBeTruthy();
    expect(screen.getByText("$0.12")).toBeTruthy();
    expect(screen.getByText("EXECUTION_FAILED: model timeout")).toBeTruthy();
    expect(screen.getByText("trigger_RETRY")).toBeTruthy();
  });
});

describe("create form", () => {
  beforeEach(() => {
    getScheduledTasksConfig.mockResolvedValue(config());
    listScheduledTasks.mockResolvedValue([]);
  });

  async function openForm() {
    render(<ScheduledTasksPanel />);
    fireEvent.click(await screen.findByText("newTask"));
    return screen.findByTestId("scheduled-task-form");
  }

  function fillBasics() {
    fireEvent.change(screen.getByLabelText("nameLabel"), { target: { value: "Digest" } });
    fireEvent.change(screen.getByLabelText("promptLabel"), { target: { value: "Summarise my unread email" } });
  }

  it("previews the next three runs as the schedule changes", async () => {
    await openForm();

    const preview = screen.getByTestId("scheduled-task-preview");
    expect(preview.querySelectorAll("li")).toHaveLength(3);

    fireEvent.change(screen.getByLabelText("timeLabel"), { target: { value: "25:99" } });
    // An invalid time is a message, not a preview.
    expect(screen.queryByTestId("scheduled-task-preview")).toBeNull();
  });

  it("creates a task with the friendly schedule", async () => {
    createScheduledTask.mockResolvedValue(task());
    await openForm();
    fillBasics();
    pickOption("frequencyLabel", "freq_weekly");
    pickOption("dayLabel", "day_FRI");
    fireEvent.change(screen.getByLabelText("timeLabel"), { target: { value: "17:30" } });
    fireEvent.change(screen.getByLabelText("timezoneLabel"), { target: { value: "Asia/Kolkata" } });

    fireEvent.click(screen.getByText("save"));

    await waitFor(() => expect(createScheduledTask).toHaveBeenCalledTimes(1));
    expect(createScheduledTask).toHaveBeenCalledWith(
      expect.objectContaining({
        agentId: "a1",
        name: "Digest",
        prompt: "Summarise my unread email",
        frequency: "weekly",
        day: "FRI",
        time: "17:30",
        timezone: "Asia/Kolkata",
        delivery: "in_app",
      })
    );
    expect(await screen.findByText("Morning digest")).toBeTruthy();
    expect(screen.queryByTestId("scheduled-task-form")).toBeNull();
  });

  it("shows a plan-limit refusal inline with an upgrade link and keeps the form open", async () => {
    createScheduledTask.mockRejectedValue(
      Object.assign(new Error("This workspace already has the maximum of 3 scheduled tasks on the free plan."), {
        status: 403,
      })
    );
    await openForm();
    fillBasics();

    fireEvent.click(screen.getByText("save"));

    const card = await screen.findByTestId("scheduled-task-plan-limit");
    expect(card.textContent).toContain("maximum of 3 scheduled tasks");
    expect(screen.getByText("upgradeLink").closest("a")?.getAttribute("href")).toBe("/admin/billing");
    expect(screen.getByTestId("scheduled-task-form")).toBeTruthy();
  });

  it("shows the plan-limit refusal without an upgrade link on a personal install", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    try {
      createScheduledTask.mockRejectedValue(Object.assign(new Error("Maximum of 3 scheduled tasks."), { status: 403 }));
      await openForm();
      fillBasics();
      fireEvent.click(screen.getByText("save"));
      const card = await screen.findByTestId("scheduled-task-plan-limit");
      expect(card.textContent).toContain("Maximum of 3 scheduled tasks.");
      expect(screen.queryByText("upgradeLink")).toBeNull();
    } finally {
      vi.unstubAllEnvs();
    }
  });

  it("does not submit while the name or prompt is missing", async () => {
    await openForm();

    fireEvent.click(screen.getByText("save"));

    expect(createScheduledTask).not.toHaveBeenCalled();
    expect(screen.getByText("problem_nameRequired")).toBeTruthy();
    expect(screen.getByText("problem_promptRequired")).toBeTruthy();
  });

  it("refuses a custom cron faster than the minimum interval before calling the server", async () => {
    await openForm();
    fillBasics();
    pickOption("frequencyLabel", "freq_custom");
    fireEvent.change(screen.getByLabelText("cronLabel"), { target: { value: "*/5 * * * *" } });

    expect((await screen.findByTestId("scheduled-task-schedule-problem")).textContent).toBe("problem_cronTooFrequent");
    fireEvent.click(screen.getByText("save"));

    expect(createScheduledTask).not.toHaveBeenCalled();
    expect(previewScheduledTaskSchedule).not.toHaveBeenCalled();
  });

  it("asks the server to preview a valid custom cron", async () => {
    previewScheduledTaskSchedule.mockResolvedValue({
      cron: "0 0 8 * * MON-FRI",
      description: "Every weekday",
      timezone: "UTC",
      nextRuns: ["2026-10-05T08:00:00Z", "2026-10-06T08:00:00Z", "2026-10-07T08:00:00Z"],
    });
    await openForm();
    pickOption("frequencyLabel", "freq_custom");
    fireEvent.change(screen.getByLabelText("timezoneLabel"), { target: { value: "UTC" } });
    fireEvent.change(screen.getByLabelText("cronLabel"), { target: { value: "0 8 * * 1-5" } });

    await waitFor(() =>
      expect(previewScheduledTaskSchedule).toHaveBeenCalledWith({
        frequency: "custom",
        cron: "0 8 * * 1-5",
        timezone: "UTC",
      })
    );
    await waitFor(() => expect(screen.getByTestId("scheduled-task-preview").querySelectorAll("li")).toHaveLength(3));
  });

  it("requires a well-formed recipient for an external delivery and normalizes it", async () => {
    createScheduledTask.mockResolvedValue(task({ delivery: "whatsapp", deliveryTo: "+919876543210" }));
    await openForm();
    fillBasics();
    pickOption("deliveryLabel", "delivery_whatsapp");
    fireEvent.change(screen.getByLabelText("deliveryToLabel"), { target: { value: "12" } });

    fireEvent.click(screen.getByText("save"));
    expect(createScheduledTask).not.toHaveBeenCalled();
    expect(screen.getByText("problem_to_whatsapp")).toBeTruthy();
    expect(screen.getByText("deliveryApprovalNote")).toBeTruthy();

    fireEvent.change(screen.getByLabelText("deliveryToLabel"), { target: { value: "+91 98765-43210" } });
    fireEvent.click(screen.getByText("save"));

    await waitFor(() => expect(createScheduledTask).toHaveBeenCalledTimes(1));
    expect(createScheduledTask).toHaveBeenCalledWith(
      expect.objectContaining({ delivery: "whatsapp", deliveryTo: "+919876543210" })
    );
  });

  it("only offers the delivery channels the server allows", async () => {
    getScheduledTasksConfig.mockResolvedValue(config({ deliveryChannels: ["in_app", "email"] }));
    await openForm();

    const options = optionLabels("deliveryLabel");
    expect(options).toEqual(["delivery_in_app", "delivery_email"]);
  });

  it("edits an existing task in place with the agent fixed", async () => {
    listScheduledTasks.mockResolvedValue([task()]);
    updateScheduledTask.mockResolvedValue(task({ name: "Renamed" }));
    render(<ScheduledTasksPanel />);

    fireEvent.click(await screen.findByText("edit"));
    expect(((await screen.findByLabelText("agentLabel")) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("nameLabel"), { target: { value: "Renamed" } });
    fireEvent.click(screen.getByText("saveChanges"));

    await waitFor(() =>
      expect(updateScheduledTask).toHaveBeenCalledWith(
        "t1",
        expect.objectContaining({ name: "Renamed", frequency: "weekdays", time: "08:00" })
      )
    );
    expect(createScheduledTask).not.toHaveBeenCalled();
  });
});
