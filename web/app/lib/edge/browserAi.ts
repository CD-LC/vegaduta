// The browser AI the person ALREADY HAS.
//
// tier0.ts wires exactly one of the browser's built-in AI surfaces: the Prompt
// API (`LanguageModel`). Chrome ships more than that as separate globals —
// `Summarizer`, `Translator` and `LanguageDetector` — and none of them was
// reachable from this codebase before this module (checked 2026-09-12:
// `grep -rn "Summarizer\|Translator\|LanguageDetector" web/ --include=*.ts
// --include=*.tsx` returned only unrelated local `type Translator = (key…)`
// i18n aliases in admin/workflow components and no reference to any of the
// three browser globals).
//
// They matter for the account-less person this wave is about: the weights are
// the BROWSER's, already on the device or fetched by the browser itself, so
// there is nothing for this page to download, nothing to pay for, and no
// WebGPU requirement of ours. That is materially different from tier 1, where
// the person downloads gigabytes through downloadManager, and the tool
// descriptions below say so in those words.
//
// ---------------------------------------------------------------------------
// THE FOUR PROPERTIES, AND HOW THIS FILE MAKES EACH ONE CHECKABLE
// ---------------------------------------------------------------------------
//
//  1. NO SPEND. Every STATIC import in this module is `import type`, which
//     TypeScript erases — the emitted module has no static imports at all, so
//     there is no import path from here to toolBridge, to the API base, or to
//     anything that can reach a billable endpoint. The single runtime
//     dependency (downloadManager, for visible progress) is reached through a
//     dynamic `import()` INSIDE startBrowserAiDownload, which no tool handler
//     calls. browserAi.test.ts asserts both halves: it reads this source and
//     fails on any static value import or network identifier, and it runs
//     every tool with throwing stubs over fetch, XMLHttpRequest, WebSocket,
//     sendBeacon and EventSource.
//
//  2. NO SURPRISE DOWNLOAD. Two halves, and the file needs both.
//
//     (a) The gate asks the right question. A tool handler never calls
//     create(); it calls availability() and, unless the answer is exactly
//     "available", returns a sentence and stops. The probe is given the SAME
//     options bag create() would receive, for every kind — these built-ins'
//     availability is option-dependent, so a probe with no options would
//     answer about the default configuration while create() asked for another
//     one, and the model fetch would happen on the difference.
//
//     (b) The gate cannot be walked around from outside. create() is reached
//     from exactly one module-local function, openSession, and the only way to
//     reach it with downloads permitted is openSessionAllowingDownload, which
//     is NOT exported and requires the literal `userInitiated: true`. What IS
//     exported is openBrowserAiSession, which has no download parameter at all
//     — there is no argument in which a caller could ask for one. So the
//     guarantee holds at the export surface, not merely inside this file.
//     startBrowserAiDownload is the one public entry point that can start a
//     fetch, and it too requires `userInitiated: true` from its caller.
//
//     The test drives every tool against a "downloadable" browser and asserts
//     create() was not called once, reads this source for the export shape,
//     and checks the probe is given the create options.
//
//  3. NO SILENT NETWORK. All three tools are `network: "none"`, and this
//     module contains no network primitive to contradict that (test above).
//     The honest boundary, stated plainly because overclaiming here would be
//     the exact failure this wave is guarding against: what THIS PAGE does is
//     provable from this source — it transmits nothing. What the browser's own
//     implementation does inside its process is the browser's business and not
//     observable from here. These APIs are specified as on-device built-ins,
//     and "the browser does it on this device" is the strongest sentence this
//     file is entitled to write. So every network sentence in the three
//     descriptions and in BROWSER_PROVENANCE is scoped to THIS PAGE — "this
//     page sends nothing over the network" — and never the unscoped "nothing
//     ever leaves your machine", which would be a claim about the browser's
//     own process that this module cannot check. browserAi.test.ts asserts
//     the scoped wording on all three descriptions.
//
//  4. HONEST DEGRADATION. Firefox, Safari, an older Chrome, a Chrome whose
//     model is not downloaded, a Chrome that says "unavailable" without saying
//     why, a language pair the browser will not translate, a detector that is
//     not sure — each is a DEFINED, named state with its own full sentence.
//     None of them is an empty result and none is a thrown error. The one
//     thing that throws is a genuine failure (a timeout, or the browser API
//     itself raising), because that is what "failed" means to the tool loop.
//
// ---------------------------------------------------------------------------
// REFUSALS ARE RETURNED, NOT THROWN  (same reasoning as localToolsFiles.ts)
// ---------------------------------------------------------------------------
// runLocalTool maps a thrown LocalToolRefusal to code "rejected" and anything
// else to "failed". LocalToolRefusal is not exported from localTools.ts, so
// this module cannot construct one; a plain thrown Error would arrive as
// "failed", which means "a defect on our side, do not retry". For "your
// browser does not provide this" that label would be a lie — the tool worked
// perfectly and the answer is "I can't, because X". So every refusal here is a
// normal returned string, which is what reaches the model and through it the
// person. What it costs is the rejected/failed distinction; needsOtherFiles
// asks for LocalToolRefusal to be exported, same ask localToolsWeb.ts and
// localToolsFiles.ts already carry.
//
// ---------------------------------------------------------------------------
// THE CALLER'S TIME CEILING  (read before the registry merge)
// ---------------------------------------------------------------------------
// runLocalTool races every handler against LOCAL_TOOL_TIMEOUT_MS, which is
// 2_000 (read from localTools.ts on 2026-09-12). That constant's own comment
// says it was sized as "three orders of magnitude above what these four
// actually take" — i.e. for pure arithmetic and string work.
//
// I have NOT measured how long Chrome's Summarizer/Translator take, and I
// cannot: these globals do not exist in Node, so this module's whole test
// suite runs against fakes. No number in this file is presented as a
// measurement of them. What IS certain is the shape of the risk: these calls
// run a language model, and 2_000 ms is a plausible thing for one to exceed.
// If it does, runLocalTool abandons the call and answers "did not finish
// within 2000ms" — typed and non-silent, so nothing breaks unsafely, but the
// tool would be unusable.
//
// This module cannot fix that and does not pretend to: BROWSER_AI_BUDGET_MS
// bounds the call INSIDE the handler, but runLocalTool's race is outside it,
// so on a slow browser the caller's 2_000 ms fires first and this file's own
// 30_000 ms ceiling never gets to speak. Widening LOCAL_TOOL_TIMEOUT_MS is not
// something this track may do unilaterally either — it is ONE shared constant
// for every local tool, and raising it to 30_000 would let a wedged arithmetic
// or file handler hold the loop 15x longer. The change that is actually needed
// is a PER-TOOL ceiling, and it is written out in full (exact file, exact
// lines, exact shape) in needsOtherFiles rather than applied here.
// localTools.ts belongs to another track.

