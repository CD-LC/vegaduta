import type { MutableRefObject } from "react";
import { getEdgeOptIn } from "../../../lib/edge/capabilities";
import { kokoroMissedStart, type VoiceChainResult } from "../../../lib/voice/voiceOutput";
import { prefetchKokoro, type VoiceFragment } from "../../../lib/voice/voicePlayers";
import type { VoiceAudioChunk } from "./callStage";

// What the queue needs from the owning CallPanel instance (its closures/refs).
export interface AudioChunkQueueDeps {
  voiceEpochRef: MutableRefObject<number>;
  kokoroWouldSpeak: (text: string | null | undefined) => boolean;
  noteServerVoiceMissing: (replyText: string | null | undefined) => void;
  speakFragment: (
    fragment: VoiceFragment,
    onStart?: () => void,
    options?: { skipKokoro?: boolean }
  ) => Promise<VoiceChainResult>;
}


// Plays queued audio-chunk events one at a time, in the order they arrived
// (the backend already guarantees wire order matches playback order - see
// VoiceCallController#turn's javadoc on flatMapSequential). A chunk with no
// audio (method === "text-only-fallback") has its own text fragment spoken
// by the voice chain instead (an installed Kokoro, else the browser's Web
// Speech API) - per sentence.
//
// HISTORY (the E-4 paragraph below describes the 2026-08 design, kept for
// the reasoning; what runs today is in the dated comment inside playNext):
//
// On-device Kokoro synthesis slots in as a NEW FIRST preference,
// tried before the server-provided audio and before the browser
// speechSynthesis fallback - "every Kokoro synth is a Sarvam/ElevenLabs
// bill that never happens." It only ever runs when the person has
// on-device replies turned on AND the model is already installed on this
// device (isTtsModelCached()) - this queue never triggers a download
// itself (E-0b rule 2: downloads are opt-in, from an explicit user
// gesture, never a side effect of playback). The server already
// synthesized chunk.audioBase64 via its own ElevenLabs -> Sarvam chain by
// the time this chunk arrives (that leg isn't skipped by this client-side
// change - see the CLAUDE.md-scoped task note this was built under), so
// this is the client half of the local-TTS story; a future core-3 change
// could let an edge-tier turn opt out of server synthesis entirely.
// `callbacks.onSpeakStart` fires once, the first time playback of any
// chunk actually begins (Part 5(a) trigger 5 - Speaking should visually
// supersede Generating "the instant the first audio-chunk starts playing",
// per the design spec's caveat 2, since reply-delta/audio-chunk are
// interleaved on the wire, not phased).
// `callbacks.onIdle` fires every time playback drains back to empty (Part
// 5(a) required addition - previously this closure exposed only push(),
// with no way for a caller to know when the queue had actually finished
// draining, which is exactly the "onDone fires before playback finishes"
// gap caveat 3 calls out).
export function createAudioChunkQueue(
  deps: AudioChunkQueueDeps,
  callbacks: { onSpeakStart?: () => void; onIdle?: () => void; onAudioUnavailable?: () => void } = {}
) {
  const { voiceEpochRef, kokoroWouldSpeak, noteServerVoiceMissing, speakFragment } = deps;
  const queue: { chunk: VoiceAudioChunk; kokoroPrefetch?: ReturnType<typeof prefetchKokoro> }[] = [];
  let playing = false;
  let speakStarted = false;
  // Kokoro missed a start deadline in THIS reply: skip it for the rest (its
  // abandoned synth still occupies kokoro.ts's serialized queue).
  let skipKokoro = false;
  const epoch = voiceEpochRef.current;

  // Kokoro will be the voice for a chunk when the chunk has no server audio
  // or the person put on-device replies first.
  const kokoroWillSpeak = (c: VoiceAudioChunk) =>
    !skipKokoro && (!c.audioBase64 || getEdgeOptIn()) && kokoroWouldSpeak(c.textFragment);

  function playNext() {
    if (playing) return;
    // Barge-in (see primeVoiceOutputFromGesture): the person is talking
    // again, so the rest of THIS reply is never spoken. Its text is
    // already in the transcript.
    if (voiceEpochRef.current !== epoch) {
      // No onIdle here: it would mark the OLD turn "completed" on screen
      // while the person is already recording the next one.
      queue.length = 0;
      return;
    }
    const entry = queue.shift();
    if (!entry) {
      callbacks.onIdle?.();
      return;
    }
    const chunk = entry.chunk;
    playing = true;
    // Synthesize the NEXT queued sentence while this one is being spoken.
    const upcoming = queue[0];
    if (upcoming && upcoming.kokoroPrefetch === undefined && kokoroWillSpeak(upcoming.chunk)) {
      upcoming.kokoroPrefetch = prefetchKokoro(upcoming.chunk.textFragment);
    }
    // 2026-09-19: the hand-rolled tryLocalTts/playServerAudio/speakFallback
    // ladder that lived here is now lib/voice/voiceOutput.ts's chain (see
    // speakFragment above). What changed in behaviour, deliberately:
    //  - Kokoro no longer needs "on-device replies" to be ON; being
    //    installed is enough. With that opt-in it still goes FIRST (the E-4
    //    rule in the comment above); without it, it is the fallback right
    //    after server audio and BEFORE the robotic Web Speech voice.
    //  - every voice has a start deadline and a playback ceiling, so a
    //    browser whose speechSynthesis accepts speak() and then never fires
    //    onstart/onend can no longer wedge this queue (and with it the
    //    "Speaking..." stage) forever.
    //  - onSpeakStart now fires when sound actually starts, not when a
    //    chunk is merely dequeued.
    // "No server voice" is taken from the server's own word for it - method
    // "text-only-fallback", which VoiceCallController sends when its whole
    // ElevenLabs -> Sarvam chain produced nothing. A chunk that merely has
    // no audio is NOT that: a tenant with a working voice gets one whenever
    // a single sentence fails to synthesize (rate limit, a punctuation-only
    // fragment), and that must not start a 90 MB download.
    if (!chunk.audioBase64 && chunk.method === "text-only-fallback") {
      noteServerVoiceMissing(chunk.textFragment);
    }
    void speakFragment(
      {
        text: chunk.textFragment,
        audioBase64: chunk.audioBase64,
        method: chunk.method,
        kokoroPrefetch: skipKokoro ? null : (entry.kokoroPrefetch ?? null),
      },
      () => {
        if (!speakStarted) {
          speakStarted = true;
          callbacks.onSpeakStart?.();
        }
      },
      { skipKokoro }
    )
      .then((result) => {
        if (kokoroMissedStart(result)) skipKokoro = true;
        // Nothing could speak this chunk. The reply text is still visible
        // in the transcript panel; this only explains the silence. An
        // ABORTED chain (barge-in) is not "audio unavailable".
        if (!result.spokenBy && !result.aborted) callbacks.onAudioUnavailable?.();
      })
      .finally(() => {
        playing = false;
        playNext();
      });
  }

  return {
    push(chunk: VoiceAudioChunk) {
      const entry: (typeof queue)[number] = { chunk };
      // Already speaking and this is the very next sentence: start its
      // synthesis now instead of when the current one ends.
      if (playing && queue.length === 0 && voiceEpochRef.current === epoch && kokoroWillSpeak(chunk)) {
        entry.kokoroPrefetch = prefetchKokoro(chunk.textFragment);
      }
      queue.push(entry);
      playNext();
    },
    // Part 5(a) required addition - true once nothing is playing and
    // nothing is queued. Used to gate "Completed" on onDone AND
    // queue-drained both being true (see sendTurn's maybeComplete below).
    isEmpty() {
      return !playing && queue.length === 0;
    },
  };
}
