"use client";

// Push-to-talk recording: start a hold (mic capture + MediaRecorder) and release
// it. Lifted out of CallPanel.tsx unchanged; takes the per-render CallCtx.

import { micStateFromError } from "../../../lib/voice/callPreflight";
import { mediaRecorderSupported } from "../../../lib/voice/callEnvironment";
import { MIN_HOLD_MS, MIN_VIABLE_AUDIO_BYTES } from "../CallPanel/constants";
import { errorMessage, errorName } from "../../../lib/errors";
import type { CallCtx } from "./callContext";

export async function startPushToTalkImpl(ctx: CallCtx) {
  const { agent, t, recording, setRecording, pipelineBusy, setPipelineError, setCallStage, setAudioUnavailableNotice, setSttReadyNotice, pendingSttRef, activeAgentIdRef, recordingStartedAtRef, mediaStreamRef, mediaRecorderRef, chunksRef, setMicState, setRecorderSupported, primeVoiceOutputFromGesture, sendTurn } = ctx;
  if (!agent || recording || pipelineBusy) return;
  // Fix #1 (P0): the agent this specific hold belongs to - compared
  // against activeAgentIdRef.current in the recorder's onstop handler
  // below, so a hold started under this agent but released after a switch
  // to a different one is dropped instead of sent to (and rendered under)
  // the new agent.
  const startedAgentId = agent.id;
  setPipelineError(null);
  setAudioUnavailableNotice(null);
  setCallStage("idle");
  // 2026-09-19: this handler IS the user gesture. Everything that autoplay
  // policy or a cold model would otherwise break seconds later, when the
  // reply arrives, is done here, synchronously, before the first await.
  primeVoiceOutputFromGesture();
  // A new hold supersedes a parked recording: the person is saying it (or
  // something newer) again, so the old one must never be replayed later.
  pendingSttRef.current = null;
  setSttReadyNotice(null);
  // Feature-detect instead of letting `new MediaRecorder` throw a
  // ReferenceError whose message ("MediaRecorder is not defined") used to
  // be shown to the person verbatim. The preflight line explains it.
  if (!navigator.mediaDevices || typeof navigator.mediaDevices.getUserMedia !== "function") {
    setMicState(window.isSecureContext === false ? "insecure" : "unsupported");
    return;
  }
  if (!mediaRecorderSupported()) {
    setRecorderSupported(false);
    return;
  }
  try {
    console.log(`[Voice] Starting microphone capture for agent ${agent.id}`);
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
        sampleRate: 16000,
      },
    });
    mediaStreamRef.current = stream;
    setMicState("granted");
    console.log(`[Voice] Microphone stream acquired, stream.active: ${stream.active}, tracks: ${stream.getAudioTracks().length}`);

    let mimeType = "";
    if (typeof MediaRecorder !== "undefined") {
      if (MediaRecorder.isTypeSupported("audio/wav")) {
        mimeType = "audio/wav";
      } else if (MediaRecorder.isTypeSupported("audio/webm")) {
        mimeType = "audio/webm";
      }
    }
    console.log(`[Voice] Selected MIME type: ${mimeType || "default"}`);

    const options = mimeType ? { mimeType } : {};
    const recorder = new MediaRecorder(stream, options);
    chunksRef.current = [];
    recorder.ondataavailable = (e) => {
      if (e.data && e.data.size > 0) {
        chunksRef.current.push(e.data);
        console.log(`[Voice] Recorded chunk: ${e.data.size} bytes`);
      }
    };
    recorder.onerror = (e) => {
      console.error(`[Voice] Recording error:`, e.error);
      setPipelineError(`Recording error: ${e.error}. Please try again.`);
    };
    recorder.onstop = () => {
      stream.getTracks().forEach((t) => t.stop());
      mediaStreamRef.current = null;
      console.log(`[Voice] Recording stopped, total chunks: ${chunksRef.current.length}`);
      // Fix #1 (P0): the agent switched while this hold was still active -
      // this closure otherwise still targets the ABANDONED agent (its
      // sendTurn/api.sendVoiceTurnStream call would use `agent.id` from
      // this same stale render), which would post the turn to the wrong
      // agent's endpoint and render its reply into whichever agent's panel
      // happens to be showing now. Drop it entirely rather than send it.
      if (activeAgentIdRef.current !== startedAgentId) {
        console.warn(
          `[Voice] Dropping a push-to-talk turn recorded under a since-abandoned agent (${startedAgentId})`
        );
        return;
      }
      if (chunksRef.current.length === 0) {
        setPipelineError("No audio was recorded. Please speak and try again.");
        setCallStage("failed");
        return;
      }
      const blob = new Blob(chunksRef.current, { type: recorder.mimeType || mimeType || "audio/webm" });
      console.log(`[Voice] Created blob: ${blob.size} bytes, type: ${blob.type}`);
      // A bare WebM/Opus container with no real audio frames muxed in is
      // ~100-150 bytes (just the EBML+Segment headers) - "works in Edge,
      // fails in Chrome" was exactly this: released the button before
      // Chrome's Opus encoder had buffered enough samples to flush a real
      // frame, so `stop()` finalized a header-only container that Sarvam/
      // Whisper correctly reject as unreadable. size===0 never caught this
      // because the container bytes are real, just contain no audio - a
      // meaningfully higher floor (well under any genuine >=300ms
      // utterance, comfortably above container-only overhead) does.
      if (blob.size < MIN_VIABLE_AUDIO_BYTES) {
        setPipelineError("That was too short to capture any speech - hold the button a little longer and try again.");
        setCallStage("failed");
        return;
      }
      // Part 5(a) trigger 2: blob assembled, sendTurn about to be called -
      // Listening -> Transcribing (the request is about to go in flight;
      // nothing back yet - the backend's synchronous STT fallback chain
      // runs in exactly this window).
      setCallStage("transcribing");
      void sendTurn(blob);
    };
    // A timeslice (vs. the default "flush everything only at stop()")
    // forces the encoder to hand off whatever it has buffered every 250ms
    // instead of gambling on stop() landing after it has produced a
    // frame at all - the other half of the same Chrome-short-hold fix:
    // even a hold barely longer than one interval now has a real chance
    // of containing actual encoded audio by the time stop() is called.
    recorder.start(250);
    recordingStartedAtRef.current = Date.now();
    mediaRecorderRef.current = recorder;
    setRecording(true);
    // Part 5(a) trigger 1: recorder actually started -> Listening.
    setCallStage("listening");
    console.log(`[Voice] Recording started`);
  } catch (err) {
    console.error(`[Voice] Microphone access error:`, err);
    // Feeds the preflight, which adds the one next step for this exact
    // failure under the error below. "unknown" leaves a previous state be.
    const failedMicState = micStateFromError(errorName(err));
    if (failedMicState !== "unknown") setMicState(failedMicState);
    let message = errorMessage(err, "Microphone access was denied.");
    if (errorName(err) === "NotAllowedError") {
      // Fix #10 (P2): reuse the single localized micDenied string the
      // LiveKit call path already uses, instead of this hand-rolled one.
      message = t("micDenied");
    } else if (errorName(err) === "NotFoundError") {
      message = "No microphone found. Please check your device.";
    } else if (errorName(err) === "NotReadableError") {
      message = "Microphone is in use by another application. Please close other apps and try again.";
    }
    setPipelineError(message);
  }
}

export function stopPushToTalkImpl(ctx: CallCtx) {
  const { setRecording, recordingStartedAtRef, mediaRecorderRef } = ctx;
  const recorder = mediaRecorderRef.current;
  if (!recorder || recorder.state === "inactive") {
    mediaRecorderRef.current = null;
    setRecording(false);
    return;
  }
  const elapsed = recordingStartedAtRef.current ? Date.now() - recordingStartedAtRef.current : MIN_HOLD_MS;
  const remaining = MIN_HOLD_MS - elapsed;
  mediaRecorderRef.current = null;
  setRecording(false);
  if (remaining > 0) {
    // See MIN_HOLD_MS's comment - let the encoder catch up before
    // finalizing instead of finalizing on a too-short hold immediately.
    setTimeout(() => {
      if (recorder.state !== "inactive") recorder.stop();
    }, remaining);
  } else {
    recorder.stop();
  }
}
