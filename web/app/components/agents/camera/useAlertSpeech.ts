import { useEffect, useRef } from "react";
// Spoken watch alerts go through the shared voice chain (lib/voice): an
// ALREADY-INSTALLED Kokoro speaks English alerts, the browser's Web Speech API
// speaks everything else, and a voice that never starts is abandoned after a
// deadline. Nothing here ever downloads a model.
import { speakReply } from "../../../lib/voice/speakReply";
import { stopAllSpeech } from "../../../lib/voice/voicePlayers";

/**
 * Element for Kokoro's audio, and a token so a newer alert (or switching
 * alerts off, an agent switch, unmount) silences the one still speaking.
 * A change of `agentId` or `canSee` (or unmount) silences the alert in flight.
 */
export function useAlertSpeech(agentId: unknown, canSee: boolean) {
  const alertAudioRef = useRef<HTMLAudioElement | null>(null);
  const alertSpeakTokenRef = useRef(0);
  useEffect(() => {
    // Captured now: React detaches the <audio> ref before this cleanup runs
    // on unmount, so reading alertAudioRef.current there would stop nothing.
    const alertAudio = alertAudioRef.current;
    return () => {
      // The previous agent's alert must not keep talking over the new screen.
      alertSpeakTokenRef.current += 1;
      stopAllSpeech(alertAudio);
    };
  }, [agentId, canSee]);
  function stopAlertSpeech() {
    alertSpeakTokenRef.current += 1;
    stopAllSpeech(alertAudioRef.current);
  }
  function speakAlert(text: string) {
    stopAlertSpeech();
    const token = alertSpeakTokenRef.current;
    // speakReply never rejects; a silent alert is acceptable (the text is in
    // the transcript), so its result is not surfaced.
    void speakReply({
      text,
      getAudioEl: () => alertAudioRef.current,
      isStale: () => alertSpeakTokenRef.current !== token,
    });
  }
  return { alertAudioRef, stopAlertSpeech, speakAlert };
}
