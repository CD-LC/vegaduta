// Browser-side runners for the voice-output chain (voiceOutput.ts holds the
// pure ordering/timeout rules; this file is the part that touches an <audio>
// element, the Kokoro facade and window.speechSynthesis). Every function here
// resolves - never rejects - and honours StepControl's cancel, so a voice the
// chain has given up on can never start talking over the next one.

import { voiceAudioMimeType } from "../edge/capabilities";
import { synthesizeSpeechLocally, type SynthesizeResult } from "../edge/audio/kokoro";
import { getVoiceoverPref, type VoiceoverChoice } from "./voiceover";
import { speechLangForText, type StepControl, type StepRunner, type VoiceStep } from "./voiceOutput";

// ---------------------------------------------------------------------------
// Autoplay unlock
// ---------------------------------------------------------------------------

/** A 44-byte RIFF header plus a handful of zero samples: valid, silent, tiny.
 * Built rather than pasted so the header fields are checkable in a test. */
export function silentWavDataUri(sampleCount = 64): string {
  const sampleRate = 8000;
  const dataBytes = sampleCount * 2;
  const bytes = new Uint8Array(44 + dataBytes);
  const view = new DataView(bytes.buffer);
  const writeAscii = (offset: number, s: string) => {
    for (let i = 0; i < s.length; i++) bytes[offset + i] = s.charCodeAt(i);
  };
  writeAscii(0, "RIFF");
  view.setUint32(4, 36 + dataBytes, true);
  writeAscii(8, "WAVE");
  writeAscii(12, "fmt ");
  view.setUint32(16, 16, true); // PCM chunk size
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // byte rate
  view.setUint16(32, 2, true); // block align
  view.setUint16(34, 16, true); // bits per sample
  writeAscii(36, "data");
  view.setUint32(40, dataBytes, true);
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return `data:audio/wav;base64,${btoa(binary)}`;
}

/**
 * Unlock an <audio> element for later programmatic play(). MUST be called
 * synchronously from the user gesture that starts the call / the hold.
 *
 * Why (2026-09-19): autoplay policy ties "may play sound" to a user gesture.
 * The old comment in CallPanel said "the recorded push-to-talk action itself
 * is a user gesture, so autoplay should succeed" - true in Chrome, where the
 * activation lingers, but Safari (notably iOS) only honours a play() issued
 * DURING the gesture, and the reply arrives seconds later after STT + the
 * model + (for Kokoro) synthesis. An element that has already played once
 * from a gesture stays unlocked for the page's lifetime, so a silent clip
 * here is what makes every later reply audible. Never throws; skipped while
 * the element is busy playing a real reply (it is unlocked already then).
 */
export function unlockAudioElement(el: HTMLAudioElement | null | undefined): void {
  try {
    if (!el || !el.paused) return;
    el.muted = true;
    el.src = silentWavDataUri();
    const restore = () => {
      try {
        el.muted = false;
      } catch {
        // Best-effort.
      }
    };
    const p = el.play();
    if (typeof p?.then === "function") {
      p.then(restore, restore);
    } else {
      restore();
    }
  } catch {
    // A failed unlock only means the chain's own autoplay-blocked
    // fall-through does its job later.
  }
}

/** Also wake the Web Speech engine inside the gesture: iOS Safari ignores a
 * first speak() issued outside one. An empty utterance is inaudible. */
export function unlockWebSpeech(): void {
  try {
    if (typeof window === "undefined" || !window.speechSynthesis) return;
    if (typeof SpeechSynthesisUtterance === "undefined") return;
    if (window.speechSynthesis.speaking || window.speechSynthesis.pending) return;
    const u = new SpeechSynthesisUtterance("");
    u.volume = 0;
    window.speechSynthesis.speak(u);
  } catch {
    // Best-effort.
  }
}

