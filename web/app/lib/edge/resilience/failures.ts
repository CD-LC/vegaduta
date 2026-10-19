// On-device download failure taxonomy (2026-09-19).
//
// WHY THIS FILE EXISTS. On-device models are downloaded by the BROWSER from
// hosts this platform does not run: huggingface.co (+ its *.hf.co CDNs) for
// weights/tokenizers, raw.githubusercontent.com for WebLLM's compiled model
// libraries, cdn.jsdelivr.net for ONNX Runtime's wasm. Any of them can be
// blocked by a corporate proxy, rate-limited, slow, or simply down for one
// real user today. Before this file every such failure collapsed into ONE
// string — EDGE_BRANDING.interruptedError, "Download interrupted — resume to
// continue" — shown even when the actual cause was "no WebGPU", "disk full" or
// "the model host is blocked on this network", with a Retry button that for
// WebLLM downloads did nothing at all (see downloadManager.resume's 2026-09-19
// comment). The person could not tell what happened or what to do.
//
// The contract here: every failure maps to ONE typed reason, and every reason
// maps to a plain-language sentence plus exactly ONE primary next action.
// Independently of the reason, the hosted model is always offered as the
// second button (HOSTED_FALLBACK_*), because a failed on-device setup must
// never leave someone unable to chat.
//
// Naming stays consistent with tierRouter's HostedFallthroughReason /
// capabilities.ts edgeFallbackLabel: lowercase kebab-case codes, plain English
// (not t()) sentences — same precedent as the rest of the edge UI, and
// the messages/*.json catalogue is deliberately not touched from here.
//
// PURE MODULE: no DOM, no network, no imports of app code. Safe in workers.

export type EdgeDownloadFailureReason =
  /** NEXT_PUBLIC_EDGE_AGENTS is off for this deployment. */
  | "edge-disabled"
  /** No usable WebGPU adapter — WebLLM (tier 1) cannot run here at all. */
  | "no-webgpu"
  /** No WebAssembly — nothing on-device can run (very old / locked-down browser). */
  | "no-wasm"
  /** No Cache API (private windows in some browsers, insecure origin). */
  | "no-cache-storage"
  /** navigator.onLine is false. */
  | "offline"
  /** Online, but the model host did not answer (blocked / down / DNS). */
  | "host-unreachable"
  /** storage.estimate() says the model will not fit. Refused BEFORE any byte moves. */
  | "quota-too-small"
  /** The browser refused a cache write mid-download (quota hit for real). */
  | "storage-write-failed"
  /** The connection dropped mid-transfer; already-downloaded parts are kept. */
  | "network-interrupted"
  /** No progress signal for the stall window, so the caller stopped WAITING.
   * downloadManager's own engine aborts the transfer; the WebLLM path cannot
   * (the library has no abort), so there the download may still be running. */
  | "stalled"
  /** The hard overall ceiling was hit. */
  | "timed-out"
  /** A published checksum did not match. The bad bytes were discarded. */
  | "integrity-failed"
  /** The download reported success but the cached file set is not complete. */
  | "incomplete"
  /** Cached files were written for an older engine / registry version and no longer load. */
  | "version-mismatch"
  /** An installed model failed to load more than once under the SAME engine
   * version (or an unknown one). The files are suspect; the only fix offered
   * deletes them and downloads the whole model again. */
  | "cache-unloadable"
  /** The GPU ran out of memory (or was lost) while loading the model. */
  | "device-memory"
  /** The registry no longer lists this model, or the engine cannot serve it. */
  | "model-unavailable"
  /** The person cancelled. Not an error — never rendered as one. */
  | "cancelled"
  | "unknown";

/** The ONE primary action a failure offers. "use-hosted" is ALSO always
 * available as the secondary button regardless of this value. */
export type EdgeDownloadNextAction =
  | "retry"
  | "pick-smaller-model"
  | "free-space"
  | "redownload"
  | "use-hosted";

