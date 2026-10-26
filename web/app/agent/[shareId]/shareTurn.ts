"use client";

// The share page's push-to-talk turn (POST .../voice/turn -> SSE): lifted out
// of ShareCallPanel.tsx (file-size ratchet) - a plain async function taking
// the panel's deps, same contract the in-component version had.
//
// The reply streams as transcript -> reply-delta -> audio-chunk -> done
// (api.ts's sendSharedVoiceTurn). Audio chunks play one at a time, in wire
// order, through the shared voice chain (speakReply) - bounded in time, so
// this queue cannot wedge. Every completed exchange is appended to the
// panel's retained callLog (the reply text is on screen regardless of
// whether any voice could speak it).

import { speakReply } from "../../lib/voice/speakReply";
import { isLatinScriptText } from "../../lib/voice/voiceOutput";
import type { sendSharedVoiceTurn } from "./api";
import type { MutableRefObject, RefObject } from "react";

export type ShareStage = "idle" | "listening" | "thinking" | "speaking" | "error";

export interface ShareTurnDeps {
  shareId: string;
  sessionIdRef: MutableRefObject<string | null>;
  voiceEpochRef: MutableRefObject<number>;
  unmountedRef: MutableRefObject<boolean>;
  repliesMutedRef: MutableRefObject<boolean>;
  audioElRef: RefObject<HTMLAudioElement | null>;
  sendTurnApi: typeof sendSharedVoiceTurn;
  setStage: (s: ShareStage | ((prev: ShareStage) => ShareStage)) => void;
  setTranscript: (t: string | null) => void;
  setReply: (r: string | ((prev: string) => string)) => void;
  setError: (e: string | null) => void;
  setVoiceNotice: (n: string | null) => void;
  setServerVoiceMissing: (v: boolean) => void;
  appendCallLog: (entry: { role: "user" | "agent"; text: string }) => void;
  setMode: (m: "idle" | "push-to-talk" | "call") => void;
  /** Fires when the server turn errors out - lets the panel offer the
   *  on-device speech-to-text download as the fallback path. */
  onServerError?: () => void;
}

export async function sendShareTurn(deps: ShareTurnDeps, blob: Blob) {
  const {
    shareId,
    sessionIdRef,
    voiceEpochRef,
    unmountedRef,
    repliesMutedRef,
    audioElRef,
    sendTurnApi,
    setStage,
    setTranscript,
    setReply,
    setError,
    setVoiceNotice,
    setServerVoiceMissing,
    appendCallLog,
    setMode,
  } = deps;

  setStage("thinking");
  setTranscript(null);
  setReply("");
  const queue: { textFragment: string; audioBase64: string; method: string }[] = [];
  let playing = false;
  let speakStarted = false;
  let streamDone = false;
  const epoch = voiceEpochRef.current;
  const isStale = () => unmountedRef.current || voiceEpochRef.current !== epoch;

  function playNext() {
    if (playing) return;
    if (isStale()) {
      queue.length = 0;
      return;
    }
    const chunk = queue.shift();
    if (!chunk) {
      // Preserve the "error" stage - a drained queue never overwrites it.
      if (speakStarted || streamDone) setStage((prev) => (prev === "error" ? prev : "idle"));
      return;
    }
    playing = true;
    // Speaker-muted: the chunk drains without being spoken and without the
    // "text-only" notice - silence here is the visitor's choice, not a
    // failure. The text reply is already on screen/retained regardless.
    if (repliesMutedRef.current) {
      playing = false;
      playNext();
      return;
    }
    if (!chunk.audioBase64 && chunk.method === "text-only-fallback" && isLatinScriptText(chunk.textFragment)) {
      setServerVoiceMissing(true);
    }
    // speakReply never rejects and is bounded in time (start deadlines and
    // a playback ceiling per voice), so this queue cannot wedge.
    void speakReply({
      text: chunk.textFragment,
      audioBase64: chunk.audioBase64 || null,
      method: chunk.method,
      getAudioEl: () => audioElRef.current,
      isStale,
      neverFetch: true,
      onStart: () => {
        if (isStale()) return;
        speakStarted = true;
        setStage("speaking");
      },
    })
      .then((result) => {
        if (isStale()) return;
        if (!result.spokenBy && !result.aborted && chunk.textFragment?.trim()) {
          setVoiceNotice("This reply is text-only - no voice could play it on this browser.");
        }
      })
      .finally(() => {
        playing = false;
        playNext();
      });
  }

  try {
    await sendTurnApi(shareId, blob, sessionIdRef.current, {
      onTranscript: (payload) => {
        setTranscript(payload.transcript);
        // Retained: every completed exchange stays on screen for the visit
        // (the "retained whatever text response it gives" ask) - the
        // transcript line lands now, the reply line on done.
        if (payload.transcript?.trim()) {
          appendCallLog({ role: "user", text: payload.transcript.trim() });
        }
      },
      onReplyDelta: (text) => setReply((prev) => prev + text),
      onAudioChunk: (chunk) => {
        queue.push(chunk);
        playNext();
      },
      onDone: (payload) => {
        setReply(payload.replyText);
        if (payload.replyText?.trim()) {
          appendCallLog({ role: "agent", text: payload.replyText.trim() });
        }
        if (payload.sessionId) sessionIdRef.current = payload.sessionId;
        streamDone = true;
        if (queue.length === 0 && !playing) setStage("idle");
      },
      onError: (message) => {
        setError(message);
        setStage("error");
        deps.onServerError?.();
      },
    });
  } finally {
    setMode("idle");
  }
}

// One spoken line on the on-device/browser voices - shared by the panel's
// replay button and the live-call `voiced:false` fallback (the worker has
// no server-side TTS key, so the caller speaks the reply text it got over
// the data channel). neverFetch keeps anonymous pages from downloading a
// model; no "/speak" call - anonymous callers cannot mint server audio.
export async function speakShareLine(
  text: string,
  getAudioEl: () => HTMLAudioElement | null,
  isStale: () => boolean
): Promise<void> {
  await speakReply({
    text,
    getAudioEl,
    isStale,
    neverFetch: true,
    voices: ["kokoro", "web-speech"],
  });
}