export function webSpeechPresent(): boolean {
  try {
    return (
      typeof window !== "undefined" && !!window.speechSynthesis && typeof SpeechSynthesisUtterance !== "undefined"
    );
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------------------
// Runners
// ---------------------------------------------------------------------------

/** Whoever is currently playing through a given <audio> element. ONE element
 * is shared by every reply, so a new playback must settle the previous one's
 * promise (nothing else ever will once its handlers are replaced), and a
 * step that the chain gives up on late must not pause audio that is no
 * longer its own. */
const activePlayback = new WeakMap<HTMLAudioElement, (ok: boolean) => void>();

/** Stop whatever is playing through `el` and through the Web Speech API, and
 * settle the pending playback promise so nothing waits out a 90s ceiling.
 * Used for barge-in: the person started talking again. Never throws. */
export function stopAllSpeech(el: HTMLAudioElement | null | undefined): void {
  try {
    if (el) {
      const settle = activePlayback.get(el);
      try {
        el.pause();
      } catch {
        // Best-effort.
      }
      settle?.(false);
    }
  } catch {
    // Best-effort.
  }
  try {
    if (typeof window !== "undefined" && window.speechSynthesis) window.speechSynthesis.cancel();
  } catch {
    // Best-effort.
  }
}

/** Play one source through the shared <audio> element. Resolves true at
 * `ended`, false on error / autoplay refusal / cancel / being replaced. */
export function playSourceOnElement(
  el: HTMLAudioElement,
  src: string,
  control: StepControl,
  onSettled?: () => void
): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    let done = false;
    const finish = (ok: boolean) => {
      if (done) return;
      done = true;
      if (activePlayback.get(el) === finish) {
        activePlayback.delete(el);
        try {
          el.onended = null;
          el.onerror = null;
          el.onplaying = null;
        } catch {
          // Best-effort.
        }
      }
      try {
        onSettled?.();
      } catch {
        // Best-effort.
      }
      resolve(ok);
    };
    control.onCancel(() => {
      // Only silence the element if it is still OURS to silence.
      if (activePlayback.get(el) === finish) {
        try {
          el.pause();
        } catch {
          // Best-effort.
        }
      }
      finish(false);
    });
    if (control.isCancelled()) {
      finish(false);
      return;
    }
    try {
      // Settle whoever had the element before us - we are about to replace
      // its handlers and its src, so nothing else would ever resolve it.
      activePlayback.get(el)?.(false);
      activePlayback.set(el, finish);
      el.muted = false;
      el.onplaying = () => control.started();
      el.onended = () => finish(true);
      el.onerror = () => finish(false);
      el.src = src;
      const p = el.play();
      if (typeof p?.catch === "function") {
        p.catch((err: unknown) => {
          console.warn("[Voice] audio playback refused, trying the next voice:", err);
          finish(false);
        });
      }
    } catch (err) {
      console.warn("[Voice] audio playback threw, trying the next voice:", err);
      finish(false);
    }
  });
}

/** Does `voice` speak `lang`? Compares the primary subtag only ("hi" of
 * "hi-IN"); Android reports tags with an underscore ("hi_IN"). */
function voiceSpeaks(voice: { lang?: string } | null | undefined, lang: string): boolean {
  const primary = (tag: string) => tag.toLowerCase().split(/[-_]/)[0];
  return !!voice?.lang && primary(voice.lang) === primary(lang);
}

/**
 * Which installed voice should speak `lang`?
 *   { voice }          a matching voice is installed - use it;
 *   { voice: null }    the browser has not listed its voices (Chrome fills the
 *                      list asynchronously, some engines never do) - set the
 *                      language and let the engine choose;
 *   "none"             the browser DID list voices and none speaks `lang` -
 *                      speaking anyway would run the text through a voice for
 *                      another language (silence or noise for Devanagari
 *                      through an English voice) while onstart/onend still
 *                      fire, so the chain would wrongly record it as spoken.
 */
export function pickWebSpeechVoice(
  voices: SpeechSynthesisVoice[] | null | undefined,
  lang: string
): { voice: SpeechSynthesisVoice | null } | "none" {
  const list = Array.isArray(voices) ? voices : [];
  if (list.length === 0) return { voice: null };
  const match = list.find((v) => voiceSpeaks(v, lang) && v.localService) || list.find((v) => voiceSpeaks(v, lang));
  return match ? { voice: match } : "none";
}

/** Speak through the browser's Web Speech API. `onstart` is what tells the
 * chain this voice is real - a browser that exposes speechSynthesis but has
 * no voices accepts speak() and then does nothing, forever.
 *
 * Non-Latin text (every reply Kokoro cannot take) gets an explicit language
 * and a matching voice; with no matching voice installed this resolves false
 * so the caller shows its text-only notice instead of claiming it was spoken.
 * Latin-script text keeps the browser's default voice: the script cannot tell
 * English from Spanish, so no language is asserted. */
