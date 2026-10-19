// Tier 0: Chrome built-in model (Prompt API) wrapper.
//
// Chat-only, no tool calls. Wraps BOTH API shapes:
//   - current Chrome: the `LanguageModel` global — availability() returning
//     "unavailable" | "downloadable" | "downloading" | "available",
//     create({ initialPrompts, monitor }), session.promptStreaming()
//   - older origin-trial shape: window.ai.languageModel with capabilities()
//     ("readily" | "after-download" | "no") and create({ systemPrompt })
//
// Contract with callers (the tier router): NEVER surface a raw exception —
// every failure is a typed Tier0Error, and the router's job is to fall
// through to the next tier, never to show an error wall.
//
// Chrome's own model download (triggered by create() when availability is
// "downloadable") is mapped into the shared downloadManager progress stream
// via the monitor callback, so the EdgeSetupChip shows it exactly like any
// other on-device asset (E-0b rule 3: one unified, visible progress surface).

// edgeEnabled read-only import (2026-08-10 finding #11 fix, see
// prewarmTier0Session below) — same gate prewarmTier1Engine already applies,
// no new dependency on capabilities.ts's behavior, just its existing flag check.
import { edgeEnabled, normalizeAvailability, type PromptApiAvailability } from "./capabilities";
import { downloadManager } from "./downloadManager";
import { EDGE_BRANDING } from "./branding";

/** Asset id the built-in model reports progress under in the downloadManager. */
export const TIER0_ASSET_ID = "chrome-built-in";
// Display name comes from the branding seam: model identity is
// details-panel-only copy, and white-label tenants swap it centrally.
const TIER0_ASSET_NAME = EDGE_BRANDING.tier0ModelDisplayName;

// ---------------------------------------------------------------------------
// Typed errors
// ---------------------------------------------------------------------------

export type Tier0ErrorCode =
  | "unsupported" // API surface absent in this browser
  | "unavailable" // API present but the model can't run here
  | "create-failed" // session creation (incl. model download) failed
  | "create-timeout" // session creation stalled with no download in progress (see withDeadline below)
  | "prompt-failed" // the turn itself failed mid-stream
  | "generation-timeout" // the prompt/streaming call stalled (no new token for too long)
  | "turn-ceiling-exceeded" // turn hit the absolute wall-clock ceiling despite chunks still arriving
  | "resource-exhausted" // Chrome's own QuotaExceededError — input/context budget exceeded
  | "aborted"; // caller's AbortSignal fired

export class Tier0Error extends Error {
  code: Tier0ErrorCode;
  /** Original browser error message, kept for logs — never for the UI. */
  detail: string | null;

  constructor(code: Tier0ErrorCode, message: string, detail?: unknown) {
    super(message);
    this.name = "Tier0Error";
    this.code = code;
    this.detail = detail instanceof Error ? detail.message : detail != null ? String(detail) : null;
  }
}

function toTier0Error(err: unknown, fallbackCode: Tier0ErrorCode): Tier0Error {
  if (err instanceof Tier0Error) return err;
  const isAbort = err instanceof Error && err.name === "AbortError";
  if (isAbort) return new Tier0Error("aborted", "On-device reply was stopped.", err);
  // Chrome's Prompt API throws a named QuotaExceededError (spec'd behavior,
  // not an invented signal) when a request exceeds the model's available
  // context/resource budget — this is the "memory-exhaustion" signal this
  // API actually surfaces, so it gets its own distinct code rather than
  // being folded into the generic create-failed/prompt-failed buckets.
  const errName = err instanceof Error ? err.name : (err as { name?: unknown } | null)?.name;
  if (errName === "QuotaExceededError") {
    return new Tier0Error(
      "resource-exhausted",
      "This conversation is too large for the on-device model to continue.",
      err
    );
  }
  const message =
    fallbackCode === "create-failed"
      ? "Could not start the on-device model."
      : "The on-device model could not finish this reply.";
  return new Tier0Error(fallbackCode, message, err);
}

