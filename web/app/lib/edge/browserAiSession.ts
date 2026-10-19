// Browser built-in AI: the ONLY place create() is called, plus the explicit,
// user-initiated model download. Split out of browserAi.ts; see that file's
// header for the guarantees.
import {
  type AnyRecord,
  explain,
  method,
  type BrowserAiKind,
  BrowserAiError,
  resolveBrowserAiSurface,
  browserAiAvailability,
  LABELS,
} from "./browserAiSurface";


// ---------------------------------------------------------------------------
// Sessions — the ONLY place create() is called
// ---------------------------------------------------------------------------

interface BrowserAiSession {
  /** The raw object the browser returned (a Summarizer/Translator/Detector). */
  raw: AnyRecord;
  destroy: () => void;
}

/** Asset ids the browser's own model downloads report under in the shared
 * downloadManager, so they appear in the same progress surface as every other
 * on-device asset rather than in a second, invented one. */
export const BROWSER_AI_ASSET_IDS: Record<BrowserAiKind, string> = {
  summarizer: "browser-ai-summarizer",
  translator: "browser-ai-translator",
  languageDetector: "browser-ai-language-detector",
};

const ASSET_NAMES: Record<BrowserAiKind, string> = {
  summarizer: "Browser summarizer model",
  translator: "Browser translation model",
  languageDetector: "Browser language-detector model",
};

export type BrowserAiSessionResult =
  | { ok: true; session: BrowserAiSession }
  | { ok: false; message: string };

/**
 * The ONLY session opener reachable from outside this module.
 *
 * It has no download parameter — not one defaulted to false, none at all — so
 * the refusal below is not a policy a caller can opt out of. A routing-path
 * caller (a tool handler, a console panel, a future module) that holds this
 * export cannot express "download anyway", because there is no argument in
 * which to say it. The download path is `openSessionAllowingDownload`, which
 * is module-local AND takes the same `{ userInitiated: true }` literal
 * startBrowserAiDownload demands, so it is unreachable and unforgeable from
 * outside this file.
 *
 * Returns a discriminated result rather than throwing for the refusal cases,
 * narrowed with the explicit literal form the repo's non-strict TS config
 * needs (`result.ok === true`).
 */
export async function openBrowserAiSession(
  kind: BrowserAiKind,
  createOptions: Record<string, unknown>,
  opts: { signal?: AbortSignal }
): Promise<BrowserAiSessionResult> {
  return await openSession(kind, createOptions, { signal: opts.signal, userInitiatedDownload: null });
}

/**
 * The download-permitting opener. Module-local, and its `userInitiated` gate
 * is the same literal `true` startBrowserAiDownload requires from its own
 * caller, so the marker is carried all the way to create() rather than being
 * traded for a boolean halfway down.
 */
async function openSessionAllowingDownload(
  kind: BrowserAiKind,
  createOptions: Record<string, unknown>,
  opts: {
    signal?: AbortSignal;
    userInitiated: true;
    onDownloadProgress?: (loaded: number, total: number) => void;
  }
): Promise<BrowserAiSessionResult> {
  if (opts.userInitiated !== true) {
    return { ok: false, message: explain(kind, resolveBrowserAiSurface(kind).shape, "downloadable") };
  }
  return await openSession(kind, createOptions, {
    signal: opts.signal,
    userInitiatedDownload: { userInitiated: true },
    onDownloadProgress: opts.onDownloadProgress,
  });
}