export function speakWithWebSpeech(
  text: string,
  control: StepControl,
  preferredVoiceURI?: string
): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    if (!webSpeechPresent()) {
      resolve(false);
      return;
    }
    let done = false;
    const finish = (ok: boolean) => {
      if (done) return;
      done = true;
      resolve(ok);
    };
    control.onCancel(() => {
      try {
        window.speechSynthesis.cancel();
      } catch {
        // Best-effort.
      }
      finish(false);
    });
    try {
      const utterance = new SpeechSynthesisUtterance(text);
      const lang = speechLangForText(text);
      // An explicit pick (the panel's voice-over selector) names the exact
      // voiceURI to speak with and wins over the language match below - the
      // caller's choice is deliberate. A stale pick (voice since removed)
      // silently falls back to the previous language/default matching.
      let pickedVoice: SpeechSynthesisVoice | null = null;
      if (preferredVoiceURI) {
        try {
          pickedVoice =
            (window.speechSynthesis.getVoices() || []).find((v) => v.voiceURI === preferredVoiceURI) || null;
        } catch {
          pickedVoice = null;
        }
      }
      if (pickedVoice) {
        utterance.voice = pickedVoice;
        utterance.lang = pickedVoice.lang;
      } else if (lang) {
        let voices: SpeechSynthesisVoice[] = [];
        try {
          voices = window.speechSynthesis.getVoices() || [];
        } catch {
          voices = [];
        }
        const picked = pickWebSpeechVoice(voices, lang);
        if (picked === "none") {
          console.warn(`[Voice] this browser has no voice for ${lang}, reply stays text-only`);
          finish(false);
          return;
        }
        utterance.lang = lang;
        if (picked.voice) utterance.voice = picked.voice;
      }
      utterance.rate = 1.0;
      utterance.pitch = 1.0;
      utterance.volume = 1.0;
      utterance.onstart = () => control.started();
      utterance.onend = () => finish(true);
      utterance.onerror = () => finish(false);
      window.speechSynthesis.cancel(); // clear anything stale (incl. the unlock utterance)
      window.speechSynthesis.speak(utterance);
    } catch (err) {
      console.warn("[Voice] Web Speech failed, reply stays text-only:", err);
      finish(false);
    }
  });
}

export interface VoiceFragment {
  text: string;
  /** Server-synthesized audio for this fragment, when there is any. */
  audioBase64?: string | null;
  /** The server's `method` - picks the MIME type (Sarvam = WAV). */
  method?: string | null;
  /** A Kokoro synthesis of THIS text that was started ahead of time (while
   * the previous fragment was playing). Used instead of starting a new one. */
  kokoroPrefetch?: Promise<SynthesizeResult> | null;
  /** The caller's voice pick (lib/voice/voiceover.ts). Undefined = the
   * persisted device pref is read at play time; an explicit value overrides
   * it (tests, or a future per-reply voice, can pin a voice without touching
   * localStorage). */
  voiceover?: VoiceoverChoice;
}

/** Start synthesizing `text` with Kokoro now, to be handed to
 * createVoiceRunners later as `kokoroPrefetch`. Never rejects and never
 * downloads (synthesizeSpeechLocally's contract; the catch is belt and
 * braces so an unused prefetch can never become an unhandled rejection).
 * `voice` defaults to the persisted voice-over pick when it is a Kokoro
 * voice, so a prefetched fragment speaks in the same voice the runner would
 * have used. */
export function prefetchKokoro(text: string, voice?: string): Promise<SynthesizeResult> {
  const pref = getVoiceoverPref();
  const voiceId = voice ?? (pref.kind === "kokoro" ? pref.voiceId : undefined);
  return synthesizeSpeechLocally(text, voiceId ? { voice: voiceId } : undefined).catch(
    (): SynthesizeResult => ({ handled: false, reason: "tts-failed" })
  );
}

/** The three runners for one fragment, bound to the shared <audio> element.
 * `getAudioEl` is a getter because the element lives in a React ref and the
 * panel can unmount while Kokoro is still synthesizing. */
export function createVoiceRunners(
  fragment: VoiceFragment,
  getAudioEl: () => HTMLAudioElement | null
): Partial<Record<VoiceStep, StepRunner>> {
  // The voiceover pick resolved once per fragment: the fragment's own field
  // wins, else the persisted device pref. "auto" leaves every runner exactly
  // as before (undefined voice / no explicit SpeechSynthesisVoice).
  const voiceover = fragment.voiceover ?? getVoiceoverPref();
  const kokoroVoice = voiceover.kind === "kokoro" ? voiceover.voiceId : undefined;
  const webSpeechVoiceURI = voiceover.kind === "web-speech" ? voiceover.voiceURI : undefined;
  return {
    "server-audio": (control) => {
      const el = getAudioEl();
      if (!el || !fragment.audioBase64) return Promise.resolve(false);
      return playSourceOnElement(
        el,
        `data:${voiceAudioMimeType(fragment.method)};base64,${fragment.audioBase64}`,
        control
      );
    },
    kokoro: async (control) => {
      // synthesizeSpeechLocally never throws and never downloads (kokoro.ts's
      // contract); a model that is not installed answers handled:false at once.
      const result = await (
        fragment.kokoroPrefetch ?? synthesizeSpeechLocally(fragment.text, kokoroVoice ? { voice: kokoroVoice } : undefined)
      );
      if (result.handled === false) {
        console.warn(`[Voice] on-device voice declined (${result.reason}), trying the next voice`);
        return false;
      }
      const el = getAudioEl();
      if (!el || control.isCancelled()) return false;
      const url = URL.createObjectURL(result.audioBlob);
      return playSourceOnElement(el, url, control, () => URL.revokeObjectURL(url));
    },
    "web-speech": (control) => speakWithWebSpeech(fragment.text, control, webSpeechVoiceURI),
  };
}
