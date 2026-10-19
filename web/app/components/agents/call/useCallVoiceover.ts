"use client";

// The call panel's voice-over state: the spoken-replies speaker toggle and
// the per-agent live-call voice pick, both persisted. Lifted out of
// CallPanel.tsx (file-size ratchet) - the callers there are one line each.
//
//   * repliesMuted: mutes the panel's OWN playback (push-to-talk replies and
//     replayed lines). Distinct from agentAudioMuted, which mutes the live
//     call's remote audio track; muting here also silences in-flight speech
//     through the same barge-in mechanics a new hold uses.
//   * callVoice: the caller's per-call pick of the LIVE call's own voice (a
//     tenant ElevenLabs voice), sent on the token request's ttsVoice fields
//     and applied by the voice-agent worker. Persisted per agent.

import { useEffect, useRef, useState, type MutableRefObject, type RefObject } from "react";
import { stopAllSpeech } from "../../../lib/voice/voicePlayers";
import type { CallVoicePick } from "../CallPanel/VoiceoverControls";

const REPLIES_MUTED_KEY = "callpanel.repliesMuted";
const callVoiceKey = (agentId: string) => `vd.callVoice.${agentId}`;

export function useCallVoiceover({
  agentId,
  voiceEpochRef,
  audioElRef,
  onSilence,
}: {
  agentId: string | undefined;
  voiceEpochRef: MutableRefObject<number>;
  audioElRef: RefObject<HTMLAudioElement | null>;
  /** Anything else muting should silence (the transcript replay). */
  onSilence?: () => void;
}) {
  const [repliesMuted, setRepliesMuted] = useState(false);
  const repliesMutedRef = useRef(false);
  const [callVoice, setCallVoice] = useState<CallVoicePick | null>(null);
  const callVoiceRef = useRef<CallVoicePick | null>(null);

  // Speaker toggle (persisted like the playground's autoSpeak). Muting also
  // silences whatever is being spoken now.
  useEffect(() => {
    try {
      const on = localStorage.getItem(REPLIES_MUTED_KEY) === "1";
      repliesMutedRef.current = on;
      setRepliesMuted(on);
    } catch {
      // Private-mode: the toggle still works for the session via the ref.
    }
  }, []);

  function toggleRepliesMuted() {
    const next = !repliesMutedRef.current;
    repliesMutedRef.current = next;
    setRepliesMuted(next);
    try {
      localStorage.setItem(REPLIES_MUTED_KEY, next ? "1" : "0");
    } catch {
      // Best-effort persist only.
    }
    if (next) {
      voiceEpochRef.current += 1;
      stopAllSpeech(audioElRef.current);
      onSilence?.();
    }
  }

  // Per-agent live-call voice pick: persisted so a caller's choice survives
  // a reload, keyed by agent (each agent's voice-over is its own decision).
  useEffect(() => {
    let pick: CallVoicePick | null = null;
    try {
      const raw = agentId ? localStorage.getItem(callVoiceKey(agentId)) : null;
      const parsed = raw ? JSON.parse(raw) : null;
      if (parsed && typeof parsed.voiceId === "string" && typeof parsed.provider === "string") {
        pick = { voiceId: parsed.voiceId, provider: parsed.provider, label: parsed.label };
      }
    } catch {
      pick = null;
    }
    callVoiceRef.current = pick;
    setCallVoice(pick);
  }, [agentId]);

  function changeCallVoice(pick: CallVoicePick | null) {
    callVoiceRef.current = pick;
    setCallVoice(pick);
    try {
      if (agentId) {
        if (pick) localStorage.setItem(callVoiceKey(agentId), JSON.stringify(pick));
        else localStorage.removeItem(callVoiceKey(agentId));
      }
    } catch {
      // Best-effort persist only.
    }
  }

  return { repliesMuted, repliesMutedRef, toggleRepliesMuted, callVoice, callVoiceRef, changeCallVoice };
}
