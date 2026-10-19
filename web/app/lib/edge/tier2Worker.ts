// Tier 2 WORKER — the dedicated worker tier2.ts spawns. Everything in this
// file runs off the main thread; nothing here touches window, localStorage,
// downloadManager or branding.
//
// WHY THIS IS ITS OWN FILE (2026-09-12). This body used to live at the bottom
// of tier2.ts, and tier2.ts spawned ITSELF as the worker
// (`new Worker(new URL("./tier2.ts", import.meta.url))`). That is the one
// shape no sibling worker uses (audio/whisperWorker.ts, visionWorker.ts,
// faceWorker.ts, audio/kokoroWorker.ts and localcode/index/embedder.worker.ts
// all have ZERO runtime imports — only `import type` and a dynamic
// `import()` of the library), and it is what made `next build` for the whole
// app never finish: the worker bundle contained the module that declares the
// worker, so the bundler's worker chunk referenced itself. Every other file
// in web/ had built in ~6 minutes right up to the commit that added it.
//
// The rule this file therefore follows, same as its siblings: NO runtime
// import of anything outside tier2Shared.ts (which itself imports nothing at
// runtime). @huggingface/transformers arrives ONLY via the dynamic import()
// inside the load path below.

import type { ProgressInfo } from "@huggingface/transformers";
import {
  TRANSFORMERS_CACHE_KEY,
  TIER2_MAX_NEW_TOKENS,
  stripThinkBlocks,
  TIER2_CACHE_ONLY_MARKER,
  applyTier2NetworkPolicy,
} from "./tier2Shared";
import type {
  Tier2ChatMessage,
  Tier2WorkerRequest,
  Tier2WorkerResponse,
  Tier2LibraryEnv,
} from "./tier2Shared";

type TransformersModule = typeof import("@huggingface/transformers");

interface Tier2LoadedAssets {
  generator: any;
  tokenizer: any;
  modelId: string;
  streaming: boolean;
  runtimeAssetUrls: string[];
}

