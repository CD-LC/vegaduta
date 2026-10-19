// Tier 2 (tier2.ts) — the CPU/WebAssembly fallback tier.
//
// What these tests are FOR. Tier 2's whole reason to exist is reach: it runs
// on devices with no WebGPU and no Chrome Prompt API. That makes its riskiest
// property the one hardest to see in a diff — that nothing on the routing
// path can start a download. So the tests below are weighted towards the
// guarantees rather than the happy path:
//
//   * the routing-path load message carries allowNetwork:false, and the ONLY
//     message that carries true comes from installTier2Model;
//   * a "not installed" device never even constructs the worker;
//   * readiness insists on the ONNX Runtime wasm assets, not just the model
//     files, because those come from a different host and are tens of MB;
//   * a stale localStorage marker is cleared by the cache walk rather than
//     trusted.
//
// THE GAP THESE TESTS ONCE HAD, and how it is closed. Everything above drives
// a FAKE worker, so none of it ever touched @huggingface/transformers — which
// is how the tier shipped unable to load from its own cache at all
// (allowRemoteModels=false plus the worker's allowLocalModels default of
// false makes getModelFile() throw before it looks in the cache, so tier 2
// could only answer in the tab that installed it). The flag combination is
// now decided by applyTier2NetworkPolicy(), exported for exactly this reason,
// and asserted directly below — including the negative that matters: the
// cache-only path must NOT leave both flags false.
//
// Style follows the sibling tierRouter.policy.test.ts / routingState.test.ts:
// vi.resetModules() + storage clears + a fresh dynamic import per test, and
// the module's own documented test seam (__setTier2WorkerFactoryForTests)
// instead of stubbing a real Worker — jsdom has none.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const MODEL_ID = "onnx-community/Qwen3-0.6B-ONNX";
const HUB_BASE = `https://huggingface.co/${MODEL_ID}/resolve/main/`;
const RUNTIME_WASM = "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.27.0/dist/ort-wasm-simd-threaded.asyncify.wasm";
const RUNTIME_MJS = "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.27.0/dist/ort-wasm-simd-threaded.asyncify.mjs";

/** The two localStorage keys tier2.ts keeps. They are module-internal, so the
 * install test below asserts these EXACT names are what a completed install
 * writes — that is what keeps this duplication honest. */
const CACHED_KEY = `edge.tier2.cached.${MODEL_ID}`;
// Mirrors tier2.ts's TIER2_RUNTIME_URLS_KEY, including the `-ort2` invalidation
// suffix added when @huggingface/transformers 4.3.0 moved the bundled ONNX
// Runtime to 1.31.0-dev (see that constant's comment). If this drifts from the
// source key the readiness tests silently assert against a key nothing writes.
const RUNTIME_URLS_KEY = `edge.tier2.runtimeAssets-ort2.${MODEL_ID}`;

type PostedMessage = Record<string, any>;

/** A Worker stand-in. Records everything the module posts and lets a test
 * drive replies back, which is all the module's plumbing actually needs. */
class FakeWorker {
  posted: PostedMessage[] = [];
  terminated = false;
  private listeners: Array<(event: any) => void> = [];
  /** Called on every postMessage so a test can script the worker's answers. */
  onPost: ((message: PostedMessage, worker: FakeWorker) => void) | null = null;

  addEventListener(type: string, listener: (event: any) => void): void {
    if (type === "message") this.listeners.push(listener);
  }
  removeEventListener(): void {
    /* not used by the module */
  }
  postMessage(message: PostedMessage): void {
    this.posted.push(message);
    this.onPost?.(message, this);
  }
  terminate(): void {
    this.terminated = true;
  }
  /** Deliver one worker → main-thread message. */
  reply(message: PostedMessage): void {
    for (const listener of this.listeners) listener({ data: message });
  }
  find(type: string): PostedMessage | undefined {
    return this.posted.find((m) => m.type === type);
  }
}

/** Minimal Cache API over a fixed URL list — the same shape tier1.ts's and
 * indexer.ts's own tests use for their cache walks. */
