// Voice-output chain for a call reply: which voice speaks it, in what order,
// and what happens when one of them fails. PURE - no DOM, no workers, no
// network - so every rule below is unit-tested (voiceOutput.test.ts). The
// browser side (the <audio> element, Kokoro, the Web Speech API) lives in
// voicePlayers.ts and is handed in as `runners`.
//
// WHY THIS EXISTS (production evidence, 2026-09-19, real tenants): every
// VoiceCallController speak/turn request logged "ElevenLabs TTS generation
// failed (credential...)" then "Sarvam TTS synthesis failed: No active Sarvam
// AI credential configured" and completed TEXT-ONLY. Both server voices are
// per-tenant BYOK and consumers have neither, so a caller heard the browser's
// robotic Web Speech voice - or nothing at all where Web Speech never starts.
// Kokoro was already vendored for in-browser speech but CallPanel only used it
// for people who had separately switched "on-device replies" on, and went
// straight from "no server audio" to the Web Speech API for everyone else.
//
// The chain, in order (planVoiceOutput):
//   server audio (when the reply carries some)
//   -> Kokoro in the browser (when it is installed on this device)
//   -> the browser's Web Speech API
//   -> text only (always reachable; the reply text is rendered by the caller
//      BEFORE any of this runs, so no step here can ever hide it).
// A person who turned on-device replies on keeps the older E-4 order (Kokoro
// first - "every Kokoro synth is a Sarvam/ElevenLabs bill that never happens").
//
// Every step falls through on ANY failure and is bounded in time twice
// (runVoiceChain): a START deadline (nothing audible yet -> try the next
// voice) and a PLAYBACK ceiling (Chrome's speechSynthesis is known to never
// fire `onend` for some utterances - without a ceiling the call's "Speaking..."
// stage, and the queue behind it, would hang forever).

export type VoiceStep = "server-audio" | "kokoro" | "web-speech";

export interface VoicePlanInput {
  /** The sentence (or reply) to speak. Kokoro and Web Speech need it. */
  text: string | null | undefined;
  /** The server already synthesized audio for this fragment. */
  hasServerAudio: boolean;
  /** An <audio> element exists to play server/Kokoro audio through. */
  hasAudioElement: boolean;
  /** edgeEnabled() - the edge surface is compiled into this build. */
  edgeOn: boolean;
  /** Kokoro is installed on this device (isTtsModelCached() or warm). */
  kokoroInstalled: boolean;
  /** The person turned on-device replies on (getEdgeOptIn()). */
  preferOnDevice: boolean;
  /** The caller's explicit voice pick (lib/voice/voiceover.ts): the leg that
   * owns the picked voice goes FIRST, ahead of even server audio - "he
   * selected the voice over" means exactly that. Only reorders; a step that
   * was not planned anyway (kokoro excluded for non-Latin text, no
   * speechSynthesis) is not conjured into the plan. */
  preferredStep?: VoiceStep;
  /** window.speechSynthesis exists. It may still never start (no voices) -
   * that is what the start deadline is for. */
  webSpeechPresent: boolean;
  /** Kokoro already missed its start deadline earlier in THIS reply. Its
   * abandoned synth is still running on kokoro.ts's serialized queue, so the
   * next fragment would only wait behind it and time out too. */
  skipKokoro?: boolean;
}

/**
 * Can Kokoro say this? kokoro-js ships English voices and an English
 * phonemizer only. Now that Kokoro is the DEFAULT voice rather than something
 * a person opted into, a Hindi/Kannada/Tamil/Arabic/CJK reply must not be
 * routed to it (it reads non-Latin script as silence or noise). Such a reply
 * goes to the browser's Web Speech API instead, which speaks it ONLY when the
 * device has a voice for that language (voicePlayers.ts sets the utterance
 * language from speechLangForText and refuses when the browser lists voices
 * and none matches - the reply is then text-only, and the panel says so).
 * Heuristic on purpose: of the LETTERS in the text, at least 70% are Latin
 * script. Digits, punctuation and emoji don't count either way.
 */