import type { LocalTool } from "./localTools";
import {
  type AnyRecord,
  method,
  type BrowserAiKind,
  BrowserAiError,
  BROWSER_AI_BUDGET_MS,
  MAX_SUMMARIZE_CHARS,
  MAX_TRANSLATE_CHARS,
  MAX_DETECT_CHARS,
  DETECT_CONFIDENCE_FLOOR,
  LABELS,
} from "./browserAiSurface";
export { BROWSER_AI_KINDS, BrowserAiError, BROWSER_AI_BUDGET_MS, MAX_SUMMARIZE_CHARS, MAX_TRANSLATE_CHARS, MAX_DETECT_CHARS, DETECT_CONFIDENCE_FLOOR, resolveBrowserAiSurface, browserAiAvailability, getBrowserAiCapabilities } from "./browserAiSurface";
export type { BrowserAiKind, BrowserAiShape, BrowserAiAvailability, BrowserAiFeatureStatus, BrowserAiCapabilitySummary } from "./browserAiSurface";
import {
  toBrowserAiError,
  openBrowserAiSession,
} from "./browserAiSession";
export { BROWSER_AI_ASSET_IDS, openBrowserAiSession, startBrowserAiDownload } from "./browserAiSession";
export type { BrowserAiSessionResult, BrowserAiDownloadRequest, BrowserAiDownloadResult } from "./browserAiSession";



// ---------------------------------------------------------------------------
// Bounded calls
// ---------------------------------------------------------------------------

/**
 * Run one browser call under BROWSER_AI_BUDGET_MS, with a real abort.
 *
 * The controller's signal is handed to create() AND to the
 * summarize/translate/detect call, so a timeout actually asks the browser to
 * stop — unlike tier0.ts's raceDeadline, which can only end the caller's wait
 * and documents that it cannot do more.
 */