function stubCaches(urls: string[] | null): void {
  if (urls === null) {
    vi.stubGlobal("caches", undefined);
    return;
  }
  vi.stubGlobal("caches", {
    has: async (name: string) => name === "transformers-cache",
    open: async () => ({
      keys: async () => urls.map((url) => ({ url })),
      match: async (url: string) => (urls.includes(url) ? {} : undefined),
    }),
  });
}

/** Every file a "fully installed" device has. */
function fullyInstalledUrls(): string[] {
  return [
    `${HUB_BASE}config.json`,
    `${HUB_BASE}tokenizer.json`,
    `${HUB_BASE}tokenizer_config.json`,
    `${HUB_BASE}onnx/model_quantized.onnx`,
    RUNTIME_WASM,
    RUNTIME_MJS,
  ];
}

function markInstalled(): void {
  localStorage.setItem(CACHED_KEY, "1");
  localStorage.setItem(RUNTIME_URLS_KEY, JSON.stringify([RUNTIME_WASM, RUNTIME_MJS]));
}

async function importTier2() {
  vi.doMock("./downloadManager", () => ({
    downloadManager: {
      enqueue: vi.fn(),
      reportExternalProgress: vi.fn(),
      hasStorageHeadroomFor: vi.fn().mockResolvedValue(true),
      requestPersistentStorage: vi.fn().mockResolvedValue(true),
    },
  }));
  vi.doMock("./branding", () => ({
    EDGE_BRANDING: {
      insufficientStorageError: "Not enough free space on this device",
      interruptedError: "Download interrupted — resume to continue",
    },
  }));
  return import("./tier2");
}

describe("edge/tier2 — think-block handling", () => {
  it("removes a complete <think> block and keeps the answer", async () => {
    const { stripThinkBlocks } = await importTier2();
    expect(stripThinkBlocks("<think>weighing it up</think>The answer is 4.")).toBe("The answer is 4.");
    expect(stripThinkBlocks("A<think>x</think>B<think>y</think>C")).toBe("ABC");
  });

  it("drops an UNTERMINATED think block rather than rendering reasoning as the reply", async () => {
    const { stripThinkBlocks } = await importTier2();
    // A generation that hit the token cap mid-thought produced no answer.
    // Empty is the honest result — the router treats an empty reply as a
    // failure and falls through, which is what should happen here.
    expect(stripThinkBlocks("<think>still thinking and then the tokens ran out")).toBe("");
  });

  it("leaves ordinary text alone", async () => {
    const { stripThinkBlocks } = await importTier2();
    expect(stripThinkBlocks("plain reply")).toBe("plain reply");
  });
});