export interface EdgeDownloadFailureDescription {
  reason: EdgeDownloadFailureReason;
  /** Short heading, no trailing punctuation. */
  title: string;
  /** One or two plain sentences. Never contains a raw error string. */
  message: string;
  action: EdgeDownloadNextAction;
  actionLabel: string;
  /** True when retrying the SAME thing can plausibly succeed. */
  retryable: boolean;
}

/** The always-present escape hatch. Wording matches routingState.ts
 * ROUTING_MODES.hosted ("Hosted") so the button names the mode it selects. */
export const HOSTED_FALLBACK_LABEL = "Use the hosted model";
export const HOSTED_FALLBACK_NOTE = "Hosted replies keep working either way.";

export interface EdgeDownloadFailureContext {
  /** Bytes the model needs (manifest sizeBytes). */
  neededBytes?: number;
  /** Bytes the browser says are free for this origin. */
  freeBytes?: number;
  /** A smaller model than the failed one exists and fits this device. */
  smallerModelAvailable?: boolean;
  /** Hostname that did not answer, e.g. "huggingface.co". */
  host?: string;
}

/** Decimal units, matching tier1.ts formatSize / the marketplace cards. */
export function formatBytesPlain(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 MB";
  if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
  return `${Math.max(1, Math.round(bytes / 1_000_000))} MB`;
}

/**
 * Reason → sentence + one action. Total: every reason (and any unexpected
 * string, via the default branch) returns a usable description, so a caller
 * can never end up rendering nothing.
 */
