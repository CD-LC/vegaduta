// Browser built-in AI: vocabulary, constants, API-surface resolution,
// availability normalisation and the capability summary. Split out of
// browserAi.ts; see that file's header for the guarantees. Self-contained.

import type { PromptApiAvailability } from "./capabilities";

// ---------------------------------------------------------------------------
// Vocabulary
// ---------------------------------------------------------------------------

/** The three built-ins this module wires. `languageDetector` is spelled in
 * camelCase here and matched to BOTH the `LanguageDetector` global and the
 * older `ai.languageDetector` / `translation.canDetect` shapes below. */
export type BrowserAiKind = "summarizer" | "translator" | "languageDetector";

export const BROWSER_AI_KINDS: BrowserAiKind[] = ["summarizer", "translator", "languageDetector"];

/**
 * Which API shape answered.
 *
 *   "modern" — the standalone global (`Summarizer`, `Translator`,
 *              `LanguageDetector`) with `availability()` + `create()`.
 *   "legacy" — the older origin-trial namespaces (`ai.summarizer`,
 *              `ai.languageDetector`, `translation.canTranslate` /
 *              `createTranslator` / `canDetect` / `createDetector`) whose
 *              capability check answers "readily" | "after-download" | "no".
 *   "none"   — neither is present in this browser.
 *
 * Handling both mirrors tier0.ts, which wraps the same two-shape split for the
 * Prompt API (see its header). It is defensive, not a claim about which
 * browser version ships which: the detection asks the object in front of it
 * what methods it has, and no version number appears anywhere in this file.
 */
export type BrowserAiShape = "modern" | "legacy" | "none";

/** Deliberately the SAME five-state vocabulary capabilities.ts already defines
 * for the Prompt API, so a console rendering both does not need two mental
 * models. Imported as a type only (erased at build); the normalizers below are
 * this module's own three-line copies, which is what keeps the emitted module
 * free of static imports — see property 1 in the header. */
export type BrowserAiAvailability = PromptApiAvailability;

export interface BrowserAiFeatureStatus {
  kind: BrowserAiKind;
  /** Short human label, for a console row. */
  label: string;
  shape: BrowserAiShape;
  /** The API surface exists in this browser (whatever it then reports). */
  present: boolean;
  availability: BrowserAiAvailability;
  /** True only when a call would work RIGHT NOW with no download at all. */
  ready: boolean;
  /** True when the browser has the API but must fetch its own model first.
   * The download is the browser's, and startBrowserAiDownload is the only
   * thing in this module that can start it. */
  needsDownload: boolean;
  /**
   * True when this feature's availability genuinely depends on the arguments,
   * so a summary row cannot be the final word. Only the translator: its
   * availability is per language PAIR, and a browser that translates
   * English→Spanish may refuse Tamil→Icelandic.
   */
  perRequest: boolean;
  /** A complete sentence for a person. Never empty, in any state. */
  explanation: string;
}

export interface BrowserAiCapabilitySummary {
  checkedAt: number;
  /** True when at least one of the three is present. */
  anyPresent: boolean;
  /** True when at least one is usable right now with no download. */
  anyReady: boolean;
  features: BrowserAiFeatureStatus[];
  /** One sentence a console can print above the rows without composing its
   * own. Never empty. */
  headline: string;
}

/** A genuine failure — the browser API threw, or our own deadline fired. NOT
 * used for "your browser does not have this", which is a returned sentence. */
export class BrowserAiError extends Error {
  readonly kind: BrowserAiKind;
  readonly reason: "timeout" | "browser-error" | "quota";
  constructor(kind: BrowserAiKind, reason: "timeout" | "browser-error" | "quota", message: string) {
    super(message);
    this.name = "BrowserAiError";
    this.kind = kind;
    this.reason = reason;
  }
}

// ---------------------------------------------------------------------------
// Budgets
// ---------------------------------------------------------------------------

/**
 * Wall-clock ceiling on one call into a browser built-in, ours not the
 * caller's.
 *
 * NOT a measurement, and not presented as one — see the header. It is a hang
 * guard: its job is to stop a browser call that is never going to answer from
 * leaving a tool handler pending forever. It is deliberately generous because
 * guessing LOW here would invent a failure that the browser was not having.
 *
 * Unlike tier0.ts's raceDeadline (which cannot cancel the underlying call and
 * says so), this one CAN: every create/summarize/translate/detect below is
 * given an AbortSignal from the same controller the timer aborts, so a
 * timed-out call is actually asked to stop rather than left running.
 */
export const BROWSER_AI_BUDGET_MS = 30_000;

