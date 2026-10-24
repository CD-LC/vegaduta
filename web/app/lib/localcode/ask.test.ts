// localcode/ask — the properties that make this feature worth shipping, each
// with its own case below: it never starts a model download to answer a
// question, it never puts more in the citation list than the model actually
// read, it decides what fits with tier1.ts's real budgeter rather than a
// guessed chunk count, and it never reports "nothing matched" over a search
// that never ran.
//
// Style follows the sibling edge/redactor.test.ts: vi.resetModules() +
// vi.doMock + a fresh dynamic import per test, so the mocked tier modules
// never leak between cases. The tier-1 mock spreads vi.importActual, so the
// budget math (computeTier1PromptBudget / estimateTier1Tokens) and Tier1Error
// under test are the REAL ones — only model readiness, the context window and
// session creation are stubbed. A test that re-implemented chars/3.5 here
// would pass forever while the shipped budgeter drifted.

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { IndexStatus, RetrievedChunk } from "./types";

/** The excerpt headers ask.ts renders into a user prompt, read back out of the
 * prompt a session was actually handed — `path:start-end`, in prompt order.
 * This is the ground truth a citation list is checked against: what the model
 * read, not what retrieval returned. */
function excerptRefsIn(prompt: string): string[] {
  return [...prompt.matchAll(/^--- (.+):(\d+)-(\d+) ---$/gm)].map((m) => `${m[1]}:${m[2]}-${m[3]}`);
}

function citationRefs(citations: RetrievedChunk[]): string[] {
  return citations.map((c) => `${c.path}:${c.startLine}-${c.endLine}`);
}

interface EdgeMockOptions {
  /** false = no downloaded tier-1 model on this device. */
  tier1Ready?: boolean;
  contextWindowSize?: number;
  tier0?: "available" | "downloadable" | "downloading" | "unavailable";
  /** false = the deployment was built with the edge stack compiled out. */
  edgeOn?: boolean;
  chunks?: RetrievedChunk[];
  /** What getLocalIndexStatus() reports — the only way a caller can tell an
   * honest "no match" from a search that failed, since searchLocalIndex
   * swallows its own errors and returns [] for both. */
  indexStatus?: IndexStatus;
  reply?: string;
  abortOnPrompt?: boolean;
  /** Session teardown blows up after a perfectly good generation. */
  destroyThrows?: boolean;
}

interface EdgeMockState {
  /** Every user prompt handed to a session — the ground truth a citation
   * claim is checked against. */
  prompts: string[];
  tier1Sessions: number;
  tier0Sessions: number;
  destroyed: number;
  searches: string[];
  streamed: string[];
  /** Readiness probes, counted so "spent nothing" can be asserted rather
   * than assumed. */
  capabilityProbes: number;
  tier1ModelLookups: number;
  tier0Probes: number;
}

function chunk(name: string, score: number, text: string): RetrievedChunk {
  return {
    id: `${name}#0`,
    path: `src/${name}.ts`,
    startLine: 1,
    endLine: 20,
    text,
    score,
  };
}

const IDLE_STATUS: IndexStatus = { state: "idle", files: 0, chunks: 0, indexedAt: null };
const READY_STATUS: IndexStatus = { state: "ready", files: 4, chunks: 40, indexedAt: 1 };