export function isLatinScriptText(text: string | null | undefined): boolean {
  const letters = (text || "").match(/\p{L}/gu);
  if (!letters || letters.length === 0) return false;
  let latin = 0;
  for (const ch of letters) {
    if (/\p{Script=Latin}/u.test(ch)) latin++;
  }
  return latin / letters.length >= 0.7;
}

/** Scripts we can name a BCP-47 speech language for. Arabic script is also
 * Urdu/Persian/Sindhi and Han is also Japanese kanji - "ar" / "zh-CN" are the
 * most likely reading, not a certainty; kana anywhere in the text wins over
 * Han so Japanese is not read as Chinese. */
const SCRIPT_SPEECH_LANGS: [RegExp, string][] = [
  [/\p{Script=Devanagari}/u, "hi-IN"],
  [/\p{Script=Kannada}/u, "kn-IN"],
  [/\p{Script=Tamil}/u, "ta-IN"],
  [/\p{Script=Telugu}/u, "te-IN"],
  [/\p{Script=Malayalam}/u, "ml-IN"],
  [/\p{Script=Bengali}/u, "bn-IN"],
  [/\p{Script=Gujarati}/u, "gu-IN"],
  [/\p{Script=Gurmukhi}/u, "pa-IN"],
  [/\p{Script=Oriya}/u, "or-IN"],
  [/\p{Script=Arabic}/u, "ar"],
  [/\p{Script=Hangul}/u, "ko-KR"],
  [/\p{Script=Han}/u, "zh-CN"],
  [/\p{Script=Cyrillic}/u, "ru-RU"],
  [/\p{Script=Thai}/u, "th-TH"],
  [/\p{Script=Hebrew}/u, "he-IL"],
  [/\p{Script=Greek}/u, "el-GR"],
];

/**
 * The speech language to ask the browser's Web Speech API for, judged from the
 * text's dominant script. Returns null for Latin-script text (English, Spanish
 * and French all look alike here, so the browser's default voice is left
 * alone), for text with no letters, and for a script not in the table above.
 */
export function speechLangForText(text: string | null | undefined): string | null {
  const letters = (text || "").match(/\p{L}/gu);
  if (!letters || letters.length === 0) return null;
  if (isLatinScriptText(text)) return null;
  if (letters.some((ch) => /\p{Script=Hiragana}|\p{Script=Katakana}/u.test(ch))) return "ja-JP";
  const counts = new Map<string, number>();
  for (const ch of letters) {
    for (const [pattern, lang] of SCRIPT_SPEECH_LANGS) {
      if (pattern.test(ch)) {
        counts.set(lang, (counts.get(lang) || 0) + 1);
        break;
      }
    }
  }
  let best: string | null = null;
  let bestCount = 0;
  for (const [lang, count] of counts) {
    if (count > bestCount) {
      best = lang;
      bestCount = count;
    }
  }
  return best;
}

/** Ordered list of voices worth TRYING for this fragment. An empty list means
 * text-only, and is a valid answer. Never downloads or probes anything. */
export function planVoiceOutput(input: VoicePlanInput): VoiceStep[] {
  const hasText = !!(input.text && input.text.trim());
  const server = input.hasServerAudio && input.hasAudioElement;
  const kokoro =
    hasText &&
    !input.skipKokoro &&
    input.edgeOn &&
    input.kokoroInstalled &&
    input.hasAudioElement &&
    isLatinScriptText(input.text);
  const webSpeech = hasText && input.webSpeechPresent;
  const steps: VoiceStep[] = [];
  if (kokoro && input.preferOnDevice) steps.push("kokoro");
  if (server) steps.push("server-audio");
  if (kokoro && !input.preferOnDevice) steps.push("kokoro");
  if (webSpeech) steps.push("web-speech");
  // An explicit voice pick promotes its leg to the front; everything else
  // keeps the order above as the fallback tail.
  if (input.preferredStep) {
    const idx = steps.indexOf(input.preferredStep);
    if (idx > 0) {
      steps.splice(idx, 1);
      steps.unshift(input.preferredStep);
    }
  }
  return steps;
}