// ---------------------------------------------------------------------------
// Bounded waits (2026-08-06 hardening — this file previously had ZERO
// timeout/AbortController anywhere; both session creation and the
// prompt-streaming call could hang forever). Same Promise.race-with-deadline
// shape as tier1.ts's withEngineLoadDeadline (read that file's comment above
// its definition for the full rationale) — the wrapped call is NOT actually
// cancelled on timeout (this API gives us no way to force that), only the
// CALLER's wait ends, so the router's existing fall-through to the next tier
// takes over instead of hanging the whole turn forever.
// ---------------------------------------------------------------------------

/** Session creation without a pending Chrome model download should be near-
 * instant; this only guards against a browser-side hang. When Chrome still
 * needs to download its model, createTier0Session below deliberately does
 * NOT apply this — that download is the exact analogue of tier1.ts's
 * explicit, user-initiated, legitimately-unbounded startTier1Download. */
const TIER0_SESSION_CREATE_TIMEOUT_MS = 15_000;

/** Ceiling on "nothing happened for this long" during a turn — reset per
 * chunk (see raceIdle's call sites in streamPrompt below), not a fixed total
 * ceiling, so a legitimately long reply that keeps producing tokens is never
 * punished; only a genuine stall trips it. This is a safety net, not a
 * latency budget (same framing as requestLifecycle.ts's DEFAULT_TIMEOUT_POLICY). */
const TIER0_GENERATION_IDLE_TIMEOUT_MS = 30_000;

/** Bug fix (2026-08-06 Phase 2 follow-up): distinct from the idle timeout
 * above — a turn whose chunks each arrive just under the idle window apart,
 * forever, would never trip TIER0_GENERATION_IDLE_TIMEOUT_MS. This is a hard
 * wall-clock ceiling on the WHOLE turn regardless of chunk cadence, mirroring
 * tier1.ts's TIER1_TURN_CEILING_MS. streamPrompt shrinks each per-chunk race
 * to whatever budget remains under this ceiling, so the two timers compose
 * instead of one silently overriding the other. */
const TIER0_TURN_CEILING_MS = 120_000;

const TIER0_SESSION_CREATE_TIMEOUT_MARKER = Symbol("tier0-session-create-timeout");
const TIER0_GENERATION_IDLE_TIMEOUT_MARKER = Symbol("tier0-generation-idle-timeout");

/** Races `promise` against `ms`; on timeout rejects with `marker` (a private
 * sentinel callers check for with `===`, so a genuine error from the wrapped
 * call is never confused with a timeout). `ms <= 0` disables the ceiling. */
function raceDeadline<T>(promise: Promise<T>, ms: number, marker: symbol): Promise<T> {
  if (ms <= 0) return promise;
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(marker), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      }
    );
  });
}

// ---------------------------------------------------------------------------
// Availability
// ---------------------------------------------------------------------------

/** Live availability check — always queried fresh (it is cheap and its answer
 * changes while Chrome downloads the model), unlike the sessionStorage-cached
 * capability pass in capabilities.ts. Never rejects. */
export async function tier0Availability(): Promise<PromptApiAvailability> {
  try {
    if (typeof window === "undefined") return "unavailable";
    const LanguageModel = (globalThis as any).LanguageModel;
    if (LanguageModel && typeof LanguageModel.availability === "function") {
      return normalizeAvailability(await LanguageModel.availability());
    }
    const legacy = (globalThis as any).ai?.languageModel;
    if (legacy && typeof legacy.capabilities === "function") {
      const c = await legacy.capabilities();
      if (c?.available === "readily") return "available";
      if (c?.available === "after-download") return "downloadable";
      return "unavailable";
    }
  } catch {
    // Broken API surface — treat as absent.
  }
  return "unavailable";
}

/** True when tier 0 can serve (or start serving after Chrome's own model
 * download, which create() triggers with visible progress). */