function mockEdge(options: EdgeMockOptions = {}): EdgeMockState {
  const state: EdgeMockState = {
    prompts: [],
    tier1Sessions: 0,
    tier0Sessions: 0,
    destroyed: 0,
    searches: [],
    streamed: [],
    capabilityProbes: 0,
    tier1ModelLookups: 0,
    tier0Probes: 0,
  };

  function makeSession(ErrorClass: new (code: string, message: string) => Error) {
    return {
      async prompt(text: string, onToken?: (t: string) => void) {
        state.prompts.push(text);
        if (options.abortOnPrompt) throw new ErrorClass("aborted", "On-device reply was stopped.");
        const reply = options.reply ?? "Handled in src/alpha.ts:1-20.";
        // Deltas, not cumulative text — the contract both tier sessions keep.
        for (const word of reply.split(" ")) {
          const delta = `${word} `;
          state.streamed.push(delta);
          onToken?.(delta);
        }
        return reply;
      },
      destroy() {
        state.destroyed += 1;
        if (options.destroyThrows) throw new Error("session teardown failed");
      },
    };
  }

  vi.doMock("../edge/capabilities", () => ({
    edgeEnabled: () => options.edgeOn !== false,
    detectCapabilities: async () => {
      state.capabilityProbes += 1;
      return { webgpu: { available: true } };
    },
  }));

  vi.doMock("../edge/tier1", async () => {
    const actual = await vi.importActual<Record<string, unknown>>("../edge/tier1");
    const Tier1Error = actual.Tier1Error as new (code: string, message: string) => Error;
    return {
      ...actual,
      getTier1RunModel: vi.fn(async () => {
        state.tier1ModelLookups += 1;
        return options.tier1Ready === false ? null : { id: "local-model", sizeBytes: 1 };
      }),
      resolveTier1ContextWindow: () => options.contextWindowSize ?? 32_768,
      createTier1Session: vi.fn(async () => {
        state.tier1Sessions += 1;
        return makeSession(Tier1Error);
      }),
    };
  });

  vi.doMock("../edge/tier0", () => {
    class Tier0Error extends Error {
      code: string;
      constructor(code: string, message: string) {
        super(message);
        this.code = code;
      }
    }
    return {
      Tier0Error,
      tier0Availability: vi.fn(async () => {
        state.tier0Probes += 1;
        return options.tier0 ?? "unavailable";
      }),
      tier0Available: vi.fn(async () => (options.tier0 ?? "unavailable") !== "unavailable"),
      createTier0Session: vi.fn(async () => {
        state.tier0Sessions += 1;
        return makeSession(Tier0Error);
      }),
    };
  });

  vi.doMock("./index/indexer", () => ({
    searchLocalIndex: vi.fn(async (query: string) => {
      state.searches.push(query);
      return options.chunks ?? [chunk("alpha", 0.9, "export const alpha = 1;")];
    }),
    getLocalIndexStatus: vi.fn(() => options.indexStatus ?? IDLE_STATUS),
  }));

  return state;
}

async function importAsk() {
  return import("./ask");
}

let fetchSpy: ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.resetModules();
  localStorage.clear();
  // What this spy can and cannot prove, stated honestly: ask.ts issues no
  // request of its own, so any fetch from THIS module is a bug and fails
  // loudly here. The request the live path CAN make — tier1's unauthenticated
  // GET /api/edge/manifest, which carries no user data — is inside
  // getTier1RunModel, which is mocked in these tests and covered by tier1's
  // own. The retrieval side is mocked here too, and its own no-fetch guarantee
  // is index/indexer.ts's to keep and test. So what this file establishes is
  // "ask.ts adds no request", not "this path never fetches".
  fetchSpy = vi.fn(() => {
    throw new Error("localcode/ask made a network call");
  });
  vi.stubGlobal("fetch", fetchSpy);
});

afterEach(() => {
  vi.doUnmock("../edge/capabilities");
  vi.doUnmock("../edge/tier1");
  vi.doUnmock("../edge/tier0");
  vi.doUnmock("./index/indexer");
  vi.unstubAllGlobals();
});

describe("localcode/ask — nothing ready", () => {
  it("no downloaded model on the device: ranLocally false, and the detail says where to get one", async () => {
    const state = mockEdge({ tier1Ready: false, tier0: "unavailable" });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("where is the retry logic?");

    expect(result.ranLocally).toBe(false);
    expect(result.answer).toBe("");
    expect(result.citations).toEqual([]);
    expect(result.detail).toContain("/edge");
    expect(result.detail?.toLowerCase()).toContain("download");
    // Not a single byte of work spent on a device that cannot answer.
    expect(state.searches).toEqual([]);
    expect(state.tier1Sessions).toBe(0);
    expect(state.tier0Sessions).toBe(0);
  });

  it("a merely 'downloadable' Chrome model is NOT a ready tier — a typed question never starts a download", async () => {
    const state = mockEdge({ tier1Ready: false, tier0: "downloadable" });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("where is the retry logic?");

    expect(result.ranLocally).toBe(false);
    // createTier0Session on a "downloadable" Chrome pulls the built-in model.
    expect(state.tier0Sessions).toBe(0);
  });

  it("an edge-disabled deployment refuses before it probes anything", async () => {
    const state = mockEdge({ edgeOn: false });
    const { askLocalCode, localAskReady } = await importAsk();

    expect(await localAskReady()).toBe(false);
    const result = await askLocalCode("where is the retry logic?");

    expect(result.ranLocally).toBe(false);
    expect(result.detail).toContain("/edge");
    expect(state.capabilityProbes).toBe(0);
    expect(state.tier1ModelLookups).toBe(0);
    expect(state.searches).toEqual([]);
  });
});