export function describeDownloadFailure(
  reason: EdgeDownloadFailureReason | string | undefined,
  ctx: EdgeDownloadFailureContext = {}
): EdgeDownloadFailureDescription {
  const smaller = ctx.smallerModelAvailable === true;
  switch (reason) {
    case "edge-disabled":
      return {
        reason,
        title: "On-device is off",
        message: "On-device models are turned off for this deployment.",
        action: "use-hosted",
        actionLabel: HOSTED_FALLBACK_LABEL,
        retryable: false,
      };
    case "no-webgpu":
      return {
        reason,
        title: "This browser can't run this model",
        message:
          "This model needs WebGPU, and this browser or device doesn't provide it. " +
          "A current desktop Chrome or Edge usually does.",
        action: "use-hosted",
        actionLabel: HOSTED_FALLBACK_LABEL,
        retryable: false,
      };
    case "no-wasm":
      return {
        reason,
        title: "This browser can't run on-device models",
        message: "WebAssembly is unavailable here, so nothing can run on this device.",
        action: "use-hosted",
        actionLabel: HOSTED_FALLBACK_LABEL,
        retryable: false,
      };
    case "no-cache-storage":
      return {
        reason,
        title: "This window can't store a model",
        message:
          "The browser isn't allowing downloads to be kept in this window. " +
          "Private/incognito windows often block it — a normal window usually works.",
        action: "use-hosted",
        actionLabel: HOSTED_FALLBACK_LABEL,
        retryable: false,
      };
    case "offline":
      return {
        reason,
        title: "You're offline",
        message: "The model has to be downloaded once while you're online. Nothing was lost.",
        action: "retry",
        actionLabel: "Try again",
        retryable: true,
      };
    case "host-unreachable":
      return {
        reason,
        title: "Couldn't reach the model host",
        message:
          `${ctx.host || "The model host"} didn't respond from this network. ` +
          "Work networks and some VPNs block it — another network may work.",
        action: "retry",
        actionLabel: "Try again",
        retryable: true,
      };
    case "quota-too-small": {
      const needs =
        ctx.neededBytes && ctx.neededBytes > 0 ? ` It needs about ${formatBytesPlain(ctx.neededBytes)}` : "";
      const has =
        needs && ctx.freeBytes != null && ctx.freeBytes >= 0
          ? `, and this browser has about ${formatBytesPlain(ctx.freeBytes)} free.`
          : needs
            ? "."
            : "";
      return {
        reason,
        title: "Not enough free space",
        message: `This model won't fit on this device, so the download wasn't started.${needs}${has}`,
        action: smaller ? "pick-smaller-model" : "free-space",
        actionLabel: smaller ? "Pick a smaller model" : "Free up space",
        retryable: false,
      };
    }
    case "storage-write-failed":
      return {
        reason,
        title: "The device ran out of space",
        message:
          "The browser stopped accepting the download partway through. " +
          "The parts already downloaded are kept.",
        action: smaller ? "pick-smaller-model" : "free-space",
        actionLabel: smaller ? "Pick a smaller model" : "Free up space",
        retryable: false,
      };
    case "network-interrupted":
      return {
        reason,
        title: "The download was interrupted",
        message: "The connection dropped. The parts already downloaded are kept, so retrying continues from there.",
        action: "retry",
        actionLabel: "Resume download",
        retryable: true,
      };
    case "stalled":
      return {
        reason,
        title: "The download stopped making progress",
        // "We stopped waiting", never "it was stopped": on the WebLLM path
        // nothing can be stopped, and a download that is merely very slow
        // carries on and can still finish (the surfaces clear this message
        // when it does).
        message:
          "Nothing new arrived for a long while, so we stopped waiting. " +
          "The parts already downloaded are kept. If trying again does not move, reload this page and start the download again: it picks up from the saved parts.",
        action: "retry",
        actionLabel: "Try again",
        retryable: true,
      };
    case "timed-out":
      return {
        reason,
        title: "The download took too long",
        message:
          "It didn't finish in time on this connection. Downloaded parts are kept" +
          (smaller ? ", or a smaller model will finish sooner." : "."),
        action: smaller ? "pick-smaller-model" : "retry",
        actionLabel: smaller ? "Pick a smaller model" : "Resume download",
        retryable: true,
      };
    case "integrity-failed":
      return {
        reason,
        title: "The download didn't verify",
        message: "The files didn't match their checksum, so they were discarded. Nothing unverified was kept.",
        action: "retry",
        actionLabel: "Download again",
        retryable: true,
      };
    case "incomplete":
      return {
        reason,
        title: "The download is incomplete",
        message: "Some model files are missing on this device, so it wasn't marked ready. Retrying fetches only what's missing.",
        action: "retry",
        actionLabel: "Finish download",
        retryable: true,
      };
    case "version-mismatch": {
      // Only produced when an install STAMP names a different engine/registry
      // version AND a load has failed since (cacheCompat.ts) — so "earlier
      // version" is a recorded fact here, never a guess.
      const size =
        ctx.neededBytes && ctx.neededBytes > 0 ? ` (about ${formatBytesPlain(ctx.neededBytes)})` : "";
      return {
        reason,
        title: "This model needs to be downloaded again",
        message:
          "It was downloaded for an earlier version of the on-device engine and no longer loads. " +
          `Downloading it again deletes the stored files and fetches the whole model${size}.`,
        action: "redownload",
        actionLabel: "Download again",
        retryable: true,
      };
    }
    case "cache-unloadable": {
      const size =
        ctx.neededBytes && ctx.neededBytes > 0 ? ` (about ${formatBytesPlain(ctx.neededBytes)})` : "";
      return {
        reason,
        title: "This model keeps failing to load",
        message:
          "It is stored on this device but has failed to load more than once. " +
          `Downloading it again deletes the stored files and fetches the whole model${size}.`,
        action: "redownload",
        actionLabel: "Download again",
        retryable: true,
      };
    }
    case "device-memory":
      return {
        reason,
        title: "The device ran out of graphics memory",
        message:
          "The model couldn't be loaded onto the GPU. Closing other tabs can help" +
          (smaller ? ", or a smaller model needs less memory." : "."),
        action: smaller ? "pick-smaller-model" : "retry",
        actionLabel: smaller ? "Pick a smaller model" : "Try again",
        retryable: true,
      };
    case "model-unavailable":
      return {
        reason,
        title: "This model isn't available",
        message: "It's no longer in the on-device model list for this version of the app.",
        action: "pick-smaller-model",
        actionLabel: "Pick another model",
        retryable: false,
      };
    case "cancelled":
      return {
        reason,
        title: "Download cancelled",
        message: "Downloaded parts are kept, so starting again continues from there.",
        action: "retry",
        actionLabel: "Resume download",
        retryable: true,
      };
    case "unknown":
    default:
      return {
        reason: "unknown",
        title: "On-device setup didn't finish",
        message: "Something went wrong while setting up the model. Downloaded parts are kept.",
        action: "retry",
        actionLabel: "Try again",
        retryable: true,
      };
  }
}

