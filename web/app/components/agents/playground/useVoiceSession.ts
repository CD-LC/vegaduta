"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "../../../lib/api";
// "Read aloud" goes through the same voice chain as the call panel
// (lib/voice): bounded start/playback deadlines, short fragments so Kokoro's
// 600-character clip never cuts a reply off, English-only gating for Kokoro,
// and a language-matched browser voice for everything else.
import { kokoroReadyFor, speakReply } from "../../../lib/voice/speakReply";
import { stopAllSpeech, unlockAudioElement, unlockWebSpeech } from "../../../lib/voice/voicePlayers";
import { useLatestRef } from "../../../lib/hooks/useLatestRef";
import type { PlaygroundAgent, PlaygroundTurn } from "./types";

/**
 * Read-aloud for the playground: per-turn "Play this reply" transport (play /
 * stop / skip ahead), the persisted default-muted auto-speak toggle, and the
 * auto-speak of each newly completed assistant reply.
 *
 * The speaking turn's index is owned by the caller (it passes the setter)
 * because the transcript window needs the value too: a turn being read aloud
 * must never scroll out of the window.
 */
export function useVoiceSession({
  agent,
  turns,
  setSpeakingTurnIndex,
}: {
  agent: PlaygroundAgent | null;
  turns: PlaygroundTurn[];
  setSpeakingTurnIndex: (index: number | null) => void;
}) {

  // "Play this reply" controls - index of the turn currently being
  // synthesized/played (so only that one turn's row shows transport controls),
  // plus which phase it's in. "loading" = waiting on the TTS round trip,
  // "playing" = audible right now. Any other value means nothing is playing.
  const [speakPhase, setSpeakPhase] = useState<"idle" | "loading" | "playing">("idle");
  // Whether the CURRENT playback is a real, seekable media clip (the
  // ElevenLabs MP3) rather than the Web Speech API fallback. The Web Speech
  // API exposes no timeline at all - no duration, no currentTime, no seek -
  // so "skip ahead" is genuinely impossible there and the control is disabled
  // rather than silently doing nothing.
  const [speakSeekable, setSpeakSeekable] = useState(false);
  const speakAudioRef = useRef<HTMLAudioElement | null>(null);
  // Monotonic token bumped on every start AND every stop. Every async
  // continuation (the speakText round trip, <audio> ended/error, the
  // SpeechSynthesisUtterance callbacks) captures the token it started under
  // and no-ops if it no longer matches. Without this, stopping playback - or
  // starting a different turn - lets the previous clip's late "ended" callback
  // clear the state of the new one, which is exactly how a control ends up
  // stuck showing "playing" with nothing audible.
  const speakTokenRef = useRef(0);
  const speakUnlockedRef = useRef(false);
  // Speaker muted by default (ask 2026-09-30: "in agent workbench, speaker
  // should be in mute by default" - supersedes the earlier auto-speak-every-
  // reply default). Persisted per-device; default OFF so a fresh session is
  // silent until the person unmutes. Independent of the per-turn "Play this
  // reply" button above - that one still works manually regardless of this
  // setting, e.g. to replay an older message.
  const [autoSpeakEnabled, setAutoSpeakEnabledState] = useState(false);
  useEffect(() => {
    try {
      const stored = window.localStorage?.getItem("playground.autoSpeak");
      if (stored === "1") setAutoSpeakEnabledState(true);
    } catch {
      // Best-effort - default stays muted.
    }
  }, []);
  function setAutoSpeakEnabled(value: boolean) {
    setAutoSpeakEnabledState(value);
    try {
      window.localStorage?.setItem("playground.autoSpeak", value ? "1" : "0");
    } catch {
      // Best-effort - the in-memory state still takes effect this session.
    }
  }
  // Index of the last turn already auto-spoken (or offered to), so a re-render
  // (e.g. a metrics field arriving after content) never re-triggers playback
  // for the same reply. Reset per agent below, alongside every other
  // per-agent transcript ref.
  const autoSpokenIndexRef = useRef(-1);

  // Halts whatever is currently being spoken and returns the controls to idle.
  // Safe to call when nothing is playing, and safe to call from an unmount
  // cleanup (guards every DOM touch).
  function stopSpeaking() {
    // The token bump is also what aborts a voice chain that is still
    // PREPARING sound (speakReply's isStale polls it).
    speakTokenRef.current += 1;
    const audio = speakAudioRef.current;
    // Settles the shared voice chain's pending playback promise and cancels
    // Web Speech; never throws.
    stopAllSpeech(audio);
    if (audio) {
      audio.pause();
      // Drop the (potentially multi-MB) decoded data: URL rather than leaving
      // it attached to a hidden element for the rest of the session.
      audio.removeAttribute("src");
      try {
        audio.load();
      } catch {
        // Some browsers throw on load() with no source - harmless here.
      }
    }
    if (typeof window !== "undefined" && window.speechSynthesis) {
      window.speechSynthesis.cancel();
    }
    setSpeakingTurnIndex(null);
    setSpeakPhase("idle");
    setSpeakSeekable(false);
  }

  // "Skip ahead" - the backend hands back one complete MP3 per reply (see
  // api.speakText: a single audioBase64 blob, not a chunk stream), so there is
  // a real timeline to move along. Jumping +10s is the useful behaviour for a
  // spoken paragraph you've already got the gist of; running past the end just
  // ends playback, which is what a skip at the tail should do anyway.
  const SKIP_AHEAD_SECONDS = 10;
  function skipAhead() {
    const audio = speakAudioRef.current;
    if (!audio || !speakSeekable) return;
    const duration = Number.isFinite(audio.duration) ? audio.duration : null;
    const next = audio.currentTime + SKIP_AHEAD_SECONDS;
    if (duration != null && next >= duration) {
      stopSpeaking();
      return;
    }
    try {
      audio.currentTime = next;
    } catch {
      // Not seekable yet (metadata still loading) - leave playback alone.
    }
  }

  // "Play this reply" - edge-first (maintainer decision: "by default it
  // should speak using edge"). Priority: on-device Kokoro (only if its model
  // is ALREADY installed and the text is in a script it can speak - nothing
  // here ever starts a download) -> hosted ElevenLabs-then-Sarvam via
  // api.speakText -> the browser's own Web Speech API. On-device first means
  // zero server round-trip and zero dependence on the hosted pool for the
  // common case; the hosted/browser legs stay as the fallback chain.
  //
  // 2026-09-19: every leg now runs through lib/voice's chain (speakReply).
  // What that changed: Kokoro gets the reply in short fragments (its worker
  // silently clips one synth at 600 characters, so long replies used to stop
  // mid-thought), a voice that never starts or never ends is abandoned after
  // a deadline instead of stranding the control, non-Latin text skips the
  // English-only Kokoro, and Web Speech is given the text's language. Only a
  // hosted clip is ONE seekable file; Kokoro fragments and Web Speech are not,
  // so "skip ahead" is offered for the hosted leg alone.
  async function handleSpeak(turnIndex: number, text: string) {
    if (!agent || !text) return;
    // Always stop first: this is what makes starting a second message cut the
    // first one off instead of two voices talking over each other. It also
    // bumps the token, invalidating the previous attempt's callbacks.
    stopSpeaking();
    const token = speakTokenRef.current;
    const isCurrent = () => speakTokenRef.current === token;
    // When this runs from the play button it is a user gesture: unlock the
    // element and the speech engine now, because the sound starts after an
    // await (Safari only honours play() issued during the gesture). From the
    // auto-speak effect it is not a gesture and these are harmless no-ops.
    if (!speakUnlockedRef.current) {
      speakUnlockedRef.current = true;
      unlockAudioElement(speakAudioRef.current);
      unlockWebSpeech();
    }

    setSpeakingTurnIndex(turnIndex);
    setSpeakPhase("loading");
    setSpeakSeekable(false);

    const getAudioEl = () => speakAudioRef.current;
    const isStale = () => !isCurrent();
    const onStart = (step: string) => {
      if (!isCurrent()) return;
      setSpeakPhase("playing");
      setSpeakSeekable(step === "server-audio");
    };
    // Finished, failed everywhere, or nothing could start: either way the
    // control goes back to idle. A superseded attempt must not touch it.
    const finish = () => {
      if (isCurrent()) stopSpeaking();
    };

    if (kokoroReadyFor(text)) {
      const local = await speakReply({ text, getAudioEl, isStale, onStart, voices: ["kokoro"] });
      if (!isCurrent()) return;
      if (local.spokenBy || local.aborted) {
        finish();
        return;
      }
      // Installed but this call declined (worker not ready, synth failed or
      // too slow) - fall through to the hosted/browser chain below rather
      // than leaving the turn silent.
    }

    let hosted: { audioBase64?: string; method?: string; speechText?: string } | null = null;
    try {
      hosted = await api.speakText(agent.id, text);
    } catch {
      // Best-effort - the browser voice below still gets a go; a convenience
      // control should not surface an error of its own.
      hosted = null;
    }
    if (!isCurrent()) return; // stopped (or superseded) while synthesizing
    await speakReply({
      // speechText is the backend-normalized-for-speech variant (markdown and
      // symbols stripped); the raw text when the server did not answer.
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

  // Auto-speak: fires once per newly-completed assistant turn when
  // autoSpeakEnabled is on. Deliberately does NOT fire for a turn that's
  // still `pending` (streaming) - waits for the full reply, same as the
  // manual "Play this reply" button always has, so this never races partial
  // text. Skips turns with no content (a turn that errored out empty) since
  // there's nothing to say. autoSpokenIndexRef (not turns.length alone)
  // guards against re-firing on every re-render of the same completed turn
  // (e.g. a metrics field landing a tick after content does).
  // handleSpeak is re-created every render; read through a latest-ref so this
  // effect stays keyed on the turns, not on handleSpeak's identity.
  const handleSpeakRef = useLatestRef(handleSpeak);
  useEffect(() => {
    if (!autoSpeakEnabled) return;
    const lastIndex = turns.length - 1;
    if (lastIndex <= autoSpokenIndexRef.current) return;
    const last = turns[lastIndex];
    if (!last || last.role !== "assistant" || last.pending || last.failed || !last.content) return;
    autoSpokenIndexRef.current = lastIndex;
    void handleSpeakRef.current(lastIndex, last.content);
  }, [turns, autoSpeakEnabled, handleSpeakRef]);

  // Unmount cleanup - without this, navigating away mid-reply leaves the
  // browser still speaking (speechSynthesis is a global, it does not stop when
  // the component that started it goes away).
  useEffect(() => {
    return () => {
      stopSpeaking();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return {
    speakPhase,
    speakSeekable,
    speakAudioRef,
    autoSpeakEnabled,
    setAutoSpeakEnabled,
    autoSpokenIndexRef,
    stopSpeaking,
    skipAhead,
    handleSpeak,
  };
}