describe("localcode/ask — localAskReady", () => {
  // The UI had re-implemented this probe inline and dropped the edgeEnabled()
  // clause, so the Ask button went live where every ask would be refused.
  // Exporting it is the fix; these cases are what callers may rely on.
  it("is true when a downloaded tier-1 model can serve, and starts nothing", async () => {
    const state = mockEdge();
    const { localAskReady } = await importAsk();

    expect(await localAskReady()).toBe(true);
    expect(state.tier1Sessions).toBe(0);
    expect(state.tier0Sessions).toBe(0);
    expect(state.searches).toEqual([]);
  });

  it("is true for a ready Chrome built-in model and false for a merely downloadable one", async () => {
    mockEdge({ tier1Ready: false, tier0: "available" });
    const ready = await importAsk();
    expect(await ready.localAskReady()).toBe(true);

    vi.resetModules();
    mockEdge({ tier1Ready: false, tier0: "downloadable" });
    const notReady = await importAsk();
    expect(await notReady.localAskReady()).toBe(false);
  });
});

describe("localcode/ask — an empty retrieval says WHY", () => {
  it("a failed search is reported as a failed search, never as 'nothing matched'", async () => {
    // searchLocalIndex swallows this and returns [] — the reason only exists
    // in the status, which is exactly why ask.ts has to read it.
    const state = mockEdge({
      chunks: [],
      indexStatus: {
        state: "error",
        files: 0,
        chunks: 0,
        indexedAt: null,
        detail: "The on-device embedding model isn't installed yet.",
      },
    });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("where is the retry logic?");

    expect(result.ranLocally).toBe(false);
    expect(result.detail).toContain("embedding model isn't installed");
    expect(result.detail?.toLowerCase()).not.toContain("nothing in the local index");
    expect(state.prompts).toEqual([]);
  });

  it("a built index with no close match says so, and does NOT tell the person to build an index", async () => {
    mockEdge({ chunks: [], indexStatus: READY_STATUS });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("where is the retry logic?");

    expect(result.ranLocally).toBe(false);
    expect(result.detail?.toLowerCase()).toContain("nothing in the local index");
    expect(result.detail?.toLowerCase()).not.toContain("pick a folder");
  });

  it("an index still building says wait, not 'no index'", async () => {
    mockEdge({
      chunks: [],
      indexStatus: { state: "indexing", files: 2, chunks: 9, indexedAt: null },
    });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("where is the retry logic?");

    expect(result.detail?.toLowerCase()).toContain("still building");
  });

  it("no index at all points at picking a folder, without opening a session", async () => {
    const state = mockEdge({ chunks: [], indexStatus: IDLE_STATUS });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("where is the retry logic?");

    expect(result.ranLocally).toBe(false);
    expect(result.detail).toContain("index");
    expect(result.detail?.toLowerCase()).toContain("pick a folder");
    // Readiness resolved off the cached-weights check alone; no engine was
    // ever loaded for a question there was nothing to ground.
    expect(state.tier1Sessions).toBe(0);
    expect(state.prompts).toEqual([]);
  });
});

describe("localcode/ask — citations", () => {
  it("returns exactly the chunks that went into the prompt, path and line range included", async () => {
    const chunks = [
      chunk("alpha", 0.9, "export const alpha = 1;"),
      chunk("beta", 0.5, "export const beta = 2;"),
      chunk("gamma", 0.2, "export const gamma = 3;"),
    ];
    const state = mockEdge({ chunks });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("what do the exports do?");

    expect(result.ranLocally).toBe(true);
    expect(result.citations).toHaveLength(3);
    const prompt = state.prompts[0];
    // Both directions: nothing cited that the model did not read, nothing read
    // that the citation list hides.
    expect(excerptRefsIn(prompt)).toEqual(citationRefs(result.citations));
    for (const cited of result.citations) {
      expect(prompt).toContain(cited.text);
    }
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(state.destroyed).toBe(1);
  });

  it("orders citations strongest-first regardless of the order the index returned", async () => {
    const chunks = [
      chunk("weak", 0.1, "export const weak = 1;"),
      chunk("strong", 0.99, "export const strong = 2;"),
    ];
    mockEdge({ chunks });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("which is which?");

    expect(result.citations.map((c) => c.path)).toEqual(["src/strong.ts", "src/weak.ts"]);
  });

  it("streams deltas through opts.onToken and still resolves the full answer", async () => {
    const state = mockEdge({ reply: "alpha owns the retry loop" });
    const { askLocalCode } = await importAsk();

    const seen: string[] = [];
    const result = await askLocalCode("who owns retries?", { onToken: (t) => seen.push(t) });

    expect(seen).toEqual(state.streamed);
    expect(seen.join("").trim()).toBe(result.answer);
  });

  it("searches, budgets and prompts with the same normalized question string", async () => {
    const state = mockEdge();
    const { askLocalCode } = await importAsk();

    await askLocalCode("  who owns retries?  ");

    expect(state.searches).toEqual(["who owns retries?"]);
    expect(state.prompts[0]).toContain("Question: who owns retries?\n");
  });
});

