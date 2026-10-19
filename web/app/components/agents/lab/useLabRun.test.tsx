// useLabRun: the fire-and-poll lifecycle both lab instruments run on. Fake
// timers drive the poll loop; ./labApi is mocked, so nothing here depends on
// the HTTP layer (labApi.test.ts covers that).

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";

const api = vi.hoisted(() => ({
  startLabRun: vi.fn(),
  getLabRun: vi.fn(),
  cancelLabRun: vi.fn(),
}));
vi.mock("./labApi", () => ({ labApi: api }));

import { useLabRun } from "./useLabRun";
import type { LabRun, LabRunRequest, LabRunStatus } from "./labApi";

const AGENT = "agent-1";
const STORAGE_KEY = "agentic:lab:run:agent-1:KNOCKOUT";
const REQUEST: LabRunRequest = {
  kind: "KNOCKOUT",
  systemPrompts: ["p"],
  cells: [{ key: "b0:i0", promptIndex: 0, message: "q" }],
  scoring: "JUDGE",
  similarity: "NONE",
};
interface Ctx { label: string }

function runOf(status: LabRunStatus, extra: Partial<LabRun> = {}): LabRun {
  return {
    runId: "run-1", agentId: AGENT, kind: "KNOCKOUT", status,
    plannedCalls: 2, completedCalls: status === "SUCCEEDED" ? 2 : 0, cancelRequested: false,
    errorMessage: null, createdAtMs: 1, updatedAtMs: 1, finishedAtMs: null,
    cells: [], similarity: null,
    ...extra,
  };
}

function httpError(status: number, message = `HTTP ${status}`): Error & { status: number } {
  return Object.assign(new Error(message), { status });
}

/** Let resolved promises settle without moving the clock. */
async function flush() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

