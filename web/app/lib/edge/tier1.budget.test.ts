// D-9 — dynamic edge context budget (docs/rewriting-story/04-design/
// EDGE-CONTEXT-BUDGET-DESIGN.md): the budget math, the windowed prompt
// assembly's priority order, the irreducible-overflow short-circuit, and the
// router's mapping of tier1's "context-window-exceeded" to the typed hosted
// fallthrough reason (never the circuit breaker, never a tier-0 retry, and
// the edge-only refusal under EDGE_ONLY). Router tests follow the sibling
// tierRouter.policy.test.ts style: vi.resetModules() + storage clears +
// vi.doMock + a fresh dynamic import per test; capability detection is
// driven by seeding the sessionStorage cache detectCapabilities() reads.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ChatCompletionMessageParam } from "@mlc-ai/web-llm";
import {
  assembleTier1Prompt,
  assembleTier1PromptWithToolRelief,
  computeTier1PromptBudget,
  estimateTier1Tokens,
  TIER1_BUDGET_SAFETY_MARGIN,
  TIER1_OUTPUT_RESERVE_TOKENS,
} from "./tier1";
import { edgeFallbackLabel, edgeFallbackShortLabel, type ManifestModel } from "./capabilities";

const AGENT_ID = "11111111-2222-4333-8444-555555555555";

function msg(role: "user" | "assistant" | "system", content: string): ChatCompletionMessageParam {
  return { role, content } as ChatCompletionMessageParam;
}

describe("edge/tier1 — D-9 budget math", () => {
  it("estimates tokens at chars/3.5, rounded up, 0 for empty", () => {
    expect(estimateTier1Tokens("")).toBe(0);
    expect(estimateTier1Tokens("x".repeat(35))).toBe(10);
    expect(estimateTier1Tokens("x")).toBe(1); // ceil(1/3.5)
  });

  it("budget = window − output reserve − ceil(window × 8%)", () => {
    const window = 4096;
    const expected =
      window - TIER1_OUTPUT_RESERVE_TOKENS - Math.ceil(window * TIER1_BUDGET_SAFETY_MARGIN);
    expect(computeTier1PromptBudget(window)).toBe(expected);
    expect(computeTier1PromptBudget(window)).toBe(4096 - 1024 - 328);
  });

  it("honors an explicit output reserve over the default", () => {
    expect(computeTier1PromptBudget(4096, 100)).toBe(4096 - 100 - 328);
  });
});

describe("edge/tier1 — D-9 prompt assembly priority", () => {
  const system = msg("system", "s".repeat(35)); // ~10 tokens

  it("keeps system + current turn and trims history oldest-first in whole turns", () => {
    // Each history turn is ~20 tokens (user 10 + assistant 10).
    const history = [
      msg("user", "a".repeat(35)),
      msg("assistant", "b".repeat(35)),
      msg("user", "c".repeat(35)),
      msg("assistant", "d".repeat(35)),
      msg("user", "e".repeat(35)),
      msg("assistant", "f".repeat(35)),
    ];
    const currentTurn = [msg("user", "g".repeat(35))];
    // Irreducible = 10 (system) + 10 (current) = 20. Choose a window whose
    // budget fits exactly TWO more turns (2 × 20 = 40): budget must land in
    // [60, 79]. With reserve 0: budget = w − ceil(0.08 w); w = 76 → 76 − 7
    // = 69.
    const assembly = assembleTier1Prompt(system, history, currentTurn, 76, 0);

    expect(assembly.fits).toBe(true);
    // Oldest turn (a/b) dropped whole; newest two turns kept in order.
    expect(assembly.droppedHistoryMessages).toBe(2);
    expect(assembly.messages).toEqual([system, history[2], history[3], history[4], history[5], ...currentTurn]);
    // Never a dangling assistant at the front of kept history.
    expect(assembly.messages[1].role).toBe("user");
  });

  it("drops a turn whole rather than splitting it mid-turn", () => {
    const history = [
      msg("user", "a".repeat(35)),
      msg("assistant", "b".repeat(350)), // ~100-token reply — turn is ~110
      msg("user", "c".repeat(35)),
      msg("assistant", "d".repeat(35)),
    ];
    const currentTurn = [msg("user", "e".repeat(35))];
    // Budget fits the newest turn (~20) but NOT the big one (~110): the big
    // turn must vanish entirely — its user half never survives alone.
    const assembly = assembleTier1Prompt(system, history, currentTurn, 90, 0);

    expect(assembly.fits).toBe(true);
    expect(assembly.droppedHistoryMessages).toBe(2);
    expect(assembly.messages).toEqual([system, history[2], history[3], ...currentTurn]);
  });

  it("never mutates the stored history it was given", () => {
    const history = [msg("user", "a".repeat(3500)), msg("assistant", "b".repeat(3500))];
    const snapshot = history.map((m) => ({ ...m }));
    assembleTier1Prompt(system, history, [msg("user", "hi")], 64, 0);
    expect(history).toEqual(snapshot);
  });

  it("irreducible over budget short-circuits: fits=false, all history dropped", () => {
    const bigSystem = msg("system", "s".repeat(35_000)); // ~10k tokens
    const history = [msg("user", "a"), msg("assistant", "b")];
    const assembly = assembleTier1Prompt(bigSystem, history, [msg("user", "hi")], 4096);

    expect(assembly.fits).toBe(false);
    expect(assembly.droppedHistoryMessages).toBe(history.length);
    expect(assembly.estimatedPromptTokens).toBeGreaterThan(assembly.budgetTokens);
    // Informational message set is exactly the irreducible pair.
    expect(assembly.messages.map((m) => m.role)).toEqual(["system", "user"]);
  });
});