describe("localcode/ask — context budget", () => {
  const SMALL_WINDOW = 4096;
  const LARGE_WINDOW = 32_768;
  const EXCERPT_LINE = "// line\n";

  // The overflow fixture is DERIVED from the shipped budgeter, never written
  // down as a pre-computed character count. beforeAll asks the real
  // computeTier1PromptBudget what a SMALL_WINDOW model may spend, measures the
  // real estimator to convert that to characters (rather than restating its
  // ratio, which is exactly the kind of copied constant that goes stale), and
  // cuts an excerpt at a fraction of the result chosen so two fit with room to
  // spare and three overflow with room to spare. Change tier1's output
  // reserve, safety margin or estimator and this resizes with it. Nothing here
  // is trusted, either: the assertions below are what actually establish that
  // two fit and three do not.
  let BIG = "";

  beforeAll(async () => {
    const tier1 = await vi.importActual<typeof import("../edge/tier1")>("../edge/tier1");
    const probe = "x".repeat(10_000);
    const charsPerToken = probe.length / tier1.estimateTier1Tokens(probe);
    const budgetChars = tier1.computeTier1PromptBudget(SMALL_WINDOW) * charsPerToken;
    // 2.5 excerpts to a budget: two land at 80% of it, three at 120%, leaving
    // the framing text (system prompt, question, scaffolding) far too small to
    // flip either case.
    BIG = EXCERPT_LINE.repeat(Math.floor(budgetChars / 2.5 / EXCERPT_LINE.length));
  });

  it("drops the lowest-scoring chunk when the retrieved set overflows the model's window", async () => {
    const chunks = [
      chunk("alpha", 0.9, BIG),
      chunk("beta", 0.8, BIG),
      chunk("gamma", 0.1, BIG),
    ];
    const state = mockEdge({ chunks, contextWindowSize: SMALL_WINDOW });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("how does this fit together?");

    expect(result.ranLocally).toBe(true);
    expect(result.citations.map((c) => c.path)).toEqual(["src/alpha.ts", "src/beta.ts"]);
    // The citation list is only honest if the dropped chunk is absent from the
    // prompt too — the one invariant a longer-than-what-was-read list breaks.
    expect(state.prompts[0]).not.toContain("src/gamma.ts");
  });

  it("cites the POST-trim excerpt set exactly — not the wider set retrieval returned", async () => {
    const chunks = [
      chunk("alpha", 0.9, BIG),
      chunk("beta", 0.8, BIG),
      chunk("gamma", 0.1, BIG),
    ];
    const state = mockEdge({ chunks, contextWindowSize: SMALL_WINDOW });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("how does this fit together?");

    expect(result.ranLocally).toBe(true);
    // Set equality in BOTH directions, order included, against the prompt the
    // session was handed. A citation with no excerpt behind it invites opening
    // a file the answer was never grounded in; an excerpt with no citation
    // hides what it WAS grounded in. Either one breaks the contract.
    expect(excerptRefsIn(state.prompts[0])).toEqual(citationRefs(result.citations));
    // And trimming actually happened here, or the equality above proves only
    // that nothing was dropped.
    expect(result.citations.length).toBeLessThan(chunks.length);
    expect(result.citations.length).toBeGreaterThan(0);
  });

  it("keeps all three of the same chunks on a model with a large window", async () => {
    const chunks = [
      chunk("alpha", 0.9, BIG),
      chunk("beta", 0.8, BIG),
      chunk("gamma", 0.1, BIG),
    ];
    const state = mockEdge({ chunks, contextWindowSize: LARGE_WINDOW });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("how does this fit together?");

    expect(result.citations).toHaveLength(3);
    // Same invariant when nothing is trimmed: what fits is what is cited.
    expect(excerptRefsIn(state.prompts[0])).toEqual(citationRefs(result.citations));
  });

  it("refuses rather than prompting with no code at all when even the top match overflows", async () => {
    const state = mockEdge({
      chunks: [chunk("huge", 0.9, EXCERPT_LINE.repeat(20_000))],
      contextWindowSize: SMALL_WINDOW,
    });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("explain this file");

    expect(result.ranLocally).toBe(false);
    expect(result.citations).toEqual([]);
    expect(state.prompts).toEqual([]);
    expect(result.detail).toContain("/edge");
  });
});