/**
 * Longest text local_summarize accepts.
 *
 * Chosen to match localTools.ts's own MAX_TEXT_CHARS (20_000, read there on
 * 2026-09-12) so the two tool sets refuse at the same size and a person does
 * not have to learn two limits. Summarizing is the case where a big input is
 * the whole point, and only the ANSWER has to fit the caller's result cap.
 *
 * The browser has its OWN input quota on top of this, which this module does
 * not try to predict — it catches the browser's QuotaExceededError and says so
 * in words instead (see toBrowserAiError).
 */
export const MAX_SUMMARIZE_CHARS = 20_000;

/**
 * Longest text local_translate accepts.
 *
 * Smaller than MAX_SUMMARIZE_CHARS on purpose, and the reason is the caller,
 * not the browser: runLocalTool caps every result at MAX_RESULT_CHARS, which
 * is 2_000 (read from localTools.ts on 2026-09-12), and replaces the tail with
 * a truncation marker. A truncated SUMMARY is still a summary; a truncated
 * TRANSLATION is a sentence cut in half, which is worse than a refusal.
 *
 * 1_500 is a judgement, not a measured expansion ratio — I did not measure how
 * much longer a translation runs than its source, and this file does not claim
 * to know. It leaves headroom under 2_000 for the provenance line and for a
 * target language that needs more characters than the source. Anything longer
 * is REFUSED with a sentence naming the limit, rather than silently cut.
 */
export const MAX_TRANSLATE_CHARS = 1_500;

/**
 * Longest text local_detect_language accepts. A language is identifiable from
 * a passage far shorter than a document, so the cap bounds the call cheaply;
 * the refusal tells the person to pass the opening paragraphs.
 */
export const MAX_DETECT_CHARS = 4_000;

/** Below this the detector's own answer is reported as a guess rather than as
 * a fact, and `from` is not auto-filled for local_translate. 0.5 is a
 * threshold this module picked, not a value the API recommends — it is stated
 * in the result text so a reader can disagree with it. */
export const DETECT_CONFIDENCE_FLOOR = 0.5;

// ---------------------------------------------------------------------------
// Surface detection
//
// Each feature is detected INDEPENDENTLY. A browser can ship one without the
// others, and a summary that reported them as a single "built-in AI: yes/no"
// would be wrong for every such browser. There is no shared "is this Chrome"
// check anywhere in this file, and no user-agent sniffing: the only question
// asked is whether a particular object exists and has the methods needed.
// ---------------------------------------------------------------------------

export interface AnyRecord {
  [key: string]: unknown;
}

function globalObject(kind: string): AnyRecord | null {
  try {
    const found = (globalThis as unknown as AnyRecord)[kind];
    if (found && (typeof found === "object" || typeof found === "function")) {
      return found as unknown as AnyRecord;
    }
  } catch {
    // A getter that throws is the same as absent, for our purposes.
  }
  return null;
}

export function method(host: AnyRecord | null, name: string): ((...args: unknown[]) => unknown) | null {
  if (!host) return null;
  const value = host[name];
  return typeof value === "function" ? (value as (...args: unknown[]) => unknown) : null;
}

interface ResolvedSurface {
  shape: BrowserAiShape;
  /** The object create()/availability() are called ON (`this` matters to these
   * APIs, so every call below goes through `host[name](...)`, never a detached
   * function reference). */
  host: AnyRecord | null;
  availabilityMethod: string | null;
  createMethod: string | null;
  /** "availability" (four-state) vs "capabilities"/"canTranslate"/"canDetect"
   * (the older three-state strings). */
  availabilityStyle: "modern" | "capabilities" | "can";
}

const ABSENT: ResolvedSurface = {
  shape: "none",
  host: null,
  availabilityMethod: null,
  createMethod: null,
  availabilityStyle: "modern",
};

/**
 * Resolve one feature to whatever shape this browser actually has.
 *
 * Exported for the test suite, and because a console that wants to say "you
 * have the old shape of this" needs the same answer the tools use rather than
 * a second, drifting copy of the detection.
 */