// ---------------------------------------------------------------------------
// Running the chain
// ---------------------------------------------------------------------------

export interface StepControl {
  /** The runner MUST call this the moment sound is actually coming out (the
   * <audio> element's `playing`, the utterance's `onstart`). It disarms the
   * start deadline and arms the playback ceiling. */
  started(): void;
  /** True once the chain has given up on this step (deadline) - a late
   * result must not start playing over the next voice. */
  isCancelled(): boolean;
  /** Register how to silence this step if the chain gives up on it. */
  onCancel(fn: () => void): void;
}

/** Resolves true when the fragment was spoken to the end, false on any
 * failure. May also throw/reject - the chain treats that as a failure. */
export type StepRunner = (control: StepControl) => Promise<boolean>;

export type StepOutcome =
  "spoken" | "failed" | "threw" | "start-timeout" | "playback-timeout" | "no-runner" | "aborted";

export interface VoiceChainResult {
  /** Which voice the person actually heard; null = text only. */
  spokenBy: VoiceStep | null;
  attempts: { step: VoiceStep; outcome: StepOutcome }[];
  /** The caller's isAborted() turned true (barge-in, unmount, a newer reply):
   * the chain stopped trying voices. NOT a "no voice works here" result. */
  aborted?: boolean;
}

export interface VoiceChainOptions {
  /** How long each voice gets to START making sound. */
  startTimeoutMs?: Partial<Record<VoiceStep, number>>;
  /** Ceiling on one fragment's playback once it started. */
  maxPlaybackMs?: number;
  /**
   * External abort (barge-in, unmount, a newer reply). Checked before every
   * step, and folded into each step's control.isCancelled() so a runner that
   * was still PREPARING sound when the abort happened (Kokoro synthesizing)
   * does not start playing afterwards. While a step runs it is polled every
   * ABORT_POLL_MS; on abort the step's own cancel handlers silence it and the
   * chain returns without trying another voice.
   */
  isAborted?: () => boolean;
}

/** Start deadlines. Server audio is already-decoded bytes (a data: URI), so
 * it starts almost at once. Kokoro must synthesize first, and on the first
 * fragment after a page load it also has to re-initialise the model from the
 * browser cache. Web Speech normally starts within a few hundred ms; where it
 * has no voices it never starts at all, and this is the only thing that
 * notices. */
export const DEFAULT_START_TIMEOUT_MS: Record<VoiceStep, number> = {
  "server-audio": 5_000,
  kokoro: 15_000,
  "web-speech": 5_000,
};

/** How often a running step re-checks VoiceChainOptions.isAborted. */
export const ABORT_POLL_MS = 100;

/** One sentence fragment never legitimately plays for longer than this. */
export const DEFAULT_MAX_PLAYBACK_MS = 90_000;

/**
 * Try each voice in order until one speaks the fragment. NEVER rejects and
 * ALWAYS resolves within a bounded time:
 *   sum(start deadlines) + maxPlaybackMs at the very worst.
 * A step that STARTED and then failed or hit the playback ceiling counts as
 * heard - falling through at that point would say the same sentence twice.
 */
export async function runVoiceChain(
  steps: VoiceStep[],
  runners: Partial<Record<VoiceStep, StepRunner>>,
  options: VoiceChainOptions = {}
): Promise<VoiceChainResult> {
  const attempts: VoiceChainResult["attempts"] = [];
  const maxPlaybackMs = options.maxPlaybackMs ?? DEFAULT_MAX_PLAYBACK_MS;
  const isAborted = () => {
    try {
      return options.isAborted?.() === true;
    } catch {
      return false;
    }
  };

  for (const step of steps) {
    if (isAborted()) return { spokenBy: null, attempts, aborted: true };
    const runner = runners[step];
    if (!runner) {
      attempts.push({ step, outcome: "no-runner" });
      continue;
    }
    const startTimeoutMs = options.startTimeoutMs?.[step] ?? DEFAULT_START_TIMEOUT_MS[step];
    const { outcome, started } = await runOneStep(
      runner,
      startTimeoutMs,
      maxPlaybackMs,
      isAborted,
      typeof options.isAborted === "function"
    );
    attempts.push({ step, outcome });
    if (outcome === "aborted") {
      return { spokenBy: started ? step : null, attempts, aborted: true };
    }
    if (outcome === "spoken" || started) {
      return { spokenBy: step, attempts };
    }
  }
  if (isAborted()) return { spokenBy: null, attempts, aborted: true };
  return { spokenBy: null, attempts };
}

