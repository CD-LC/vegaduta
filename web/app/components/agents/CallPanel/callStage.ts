import type { api } from "../../../lib/api";

// Reliability blueprint Phase 4/Part 5(a): the 7(+idle/failed)-stage
// lifecycle both call surfaces (push-to-talk below, and the LiveKit call via
// its agent-stage data-channel listener once the parallel Python-side task
// ships it) drive the SAME shared `callStage` state through, so one
// indicator component/label mapping covers both instead of two parallel
// implementations.
//
// "reasoning" is real for the LiveKit path (voice-agent's llm_core_bridge.py
// is specced to publish it as a genuine backend signal, part (b) step 5) but
// COSMETIC-ONLY for push-to-talk (see the onTranscript handler in sendTurn
// below) - the backend emits no distinct signal between "transcript
// received" and "first reply token" for push-to-talk today, so that path's
// "reasoning" is a client-side timer-driven label flip with no telemetry
// meaning, not a real sub-stage. Documented here so it isn't later mistaken
// for one.
export type CallStage =
  | "idle"
  | "listening"
  | "transcribing"
  | "understanding"
  | "reasoning"
  | "generating"
  | "speaking"
  | "completed"
  | "failed";

export function callStageLabel(stage: CallStage): string | null {
  switch (stage) {
    case "listening":
      return "Listening…";
    case "transcribing":
      return "Transcribing…";
    case "understanding":
      return "Understanding…";
    case "reasoning":
      return "Reasoning…";
    case "generating":
      return "Generating reply…";
    case "speaking":
      return "Speaking…";
    default:
      return null;
  }
}

// One interleaved audio-chunk SSE event of the push-to-talk reply stream.
// The shape is declared inline on api.sendVoiceTurnStream's callbacks object
// (api.ts exports no standalone type for it), so it's DERIVED from there
// rather than re-declared here - the two can't drift apart.
export type VoiceAudioChunk = Parameters<
  NonNullable<Parameters<typeof api.sendVoiceTurnStream>[3]["onAudioChunk"]>
>[0];

// trySendTurnLocally's four-way result contract - see that function's own
// header comment for what each outcome means and why the old bare boolean
// wasn't enough (Edge Wave 2, finding E-2/F-25).
export type PushToTalkRouting =
  | { outcome: "local" }
  // localSttTried (2026-09-19): in-browser Whisper ALREADY ran on this blob
  // and could not transcribe it, so sendTurn's server-speech-to-text-failed
  // recovery must not run it a second time on the same audio.
  | { outcome: "server-audio"; localSttTried?: boolean }
  | { outcome: "refused" }
  | { outcome: "server-text"; transcript: string };
