// One call that speaks a whole reply through the shared voice chain
// (voiceOutput.ts) for the surfaces that are NOT the call panel: the
// playground's "read aloud", the camera panel's spoken answers and the two
// public share pages. Before this they each called the Web Speech API
// directly, so an installed Kokoro was never used there and a browser whose
// speech engine never starts left them silent with no explanation.
//
// What this does NOT do, ever: download anything. Kokoro is used only when it
// is ALREADY installed on the device (isTtsModelCached). Offering the
// download is the caller's business, and the public share pages must only
// ever offer it (see decideVoiceModelFetch's comment).
//
// Never rejects. Resolves { spokenBy: null } when nothing could speak - the
// caller has already rendered the text and decides what to say about silence.

import { edgeEnabled, getEdgeOptIn } from "../edge/capabilities";
import { isTtsModelCached, ttsModelFilesPresent } from "../edge/audio/kokoro";
import {
  isLatinScriptText,
  planVoiceOutput,
  runVoiceChain,
  speakSequence,
  splitForSpeech,
  type VoiceChainResult,
  type VoiceStep,
} from "./voiceOutput";
import { createVoiceRunners, prefetchKokoro, webSpeechPresent, type VoiceFragment } from "./voicePlayers";
import { getVoiceoverPref, preferredStepFor, type VoiceoverChoice } from "./voiceover";

export interface SpeakReplyOptions {
  text: string | null | undefined;
  /** Server-synthesized audio for the WHOLE text, when there is any. */
  audioBase64?: string | null;
  /** The server's `method` - picks the MIME type. */
  method?: string | null;
  /** The <audio> element to play through. Without one, only the Web Speech
   * API can speak (server audio and Kokoro both need an element). */
  getAudioEl: () => HTMLAudioElement | null;
  /** True = stop: a newer request superseded this one, the person pressed
   * stop, or the component unmounted. Whoever flips it should also call
   * stopAllSpeech() so what is already audible goes quiet at once. */
  isStale?: () => boolean;
  /** Fires once, when sound actually starts, with the voice that started. */
  onStart?: (step: VoiceStep) => void;
  /** Restrict this call to some voices (default: all three). The playground
   * uses it to keep its own order - Kokoro, then the hosted voice, then the
   * browser - across two calls with a server request in between. */
  voices?: VoiceStep[];
  /** For surfaces that must never cause a download on their own (the public
   * share pages): use Kokoro only when its files are verifiably in the
   * browser cache right now, not merely marked as installed once - a synth
   * against an evicted cache would quietly fetch ~90 MB again. */
  neverFetch?: boolean;
  /** The caller's voice pick (lib/voice/voiceover.ts); the persisted device
   * pref is used when omitted. An explicit pick promotes its leg to FIRST -
   * ahead of server audio too - with the normal chain as the fallback tail. */
  voiceover?: VoiceoverChoice;
}

export interface SpeakReplyResult {
  /** The first voice that was heard; null = nothing was spoken. */
  spokenBy: VoiceStep | null;
  /** Stopped by isStale() rather than by every voice failing. */
  aborted: boolean;
}

function safe<T>(fn: () => T, fallback: T): T {
  try {
    return fn();
  } catch {
    return fallback;
  }
}

/** Would an ALREADY-INSTALLED Kokoro be used for this text right now? */
export function kokoroReadyFor(text: string | null | undefined): boolean {
  return safe(() => edgeEnabled() && isTtsModelCached(), false) && isLatinScriptText(text);
}