describe("edge/tier1 — tool-relief assembly (tools block over budget)", () => {
  // Window 4096 → budget 2744 tokens ≈ 9,604 chars. A ~10.5k-char tool
  // block on top of a small base prompt is the exact real-world shape that
  // made every turn overflow regardless of history ("Clear context" no-op).
  const plainSystem = msg("system", "s".repeat(350)); // ~100 tokens
  const systemWithTools = msg("system", "s".repeat(350) + "t".repeat(10_500)); // ~3100 tokens
  const history = [msg("user", "a".repeat(35)), msg("assistant", "b".repeat(35))];
  const currentTurn = [msg("user", "hello there")];

  it("drops the tool block instead of overflowing when the plain prompt fits", () => {
    const { assembly, toolsDropped } = assembleTier1PromptWithToolRelief(
      systemWithTools,
      plainSystem,
      history,
      currentTurn,
      4096
    );
    expect(toolsDropped).toBe(true);
    expect(assembly.fits).toBe(true);
    // The relieved prompt uses the plain system message, history intact.
    expect(assembly.messages[0]).toBe(plainSystem);
    expect(assembly.droppedHistoryMessages).toBe(0);
  });

  it("keeps tools when the with-tools prompt fits", () => {
    const { assembly, toolsDropped } = assembleTier1PromptWithToolRelief(
      msg("system", "s".repeat(700)), // ~200 tokens — fits with room to spare
      plainSystem,
      history,
      currentTurn,
      4096
    );
    expect(toolsDropped).toBe(false);
    expect(assembly.fits).toBe(true);
    expect(assembly.messages[0].role).toBe("system");
    expect((assembly.messages[0] as { content?: unknown }).content).toBe("s".repeat(700));
  });

  it("reports the with-tools overflow when even the plain prompt cannot fit", () => {
    const hugeMessage = [msg("user", "m".repeat(40_000))]; // ~11.5k tokens alone
    const { assembly, toolsDropped } = assembleTier1PromptWithToolRelief(
      systemWithTools,
      plainSystem,
      history,
      hugeMessage,
      4096
    );
    expect(toolsDropped).toBe(false);
    expect(assembly.fits).toBe(false);
    // Telemetry sees the full honest ask (with-tools system), not the relieved one.
    expect(assembly.messages[0]).toBe(systemWithTools);
  });
});

describe("edge — D-9 fallthrough reason labels", () => {
  it("both label maps carry a dedicated entry (not the all-tiers-failed default)", () => {
    expect(edgeFallbackLabel("context-window-exceeded")).not.toBe(edgeFallbackLabel("all-tiers-failed"));
    expect(edgeFallbackShortLabel("context-window-exceeded")).toBe("too long for on-device");
  });
});

// ---------------------------------------------------------------------------
// Router mapping (tierRouter.policy.test.ts style)
// ---------------------------------------------------------------------------

type PolicyInfo = {
  policy: "EDGE_ONLY" | "EDGE_FIRST" | "HOSTED_ONLY" | null;
  guardrailsEnabled: boolean;
  guardrailsPatternRepresentable: boolean;
};

/** Seed a WebGPU-capable device so resolveTier picks tier 1 once the mocked
 * getTier1RunModel answers a ready model. */
function seedWebGpuCaps(): void {
  sessionStorage.setItem(
    "edge.capabilities.v1",
    JSON.stringify({
      version: 1,
      detectedAt: Date.now(),
      webgpu: { available: true, maxBufferSize: 2_000_000_000, maxStorageBufferBindingSize: 1_000_000_000 },
      promptApi: { shape: "none", availability: "unavailable" },
      deviceMemoryGB: 8,
      hardwareConcurrency: 8,
      connection: { saveData: false, effectiveType: null },
    })
  );
}