async function withBudget<T>(kind: BrowserAiKind, run: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    try {
      controller.abort();
    } catch {
      // Best-effort.
    }
  }, BROWSER_AI_BUDGET_MS);
  try {
    return await run(controller.signal);
  } catch (err) {
    if (timedOut) {
      throw new BrowserAiError(
        kind,
        "timeout",
        `Your browser's ${LABELS[kind]} did not answer within ${BROWSER_AI_BUDGET_MS / 1000} seconds and was stopped.`
      );
    }
    throw toBrowserAiError(kind, err);
  } finally {
    clearTimeout(timer);
  }
}

// ---------------------------------------------------------------------------
// Argument helpers — refusals are RETURNED (see header), so these answer with
// a sentence rather than throwing.
// ---------------------------------------------------------------------------

function readText(
  args: Record<string, unknown>,
  key: string,
  maxChars: number,
  whatFor: string
): { ok: true; value: string } | { ok: false; message: string } {
  const raw = args[key];
  if (typeof raw !== "string") {
    return {
      ok: false,
      message: `"${key}" must be a string of text to ${whatFor} (got ${raw === null ? "null" : typeof raw}).`,
    };
  }
  if (!raw.trim()) {
    return { ok: false, message: `"${key}" is empty, so there is nothing to ${whatFor}.` };
  }
  if (raw.length > maxChars) {
    return {
      ok: false,
      message: `"${key}" is ${raw.length} characters; this tool accepts at most ${maxChars}. Send a shorter passage — it is refused rather than silently cut, because a half-processed passage reads like a complete one.`,
    };
  }
  return { ok: true, value: raw };
}

/**
 * Canonicalise a language tag, KEEPING its script and region subtags.
 *
 * Uses Intl rather than a bundled list, for the reason localTools.ts's
 * assertKnownTimeZone gives about zones: a hand-written allowlist would refuse
 * tags the platform handles perfectly well. Intl.Locale rejects malformed
 * tags, which is the only check this module is entitled to make — whether the
 * BROWSER will translate a given pair is a question only its availability()
 * can answer, and that is asked separately.
 *
 * It returns `baseName` (language[-script][-region]), NOT `.language`. The
 * subtags are not decoration: "zh-Hant" and "zh-Hans" are different writing
 * systems and "pt-BR" and "pt-PT" are different targets, and the browser's
 * Translator takes them. Reducing to the language subtag would hand the
 * browser a pair the person did not ask for while the schema advertises
 * "pt-BR" as a valid value. Extensions (-u-…, -x-…) are dropped, because they
 * carry calendar/numbering preferences rather than a language identity.
 */
function canonicalLanguage(tag: string): string | null {
  try {
    const locale = new Intl.Locale(tag.trim());
    return locale.language && locale.baseName ? locale.baseName : null;
  } catch {
    return null;
  }
}

/**
 * Are these two canonical tags the same language for translation purposes?
 *
 * Equal when the language subtags match AND neither side contradicts the other
 * on script or region — an ABSENT subtag is treated as "unspecified", not as a
 * third value. So "en" vs "en-US" is the same language (nothing to translate),
 * while "pt-BR" vs "pt-PT" and "zh-Hant" vs "zh-Hans" are not, and go to the
 * browser to decide.
 *
 * The unspecified-side call is a judgement and it is stated to the person in
 * the message that uses it: "zh" vs "zh-Hant" answers "already in Chinese",
 * because a bare "zh" gives this module nothing that says otherwise. Someone
 * who meant a script conversion is told to give both tags in full.
 */
function sameLanguage(a: string, b: string): boolean {
  try {
    const left = new Intl.Locale(a);
    const right = new Intl.Locale(b);
    if (left.language !== right.language) return false;
    if (left.script && right.script && left.script !== right.script) return false;
    if (left.region && right.region && left.region !== right.region) return false;
    return true;
  } catch {
    return a === b;
  }
}

function languageName(code: string): string {
  try {
    const names = new Intl.DisplayNames(["en"], { type: "language" });
    const found = names.of(code);
    return found && found !== code ? `${found} (${code})` : code;
  } catch {
    return code;
  }
}