async function openSession(
  kind: BrowserAiKind,
  createOptions: Record<string, unknown>,
  opts: {
    signal?: AbortSignal;
    /** Non-null ONLY on the explicit-click path. Nothing outside this file can
     * construct a call that sets it. */
    userInitiatedDownload: { userInitiated: true } | null;
    onDownloadProgress?: (loaded: number, total: number) => void;
  }
): Promise<BrowserAiSessionResult> {
  const surface = resolveBrowserAiSurface(kind);
  if (surface.shape === "none" || !surface.createMethod) {
    return { ok: false, message: explain(kind, "none", "unavailable") };
  }

  const allowDownload = opts.userInitiatedDownload?.userInitiated === true;
  // NO SURPRISE DOWNLOAD, half (a) from the header: the probe has to ask about the SAME
  // configuration create() will be called with. Chrome's availability() is
  // option-dependent for every one of these built-ins (the create-options bag
  // IS the argument), so probing with no options and then creating with
  // {type, length, format} can get "available" for the default configuration
  // and a model fetch for the requested one. Passing createOptions for every
  // kind — not only the translator — is what makes the gate's answer describe
  // the call it is gating. The detector's bag is {}, which is what its own
  // availability() takes.
  const availability = await browserAiAvailability(kind, createOptions);
  if (availability !== "available" && !allowDownload) {
    // NO SURPRISE DOWNLOAD: this is the branch that returns before create().
    return { ok: false, message: explain(kind, surface.shape, availability) };
  }
  if (availability === "unavailable" && allowDownload) {
    return { ok: false, message: explain(kind, surface.shape, "unavailable") };
  }

  const create = method(surface.host, surface.createMethod);
  if (!create) return { ok: false, message: explain(kind, "none", "unavailable") };

  const args: Record<string, unknown> = { ...createOptions };
  if (opts.signal) args.signal = opts.signal;
  if (opts.onDownloadProgress) {
    const report = opts.onDownloadProgress;
    args.monitor = (m: AnyRecord): void => {
      try {
        const add = method(m, "addEventListener");
        if (!add) return;
        add.call(m, "downloadprogress", (e: AnyRecord) => {
          // Same two shapes tier0.ts's monitor already handles: a 0..1
          // fraction with no total, or byte counts.
          const total = typeof e.total === "number" && e.total > 1 ? e.total : 1;
          const loaded = typeof e.loaded === "number" ? e.loaded : 0;
          report(loaded, total);
        });
      } catch {
        // Progress is best-effort; never block session creation over it.
      }
    };
  }

  try {
    const raw = (await create.call(surface.host, args)) as AnyRecord | null;
    if (!raw || typeof raw !== "object") {
      return { ok: false, message: `Your browser's ${LABELS[kind]} did not return a usable session.` };
    }
    return {
      ok: true,
      session: {
        raw,
        destroy: (): void => {
          try {
            const d = method(raw, "destroy");
            if (d) d.call(raw);
          } catch {
            // Best-effort.
          }
        },
      },
    };
  } catch (err) {
    throw toBrowserAiError(kind, err);
  }
}

export function toBrowserAiError(kind: BrowserAiKind, err: unknown): BrowserAiError {
  if (err instanceof BrowserAiError) return err;
  const name = err instanceof Error ? err.name : (err as { name?: unknown } | null)?.name;
  if (name === "QuotaExceededError") {
    return new BrowserAiError(
      kind,
      "quota",
      `That text is larger than your browser's ${LABELS[kind]} will accept in one go. Try a shorter passage.`
    );
  }
  if (name === "AbortError") {
    return new BrowserAiError(kind, "timeout", `Your browser's ${LABELS[kind]} was stopped before it answered.`);
  }
  const detail = err instanceof Error && err.message ? ` (${err.message})` : "";
  return new BrowserAiError(
    kind,
    "browser-error",
    `Your browser's ${LABELS[kind]} failed while running${detail}. This is the browser's own model, so nothing was sent anywhere and nothing was charged.`
  );
}

// ---------------------------------------------------------------------------
// The explicit, user-initiated download
// ---------------------------------------------------------------------------

export interface BrowserAiDownloadRequest {
  kind: BrowserAiKind;
  /** Translator only — the pair whose model is being fetched. */
  sourceLanguage?: string;
  targetLanguage?: string;
  /**
   * Wired to a visible Cancel control by the caller.
   *
   * HONEST LIMITATION, stated because the obvious assumption is wrong:
   * downloadManager.cancel(id) aborts transfers the MANAGER itself drives, and
   * this one is driven by the browser. It cannot stop this. This signal is the
   * only real cancel, and aborting it is what reports the "cancelled" state
   * into the manager below.
   */
  signal?: AbortSignal;
  /**
   * Required, and required to be the literal `true`. It carries no data; it
   * exists so that a call site cannot start a model download without writing,
   * in code, that a person clicked. A programmatic/background caller that has
   * not thought about it will not type this.
   */
  userInitiated: true;
}