export function resolveBrowserAiSurface(kind: BrowserAiKind): ResolvedSurface {
  if (typeof globalThis === "undefined") return ABSENT;

  if (kind === "summarizer") {
    const modern = globalObject("Summarizer");
    if (method(modern, "create")) {
      return {
        shape: "modern",
        host: modern,
        availabilityMethod: method(modern, "availability") ? "availability" : null,
        createMethod: "create",
        availabilityStyle: "modern",
      };
    }
    const ai = globalObject("ai");
    const legacy = ai && ai.summarizer ? (ai.summarizer as AnyRecord) : null;
    if (method(legacy, "create")) {
      return {
        shape: "legacy",
        host: legacy,
        availabilityMethod: method(legacy, "capabilities") ? "capabilities" : null,
        createMethod: "create",
        availabilityStyle: "capabilities",
      };
    }
    return ABSENT;
  }

  if (kind === "translator") {
    const modern = globalObject("Translator");
    if (method(modern, "create")) {
      return {
        shape: "modern",
        host: modern,
        availabilityMethod: method(modern, "availability") ? "availability" : null,
        createMethod: "create",
        availabilityStyle: "modern",
      };
    }
    const translation = globalObject("translation");
    if (method(translation, "createTranslator")) {
      return {
        shape: "legacy",
        host: translation,
        availabilityMethod: method(translation, "canTranslate") ? "canTranslate" : null,
        createMethod: "createTranslator",
        availabilityStyle: "can",
      };
    }
    return ABSENT;
  }

  // languageDetector
  const modern = globalObject("LanguageDetector");
  if (method(modern, "create")) {
    return {
      shape: "modern",
      host: modern,
      availabilityMethod: method(modern, "availability") ? "availability" : null,
      createMethod: "create",
      availabilityStyle: "modern",
    };
  }
  const ai = globalObject("ai");
  const legacyAi = ai && ai.languageDetector ? (ai.languageDetector as AnyRecord) : null;
  if (method(legacyAi, "create")) {
    return {
      shape: "legacy",
      host: legacyAi,
      availabilityMethod: method(legacyAi, "capabilities") ? "capabilities" : null,
      createMethod: "create",
      availabilityStyle: "capabilities",
    };
  }
  const translation = globalObject("translation");
  if (method(translation, "createDetector")) {
    return {
      shape: "legacy",
      host: translation,
      availabilityMethod: method(translation, "canDetect") ? "canDetect" : null,
      createMethod: "createDetector",
      availabilityStyle: "can",
    };
  }
  return ABSENT;
}

// ---------------------------------------------------------------------------
// Availability
// ---------------------------------------------------------------------------

/** Four-state answer from a modern `availability()`. Anything unrecognised is
 * "unknown", never optimistically "available" — the same mapping
 * capabilities.ts's normalizeAvailability uses, restated here so this module
 * needs no runtime import (header, property 1). */
function normalizeModern(value: unknown): BrowserAiAvailability {
  return value === "available" || value === "downloadable" || value === "downloading" || value === "unavailable"
    ? value
    : "unknown";
}

/** The older three-state strings, mapped onto the same vocabulary — matching
 * capabilities.ts's normalizeLegacyAvailability. `canTranslate`/`canDetect`
 * return the bare string; `capabilities()` returns `{ available: <string> }`,
 * so both are unwrapped here. */
function normalizeLegacy(value: unknown): BrowserAiAvailability {
  const raw =
    value && typeof value === "object" && "available" in (value as AnyRecord)
      ? (value as AnyRecord).available
      : value;
  if (raw === "readily") return "available";
  if (raw === "after-download") return "downloadable";
  if (raw === "no") return "unavailable";
  return "unknown";
}

/**
 * Live availability for one feature. Always queried fresh (it is cheap, and
 * its answer changes while the browser downloads its own model) — the same
 * posture tier0Availability takes, and deliberately NOT the sessionStorage
 * cache capabilities.ts uses for its one-per-session pass.
 *
 * Never rejects. A browser that throws out of availability() is reported
 * "unknown", which the explanation below turns into "would not say", not into
 * a false "unavailable" and not into a false "available".
 *
 * `options` is passed through to a modern `availability()` and to the legacy
 * `canTranslate()`. For the translator that is the language pair, and it is
 * the reason translator availability cannot be answered once for the session.
 */
export async function browserAiAvailability(
  kind: BrowserAiKind,
  options?: Record<string, unknown>
): Promise<BrowserAiAvailability> {
  const surface = resolveBrowserAiSurface(kind);
  if (surface.shape === "none") return "unavailable";
  if (!surface.availabilityMethod) {
    // The create() method exists but there is no way to ask first. Saying
    // "available" here would be this module inventing an answer, and the
    // tools treat anything but "available" as do-not-call — which is the safe
    // direction: no create(), no download.
    return "unknown";
  }
  try {
    const fn = method(surface.host, surface.availabilityMethod);
    if (!fn) return "unknown";
    const raw = await (options === undefined
      ? (fn.call(surface.host) as unknown)
      : (fn.call(surface.host, options) as unknown));
    return surface.availabilityStyle === "modern" ? normalizeModern(raw) : normalizeLegacy(raw);
  } catch {
    // Includes the real case where a modern availability() REQUIRES its
    // options bag and throws without one — which is exactly why the summary
    // marks the translator perRequest instead of printing a state it guessed.
    return "unknown";
  }
}

// ---------------------------------------------------------------------------
// The capability summary a console can render
// ---------------------------------------------------------------------------

export const LABELS: Record<BrowserAiKind, string> = {
  summarizer: "built-in summarizer",
  translator: "built-in translator",
  languageDetector: "built-in language detector",
};

