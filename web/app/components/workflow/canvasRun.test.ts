import { describe, expect, it, vi } from "vitest";
import { canRunDraft, startCanvasRun } from "./canvasRun";

describe("canRunDraft", () => {
  it("allows editors, hides the Run controls from runners and viewers", () => {
    expect(canRunDraft({ accessLevel: "EDITOR" })).toBe(true);
    expect(canRunDraft({ accessLevel: "RUNNER" })).toBe(false);
    expect(canRunDraft({ accessLevel: "VIEWER" })).toBe(false);
  });

  it("keeps the controls when the level is unknown (older core, workflow not loaded yet)", () => {
    expect(canRunDraft({})).toBe(true);
    expect(canRunDraft({ accessLevel: null })).toBe(true);
    expect(canRunDraft(null)).toBe(true);
    expect(canRunDraft(undefined)).toBe(true);
  });
});

describe("startCanvasRun", () => {
  it("starts a background run in draft mode", async () => {
    const runWorkflow = vi.fn().mockResolvedValue({ id: "r1" });
    await startCanvasRun(runWorkflow, "wf1", "in", "client-1");
    expect(runWorkflow).toHaveBeenCalledWith("wf1", "in", undefined, "client-1", true, "draft");
  });

  it("asks core to simulate sends only for a test run", async () => {
    const runWorkflow = vi.fn().mockResolvedValue({ id: "r2" });
    await startCanvasRun(runWorkflow, "wf1", "in", "client-2", true);
    expect(runWorkflow).toHaveBeenCalledWith("wf1", "in", undefined, "client-2", true, "draft", true);
  });
});
