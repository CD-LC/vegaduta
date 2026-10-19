// Proof for the Phase-5 tool-protocol change in tier1.ts (2026-09-12):
//
//  1. A tool-active turn now asks the engine for TRIGGERED-TAG constrained
//     decoding (response_format.type === "structural_tag"), not an
//     unconstrained stream and not a whole-response JSON schema.
//  2. The loop parses BOTH the new <tool_call>…</tool_call> shape (compact and
//     Hermes' newline-padded form) and the bare-JSON envelope this file used
//     before, so a model that ignores the new instruction does not regress.
//  3. Neither shape's envelope text reaches the person: streamCompletion's
//     hold-back must cover a reply that opens with "<" exactly as it already
//     covered one opening with "{".
//
// Harness copied deliberately from tier1.concurrency.test.ts (same fake
// engine, same cache/manifest stubs) so the two files fail the same way if the
// module's load path changes. Server tools are stubbed 404 throughout: the
// four local tools in localTools.ts are enough to make a turn tool-active, and
// keeping the server out means nothing here depends on an account.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const MODEL_A = "Hermes-2-Pro-Llama-3-8B-q4f16_1-MLC";

function weightUrls(modelId: string) {
  const base = `https://huggingface.co/mlc-ai/${modelId}/resolve/main/`;
  return { base, indexUrl: `${base}ndarray-cache.json`, weightUrl: `${base}params_shard_0.bin` };
}

class FakeCache {
  async keys() {
    const { indexUrl, weightUrl } = weightUrls(MODEL_A);
    return [{ url: indexUrl }, { url: weightUrl }];
  }
  async match(url: string) {
    const { indexUrl } = weightUrls(MODEL_A);
    if (url === indexUrl) {
      return { json: async () => ({ records: [{ dataPath: "params_shard_0.bin" }] }) };
    }
    return undefined;
  }
}

function buildCaps() {
  return {
    version: 1 as const,
    detectedAt: 0,
    webgpu: { available: true, maxBufferSize: 1_000_000_000, maxStorageBufferBindingSize: 1_000_000_000 },
    promptApi: { shape: "none" as const, availability: "unavailable" as const },
    deviceMemoryGB: 8,
    hardwareConcurrency: 8,
    connection: { saveData: false, effectiveType: "4g" },
  };
}

type FakeChatStream = {
  [Symbol.asyncIterator](): AsyncGenerator<{ choices: { delta: { content: string } }[] }, void, unknown>;
};
type CreateOpts = { stream?: boolean; response_format?: { type?: string; structural_tag?: unknown } };

/** One stream whose whole content is `text`, delivered one character at a
 * time. Character-by-character on purpose: it is the only way to exercise the
 * "lead is still growing into the opening tag" branch of the hold-back, which
 * a single-chunk mock would jump straight past. */
function charStream(text: string): FakeChatStream {
  return {
    async *[Symbol.asyncIterator]() {
      for (const ch of text) yield { choices: [{ delta: { content: ch } }] };
    },
  };
}

function makeFakeEngine() {
  const calls: CreateOpts[] = [];
  const engine = {
    setInitProgressCallback: vi.fn(),
    interruptGenerate: vi.fn(),
    unload: vi.fn(async () => {}),
    reload: vi.fn(async () => {}),
    chat: {
      completions: {
        // Typed to the real per-test return shape so mockImplementation below
        // satisfies it — the default body only ever throws, which on its own
        // would infer Promise<never>.
        create: vi.fn<(opts: CreateOpts) => Promise<FakeChatStream>>(async () => {
          throw new Error("override create() per-test");
        }),
      },
    },
    __calls: calls,
  };
  return engine;
}

/** Queue of replies, one per streamCompletion call, recording each request. */
function respondInOrder(engine: ReturnType<typeof makeFakeEngine>, replies: string[]) {
  let i = 0;
  engine.chat.completions.create.mockImplementation(async (opts: CreateOpts) => {
    engine.__calls.push(opts);
    const reply = replies[Math.min(i, replies.length - 1)];
    i += 1;
    return charStream(reply);
  });
}

