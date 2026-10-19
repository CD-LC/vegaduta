// Edge tier-1 weight-presence detection, per storage backend (E-9).
//
// WHY THIS FILE EXISTS. tier1.ts used to read WebLLM's Cache API scope
// ("webllm/model") directly, with no notion of which backend WebLLM was
// actually configured for. That is safe exactly as long as the Cache API is
// the only backend. The moment appConfig.cacheBackend is anything else, every
// installed model reads as ABSENT - and "absent" is the one answer with a
// destructive side effect: verifyTier1WeightsPresent() clears the
// "edge.tier1.cached.<id>" marker on it, so the next session re-prompts the
// person for a multi-GB download of weights they already have.
//
// So the properties under test are:
//   1. the presence check answers correctly PER BACKEND (Cache API: a real
//      three-state answer; a backend this page cannot inspect: "cannot
//      determine", never "absent");
//   2. a marker is cleared ONLY on a definitive absent - never on an
//      indeterminate result. This is the rule that stops the re-download.
//
// Style follows the sibling tier1.warmup.test.ts: real @mlc-ai/web-llm model
// ids (the manifest sanitizer drops anything else), a fake Cache API and
// fetch mock, vi.resetModules() per test so tier1.ts's module-scope memo
// starts empty.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const MODEL_A = "Hermes-2-Pro-Llama-3-8B-q4f16_1-MLC";
const MARKER_A = `edge.tier1.cached.${MODEL_A}`;
const SHARED_PREF = "edge.tier1.sharedWeights";

const BASE = `https://huggingface.co/mlc-ai/${MODEL_A}/resolve/main/`;
const INDEX_URL = `${BASE}ndarray-cache.json`;
const SHARD_URL = `${BASE}params_shard_0.bin`;

/** A Cache API stand-in over an explicit URL list, so each test states the
 * device's real storage contents rather than sharing one fixture. */
function fakeCaches(urls: string[] | null) {
  if (urls === null) return undefined;
  const cache = {
    async keys() {
      return urls.map((url) => ({ url }));
    },
    async match(req: string | { url: string }) {
      const url = typeof req === "string" ? req : req.url;
      if (!urls.includes(url)) return undefined;
      return {
        headers: { get: (name: string) => (name === "content-length" ? "1000" : null) },
        json: async () => ({ records: [{ dataPath: "params_shard_0.bin" }] }),
      };
    },
  };
  return { has: async () => true, open: async () => cache };
}

/** Complete install: the tensor index plus every shard it lists. */
const COMPLETE = [INDEX_URL, SHARD_URL];
/** The scope exists but this model's entries are gone - a definitive absent. */
const EVICTED: string[] = [];

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

/** Pretend the Chrome Cross-Origin Storage extension is installed. tier1.ts
 * tests exactly what WebLLM's own createArtifactCache() tests -
 * navigator.crossOriginStorage !== undefined - so an empty object is a
 * faithful stand-in for "the backend will really be used". */
function installCrossOriginStorage(): void {
  Object.defineProperty(globalThis.navigator, "crossOriginStorage", {
    value: {},
    configurable: true,
  });
}

function removeCrossOriginStorage(): void {
  if ("crossOriginStorage" in globalThis.navigator) {
    delete (globalThis.navigator as unknown as Record<string, unknown>).crossOriginStorage;
  }
}

/** The smallest engine the load path will accept: acquireEngine calls
 * setInitProgressCallback, primeTier1EngineShaderCompile calls
 * chat.completions.create, and the backend-change release calls unload(). */
function makeFakeEngine() {
  return {
    setInitProgressCallback: vi.fn(),
    setAppConfig: vi.fn(),
    interruptGenerate: vi.fn(),
    unload: vi.fn(async () => {}),
    reload: vi.fn(async () => {}),
    chat: { completions: { create: vi.fn(async () => ({ choices: [{ message: { content: "" } }] })) } },
  };
}

