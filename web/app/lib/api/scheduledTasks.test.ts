import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...args: unknown[]) => apiFetchMock(...args),
  toJson: async (res: Response) => res.json(),
  toJsonQuietOn: () => async (res: Response) => res.json(),
  jsonBody: (payload: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }),
}));

import { scheduledTasksApi } from "./scheduledTasks";

const ok = (body: unknown = {}) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

beforeEach(() => {
  apiFetchMock.mockReset();
  apiFetchMock.mockImplementation(async () => ok());
});

describe("scheduledTasksApi", () => {
  it("reads the capabilities and the list from the documented routes", async () => {
    await scheduledTasksApi.getScheduledTasksConfig();
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/scheduled-tasks/capabilities");
    await scheduledTasksApi.listScheduledTasks();
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/scheduled-tasks", { skipGetCache: true });
  });

  it("creates with POST and updates with PUT, sending the payload as JSON", async () => {
    const payload = {
      agentId: "a1",
      name: "n",
      prompt: "p",
      frequency: "daily" as const,
      time: "08:00",
      timezone: "UTC",
      delivery: "in_app" as const,
    };
    await scheduledTasksApi.createScheduledTask(payload);
    const [createUrl, createInit] = apiFetchMock.mock.calls[0];
    expect(createUrl).toBe("/api/scheduled-tasks");
    expect(createInit.method).toBe("POST");
    expect(JSON.parse(createInit.body)).toEqual(payload);

    await scheduledTasksApi.updateScheduledTask("t 1", payload);
    const [updateUrl, updateInit] = apiFetchMock.mock.calls[1];
    expect(updateUrl).toBe("/api/scheduled-tasks/t%201");
    expect(updateInit.method).toBe("PUT");
  });

  it("pauses, resumes and runs a task with POSTs on the task's sub-routes", async () => {
    await scheduledTasksApi.pauseScheduledTask("t1");
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/scheduled-tasks/t1/pause", { method: "POST" });
    await scheduledTasksApi.resumeScheduledTask("t1");
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/scheduled-tasks/t1/resume", { method: "POST" });
    await scheduledTasksApi.runScheduledTaskNow("t1");
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/scheduled-tasks/t1/run-now", { method: "POST" });
  });

  it("lists a task's runs with a limit", async () => {
    await scheduledTasksApi.listScheduledTaskRuns("t1");
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/scheduled-tasks/t1/runs?limit=20", { skipGetCache: true });
    await scheduledTasksApi.listScheduledTaskRuns("t1", 5);
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/scheduled-tasks/t1/runs?limit=5", { skipGetCache: true });
  });

  it("deletes with DELETE and treats a 204 as success", async () => {
    apiFetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(scheduledTasksApi.deleteScheduledTask("t1")).resolves.toBeNull();
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/scheduled-tasks/t1", { method: "DELETE" });
  });

  it("previews a schedule through POST /preview", async () => {
    await scheduledTasksApi.previewScheduledTaskSchedule({ frequency: "custom", cron: "0 8 * * *", timezone: "UTC" });
    const [url, init] = apiFetchMock.mock.calls[0];
    expect(url).toBe("/api/scheduled-tasks/preview");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ frequency: "custom", cron: "0 8 * * *", timezone: "UTC" });
  });
});
