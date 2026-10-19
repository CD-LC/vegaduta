// Tests for the local-code indexer, group 2: "the no-download gate" — the real
// embedder path with a FAKE Worker and a FAKE Cache API. See indexer.test.ts's
// header for the three groups and why they are shaped this way.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { REPO, createFileAccess, SOURCES, poisonFetch } from "./indexer.testkit";

// ---------------------------------------------------------------------------
// The no-download gate
// ---------------------------------------------------------------------------

const HUB_BASE = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/";
/** What a completed q8 install leaves in the Cache API. jsdom's navigator has
 * no `gpu`, so the module asks for the quantized weights — the fp32 entry is
 * here to prove the check looks for the file it will actually load and not
 * merely "something under this model id". */
const CACHED_Q8 = [
  `${HUB_BASE}config.json`,
  `${HUB_BASE}tokenizer.json`,
  `${HUB_BASE}tokenizer_config.json`,
  `${HUB_BASE}onnx/model_quantized.onnx`,
];

function fakeCaches(urls: string[] | null) {
  return {
    async has(name: string): Promise<boolean> {
      return urls !== null && name === "transformers-cache";
    },
    async open(_name: string): Promise<any> {
      return {
        async keys(): Promise<any[]> {
          return (urls || []).map((url: string) => ({ url }));
        },
      };
    },
  };
}

interface FakeWorkerInstance {
  postMessage(message: any): void;
  terminate(): void;
  emit(data: any): void;
  terminated: boolean;
  posted: any[];
}

/** A Worker the test drives by hand. `script` is the worker's behaviour: it
 * gets each posted message and may emit replies. Returns the constructor plus
 * the list of instances, because "how many workers were constructed" is itself
 * an assertion — a refusal that constructs no worker cannot possibly download. */
function fakeWorkerClass(script: (message: any, worker: FakeWorkerInstance) => void) {
  const instances: FakeWorkerInstance[] = [];
  class FakeWorker implements FakeWorkerInstance {
    listeners: Record<string, ((event: any) => void)[]> = {};
    terminated = false;
    posted: any[] = [];
    constructor() {
      instances.push(this);
    }
    addEventListener(type: string, fn: (event: any) => void): void {
      (this.listeners[type] = this.listeners[type] || []).push(fn);
    }
    removeEventListener(): void {}
    postMessage(message: any): void {
      this.posted.push(message);
      script(message, this);
    }
    terminate(): void {
      this.terminated = true;
    }
    emit(data: any): void {
      for (const fn of this.listeners.message || []) fn({ data });
    }
  }
  return { FakeWorker, instances };
}

/** The happy-path worker: answers `load` by echoing the model id and reporting
 * `dimensions` (which a real worker measures off the loaded pipeline — here it
 * is whatever the test asks for), and `embed` with unit vectors of that
 * width. */
function loadingWorker(dimensions = 384) {
  return fakeWorkerClass((message: any, worker: FakeWorkerInstance) => {
    if (message.type === "load") {
      worker.emit({ type: "progress", requestId: message.requestId, loaded: 10, total: 100, status: "downloading" });
      worker.emit({
        type: "loaded",
        requestId: message.requestId,
        modelId: message.modelId,
        dimensions,
      });
      return;
    }
    if (message.type === "embed") {
      const count = message.texts.length;
      const vectors = new Float32Array(count * dimensions);
      for (let i = 0; i < count; i++) vectors[i * dimensions] = 1;
      worker.emit({
        type: "result",
        requestId: message.requestId,
        modelId: message.modelId,
        vectors,
        count,
        dimensions,
      });
    }
  });
}