function installTier2WorkerHandlers(): void {
  const scope = self as unknown as {
    postMessage(message: Tier2WorkerResponse): void;
    addEventListener(type: "message", listener: (event: MessageEvent) => void): void;
  };

  const post = (message: Tier2WorkerResponse): void => {
    try {
      scope.postMessage(message);
    } catch {
      // A dead port means the main thread already terminated us.
    }
  };

  let assets: Tier2LoadedAssets | null = null;
  let loadInFlight: Promise<Tier2LoadedAssets> | null = null;
  /** Whether the load currently in flight was allowed to use the network, so
   * a second request can decide whether joining it is honest. */
  let loadInFlightAllowNetwork = false;
  /** The live interrupt handle for the generation currently running, so an
   * "interrupt" message can stop it at the next token step. */
  let activeStopper: { interrupt(): void } | null = null;

  const forwardProgress = (requestId: number, info: ProgressInfo): void => {
    try {
      if (info.status === "progress_total") {
        post({
          type: "progress",
          requestId,
          loaded: info.loaded || 0,
          total: info.total || 0,
          status: "downloading",
        });
      } else if (info.status === "initiate" || info.status === "done") {
        post({ type: "progress", requestId, loaded: 0, total: 0, status: info.status });
      }
    } catch {
      // Progress is best-effort — never break a load over it.
    }
  };

  /** Reads the ONNX Runtime asset URLs the library just configured. Called
   * immediately after the dynamic import and before any load, because
   * cacheWasm.js rewrites wasmPaths.mjs to a blob: URL once a load succeeds. */
  const readRuntimeAssetUrls = (tf: TransformersModule): string[] => {
    try {
      const paths = (tf as any).env?.backends?.onnx?.wasm?.wasmPaths;
      if (!paths || typeof paths !== "object") return [];
      const urls: string[] = [];
      for (const value of Object.values(paths as Record<string, unknown>)) {
        if (typeof value === "string" && /^https?:/i.test(value)) urls.push(value);
      }
      return urls;
    } catch {
      return [];
    }
  };

  /** Are the ONNX Runtime assets this load will use already in the cache?
   *
   * This exists because those assets are the one part of a load that can
   * reach the network without passing through the env.fetch guard: if
   * cacheWasm.js's pre-load fails, backends/onnx.js swallows the error and
   * ORT imports the CDN .mjs and fetches the .wasm itself (see
   * tier2GuardedFetch). A refusal would become a download rather than an
   * error, so the cache-only path asks the question first and declines the
   * load instead.
   *
   * The URLs are the ones the library itself just published in
   * env.backends.onnx.wasm.wasmPaths, so this cannot drift from whatever
   * onnxruntime-web version is actually installed — no version is guessed at
   * here. No Cache API means nothing can be cached, which means a cache-only
   * load cannot succeed anyway: false. */
  const runtimeAssetsCached = async (urls: string[]): Promise<boolean> => {
    if (urls.length === 0) return true; // nothing for ORT to fetch
    try {
      if (typeof caches === "undefined") return false;
      if (!(await caches.has(TRANSFORMERS_CACHE_KEY))) return false;
      const cache = await caches.open(TRANSFORMERS_CACHE_KEY);
      for (const url of urls) {
        if ((await cache.match(url)) === undefined) return false;
      }
      return true;
    } catch {
      return false;
    }
  };

  const loadAssets = (
    requestId: number,
    modelId: string,
    dtype: string,
    allowNetwork: boolean
  ): Promise<Tier2LoadedAssets> => {
    if (assets) {
      if (assets.modelId !== modelId) {
        throw new Error(`This worker is already loaded with ${assets.modelId}.`);
      }
      return Promise.resolve(assets);
    }
    if (loadInFlight !== null) {
      // Joining is only honest when the load already running is at least as
      // permissive as this request.
      //  - cache-only request, network load in flight: join. This request did
      //    not start a download and does not extend one; the bytes are moving
      //    because someone clicked install in this same tab.
      //  - equally permissive: join.
      //  - install request, CACHE-ONLY load in flight: do NOT join. That load
      //    will fail on the first uncached file, and the person just asked us
      //    to fetch it. Wait for it to settle and then start a real one.
      if (allowNetwork !== true || loadInFlightAllowNetwork) return loadInFlight;
      const previous = loadInFlight;
      return previous.then(
        (loaded) => loaded,
        () => loadAssets(requestId, modelId, dtype, allowNetwork)
      );
    }
    loadInFlightAllowNetwork = allowNetwork === true;
    /** Held so the finally below can re-lock the network guard synchronously
     * rather than awaiting the import again. */
    let loadedLibrary: TransformersModule | null = null;
    loadInFlight = (async (): Promise<Tier2LoadedAssets> => {
      // THE dynamic import — the only place the library is pulled in, so
      // spawning this worker costs nothing until a load is asked for.
      const tf = (await import("@huggingface/transformers")) as TransformersModule;
      loadedLibrary = tf;
      const runtimeAssetUrls = readRuntimeAssetUrls(tf);
      // PROPERTY 2(a), the structural lock — see applyTier2NetworkPolicy for
      // what it sets and, more importantly, why "allowRemoteModels = false"
      // on its own was NOT a cache-only mode but a mode in which nothing
      // loaded at all. Applied BEFORE pipeline() on every single load; only
      // an explicit install ever passes allowNetwork true, and the finally
      // below re-locks the guard the moment that load ends.
      applyTier2NetworkPolicy((tf as any).env as Tier2LibraryEnv, allowNetwork === true);
      if (allowNetwork !== true && !(await runtimeAssetsCached(runtimeAssetUrls))) {
        // See runtimeAssetsCached: refusing these inside the guard would make
        // ORT fetch them itself, so the load has to stop before pipeline().
        throw new Error(
          `${TIER2_CACHE_ONLY_MARKER}: the ONNX Runtime WebAssembly assets are not in this ` +
            `device's cache, and loading would fetch them.`
        );
      }
      const progress_callback = (info: ProgressInfo) => forwardProgress(requestId, info);
      const generator = await (tf as any).pipeline("text-generation", modelId, {
        // "wasm", never "webgpu": this tier exists precisely for devices
        // WebGPU cannot reach, and a silent WebGPU upgrade here would make
        // tier 1 and tier 2 fight over the same device.
        device: "wasm",
        dtype,
        progress_callback,
      });
      const tokenizer = generator?.tokenizer;
      if (!tokenizer) {
        // pipeline() RESOLVES without a tokenizer when get_pipeline_files()
        // could not confirm the model has one — which on a cache-only load
        // means tokenizer_config.json was not in the cache, not that the
        // model has no tokenizer (see TIER2_REQUIRED_HUB_FILES). A chat turn
        // would then throw inside the library on apply_chat_template. Fail
        // the LOAD instead, with the marker that makes the handler report
        // "not installed on this device" rather than a generic crash.
        throw new Error(
          `${TIER2_CACHE_ONLY_MARKER}: the pipeline loaded without a tokenizer, so the ` +
            `tokenizer files for ${modelId} are not on this device.`
        );
      }
      // Streaming is REAL on this runtime, not simulated: the installed
      // package exports TextStreamer (src/generation/streamers.js) and
      // src/generation/parameters.js documents `streamer` as a generate
      // parameter. `streaming` below is true only when the class is actually
      // present in the build we just imported — the tokenizer is guaranteed
      // by the check above, so a false here means the build has no streamer
      // and the worker returns whole replies and says so, rather than faking
      // a stream.
      const streaming = typeof (tf as any).TextStreamer === "function";
      assets = { generator, tokenizer, modelId, streaming, runtimeAssetUrls };
      return assets;
    })().finally(() => {
      loadInFlight = null;
      // Fail closed: the network is open only for the duration of the install
      // load that asked for it. Anything the library does afterwards — a lazy
      // asset, a retry, a future load in this worker — meets the locked guard
      // again. Synchronous, so there is no window in which the guard is open
      // and no load is running.
      if (loadInFlightAllowNetwork && loadedLibrary) {
        applyTier2NetworkPolicy((loadedLibrary as any).env as Tier2LibraryEnv, false);
      }
      loadInFlightAllowNetwork = false;
    });
    return loadInFlight;
  };

  const runGenerate = async (
    requestId: number,
    loaded: Tier2LoadedAssets,
    messages: Tier2ChatMessage[],
    maxNewTokens: number
  ): Promise<string> => {
    const tf = (await import("@huggingface/transformers")) as TransformersModule;
    const stopper = new (tf as any).InterruptableStoppingCriteria();
    activeStopper = stopper;

    // Streamed deltas are computed from the VISIBLE text (think blocks
    // removed) so the person never sees reasoning rendered as the answer,
    // and so the deltas and the final text agree.
    let raw = "";
    /** The exact text already sent as deltas — the string, not its length.
     * stripThinkBlocks() can SHRINK its output between calls (a partial
     * "<thi" is ordinary text until the tag closes, and then it is not), so a
     * numeric high-water mark would go on indexing into a shorter string and
     * silently drop real characters from the next slice. Comparing prefixes
     * cannot do that. */
    let emitted = "";
    const pushVisible = (): void => {
      const visible = stripThinkBlocks(raw);
      if (visible === emitted) return;
      if (visible.startsWith(emitted)) {
        post({ type: "token", requestId, delta: visible.slice(emitted.length) });
      }
      // If it is NOT a prefix the visible text was rewritten under us and the
      // deltas already sent cannot be taken back. Re-sync the pointer and
      // send nothing: the turn still resolves with the full, correct text,
      // which is what the caller records.
      emitted = visible;
    };

    const generateOptions: Record<string, unknown> = {
      max_new_tokens: maxNewTokens,
      // Greedy: deterministic, and one less thing for a CPU to do per token.
      do_sample: false,
      return_full_text: false,
      stopping_criteria: stopper,
      // Passed straight into apply_chat_template by the installed
      // text-generation pipeline (it spreads tokenizer_encode_kwargs into
      // chat_template_kwargs — verified in src/pipelines/text-generation.js).
      // Qwen3's template reads this to skip its reasoning block; a template
      // that does not know the key simply ignores it, and stripThinkBlocks
      // above is the backstop either way.
      tokenizer_encode_kwargs: { enable_thinking: false },
    };
    if (loaded.streaming) {
      generateOptions.streamer = new (tf as any).TextStreamer(loaded.tokenizer, {
        skip_prompt: true,
        skip_special_tokens: true,
        callback_function: (text: string) => {
          raw += text;
          pushVisible();
        },
      });
    }

    try {
      const output = await loaded.generator(messages, generateOptions);
      // Chat input → the pipeline returns the whole chat with the assistant
      // turn appended (installed package's TextGenerationPipeline._call).
      const first = Array.isArray(output) ? output[0] : output;
      const generated = first?.generated_text;
      let text = "";
      if (typeof generated === "string") {
        text = generated;
      } else if (Array.isArray(generated)) {
        const last = generated[generated.length - 1];
        text = typeof last?.content === "string" ? last.content : "";
      }
      if (!text && raw) text = raw; // streamed but the shape surprised us
      return stripThinkBlocks(text);
    } finally {
      if (activeStopper === stopper) activeStopper = null;
    }
  };

  const handle = async (request: Tier2WorkerRequest): Promise<void> => {
    if (!request || typeof request !== "object") return;

    if (request.type === "interrupt") {
      try {
        activeStopper?.interrupt();
      } catch {
        // Best-effort — the main thread has already given up on this turn.
      }
      return;
    }

    if (request.type === "load") {
      try {
        if (!request.modelId) throw new Error("No model id was supplied to the tier-2 worker.");
        const loaded = await loadAssets(
          request.requestId,
          request.modelId,
          request.dtype,
          request.allowNetwork === true
        );
        post({
          type: "loaded",
          requestId: request.requestId,
          modelId: loaded.modelId,
          streaming: loaded.streaming,
          runtimeAssetUrls: loaded.runtimeAssetUrls,
        });
      } catch (err) {
        const detail = err instanceof Error ? err.message : String(err);
        // Three ways a cache-only load says "this file is not on this
        // device", reported as one code so the main thread can say exactly
        // that instead of a generic load failure:
        //   - TIER2_CACHE_ONLY_MARKER — our own guard refused a request, or
        //     the pipeline came back without a tokenizer;
        //   - "allowRemoteModels" / "local_files_only" — the library's own
        //     refusal prose (src/utils/hub.js, loadResourceFile).
        const wouldDownload =
          request.allowNetwork !== true &&
          (detail.includes(TIER2_CACHE_ONLY_MARKER) ||
            detail.includes("allowRemoteModels") ||
            detail.includes("local_files_only"));
        post({
          type: "error",
          requestId: request.requestId,
          reason: wouldDownload ? "would-download" : "load-failed",
          detail,
        });
      }
      return;
    }

    if (request.type === "generate") {
      try {
        // Backstop for property 2, the same one embedder.worker.ts keeps: a
        // generate on an unloaded worker is an ERROR, never an implicit load.
        // Even if a future caller forgets the gate, a turn cannot acquire a
        // model as a side effect.
        const loaded = assets;
        if (!loaded) {
          post({
            type: "error",
            requestId: request.requestId,
            reason: "unavailable",
            detail: "The tier-2 model is not loaded in this worker yet.",
          });
          return;
        }
        const messages = Array.isArray(request.messages) ? request.messages : [];
        if (messages.length === 0) {
          post({
            type: "error",
            requestId: request.requestId,
            reason: "prompt-failed",
            detail: "No messages were supplied.",
          });
          return;
        }
        const text = await runGenerate(
          request.requestId,
          loaded,
          messages,
          request.maxNewTokens > 0 ? request.maxNewTokens : TIER2_MAX_NEW_TOKENS
        );
        post({ type: "done", requestId: request.requestId, text });
      } catch (err) {
        post({
          type: "error",
          requestId: request.requestId,
          reason: "prompt-failed",
          detail: err instanceof Error ? err.message : String(err),
        });
      }
    }
  };

  scope.addEventListener("message", (event: MessageEvent) => {
    void handle(event.data as Tier2WorkerRequest);
  });
}

installTier2WorkerHandlers();
