"use client";

// "Play this reply" for a transcript line - the same Kokoro -> hosted /speak
// -> Web Speech chain the playground's own play button runs
// (useVoiceSession.handleSpeak), adapted to the call panel's transcript: the
// running record (callTranscript) is flat rather than per-turn, so replay is
// keyed by line index instead of turnIndex.
//
// Separate from the push-to-talk auto-speech path on purpose: an explicit
// play press is a user gesture and must work even when the panel's speaker
// toggle has auto-speech muted - same semantics as the playground, where the
// auto-speak toggle never disables the per-turn play control.

import { useRef, useState, type MutableRefObject, type RefObject } from "react";
import { api } from "../../../lib/api";
import { kokoroReadyFor, speakReply } from "../../../lib/voice/speakReply";
import { stopAllSpeech, unlockAudioElement, unlockWebSpeech } from "../../../lib/voice/voicePlayers";
import type { VoiceStep } from "../../../lib/voice/voiceOutput";

export interface ReplayState {
  /** Index into callTranscript of the line being spoken. */
  index: number;
  phase: "loading" | "playing";
  /** True while the playing leg is seekable hosted audio (server-audio step). */
  seekable: boolean;
}

export function useReplyPlayback({
  getAgentId,
  audioElRef,
  voiceEpochRef,
  unmountedRef,
}: {
  getAgentId: () => string | null | undefined;
  audioElRef: RefObject<HTMLAudioElement | null>;
  // The panel's barge-in epoch: a new hold bumps it (primeVoiceOutput's
  // job), and a replay must go stale with it - otherwise a replayed line
  // keeps talking over, or restarts under, the person's next recording.
  voiceEpochRef: MutableRefObject<number>;
  unmountedRef: MutableRefObject<boolean>;
}) {
  const [replay, setReplay] = useState<ReplayState | null>(null);
  // Monotonic token - bumping it invalidates every in-flight replay's
  // callbacks, the same job speakTokenRef does in useVoiceSession.
  const replayTokenRef = useRef(0);
  const unlockedRef = useRef(false);

  function stopReplay() {
    replayTokenRef.current += 1;
    stopAllSpeech(audioElRef.current);
    setReplay(null);
  }

  function skipReplay(seconds = 10) {
    const el = audioElRef.current;
    if (!el || !Number.isFinite(el.duration)) return;
    try {
      el.currentTime = Math.min(el.duration, el.currentTime + seconds);
    } catch {
      // Best-effort - a stream that rejects currentTime just skips nothing.
    }
  }

  async function replayReply(index: number, text: string) {
    const agentId = getAgentId();
    if (!agentId || !text) return;
    // Always stop first: starting a second line's replay cuts the first one
    // off instead of two voices talking over each other, and a new
    // push-to-talk hold does the same to us (its prime() bumps the epoch and
    // stops this element).
    stopReplay();
    const token = replayTokenRef.current;
    const epoch = voiceEpochRef.current;
    const isCurrent = () =>
      !unmountedRef.current && replayTokenRef.current === token && voiceEpochRef.current === epoch;
    // A user gesture - unlock now, playback starts after awaits.
    if (!unlockedRef.current) {
      unlockedRef.current = true;
      unlockAudioElement(audioElRef.current);
      unlockWebSpeech();
    }

    setReplay({ index, phase: "loading", seekable: false });
    const getAudioEl = () => audioElRef.current;
    const isStale = () => !isCurrent();
    const onStart = (step: VoiceStep) => {
      if (!isCurrent()) return;
      setReplay({ index, phase: "playing", seekable: step === "server-audio" });
    };
    const finish = () => {
      if (isCurrent()) setReplay(null);
    };

    // An installed Kokoro gets first go (it is also the leg a "kokoro:*"
    // voice-over pick promotes - the pick is honoured automatically since
    // speakReply reads the device pref unless callers override it).
    if (kokoroReadyFor(text)) {
      const local = await speakReply({ text, getAudioEl, isStale, onStart, voices: ["kokoro"] });
      if (!isCurrent()) return;
      if (local.spokenBy || local.aborted) {
        finish();
        return;
      }
      // Declined (not ready / synth failed) - fall through to hosted+browser.
    }

    let hosted: { audioBase64?: string; method?: string; speechText?: string } | null = null;
    try {
      hosted = await api.speakText(agentId, text);
    } catch {
      hosted = null; // the browser voice still gets a go
    }
    if (!isCurrent()) return;
    await speakReply({
      text: hosted?.speechText || text,
      audioBase64: hosted?.audioBase64 || null,
      method: hosted?.method || null,
      getAudioEl,
      isStale,
      onStart,
      voices: ["server-audio", "web-speech"],
    });
    finish();
  }

  return { replay, replayReply, stopReplay, skipReplay };
}