describe("localcode/indexer — the no-download gate", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    poisonFetch();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("answers 'not ready' when the weights are not in the browser cache — and clears the stale marker", async () => {
    // The exact situation the old localStorage gate got wrong: the marker says
    // installed, the cache says otherwise (an eviction), and trusting the
    // marker re-downloads the model with nobody asking.
    localStorage.setItem("edge.localcode.cached.all-minilm-l6-v2", "1");
    vi.stubGlobal("caches", fakeCaches([]));
    vi.stubGlobal("Worker", loadingWorker().FakeWorker);
    const mod = await import("./indexer");
    mod.__setIndexerOverridesForTests(null);

    expect(mod.isLocalEmbedderInstalled()).toBe(true); // the flag still lies
    expect(await mod.isLocalEmbedderReady()).toBe(false); // the cache does not
    expect(localStorage.getItem("edge.localcode.cached.all-minilm-l6-v2")).toBeNull();
  });

  it("answers 'ready' only when the file the load would fetch is cached", async () => {
    // fp32 weights only, on a device with no WebGPU: a load here would pull the
    // quantized build, so this is NOT ready however much is cached.
    vi.stubGlobal(
      "caches",
      fakeCaches([`${HUB_BASE}config.json`, `${HUB_BASE}tokenizer.json`, `${HUB_BASE}onnx/model.onnx`])
    );
    vi.stubGlobal("Worker", loadingWorker().FakeWorker);
    const wrongDtype = await import("./indexer");
    wrongDtype.__setIndexerOverridesForTests(null);
    expect(await wrongDtype.isLocalEmbedderReady()).toBe(false);

    vi.resetModules();
    vi.stubGlobal("caches", fakeCaches(CACHED_Q8));
    const ready = await import("./indexer");
    ready.__setIndexerOverridesForTests(null);
    expect(await ready.isLocalEmbedderReady()).toBe(true);
  });

  it("answers 'not ready' where there is no Cache API to check", async () => {
    // Unverifiable is treated as not-ready on purpose: one extra click costs
    // nothing when the files turn out to be there, and the other way round is
    // an unattended multi-megabyte download.
    vi.stubGlobal("caches", undefined);
    vi.stubGlobal("Worker", loadingWorker().FakeWorker);
    const mod = await import("./indexer");
    mod.__setIndexerOverridesForTests(null);
    expect(await mod.isLocalEmbedderReady()).toBe(false);
  });

  it("refuses to build — without constructing a worker — when the model is not on the device", async () => {
    const { FakeWorker, instances } = loadingWorker();
    vi.stubGlobal("caches", fakeCaches([]));
    vi.stubGlobal("Worker", FakeWorker);
    const mod = await import("./indexer");
    mod.__setIndexerOverridesForTests({ files: createFileAccess({ ...SOURCES }) } as any);

    const final = await mod.buildLocalIndex(REPO, () => {});
    expect(final.state).toBe("error");
    expect(final.detail).toBe(mod.EMBEDDER_NOT_READY_DETAIL);
    // No worker, therefore no library import, therefore no possible download.
    expect(instances).toHaveLength(0);
  });

  it("builds and searches through the real worker protocol once the weights are cached", async () => {
    const { FakeWorker, instances } = loadingWorker();
    vi.stubGlobal("caches", fakeCaches(CACHED_Q8));
    vi.stubGlobal("Worker", FakeWorker);
    const mod = await import("./indexer");
    mod.__setIndexerOverridesForTests({ files: createFileAccess({ ...SOURCES }) } as any);

    const final = await mod.buildLocalIndex(REPO, () => {});
    expect(final.state).toBe("ready");
    expect(final.files).toBe(3);
    expect(instances).toHaveLength(1);
    // The load message carries the model identity and the dtype, because the
    // readiness check upstream verified that exact file. It deliberately does
    // NOT carry an expected width: the worker measures the real one and reports
    // it back, and a width sent down only to be echoed up would turn the main
    // thread's post-load check into a comparison of a number with itself.
    const load = instances[0].posted.find((m: any) => m.type === "load");
    expect(load.modelId).toBe(mod.EMBEDDING_MODEL_ID);
    expect(load.dtype).toBe("q8");
    expect("expectedDimensions" in load).toBe(false);
    expect((await mod.searchLocalIndex("alpha")).length).toBeGreaterThan(0);
  });

  it("refuses the build when the worker loads a model of the wrong width", async () => {
    // The silent-garbage path: a 768-dim model answering for a 384-dim index.
    // The width in a `loaded` reply is the one the worker measured off a real
    // tensor, so this fixture stands for a genuine mismatch rather than for a
    // number the main thread sent down and got back.
    const { FakeWorker } = loadingWorker(768);
    vi.stubGlobal("caches", fakeCaches(CACHED_Q8));
    vi.stubGlobal("Worker", FakeWorker);
    const mod = await import("./indexer");
    mod.__setIndexerOverridesForTests({ files: createFileAccess({ ...SOURCES }) } as any);

    const final = await mod.buildLocalIndex(REPO, () => {});
    expect(final.state).toBe("error");
    expect(final.detail).toContain("768");
  });

  it("installLocalEmbedder is what turns readiness on, and it reports progress", async () => {
    // Cached already, so this click is a warm-up rather than a download — which
    // is exactly the case that must not fail on a storage check or announce a
    // transfer that is not happening.
    const { FakeWorker, instances } = loadingWorker();
    vi.stubGlobal("caches", fakeCaches(CACHED_Q8));
    vi.stubGlobal("Worker", FakeWorker);
    const mod = await import("./indexer");
    mod.__setIndexerOverridesForTests(null);

    const seen: any[] = [];
    expect(await mod.installLocalEmbedder((p: any) => seen.push(p))).toBe(true);
    expect(instances).toHaveLength(1);
    expect(seen.length).toBeGreaterThan(0);
    expect(seen[seen.length - 1].detail).toContain("ready");
    expect(await mod.isLocalEmbedderReady()).toBe(true);
    expect(localStorage.getItem("edge.localcode.cached.all-minilm-l6-v2")).toBe("1");
  });

  it("installLocalEmbedder cancels for real: the worker is terminated and it resolves false", async () => {
    // A worker that never answers the load, standing in for a download in
    // flight. Aborting has to kill it — that is what actually stops the bytes.
    const { FakeWorker, instances } = fakeWorkerClass(() => {});
    vi.stubGlobal("caches", fakeCaches(CACHED_Q8));
    vi.stubGlobal("Worker", FakeWorker);
    const mod = await import("./indexer");
    mod.__setIndexerOverridesForTests(null);

    const controller = new AbortController();
    const seen: any[] = [];
    const install = mod.installLocalEmbedder((p: any) => seen.push(p), controller.signal);
    // Wait for the worker to exist: that is the point at which the load is
    // genuinely in flight and the abort listener is attached.
    for (let i = 0; i < 50 && instances.length === 0; i++) {
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    expect(instances).toHaveLength(1);
    controller.abort();
    expect(await install).toBe(false);
    expect(instances[0].terminated).toBe(true);
    expect(seen[seen.length - 1].detail).toContain("cancelled");
    // The load never completed, so nothing claims it did...
    expect(mod.isLocalEmbedderInstalled()).toBe(false);
    // ...but readiness answers for the FILES, and this fixture had them cached
    // all along. Cancelling a warm-up does not uninstall a model, and saying
    // otherwise would send the person back through a download they don't need.
    expect(await mod.isLocalEmbedderReady()).toBe(true);
  });

  it("installLocalEmbedder refuses politely when on-device AI is off", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "";
    const { FakeWorker, instances } = loadingWorker();
    vi.stubGlobal("caches", fakeCaches(CACHED_Q8));
    vi.stubGlobal("Worker", FakeWorker);
    const mod = await import("./indexer");
    mod.__setIndexerOverridesForTests(null);

    const seen: any[] = [];
    expect(await mod.installLocalEmbedder((p: any) => seen.push(p))).toBe(false);
    expect(seen[0].detail).toBe(mod.EDGE_OFF_DETAIL);
    expect(instances).toHaveLength(0);
  });
});
