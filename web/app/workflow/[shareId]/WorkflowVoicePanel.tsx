"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Loader2, Mic } from "lucide-react";
import { sendSharedWorkflowVoiceTurn } from "./api";
// The spoken result goes through the shared voice chain (lib/voice): the
// server's audio when there is some; otherwise the on-device voice, but only
// when it is ALREADY on this device and verifiably still in the browser cache
// (neverFetch - this public page never causes a model download, and does not
// offer one either: it is a one-shot result, not a conversation); otherwise
// the browser's built-in voice. Each voice has a start deadline, so a browser
// that refuses autoplay falls through instead of staying silent.
import { speakReply } from "../../lib/voice/speakReply";
import { plainSpokenExcerpt } from "../../lib/voice/voiceOutput";
import { stopAllSpeech, unlockAudioElement, unlockWebSpeech } from "../../lib/voice/voicePlayers";

// Push-to-talk for the public workflow-share surface: hold to record, release
// to run the workflow ONCE and hear the result. This is NOT a live LiveKit
// call and NOT a multi-turn conversation - simplified copy of
// CallPanel.tsx's startPushToTalk/stopPushToTalk (same getUserMedia/
// MediaRecorder setup, same MIN_HOLD_MS/MIN_VIABLE_AUDIO_BYTES gates) for a
// single blocking-JSON turn instead of a streamed SSE reply.

// See CallPanel.tsx's own comment on this constant: a quick tap-and-release
// can land before the MediaRecorder's encoder has buffered enough samples to
// produce a real frame - stopPushToTalk waits out the rest of this window
// before finalizing, giving the encoder (flushed every 250ms via the
// timeslice passed to start()) a real chance to have produced a chunk.
const MIN_HOLD_MS = 350;
// A bare WebM/Opus container with no audio frames muxed in is roughly
// 100-150 bytes; this floor sits comfortably above that and comfortably
// below what even a very short (>=MIN_HOLD_MS) real utterance encodes to.
const MIN_VIABLE_AUDIO_BYTES = 1000;

type Phase = "idle" | "recording" | "sending" | "error";

// A workflow result can be pages of markdown. When the server's audio is
// missing or will not play and a browser/on-device voice reads it instead, only a plain-text opening
// of it is spoken (plainSpokenExcerpt); the full result is always on screen.
const SPOKEN_FALLBACK_MAX_CHARS = 500;