/**
 * The provenance line, and it goes FIRST in every result — before the content,
 * never after it.
 *
 * Position is load-bearing, not style. runLocalTool caps every result with
 * capResult (localTools.ts, read 2026-09-12): over MAX_RESULT_CHARS (2_000) it
 * keeps `text.slice(0, 1920)` and replaces the TAIL with a truncation marker.
 * A disclosure appended at the end is therefore exactly the part a long
 * summary loses — the content survives and the provenance does not, which is
 * the wrong way round for the one sentence that says who did the work and what
 * it cost. At the head it survives every truncation this caller can perform.
 *
 * Every network claim in it is scoped to THIS PAGE, for the reason property 3
 * in the header gives: what the browser's own process does is not observable
 * from here.
 */
const BROWSER_PROVENANCE = "[done by your browser's own built-in model, on this device — no account, no payment, and this page sent nothing over the network]";

// ---------------------------------------------------------------------------
// The tools
//
// Schemas stay SMALL for the same reason localTools.ts's do: every advertised
// tool's whole schema is pasted into the tier-1 system prompt out of the same
// context budget the conversation lives in. The one thing that does NOT get
// trimmed is the sentence saying the browser's own built-in model does the
// work — that is the disclosure, and a shorter description that dropped it
// would be the wrong saving. It matters here in the opposite direction from
// localToolsWeb.ts: there the disclosure is "this leaves your machine", here
// it is "this needs nothing from us, and is not the model you downloaded".
// ---------------------------------------------------------------------------

const SUMMARY_TYPES = ["key-points", "tldr", "teaser", "headline"] as const;
const SUMMARY_LENGTHS = ["short", "medium", "long"] as const;

interface DetectionRow {
  code: string;
  confidence: number;
}

/** Normalise both shapes the detectors return: an array of
 * `{detectedLanguage, confidence}` (modern) and the older
 * `{detectedLanguage, confidence}` list from `createDetector`. Anything that is
 * not a usable row is dropped rather than surfaced as a bogus language. */
function readDetections(raw: unknown): DetectionRow[] {
  if (!Array.isArray(raw)) return [];
  const rows: DetectionRow[] = [];
  for (const entry of raw) {
    if (!entry || typeof entry !== "object") continue;
    const record = entry as AnyRecord;
    const code = typeof record.detectedLanguage === "string" ? record.detectedLanguage : null;
    if (!code) continue;
    const confidence = typeof record.confidence === "number" && Number.isFinite(record.confidence) ? record.confidence : 0;
    rows.push({ code, confidence });
  }
  rows.sort((a, b) => b.confidence - a.confidence);
  return rows;
}

/** Shared by local_detect_language and by local_translate's source-language
 * guess. Returns rows, or a sentence saying why there are none. */
async function detectLanguage(
  text: string
): Promise<{ ok: true; rows: DetectionRow[] } | { ok: false; message: string }> {
  const opened = await openBrowserAiSession("languageDetector", {}, {});
  if (opened.ok !== true) return { ok: false, message: opened.message };
  try {
    // A session object without the method it is supposed to have is a
    // BROWSER shortfall, not a defect on our side, so it is RETURNED like
    // every other "your browser cannot do this" (see the header's refusal
    // section) rather than thrown, which runLocalTool would label "failed".
    const detect = method(opened.session.raw, "detect");
    if (!detect) {
      return {
        ok: false,
        message:
          "Your browser's built-in language detector opened but has no detect() method, so this page cannot use it. That is this browser's version of the API, not an error on this page.",
      };
    }
    const rows = await withBudget("languageDetector", async (signal: AbortSignal) =>
      readDetections(await detect.call(opened.session.raw, text, { signal }))
    );
    return { ok: true, rows };
  } finally {
    opened.session.destroy();
  }
}