function runOneStep(
  runner: StepRunner,
  startTimeoutMs: number,
  maxPlaybackMs: number,
  isAborted: () => boolean,
  pollAbort: boolean
): Promise<{ outcome: StepOutcome; started: boolean }> {
  return new Promise((resolve) => {
    let settled = false;
    let started = false;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const cancelFns: (() => void)[] = [];

    let abortPoll: ReturnType<typeof setInterval> | null = null;
    const settle = (outcome: StepOutcome) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      if (abortPoll) clearInterval(abortPoll);
      resolve({ outcome, started });
    };
    const giveUp = (outcome: StepOutcome) => {
      if (settled) return;
      cancelled = true;
      for (const fn of cancelFns) {
        try {
          fn();
        } catch {
          // Silencing a dead voice is best-effort.
        }
      }
      settle(outcome);
    };

    const control: StepControl = {
      started() {
        if (settled || started) return;
        started = true;
        if (timer) clearTimeout(timer);
        timer = setTimeout(() => giveUp("playback-timeout"), maxPlaybackMs);
      },
      isCancelled: () => cancelled || isAborted(),
      onCancel(fn) {
        if (cancelled) {
          try {
            fn();
          } catch {
            // Best-effort.
          }
          return;
        }
        cancelFns.push(fn);
      },
    };

    timer = setTimeout(() => giveUp("start-timeout"), startTimeoutMs);
    if (pollAbort) {
      abortPoll = setInterval(() => {
        if (isAborted()) giveUp("aborted");
      }, ABORT_POLL_MS);
    }

    let result: Promise<boolean>;
    try {
      result = Promise.resolve(runner(control));
    } catch {
      settle("threw");
      return;
    }
    result.then(
      (ok) => settle(ok ? "spoken" : "failed"),
      () => settle("threw")
    );
  });
}

// ---------------------------------------------------------------------------
// Splitting a whole reply into speakable fragments
// ---------------------------------------------------------------------------

/** kokoroWorker.ts clips one synth at 600 characters (MAX_TEXT_CHARS) and
 * says nothing about it - fine for the server's per-sentence audio-chunk
 * stream, but the whole-reply call sites handed it the ENTIRE reply, so
 * anything past the first 600 characters was silently never spoken.
 *
 * 200, not "as close to 600 as fits": a fragment is synthesized in full
 * before its first sound. Kokoro on wasm (every browser without WebGPU) is
 * expected to run at very roughly real-time speed, so a 400-character
 * fragment (~25 s of speech) could outlast both the chain's 15 s Kokoro start
 * deadline and kokoro.ts's 30 s per-synth watchdog. NOT MEASURED on real
 * hardware. If a fragment still misses the deadline, the chain falls through
 * to Web Speech and the caller skips Kokoro for the rest of that reply
 * (VoicePlanInput.skipKokoro). */
export const MAX_FRAGMENT_CHARS = 200;

/** Sentences are only joined while the result stays this short (~8 s of
 * speech). Joining exists for the tiny pieces - "Sure.", a list marker like
 * "1." that the sentence regex cuts off on its own - not to build long
 * fragments: time to first sound grows with fragment length. */
export const MERGE_TARGET_CHARS = 120;

/** Split a reply into speakable fragments, none longer than `maxChars`.
 * Sentence boundaries first; neighbouring SHORT sentences are joined up to
 * MERGE_TARGET_CHARS, a longer sentence always stands alone; an over-long
 * sentence is broken at the last space before the cap; a single unbroken run
 * longer than the cap is cut hard. No text is ever dropped. */
