// Retry / backoff / watchdogs for on-device downloads (2026-09-19).
//
// WHY. startTier1Download's wait on WebLLM was, by an explicit 2026-08-06
// decision, UNBOUNDED: a multi-GB download legitimately takes a long time and
// a fixed deadline would fail honest transfers on slow links. That left the
// opposite hole open — a transfer that has silently died (wifi roamed, laptop
// slept, the CDN stopped sending) spins at 37% forever, because nothing ever
// rejects. The fix is not a short deadline, it is a STALL watchdog: "no
// progress event for N seconds" distinguishes hung from slow without
// punishing slow. A generous hard ceiling sits behind it as the last resort.
//
// A single dropped connection also used to end the whole attempt. Both
// engines here are resumable by construction (WebLLM caches each weight shard
// as its own Cache API entry; downloadManager's engine resumes with HTTP
// Range at the last shard), so an automatic retry costs only what is missing.
//
// PURE-ISH MODULE: timers, plus waitForOnline's feature-detected `online`
// event listener — all injectable for tests. No app imports.

export interface BackoffOptions {
  /** First delay. */
  baseMs?: number;
  /** Ceiling for any single delay. */
  maxMs?: number;
  factor?: number;
  /** 0..1 — fraction of the delay that is randomised (full value = "full jitter"). */
  jitter?: number;
}

export const DEFAULT_BACKOFF: Required<BackoffOptions> = {
  baseMs: 1_500,
  maxMs: 20_000,
  factor: 2,
  jitter: 0.25,
};

/**
 * Delay before retry number `attempt` (1 = the first retry). Exponential,
 * capped, with bounded jitter so a CDN hiccup doesn't get every open tab
 * retrying in lockstep. `random` is injectable; it must return [0,1).
 */
export function backoffDelayMs(attempt: number, options: BackoffOptions = {}, random: () => number = Math.random): number {
  const o = { ...DEFAULT_BACKOFF, ...options };
  const n = Math.max(1, Math.floor(attempt));
  const raw = Math.min(o.maxMs, o.baseMs * Math.pow(o.factor, n - 1));
  const jitter = Math.max(0, Math.min(1, o.jitter));
  const r = Math.max(0, Math.min(0.999999, random()));
  // Spread symmetrically around `raw`, then re-cap.
  const spread = raw * jitter * (r * 2 - 1);
  return Math.max(0, Math.min(o.maxMs, Math.round(raw + spread)));
}

export interface RetryOptions<E> {
  /** Total tries including the first. */
  maxAttempts: number;
  backoff?: BackoffOptions;
  /** Decide from the thrown value whether another try is worth it. */
  shouldRetry: (error: E, attempt: number) => boolean;
  /** Called before each wait — for "retrying…" UI. */
  onRetry?: (info: { attempt: number; delayMs: number; error: E }) => void;
  /**
   * Optional gate run after the backoff sleep and before the next attempt —
   * e.g. waitForOnline. Resolving false means "the precondition never came
   * back": retrying stops and the pending error is rethrown. A gate that
   * throws is treated as "go ahead" (a broken gate must not end the retries).
   */
  beforeNextAttempt?: (error: E, attempt: number) => Promise<boolean>;
  sleep?: (ms: number) => Promise<void>;
  random?: () => number;
  /** Stop waiting/retrying when aborted. */
  signal?: AbortSignal;
}

export function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Run `task` up to maxAttempts times. Rethrows the LAST error when retries
 * are exhausted or shouldRetry says no — the caller classifies it. An aborted
 * signal stops further attempts immediately (the pending error is rethrown).
 */
export async function withRetry<T, E = unknown>(task: (attempt: number) => Promise<T>, options: RetryOptions<E>): Promise<T> {
  const sleep = options.sleep ?? defaultSleep;
  const max = Math.max(1, Math.floor(options.maxAttempts));
  let attempt = 1;
  for (;;) {
    try {
      return await task(attempt);
    } catch (err) {
      const error = err as E;
      if (attempt >= max || options.signal?.aborted || !options.shouldRetry(error, attempt)) throw err;
      const delayMs = backoffDelayMs(attempt, options.backoff, options.random);
      try {
        options.onRetry?.({ attempt, delayMs, error });
      } catch {
        // A broken observer must never break the retry loop.
      }
      await sleep(delayMs);
      if (options.signal?.aborted) throw err;
      if (options.beforeNextAttempt) {
        let go = true;
        try {
          go = await options.beforeNextAttempt(error, attempt);
        } catch {
          go = true;
        }
        if (!go || options.signal?.aborted) throw err;
      }
      attempt += 1;
    }
  }
}

/** How long a retry waits for the link to come back before giving up. */
export const WAIT_FOR_ONLINE_MS = 2 * 60 * 1000;