/**
 * A full sentence for every state, including the states that are easy to leave
 * as a blank row. "Silence is the bug" is the rule this function exists to
 * satisfy: there is no branch here that returns "".
 */
export function explain(kind: BrowserAiKind, shape: BrowserAiShape, availability: BrowserAiAvailability): string {
  const label = LABELS[kind];
  if (shape === "none") {
    return `This browser does not provide a ${label}. It is a feature of the browser itself, so it appears only where the browser ships it — there is nothing to install on this page, and nothing is broken.`;
  }
  const old =
    shape === "legacy" ? " (via this browser's older version of the API, which this page also supports)" : "";
  switch (availability) {
    case "available":
      return `Your browser provides a ${label}${old} and it is ready now — it runs on your device, using the browser's own model, with no account, no payment and no download from this page.`;
    case "downloadable":
      return `Your browser provides a ${label}${old}, but it has to fetch its own model first. That download belongs to the browser, not to this page, and it starts only when you ask for it.`;
    case "downloading":
      return `Your browser is fetching the model for its ${label}${old} right now. It will start working once that finishes.`;
    case "unavailable":
      return `Your browser has a ${label}${old} but reports that it cannot run it here. The browser does not say why, so this page cannot tell you either.`;
    default:
      return `Your browser exposes a ${label}${old} but would not say whether it can run it. Treat that as unproven: this page will not call it until it says "available".`;
  }
}

/**
 * What this browser gives the person for free, feature by feature.
 *
 * Asks each of the three separately and never short-circuits: a browser with a
 * detector and no summarizer gets two accurate rows, not one wrong verdict.
 * Never rejects — each probe is already non-throwing, and the whole thing is
 * safe to call during render or on the server (where it answers three
 * "not provided" rows rather than blowing up).
 */
export async function getBrowserAiCapabilities(): Promise<BrowserAiCapabilitySummary> {
  const features: BrowserAiFeatureStatus[] = [];
  for (const kind of BROWSER_AI_KINDS) {
    const surface = resolveBrowserAiSurface(kind);
    const present = surface.shape !== "none";
    // The translator's availability is per language pair. Asking it here with
    // no pair would either throw (modern) or answer about a pair we invented
    // (legacy), so the summary reports presence and says the pair decides.
    let availability: BrowserAiAvailability;
    if (!present) {
      availability = "unavailable";
    } else if (kind === "translator") {
      availability = "unknown";
    } else {
      availability = await browserAiAvailability(kind);
    }
    const explanation =
      kind === "translator" && present
        ? `Your browser provides a ${LABELS.translator}${surface.shape === "legacy" ? " (via its older API, which this page also supports)" : ""}. Whether a given translation works depends on the language pair, so it is checked when you ask for one — this row cannot answer it in advance.`
        : explain(kind, surface.shape, availability);
    features.push({
      kind,
      label: LABELS[kind],
      shape: surface.shape,
      present,
      availability,
      ready: availability === "available",
      needsDownload: availability === "downloadable" || availability === "downloading",
      perRequest: kind === "translator",
      explanation,
    });
  }

  const anyPresent = features.some((f) => f.present === true);
  const anyReady = features.some((f) => f.ready === true);
  const readyNames = features.filter((f) => f.ready === true).map((f) => f.label);
  // A perRequest feature (today: the translator) is deliberately left at
  // "unknown"/ready false above, because its real answer depends on the
  // language pair. That makes "none is ready yet - each row says what it is
  // waiting for" a sentence the rows themselves contradict: the translator row
  // is not waiting for anything, it simply cannot be answered in advance. So
  // the headline names the per-request features separately rather than
  // sweeping them into the waiting set.
  const perRequestNames = features.filter((f) => f.present === true && f.perRequest === true).map((f) => f.label);
  const waitingCount = features.filter((f) => f.present === true && f.perRequest !== true).length;
  let headline: string;
  if (!anyPresent) {
    headline =
      "This browser provides none of the built-in AI features this page can use. That is a property of the browser, not of your account — everything else on this page still works.";
  } else if (anyReady) {
    headline = `Your browser can do this much on its own, with no account and no payment: ${readyNames.join(", ")}.`;
  } else if (perRequestNames.length > 0 && waitingCount === 0) {
    headline = `Your browser provides ${perRequestNames.join(", ")}, with no account and no payment. Whether a given request can run is decided per request — ask for one and it will tell you.`;
  } else if (perRequestNames.length > 0) {
    headline = `None of your browser's built-in AI features is ready to run yet — each row below says what it is waiting for, except ${perRequestNames.join(", ")}, which is decided per request rather than in advance.`;
  } else {
    headline =
      "Your browser has these built-in AI features but none is ready to run yet — each row below says what it is waiting for.";
  }

  return { checkedAt: Date.now(), anyPresent, anyReady, features, headline };
}