describe("edge/tier1 tool-call protocol (triggered tags + both envelope shapes)", () => {
  let fakeEngine: ReturnType<typeof makeFakeEngine>;

  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    fakeEngine = makeFakeEngine();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";

    (globalThis as any).caches = {
      has: async () => true,
      open: async () => new FakeCache(),
    };

    (globalThis as any).fetch = vi.fn(async (url: string) => {
      if (String(url).includes("/api/edge/manifest")) {
        return {
          ok: true,
          json: async () => ({
            version: "1",
            models: [
              {
                id: MODEL_A,
                displayName: "Model A",
                sizeBytes: 1_000_000,
                minDeviceMemoryGB: 0,
                minMaxBufferSize: 0,
                tier: 1,
                speedTier: "fast",
              },
            ],
          }),
        };
      }
      // Server tool surface absent — local tools only.
      return { ok: false, status: 404, json: async () => ({}) };
    });

    vi.doMock("@mlc-ai/web-llm", () => ({
      CreateMLCEngine: vi.fn(async () => fakeEngine),
    }));

    localStorage.setItem(`edge.tier1.cached.${MODEL_A}`, "1");
  });

  afterEach(() => {
    delete (globalThis as any).caches;
    vi.unstubAllGlobals();
    vi.doUnmock("@mlc-ai/web-llm");
  });

  async function session() {
    const { createTier1Session } = await import("./tier1");
    const { setModelOverride } = await import("./capabilities");
    setModelOverride(MODEL_A);
    return createTier1Session(buildCaps());
  }

  it("constrains a tool-active turn with a triggered structural tag, not a whole-response schema", async () => {
    const s = await session();
    respondInOrder(fakeEngine, ["No tool needed."]);
    await s.prompt("hello");

    expect(fakeEngine.__calls.length).toBeGreaterThan(0);
    const first = fakeEngine.__calls[0];
    expect(first.stream).toBe(true);
    expect(first.response_format?.type).toBe("structural_tag");
    // The trigger is the opening delimiter — that is what makes this a
    // triggered constraint rather than a constraint on the whole reply.
    const tag = first.response_format?.structural_tag as {
      format?: { type?: string; triggers?: string[]; tags?: unknown[] };
    };
    expect(tag.format?.type).toBe("triggered_tags");
    expect(tag.format?.triggers).toEqual(["<tool_call>"]);
    // Two tags: compact and Hermes' newline-padded form (xgrammar matches the
    // delimiters exactly, so one tag cannot cover both).
    expect(tag.format?.tags).toHaveLength(2);
  });

  it("leaves an ordinary prose reply unconstrained end to end (streams live, no tool round)", async () => {
    const s = await session();
    respondInOrder(fakeEngine, ["Nothing to call here."]);
    const seen: string[] = [];
    const out = await s.prompt("just chat", (d: string) => seen.push(d));

    expect(out).toBe("Nothing to call here.");
    expect(seen.join("")).toBe("Nothing to call here.");
    expect(fakeEngine.__calls).toHaveLength(1); // one round, no tool dispatch
  });

  const toolBody = '{"name": "local_calculate", "arguments": {"expression": "2+2"}}';

  const shapes: Array<[string, string]> = [
    ["compact tag", `<tool_call>${toolBody}</tool_call>`],
    ["hermes newline tag", `<tool_call>\n${toolBody}\n</tool_call>`],
    ["legacy bare envelope", '{"tool_call": {"name": "local_calculate", "arguments": {"expression": "2+2"}}}'],
    ["fenced tag", "```\n<tool_call>" + toolBody + "</tool_call>\n```"],
  ];

  for (const [label, reply] of shapes) {
    it(`dispatches the tool from a ${label} and never shows the envelope to the person`, async () => {
      const s = await session();
      respondInOrder(fakeEngine, [reply, "2 + 2 is 4."]);
      const seen: string[] = [];
      const toolEvents: Array<{ name: string; phase: string }> = [];
      const out = await s.prompt("what is 2+2", (d: string) => seen.push(d), undefined, (e: any) =>
        toolEvents.push({ name: e.name, phase: e.phase })
      );

      expect(out).toBe("2 + 2 is 4.");
      expect(toolEvents.some((e) => e.name === "local_calculate" && e.phase === "calling")).toBe(true);
      // Two generations: the tool round and the answer round.
      expect(fakeEngine.__calls).toHaveLength(2);
      // The hold-back is the point: not one character of the envelope — the
      // opening "<" or "{" included — may have been streamed out.
      const streamed = seen.join("");
      expect(streamed).not.toContain("<tool_call>");
      expect(streamed).not.toContain("tool_call");
      expect(streamed).not.toContain("{");
      expect(streamed).toBe("2 + 2 is 4.");
    });
  }

  it("still refuses to treat ordinary JSON with a name key as a tool call", async () => {
    // The bare {name, arguments} shape is accepted (Hermes' tag body), which
    // is why it has to be the FULL shape: a model answering a request for JSON
    // must not be read as proposing a tool.
    const s = await session();
    respondInOrder(fakeEngine, ['{"name": "Ada", "role": "engineer"}']);
    const out = await s.prompt("give me a person as JSON");
    expect(out).toBe('{"name": "Ada", "role": "engineer"}');
    expect(fakeEngine.__calls).toHaveLength(1); // no tool round was entered
  });

  // ---- disableTools (agentkit contract, 2026-09-19) ------------------------

  describe("disableTools: a plain-generation session", () => {
    async function plainSession() {
      const { createTier1Session } = await import("./tier1");
      const { setModelOverride } = await import("./capabilities");
      setModelOverride(MODEL_A);
      return createTier1Session(buildCaps(), { disableTools: true, systemPrompt: "You are terse." });
    }

    function systemPromptOf(call: CreateOpts): string {
      const messages = (call as { messages?: Array<{ role: string; content: string }> }).messages || [];
      return messages.find((m) => m.role === "system")?.content || "";
    }

    it("control: the same harness WITHOUT the flag does put a tool block in the system prompt", async () => {
      const s = await session();
      respondInOrder(fakeEngine, ["hi"]);
      await s.prompt("hello");
      expect(systemPromptOf(fakeEngine.__calls[0])).toContain("<tool_call>");
    });

    it("puts no tool block in the system prompt and sets no response_format", async () => {
      const s = await plainSession();
      respondInOrder(fakeEngine, ["hi"]);
      await s.prompt("hello");

      expect(fakeEngine.__calls).toHaveLength(1);
      const first = fakeEngine.__calls[0];
      expect(first.response_format).toBeUndefined();
      const system = systemPromptOf(first);
      expect(system).toContain("You are terse.");
      expect(system).not.toContain("<tool_call>");
      expect(system).not.toContain("local_calculate");
    });

    it("never calls listLocalTools, fetchEdgeTools or fetchEdgeMemory (and does without the flag)", async () => {
      // Spies on the three lookups themselves, with the server surface made
      // reachable — asserting on fetch URLs alone would pass vacuously here,
      // because a signed-out harness never reaches the network for them anyway.
      const listLocalTools = vi.fn(() => []);
      const fetchEdgeTools = vi.fn(async () => null);
      const fetchEdgeMemory = vi.fn(async () => null);
      vi.doMock("./localTools", async () => ({
        ...(await vi.importActual<typeof import("./localTools")>("./localTools")),
        listLocalTools,
      }));
      vi.doMock("./toolBridge", async () => ({
        ...(await vi.importActual<typeof import("./toolBridge")>("./toolBridge")),
        fetchEdgeTools,
        canUseServerTools: () => true,
        edgeToolsUnavailable: () => false,
      }));
      vi.doMock("./edgeMemory", async () => ({
        ...(await vi.importActual<typeof import("./edgeMemory")>("./edgeMemory")),
        fetchEdgeMemory,
      }));
      try {
        const { createTier1Session } = await import("./tier1");
        const { setModelOverride } = await import("./capabilities");
        setModelOverride(MODEL_A);
        respondInOrder(fakeEngine, ["hi"]);

        const plain = await createTier1Session(buildCaps(), { disableTools: true, sessionKey: "agent-1" });
        await plain.prompt("hello");
        expect(listLocalTools).not.toHaveBeenCalled();
        expect(fetchEdgeTools).not.toHaveBeenCalled();
        expect(fetchEdgeMemory).not.toHaveBeenCalled();

        const normal = await createTier1Session(buildCaps(), { sessionKey: "agent-1" });
        await normal.prompt("hello");
        expect(listLocalTools).toHaveBeenCalled();
        expect(fetchEdgeTools).toHaveBeenCalled();
        expect(fetchEdgeMemory).toHaveBeenCalled();
      } finally {
        vi.doUnmock("./localTools");
        vi.doUnmock("./toolBridge");
        vi.doUnmock("./edgeMemory");
      }
    });

    it("returns and streams a tool-call-shaped reply verbatim: no parse, no dispatch, no second round", async () => {
      const s = await plainSession();
      const envelope = `<tool_call>${toolBody}</tool_call>`;
      respondInOrder(fakeEngine, [envelope, "should never be asked for"]);
      const seen: string[] = [];
      const toolEvents: unknown[] = [];
      const out = await s.prompt("what is 2+2", (d: string) => seen.push(d), undefined, (e: unknown) =>
        toolEvents.push(e)
      );

      expect(out).toBe(envelope);
      expect(seen.join("")).toBe(envelope); // nothing held back
      expect(toolEvents).toHaveLength(0);
      expect(fakeEngine.__calls).toHaveLength(1);
    });
  });

  it("exports parseToolCall for callers that run their own protocol over a disableTools session", async () => {
    const { parseToolCall } = await import("./tier1");
    expect(parseToolCall(`<tool_call>${toolBody}</tool_call>`)).toEqual({
      name: "local_calculate",
      arguments: { expression: "2+2" },
    });
    expect(parseToolCall("just prose")).toBeNull();
    expect(parseToolCall('{"name": "Ada", "role": "engineer"}')).toBeNull();
  });
});
