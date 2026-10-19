// agentkit/edgeModel — the adapter's contract with lib/edge, against mocks.
//
// No engine runs here. What is pinned is what the adapter ASKS of tier 1: a
// plain-generation session (disableTools) so tier 1's own tool loop never
// claims agentkit's calls, the <tool_call> delimiter that only makes sense
// with that option, a fresh session per step that is always destroyed, and
// failures turned into typed replies instead of exceptions.

import { beforeEach, describe, expect, it, vi } from "vitest";

const createTier1Session = vi.fn();
const getTier1RunModel = vi.fn();
const createTier0Session = vi.fn();
const tier0Availability = vi.fn();
const edgeEnabled = vi.fn();

// vi.mock factories are hoisted above everything else in this file, so the
// error classes they hand out have to be hoisted with them.
const { FakeTier0Error, FakeTier1Error } = vi.hoisted(() => {
  class CodedError extends Error {
    code: string;
    constructor(code: string, message: string) {
      super(message);
      this.code = code;
    }
  }
  return { FakeTier0Error: class extends CodedError {}, FakeTier1Error: class extends CodedError {} };
});

vi.mock("../edge/capabilities", () => ({
  edgeEnabled: () => edgeEnabled(),
  detectCapabilities: async () => ({ webgpu: true }),
}));
vi.mock("../edge/tier0", () => ({
  Tier0Error: FakeTier0Error,
  createTier0Session: (...args: unknown[]) => createTier0Session(...args),
  tier0Availability: () => tier0Availability(),
}));
vi.mock("../edge/tier1", () => ({
  Tier1Error: FakeTier1Error,
  WEBLLM_FALLBACK_CONTEXT_WINDOW: 4096,
  computeTier1PromptBudget: (window: number) => Math.floor(window * 0.67),
  createTier1Session: (...args: unknown[]) => createTier1Session(...args),
  getTier1RunModel: (...args: unknown[]) => getTier1RunModel(...args),
  resolveTier1ContextWindow: () => 8192,
}));

import { TIER1_CALL_TAG, resolveEdgeAgentModel } from "./edgeModel";
import { AGENT_CALL_TAG } from "./protocol";

function session(reply: string | Error) {
  const destroy = vi.fn();
  const prompt = vi.fn(async (user: string): Promise<string> => {
    void user;
    if (reply instanceof Error) throw reply;
    return reply;
  });
  return { prompt, destroy };
}

async function readyModel() {
  const resolved = await resolveEdgeAgentModel();
  if (resolved.ok !== true) throw new Error("expected a model");
  return resolved.model;
}

describe("agentkit/edgeModel", () => {
  beforeEach(() => {
    createTier1Session.mockReset();
    getTier1RunModel.mockReset();
    createTier0Session.mockReset();
    tier0Availability.mockReset();
    edgeEnabled.mockReset();
    edgeEnabled.mockReturnValue(true);
    tier0Availability.mockResolvedValue("unavailable");
  });

  it("opens tier 1 as a plain-generation session and asks for <tool_call>", async () => {
    getTier1RunModel.mockResolvedValue({ id: "Hermes-3-Llama-3.2-3B" });
    const s = session('<tool_call>{"name": "list_dir", "arguments": {"path": "."}}</tool_call>');
    createTier1Session.mockResolvedValue(s);

    const model = await readyModel();
    expect(model.id).toBe("Hermes-3-Llama-3.2-3B");
    expect(model.contextWindow).toBe(8192);
    expect(TIER1_CALL_TAG).toBe("tool_call");
    expect(model.callTag).toBe("tool_call");

    const reply = await model.complete("SYSTEM", "USER");
    // The envelope comes back verbatim for agentkit's own parser.
    expect(reply).toEqual({ ok: true, text: '<tool_call>{"name": "list_dir", "arguments": {"path": "."}}</tool_call>' });
    expect(createTier1Session).toHaveBeenCalledTimes(1);
    expect(createTier1Session.mock.calls[0][1]).toEqual({ systemPrompt: "SYSTEM", turnComplexity: "complex", disableTools: true });
    expect(s.prompt.mock.calls[0][0]).toBe("USER");
    expect(s.destroy).toHaveBeenCalledTimes(1);
  });

  it("is stateless: a fresh session per step, destroyed even when the step fails", async () => {
    getTier1RunModel.mockResolvedValue({ id: "m" });
    const ok = session("fine");
    const bad = session(new Error("raw engine text that may quote the prompt"));
    createTier1Session.mockResolvedValueOnce(ok).mockResolvedValueOnce(bad);
    const model = await readyModel();

    expect(await model.complete("s", "u1")).toEqual({ ok: true, text: "fine" });
    const failed = await model.complete("s", "u2");
    expect(failed).toMatchObject({ ok: false, reason: "failed" });
    // A raw engine error is never shown: it can quote the person's source.
    expect((failed as { detail: string }).detail).not.toContain("raw engine text");
    expect(createTier1Session).toHaveBeenCalledTimes(2);
    expect(ok.destroy).toHaveBeenCalledTimes(1);
    expect(bad.destroy).toHaveBeenCalledTimes(1);
  });

  it("maps abort, an over-size prompt and an empty reply to typed failures — never a throw", async () => {
    getTier1RunModel.mockResolvedValue({ id: "m" });
    const model = await readyModel();

    const aborted = new AbortController();
    aborted.abort();
    expect(await model.complete("s", "u", aborted.signal)).toMatchObject({ ok: false, reason: "aborted" });
    expect(createTier1Session).not.toHaveBeenCalled();

    createTier1Session.mockResolvedValueOnce(session(new FakeTier1Error("context-window-exceeded", "too big")));
    expect(await model.complete("s", "u")).toMatchObject({ ok: false, reason: "too-large" });

    createTier1Session.mockResolvedValueOnce(session("   "));
    expect(await model.complete("s", "u")).toMatchObject({ ok: false, reason: "empty" });

    createTier1Session.mockRejectedValueOnce(new FakeTier1Error("load-failed", "The model could not be loaded."));
    expect(await model.complete("s", "u")).toEqual({ ok: false, reason: "failed", detail: "The model could not be loaded." });
  });

  it("falls back to Chrome's built-in model with the neutral tag, and says so when there is nothing", async () => {
    getTier1RunModel.mockResolvedValue(null);
    tier0Availability.mockResolvedValue("available");
    const s = session("hello");
    createTier0Session.mockResolvedValue(s);
    const model = await readyModel();
    expect(model.id).toBe("chrome-built-in");
    expect(model.callTag).toBe(AGENT_CALL_TAG);
    expect(await model.complete("SYSTEM", "USER")).toEqual({ ok: true, text: "hello" });
    expect(createTier0Session).toHaveBeenCalledWith("SYSTEM");
    expect(s.destroy).toHaveBeenCalledTimes(1);

    tier0Availability.mockResolvedValue("unavailable");
    expect(await resolveEdgeAgentModel()).toMatchObject({ ok: false, reason: "no-model" });
  });

  it("answers edge-off and a throwing probe without throwing", async () => {
    edgeEnabled.mockReturnValue(false);
    expect(await resolveEdgeAgentModel()).toMatchObject({ ok: false, reason: "edge-off" });
    edgeEnabled.mockReturnValue(true);
    getTier1RunModel.mockRejectedValue(new Error("indexedDB blocked"));
    expect(await resolveEdgeAgentModel()).toMatchObject({ ok: false, reason: "probe-failed" });
  });
});
