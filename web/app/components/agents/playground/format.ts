import type { StagedFrame } from "../CameraPanel";
import type { HistoryFrame } from "./types";
import { edgeFallbackLabel } from "../../../lib/edge/capabilities";

// Estimated per-call cost is a USD BigDecimal off llm_call_log - often a
// fraction of a cent, so fixed-precision tiers rather than one toFixed:
// $1.23 at a dollar+, $0.042 at a cent+, $0.00042 below that (a real
// hosted turn's typical scale). Never rounded to a misleading "$0.00".
export function formatUsageCost(usd: number): string {
  if (usd >= 1) return `$${usd.toFixed(2)}`;
  if (usd >= 0.01) return `$${usd.toFixed(3)}`;
  return `$${usd.toFixed(5)}`;
}

// Bug fix (2026-08-06 audit): visionFallback.reason was rendered raw
// ("On-device analysis couldn't handle that: model-not-ready") instead of
// through a human-readable label, unlike the text-chat edge-skip pill which
// already runs its reason through edgeFallbackLabel/edgeFallbackShortLabel
// (capabilities.ts). analyzeFramesLocally's contract (lib/edge/vision.ts)
// returns its own kebab-case vocabulary that only overlaps
// edgeFallbackLabel's on "edge-disabled" - the rest (model-not-ready,
// worker-unsupported, etc.) aren't in that switch, so this is vision's own
// mapping rather than a call into capabilities.ts. err?.message fallbacks
// (sendFramesLocally's catch) are already a plain sentence, so the default
// case passes the reason through as-is instead of hiding it.
export function visionFallbackLabel(reason: string | undefined): string {
  switch (reason) {
    case "edge-disabled":
      return edgeFallbackLabel(reason);
    case "worker-unsupported":
      return "this browser can't run on-device vision";
    case "no-frames":
      return "no usable frames were captured";
    case "model-not-ready":
      return "the on-device vision model isn't downloaded yet";
    case "warmup-failed":
      return "the on-device vision model failed to load";
    case "analysis-timeout":
      return "on-device analysis took too long";
    case "empty-vision-reply":
      return "the on-device model didn't return an answer";
    case "worker-failed":
      return "the on-device vision worker crashed";
    case "vision-failed":
    case "analysis-failed":
      return "on-device analysis hit an unexpected error";
    default:
      // Covers sendFramesLocally's err?.message fallback, which is already a
      // plain-language sentence - passing it through beats hiding real detail
      // behind a generic label.
      return reason || "on-device analysis failed";
  }
}

// What a sent turn keeps of its frames for history rendering - the JPEG
// data URL for the thumbnail plus identity, never the raw base64 payload a
// second time (the dataUrl already embeds it once; keeping dataBase64 too
// would double the localStorage weight of every vision turn).
export function framesForHistory(frames: StagedFrame[] | null | undefined): HistoryFrame[] {
  return (frames || []).map((f) => ({ dataUrl: f.dataUrl, fileName: f.fileName, source: f.source }));
}
