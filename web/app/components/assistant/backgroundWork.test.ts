import { beforeEach, describe, expect, it } from "vitest";
import {
  backgroundTasks,
  forgetThreadTasks,
  handoffTurns,
  isJobThread,
  isLivePhase,
  jobPhase,
  jobThreadId,
  jobThreadTurns,
  recordBackgroundTask,
  taskForJob,
  type BackgroundTask,
} from "./backgroundWork";

const task = (over: Partial<BackgroundTask> = {}): BackgroundTask => ({
  jobId: "j1",
  agentId: "a1",
  title: "Summarise the contracts folder",
  threadId: "s1",
  startedAt: 1000,
  ...over,
});

describe("background task store", () => {
  beforeEach(() => window.localStorage.clear());

  it("remembers which thread a job came from, newest first, one row per job", () => {
    recordBackgroundTask("u", task());
    recordBackgroundTask("u", task({ jobId: "j2", startedAt: 2000 }));
    recordBackgroundTask("u", task({ title: "renamed" }));
    expect(backgroundTasks("u").map((t) => t.jobId)).toEqual(["j1", "j2"]);
    expect(taskForJob("u", "j1")?.title).toBe("renamed");
    expect(taskForJob("other", "j1")).toBeNull();
  });

  it("forgets the tasks of a removed thread only", () => {
    recordBackgroundTask("u", task());
    recordBackgroundTask("u", task({ jobId: "j2", threadId: "s2" }));
    forgetThreadTasks("u", "s1");
    expect(backgroundTasks("u").map((t) => t.jobId)).toEqual(["j2"]);
  });

  it("survives garbage in storage", () => {
    window.localStorage.setItem("vd.assistant.background.u", "{not json");
    expect(backgroundTasks("u")).toEqual([]);
    window.localStorage.setItem("vd.assistant.background.u", JSON.stringify([{ nope: 1 }, task()]));
    expect(backgroundTasks("u")).toHaveLength(1);
  });

  it("brings the card back, and a hand-off-only thread restores as request plus card", () => {
    const cards = handoffTurns([task({ startedAt: 5 }), task({ jobId: "j0", startedAt: 1 })]);
    expect(cards.map((c) => c.handoff?.id)).toEqual(["j0", "j1"]);
    expect(cards[1].handoff?.href).toBe("/jobs/a1/j1");
    const thread = jobThreadTurns(task());
    expect(thread.map((t) => t.role)).toEqual(["user", "assistant"]);
    expect(thread[0].content).toBe("Summarise the contracts folder");
  });

  it("names a thread that began in the background after its job", () => {
    expect(jobThreadId("j9")).toBe("job:j9");
    expect(isJobThread("job:j9")).toBe(true);
    expect(isJobThread("session-1")).toBe(false);
    expect(isJobThread(null)).toBe(false);
  });
});

describe("jobPhase", () => {
  it("maps every server status to a person-facing phase", () => {
    expect(jobPhase("QUEUED")).toBe("queued");
    expect(jobPhase("RUNNING")).toBe("running");
    expect(jobPhase("AWAITING_APPROVAL")).toBe("waiting");
    expect(jobPhase("SUCCEEDED")).toBe("done");
    expect(jobPhase("FAILED")).toBe("failed");
    expect(jobPhase("CANCELLED")).toBe("stopped");
    expect(jobPhase("SOMETHING_NEW")).toBe("failed");
  });

  it("counts queued, running and parked jobs as live", () => {
    expect(["queued", "running", "waiting"].every((p) => isLivePhase(p as never))).toBe(true);
    expect(["done", "failed", "stopped"].some((p) => isLivePhase(p as never))).toBe(false);
  });
});