const READY_MODEL: ManifestModel = {
  id: "Llama-3.2-1B-Instruct-q4f16_1-MLC",
  displayName: "On-device model — compact",
  sizeBytes: 879_040_000,
  minDeviceMemoryGB: 4,
  minMaxBufferSize: 0,
  tier: 1,
  url: null,
  sha256: null,
  speedTier: "fast",
  contextWindowSize: 4096,
};

const recordFailure = vi.fn();

function mockContextExceededDeps(policyInfo: PolicyInfo | null): void {
  vi.doMock("./agentPolicy", () => ({
    fetchEdgeAgentPolicy: vi.fn().mockResolvedValue(policyInfo),
    resetEdgeAgentPolicyCache: vi.fn(),
  }));
  vi.doMock("./telemetry", () => ({ reportEdgeTurn: vi.fn() }));
  vi.doMock("./offline", () => ({ isEdgeOffline: () => false }));
  vi.doMock("./tier0", () => ({
    TIER0_ASSET_ID: "chrome-built-in",
    Tier0Error: class Tier0Error extends Error {
      code = "engine-failed";
      detail = "mock";
    },
    createTier0Session: vi.fn().mockRejectedValue(new Error("tier0 attempted — context overflow must not retry on tier 0")),
  }));
  vi.doMock("./tier1", () => {
    class Tier1Error extends Error {
      code: string;
      detail: string | null;
      constructor(code: string, message: string, detail?: string) {
        super(message);
        this.name = "Tier1Error";
        this.code = code;
        this.detail = detail ?? null;
      }
    }
    return {
      Tier1Error,
      withReasoningNudge: (p: string | undefined) => p,
      fetchTier1Manifest: vi.fn().mockResolvedValue({ version: "test", models: [READY_MODEL] }),
      getTier1RunModel: vi.fn().mockResolvedValue(READY_MODEL),
      isTier1EngineWarm: vi.fn().mockReturnValue(false),
      createTier1Session: vi.fn().mockResolvedValue({
        prompt: vi
          .fn()
          .mockRejectedValue(
            new Tier1Error(
              "context-window-exceeded",
              "This request is too large for the on-device model's context window.",
              "irreducible prompt ~9000 tokens over budget 2744 (window 4096)"
            )
          ),
        destroy: vi.fn(),
      }),
    };
  });
  vi.doMock("../requestLifecycle", () => ({
    getCircuitBreakerState: () => ({ open: false }),
    registerCircuitBreakerPolicyOverride: vi.fn(),
    requestCircuitBreaker: { recordSuccess: vi.fn(), recordFailure },
  }));
}

describe("edge/tierRouter — D-9 context-window-exceeded mapping", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    sessionStorage.clear();
    recordFailure.mockClear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.doUnmock("./agentPolicy");
    vi.doUnmock("./telemetry");
    vi.doUnmock("./offline");
    vi.doUnmock("./tier0");
    vi.doUnmock("./tier1");
    vi.doUnmock("../requestLifecycle");
  });

  it("maps tier1's context overflow to the typed reason, skips tier 0, and never trips the breaker", async () => {
    seedWebGpuCaps();
    localStorage.setItem("edge.optIn", "1");
    mockContextExceededDeps({ policy: null, guardrailsEnabled: false, guardrailsPatternRepresentable: true });
    const { routeTurn } = await import("./tierRouter");

    const result = await routeTurn("hello", { sessionKey: AGENT_ID });

    expect(result.handled).toBe(false);
    if (result.handled === false) {
      expect(result.reason).toBe("context-window-exceeded");
      expect(result.detail).toContain("over budget");
    }
    // Input-shaped, not model-health: the tier-1 breaker must NOT count it
    // (and the tier-0 mock above throws loudly if a retry was attempted).
    expect(recordFailure).not.toHaveBeenCalled();
  });

  it("under EDGE_ONLY the overflow surfaces as the edge-only refusal, never a hosted fallthrough", async () => {
    seedWebGpuCaps();
    mockContextExceededDeps({ policy: "EDGE_ONLY", guardrailsEnabled: false, guardrailsPatternRepresentable: true });
    const { routeTurn } = await import("./tierRouter");

    const result = await routeTurn("hello", { sessionKey: AGENT_ID });

    expect(result.handled).toBe(false);
    if (result.handled === false) {
      expect(result.reason).toBe("edge-only-refused");
      // Nothing may be sent hosted for an edge-only agent.
      expect(result.scrubbedInput).toBeUndefined();
    }
    expect(recordFailure).not.toHaveBeenCalled();
  });
});
