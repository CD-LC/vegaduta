// Core's CLOUD_SPEECH edition feature, as the console sees it: the hosted speech vendors (Sarvam text-to-
// speech / translation, ElevenLabs voices and voice cloning) and the generated-audio listings behind them.
//
// A personal install runs core with CLOUD_SPEECH off (the beans, and so /api/sarvam/** and /api/elevenlabs/**,
// do not exist), so the console must not draw their provider cards, ask for their credentials, offer voice
// cloning, or list their generated audio: every one of those calls would be a 404 that reads as a failure.
// lite and full (and an undeclared build) keep everything. The hold-to-talk call overlay is a separate
// concern and stays on every edition.
//
// Rendering is synchronous and edition-based, like the rest of the console (lib/edition.ts). The core
// escape hatch (app.edition.features.cloud-speech.enabled=true on a personal install) is therefore not seen
// here; a personal install that opts in keeps the cards hidden until the console reads the reported feature
// flags too.
import { isPersonalEdition } from "./edition";

/** The provider card keys that exist only because of CLOUD_SPEECH (ProviderName.java: SARVAM, ELEVENLABS). */
export const CLOUD_SPEECH_PROVIDERS: ReadonlySet<string> = new Set(["SARVAM", "ELEVENLABS"]);

/** True when this install has the hosted speech vendors: every edition except personal. */
export function cloudSpeechOffered(): boolean {
  return !isPersonalEdition();
}