export async function tier0Available(): Promise<boolean> {
  // A build already proven to be a stub this session is not available, no
  // matter what its availability() claims (see the engine probe below).
  if (readTier0ProbeVerdict() === "stub") return false;
  const a = await tier0Availability();
  return a === "available" || a === "downloadable" || a === "downloading";
}

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

export interface Tier0Session {
  /** Stream a reply. onToken receives DELTAS (never cumulative text); the
   * resolved value is the full reply. Abort via the signal → Tier0Error
   * with code "aborted". The 4th param exists ONLY so this interface stays
   * structurally call-compatible with Tier1Session's onToolCall (tierRouter's
   * promptWithOneRetryOnEmpty calls both through one `Tier0Session |
   * Tier1Session` union) — tier 0 (Chrome's built-in Prompt API) never has
   * tool access at all (see tierRouter.ts's tryTier0 comment on why), so
   * every implementation here ignores it. */
  prompt(
    text: string,
    onToken?: (delta: string) => void,
    signal?: AbortSignal,
    onToolCall?: (event: { name: string; phase: "calling" | "done" | "error" }) => void
  ): Promise<string>;
  destroy(): void;
}

/** Prompt API download-progress → downloadManager progress events. Current
 * Chrome reports e.loaded as a 0..1 fraction (no total); older builds
 * reported bytes — both handled (see AssetStatus.receivedBytes docs). */
function monitor(m: any): void {
  try {
    m.addEventListener("downloadprogress", (e: any) => {
      const total = typeof e?.total === "number" && e.total > 1 ? e.total : 1;
      const loaded = typeof e?.loaded === "number" ? e.loaded : 0;
      downloadManager.reportExternalProgress(TIER0_ASSET_ID, TIER0_ASSET_NAME, {
        state: loaded >= total ? "verifying" : "downloading",
        receivedBytes: loaded,
        totalBytes: total,
      });
    });
  } catch {
    // Progress is best-effort — never block session creation over it.
  }
}

/**
 * Create a Tier-0 session. If Chrome still has to download its model, this
 * awaits that download (progress visible via the chip); the caller stays
 * non-blocking because the tier router only routes here after the caller
 * opted in, and any failure falls through to hosted.
 */
export async function createTier0Session(systemPrompt?: string): Promise<Tier0Session> {
  if (typeof window === "undefined") {
    throw new Tier0Error("unsupported", "On-device mode only runs in a browser.");
  }
  // Snapshot availability up front so the bounded-vs-unbounded decision below
  // matches the create() call about to run. "downloadable"/"downloading"
  // means Chrome is about to (or already is) pulling its own model — that
  // download legitimately takes minutes and already has visible progress via
  // the monitor → downloadManager wiring below, so it stays unbounded exactly
  // like tier1.ts's user-initiated startTier1Download. Anything else
  // ("available", "unavailable", "unknown") should resolve near-instantly, so
  // a stall there gets the bounded deadline.
  const availability = await tier0Availability();
  const needsChromeDownload = availability === "downloadable" || availability === "downloading";
  // A build already proven to be a stub is refused before anything (least of
  // all the caller's system prompt) is handed to it.
  if (readTier0ProbeVerdict() === "stub") throw stubEngineError();
  let raw: any = null;
  try {
    const createCall = createRawSession(systemPrompt);
    raw = needsChromeDownload
      ? await createCall
      : await raceDeadline(createCall, TIER0_SESSION_CREATE_TIMEOUT_MS, TIER0_SESSION_CREATE_TIMEOUT_MARKER);
  } catch (err) {
    downloadManager.reportExternalProgress(TIER0_ASSET_ID, TIER0_ASSET_NAME, {
      state: "error",
      error: "Setup failed",
    });
    if (err === TIER0_SESSION_CREATE_TIMEOUT_MARKER) {
      throw new Tier0Error(
        "create-timeout",
        "The on-device model is taking too long to start.",
        "session-create-timeout"
      );
    }
    throw toTier0Error(err, "create-failed");
  }
  if (!raw) {
    throw new Tier0Error("unsupported", "This browser has no built-in AI model.");
  }
  // Sanity probe (once per browser session): the session above only proves
  // the API surface exists. Nothing is returned to a caller - so nothing can
  // be prompted, and nothing rendered - until the engine has shown it is a
  // model rather than an echo stub.
  try {
    await ensureTier0EngineTrusted();
  } catch (err) {
    try {
      if (typeof raw.destroy === "function") raw.destroy();
    } catch {
      // Best-effort.
    }
    downloadManager.reportExternalProgress(TIER0_ASSET_ID, TIER0_ASSET_NAME, {
      state: "error",
      error: "Setup failed",
    });
    throw toTier0Error(err, "create-failed");
  }
  downloadManager.reportExternalProgress(TIER0_ASSET_ID, TIER0_ASSET_NAME, { state: "ready" });
  return wrapSession(raw);
}

