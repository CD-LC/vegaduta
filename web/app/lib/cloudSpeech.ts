// Core's CLOUD_SPEECH edition feature, as the console sees it: the hosted speech vendors (Sarvam text-to-
// speech / translation, ElevenLabs voices and voice cloning) and the generated-audio listings behind them.
//
// A personal install runs core with CLOUD_SPEECH off (the beans, and so /api/sarvam/** and /api/elevenlabs/**,
// do not exist), so the console must not draw their provider cards, ask for their credentials, offer voice
// cloning, or list their generated audio: every one of those calls would be a 404 that reads as a failure.
// lite and full (and an undeclared build) keep everything. The hold-to-talk call overlay is a separate
// concern and stays on every edition.
//
// The first paint never waits on the network, so the seed is the edition (lib/edition.ts). Once core's
// GET /api/public/status has arrived, its reported `edition.features["cloud-speech"]` wins over the seed:
// that is how the escape hatch (app.edition.features.cloud-speech.enabled=true on a personal install)
// reaches the console, and how the console never draws a card for a feature core has off. Components call
// useCloudSpeechOffered so they re-render when the status arrives; plain functions (providerCatalog) call
// cloudSpeechOffered, which reads the same store.
import { isPersonalEdition } from "./edition";
import { getPlatformStatusState, usePlatformStatus } from "./platformStatus";

/** The provider card keys that exist only because of CLOUD_SPEECH (ProviderName.java: SARVAM, ELEVENLABS). */
export const CLOUD_SPEECH_PROVIDERS: ReadonlySet<string> = new Set(["SARVAM", "ELEVENLABS"]);

/** The status-reported flag, or null while core has not reported one (an older core, or no fetch yet). */
function reportedCloudSpeech(edition: { features: Record<string, boolean> } | null | undefined): boolean | null {
  const v = edition?.features?.["cloud-speech"];
  return typeof v === "boolean" ? v : null;
}

/** True when this install has the hosted speech vendors: what core reported, else the edition seed (every edition except personal). */
export function cloudSpeechOffered(): boolean {
  return reportedCloudSpeech(getPlatformStatusState().status?.edition) ?? !isPersonalEdition();
}

/** {@link cloudSpeechOffered} for a component: re-renders when the public status arrives or changes. */
export function useCloudSpeechOffered(): boolean {
  const { status } = usePlatformStatus();
  return reportedCloudSpeech(status?.edition) ?? !isPersonalEdition();
}