describe("edge/tier2 — the cache-only load policy (applyTier2NetworkPolicy)", () => {
  beforeEach(() => {
    vi.resetModules();
  });

  /** A stand-in for @huggingface/transformers' `env`, which is just a mutable
   * object. `fetch` starts as a spy so the tests can prove whether a request
   * would have gone out. */
  function fakeEnv() {
    const baseFetch = vi.fn(async () => "RESPONSE");
    const env: Record<string, any> = { fetch: baseFetch };
    return { env, baseFetch };
  }

  it("does NOT leave both allowLocalModels and allowRemoteModels false — the bug that made tier 2 unable to load its own cache", async () => {
    const mod = await importTier2();
    mod.__resetTier2NetworkPolicyForTests();
    const { env } = fakeEnv();
    mod.applyTier2NetworkPolicy(env, false);
    // getModelFile()'s first statement throws "both local and remote models
    // are disabled" BEFORE any cache lookup, so this exact pair is the
    // difference between "cache-only" and "nothing can ever load".
    expect(env.allowRemoteModels).toBe(false);
    expect(env.allowLocalModels).toBe(true);
    expect([env.allowLocalModels, env.allowRemoteModels]).not.toEqual([false, false]);
  });

  it("refuses every request on the cache-only path, without calling the real fetch", async () => {
    const mod = await importTier2();
    mod.__resetTier2NetworkPolicyForTests();
    const { env, baseFetch } = fakeEnv();
    mod.applyTier2NetworkPolicy(env, false);
    // The hub, and the same-origin local-model path allowLocalModels=true
    // would otherwise open. Both are refused by the same guard.
    expect(() => (env.fetch as any)(`${HUB_BASE}config.json`)).toThrow(
      new RegExp(mod.TIER2_CACHE_ONLY_MARKER)
    );
    expect(() => (env.fetch as any)(`/models/${MODEL_ID}/config.json`)).toThrow();
    // And the ONNX Runtime wasm assets, which cacheWasm.js also pulls through
    // env.fetch.
    expect(() => (env.fetch as any)(RUNTIME_WASM)).toThrow();
    expect(baseFetch).not.toHaveBeenCalled();
  });

  it("opens the network for an install and closes it again on the next cache-only load", async () => {
    const mod = await importTier2();
    mod.__resetTier2NetworkPolicyForTests();
    const { env, baseFetch } = fakeEnv();

    mod.applyTier2NetworkPolicy(env, true);
    expect(env.allowRemoteModels).toBe(true);
    await expect((env.fetch as any)(`${HUB_BASE}config.json`)).resolves.toBe("RESPONSE");
    expect(baseFetch).toHaveBeenCalledTimes(1);

    // The worker re-locks in a finally the moment the install load ends.
    mod.applyTier2NetworkPolicy(env, false);
    expect(() => (env.fetch as any)(`${HUB_BASE}config.json`)).toThrow();
    expect(baseFetch).toHaveBeenCalledTimes(1);
  });

  it("pins off the one cache path in the library that bypasses env.fetch", async () => {
    const mod = await importTier2();
    mod.__resetTier2NetworkPolicyForTests();
    const { env } = fakeEnv();
    env.experimental_useCrossOriginStorage = true;
    mod.applyTier2NetworkPolicy(env, false);
    expect(env.experimental_useCrossOriginStorage).toBe(false);
    env.experimental_useCrossOriginStorage = true;
    mod.applyTier2NetworkPolicy(env, true);
    expect(env.experimental_useCrossOriginStorage).toBe(false);
  });

  it("survives being applied twice without swallowing the library's real fetch", async () => {
    const mod = await importTier2();
    mod.__resetTier2NetworkPolicyForTests();
    const { env, baseFetch } = fakeEnv();
    mod.applyTier2NetworkPolicy(env, false);
    // Second application sees its OWN guard in env.fetch and must not capture
    // it as the base — doing so would make the install path call the guard
    // recursively instead of the network.
    mod.applyTier2NetworkPolicy(env, true);
    await expect((env.fetch as any)("https://huggingface.co/x")).resolves.toBe("RESPONSE");
    expect(baseFetch).toHaveBeenCalledTimes(1);
  });
});

describe("edge/tier2 — honest consent copy", () => {
  it("names every host a download can reach, and says the tier is slow", async () => {
    const { tier2DownloadDetails, TIER2_NETWORK_HOSTS } = await importTier2();
    const copy = tier2DownloadDetails();
    for (const host of TIER2_NETWORK_HOSTS) {
      expect(copy).toContain(host);
    }
    expect(TIER2_NETWORK_HOSTS).toEqual(["huggingface.co", "cdn.jsdelivr.net"]);
    expect(copy.toLowerCase()).toContain("more slowly");
  });

  it("claims no size, because no size has been measured", async () => {
    const { tier2DownloadDetails } = await importTier2();
    // A size claim would look like "1.2 GB" / "450 MB". Nothing in this copy
    // may read as a measured transfer size.
    expect(tier2DownloadDetails()).not.toMatch(/\d+(\.\d+)?\s?(KB|MB|GB)/i);
  });
});