/** The one place a raw Prompt API session is created, for both API shapes.
 * The system prompt always travels in the API's own system slot - never
 * concatenated into the user turn. Resolves null when neither shape exists. */
async function createRawSession(systemPrompt?: string): Promise<any> {
  const LanguageModel = (globalThis as any).LanguageModel;
  if (LanguageModel && typeof LanguageModel.create === "function") {
    return await LanguageModel.create({
      ...(systemPrompt ? { initialPrompts: [{ role: "system", content: systemPrompt }] } : {}),
      monitor,
    });
  }
  const legacy = (globalThis as any).ai?.languageModel;
  if (legacy && typeof legacy.create === "function") {
    return await legacy.create({ ...(systemPrompt ? { systemPrompt } : {}), monitor });
  }
  return null;
}

// ---------------------------------------------------------------------------
// Engine sanity probe (prod signed-in pass 2026-10-05, bug 2)
//
// `typeof LanguageModel === "function"` and availability() === "available"
// only prove the API SURFACE exists. Chromium builds without the model ship a
// stub that answers every prompt with "...this API is just echoing back the
// input:" plus the whole input, system prompt included - which the Playground
// then rendered as the agent's answer, hidden instructions and all.
//
// So before any caller's session is handed out, a throwaway session is asked
// one question whose answer has a known property: it must NOT contain a
// one-off code that exists only in the probe's own system prompt. A model
// answers "READY"; an echo stub repeats the code. The verdict is cached for
// the browser session (module variable + sessionStorage), so the probe costs
// one short prompt per session, not one per turn.
//
// Only a definite verdict is cached. A probe that throws or times out says
// the engine is unhealthy right now, not that it is a stub - the caller fails
// over for this turn and the next createTier0Session probes again.
// ---------------------------------------------------------------------------

export type Tier0ProbeVerdict = "ok" | "stub";

const TIER0_PROBE_STORAGE_KEY = "vegaduta.edge.tier0Probe";
const TIER0_PROBE_PROMPT_TIMEOUT_MS = 20_000;
const TIER0_PROBE_TIMEOUT_MARKER = Symbol("tier0-probe-timeout");

let tier0ProbeVerdict: Tier0ProbeVerdict | null = null;
let tier0ProbeInFlight: Promise<Tier0ProbeVerdict> | null = null;

function stubEngineError(): Tier0Error {
  return new Tier0Error(
    "unavailable",
    "This browser's built-in AI model is not usable.",
    "tier-0 engine failed its sanity probe (echo stub)"
  );
}

/** The cached verdict, or null when this session has not probed yet. */
export function readTier0ProbeVerdict(): Tier0ProbeVerdict | null {
  if (tier0ProbeVerdict) return tier0ProbeVerdict;
  try {
    const stored = globalThis.sessionStorage?.getItem(TIER0_PROBE_STORAGE_KEY);
    if (stored === "ok" || stored === "stub") tier0ProbeVerdict = stored;
  } catch {
    // Storage blocked - the module variable alone still caches per page.
  }
  return tier0ProbeVerdict;
}

