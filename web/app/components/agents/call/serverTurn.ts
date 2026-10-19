"use client";

// One push-to-talk utterance end to end: on-device first, then the server voice
// pipeline (streamed), then the server-STT-failure recovery. Lifted out of
// CallPanel.tsx unchanged; takes the per-render CallCtx.

import { api } from "../../../lib/api";
import { edgeEnabled } from "../../../lib/edge/capabilities";
import { edgeSttAvailable, isSttModelCached } from "../../../lib/edge/audio/whisper";
import { canRetryTurnOnDevice, isServerSttFailure, isServerSttOutage, serverSttFailureIsLive, shouldTranscribeOnDeviceFirst } from "../../../lib/voice/callPreflight";
import { pipelineErrorForFailedStt } from "./voiceHelpers";
import { errorMessage, errorStatus } from "../../../lib/errors";
import type { CallCtx } from "./callContext";

export async function sendTurnImpl(ctx: CallCtx, blob: Blob) {
  const { agent, sessionId, onSessionId, setPipelineBusy, setPipelineError, setLastTranscript, setLastReply, setCallStage, setAudioUnavailableNotice, setSttReadyNotice, setServerSttFailedAt, serverSttFailedAtRef, pendingSttRef, systemHealthRef, activeAgentIdRef, speechLikelyEnglish, makeAudioChunkQueue, sendTurnViaLocalStt, trySendTurnLocally, sendHostedTextTurn } = ctx;
  if (!agent) return;
  // Fix #1 (P0): captured once at entry - the agent this specific turn
  // belongs to. trySendTurnLocally/api.sendVoiceTurnStream below can each
  // take a while (STT, on-device reasoning, a network round trip); every
  // state-setting continuation past an await checks this against
  // activeAgentIdRef.current so a since-abandoned agent's late-arriving
  // transcript/reply/stage can never render into whichever agent's panel
  // is showing by the time it resolves.
  const sentAgentId = agent.id;
  const stillCurrentAgent = () => activeAgentIdRef.current === sentAgentId;
  // A new turn supersedes any parked recording (see pendingSttRef).
  pendingSttRef.current = null;
  setSttReadyNotice(null);
  const local = await trySendTurnLocally(blob);
  if (!stillCurrentAgent()) return;
  // "local": the whole turn stayed on-device. "refused": either the
  // server-side EDGE_ONLY policy, or the client-side edge.fallbackPolicy
  // preference ("never", or "ask" cancelled/timed out) - either way
  // already surfaced via edgeFallbackNotice, NOTHING may reach the server
  // for it.
  if (local.outcome === "local" || local.outcome === "refused") return;
  if (local.outcome === "server-text") {
    // Local STT succeeded: hosted finishes from the scrubbed transcript,
    // the raw audio never leaves this device (finding E-2).
    await sendHostedTextTurn(local.transcript);
    return;
  }
  // 2026-09-19 - the speech-to-text sidecar was scaling to zero in
  // production, leaving transcription dead for minutes after every idle
  // spell (consumers have no Sarvam key for the server chain to fall back
  // to). When a turn in this call ALREADY failed at the server's
  // transcription (within the last few minutes) and in-browser Whisper is
  // installed, don't upload audio just to collect the same error:
  // transcribe here and finish over the text chat endpoint. The health
  // probe alone never triggers this - it only sees the sidecar, and a
  // tenant with a Sarvam key transcribes fine while the sidecar sleeps.
  // English speakers only (whisper-tiny.en). Never downloads; when Whisper
  // can't make out the recording, the normal server upload below still runs.
  const localSttTried = local.outcome === "server-audio" && local.localSttTried === true;
  const likelyEnglish = speechLikelyEnglish();
  if (
    !localSttTried &&
    shouldTranscribeOnDeviceFirst({
      edgeOn: edgeEnabled(),
      localSttInstalled: isSttModelCached(),
      serverSttFailedThisCall: serverSttFailureIsLive(serverSttFailedAtRef.current, Date.now()),
      speechLikelyEnglish: likelyEnglish,
    })
  ) {
    if (await sendTurnViaLocalStt(blob)) return;
    if (!stillCurrentAgent()) return;
  }
  // Set by either failure route below (a thrown non-2xx, or a server
  // "error" SSE event) and acted on once, after `finally`.
  let transcriptReceived = false;
  let turnFailure: { message: string; status: number | null } | null = null;
  setPipelineBusy(true);
  setLastTranscript(null);
  setLastReply("");
  setAudioUnavailableNotice(null);
  // Part 5(a) trigger 2 (authoritative): whatever trySendTurnLocally left
  // callStage at (it may have fallen through from "transcribing" or
  // "understanding"), the real server pipeline starts fresh from here.
  setCallStage("transcribing");

  // Part 5(a): per-turn local state, not React state - sendTurn is called
  // fresh once per utterance, so plain closures scoped to this invocation
  // are simpler and safer than refs that would need resetting per turn.
  let generatingStarted = false;
  let doneReceived = false;
  let reasoningTimer: ReturnType<typeof setTimeout> | null = null;
  const clearReasoningTimer = () => {
    if (reasoningTimer) {
      clearTimeout(reasoningTimer);
      reasoningTimer = null;
    }
  };
  // Part 5(a) required addition: "Completed" is gated on BOTH the SSE
  // stream ending (onDone) AND the audio-chunk queue actually finishing
  // playback (isEmpty()) - onDone alone means "all bytes were sent", not
  // "the agent has finished audibly speaking" (caveat 3).
  const maybeComplete = () => {
    if (doneReceived && audioChunkQueue.isEmpty()) {
      setCallStage("completed");
    }
  };
  const audioChunkQueue = makeAudioChunkQueue({
    onSpeakStart: () => {
      // Part 5(a) trigger 5 / caveat 2: Speaking visually supersedes
      // Generating the instant playback actually starts, even though
      // reply-delta events may keep arriving underneath (interleaved, not
      // phased, per VoiceCallController#turn's own javadoc).
      clearReasoningTimer();
      setCallStage("speaking");
    },
    onIdle: maybeComplete,
    onAudioUnavailable: () => setAudioUnavailableNotice("Reply is text-only — audio unavailable."),
  });

  try {
    console.log(`[Voice] Sending turn for agent ${agent.id}, blob size: ${blob.size} bytes`);
    await api.sendVoiceTurnStream(agent.id, blob, sessionId, {
      onTranscript: (payload) => {
        if (!stillCurrentAgent()) return; // fix #1 - agent switched mid-flight
        console.log(`[Voice] Transcript: "${payload.transcript?.substring(0, 50)}..."`);
        transcriptReceived = true;
        // The server transcribed fine - whatever failed earlier is over.
        serverSttFailedAtRef.current = null;
        setServerSttFailedAt(null);
        pendingSttRef.current = null;
        setLastTranscript(payload.transcript);
        // Part 5(a) trigger 3: transcript received -> Understanding.
        setCallStage("understanding");
        // Caveat 1: the backend has no signal between "transcript
        // received" and "first reply token" today - Understanding and
        // Reasoning are one observable window. This is a COSMETIC-ONLY
        // client-side label flip after 800ms with no token yet, carries no
        // telemetry meaning, and must never be read as a real backend
        // signal (contrast with the LiveKit path's DataReceived listener
        // above, where "reasoning" IS a genuine backend-published stage).
        clearReasoningTimer();
        reasoningTimer = setTimeout(() => {
          if (!stillCurrentAgent()) return;
          setCallStage((prev) => (prev === "understanding" ? "reasoning" : prev));
        }, 800);
      },
      onReplyDelta: (text) => {
        if (!stillCurrentAgent()) return; // fix #1 - agent switched mid-flight
        if (!generatingStarted) {
          generatingStarted = true;
          clearReasoningTimer();
          // Part 5(a) trigger 4: first reply-delta -> Generating.
          setCallStage("generating");
        }
        setLastReply((prev: string | null) => (prev || "") + text);
      },
      onAudioChunk: (chunk) => {
        // fix #1 - never queue a since-abandoned agent's reply audio for
        // playback into whatever's currently on screen.
        if (!stillCurrentAgent()) return;
        audioChunkQueue.push(chunk);
      },
      onDone: (payload) => {
        if (!stillCurrentAgent()) return; // fix #1 - agent switched mid-flight
        console.log(`[Voice] Turn completed: reply="${payload.replyText?.substring(0, 50)}..."`);
        // Authoritative full text, in case any reply-delta events were
        // missed - reconciles what progressive accumulation built up.
        setLastReply(payload.replyText);
        if (payload.sessionId) onSessionId?.(payload.sessionId);
        doneReceived = true;
        maybeComplete();
      },
      onError: (message) => {
        if (!stillCurrentAgent()) return; // fix #1 - agent switched mid-flight
        console.error(`[Voice] Turn stream error:`, message);
        turnFailure = { message: message || "", status: null };
        setPipelineError(message || "That turn failed.");
        // Part 5(a) trigger 7: post-transcript failure -> Failed.
        clearReasoningTimer();
        setCallStage("failed");
      },
    });
  } catch (err) {
    console.error(`[Voice] Turn failed:`, err);
    if (!stillCurrentAgent()) return; // fix #1 - agent switched mid-flight
    turnFailure = { message: errorMessage(err, ""), status: errorStatus(err) ?? null };
    // Provide more helpful error messages based on the error
    let message = errorMessage(err, "That turn failed.");
    if (message.includes("transcribe")) {
      message = "Could not understand your audio. Please speak clearly and try again.";
    } else if (message.includes("empty")) {
      message = "No speech detected. Please make sure your microphone is working.";
    } else if (message.includes("unreachable")) {
      message = "The service is temporarily unavailable. Please try again in a moment.";
    }
    setPipelineError(message);
    // Part 5(a) trigger 8: pre-transcript failure (network error, or the
    // sync STT throw surfacing as a non-2xx before the stream even opens)
    // -> Failed, stage-attributed Transcribing.
    clearReasoningTimer();
    setCallStage("failed");
  } finally {
    setPipelineBusy(false);
  }

  // ---- Recovery: the SERVER'S transcription step failed (2026-09-19) ----
  // Before this, the person was left with the server's admin-facing "No
  // speech-to-text provider is configured for this agent. Add a Sarvam
  // credential..." and no way forward. isServerSttFailure is deliberately
  // narrow (never 401/402/403/429 - see its comment).
  const failure = turnFailure as { message: string; status: number | null } | null;
  if (!failure || !stillCurrentAgent()) return;
  if (!isServerSttFailure({ ...failure, transcriptReceived })) return;
  // Read through the ref: `systemHealth` in this closure is the snapshot
  // from the render the recording started in.
  const health = systemHealthRef.current;
  const outage = isServerSttOutage(failure) || (health.checked && !health.sidecarAvailable);
  if (outage) {
    serverSttFailedAtRef.current = Date.now();
    setServerSttFailedAt(serverSttFailedAtRef.current);
    health.refresh();
  }
  if (
    !localSttTried &&
    canRetryTurnOnDevice({
      edgeOn: edgeEnabled(),
      localSttInstalled: isSttModelCached(),
      speechLikelyEnglish: likelyEnglish,
    })
  ) {
    // Whisper is installed: redo THIS utterance here. The error the failed
    // server attempt left on screen is cleared by sendTurnViaLocalStt.
    const failedMessage = pipelineErrorForFailedStt(failure.message);
    const recovered = await sendTurnViaLocalStt(blob);
    if (!recovered && stillCurrentAgent()) {
      setPipelineError(failedMessage);
      setCallStage("failed");
    }
    return;
  }
  if (outage) {
    // Not installed (or not installable here): park the utterance so the
    // preflight's Download action can finish THIS turn, and say what
    // happened in the customer's terms - the preflight line right under
    // it carries the one next step.
    // On-device speech-to-text is English-only, so the recording is only
    // parked (and the download only offered) for a likely English speaker.
    let canGoOnDevice = false;
    try {
      canGoOnDevice = likelyEnglish && !isSttModelCached() && (await edgeSttAvailable());
    } catch {
      canGoOnDevice = false;
    }
    if (!stillCurrentAgent()) return;
    if (canGoOnDevice) {
      pendingSttRef.current = { blob, at: Date.now() };
      setPipelineError(
        "The platform could not turn that recording into text just now. Your recording is kept on this page for the next two minutes: if the download offered above finishes in that time, it is transcribed here and sent. Holding to talk again, or typing your message, discards it."
      );
    } else {
      setPipelineError(
        "The platform could not turn that recording into text just now. Type your message in the chat, or hold to talk again shortly."
      );
    }
  }
}