describe("edge/tier2 — readiness never promises a download", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    sessionStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    vi.stubGlobal("Worker", FakeWorker);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.doUnmock("./downloadManager");
    vi.doUnmock("./branding");
  });

  it("answers false without touching the Cache API when nothing was ever installed", async () => {
    const open = vi.fn();
    vi.stubGlobal("caches", { has: vi.fn(), open });
    const { isTier2Ready } = await importTier2();
    expect(await isTier2Ready()).toBe(false);
    // The synchronous marker short-circuit: a routing decision on a device
    // that never installed anything must not pay for a cache walk.
    expect(open).not.toHaveBeenCalled();
  });

  it("answers false and CLEARS the stale marker when the model files are gone", async () => {
    markInstalled();
    stubCaches([RUNTIME_WASM, RUNTIME_MJS]); // runtime cached, model evicted
    const { isTier2Ready } = await importTier2();
    expect(await isTier2Ready()).toBe(false);
    expect(localStorage.getItem(CACHED_KEY)).toBeNull();
  });

  it("answers false when the ONNX Runtime wasm assets are missing", async () => {
    // The model itself is cached, but the runtime comes from a different host
    // and is tens of MB — routing on this state would be a surprise download.
    markInstalled();
    stubCaches([
      `${HUB_BASE}config.json`,
      `${HUB_BASE}tokenizer.json`,
      `${HUB_BASE}onnx/model_quantized.onnx`,
    ]);
    const { isTier2Ready } = await importTier2();
    expect(await isTier2Ready()).toBe(false);
  });

  it("answers false when tokenizer_config.json is missing, even though everything else is cached", async () => {
    // Not a nicety. get_tokenizer_files() decides whether the model has a
    // tokenizer by probing this exact file, and on a cache-only load that
    // probe cannot reach the hub — so an uncached tokenizer_config.json makes
    // pipeline() RESOLVE with a tokenizer-less generator that then throws on
    // the first chat turn. A gate that called this device ready would be the
    // false positive it exists to prevent.
    markInstalled();
    stubCaches([
      `${HUB_BASE}config.json`,
      `${HUB_BASE}tokenizer.json`,
      `${HUB_BASE}onnx/model_quantized.onnx`,
      RUNTIME_WASM,
      RUNTIME_MJS,
    ]);
    const { isTier2Ready } = await importTier2();
    expect(await isTier2Ready()).toBe(false);
  });

  it("answers false when no install ever recorded which runtime assets it used", async () => {
    localStorage.setItem(CACHED_KEY, "1");
    stubCaches(fullyInstalledUrls());
    const { isTier2Ready } = await importTier2();
    expect(await isTier2Ready()).toBe(false);
  });

  it("answers true only when every required file is already cached", async () => {
    markInstalled();
    stubCaches(fullyInstalledUrls());
    const { isTier2Ready, getTier2RunModel } = await importTier2();
    expect(await isTier2Ready()).toBe(true);
    const model = await getTier2RunModel();
    expect(model?.id).toBe(MODEL_ID);
  });

  it("treats an unavailable Cache API as NOT ready", async () => {
    markInstalled();
    stubCaches(null);
    const { isTier2Ready } = await importTier2();
    expect(await isTier2Ready()).toBe(false);
  });
});