async function tick(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  window.sessionStorage.clear();
  api.startLabRun.mockReset();
  api.getLabRun.mockReset();
  api.cancelLabRun.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useLabRun", () => {
  it("is idle with nothing stored", async () => {
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await flush();
    expect(result.current.phase).toBe("idle");
    expect(result.current.run).toBeNull();
    expect(result.current.context).toBeNull();
    expect(api.getLabRun).not.toHaveBeenCalled();
  });

  it("start -> poll -> done, storing the run id and context on the way", async () => {
    let resolveStart: (run: LabRun) => void = () => {};
    api.startLabRun.mockReturnValue(new Promise<LabRun>((resolve) => (resolveStart = resolve)));
    api.getLabRun
      .mockResolvedValueOnce(runOf("RUNNING", { completedCalls: 1 }))
      .mockResolvedValueOnce(runOf("SUCCEEDED"));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));

    let started: Promise<void> = Promise.resolve();
    act(() => {
      started = result.current.start(REQUEST, { label: "first" });
    });
    expect(result.current.phase).toBe("starting");
    expect(result.current.context).toEqual({ label: "first" });
    expect(api.startLabRun).toHaveBeenCalledWith(AGENT, REQUEST);

    await act(async () => {
      resolveStart(runOf("QUEUED"));
      await started;
    });
    expect(result.current.phase).toBe("polling");
    expect(result.current.run?.status).toBe("QUEUED");
    expect(JSON.parse(window.sessionStorage.getItem(STORAGE_KEY) as string)).toEqual({
      runId: "run-1",
      context: { label: "first" },
    });
    // Nothing is polled before the interval has passed.
    expect(api.getLabRun).not.toHaveBeenCalled();

    await tick(1500);
    expect(api.getLabRun).toHaveBeenCalledTimes(1);
    expect(api.getLabRun).toHaveBeenCalledWith(AGENT, "run-1");
    expect(result.current.phase).toBe("polling");
    expect(result.current.run?.completedCalls).toBe(1);

    await tick(1500);
    expect(api.getLabRun).toHaveBeenCalledTimes(2);
    expect(result.current.phase).toBe("done");
    expect(result.current.run?.status).toBe("SUCCEEDED");

    // Terminal: the loop has stopped for good.
    await tick(10_000);
    expect(api.getLabRun).toHaveBeenCalledTimes(2);
  });

  it("goes straight to done when the start response is already terminal", async () => {
    api.startLabRun.mockResolvedValue(runOf("SUCCEEDED"));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "x" });
    });
    expect(result.current.phase).toBe("done");
    await tick(5000);
    expect(api.getLabRun).not.toHaveBeenCalled();
  });

  it("honours a custom poll interval", async () => {
    api.startLabRun.mockResolvedValue(runOf("RUNNING"));
    api.getLabRun.mockResolvedValue(runOf("RUNNING"));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT", { pollMs: 200 }));
    await act(async () => {
      await result.current.start(REQUEST, { label: "x" });
    });
    await tick(200);
    expect(api.getLabRun).toHaveBeenCalledTimes(1);
    await tick(200);
    expect(api.getLabRun).toHaveBeenCalledTimes(2);
  });

  it("a failed start gives phase error with the server's message", async () => {
    api.startLabRun.mockRejectedValue(httpError(409, "You already have a lab run in progress."));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "x" });
    });
    expect(result.current.phase).toBe("error");
    expect(result.current.error).toBe("You already have a lab run in progress.");
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
    await tick(5000);
    expect(api.getLabRun).not.toHaveBeenCalled();
  });

  it("ignores a second start while one is in flight", async () => {
    api.startLabRun.mockReturnValue(new Promise<LabRun>(() => {}));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    act(() => {
      void result.current.start(REQUEST, { label: "one" });
    });
    await act(async () => {
      await result.current.start(REQUEST, { label: "two" });
    });
    expect(api.startLabRun).toHaveBeenCalledTimes(1);
    expect(result.current.context).toEqual({ label: "one" });
  });

  it("a 404 while polling gives phase expired, keeps the last snapshot and drops the stored run", async () => {
    api.startLabRun.mockResolvedValue(runOf("RUNNING", { completedCalls: 1 }));
    api.getLabRun.mockRejectedValue(httpError(404));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "x" });
    });
    await tick(1500);
    expect(result.current.phase).toBe("expired");
    expect(result.current.run?.completedCalls).toBe(1);
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
    await tick(10_000);
    expect(api.getLabRun).toHaveBeenCalledTimes(1);
  });

  it("five consecutive poll failures give phase lost, and resume polls again", async () => {
    api.startLabRun.mockResolvedValue(runOf("RUNNING"));
    api.getLabRun.mockRejectedValue(httpError(502));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "x" });
    });
    for (let i = 1; i <= 4; i++) {
      await tick(1500);
      expect(api.getLabRun).toHaveBeenCalledTimes(i);
      expect(result.current.phase).toBe("polling");
    }
    await tick(1500);
    expect(api.getLabRun).toHaveBeenCalledTimes(5);
    expect(result.current.phase).toBe("lost");
    // Lost means stopped: no more requests until the person asks.
    await tick(10_000);
    expect(api.getLabRun).toHaveBeenCalledTimes(5);
    // The stored run survives, so a reload could still find it.
    expect(window.sessionStorage.getItem(STORAGE_KEY)).not.toBeNull();

    api.getLabRun.mockResolvedValue(runOf("SUCCEEDED"));
    act(() => {
      result.current.resume();
    });
    expect(result.current.phase).toBe("polling");
    await flush();
    expect(api.getLabRun).toHaveBeenCalledTimes(6);
    expect(result.current.phase).toBe("done");
  });

  /** Start a run and fail five polls in a row, which leaves the hook "lost". */
  async function startAndLose() {
    api.startLabRun.mockResolvedValue(runOf("RUNNING", { completedCalls: 1 }));
    api.getLabRun.mockRejectedValue(httpError(502));
    const hook = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await hook.result.current.start(REQUEST, { label: "x" });
    });
    for (let i = 0; i < 5; i++) await tick(1500);
    expect(hook.result.current.phase).toBe("lost");
    return hook;
  }

  it("start from lost does not POST a second run: it looks at the lost one again and keeps its pointer", async () => {
    const { result } = await startAndLose();
    api.getLabRun.mockResolvedValue(runOf("RUNNING", { completedCalls: 2 }));
    await act(async () => {
      await result.current.start(REQUEST, { label: "two" });
    });
    expect(api.startLabRun).toHaveBeenCalledTimes(1);
    expect(result.current.phase).toBe("polling");
    expect(api.getLabRun).toHaveBeenCalledTimes(6);
    expect(api.getLabRun).toHaveBeenLastCalledWith(AGENT, "run-1");
    // The run on screen, its context and the stored pointer are all still the first run's.
    expect(result.current.run?.completedCalls).toBe(2);
    expect(result.current.context).toEqual({ label: "x" });
    expect(JSON.parse(window.sessionStorage.getItem(STORAGE_KEY) as string)).toEqual({ runId: "run-1", context: { label: "x" } });
  });

  it("cancel from lost asks the server to stop the run, then polls it again", async () => {
    const { result } = await startAndLose();
    api.cancelLabRun.mockResolvedValue({ runId: "run-1", cancelRequested: true });
    api.getLabRun.mockResolvedValue(runOf("CANCELLED", { completedCalls: 1 }));
    await act(async () => {
      await result.current.cancel();
    });
    expect(api.cancelLabRun).toHaveBeenCalledWith(AGENT, "run-1");
    await flush();
    expect(api.getLabRun).toHaveBeenCalledTimes(6);
    expect(result.current.phase).toBe("done");
    expect(result.current.run?.status).toBe("CANCELLED");
    expect(result.current.cancelling).toBe(false);
  });

  it("a failed cancel from lost stays lost", async () => {
    const { result } = await startAndLose();
    api.cancelLabRun.mockRejectedValue(httpError(502));
    await act(async () => {
      await result.current.cancel();
    });
    expect(result.current.phase).toBe("lost");
    expect(result.current.cancelling).toBe(false);
    expect(api.getLabRun).toHaveBeenCalledTimes(5);
  });

  it("reset from lost asks the server to stop the run before forgetting it", async () => {
    const { result } = await startAndLose();
    api.cancelLabRun.mockRejectedValue(httpError(502));
    act(() => {
      result.current.reset();
    });
    await flush();
    expect(api.cancelLabRun).toHaveBeenCalledTimes(1);
    expect(api.cancelLabRun).toHaveBeenCalledWith(AGENT, "run-1");
    // Best effort: the failed request changes nothing about the reset.
    expect(result.current.phase).toBe("idle");
    expect(result.current.run).toBeNull();
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it("a 409 on start goes back to the stored run when that run is still live", async () => {
    // A finished run is on screen and its pointer is stored...
    api.startLabRun.mockResolvedValueOnce(runOf("SUCCEEDED"));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "first" });
    });
    expect(result.current.phase).toBe("done");
    // ...the next start is refused, and the server says the stored run is in fact still going.
    api.startLabRun.mockRejectedValueOnce(httpError(409, "A lab run is already in progress."));
    api.getLabRun.mockResolvedValue(runOf("RUNNING", { completedCalls: 1 }));
    await act(async () => {
      await result.current.start(REQUEST, { label: "second" });
    });
    expect(result.current.phase).toBe("polling");
    expect(result.current.error).toBeNull();
    expect(result.current.run?.status).toBe("RUNNING");
    expect(result.current.context).toEqual({ label: "first" });
    expect(api.getLabRun).toHaveBeenCalledWith(AGENT, "run-1");
    expect(JSON.parse(window.sessionStorage.getItem(STORAGE_KEY) as string)).toEqual({ runId: "run-1", context: { label: "first" } });
    // It is cancellable again, and the poll loop is running.
    api.getLabRun.mockResolvedValue(runOf("SUCCEEDED"));
    await tick(1500);
    expect(result.current.phase).toBe("done");
  });

  it("a 409 on start is an error when the stored run is over (the live run is somewhere else)", async () => {
    api.startLabRun.mockResolvedValueOnce(runOf("SUCCEEDED"));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "first" });
    });
    api.startLabRun.mockRejectedValueOnce(httpError(409, "A lab run is already in progress."));
    api.getLabRun.mockResolvedValue(runOf("SUCCEEDED"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "second" });
    });
    expect(result.current.phase).toBe("error");
    expect(result.current.error).toBe("A lab run is already in progress.");
    await tick(5000);
    expect(api.getLabRun).toHaveBeenCalledTimes(1);
  });

  it("a success between failures resets the failure count", async () => {
    api.startLabRun.mockResolvedValue(runOf("RUNNING"));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "x" });
    });
    api.getLabRun.mockRejectedValue(httpError(502));
    for (let i = 0; i < 4; i++) await tick(1500);
    api.getLabRun.mockResolvedValueOnce(runOf("RUNNING"));
    await tick(1500);
    api.getLabRun.mockRejectedValue(httpError(502));
    for (let i = 0; i < 4; i++) await tick(1500);
    expect(result.current.phase).toBe("polling");
    await tick(1500);
    expect(result.current.phase).toBe("lost");
  });

  it("cancel calls the API, keeps polling, and clears `cancelling` once the run is terminal", async () => {
    api.startLabRun.mockResolvedValue(runOf("RUNNING"));
    api.cancelLabRun.mockResolvedValue({ runId: "run-1", cancelRequested: true });
    api.getLabRun
      .mockResolvedValueOnce(runOf("RUNNING", { cancelRequested: true }))
      .mockResolvedValueOnce(runOf("CANCELLED", { cancelRequested: true }));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "x" });
    });
    await act(async () => {
      await result.current.cancel();
    });
    expect(api.cancelLabRun).toHaveBeenCalledWith(AGENT, "run-1");
    expect(result.current.cancelling).toBe(true);
    expect(result.current.phase).toBe("polling");

    await tick(1500);
    expect(result.current.cancelling).toBe(true);
    expect(result.current.phase).toBe("polling");

    await tick(1500);
    expect(result.current.phase).toBe("done");
    expect(result.current.run?.status).toBe("CANCELLED");
    expect(result.current.cancelling).toBe(false);
  });

  it("a failed cancel drops `cancelling` and leaves the run polling", async () => {
    api.startLabRun.mockResolvedValue(runOf("RUNNING"));
    api.cancelLabRun.mockRejectedValue(httpError(500));
    api.getLabRun.mockResolvedValue(runOf("RUNNING"));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "x" });
    });
    await act(async () => {
      await result.current.cancel();
    });
    expect(result.current.cancelling).toBe(false);
    expect(result.current.phase).toBe("polling");
  });

  it("cancel with no run does nothing", async () => {
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await result.current.cancel();
    });
    expect(api.cancelLabRun).not.toHaveBeenCalled();
    expect(result.current.cancelling).toBe(false);
  });

  it("restores a stored run on mount and resumes polling at once", async () => {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ runId: "run-9", context: { label: "stored" } }));
    api.getLabRun
      .mockResolvedValueOnce(runOf("RUNNING", { runId: "run-9" }))
      .mockResolvedValueOnce(runOf("SUCCEEDED", { runId: "run-9" }));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    expect(result.current.phase).toBe("polling");
    expect(result.current.context).toEqual({ label: "stored" });
    await flush();
    expect(api.getLabRun).toHaveBeenCalledTimes(1);
    expect(api.getLabRun).toHaveBeenCalledWith(AGENT, "run-9");
    expect(result.current.run?.status).toBe("RUNNING");
    await tick(1500);
    expect(result.current.phase).toBe("done");
  });

  it("ignores a stored entry it cannot read", async () => {
    window.sessionStorage.setItem(STORAGE_KEY, "{not json");
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await flush();
    expect(result.current.phase).toBe("idle");
    expect(api.getLabRun).not.toHaveBeenCalled();
  });

  it("switching agent drops the old run and restores the new agent's stored one", async () => {
    window.sessionStorage.setItem("agentic:lab:run:agent-2:KNOCKOUT", JSON.stringify({ runId: "run-b", context: { label: "b" } }));
    api.startLabRun.mockResolvedValue(runOf("RUNNING"));
    api.getLabRun.mockResolvedValue(runOf("SUCCEEDED", { runId: "run-b", agentId: "agent-2" }));
    const { result, rerender } = renderHook(({ id }: { id: string | null }) => useLabRun<Ctx>(id, "KNOCKOUT"), {
      initialProps: { id: AGENT as string | null },
    });
    await act(async () => {
      await result.current.start(REQUEST, { label: "a" });
    });
    expect(result.current.phase).toBe("polling");

    rerender({ id: "agent-2" });
    expect(result.current.context).toEqual({ label: "b" });
    await flush();
    expect(api.getLabRun).toHaveBeenCalledTimes(1);
    expect(api.getLabRun).toHaveBeenCalledWith("agent-2", "run-b");
    expect(result.current.phase).toBe("done");
    // The first agent's poll timer was cleared with the switch.
    await tick(10_000);
    expect(api.getLabRun).toHaveBeenCalledTimes(1);

    rerender({ id: null });
    expect(result.current.phase).toBe("idle");
    expect(result.current.run).toBeNull();
  });

  it("reset forgets the run, its context and the stored entry", async () => {
    api.startLabRun.mockResolvedValue(runOf("RUNNING"));
    api.getLabRun.mockResolvedValue(runOf("RUNNING"));
    const { result } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "x" });
    });
    act(() => {
      result.current.reset();
    });
    expect(result.current.phase).toBe("idle");
    expect(result.current.run).toBeNull();
    expect(result.current.context).toBeNull();
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
    await tick(10_000);
    expect(api.getLabRun).not.toHaveBeenCalled();
    // Only a lost run is stopped on reset; this one was being polled.
    expect(api.cancelLabRun).not.toHaveBeenCalled();
  });

  it("clears its timer on unmount", async () => {
    api.startLabRun.mockResolvedValue(runOf("RUNNING"));
    api.getLabRun.mockResolvedValue(runOf("RUNNING"));
    const { result, unmount } = renderHook(() => useLabRun<Ctx>(AGENT, "KNOCKOUT"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "x" });
    });
    unmount();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.getLabRun).not.toHaveBeenCalled();
    // The stored run is what lets the next mount find it again.
    expect(window.sessionStorage.getItem(STORAGE_KEY)).not.toBeNull();
  });

  it("does nothing without an agent", async () => {
    const { result } = renderHook(() => useLabRun<Ctx>(null, "KNOCKOUT"));
    await act(async () => {
      await result.current.start(REQUEST, { label: "x" });
    });
    expect(api.startLabRun).not.toHaveBeenCalled();
    expect(result.current.phase).toBe("idle");
  });
});