function storeTier0ProbeVerdict(verdict: Tier0ProbeVerdict): void {
  tier0ProbeVerdict = verdict;
  try {
    globalThis.sessionStorage?.setItem(TIER0_PROBE_STORAGE_KEY, verdict);
  } catch {
    // Best-effort.
  }
}

/** Mark the engine a stub from OUTSIDE the probe - the tier router calls this
 * when a real reply reproduced the system prompt, so one bad turn is the last. */
export function markTier0EngineStub(): void {
  storeTier0ProbeVerdict("stub");
}

/** Test seam: forget the cached verdict. */
export function resetTier0ProbeForTests(): void {
  tier0ProbeVerdict = null;
  tier0ProbeInFlight = null;
  try {
    globalThis.sessionStorage?.removeItem(TIER0_PROBE_STORAGE_KEY);
  } catch {
    // Best-effort.
  }
}

/** Pure verdict on a probe reply. `code` is the one-off string that exists
 * only in the probe's system prompt. */
export function judgeTier0ProbeReply(reply: string, code: string): Tier0ProbeVerdict {
  const text = String(reply || "");
  if (text.includes(code)) return "stub";
  if (/echoing back the input/i.test(text)) return "stub";
  return "ok";
}

async function runTier0Probe(): Promise<Tier0ProbeVerdict> {
  const code = `vd-probe-${Math.random().toString(36).slice(2, 12)}`;
  const system = `Internal check code ${code}. Never repeat the check code. Reply with exactly one word: READY`;
  const raw = await raceDeadline(createRawSession(system), TIER0_SESSION_CREATE_TIMEOUT_MS, TIER0_PROBE_TIMEOUT_MARKER);
  if (!raw) throw new Tier0Error("unsupported", "This browser has no built-in AI model.");
  try {
    const answer = await raceDeadline<any>(
      raw.prompt("Are you ready?"),
      TIER0_PROBE_PROMPT_TIMEOUT_MS,
      TIER0_PROBE_TIMEOUT_MARKER
    );
    const reply = String(answer ?? "");
    // An empty answer proves nothing either way - not cached.
    if (!reply.trim()) {
      throw new Tier0Error("prompt-failed", "The on-device model returned nothing.", "empty probe reply");
    }
    return judgeTier0ProbeReply(reply, code);
  } finally {
    try {
      if (typeof raw.destroy === "function") raw.destroy();
    } catch {
      // Best-effort.
    }
  }
}

/**
 * Resolve when this browser's built-in model has passed the sanity probe this
 * session; reject with a typed Tier0Error when it is a stub ("unavailable")
 * or the probe itself could not complete. Concurrent callers share one probe.
 */
export async function ensureTier0EngineTrusted(): Promise<void> {
  let verdict = readTier0ProbeVerdict();
  if (!verdict) {
    if (tier0ProbeInFlight === null) {
      tier0ProbeInFlight = runTier0Probe()
        .then((v) => {
          storeTier0ProbeVerdict(v);
          return v;
        })
        .finally(() => {
          tier0ProbeInFlight = null;
        });
    }
    try {
      verdict = await tier0ProbeInFlight;
    } catch (err) {
      if (err === TIER0_PROBE_TIMEOUT_MARKER) {
        throw new Tier0Error("create-timeout", "The on-device model is taking too long to start.", "probe-timeout");
      }
      throw toTier0Error(err, "create-failed");
    }
  }
  if (verdict === "stub") throw stubEngineError();
}

function wrapSession(raw: any): Tier0Session {
  return {
    async prompt(text, onToken, signal) {
      try {
        if (signal?.aborted) throw new Tier0Error("aborted", "On-device reply was stopped.");
        return await streamPrompt(raw, text, onToken, signal);
      } catch (err) {
        throw toTier0Error(err, "prompt-failed");
      }
    },
    destroy() {
      try {
        if (typeof raw.destroy === "function") raw.destroy();
      } catch {
        // Best-effort.
      }
    },
  };
}