describe("localcode/ask — failure shapes", () => {
  it("an aborted generation reports as stopped, under the exported detail callers match on", async () => {
    const state = mockEdge({ abortOnPrompt: true });
    const { askLocalCode, LOCAL_ANSWER_STOPPED_DETAIL } = await importAsk();

    const result = await askLocalCode("what broke?");

    expect(result.ranLocally).toBe(false);
    expect(result.answer).toBe("");
    // Exported constant, not prose-matching: the UI has to be able to tell
    // "you stopped this" from "it failed" to decide whether to keep the
    // partial text it accumulated from onToken.
    expect(result.detail).toBe(LOCAL_ANSWER_STOPPED_DETAIL);
    // The short-lived session is destroyed on the failure path too.
    expect(state.destroyed).toBe(1);
  });

  it("an empty model reply is a failure, never a local answer that happens to be blank", async () => {
    mockEdge({ reply: "   " });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("what broke?");

    expect(result.ranLocally).toBe(false);
    expect(result.answer).toBe("");
    expect(result.citations).toEqual([]);
  });

  it("a teardown that throws does not swallow the answer that was already generated", async () => {
    const state = mockEdge({ destroyThrows: true, reply: "alpha owns it" });
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("who owns it?");

    expect(state.destroyed).toBe(1);
    expect(result.ranLocally).toBe(true);
    expect(result.answer).toBe("alpha owns it");
    expect(result.citations).toHaveLength(1);
  });

  it("an already-aborted signal spends nothing: no capability probe, no search, no model", async () => {
    const state = mockEdge();
    const { askLocalCode, LOCAL_ANSWER_STOPPED_DETAIL } = await importAsk();

    const controller = new AbortController();
    controller.abort();
    const result = await askLocalCode("what broke?", { signal: controller.signal });

    expect(result.detail).toBe(LOCAL_ANSWER_STOPPED_DETAIL);
    // Readiness costs a capability probe and (on a real device) a manifest
    // GET, so the signal is checked before it, not after.
    expect(state.capabilityProbes).toBe(0);
    expect(state.tier1ModelLookups).toBe(0);
    expect(state.searches).toEqual([]);
    expect(state.prompts).toEqual([]);
  });

  it("an empty question is rejected before any device work", async () => {
    const state = mockEdge();
    const { askLocalCode } = await importAsk();

    const result = await askLocalCode("   ");

    expect(result.ranLocally).toBe(false);
    expect(state.tier1Sessions).toBe(0);
    expect(state.searches).toEqual([]);
  });
});

describe("localcode/ask — the instruction comes from the registry key", () => {
  const SHIPPED =
    "You answer questions about a codebase using only the excerpts you are " +
    "given. Each excerpt is labelled with its file path and line range. Cite " +
    "the path and line range for every claim you make. If the excerpts do not " +
    "contain the answer, say so plainly instead of guessing — the reader can " +
    "see the same excerpts you can, so an invented file name is worse than a " +
    "short admission that it was not in what you were shown.";

  async function systemPromptHandedToTheModel(): Promise<string> {
    const tier1 = await import("../edge/tier1");
    const calls = vi.mocked(tier1.createTier1Session).mock.calls;
    return (calls[0][1] as { systemPrompt: string }).systemPrompt;
  }

  it("is the shipped wording, byte for byte, when nothing was fetched", async () => {
    mockEdge();
    const { askLocalCode } = await importAsk();
    await askLocalCode("where is alpha?");
    expect(await systemPromptHandedToTheModel()).toBe(SHIPPED);
  });

  it("uses the wording the edge runtime already holds, and still makes no request of its own", async () => {
    mockEdge();
    vi.doMock("../auth", () => ({ auth: { getToken: vi.fn(async () => "tok") } }));
    const bundleFetch = vi.fn<() => Promise<unknown>>(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ prompts: { "edge.localcode_ask.system": { text: "Answer from the excerpts only.", enabled: true } } }),
      })
    );
    vi.stubGlobal("fetch", bundleFetch);
    const edgePrompts = await import("../edge/edgePrompts");
    await edgePrompts.primeEdgePrompts(); // what an earlier edge turn did
    const callsBeforeAsk = bundleFetch.mock.calls.length;

    const { askLocalCode } = await importAsk();
    await askLocalCode("where is alpha?");

    expect(await systemPromptHandedToTheModel()).toBe("Answer from the excerpts only.");
    expect(bundleFetch.mock.calls.length).toBe(callsBeforeAsk);
    expect(callsBeforeAsk).toBe(1);
    vi.doUnmock("../auth");
  });
});