export default function WorkflowVoicePanel({
  shareId,
  onResult,
}: {
  shareId: string;
  onResult: (resultText: string) => void;
}) {
  const [phase, setPhase] = useState<Phase>("idle");
  const [error, setError] = useState<string | null>(null);

  const mediaStreamRef = useRef<MediaStream | null>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const recordingStartedAtRef = useRef<number | null>(null);
  const audioElRef = useRef<HTMLAudioElement | null>(null);
  // Bumped by a new hold and on unmount: an earlier result stops talking.
  const voiceEpochRef = useRef(0);
  const audioUnlockedRef = useRef(false);

  useEffect(() => {
    // Captured now: React detaches the <audio> ref before this cleanup runs
    // on unmount, so reading audioElRef.current there would stop nothing.
    const audioEl = audioElRef.current;
    return () => {
      voiceEpochRef.current += 1;
      stopAllSpeech(audioEl);
    };
  }, []);

  const sendTurn = useCallback(
    async (blob: Blob) => {
      setPhase("sending");
      setError(null);
      const result = await sendSharedWorkflowVoiceTurn(shareId, blob);
      if (!result.ok || typeof result.resultText !== "string") {
        setPhase("error");
        setError(
          result.error === "rate_limited"
            ? "Voice turns are coming in a little fast — give it a moment and try again."
            : result.error === "expired"
              ? "This shared workflow isn't available any more."
              : "Couldn't complete that voice turn — please try again."
        );
        return;
      }
      setPhase("idle");
      // Text first: nothing about speech may delay or hide the result.
      onResult(result.resultText);
      // Not awaited - speakReply never rejects, and the button frees up while
      // the result is read out. `method` picks the MIME type inside the chain
      // (Sarvam is WAV, ElevenLabs MP3).
      const epoch = voiceEpochRef.current;
      void speakReply({
        text: plainSpokenExcerpt(result.resultText, SPOKEN_FALLBACK_MAX_CHARS),
        audioBase64: result.audioBase64 || null,
        method: result.method || null,
        getAudioEl: () => audioElRef.current,
        isStale: () => voiceEpochRef.current !== epoch,
        neverFetch: true,
      });
    },
    [shareId, onResult]
  );

  const startPushToTalk = useCallback(async () => {
    if (phase === "recording" || phase === "sending") return;
    setError(null);
    // This handler is the user gesture: silence an earlier result (the
    // microphone would record it) and unlock audio for the reply that plays
    // after the awaits below.
    voiceEpochRef.current += 1;
    stopAllSpeech(audioElRef.current);
    if (!audioUnlockedRef.current) {
      audioUnlockedRef.current = true;
      unlockAudioElement(audioElRef.current);
      unlockWebSpeech();
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          sampleRate: 16000,
        },
      });
      mediaStreamRef.current = stream;

      let mimeType = "";
      if (typeof MediaRecorder !== "undefined") {
        if (MediaRecorder.isTypeSupported("audio/wav")) {
          mimeType = "audio/wav";
        } else if (MediaRecorder.isTypeSupported("audio/webm")) {
          mimeType = "audio/webm";
        }
      }

      const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : {});
      chunksRef.current = [];
      recorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) chunksRef.current.push(e.data);
      };
      recorder.onerror = () => {
        setPhase("error");
        setError("Recording error — please try again.");
      };
      recorder.onstop = () => {
        stream.getTracks().forEach((t) => t.stop());
        mediaStreamRef.current = null;
        if (chunksRef.current.length === 0) {
          setPhase("error");
          setError("No audio was recorded. Please speak and try again.");
          return;
        }
        const blob = new Blob(chunksRef.current, { type: recorder.mimeType || mimeType || "audio/webm" });
        // See MIN_VIABLE_AUDIO_BYTES's comment - a container with no real
        // audio frames muxed in is far smaller than this floor.
        if (blob.size < MIN_VIABLE_AUDIO_BYTES) {
          setPhase("error");
          setError("That was too short to capture any speech — hold the button a little longer and try again.");
          return;
        }
        void sendTurn(blob);
      };
      // A timeslice (vs. the default "flush everything only at stop()")
      // forces the encoder to hand off whatever it has buffered every 250ms,
      // same reasoning as CallPanel.tsx's recorder.start(250).
      recorder.start(250);
      recordingStartedAtRef.current = Date.now();
      mediaRecorderRef.current = recorder;
      setPhase("recording");
    } catch (err: any) {
      setPhase("error");
      setError(
        err?.name === "NotAllowedError"
          ? "Microphone access was denied."
          : err?.name === "NotFoundError"
            ? "No microphone found. Please check your device."
            : err?.message || "Microphone access failed."
      );
    }
  }, [phase, sendTurn]);

  const stopPushToTalk = useCallback(() => {
    const recorder = mediaRecorderRef.current;
    if (!recorder || recorder.state === "inactive") {
      mediaRecorderRef.current = null;
      return;
    }
    const elapsed = recordingStartedAtRef.current ? Date.now() - recordingStartedAtRef.current : MIN_HOLD_MS;
    const remaining = MIN_HOLD_MS - elapsed;
    mediaRecorderRef.current = null;
    if (remaining > 0) {
      // See MIN_HOLD_MS's comment - let the encoder catch up before
      // finalizing instead of finalizing on a too-short hold immediately.
      setTimeout(() => {
        if (recorder.state !== "inactive") recorder.stop();
      }, remaining);
    } else {
      recorder.stop();
    }
  }, []);

  const recording = phase === "recording";
  const sending = phase === "sending";

  return (
    <div className="flex flex-col items-center gap-2 py-1">
      <button
        type="button"
        aria-label="Hold to speak"
        onMouseDown={startPushToTalk}
        onMouseUp={stopPushToTalk}
        onMouseLeave={() => {
          if (recording) stopPushToTalk();
        }}
        onTouchStart={(e) => {
          e.preventDefault();
          void startPushToTalk();
        }}
        onTouchEnd={(e) => {
          e.preventDefault();
          stopPushToTalk();
        }}
        onKeyDown={(e) => {
          if ((e.key === " " || e.key === "Enter") && !e.repeat) {
            e.preventDefault();
            void startPushToTalk();
          }
        }}
        onKeyUp={(e) => {
          if (e.key === " " || e.key === "Enter") {
            e.preventDefault();
            if (recording) stopPushToTalk();
          }
        }}
        disabled={sending}
        className={
          "flex size-14 items-center justify-center rounded-full border-2 transition-colors " +
          (recording
            ? "border-primary bg-primary text-primary-foreground"
            : "border-border/70 bg-card/50 text-foreground hover:border-primary/50") +
          (sending ? " cursor-not-allowed opacity-60" : "")
        }
      >
        {sending ? (
          <Loader2 className="size-5 animate-spin" aria-hidden="true" />
        ) : (
          <Mic className="size-5" aria-hidden="true" />
        )}
      </button>
      <p className="text-xs text-muted-foreground">
        {recording ? "Recording — release to run" : sending ? "Running…" : "Hold to speak"}
      </p>
      {error && (
        <p className="text-xs text-destructive" role="alert">
          {error}
        </p>
      )}
      {/* Hidden player for the returned voice reply - never rendered with
          native controls, this is a one-shot playback, not a media widget. */}
      <audio ref={audioElRef} className="hidden" />
    </div>
  );
}