export type BrowserAiDownloadResult =
  | { ok: true; message: string }
  | { ok: false; reason: "unsupported" | "not-needed" | "refused" | "cancelled" | "failed"; message: string };

/**
 * Start the browser's own model download for one feature, from an explicit
 * click.
 *
 * Progress goes into the SHARED downloadManager (the same surface tier0's
 * Chrome-model download and tier1's weights already report into), so the
 * person sees one list, not a second invented one. The manager is loaded by a
 * dynamic import here and nowhere else in this file — that is what keeps the
 * emitted module free of static imports, and what makes "no tool handler can
 * reach downloadManager" a structural fact rather than a promise.
 */
export async function startBrowserAiDownload(req: BrowserAiDownloadRequest): Promise<BrowserAiDownloadResult> {
  const kind = req.kind;
  if (req.userInitiated !== true) {
    return {
      ok: false,
      reason: "refused",
      message: "A model download only starts from an explicit action by the person using this page.",
    };
  }
  const surface = resolveBrowserAiSurface(kind);
  if (surface.shape === "none") {
    return { ok: false, reason: "unsupported", message: explain(kind, "none", "unavailable") };
  }

  const createOptions: Record<string, unknown> = {};
  if (kind === "translator") {
    if (!req.sourceLanguage || !req.targetLanguage) {
      return {
        ok: false,
        reason: "refused",
        message: "A translation model is downloaded per language pair, so this needs both a source and a target language.",
      };
    }
    createOptions.sourceLanguage = req.sourceLanguage;
    createOptions.targetLanguage = req.targetLanguage;
  }

  // Same configuration the create() below will use, for the same reason
  // openSession gives.
  const availability = await browserAiAvailability(kind, createOptions);
  if (availability === "available") {
    return {
      ok: false,
      reason: "not-needed",
      message: `Your browser's ${LABELS[kind]} is already ready — there is nothing to download.`,
    };
  }
  if (availability === "unavailable") {
    return { ok: false, reason: "unsupported", message: explain(kind, surface.shape, "unavailable") };
  }

  const assetId = BROWSER_AI_ASSET_IDS[kind];
  const assetName = ASSET_NAMES[kind];
  const { downloadManager } = await import("./downloadManager");
  downloadManager.reportExternalProgress(assetId, assetName, {
    state: "downloading",
    receivedBytes: 0,
    totalBytes: 1,
  });

  try {
    const opened = await openSessionAllowingDownload(kind, createOptions, {
      userInitiated: true,
      signal: req.signal,
      onDownloadProgress: (loaded: number, total: number): void => {
        downloadManager.reportExternalProgress(assetId, assetName, {
          state: loaded >= total ? "verifying" : "downloading",
          receivedBytes: loaded,
          totalBytes: total,
        });
      },
    });
    if (opened.ok !== true) {
      downloadManager.reportExternalProgress(assetId, assetName, { state: "error", error: "Setup failed" });
      return { ok: false, reason: "failed", message: opened.message };
    }
    // The session was only ever the vehicle for the download; the tools open
    // their own when they run.
    opened.session.destroy();
    downloadManager.reportExternalProgress(assetId, assetName, { state: "ready" });
    return {
      ok: true,
      message: `Your browser has finished setting up its ${LABELS[kind]}. It now runs on this device with no account and no payment.`,
    };
  } catch (err) {
    const wasCancelled = req.signal?.aborted === true;
    downloadManager.reportExternalProgress(assetId, assetName, {
      state: wasCancelled ? "cancelled" : "error",
      error: wasCancelled ? null : "Setup failed",
    });
    if (wasCancelled) {
      return {
        ok: false,
        reason: "cancelled",
        message: `Setting up your browser's ${LABELS[kind]} was cancelled. Nothing was charged and you can start it again whenever you like.`,
      };
    }
    const failure = toBrowserAiError(kind, err);
    return { ok: false, reason: "failed", message: failure.message };
  }
}