describe("edge/tier2 — the routing path can never download", () => {
  let fake: FakeWorker;

  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    sessionStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    vi.stubGlobal("Worker", FakeWorker);
    fake = new FakeWorker();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.doUnmock("./downloadManager");
    vi.doUnmock("./branding");
  });

  it("refuses with 'unavailable' and never even builds a worker when nothing is installed", async () => {
    stubCaches([]);
    const mod = await importTier2();
    const factory = vi.fn(() => fake as unknown as Worker);
    mod.__setTier2WorkerFactoryForTests(factory);
    await expect(mod.createTier2Session()).rejects.toMatchObject({
      name: "Tier2Error",
      code: "unavailable",
    });
    expect(factory).not.toHaveBeenCalled();
  });

  it("loads with allowNetwork:false — the structural no-download lock", async () => {
    markInstalled();
    stubCaches(fullyInstalledUrls());
    const mod = await importTier2();
    mod.__setTier2WorkerFactoryForTests(() => fake as unknown as Worker);
    fake.onPost = (message, worker) => {
      if (message.type === "load") {
        worker.reply({
          type: "loaded",
          requestId: message.requestId,
          modelId: MODEL_ID,
          streaming: true,
          runtimeAssetUrls: [RUNTIME_WASM, RUNTIME_MJS],
        });
      }
    };
    await mod.createTier2Session({ systemPrompt: "be brief" });
    const load = fake.find("load");
    expect(load).toBeTruthy();
    expect(load?.allowNetwork).toBe(false);
    expect(load?.modelId).toBe(MODEL_ID);
    // Nothing on this path may ever ask for the network.
    expect(fake.posted.some((m) => m.allowNetwork === true)).toBe(false);
  });

  it("streams deltas and resolves with the whole reply", async () => {
    markInstalled();
    stubCaches(fullyInstalledUrls());
    const mod = await importTier2();
    mod.__setTier2WorkerFactoryForTests(() => fake as unknown as Worker);
    fake.onPost = (message, worker) => {
      if (message.type === "load") {
        worker.reply({
          type: "loaded",
          requestId: message.requestId,
          modelId: MODEL_ID,
          streaming: true,
          runtimeAssetUrls: [RUNTIME_WASM, RUNTIME_MJS],
        });
      }
      if (message.type === "generate") {
        worker.reply({ type: "token", requestId: message.requestId, delta: "Hello " });
        worker.reply({ type: "token", requestId: message.requestId, delta: "there." });
        worker.reply({ type: "done", requestId: message.requestId, text: "Hello there." });
      }
    };
    const session = await mod.createTier2Session({ systemPrompt: "be brief" });
    const deltas: string[] = [];
    const reply = await session.prompt("hi", (d) => deltas.push(d));
    expect(deltas).toEqual(["Hello ", "there."]);
    expect(reply).toBe("Hello there.");
  });

  it("sends the system prompt plus the no-tools directive, and keeps history", async () => {
    markInstalled();
    stubCaches(fullyInstalledUrls());
    const mod = await importTier2();
    mod.__setTier2WorkerFactoryForTests(() => fake as unknown as Worker);
    fake.onPost = (message, worker) => {
      if (message.type === "load") {
        worker.reply({
          type: "loaded",
          requestId: message.requestId,
          modelId: MODEL_ID,
          streaming: true,
          runtimeAssetUrls: [RUNTIME_WASM, RUNTIME_MJS],
        });
      }
      if (message.type === "generate") {
        worker.reply({ type: "done", requestId: message.requestId, text: "ok" });
      }
    };
    const session = await mod.createTier2Session({ systemPrompt: "you are terse" });
    await session.prompt("first");
    await session.prompt("second");
    const generates = fake.posted.filter((m) => m.type === "generate");
    expect(generates).toHaveLength(2);
    const firstSystem = generates[0].messages[0];
    expect(firstSystem.role).toBe("system");
    expect(firstSystem.content).toContain("you are terse");
    // Tier 2 has no tool access at all and has to say so, for the same reason
    // tier 0 does — otherwise a small model invents file contents.
    expect(firstSystem.content).toContain("cannot access it in this mode");
    // The second turn carries the first exchange.
    expect(generates[1].messages.map((m: any) => m.role)).toEqual(["system", "user", "assistant", "user"]);
    expect(generates[1].messages.at(-1).content).toBe("second");
  });

  it("aborts by interrupting the worker, and reports it as a typed 'aborted' error", async () => {
    markInstalled();
    stubCaches(fullyInstalledUrls());
    const mod = await importTier2();
    mod.__setTier2WorkerFactoryForTests(() => fake as unknown as Worker);
    const controller = new AbortController();
    fake.onPost = (message, worker) => {
      if (message.type === "load") {
        worker.reply({
          type: "loaded",
          requestId: message.requestId,
          modelId: MODEL_ID,
          streaming: true,
          runtimeAssetUrls: [RUNTIME_WASM, RUNTIME_MJS],
        });
      }
      // A generate that never answers — the abort is what ends this turn.
    };
    const session = await mod.createTier2Session();
    const pending = session.prompt("hi", undefined, controller.signal);
    // Let the generate message go out before aborting.
    await Promise.resolve();
    await Promise.resolve();
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: "Tier2Error", code: "aborted" });
    expect(fake.find("interrupt")).toBeTruthy();
    // Abort stops the generation, it does NOT tear down the loaded pipeline —
    // rebuilding it is the expensive part on this tier.
    expect(fake.terminated).toBe(false);
  });

  it("maps the library's cache-only refusal to 'unavailable', not a generic failure", async () => {
    markInstalled();
    stubCaches(fullyInstalledUrls());
    const mod = await importTier2();
    mod.__setTier2WorkerFactoryForTests(() => fake as unknown as Worker);
    fake.onPost = (message, worker) => {
      if (message.type === "load") {
        worker.reply({
          type: "error",
          requestId: message.requestId,
          reason: "would-download",
          detail: "`env.allowRemoteModels=false`, but attempted to load a remote file",
        });
      }
    };
    await expect(mod.createTier2Session()).rejects.toMatchObject({
      name: "Tier2Error",
      code: "unavailable",
    });
  });

  it("stops claiming the model is on this device once a cache-only load disagrees with the gate", async () => {
    // The cache walk said ready and the load could not find a file. The load
    // is the stronger evidence: if the marker survived, the router would keep
    // routing to a tier that fails every turn while telling the person the
    // model is not installed.
    markInstalled();
    stubCaches(fullyInstalledUrls());
    const mod = await importTier2();
    mod.__setTier2WorkerFactoryForTests(() => fake as unknown as Worker);
    fake.onPost = (message, worker) => {
      if (message.type === "load") {
        worker.reply({
          type: "error",
          requestId: message.requestId,
          reason: "would-download",
          detail: `${mod.TIER2_CACHE_ONLY_MARKER}: the pipeline loaded without a tokenizer`,
        });
      }
    };
    await expect(mod.createTier2Session()).rejects.toMatchObject({ code: "unavailable" });
    expect(localStorage.getItem(CACHED_KEY)).toBeNull();
  });
});

