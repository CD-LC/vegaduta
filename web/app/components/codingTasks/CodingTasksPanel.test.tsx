// The Coding tasks console: which state the page shows for what the server says (flag off, a core that predates the
// feature, plan/setup gate, usable), the task detail (diff, the approval gate bound to the diff hash, reject, cancel,
// rollback) and the create form. t() returns the key so assertions bind to keys, not to English copy.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const getCodingTasksConfig = vi.fn();
const listCodingTasks = vi.fn();
const listAgents = vi.fn();
const startCodingTask = vi.fn();
const getCodingTask = vi.fn();
const getCodingTaskDiff = vi.fn();
const approveCodingTask = vi.fn();
const rejectCodingTask = vi.fn();
const cancelCodingTask = vi.fn();
const rollbackCodingTask = vi.fn();
const toastSuccess = vi.fn();
const toastInfo = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get getCodingTasksConfig() { return getCodingTasksConfig; },
    get listCodingTasks() { return listCodingTasks; },
    get listAgents() { return listAgents; },
    get startCodingTask() { return startCodingTask; },
    get getCodingTask() { return getCodingTask; },
    get getCodingTaskDiff() { return getCodingTaskDiff; },
    get approveCodingTask() { return approveCodingTask; },
    get rejectCodingTask() { return rejectCodingTask; },
    get cancelCodingTask() { return cancelCodingTask; },
    get rollbackCodingTask() { return rollbackCodingTask; },
  },
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock("../../lib/toastBus", () => ({
  toast: {
    get success() { return toastSuccess; },
    get info() { return toastInfo; },
    error: vi.fn(),
  },
}));

import CodingTasksPanel, { POLL_MS } from "./CodingTasksPanel";
import type { CodingTask, CodingTasksConfig } from "../../lib/codingTasks";

function config(over: Partial<CodingTasksConfig> = {}): CodingTasksConfig {
  return {
    enabled: true,
    allowed: true,
    reason: null,
    runner: "e2b",
    testRunners: ["mvn", "npm", "pytest"],
    defaults: { maxSteps: 20, maxWallSeconds: 900, maxTokens: 150000, maxFixIterations: 3 },
    caps: { maxSteps: 40, maxWallSeconds: 1800, maxTokens: 400000, maxFixIterations: 5 },
    maxArchiveBytes: 1024 * 1024,
    approvalTimeoutSeconds: 900,
    rollbackWindowSeconds: 600,
    networkEnabled: false,
    ...over,
  };
}

function task(over: Partial<CodingTask> = {}): CodingTask {
  return {
    taskId: "11111111-1111-1111-1111-111111111111",
    agentId: "a1",
    status: "AWAITING_APPROVAL",
    outcome: null,
    phase: "AWAITING_APPROVAL",
    instruction: "Add validation",
    testCommand: "mvn -q test",
    sourceKind: "GIT",
    repoUrl: "https://github.com/acme/widgets",
    runner: "e2b",
    budget: { maxSteps: 20, maxWallSeconds: 900, maxTokens: 150000, maxFixIterations: 3 },
    usage: { steps: 4, tokens: 1200, elapsedMs: 65000 },
    events: [{ atMs: 1, type: "start", text: "Opening an isolated e2b sandbox." }],
    tests: [{ iteration: 0, exitCode: 0, outputTail: "BUILD SUCCESS" }],
    review: { approved: true, notes: "looks fine" },
    diffAvailable: true,
    diffHash: "hash-1",
    diffFiles: 1,
    testFilesTouched: false,
    message: "Tests pass.",
    cancelRequested: false,
    createdAtMs: 1000,
    updatedAtMs: 2000,
    ...over,
  };
}

const DIFF = "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-old\n+new\n";

