// Edge-first foundation pass (2026-08-07): tests for the pure/testable
// pieces AskAiHelper.tsx's edge attempt is built from, without mounting the
// component - shouldAttemptEdgeDraft()'s precondition combination and
// buildAskAiEdgePrompt()'s prompt shape. Mirrors the sibling lib/edge test
// style (see whisper.test.ts/kokoro.test.ts): vi.resetModules() + a fresh
// dynamic import per test so each test's env/mock state is isolated.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const evaluateFeatureFlag = vi.fn();
const getEffectiveEdgeOptIn = vi.fn();

vi.mock("../lib/api", () => ({
  api: { evaluateFeatureFlag: (...args: unknown[]) => evaluateFeatureFlag(...args) },
}));

vi.mock("./agents/IntelligenceSourceControl", () => ({
  getEffectiveEdgeOptIn: () => getEffectiveEdgeOptIn(),
}));

describe("AskAiHelper edge helpers", () => {
  beforeEach(() => {
    vi.resetModules();
    evaluateFeatureFlag.mockReset();
    getEffectiveEdgeOptIn.mockReset();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "";
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe("shouldAttemptEdgeDraft()", () => {
    it("is false when the edge build flag is off, without even checking the tenant flag or opt-in", async () => {
      const { shouldAttemptEdgeDraft } = await import("./AskAiHelper");
      expect(await shouldAttemptEdgeDraft()).toBe(false);
      expect(evaluateFeatureFlag).not.toHaveBeenCalled();
      expect(getEffectiveEdgeOptIn).not.toHaveBeenCalled();
    });

    it("is true only when the flag build is on, the tenant flag evaluates to treatment, AND opt-in is effective", async () => {
      process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
      evaluateFeatureFlag.mockResolvedValue({ key: "edge.ask-ai", variant: "treatment" });
      getEffectiveEdgeOptIn.mockResolvedValue(true);
      const { shouldAttemptEdgeDraft, ASK_AI_EDGE_FLAG_KEY } = await import("./AskAiHelper");
      expect(await shouldAttemptEdgeDraft()).toBe(true);
      expect(evaluateFeatureFlag).toHaveBeenCalledWith(ASK_AI_EDGE_FLAG_KEY);
    });

    it("is false when the tenant flag evaluates to control, even with opt-in true", async () => {
      process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
      evaluateFeatureFlag.mockResolvedValue({ key: "edge.ask-ai", variant: "control" });
      getEffectiveEdgeOptIn.mockResolvedValue(true);
      const { shouldAttemptEdgeDraft } = await import("./AskAiHelper");
      expect(await shouldAttemptEdgeDraft()).toBe(false);
    });

    it("is false when the tenant flag is treatment but the device isn't effectively opted in", async () => {
      process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
      evaluateFeatureFlag.mockResolvedValue({ key: "edge.ask-ai", variant: "treatment" });
      getEffectiveEdgeOptIn.mockResolvedValue(false);
      const { shouldAttemptEdgeDraft } = await import("./AskAiHelper");
      expect(await shouldAttemptEdgeDraft()).toBe(false);
    });

    it("fails closed (never attempt edge) rather than throwing when the flag check errors", async () => {
      process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
      evaluateFeatureFlag.mockRejectedValue(new Error("network error"));
      getEffectiveEdgeOptIn.mockResolvedValue(true);
      const { shouldAttemptEdgeDraft } = await import("./AskAiHelper");
      await expect(shouldAttemptEdgeDraft()).resolves.toBe(false);
    });
  });

  describe("buildAskAiEdgePrompt()", () => {
    it("matches AssistService.draft()'s field/instruction shape, with no current draft", async () => {
      const { buildAskAiEdgePrompt } = await import("./AskAiHelper");
      const { system, user } = buildAskAiEdgePrompt({
        fieldLabel: "Agent system prompt",
        instruction: "Make it friendlier",
      });
      expect(system).toContain("Reply with ONLY the finished text");
      expect(user).toBe("Field: Agent system prompt\nInstruction: Make it friendlier");
    });

    it("includes the current draft only when it has real content", async () => {
      const { buildAskAiEdgePrompt } = await import("./AskAiHelper");
      const withDraft = buildAskAiEdgePrompt({
        fieldLabel: "Workflow description",
        instruction: "Tighten this up",
        currentValue: "Handles refunds.",
      });
      expect(withDraft.user).toBe(
        "Field: Workflow description\nInstruction: Tighten this up\nCurrent draft:\nHandles refunds."
      );

      const blankDraft = buildAskAiEdgePrompt({
        fieldLabel: "Workflow description",
        instruction: "Tighten this up",
        currentValue: "   ",
      });
      expect(blankDraft.user).not.toContain("Current draft");
    });

    it("takes the system wording from the edge prompt registry key, so an override reaches the on-device draft", async () => {
      vi.doMock("../lib/edge/edgePrompts", () => ({
        edgePrompt: (key: string) => (key === "edge.ask_ai.system" ? "Write the field." : "?"),
        primeEdgePrompts: async () => {},
      }));
      const { buildAskAiEdgePrompt } = await import("./AskAiHelper");
      expect(buildAskAiEdgePrompt({ fieldLabel: "F", instruction: "I" }).system).toBe("Write the field.");
      vi.doUnmock("../lib/edge/edgePrompts");
    });
  });
});