export async function speakReply(options: SpeakReplyOptions): Promise<SpeakReplyResult> {
  const text = (options.text || "").trim();
  const isStale = () => safe(() => options.isStale?.() === true, false);
  const getEl = () => (isStale() ? null : safe(options.getAudioEl, null));
  const allowed = (step: VoiceStep) => !options.voices || options.voices.includes(step);
  let startedOnce = false;
  let aborted = false;

  const runFragment = async (
    fragment: VoiceFragment,
    steps: VoiceStep[]
  ): Promise<VoiceChainResult> => {
    try {
      const base = createVoiceRunners(fragment, getEl);
      const runners: typeof base = {};
      for (const step of steps) {
        const runner = base[step];
        if (!runner) continue;
        runners[step] = (control) =>
          runner({
            ...control,
            started() {
              if (!startedOnce) {
                startedOnce = true;
                safe(() => options.onStart?.(step), undefined);
              }
              control.started();
            },
          });
      }
      const result = await runVoiceChain(steps, runners, { isAborted: isStale });
      if (result.aborted) aborted = true;
      return result;
    } catch (err) {
      console.warn("[Voice] voice chain threw, reply stays text-only:", err);
      return { spokenBy: null, attempts: [] };
    }
  };

  try {
    if (isStale()) return { spokenBy: null, aborted: true };
    const hasEl = !!getEl();
    const edgeOn = safe(edgeEnabled, false);
    let installed = allowed("kokoro") && edgeOn && safe(isTtsModelCached, false);
    if (installed && options.neverFetch) {
      installed = await ttsModelFilesPresent().catch(() => false);
      if (isStale()) return { spokenBy: null, aborted: true };
    }
    const preferOnDevice = safe(getEdgeOptIn, false);
    const speech = allowed("web-speech") && webSpeechPresent();
    const hasServerAudio = allowed("server-audio") && !!options.audioBase64 && hasEl;
    // An explicit voice pick promotes its leg first; a pick whose leg isn't
    // even allowed by `voices` is ignored entirely (Auto = current order).
    const voiceover = options.voiceover ?? getVoiceoverPref();
    const picked = preferredStepFor(voiceover);
    const preferredStep = picked && allowed(picked) ? picked : undefined;
    const kokoroFirst = (preferOnDevice || preferredStep === "kokoro") && installed && hasEl && isLatinScriptText(text);
    const webSpeechFirst = preferredStep === "web-speech" && speech;

    // Server audio covers the whole text in one clip, so it is one step of
    // its own rather than something to split. People who turned on-device
    // replies on keep their Kokoro-first order (the E-4 rule); an explicit
    // voice pick gets the same "first, with server audio as the tail" shape.
    if (hasServerAudio && !kokoroFirst && !webSpeechFirst) {
      const result = await runFragment(
        { text, audioBase64: options.audioBase64, method: options.method, voiceover },
        ["server-audio"]
      );
      if (result.spokenBy) return { spokenBy: result.spokenBy, aborted };
      if (aborted || isStale()) return { spokenBy: null, aborted: true };
    }

    const spokenBy = await speakSequence<ReturnType<typeof prefetchKokoro>>(splitForSpeech(text), {
      keepGoing: () => !isStale(),
      prefetch: (next) =>
        installed && hasEl && isLatinScriptText(next)
          ? prefetchKokoro(next, voiceover.kind === "kokoro" ? voiceover.voiceId : undefined)
          : undefined,
      speak: (fragmentText, context) =>
        runFragment(
          { text: fragmentText, kokoroPrefetch: context.prefetched ?? null, voiceover },
          planVoiceOutput({
            text: fragmentText,
            hasServerAudio: false,
            hasAudioElement: hasEl,
            edgeOn,
            kokoroInstalled: installed,
            preferOnDevice,
            webSpeechPresent: speech,
            skipKokoro: context.skipKokoro,
            preferredStep,
          })
        ),
    });
    if (spokenBy) return { spokenBy, aborted };
    if (aborted || isStale()) return { spokenBy: null, aborted: true };

    if (hasServerAudio && (kokoroFirst || webSpeechFirst)) {
      const result = await runFragment(
        { text, audioBase64: options.audioBase64, method: options.method, voiceover },
        ["server-audio"]
      );
      return { spokenBy: result.spokenBy, aborted };
    }
    return { spokenBy: null, aborted };
  } catch (err) {
    console.warn("[Voice] speakReply failed, reply stays text-only:", err);
    return { spokenBy: null, aborted };
  }
}
