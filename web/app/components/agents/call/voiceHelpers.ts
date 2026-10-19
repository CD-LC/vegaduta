// Pure helpers lifted out of CallPanel.tsx.

import { likelyEnglishSpeaker } from "../../../lib/voice/callPreflight";

/** The server's own words for a failed transcription are written for a tenant
 * admin ("Add a Sarvam credential..."); a caller gets this instead. */
export function pipelineErrorForFailedStt(serverMessage: string): string {
  if (/no speech was detected/i.test(serverMessage || "")) return serverMessage;
  return "That recording could not be turned into text, on the platform or on this device. Hold to talk and try again, a little closer to the microphone.";
}

/** In-browser Whisper is whisper-tiny.EN: only used or offered when the
 * browser language AND the UI locale both say English (a heuristic). */
export function speechLikelyEnglish(uiLocale: string): boolean {
  let browserLanguage: string | null = null;
  try {
    browserLanguage = typeof navigator !== "undefined" ? navigator.language : null;
  } catch {
    browserLanguage = null;
  }
  return likelyEnglishSpeaker([browserLanguage, uiLocale]);
}

const SERVER_VOICE_MISSING_KEY = "voice.serverVoiceMissing";

// sessionStorage, not localStorage: a tenant admin can add a voice key at any
// time, so this must not outlive the browser session.
export function readServerVoiceMissingFlag(): boolean {
  try {
    return typeof sessionStorage !== "undefined" && sessionStorage.getItem(SERVER_VOICE_MISSING_KEY) === "1";
  } catch {
    return false;
  }
}

export function writeServerVoiceMissingFlag(): void {
  try {
    if (typeof sessionStorage !== "undefined") sessionStorage.setItem(SERVER_VOICE_MISSING_KEY, "1");
  } catch {
    // Best-effort.
  }
}