export const BROWSER_AI_TOOLS: LocalTool[] = [
  {
    name: "local_summarize",
    description:
      "Summarize a passage using the browser's own built-in model — no account, no download, and this page sends nothing over the network.",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", description: "The passage to summarize" },
        type: { type: "string", enum: [...SUMMARY_TYPES], description: "Default key-points" },
        length: { type: "string", enum: [...SUMMARY_LENGTHS], description: "Default short" },
        context: { type: "string", description: "Optional: who the summary is for" },
      },
      required: ["text"],
      additionalProperties: false,
    },
    network: "none",
    consequential: false,
    run: async (args: Record<string, unknown>): Promise<string> => {
      const text = readText(args, "text", MAX_SUMMARIZE_CHARS, "summarize");
      if (text.ok !== true) return text.message;

      const rawType = args.type;
      if (rawType !== undefined && rawType !== null && !(SUMMARY_TYPES as readonly string[]).includes(String(rawType))) {
        return `"type" must be one of: ${SUMMARY_TYPES.join(", ")}.`;
      }
      const rawLength = args.length;
      if (
        rawLength !== undefined &&
        rawLength !== null &&
        !(SUMMARY_LENGTHS as readonly string[]).includes(String(rawLength))
      ) {
        return `"length" must be one of: ${SUMMARY_LENGTHS.join(", ")}.`;
      }
      const rawContext = args.context;
      if (rawContext !== undefined && rawContext !== null && typeof rawContext !== "string") {
        return '"context" must be a string if given.';
      }

      const createOptions: Record<string, unknown> = {
        type: typeof rawType === "string" ? rawType : "key-points",
        length: typeof rawLength === "string" ? rawLength : "short",
        format: "plain-text",
      };
      if (typeof rawContext === "string" && rawContext.trim()) createOptions.sharedContext = rawContext.trim();

      const opened = await openBrowserAiSession("summarizer", createOptions, {});
      if (opened.ok !== true) return opened.message;
      try {
        // Returned, not thrown — same reason as detectLanguage above.
        const summarize = method(opened.session.raw, "summarize");
        if (!summarize) {
          return "Your browser's built-in summarizer opened but has no summarize() method, so this page cannot use it. That is this browser's version of the API, not an error on this page.";
        }
        const summary = await withBudget("summarizer", async (signal: AbortSignal) => {
          const out: unknown = await summarize.call(opened.session.raw, text.value, { signal });
          // A non-string answer is treated as empty rather than coerced: a
          // stringified object ("[object Object]") reaching the model's
          // context as if it were a summary is worse than the empty-answer
          // branch below, which at least explains itself.
          return typeof out === "string" ? out : "";
        });
        if (!summary.trim()) {
          // An empty answer is a state, not a blank. Saying nothing here would
          // let "the model returned nothing" pass as "there was nothing to
          // say", which is the failure this wave's rule 4 is about.
          return `${BROWSER_PROVENANCE}\nYour browser's built-in summarizer ran but returned an empty summary for this passage. That is the browser's answer, not an error on this page — a longer or more structured passage usually works.`;
        }
        return `${BROWSER_PROVENANCE}\n${summary.trim()}`;
      } finally {
        opened.session.destroy();
      }
    },
  },
  {
    name: "local_translate",
    description:
      "Translate text using the browser's own built-in model — no account, no download, and this page sends nothing over the network.",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", description: "The text to translate" },
        to: { type: "string", description: 'Target language tag, e.g. "es", "hi", "fr"' },
        from: { type: "string", description: "Source language tag; omitted = detect it first" },
      },
      required: ["text", "to"],
      additionalProperties: false,
    },
    network: "none",
    consequential: false,
    run: async (args: Record<string, unknown>): Promise<string> => {
      const text = readText(args, "text", MAX_TRANSLATE_CHARS, "translate");
      if (text.ok !== true) return text.message;

      const rawTo = args.to;
      if (typeof rawTo !== "string" || !rawTo.trim()) {
        return '"to" must be a language tag such as "es", "hi" or "pt-BR".';
      }
      const target = canonicalLanguage(rawTo);
      if (!target) {
        return `"${rawTo}" is not a language tag this page can read. Use a BCP-47 tag such as "es", "hi" or "pt-BR".`;
      }

      const rawFrom = args.from;
      if (rawFrom !== undefined && rawFrom !== null && typeof rawFrom !== "string") {
        return '"from" must be a language tag if given, such as "en".';
      }
      let source: string | null = null;
      let sourceWasGuessed = false;
      let guessConfidence = 0;
      if (typeof rawFrom === "string" && rawFrom.trim()) {
        source = canonicalLanguage(rawFrom);
        if (!source) {
          return `"${rawFrom}" is not a language tag this page can read. Use a BCP-47 tag such as "en" or "ja".`;
        }
      } else {
        const detected = await detectLanguage(text.value);
        if (detected.ok !== true) {
          return `This translation needs to know the source language, and this browser could not work it out: ${detected.message} Pass "from" with the source language tag and it will go ahead.`;
        }
        const top = detected.rows.length > 0 ? detected.rows[0] : null;
        if (!top || top.confidence < DETECT_CONFIDENCE_FLOOR) {
          const best = top ? `${languageName(top.code)} at ${Math.round(top.confidence * 100)}% confidence` : "nothing";
          return `Your browser's detector was not confident enough about the source language (best guess: ${best}; this tool wants at least ${Math.round(DETECT_CONFIDENCE_FLOOR * 100)}% before it acts on a guess). Pass "from" with the source language tag.`;
        }
        source = top.code;
        sourceWasGuessed = true;
        guessConfidence = top.confidence;
      }

      if (sameLanguage(source, target)) {
        const scriptHint =
          source === target
            ? ""
            : ` (Read as the same language because "${source}" and "${target}" do not contradict each other on script or region — if you meant a script or regional conversion, give both tags in full, e.g. "zh-Hans" to "zh-Hant".)`;
        return `The text is already in ${languageName(target)}, so there is nothing to translate.${scriptHint}${sourceWasGuessed ? " (The source language was detected, not given — pass \"from\" if that is wrong.)" : ""}`;
      }

      const createOptions: Record<string, unknown> = { sourceLanguage: source, targetLanguage: target };
      const opened = await openBrowserAiSession("translator", createOptions, {});
      if (opened.ok !== true) {
        // The pair is what was refused, so the message has to name it — a bare
        // "not available" would send the person to look for a browser setting
        // that would not have helped.
        return `${opened.message} (Asked for ${languageName(source)} → ${languageName(target)}; support is per language pair, so another pair may still work.)`;
      }
      try {
        // Returned, not thrown — same reason as detectLanguage above.
        const translate = method(opened.session.raw, "translate");
        if (!translate) {
          return "Your browser's built-in translator opened but has no translate() method, so this page cannot use it. That is this browser's version of the API, not an error on this page.";
        }
        const translated = await withBudget("translator", async (signal: AbortSignal) => {
          const out: unknown = await translate.call(opened.session.raw, text.value, { signal });
          // Same reason as local_summarize: a coerced non-string would read as
          // a translation. Empty falls through to the explained branch below.
          return typeof out === "string" ? out : "";
        });
        if (!translated.trim()) {
          return `${BROWSER_PROVENANCE}\nYour browser's built-in translator ran but returned nothing for this text (${languageName(source)} → ${languageName(target)}). That is the browser's answer, not an error on this page.`;
        }
        const note = sourceWasGuessed
          ? `\n[source language detected as ${languageName(source)} at ${Math.round(guessConfidence * 100)}% confidence, not supplied — pass "from" if that is wrong]`
          : "";
        // note is a disclosure too (the source language was GUESSED), so it
        // goes in the head block with the provenance line for the same
        // truncation reason, not after the translation.
        return `${BROWSER_PROVENANCE}${note}\n${translated.trim()}`;
      } finally {
        opened.session.destroy();
      }
    },
  },
  {
    name: "local_detect_language",
    description:
      "Identify a passage's language using the browser's own built-in model — no account, no download, and this page sends nothing over the network.",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", description: "The passage to identify" },
      },
      required: ["text"],
      additionalProperties: false,
    },
    network: "none",
    consequential: false,
    run: async (args: Record<string, unknown>): Promise<string> => {
      const text = readText(args, "text", MAX_DETECT_CHARS, "identify");
      if (text.ok !== true) return text.message;

      const detected = await detectLanguage(text.value);
      if (detected.ok !== true) return detected.message;
      if (detected.rows.length === 0) {
        // An empty list must never double as "I could not look" — the lookup
        // happened, and this sentence says so.
        return `${BROWSER_PROVENANCE}\nYour browser's built-in detector examined the passage and returned no language at all. The detector ran; it simply had no answer. A longer passage usually gives it enough to work with.`;
      }

      const top = detected.rows[0];
      const ranked = detected.rows
        .slice(0, 3)
        .map((row: DetectionRow) => `${languageName(row.code)} — ${Math.round(row.confidence * 100)}% confidence`);
      const lead =
        top.confidence >= DETECT_CONFIDENCE_FLOOR
          ? `Most likely ${languageName(top.code)}.`
          : `No confident answer — the strongest candidate, ${languageName(top.code)}, is below the ${Math.round(DETECT_CONFIDENCE_FLOOR * 100)}% this tool treats as confident, so read the list as a guess.`;
      return `${BROWSER_PROVENANCE}\n[confidences are the browser detector's own numbers; the "confident" threshold is this tool's choice]\n${lead}\n${ranked.join("\n")}`;
    },
  },
];