export function splitForSpeech(text: string | null | undefined, maxChars: number = MAX_FRAGMENT_CHARS): string[] {
  const clean = (text || "").replace(/\s+/g, " ").trim();
  if (!clean) return [];
  const cap = Math.max(20, Math.floor(maxChars));
  const mergeTarget = Math.min(cap, MERGE_TARGET_CHARS);
  // Sentence enders incl. the Devanagari danda; keep the punctuation.
  const sentences = clean.match(/[^.!?।]+[.!?।]+["')\]]*\s*|[^.!?।]+$/g) || [clean];
  const out: string[] = [];
  let current = "";
  const flush = () => {
    const trimmed = current.trim();
    if (trimmed) out.push(trimmed);
    current = "";
  };
  for (const raw of sentences) {
    let sentence = raw.trim();
    if (!sentence) continue;
    while (sentence.length > cap) {
      flush();
      const window = sentence.slice(0, cap);
      const breakAt = window.lastIndexOf(" ");
      const cut = breakAt > cap * 0.5 ? breakAt : cap;
      out.push(sentence.slice(0, cut).trim());
      sentence = sentence.slice(cut).trim();
    }
    if (!sentence) continue;
    if (current && current.length + 1 + sentence.length > mergeTarget) flush();
    current = current ? `${current} ${sentence}` : sentence;
  }
  flush();
  return out;
}

/** A plain-text opening of a long, possibly-markdown text, for a voice that
 * would otherwise read pages of symbols aloud: fenced code blocks and
 * markdown punctuation removed, whitespace collapsed, cut at the last
 * sentence end within `maxChars` (hard cut when there is none past the first
 * 100 characters). Text that already fits is returned whole. */
export function plainSpokenExcerpt(text: string | null | undefined, maxChars: number): string {
  const plain = (text || "")
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/[#*_`>|]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  const cap = Math.max(1, Math.floor(maxChars));
  if (plain.length <= cap) return plain;
  const cut = plain.slice(0, cap);
  const end = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("? "), cut.lastIndexOf("! "));
  return end > 100 ? cut.slice(0, end + 1) : cut.trim();
}

// ---------------------------------------------------------------------------
// Speaking a whole reply, fragment by fragment
// ---------------------------------------------------------------------------

export interface SpeakSequenceDeps<P> {
  /** False = stop before the next fragment (barge-in, agent switch, unmount). */
  keepGoing: () => boolean;
  /** Start preparing a fragment's audio AHEAD of its turn (Kokoro synthesis
   * of fragment N+1 while N is being spoken). Return undefined when there is
   * nothing worth preparing. A throw is treated as undefined. */
  prefetch?: (text: string) => P | undefined;
  /** Speak one fragment through the chain. Must never reject. */
  speak: (text: string, context: { prefetched: P | undefined; skipKokoro: boolean }) => Promise<VoiceChainResult>;
}

/** Kokoro was given its full start deadline and produced no sound in time. */
export function kokoroMissedStart(result: VoiceChainResult): boolean {
  return result.attempts.some((a) => a.step === "kokoro" && a.outcome === "start-timeout");
}

/**
 * Speak fragments strictly in order. Returns the first voice that was heard
 * (null = nothing was). Three rules beyond "for each, speak":
 *  - fragment N+1 is handed to `prefetch` right after fragment N is handed to
 *    `speak`, so its synthesis is queued behind N's and runs while N plays;
 *  - once Kokoro misses its START deadline, the rest of the reply skips it
 *    (its abandoned synth still occupies kokoro.ts's serialized queue) and no
 *    further prefetch is started;
 *  - it stops at the first fragment that no voice could speak (the rest would
 *    fail the same way), on an aborted chain, and when keepGoing() is false.
 */
export async function speakSequence<P>(fragments: string[], deps: SpeakSequenceDeps<P>): Promise<VoiceStep | null> {
  let spokenBy: VoiceStep | null = null;
  let skipKokoro = false;
  let prefetched: P | undefined;
  for (let i = 0; i < fragments.length; i++) {
    if (!deps.keepGoing()) break;
    const mine = skipKokoro ? undefined : prefetched;
    prefetched = undefined;
    const speaking = deps.speak(fragments[i], { prefetched: mine, skipKokoro });
    const next = fragments[i + 1];
    if (next && !skipKokoro && deps.prefetch) {
      try {
        prefetched = deps.prefetch(next);
      } catch {
        prefetched = undefined;
      }
    }
    let result: VoiceChainResult;
    try {
      result = await speaking;
    } catch {
      result = { spokenBy: null, attempts: [] };
    }
    if (kokoroMissedStart(result)) skipKokoro = true;
    if (result.aborted || !result.spokenBy) break;
    spokenBy = spokenBy || result.spokenBy;
  }
  return spokenBy;
}

// ---------------------------------------------------------------------------
// Whether to fetch the on-device voice, and how
// ---------------------------------------------------------------------------

export type VoiceFetchDecision =
  /** Nothing to do (installed, unsupported, already tried, or not needed). */
  | "none"
  /** Start the download now, in the background, with visible progress. */
  | "auto"
  /** Show a one-tap offer instead of spending the person's data unasked. */
  | "offer";

export interface VoiceFetchInput {
  edgeOn: boolean;
  /** edgeTtsAvailable() - this device can run Kokoro at all. */
  kokoroSupported: boolean;
  kokoroInstalled: boolean;
  /** A reply in THIS call already came back with no server voice. Without
   * that evidence there is no reason to fetch anything: a tenant with
   * ElevenLabs/Sarvam configured never needs Kokoro as a fallback. */
  serverVoiceMissing: boolean;
  /** The person has started a call / held to talk on this page. Never fetch
   * ~90 MB for someone who merely opened the screen. */
  callStarted: boolean;
  /** One attempt per page load - a failed download must not loop. */
  alreadyAttempted: boolean;
  /** navigator.connection.saveData */
  saveData: boolean;
  /** navigator.connection.effectiveType, when the browser reports it. */
  effectiveType?: string | null;
  /** navigator.connection.type === "cellular", when the browser reports it. */
  cellular: boolean;
  /** A phone/tablet browser. iOS Safari reports no connection info at all,
   * so "mobile" is the only honest stand-in for "probably metered". */
  mobile: boolean;
  /** The browser exposes navigator.connection at all (Chromium does; Firefox
   * and Safari do not). Without it a laptop tethered to a phone looks exactly
   * like one on fibre: there is no evidence the connection is unmetered. */
  connectionKnown: boolean;
  /** Kokoro could actually SPEAK the reply that came back voiceless (Latin
   * script - isLatinScriptText). ~90 MB is never fetched, or even offered,
   * for a Hindi/Kannada/Arabic/CJK conversation it cannot voice. */
  replySpeakable: boolean;
}

/** Kokoro is the default call voice since 2026-09-19, so a caller with no
 * server voice should end up on it without hunting through /edge for a
 * download button. It is still ~90 MB from a third-party model hub, so the
 * download starts by itself ONLY when the browser positively reports a
 * connection that does not look metered (navigator.connection present, no
 * Save-Data, not 2g/3g, not cellular, not a phone). Everything else - which
 * includes every Firefox and Safari, since they report nothing - gets a
 * one-tap offer with the size stated. Nothing at all for a reply Kokoro
 * cannot speak. Callers that must never start a download unasked (the public
 * share pages) treat "auto" as "offer". */
export function decideVoiceModelFetch(input: VoiceFetchInput): VoiceFetchDecision {
  if (!input.edgeOn || !input.kokoroSupported) return "none";
  if (input.kokoroInstalled) return "none";
  if (!input.serverVoiceMissing || !input.callStarted) return "none";
  if (!input.replySpeakable) return "none";
  if (input.alreadyAttempted) return "none";
  const slow = input.effectiveType === "slow-2g" || input.effectiveType === "2g" || input.effectiveType === "3g";
  if (input.saveData || slow || input.cellular || input.mobile) return "offer";
  if (!input.connectionKnown) return "offer";
  return "auto";
}