describe("edge/tier2 — install is the one place the network is allowed", () => {
  let fake: FakeWorker;

  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    sessionStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    vi.stubGlobal("Worker", FakeWorker);
    fake = new FakeWorker();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.doUnmock("./downloadManager");
    vi.doUnmock("./branding");
  });

  it("loads with allowNetwork:true and records exactly the markers readiness reads", async () => {
    stubCaches([]);
    const mod = await importTier2();
    mod.__setTier2WorkerFactoryForTests(() => fake as unknown as Worker);
    fake.onPost = (message, worker) => {
      if (message.type === "load") {
        worker.reply({ type: "progress", requestId: message.requestId, loaded: 10, total: 100, status: "downloading" });
        worker.reply({
          type: "loaded",
          requestId: message.requestId,
          modelId: MODEL_ID,
          streaming: true,
          runtimeAssetUrls: [RUNTIME_WASM, RUNTIME_MJS],
        });
      }
    };
    const progress: Array<{ loaded: number; total: number }> = [];
    const result = await mod.installTier2Model({ onProgress: (p) => progress.push(p) });
    expect(result.ok).toBe(true);
    expect(fake.find("load")?.allowNetwork).toBe(true);
    expect(progress).toEqual([{ loaded: 10, total: 100, status: "downloading" }]);
    // These are the exact keys the readiness walk reads back — the duplication
    // at the top of this file is checked here rather than assumed.
    expect(localStorage.getItem(CACHED_KEY)).toBe("1");
    expect(JSON.parse(localStorage.getItem(RUNTIME_URLS_KEY) as string)).toEqual([RUNTIME_WASM, RUNTIME_MJS]);
  });

  it("refuses before starting when the signal is already aborted", async () => {
    stubCaches([]);
    const mod = await importTier2();
    const factory = vi.fn(() => fake as unknown as Worker);
    mod.__setTier2WorkerFactoryForTests(factory);
    const controller = new AbortController();
    controller.abort();
    const result = await mod.installTier2Model({ signal: controller.signal });
    expect(result).toMatchObject({ ok: false, code: "cancelled" });
    expect(factory).not.toHaveBeenCalled();
    expect(localStorage.getItem(CACHED_KEY)).toBeNull();
  });

  it("reports a load failure instead of claiming the model is installed", async () => {
    stubCaches([]);
    const mod = await importTier2();
    mod.__setTier2WorkerFactoryForTests(() => fake as unknown as Worker);
    fake.onPost = (message, worker) => {
      if (message.type === "load") {
        worker.reply({
          type: "error",
          requestId: message.requestId,
          reason: "load-failed",
          detail: "404 for onnx/model_quantized.onnx",
        });
      }
    };
    const result = await mod.installTier2Model();
    expect(result).toMatchObject({ ok: false, code: "load-failed" });
    expect(localStorage.getItem(CACHED_KEY)).toBeNull();
  });
});