/** Chunk semantics differ across Prompt API versions: current Chrome streams
 * deltas, older builds streamed the cumulative text so far. This merge handles
 * both: a chunk extending the accumulated text is treated as cumulative (emit
 * the diff), anything else as a plain delta. */
function mergeChunk(fullSoFar: string, chunk: string): { nextFull: string; delta: string } {
  if (fullSoFar.length > 0 && chunk.startsWith(fullSoFar)) {
    return { nextFull: chunk, delta: chunk.slice(fullSoFar.length) };
  }
  return { nextFull: fullSoFar + chunk, delta: chunk };
}

/** Bug fix (2026-08-06): this function had NO ceiling at all — a stalled
 * Prompt API call (no first token, or a mid-stream stall) previously left
 * the awaiting caller (streamPrompt -> wrapSession.prompt -> the tier
 * router -> sendViaEdge) hanging forever. Every await that waits on the
 * browser is raced against TIER0_GENERATION_IDLE_TIMEOUT_MS via raceDeadline,
 * reset on each iteration so a legitimately long streamed reply is never
 * punished — only a real stall (no read/next() resolving at all within the
 * window) trips it. The underlying browser call is NOT cancelled on timeout (this API
 * gives no way to force that); only the caller's wait ends, same tradeoff
 * tier1.ts's withEngineLoadDeadline already documents and accepts. */
async function streamPrompt(
  raw: any,
  text: string,
  onToken?: (delta: string) => void,
  signal?: AbortSignal
): Promise<string> {
  const options = signal ? { signal } : undefined;
  const turnStartedAt = Date.now();
  // Each per-chunk race is bounded by whichever is tighter right now: the
  // flat idle window, or however much of the absolute ceiling is left. This
  // is what makes the two timers compose instead of the idle timer silently
  // masking the ceiling (see TIER0_TURN_CEILING_MS's doc comment above).
  const chunkDeadline = () =>
    Math.max(1, Math.min(TIER0_GENERATION_IDLE_TIMEOUT_MS, TIER0_TURN_CEILING_MS - (Date.now() - turnStartedAt)));
  try {
    if (typeof raw.promptStreaming === "function") {
      let stream: any;
      try {
        stream = raw.promptStreaming(text, options);
      } catch {
        // Some builds reject the options bag — retry bare (abort then only
        // works between chunks via the manual check below).
        stream = raw.promptStreaming(text);
      }
      let full = "";
      const consume = (value: unknown) => {
        if (signal?.aborted) throw new Tier0Error("aborted", "On-device reply was stopped.");
        const chunk = typeof value === "string" ? value : String(value ?? "");
        if (!chunk) return;
        const merged = mergeChunk(full, chunk);
        full = merged.nextFull;
        if (merged.delta && onToken) onToken(merged.delta);
      };
      if (stream && typeof stream.getReader === "function") {
        const reader = stream.getReader();
        try {
          for (;;) {
            const { done, value } = await raceDeadline<any>(
              reader.read(),
              chunkDeadline(),
              TIER0_GENERATION_IDLE_TIMEOUT_MARKER
            );
            if (done) break;
            consume(value);
          }
        } finally {
          try {
            reader.releaseLock();
          } catch {
            // Best-effort.
          }
        }
        return full;
      }
      if (stream && typeof stream[Symbol.asyncIterator] === "function") {
        const iterator = stream[Symbol.asyncIterator]();
        for (;;) {
          const { done, value } = await raceDeadline<any>(
            iterator.next(),
            chunkDeadline(),
            TIER0_GENERATION_IDLE_TIMEOUT_MARKER
          );
          if (done) break;
          consume(value);
        }
        return full;
      }
      // Unknown stream shape — fall through to the non-streaming path.
    }
    const out = String(
      (await raceDeadline<any>(
        raw.prompt(text, options),
        chunkDeadline(),
        TIER0_GENERATION_IDLE_TIMEOUT_MARKER
      )) ?? ""
    );
    if (signal?.aborted) throw new Tier0Error("aborted", "On-device reply was stopped.");
    if (out && onToken) onToken(out);
    return out;
  } catch (err) {
    if (err === TIER0_GENERATION_IDLE_TIMEOUT_MARKER) {
      // The race that just lost could have been cut short by either timer —
      // chunkDeadline() shrinks toward the ceiling as it's approached, so
      // whichever was actually binding is determined by how much wall-clock
      // time this turn has used at the moment it lost.
      if (Date.now() - turnStartedAt >= TIER0_TURN_CEILING_MS) {
        throw new Tier0Error(
          "turn-ceiling-exceeded",
          "This reply is taking much longer than expected and was stopped."
        );
      }
      throw new Tier0Error("generation-timeout", "The on-device model stopped responding.");
    }
    throw err;
  }
}

