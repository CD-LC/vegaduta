// The "voice over" picker shared by the call surfaces (CallPanel - console,
// assistant overlay, tutor - and the public share page's ShareCallPanel).
//
// It answers "which voice speaks replies on this device": an explicit pick
// takes precedence over the voice chain's default order (see voiceOutput.ts)
// for whatever leg it belongs to - a Kokoro voice makes the on-device leg
// preferred, a browser voice makes the Web Speech leg preferred AND names the
// exact SpeechSynthesisVoice to use. "Auto" keeps the existing order
// (server audio -> Kokoro -> Web Speech) untouched.
//
// Scope note (deliberate): a live LiveKit call's voice is synthesized by the
// voice-agent worker server-side - this preference cannot retune the remote
// audio track. Per-call server-side voice selection is the SEPARATE
// ttsVoice/ttsVoiceProvider field on the token request (VoiceTokenRequest ->
// participant metadata -> voice-agent's _extract_tts_voice), driven by the
// tenant's own ElevenLabs voice list - a different pick list living beside
// this one on the panel.
//
// Device-level, like the playground's autoSpeak: persisted in localStorage so
// the choice survives reloads and is shared by every surface that speaks.

const STORAGE_KEY = "vd.voiceover.v1";

export type VoiceoverChoice =
  | { kind: "auto" }
  | { kind: "kokoro"; voiceId: string }
  | { kind: "web-speech"; voiceURI: string; label?: string };

/** A valid stored pick. Unknown/corrupt values fall back to "auto". */
function parseChoice(raw: unknown): VoiceoverChoice {
  if (!raw || typeof raw !== "object") return { kind: "auto" };
  const o = raw as Record<string, unknown>;
  if (o.kind === "kokoro" && typeof o.voiceId === "string" && o.voiceId) {
    return { kind: "kokoro", voiceId: o.voiceId };
  }
  if (o.kind === "web-speech" && typeof o.voiceURI === "string" && o.voiceURI) {
    return {
      kind: "web-speech",
      voiceURI: o.voiceURI,
      label: typeof o.label === "string" ? o.label : undefined,
    };
  }
  return { kind: "auto" };
}

export function getVoiceoverPref(): VoiceoverChoice {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? parseChoice(JSON.parse(raw)) : { kind: "auto" };
  } catch {
    return { kind: "auto" };
  }
}

export function setVoiceoverPref(choice: VoiceoverChoice): void {
  try {
    if (choice.kind === "auto") localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, JSON.stringify(choice));
  } catch {
    // Private-mode quota - the pick simply doesn't persist; the in-memory
    // choice the caller already holds still applies for this page's lifetime.
  }
}

/** Which voice-chain leg an explicit pick wants to go first - undefined for auto. */
export function preferredStepFor(choice: VoiceoverChoice): "kokoro" | "web-speech" | undefined {
  if (choice.kind === "kokoro") return "kokoro";
  if (choice.kind === "web-speech") return "web-speech";
  return undefined;
}

/**
 * The Kokoro-82M voice ids the picker offers. Kokoro's synthesis accepts any
 * loaded voice id (lib/edge/audio/kokoroWorker.ts, default "af_heart"); this
 * is the curated subset exposed as a choice - friendly labels over the
 * raw `<lang><gender>_<name>` ids.
 */
export const KOKORO_VOICEOVER_OPTIONS: { voiceId: string; label: string }[] = [
  { voiceId: "af_heart", label: "Heart (female)" },
  { voiceId: "af_bella", label: "Bella (female)" },
  { voiceId: "af_nicole", label: "Nicole (female)" },
  { voiceId: "af_sarah", label: "Sarah (female)" },
  { voiceId: "am_adam", label: "Adam (male)" },
  { voiceId: "am_michael", label: "Michael (male)" },
  { voiceId: "am_puck", label: "Puck (male)" },
  { voiceId: "bf_emma", label: "Emma (British female)" },
  { voiceId: "bm_george", label: "George (British male)" },
  { voiceId: "bm_lewis", label: "Lewis (British male)" },
];

/**
 * The browser's Web Speech voices, deduped by voiceURI (some platforms list
 * the same voice more than once). Empty when speechSynthesis is absent - the
 * caller hides that section entirely rather than showing a dead option.
 */
export function listWebSpeechVoices(): SpeechSynthesisVoice[] {
  if (typeof window === "undefined" || !("speechSynthesis" in window)) return [];
  const seen = new Set<string>();
  return window.speechSynthesis.getVoices().filter((v) => {
    if (seen.has(v.voiceURI)) return false;
    seen.add(v.voiceURI);
    return true;
  });
}

/**
 * Serialize/deserialize for the <select> element: a single string value has
 * to carry the discriminated union, so options are keyed "auto" |
 * "kokoro:<id>" | "web-speech:<voiceURI>".
 */
export function voiceoverOptionValue(choice: VoiceoverChoice): string {
  if (choice.kind === "auto") return "auto";
  if (choice.kind === "kokoro") return `kokoro:${choice.voiceId}`;
  return `web-speech:${choice.voiceURI}`;
}

export function parseVoiceoverOptionValue(value: string, label?: string): VoiceoverChoice {
  if (value.startsWith("kokoro:")) return { kind: "kokoro", voiceId: value.slice(7) };
  if (value.startsWith("web-speech:")) {
    return { kind: "web-speech", voiceURI: value.slice(11), label };
  }
  return { kind: "auto" };
}