export interface WaitForOnlineOptions {
  maxMs?: number;
  signal?: AbortSignal;
  /** Injectable for tests. Defaults to navigator.onLine (undefined = unknown). */
  isOnline?: () => boolean | undefined;
  /** Injectable for tests. Defaults to the global event target, when there is one. */
  target?: { addEventListener: (t: string, fn: () => void) => void; removeEventListener: (t: string, fn: () => void) => void } | null;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

/**
 * Resolve true as soon as the browser reports being online (immediately when
 * it already is, or when it cannot say), false when `maxMs` passes or the
 * signal aborts first. Never rejects. Feature-detected: with no event target
 * (a worker without one, a test) it degrades to the timeout alone.
 */
export function waitForOnline(options: WaitForOnlineOptions = {}): Promise<boolean> {
  const isOnline =
    options.isOnline ??
    ((): boolean | undefined => {
      try {
        return typeof navigator !== "undefined" && typeof navigator.onLine === "boolean" ? navigator.onLine : undefined;
      } catch {
        return undefined;
      }
    });
  if (isOnline() !== false) return Promise.resolve(true);
  if (options.signal?.aborted) return Promise.resolve(false);
  const setTimer = options.setTimer ?? ((fn, ms) => setTimeout(fn, ms));
  const clearTimer = options.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));
  let target = options.target;
  if (target === undefined) {
    try {
      const g = globalThis as unknown as WaitForOnlineOptions["target"];
      target = g && typeof g.addEventListener === "function" ? g : null;
    } catch {
      target = null;
    }
  }
  return new Promise<boolean>((resolve) => {
    let done = false;
    const finish = (value: boolean) => {
      if (done) return;
      done = true;
      clearTimer(timer);
      try {
        target?.removeEventListener("online", onOnline);
        options.signal?.removeEventListener("abort", onAbort);
      } catch {
        // Best-effort cleanup.
      }
      resolve(value);
    };
    const onOnline = () => finish(true);
    const onAbort = () => finish(false);
    const timer = setTimer(() => finish(isOnline() !== false), options.maxMs ?? WAIT_FOR_ONLINE_MS);
    try {
      target?.addEventListener("online", onOnline);
      options.signal?.addEventListener("abort", onAbort);
    } catch {
      // No events available — the timeout still bounds the wait.
    }
  });
}

/** Thrown by runWithWatchdogs. `kind` is the EdgeDownloadFailureReason. */
export class DownloadWatchdogError extends Error {
  kind: "stalled" | "timed-out";
  constructor(kind: "stalled" | "timed-out") {
    super(kind === "stalled" ? "download stalled (no progress)" : "download exceeded the overall time limit");
    this.name = "DownloadWatchdogError";
    this.kind = kind;
  }
}

export interface WatchdogOptions {
  /** Reject when `touch()` hasn't been called for this long. */
  stallMs: number;
  /** Reject when the whole thing has run this long, progress or not. */
  overallMs: number;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

/**
 * Race `run` against a stall watchdog and an overall ceiling. `run` receives
 * `touch`, which it must call on every progress event.
 *
 * IMPORTANT: losing the race does NOT cancel `run` — WebLLM exposes no way to
 * abort an in-flight CreateMLCEngine. The caller stops WAITING (so the UI can
 * say what happened) and should attach its own late-completion handler to the
 * underlying promise if a late success still matters. For abortable work,
 * abort it in the catch.
 */
export function runWithWatchdogs<T>(run: (touch: () => void) => Promise<T>, options: WatchdogOptions): Promise<T> {
  const setTimer = options.setTimer ?? ((fn, ms) => setTimeout(fn, ms));
  const clearTimer = options.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));
  return new Promise<T>((resolve, reject) => {
    let settled = false;
    let stallHandle: unknown = null;
    const finish = (fn: () => void) => {
      if (settled) return;
      settled = true;
      if (stallHandle != null) clearTimer(stallHandle);
      clearTimer(overallHandle);
      fn();
    };
    const armStall = () => {
      if (settled) return;
      if (stallHandle != null) clearTimer(stallHandle);
      stallHandle = setTimer(() => finish(() => reject(new DownloadWatchdogError("stalled"))), options.stallMs);
    };
    const overallHandle = setTimer(
      () => finish(() => reject(new DownloadWatchdogError("timed-out"))),
      options.overallMs
    );
    armStall();
    let work: Promise<T>;
    try {
      work = run(armStall);
    } catch (err) {
      finish(() => reject(err));
      return;
    }
    work.then(
      (value) => finish(() => resolve(value)),
      (err) => finish(() => reject(err))
    );
  });
}

/**
 * Bounded-concurrency gate. acquire() resolves with a release function once a
 * slot is free; release is idempotent. FIFO.
 *
 * WHY: nothing bounded parallel model downloads. "Update now" with several
 * stale assets started them all at once, each holding up to one 64 MB shard
 * in memory and competing for the same link — on a phone that is an OOM kill
 * of the tab, which is the least legible failure there is.
 */
export class ConcurrencyGate {
  private active = 0;
  private waiters: Array<() => void> = [];
  constructor(private readonly limit: number) {}

  get inFlight(): number {
    return this.active;
  }

  get waiting(): number {
    return this.waiters.length;
  }

  acquire(): Promise<() => void> {
    const makeRelease = () => {
      let released = false;
      return () => {
        if (released) return;
        released = true;
        this.active -= 1;
        const next = this.waiters.shift();
        if (next) next();
      };
    };
    if (this.active < Math.max(1, this.limit)) {
      this.active += 1;
      return Promise.resolve(makeRelease());
    }
    return new Promise((resolve) => {
      this.waiters.push(() => {
        this.active += 1;
        resolve(makeRelease());
      });
    });
  }
}