beforeEach(() => {
  vi.clearAllMocks();
  listAgents.mockResolvedValue([{ id: "a1", name: "Assistant" }]);
  getCodingTaskDiff.mockResolvedValue({ taskId: "11111111-1111-1111-1111-111111111111", diff: DIFF, diffHash: "hash-1", truncated: false });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("CodingTasksPanel states", () => {
  it("says so when the feature is not enabled and never lists tasks", async () => {
    getCodingTasksConfig.mockResolvedValue(config({ enabled: false }));
    render(<CodingTasksPanel />);

    expect(await screen.findByText("disabledTitle")).toBeTruthy();
    expect(listCodingTasks).not.toHaveBeenCalled();
    expect(screen.queryByText("newTask")).toBeNull();
  });

  it("treats a 404 from a core that predates the feature as not enabled", async () => {
    getCodingTasksConfig.mockRejectedValue(Object.assign(new Error("nope"), { status: 404 }));
    render(<CodingTasksPanel />);
    expect(await screen.findByText("disabledTitle")).toBeTruthy();
    expect(listCodingTasks).not.toHaveBeenCalled();
  });

  it("shows a retry on a real load failure", async () => {
    getCodingTasksConfig.mockRejectedValue(Object.assign(new Error("boom"), { status: 500 }));
    render(<CodingTasksPanel />);
    expect(await screen.findByText("loadFailed")).toBeTruthy();
    getCodingTasksConfig.mockResolvedValue(config());
    listCodingTasks.mockResolvedValue([]);
    fireEvent.click(screen.getByText("retry"));
    expect(await screen.findByText("emptyTitle")).toBeTruthy();
  });

  it("explains a plan or setup gate and disables New task", async () => {
    getCodingTasksConfig.mockResolvedValue(config({ allowed: false, reason: "PLAN" }));
    listCodingTasks.mockResolvedValue([]);
    render(<CodingTasksPanel />);
    expect(await screen.findByTestId("coding-not-allowed")).toBeTruthy();
    expect(screen.getByText("planBody", { exact: false })).toBeTruthy();
    expect((screen.getByText("newTask").closest("button") as HTMLButtonElement).disabled).toBe(true);
  });

  it("reports readiness once, after the first load", async () => {
    getCodingTasksConfig.mockResolvedValue(config({ enabled: false }));
    const onLoaded = vi.fn();
    render(<CodingTasksPanel onLoaded={onLoaded} />);
    await screen.findByText("disabledTitle");
    expect(onLoaded).toHaveBeenCalledTimes(1);
  });
});

describe("CodingTasksPanel approval gate", () => {
  beforeEach(() => {
    getCodingTasksConfig.mockResolvedValue(config());
  });

  it("shows the diff, and Approve sends the hash of the diff on screen", async () => {
    listCodingTasks.mockResolvedValue([task()]);
    approveCodingTask.mockResolvedValue(task({ message: "Approval recorded" }));
    render(<CodingTasksPanel />);

    const diff = await screen.findByTestId("coding-diff");
    expect(diff.textContent).toContain("+new");
    expect(screen.getByTestId("coding-approval-note")).toBeTruthy();

    const approve = await screen.findByTestId("coding-approve");
    await waitFor(() => expect((approve as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(approve);
    await waitFor(() => expect(approveCodingTask).toHaveBeenCalledWith("11111111-1111-1111-1111-111111111111", "hash-1"));
  });

  it("keeps Approve disabled until the diff has loaded", async () => {
    listCodingTasks.mockResolvedValue([task()]);
    getCodingTaskDiff.mockReturnValue(new Promise(() => {}));
    render(<CodingTasksPanel />);
    const approve = await screen.findByTestId("coding-approve");
    expect((approve as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(approve);
    expect(approveCodingTask).not.toHaveBeenCalled();
  });

  it("never offers Approve when the loaded diff is not the one the task now reports", async () => {
    listCodingTasks.mockResolvedValue([task({ diffHash: "hash-2" })]);
    getCodingTaskDiff.mockResolvedValue({ taskId: "x", diff: DIFF, diffHash: "hash-1", truncated: false });
    render(<CodingTasksPanel />);
    const approve = await screen.findByTestId("coding-approve");
    await screen.findByTestId("coding-diff");
    expect((approve as HTMLButtonElement).disabled).toBe(true);
  });

  it("Reject calls reject and Approve never fires", async () => {
    listCodingTasks.mockResolvedValue([task()]);
    rejectCodingTask.mockResolvedValue(task({ status: "REJECTED", diffAvailable: false, diffHash: null }));
    render(<CodingTasksPanel />);
    fireEvent.click(await screen.findByTestId("coding-reject"));
    await waitFor(() => expect(rejectCodingTask).toHaveBeenCalledWith("11111111-1111-1111-1111-111111111111"));
    expect(approveCodingTask).not.toHaveBeenCalled();
  });

  it("a 409 from approve shows the server's reason and refreshes the task", async () => {
    listCodingTasks.mockResolvedValue([task()]);
    approveCodingTask.mockRejectedValue(Object.assign(new Error("The change shown to you is no longer the current one."), { status: 409 }));
    getCodingTask.mockResolvedValue(task({ diffHash: "hash-9" }));
    render(<CodingTasksPanel />);
    const approve = await screen.findByTestId("coding-approve");
    await waitFor(() => expect((approve as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(approve);
    await waitFor(() => expect(toastInfo).toHaveBeenCalledWith("The change shown to you is no longer the current one."));
    await waitFor(() => expect(getCodingTask).toHaveBeenCalled());
  });

  it("warns when the change edits test files", async () => {
    listCodingTasks.mockResolvedValue([task({ testFilesTouched: true })]);
    render(<CodingTasksPanel />);
    expect(await screen.findByTestId("coding-test-files-note")).toBeTruthy();
  });

  it("offers no approve, reject or cancel on a failed task, and no diff section", async () => {
    listCodingTasks.mockResolvedValue([task({ status: "FAILED", diffAvailable: false, diffHash: null, message: "Tests still fail." })]);
    render(<CodingTasksPanel />);
    await screen.findByTestId("coding-task-detail");
    expect(screen.queryByTestId("coding-approve")).toBeNull();
    expect(screen.queryByTestId("coding-reject")).toBeNull();
    expect(screen.queryByText("cancel")).toBeNull();
    expect(screen.queryByTestId("coding-diff")).toBeNull();
  });

  it("offers Rollback (and no Approve) after approval, and rolls back through the API", async () => {
    listCodingTasks.mockResolvedValue([task({ status: "APPROVED", phase: "APPROVED" })]);
    rollbackCodingTask.mockResolvedValue(task({ status: "ROLLED_BACK", diffAvailable: false, diffHash: null }));
    render(<CodingTasksPanel />);
    await screen.findByTestId("coding-task-detail");
    expect(screen.queryByTestId("coding-approve")).toBeNull();
    fireEvent.click(screen.getByText("rollback"));
    fireEvent.click(await screen.findByText("rollbackConfirm"));
    await waitFor(() => expect(rollbackCodingTask).toHaveBeenCalledWith("11111111-1111-1111-1111-111111111111"));
  });

  it("cancels a running task", async () => {
    listCodingTasks.mockResolvedValue([task({ status: "RUNNING", phase: "TESTING", diffAvailable: false, diffHash: null })]);
    cancelCodingTask.mockResolvedValue({ taskId: "x", accepted: true });
    render(<CodingTasksPanel />);
    fireEvent.click(await screen.findByText("cancel"));
    await waitFor(() => expect(cancelCodingTask).toHaveBeenCalledWith("11111111-1111-1111-1111-111111111111"));
  });
});

describe("CodingTasksPanel polling", () => {
  it("polls a live task and stops once it ends", async () => {
    getCodingTasksConfig.mockResolvedValue(config());
    listCodingTasks.mockResolvedValue([task({ status: "RUNNING", phase: "TESTING", diffAvailable: false, diffHash: null })]);
    getCodingTask.mockResolvedValue(task({ status: "FAILED", diffAvailable: false, diffHash: null, message: "Tests still fail." }));
    vi.useFakeTimers({ shouldAdvanceTime: true });
    render(<CodingTasksPanel />);
    await screen.findByTestId("coding-task-detail");
    expect(getCodingTask).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(POLL_MS + 50);
    await waitFor(() => expect(getCodingTask).toHaveBeenCalledTimes(1));
    await screen.findByText("Tests still fail.");

    await vi.advanceTimersByTimeAsync(POLL_MS * 3);
    expect(getCodingTask).toHaveBeenCalledTimes(1);
  });
});

describe("CodingTasksPanel create form", () => {
  beforeEach(() => {
    getCodingTasksConfig.mockResolvedValue(config());
    listCodingTasks.mockResolvedValue([]);
  });

  it("will not send an invalid draft, then starts a git task and shows it", async () => {
    startCodingTask.mockResolvedValue(task({ status: "QUEUED", phase: "QUEUED", diffAvailable: false, diffHash: null, events: [] }));
    render(<CodingTasksPanel />);
    fireEvent.click(await screen.findByText("newTask"));
    fireEvent.click(await screen.findByText("start"));
    expect(startCodingTask).not.toHaveBeenCalled();
    expect(await screen.findByText("problem_instructionRequired")).toBeTruthy();

    fireEvent.change(screen.getByLabelText("instructionLabel"), { target: { value: "Add validation" } });
    fireEvent.change(screen.getByLabelText("repoLabel"), { target: { value: "https://github.com/acme/widgets" } });
    fireEvent.change(screen.getByLabelText("argsLabel"), { target: { value: "-q test" } });
    fireEvent.click(screen.getByText("start"));

    await waitFor(() => expect(startCodingTask).toHaveBeenCalledTimes(1));
    const form = startCodingTask.mock.calls[0][0] as FormData;
    expect(form.get("agentId")).toBe("a1");
    expect(form.get("testCommand")).toBe("mvn -q test");
    expect(form.get("repoUrl")).toBe("https://github.com/acme/widgets");
    expect(await screen.findByTestId("coding-task-detail")).toBeTruthy();
  });

  it("rejects shell syntax in the arguments before sending", async () => {
    render(<CodingTasksPanel />);
    fireEvent.click(await screen.findByText("newTask"));
    fireEvent.change(await screen.findByLabelText("instructionLabel"), { target: { value: "Do it" } });
    fireEvent.change(screen.getByLabelText("repoLabel"), { target: { value: "https://github.com/acme/widgets" } });
    fireEvent.change(screen.getByLabelText("argsLabel"), { target: { value: "test; rm -rf /" } });
    fireEvent.click(screen.getByText("start"));
    expect(await screen.findByText("problem_testArgs")).toBeTruthy();
    expect(startCodingTask).not.toHaveBeenCalled();
  });

  it("shows the server's sentence for a plan gate", async () => {
    startCodingTask.mockRejectedValue(Object.assign(new Error("Coding tasks are a paid-plan feature."), { status: 403 }));
    render(<CodingTasksPanel />);
    fireEvent.click(await screen.findByText("newTask"));
    fireEvent.change(await screen.findByLabelText("instructionLabel"), { target: { value: "Do it" } });
    fireEvent.change(screen.getByLabelText("repoLabel"), { target: { value: "https://github.com/acme/widgets" } });
    fireEvent.click(screen.getByText("start"));
    const alert = await screen.findByTestId("coding-task-problem");
    expect(alert.textContent).toContain("Coding tasks are a paid-plan feature.");
    expect(alert.textContent).toContain("upgradeLink");
  });
});