// ---------------------------------------------------------------------------
// Prewarm (2026-08-10 edge/WebLLM audit, finding #11 — tier 0 had no
// equivalent of tier1.ts's prewarmTier1Engine; createTier0Session paid its
// full creation cost synchronously on whatever turn happened to be first).
// Mirrors prewarmTier1Engine's shape and safety contract exactly (read that
// function's doc comment in tier1.ts for the fuller rationale this restates
// for tier 0):
//   - fire-and-forget: returns void, never a Promise a render/mount-effect
//     path could be tempted to await;
//   - NEVER triggers a fresh download/install: proceeds ONLY when
//     tier0Availability() reports "available" (Chrome's built-in model is
//     already resident on this device right now). "downloadable"/
//     "downloading" are deliberately excluded here — unlike
//     createTier0Session's own bounded-vs-unbounded branch above (which
//     legitimately awaits an ALREADY-started, user-triggered download), a
//     background idle-time warm call must never be what kicks off or rides
//     out a multi-minute model install the person never asked for;
//   - best-effort: every failure (including from createTier0Session/
//     session.prompt) is swallowed here; nothing ever throws past this
//     function, and the real first turn's own typed-error handling in
//     tryTier0 (tierRouter.ts) is completely unaffected by whether this
//     ran, succeeded, or failed;
//   - callable from the same mount-effect call sites that already call
//     prewarmTier1Engine (AgentPlayground.tsx, ChatPanel.tsx / its shared
//     warm-state hook), gated the same way (edge-relevant mount effects).
//
// Scope note: unlike tier1.ts, this module owns no cross-turn session cache
// of its own — the reusable per-conversation Tier0Session lives in
// tierRouter.ts's tier0Sessions map (keyed by sessionKey), which this
// function has no reach into. What gets warmed here is Chrome's underlying
// on-device model backend/runtime: a throwaway session is created and given
// one minimal, discarded prompt (mirroring tier1.ts's
// primeTier1EngineShaderCompile, which pays WebGPU's own lazy first-dispatch
// shader-compile cost the same way) to pay whatever first-use
// backend-initialization cost this browser's implementation has, then
// destroyed immediately. The real per-conversation session tryTier0 creates
// afterward still pays its own (now hopefully cheaper) creation cost —
// exactly analogous to how tier1's real generation call still runs after its
// prewarm only warms the shared engine singleton, never a specific turn.
let tier0PrewarmInFlight = false;

/**
 * Warm Chrome's built-in on-device model when it's already resident, ahead of
 * the first real turn. No-op (and no-op ONLY, never a download) on any other
 * availability state, including mid-flight or not-yet-started downloads.
 */
export function prewarmTier0Session(): void {
  if (typeof window === "undefined" || !edgeEnabled()) return;
  if (tier0PrewarmInFlight) return; // a warm probe already in flight is enough
  tier0PrewarmInFlight = true;
  void (async () => {
    try {
      const availability = await tier0Availability();
      if (availability !== "available") return; // never ride out/start a download
      const session = await createTier0Session();
      try {
        await session.prompt("Hi");
      } finally {
        session.destroy();
      }
    } catch {
      // Best-effort only — see doc comment above.
    } finally {
      tier0PrewarmInFlight = false;
    }
  })();
}