/** One-line form for the compact status rows (chip, marketplace card) that
 * have room for a single truncated string. */
export function downloadFailureOneLine(
  reason: EdgeDownloadFailureReason | string | undefined,
  ctx: EdgeDownloadFailureContext = {}
): string {
  return describeDownloadFailure(reason, ctx).title;
}

export interface ClassifyFailureInput {
  /** The thrown value, when there is one. */
  error?: unknown;
  /** navigator.onLine at the time of failure (undefined = unknown). */
  online?: boolean;
  /** HTTP status of the failing response, when there was one. */
  httpStatus?: number;
  /** The watchdog that fired, when one did. */
  watchdog?: "stalled" | "timed-out";
  /** The person paused/cancelled. */
  userCancelled?: boolean;
}

function errorName(err: unknown): string {
  return err instanceof Error ? err.name : "";
}

function errorText(err: unknown): string {
  if (err instanceof Error) {
    // Tier1Error keeps the library's original message in `detail`.
    const detail = (err as { detail?: unknown }).detail;
    return `${err.message} ${typeof detail === "string" ? detail : ""}`;
  }
  return err == null ? "" : String(err);
}

/**
 * Best-effort mapping of "whatever was thrown" to a typed reason. Order
 * matters: explicit signals (watchdog, cancel, offline) beat string sniffing,
 * because library error text is not an API and changes between releases —
 * anything unrecognised is honestly "unknown", never guessed.
 */
export function classifyDownloadFailure(input: ClassifyFailureInput): EdgeDownloadFailureReason {
  if (input.userCancelled) return "cancelled";
  if (input.watchdog) return input.watchdog;
  const name = errorName(input.error);
  const text = errorText(input.error).toLowerCase();
  if (name === "VerificationError") return "integrity-failed";
  if (name === "QuotaExceededError" || /quota|storage.*(full|exceed)|shard write failed/.test(text)) {
    return "storage-write-failed";
  }
  if (name === "DeviceLostError" || /device.{0,12}lost|out of memory|\boom\b/.test(text)) return "device-memory";
  if (input.online === false) return "offline";
  if (name === "AbortError") return "cancelled";
  const status = input.httpStatus;
  if (typeof status === "number" && status > 0) {
    if (status === 404 || status === 410) return "model-unavailable";
    // 401/403 from a CDN is what a filtering proxy answers; 429/5xx is the
    // host itself struggling. Both are "couldn't get it from there right now".
    return "host-unreachable";
  }
  // fetch() rejects with TypeError for DNS/TLS/CORS/blocked-by-client alike;
  // Chrome says "Failed to fetch", Firefox "NetworkError when attempting…",
  // Safari "Load failed".
  if (name === "TypeError" || /failed to fetch|networkerror|load failed|network/.test(text)) {
    return "network-interrupted";
  }
  if (/cannot find model record|model.*not found|modelnotfound/.test(text)) return "model-unavailable";
  if (/webgpu/.test(text) && /not (supported|available)|unavailable|no available/.test(text)) return "no-webgpu";
  return "unknown";
}

/** Reasons where an AUTOMATIC retry (with backoff) is worth it. Everything
 * else either needs the person to do something first or will fail the same
 * way again, so retrying it silently only delays the message.
 *
 * "offline" IS transient: the commonest real drop (wifi roam, laptop lid) has
 * navigator.onLine === false at the instant the fetch rejects, so excluding it
 * meant exactly those drops were never retried. Callers must not spin while
 * offline — pair this with retry.ts waitForOnline (bounded) before the next
 * attempt, and give up with the "offline" message if the link stays down. */
export function isTransientDownloadFailure(reason: EdgeDownloadFailureReason): boolean {
  return reason === "network-interrupted" || reason === "host-unreachable" || reason === "offline";
}