describe("edge/tier1 - weight presence is backend-aware", () => {
  let fakeEngine: ReturnType<typeof makeFakeEngine>;
  let createEngineMock: ReturnType<typeof vi.fn>;
  let deleteAllInfoMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fakeEngine = makeFakeEngine();
    vi.resetModules();
    localStorage.clear();
    removeCrossOriginStorage();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    (globalThis as any).caches = fakeCaches(COMPLETE);
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
      return { ok: false, status: 404, json: async () => ({}) };
    });
    // web-llm is dynamic-imported by the load/delete paths only; nothing under
    // test here should reach it, and a stub makes that observable rather than
    // pulling the real multi-MB module in if something regresses.
    createEngineMock = vi.fn(async () => fakeEngine);
    deleteAllInfoMock = vi.fn(async () => {});
    vi.doMock("@mlc-ai/web-llm", () => ({
      CreateMLCEngine: createEngineMock,
      deleteModelAllInfoInCache: deleteAllInfoMock,
      prebuiltAppConfig: { cacheBackend: "cache", model_list: [] },
    }));
  });

  afterEach(() => {
    delete (globalThis as any).caches;
    removeCrossOriginStorage();
    vi.unstubAllGlobals();
    vi.doUnmock("@mlc-ai/web-llm");
  });

  // -------------------------------------------------------------------------
  // Which backend is actually in play
  // -------------------------------------------------------------------------

  it("defaults to the Cache API: no preference set means presence is checkable and weights are deletable", async () => {
    const { getTier1WeightBackendState, getTier1SharedWeightsPreference } = await import("./tier1");
    expect(getTier1SharedWeightsPreference()).toBe(false);
    expect(getTier1WeightBackendState()).toEqual({
      preferred: "cache",
      effective: "cache",
      crossOriginStorageAvailable: false,
      presenceDeterminable: true,
      deletable: true,
      reason: "cache-api",
    });
  });

  it("the shared-weights preference alone does not change the backend - WebLLM falls back to the Cache API without the extension", async () => {
    localStorage.setItem(SHARED_PREF, "1");
    const { getTier1WeightBackendState } = await import("./tier1");
    const state = getTier1WeightBackendState();
    expect(state.preferred).toBe("cross-origin");
    // Mirrors createArtifactCache(): asked for cross-origin, got ArtifactCache.
    expect(state.effective).toBe("cache");
    expect(state.reason).toBe("cross-origin-unavailable");
    // ...and because the real backend is still the Cache API, detection and
    // deletion keep working. Reporting "cannot determine" here would strand
    // every such device on tier 0 for no reason.
    expect(state.presenceDeterminable).toBe(true);
    expect(state.deletable).toBe(true);
  });

  it("preference on WITH the extension present makes the shared store effective, and it can neither be inspected nor deleted", async () => {
    localStorage.setItem(SHARED_PREF, "1");
    installCrossOriginStorage();
    const { getTier1WeightBackendState } = await import("./tier1");
    expect(getTier1WeightBackendState()).toEqual({
      preferred: "cross-origin",
      effective: "cross-origin",
      crossOriginStorageAvailable: true,
      presenceDeterminable: false,
      deletable: false,
      reason: "cross-origin-active",
    });
  });

  it("setTier1SharedWeightsPreference stores the choice and drops the stale per-backend presence memo", async () => {
    const mod = await import("./tier1");
    localStorage.setItem(MARKER_A, "1");
    // Fill the session memo with a Cache-API "present" answer: this call is
    // the only way in, and it is the value a later turn would reuse.
    expect(await mod.getTier1RunModel(buildCaps() as any)).not.toBeNull();

    // Same device, shared store now effective. A memo that survived the
    // switch would keep reporting weights present in a store nothing checked.
    installCrossOriginStorage();
    mod.setTier1SharedWeightsPreference(true);
    expect(mod.getTier1SharedWeightsPreference()).toBe(true);
    expect(await mod.getTier1RunModel(buildCaps() as any)).toBeNull();
    // And switching backends is not a reason to forget a paid-for download.
    expect(localStorage.getItem(MARKER_A)).toBe("1");
  });

  // -------------------------------------------------------------------------
  // The presence answer itself
  // -------------------------------------------------------------------------

  it("Cache API backend: a complete weight set reads ready, an evicted one reads not-installed", async () => {
    const mod = await import("./tier1");
    expect(await mod.getTier1ModelInstallState(MODEL_A)).toBe("ready");

    vi.resetModules();
    (globalThis as any).caches = fakeCaches(EVICTED);
    const fresh = await import("./tier1");
    expect(await fresh.getTier1ModelInstallState(MODEL_A)).toBe("not-installed");
  });

  it("cross-origin backend: weights sitting in the Cache API do NOT read as ready, and their absence there does NOT read as not-installed", async () => {
    localStorage.setItem(SHARED_PREF, "1");
    installCrossOriginStorage();
    const mod = await import("./tier1");
    // The Cache API says "complete" - but the engine will not read it, so
    // "ready" would be a claim about the wrong store.
    expect(await mod.getTier1ModelInstallState(MODEL_A)).toBe("unknown");

    vi.resetModules();
    (globalThis as any).caches = fakeCaches(EVICTED);
    const fresh = await import("./tier1");
    // ...and the mirror image: an empty Cache API says nothing about the
    // shared store either.
    expect(await fresh.getTier1ModelInstallState(MODEL_A)).toBe("unknown");
  });

  it("no Cache API at all reads unknown, not not-installed", async () => {
    delete (globalThis as any).caches;
    const mod = await import("./tier1");
    expect(await mod.getTier1ModelInstallState(MODEL_A)).toBe("unknown");
  });

  // -------------------------------------------------------------------------
  // THE property: a readiness marker is cleared only on a definitive absent.
  // A cleared marker costs the person a multi-GB re-download.
  // -------------------------------------------------------------------------

  it("clears the readiness marker when the Cache API definitively shows the weights gone (the contrast case)", async () => {
    (globalThis as any).caches = fakeCaches(EVICTED);
    localStorage.setItem(MARKER_A, "1");
    const { getTier1RunModel } = await import("./tier1");
    expect(await getTier1RunModel(buildCaps() as any)).toBeNull();
    // Definitive absence is the ONE case where dropping the marker is right.
    expect(localStorage.getItem(MARKER_A)).toBeNull();
  });

  it("NEVER clears the readiness marker when the effective backend cannot be inspected", async () => {
    localStorage.setItem(SHARED_PREF, "1");
    installCrossOriginStorage();
    // Worst case for a naive implementation: the Cache API is readable and
    // empty, so a backend-blind check would answer "definitively absent".
    (globalThis as any).caches = fakeCaches(EVICTED);
    localStorage.setItem(MARKER_A, "1");
    const { getTier1RunModel } = await import("./tier1");
    // Not ready - tier 1 still refuses to serve, which is the safe half.
    expect(await getTier1RunModel(buildCaps() as any)).toBeNull();
    // ...but the marker survives, so nobody is re-prompted for the download.
    expect(localStorage.getItem(MARKER_A)).toBe("1");
  });

  it("NEVER clears the readiness marker when the Cache API is unavailable", async () => {
    delete (globalThis as any).caches;
    localStorage.setItem(MARKER_A, "1");
    const { getTier1RunModel } = await import("./tier1");
    expect(await getTier1RunModel(buildCaps() as any)).toBeNull();
    expect(localStorage.getItem(MARKER_A)).toBe("1");
  });

  it("a cross-origin-preferring device with no extension still behaves exactly like the Cache API device", async () => {
    // The fallback path must not inherit the shared store's caution: WebLLM
    // really is using the Cache API here, so a definitive absent is definitive.
    localStorage.setItem(SHARED_PREF, "1");
    (globalThis as any).caches = fakeCaches(EVICTED);
    localStorage.setItem(MARKER_A, "1");
    const { getTier1RunModel } = await import("./tier1");
    expect(await getTier1RunModel(buildCaps() as any)).toBeNull();
    expect(localStorage.getItem(MARKER_A)).toBeNull();
  });

  // -------------------------------------------------------------------------
  // What the manager UI is handed
  // -------------------------------------------------------------------------

  it("the installed list stays a Cache API list - complete and deletable - even while the shared store is effective", async () => {
    localStorage.setItem(SHARED_PREF, "1");
    installCrossOriginStorage();
    const { listInstalledTier1Models } = await import("./tier1");
    const installed = await listInstalledTier1Models();
    expect(installed).toHaveLength(1);
    expect(installed[0].id).toBe(MODEL_A);
    // Judged against the scope actually being enumerated, not against the
    // engine's backend - otherwise every row would claim to be incomplete.
    expect(installed[0].complete).toBe(true);
    expect(installed[0].deletable).toBe(true);
  });

  // -------------------------------------------------------------------------
  // Turning the preference OFF has to actually take effect, and has to say
  // what it costs BEFORE it moves.
  // -------------------------------------------------------------------------

  it("flipping the preference releases the live engine, and the next load runs on the store the page now claims", async () => {
    const mod = await import("./tier1");
    // Download once on the default backend: this is the only path that builds
    // an engine under a known store.
    const first = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(first.ok).toBe(true);
    expect(mod.isTier1EngineWarm(MODEL_A)).toBe(true);
    expect(createEngineMock).toHaveBeenCalledTimes(1);
    // No appConfig on the default path - the pre-existing call shape.
    expect(createEngineMock.mock.calls[0][1].appConfig).toBeUndefined();

    // Mid-session flip. Without the release this engine would stay live and
    // keep fetching weights through the store it was built with, while every
    // status surface reported the other one.
    installCrossOriginStorage();
    mod.setTier1SharedWeightsPreference(true);
    expect(mod.isTier1EngineWarm(MODEL_A)).toBe(false);

    const second = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(second.ok).toBe(true);
    expect(createEngineMock).toHaveBeenCalledTimes(2);
    expect(createEngineMock.mock.calls[1][1].appConfig.cacheBackend).toBe("cross-origin");
    // ...and the old engine is not left loaded on the GPU.
    expect(fakeEngine.unload).toHaveBeenCalled();
  });

  it("previewTier1SharedWeightsChange names the second download BEFORE the switch moves, and changes nothing itself", async () => {
    const mod = await import("./tier1");
    installCrossOriginStorage();
    mod.setTier1SharedWeightsPreference(true);
    // A model downloaded while the shared store was the effective one.
    const downloaded = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(downloaded.ok).toBe(true);
    expect(mod.getTier1ModelWeightStore(MODEL_A)).toBe("cross-origin");

    const impact = mod.previewTier1SharedWeightsChange(false);
    expect(impact.from).toBe("cross-origin");
    expect(impact.to).toBe("cache");
    expect(impact.backendChanges).toBe(true);
    expect(impact.strandedModelIds).toEqual([MODEL_A]);
    expect(impact.unrecordedModelIds).toEqual([]);
    // THE sentence: the cost is a second download of bytes already paid for.
    expect(impact.message).toContain("a second time");
    expect(impact.message).toContain("not deleted");
    // A preview must not be the flip.
    expect(mod.getTier1SharedWeightsPreference()).toBe(true);
    expect(mod.getTier1WeightBackendState().effective).toBe("cross-origin");
  });

  it("previewTier1SharedWeightsChange says plainly when the flip changes nothing on this device", async () => {
    const mod = await import("./tier1");
    // Preference on, no extension: the effective backend does not move, so
    // nothing is stranded and nothing is re-downloaded.
    const impact = mod.previewTier1SharedWeightsChange(true);
    expect(impact.from).toBe("cache");
    expect(impact.to).toBe("cache");
    expect(impact.backendChanges).toBe(false);
    expect(impact.strandedModelIds).toEqual([]);
    expect(impact.message).toContain("Cross-Origin Storage extension isn't available");
  });

  it("a model installed into the shared store keeps its marker after the preference goes off", async () => {
    const mod = await import("./tier1");
    installCrossOriginStorage();
    mod.setTier1SharedWeightsPreference(true);
    expect((await mod.startTier1Download(buildCaps() as any, MODEL_A)).ok).toBe(true);
    expect(localStorage.getItem(MARKER_A)).toBe("1");

    // Off again, and the Cache API is genuinely empty - the exact shape that
    // makes a backend-blind check say "definitively absent" and clear the
    // marker, re-offering a multi-GB download of bytes still on the disk.
    mod.setTier1SharedWeightsPreference(false);
    (globalThis as any).caches = fakeCaches(EVICTED);
    expect(await mod.getTier1RunModel(buildCaps() as any)).toBeNull();
    expect(localStorage.getItem(MARKER_A)).toBe("1");
    expect(mod.getTier1ModelWeightStore(MODEL_A)).toBe("cross-origin");
  });

  it("delete refuses, rather than reporting success, when the effective store cannot be deleted from here", async () => {
    localStorage.setItem(SHARED_PREF, "1");
    localStorage.setItem(MARKER_A, "1");
    installCrossOriginStorage();
    const mod = await import("./tier1");

    expect(await mod.deleteTier1Model(MODEL_A)).toBe(false);
    // Nothing was attempted against a scope that holds none of these bytes...
    expect(deleteAllInfoMock).not.toHaveBeenCalled();
    // ...and the record that this download happened survives, so the row can
    // keep saying where the weights are instead of vanishing.
    expect(localStorage.getItem(MARKER_A)).toBe("1");
  });

  it("the pre-download copy names the shared store only when it is the backend that will really run", async () => {
    const model = {
      id: MODEL_A,
      displayName: "Model A",
      sizeBytes: 1_000_000,
      minDeviceMemoryGB: 0,
      minMaxBufferSize: 0,
      tier: 1 as const,
    };
    const plain = await import("./tier1");
    expect(plain.tier1DownloadDetails(model as any)).not.toContain("Shared model storage");

    vi.resetModules();
    localStorage.setItem(SHARED_PREF, "1");
    installCrossOriginStorage();
    const shared = await import("./tier1");
    const copy = shared.tier1DownloadDetails(model as any);
    expect(copy).toContain("Shared model storage is on");
    // Property 3: the text names what talks to the network and what the
    // person loses, in the same breath as the offer.
    expect(copy).toContain("cannot be deleted from here");
  });
});
