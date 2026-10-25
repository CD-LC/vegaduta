"use client";

import { useEffect, useRef, useState } from "react";
import { Track, type DisconnectReason, type Room } from "livekit-client";
import { ChevronDown, ChevronUp } from "lucide-react";
import { cn } from "../../lib/utils";
import { useLocale, useTranslations } from "../../lib/i18n";
import { edgeEnabled } from "../../lib/edge/capabilities";
import { STT_MODEL_SIZE_BYTES } from "../../lib/edge/audio/whisper";
import { TTS_MODEL_SIZE_BYTES } from "../../lib/edge/audio/kokoro";
import { stopAllSpeech, webSpeechPresent } from "../../lib/voice/voicePlayers";
import {
  SERVER_STT_RETRY_AFTER_MS,
  evaluateCallPreflight,
  serverSttFailureIsLive,
} from "../../lib/voice/callPreflight";
import { useSystemHealth } from "../SystemHealthProvider";
import CallLivePane from "./call/CallLivePane";
import CallSetupPane from "./call/CallSetupPane";
import CallSummaryPane from "./call/CallSummaryPane";
import { useEdgeSight } from "./call/useEdgeSight";
import { useReconnectCeiling } from "./call/useReconnectCeiling";
import { useCallTranscript } from "./call/useCallTranscript";
import { useCallCapabilities } from "./call/useCallCapabilities";
import { useVoiceDeviceReadiness } from "./call/useVoiceDeviceReadiness";
import { useCallMachine } from "./call/useCallMachine";
import { useVoiceModelDownloads } from "./call/useVoiceModelDownloads";
import { speechLikelyEnglish as speechLikelyEnglishFor } from "./call/voiceHelpers";
import { useMicTest } from "./CallPanel/useMicTest";
import {
  disconnectMessage as disconnectMessageFor,
  startFailureMessage as startFailureMessageFor,
} from "./CallPanel/callErrors";
import type { CallStage } from "./CallPanel/callStage";
import { ASK_FALLBACK_TIMEOUT_MS } from "./CallPanel/constants";
import { playBrowserTTS } from "./CallPanel/browserTts";
import { useReplyPlayback } from "./call/useReplyPlayback";
import { useCallVoiceover } from "./call/useCallVoiceover";
import VoiceoverControls from "./CallPanel/VoiceoverControls";
import type { CallCtx, CallPanelAgent } from "./call/callContext";
import {
  startCallImpl,
  toggleCameraImpl,
  toggleScreenShareImpl,
  endCallImpl,
  toggleMuteImpl,
  toggleAgentAudioImpl,
} from "./call/liveCall";
import { startPushToTalkImpl, stopPushToTalkImpl } from "./call/pushToTalk";
import {
  primeVoiceOutputFromGestureImpl,
  speakFragmentImpl,
  kokoroWouldSpeakImpl,
  speakWholeReplyImpl,
  makeAudioChunkQueueImpl,
} from "./call/voiceActions";
import { sendTurnViaLocalSttImpl, trySendTurnLocallyImpl, sendHostedTextTurnImpl } from "./call/localTurn";
import { sendTurnImpl } from "./call/serverTurn";

// playBrowserTTS lives in CallPanel/browserTts.ts; re-exported so the public
// import path of this module is unchanged.
export { playBrowserTTS };

/**
 * Self-hosted in-app voice calling (Phase 1 of the self-reliance initiative -
 * LiveKit, self-hosted, zero PSTN/Twilio). Two independent pieces living in
 * one panel, deliberately not merged into one flow:
 *
 * 1) The LiveKit call itself (startCall/endCall/toggleMute below) - joins a
 *    real WebRTC room via livekit-client's Room API, captures the mic, and
 *    shows connection status. This is the "self-hosted voice/video calling
 *    infra actually works" half, and it's fully wired end to end.
 *
 * 2) Push-to-talk against the agent (startPushToTalk/sendTurn below) - a
 *    separate, much simpler MediaRecorder -> POST /api/voice/agents/{id}/turn
 *    pipeline, one upload per utterance but a STREAMED reply (transcript,
 *    then reply text, then per-sentence audio, then a final summary - see
 *    api.ts's sendVoiceTurnStream and VoiceCallController#turn's javadoc).
 *    This is NOT carried over the LiveKit room's own media tracks - see
 *    VoiceCallController's javadoc for exactly why (no Java-side LiveKit
 *    "Agents" media pipeline exists to subscribe to a published track,
 *    transcribe it, and publish synthesized audio back). Practically: being
 *    "in a call" proves the self-hosted WebRTC path is real (check the
 *    LiveKit server logs for the participant joining), while "hold to talk"
 *    is what actually gets a spoken question answered. Both can be used
 *    independently; holding to talk does not require an active LiveKit call
 *    at all.
 */
interface CallPanelProps {
  agent: CallPanelAgent | null | undefined;
  // The playground's current chat session (null before the first turn) -
  // threaded into every voice turn so it lands in the SAME AgentChatService
  // session as typed messages; onSessionId reports the id the backend minted
  // on a session's first turn back up to the playground.
  sessionId: string | null;
  onSessionId?: (sessionId: string) => void;
}

export default function CallPanel({ agent, sessionId, onSessionId }: CallPanelProps) {
  const t = useTranslations("callPanel");
  const [status, setStatus] = useState("idle"); // idle | connecting | connected | error
  // Collapsed by default - the call UI earned its space only when the person
  // asks for it (the zone above the chat already cost them a 30/70 split).
  const [callPanelOpen, setCallPanelOpen] = useState(false);
  const [muted, setMuted] = useState(false);
  // Live vision (Program S1 seam): whether this call is publishing the local
  // camera. Only ever true for a call started via "Call + camera" (token mode
  // "sight") or toggled on mid-call - the voice-only path never touches it.
  const [cameraOn, setCameraOn] = useState(false);
  // Distinct from `muted` above (that's the LOCAL mic). This one silences
  // the agent's incoming TTS playback - a "make it stop talking" control
  // with nothing to toggle before this fix, since no remote audio element
  // even existed yet (see remoteAudioContainerRef's comment).
  const [agentAudioMuted, setAgentAudioMuted] = useState(false);
  const [callError, setCallError] = useState(null);
  // Non-fatal, in-call notices (camera fell back to voice-only, a device
  // error mid-call) - deliberately separate from callError, which reads as
  // "the call failed"; these say "the call is fine, but you should know...".
  const [callNotice, setCallNotice] = useState(null);
  // True while livekit-client is mid-resume/rejoin (ConnectionStateChanged
  // Reconnecting/SignalReconnecting) - the "call + camera not working" round
  // of staging debugging showed the client sitting in exactly this state
  // with no visible sign at all.
  const [reconnecting, setReconnecting] = useState(false);
  // The room connected but no remote participant (the voice-agent worker)
  // showed up within AGENT_JOIN_TIMEOUT_MS. The call STAYS connected - this
  // is a hint that the server-side worker is down, not a client failure.
  const [agentMissing, setAgentMissing] = useState(false);
  const [recording, setRecording] = useState(false);
  const [pipelineBusy, setPipelineBusy] = useState(false);
  const [pipelineError, setPipelineError] = useState(null);
  // Running transcript + last turn (call/useCallTranscript.ts).
  const {
    lastTranscript,
    setLastTranscript,
    lastReply,
    setLastReply,
    callTranscript,
    setCallTranscript,
    capturedTurnKeyRef,
    transcriptScrollRef,
    resetTranscript,
  } = useCallTranscript(pipelineBusy);
  // Phase state machine (call/callMachine.ts): derived from the connection status.
  const { phase, markDialing, markConnected, markHangingUp, markEnded } = useCallMachine(status, callTranscript.length);
  // Part 5(a)'s shared stage indicator - replaces pipelineBusy's old single
  // binary "Thinking…" label. pipelineBusy itself is unchanged and still
  // gates the mic/test-mic buttons - this is additive, not a replacement.
  const [callStage, setCallStage] = useState<CallStage>("idle");
  // Part 5(c) fix #2 (silent TTS failure): distinct from pipelineError
  // (which reads as "the whole turn failed") - this says "the turn worked,
  // only audio didn't," for when neither server audio nor the browser's own
  // speechSynthesis could speak the reply.
  const [audioUnavailableNotice, setAudioUnavailableNotice] = useState<string | null>(null);
  // Mirrors AgentPlayground's "Platform (edge skipped)" pill for this voice
  // surface - every silent decline path in trySendTurnLocally below (STT
  // model not cached, STT declined, local reasoning declined, or the
  // attempt throwing) used to be console.warn-only, leaving someone who has
  // on-device mode ON with zero explanation for why a turn still went to the
  // server. Reset to null at the top of every new attempt (and implicitly on
  // a fully-local success, since that reset already ran before the success
  // path returns) so a stale notice from an earlier failed turn never
  // lingers through a later one.
  const [edgeFallbackNotice, setEdgeFallbackNotice] = useState<string | null>(null);
  // Live user report (2026-08-07, "for camera and voice edge computing is
  // the first choice, it should work" + "highlight edge model used for
  // voice and video"): push-to-talk already tries on-device first (STT ->
  // routeTurn -> TTS, all below), but a fully successful on-device turn
  // previously said NOTHING about it - only failures/fallbacks got a
  // notice. This is the positive counterpart: which on-device component
  // actually answered THIS turn, including an honest downgrade caveat when
  // routeTurn's tier-1 (most-capable) attempt failed and tier 0 (Chrome
  // built-in) answered instead (RouteTurnResult.downgradedFrom). Reset at
  // the top of every new attempt, same lifecycle as edgeFallbackNotice.
  const [edgeHandledSummary, setEdgeHandledSummary] = useState<string | null>(null);
  // Fallback policy / on-device opt-in / vision gate (call/useCallCapabilities.ts).
  const { fallbackPolicy, setFallbackPolicyState, edgeOptInActive, canSee } = useCallCapabilities(agent);
  // "ask" policy support: the message for a pending inline confirm/cancel
  // prompt (null when nothing is pending), and the resolver for the Promise
  // askToFallback below is currently awaiting. The resolver lives in a ref,
  // not state, so a later button click (or the timeout) can settle that
  // Promise without re-running any async function.
  const [pendingFallbackAsk, setPendingFallbackAsk] = useState<string | null>(null);
  const fallbackAskResolverRef = useRef<((confirmed: boolean) => void) | null>(null);

  /** Shows the inline confirm/cancel prompt and resolves once the person
   * answers (or ASK_FALLBACK_TIMEOUT_MS elapses, which resolves to `false` -
   * silence reads as Cancel, never as consent). Only ever called from
   * resolveAutomaticFallback below when fallbackPolicy === "ask". */
  function askToFallback(message: string): Promise<boolean> {
    return new Promise((resolve) => {
      fallbackAskResolverRef.current = (confirmed: boolean) => {
        fallbackAskResolverRef.current = null;
        setPendingFallbackAsk(null);
        resolve(confirmed);
      };
      setPendingFallbackAsk(message);
      setTimeout(() => {
        fallbackAskResolverRef.current?.(false);
      }, ASK_FALLBACK_TIMEOUT_MS);
    });
  }

  // Microphone diagnostics (see CallPanel/useMicTest.ts)
  const {
    testingMicrophone,
    micTestResult,
    setMicTestResult,
    audioLevel,
    showMicTest,
    setShowMicTest,
    testMicrophone,
  } = useMicTest(t);

  // ---- Call preflight + default voice (2026-09-19) -----------------------
  // Everything the preflight (lib/voice/callPreflight.ts) needs to know about
  // this device, gathered WITHOUT prompting for a permission or downloading
  // anything. useSystemHealth() is AppShell's shared 20s probe; outside that
  // provider (there is none on a bare mount) it answers its optimistic
  // default with checked:false, and the preflight then says nothing.
  const systemHealth = useSystemHealth();
  // The async turn closures (sendTurn runs for seconds) would otherwise read
  // the health snapshot from the render the recording STARTED in.
  const systemHealthRef = useRef(systemHealth);
  systemHealthRef.current = systemHealth;
  const uiLocale = useLocale();
  const {
    micState,
    recorderSupported,
    localSttSupported,
    localTtsSupported,
    localSttInstalled,
    localTtsInstalled,
    setMicState,
    setRecorderSupported,
    setLocalSttInstalled,
    setLocalTtsInstalled,
  } = useVoiceDeviceReadiness();
  // One quiet line after a Download finished with nothing to replay.
  const [sttReadyNotice, setSttReadyNotice] = useState<string | null>(null);
  // Wall-clock time of the last turn that failed at the SERVER'S
  // transcription step. Treated as live for SERVER_STT_RETRY_AFTER_MS, after
  // which the next turn tries the server again - a sidecar that was waking
  // up is back by then, and if it is not, that turn recovers on-device the
  // same way this one did.
  const [serverSttFailedAt, setServerSttFailedAt] = useState<number | null>(null);
  // Mirror for the async turn closures, which would otherwise read the value
  // from the render the recording STARTED in.
  const serverSttFailedAtRef = useRef<number | null>(null);
  // The utterance whose server transcription failed while in-browser Whisper
  // was not installed yet - kept so that "Download" -> done re-runs THIS turn
  // instead of making the person say it all again. It is dropped the moment
  // the conversation moves on (a new hold, a new turn, a server transcript, a
  // message typed in the chat, an agent switch) and it expires after
  // PARKED_UTTERANCE_MAX_AGE_MS - replaying it later would drop an old
  // message into the chat out of order, to an agent that may act on it twice.
  const pendingSttRef = useRef<{ blob: Blob; at: number } | null>(null);
  // Mirrors for startSttDownload, which finishes minutes after it was called.
  const recordingRef = useRef(false);
  const pipelineBusyRef = useRef(false);
  const callStartedRef = useRef(false);
  const audioUnlockedRef = useRef(false);
  // Bumped every time the person starts talking again (barge-in). Anything
  // still queued to be SPOKEN from an earlier reply checks it and stops.
  const voiceEpochRef = useRef(0);
  const unmountedRef = useRef(false);
  useEffect(() => {
    unmountedRef.current = false;
    return () => {
      unmountedRef.current = true;
    };
  }, []);
  recordingRef.current = recording;
  pipelineBusyRef.current = pipelineBusy;

  const { sttDownload, ttsDownload, serverVoiceMissing, noteServerVoiceMissing, handlePreflightAction } =
    useVoiceModelDownloads({
      unmountedRef,
      callStartedRef,
      pendingSttRef,
      recordingRef,
      pipelineBusyRef,
      serverSttFailedAtRef,
      setServerSttFailedAt,
      setLocalTtsInstalled,
      setLocalSttInstalled,
      setAudioUnavailableNotice,
      setPipelineError,
      setCallStage,
      setSttReadyNotice,
      setShowMicTest,
      refreshSystemHealth: () => systemHealth.refresh(),
      sendTurnViaLocalStt,
    });

  // In-browser Whisper is whisper-tiny.EN: only used or offered when the
  // browser language AND the UI locale both say English (see
  // likelyEnglishSpeaker - a heuristic, which is why the copy says "English
  // only" wherever it is mentioned).
  function speechLikelyEnglish(): boolean {
    return speechLikelyEnglishFor(uiLocale);
  }

  // The failure mark is only live for SERVER_STT_RETRY_AFTER_MS. Nothing else
  // re-renders an idle panel, so without this the preflight line would stay
  // on screen long after the mark stopped meaning anything.
  useEffect(() => {
    if (serverSttFailedAt === null) return undefined;
    const remaining = Math.max(0, serverSttFailedAt + SERVER_STT_RETRY_AFTER_MS - Date.now());
    const timer = setTimeout(() => {
      if (serverSttFailedAtRef.current === serverSttFailedAt) serverSttFailedAtRef.current = null;
      setServerSttFailedAt((prev) => (prev === serverSttFailedAt ? null : prev));
    }, remaining + 50);
    return () => clearTimeout(timer);
  }, [serverSttFailedAt]);

  // The playground dispatches this after a chat message was sent. If the
  // person typed their message instead (which the failed-turn copy tells them
  // they can), the parked recording must never be replayed on top of it.
  useEffect(() => {
    const onPlaygroundTurn = (event: Event) => {
      const detailAgentId = (event as CustomEvent)?.detail?.agentId;
      if (detailAgentId && detailAgentId !== activeAgentIdRef.current) return;
      pendingSttRef.current = null;
    };
    window.addEventListener("vd:playground-turn", onPlaygroundTurn);
    return () => window.removeEventListener("vd:playground-turn", onPlaygroundTurn);
  }, []);

  const roomRef = useRef<Room | null>(null);
  const mediaStreamRef = useRef<MediaStream | null>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const audioElRef = useRef<HTMLAudioElement | null>(null);
  // Mounts <audio>/<video> elements created by RemoteTrack.attach() for the
  // live LiveKit call (voice-agent's TTS reply) - a SEPARATE element from
  // audioElRef, which only ever plays the push-to-talk MP3 reply. Without
  // this, room.connect() still subscribes to the agent's published audio
  // track (that part of the WebRTC path is real) but nothing ever calls
  // .attach() on it, so the call is connected yet completely silent - the
  // exact "full silent, no errors anywhere" symptom that led here, traced
  // through voice-agent's TTS pipeline (confirmed synthesizing real audio
  // frames) all the way to this missing browser-side wiring.
  const remoteAudioContainerRef = useRef<HTMLDivElement | null>(null);
  // Local camera self-view for a sight call - the LocalVideoTrack is attached
  // here once the element exists (see the cameraOn effect below), the same
  // attach()-based wiring the remote audio container uses.
  const localVideoRef = useRef<HTMLVideoElement | null>(null);
  // room.on(TrackSubscribed) below is registered once per call and closes
  // over whatever this ref holds AT THAT MOMENT it reads it - a plain
  // `agentAudioMuted` state read from that closure would be permanently
  // stale (always "false", the value at connect time). The ref is mutated
  // synchronously by toggleAgentAudio so newly-attached elements (the agent
  // starting a new utterance after being muted) come in muted too.
  const agentAudioMutedRef = useRef(false);
  // Dedupe set for "call-transcript" data-channel lines (the live call's
  // retained text - see liveCall.ts's DataReceived handler).
  const liveTranscriptIdsRef = useRef<Set<string>>(new Set());
  // wall-clock start of the current hold, so stopPushToTalk can enforce
  // MIN_HOLD_MS below - see that constant's comment.
  const recordingStartedAtRef = useRef<number | null>(null);
  // The agent-join watchdog timer (see AGENT_JOIN_TIMEOUT_MS) - cleared the
  // moment any remote participant connects, and on every teardown path.
  const agentJoinTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Edge-sight and reconnect-ceiling machinery lives in call/ (hooks below).
  const {
    edgeSightActive,
    edgeSightSource,
    edgeSightModeRef,
    sightRoomRef,
    edgeStreamRef,
    startEdgeSight,
    stopEdgeSight,
  } = useEdgeSight({
    t,
    roomRef,
    localVideoRef,
    setCameraOn,
    setCallNotice,
  });
  const { armReconnectCeiling, disarmReconnectCeiling } = useReconnectCeiling({
    agentId: agent?.id,
    roomRef,
    endCall,
    setCallError,
    setStatus,
  });
  // Fix #1 (P0): the agent this panel is CURRENTLY showing. CallPanel is
  // reused across an agent switch (no key={agent.id} at the call site, same
  // reason AgentPlayground.tsx needs its own activeAgentIdRef), so a
  // push-to-talk turn started under the previous agent can still be mid-hold
  // or mid-flight when the switch happens. Set FIRST (before any state
  // reset) in the effect below so startPushToTalk/sendTurn/
  // trySendTurnLocally/sendHostedTextTurn can tell - by comparing the agent
  // id they captured when the turn began against this ref's CURRENT value -
  // that they now belong to an abandoned agent and must stop rather than
  // send/render into the newly-selected agent's panel.
  const activeAgentIdRef = useRef(agent?.id);
  // "Play this reply" per transcript line (call/useReplyPlayback.ts) - the
  // playground's per-turn audio control, keyed by transcript line index.
  // Declared after activeAgentIdRef since the getter reads it.
  const { replay, replayReply, stopReplay, skipReplay } = useReplyPlayback({
    getAgentId: () => activeAgentIdRef.current ?? agent?.id,
    audioElRef,
    voiceEpochRef,
    unmountedRef,
  });
  // Voice-over state (speaker toggle + per-agent live-call voice pick, both
  // persisted) - call/useCallVoiceover.ts.
  const { repliesMuted, repliesMutedRef, toggleRepliesMuted, callVoice, callVoiceRef, changeCallVoice } =
    useCallVoiceover({ agentId: agent?.id, voiceEpochRef, audioElRef, onSilence: stopReplay });

  useEffect(() => {
    activeAgentIdRef.current = agent?.id;
    // Fix #1 (P0): reset every per-turn UI state so nothing from the
    // previous agent's call/turn lingers into the new one - a stale
    // lastReply/callError/etc. left over from Agent A previously rendered
    // under Agent B's panel with nothing to explain where it came from.
    resetTranscript();
    setCallStage("idle");
    setCallError(null);
    setPipelineError(null);
    setEdgeFallbackNotice(null);
    setEdgeHandledSummary(null);
    setPendingFallbackAsk(null);
    fallbackAskResolverRef.current = null;
    // 2026-09-19: a parked utterance belongs to the agent it was spoken to.
    // serverVoiceMissing/serverSttFailedThisCall are about the tenant and the
    // platform, not the agent, so they survive a switch on purpose.
    pendingSttRef.current = null;
    setSttReadyNotice(null);
    liveTranscriptIdsRef.current.clear();
    stopReplay();
    // Captured now: React detaches these JSX refs before this cleanup runs on
    // unmount, so reading .current there would silence/clear nothing.
    const audioEl = audioElRef.current;
    const remoteAudioContainer = remoteAudioContainerRef.current;
    return () => {
      // Agent switch or unmount: the previous agent must stop TALKING, not
      // just stop queueing. Bumping the epoch aborts any voice chain still
      // preparing sound (a Kokoro synth in flight); stopAllSpeech silences
      // what is already audible.
      voiceEpochRef.current += 1;
      stopAllSpeech(audioEl);
      if (agentJoinTimerRef.current) clearTimeout(agentJoinTimerRef.current);
      disarmReconnectCeiling();
      void roomRef.current?.disconnect();
      roomRef.current = null;
      mediaStreamRef.current?.getTracks().forEach((t) => t.stop());
      if (remoteAudioContainer) remoteAudioContainer.innerHTML = "";
    };
  }, [agent?.id]);

  function disconnectMessage(reason: DisconnectReason | undefined) {
    return disconnectMessageFor(t, reason);
  }

  function startFailureMessage(err: unknown) {
    return startFailureMessageFor(t, err);
  }

  // Attaches/detaches the local camera track's self-view. Runs on cameraOn
  // changes rather than inline in startCall because the <video> element only
  // exists once React has rendered the cameraOn branch below.
  useEffect(() => {
    if (!cameraOn) return undefined;
    const el = localVideoRef.current;
    // Edge sight: the self-view shows the LOCAL (never-published) stream,
    // and doubles as edgeSightTick's frame source.
    if (edgeStreamRef.current) {
      if (el) {
        el.srcObject = edgeStreamRef.current;
        el.play?.().catch(() => {});
      }
      return () => {
        if (el) el.srcObject = null;
      };
    }
    const track = roomRef.current?.localParticipant?.getTrackPublication(Track.Source.Camera)?.videoTrack;
    if (el && track) track.attach(el);
    return () => {
      if (el && track) track.detach(el);
    };
  }, [cameraOn]);

  // The action modules under call/ read everything through this per-render bag;
  // the wrappers below are hoisted so hooks above can be handed them early.
  const ctx: CallCtx = {
    agent,
    sessionId,
    onSessionId,
    t,
    status,
    muted,
    cameraOn,
    agentAudioMuted,
    recording,
    pipelineBusy,
    setStatus,
    setMuted,
    setCameraOn,
    setAgentAudioMuted,
    setCallError,
    setCallNotice,
    setReconnecting,
    setAgentMissing,
    setRecording,
    setPipelineBusy,
    setPipelineError,
    setLastTranscript,
    setLastReply,
    setCallTranscript,
    setCallStage,
    setAudioUnavailableNotice,
    setEdgeFallbackNotice,
    setEdgeHandledSummary,
    setPendingFallbackAsk,
    setSttReadyNotice,
    setServerSttFailedAt,
    setMicState,
    setRecorderSupported,
    capturedTurnKeyRef,
    fallbackAskResolverRef,
    serverSttFailedAtRef,
    pendingSttRef,
    systemHealthRef,
    activeAgentIdRef,
    unmountedRef,
    voiceEpochRef,
    audioUnlockedRef,
    callStartedRef,
    recordingStartedAtRef,
    roomRef,
    mediaStreamRef,
    mediaRecorderRef,
    chunksRef,
    audioElRef,
    remoteAudioContainerRef,
    agentAudioMutedRef,
    liveTranscriptIdsRef,
    repliesMutedRef,
    callVoiceRef,
    agentJoinTimerRef,
    edgeSightModeRef,
    sightRoomRef,
    edgeSightSource,
    startEdgeSight,
    stopEdgeSight,
    armReconnectCeiling,
    disarmReconnectCeiling,
    markDialing,
    markConnected,
    markHangingUp,
    markEnded,
    noteServerVoiceMissing,
    askToFallback,
    speechLikelyEnglish,
    disconnectMessage,
    startFailureMessage,
    primeVoiceOutputFromGesture,
    speakFragment,
    kokoroWouldSpeak,
    speakWholeReply,
    makeAudioChunkQueue,
    sendTurnViaLocalStt,
    trySendTurnLocally,
    sendHostedTextTurn,
    sendTurn,
    endCall,
  };
  function startCall(withCamera = false) {
    return startCallImpl(ctx, withCamera);
  }
  function toggleCamera() {
    return toggleCameraImpl(ctx);
  }
  function toggleScreenShare() {
    return toggleScreenShareImpl(ctx);
  }
  function endCall() {
    return endCallImpl(ctx);
  }
  function toggleMute() {
    return toggleMuteImpl(ctx);
  }
  function toggleAgentAudio() {
    return toggleAgentAudioImpl(ctx);
  }
  function startPushToTalk() {
    return startPushToTalkImpl(ctx);
  }
  function stopPushToTalk() {
    return stopPushToTalkImpl(ctx);
  }
  function primeVoiceOutputFromGesture() {
    return primeVoiceOutputFromGestureImpl(ctx);
  }
  function speakFragment(...args: Parameters<CallCtx["speakFragment"]>) {
    return speakFragmentImpl(ctx, ...args);
  }
  function kokoroWouldSpeak(text: string | null | undefined) {
    return kokoroWouldSpeakImpl(ctx, text);
  }
  function speakWholeReply(...args: Parameters<CallCtx["speakWholeReply"]>) {
    return speakWholeReplyImpl(ctx, ...args);
  }
  function makeAudioChunkQueue(callbacks: Parameters<CallCtx["makeAudioChunkQueue"]>[0] = {}) {
    return makeAudioChunkQueueImpl(ctx, callbacks);
  }
  function sendTurnViaLocalStt(blob: Blob) {
    return sendTurnViaLocalSttImpl(ctx, blob);
  }
  function trySendTurnLocally(blob: Blob) {
    return trySendTurnLocallyImpl(ctx, blob);
  }
  function sendHostedTextTurn(transcript: string, options: { scrub?: boolean } = {}) {
    return sendHostedTextTurnImpl(ctx, transcript, options);
  }
  function sendTurn(blob: Blob) {
    return sendTurnImpl(ctx, blob);
  }

  if (!agent) return null;

  const preflightIssues = evaluateCallPreflight({
    mic: micState,
    mediaRecorderSupported: recorderSupported,
    serverSttChecked: systemHealth.checked,
    serverSttAvailable: systemHealth.sidecarAvailable,
    serverSttFailedThisCall: serverSttFailureIsLive(serverSttFailedAt, Date.now()),
    agentHasOwnSttProvider: !!(agent as any).voiceModelProvider,
    speechLikelyEnglish: speechLikelyEnglish(),
    edgeOn: edgeEnabled(),
    localSttSupported,
    localSttInstalled,
    localTtsSupported,
    localTtsInstalled,
    serverVoiceMissing,
    webSpeechPresent: webSpeechPresent(),
    sttDownload,
    ttsDownload,
    sttSizeBytes: STT_MODEL_SIZE_BYTES,
    ttsSizeBytes: TTS_MODEL_SIZE_BYTES,
  });

  // Collapse hides the body via a class, never unmounts: the hidden branch
  // keeps the LiveKit room, MediaRecorder and reply-audio chain alive, which
  // is exactly what a live call needs when the person wants more chat space.
  const statusLabel =
    status === "connected"
      ? reconnecting
        ? t("reconnecting")
        : t("statusInCall")
      : status === "connecting"
        ? t("statusConnecting")
        : status === "error"
          ? t("statusFailed")
          : t("statusIdle");

  return (
    <div className="flex flex-col gap-2 border-t border-border px-5 py-3" data-call-phase={phase}>
      <button
        type="button"
        className="flex w-full items-center gap-2 text-left text-xs font-medium text-muted-foreground hover:text-foreground"
        aria-expanded={callPanelOpen}
        data-testid="call-panel-toggle"
        onClick={() => setCallPanelOpen((o) => !o)}
      >
        <span
          className={cn("state-dot", (status === "connected" || status === "connecting") && "state-dot-live")}
          style={
            {
              ["--state" as any]:
                status === "connected"
                  ? reconnecting
                    ? "var(--state-warning)"
                    : "var(--state-success)"
                  : status === "connecting"
                    ? "var(--state-warning)"
                    : status === "error"
                      ? "var(--state-error)"
                      : "var(--state-idle)",
            } as any
          }
          aria-hidden="true"
        />
        {t("sectionTitle")}
        <span className="text-muted-foreground/70">· {statusLabel}</span>
        {callPanelOpen ? (
          <ChevronUp className="ml-auto size-3.5" aria-hidden="true" />
        ) : (
          <ChevronDown className="ml-auto size-3.5" aria-hidden="true" />
        )}
      </button>
      <div className={callPanelOpen ? "flex flex-col gap-2" : "hidden"} data-testid="call-panel-body">
        <CallLivePane
          controls={{
            t,
            status,
            reconnecting,
            muted,
            cameraOn,
            agentAudioMuted,
            canSee,
            screenSharing: edgeSightSource === "screen",
            toggleMute: () => void toggleMute(),
            toggleCamera: () => void toggleCamera(),
            toggleScreenShare: () => void toggleScreenShare(),
            toggleAgentAudio,
            endCall: () => void endCall(),
            startCall: (withCamera?: boolean) => void startCall(withCamera),
          }}
          notices={{ t, status, agentMissing, callNotice, callStage, cameraOn, edgeSightActive, localVideoRef }}
          pushToTalk={{
            recording,
            pipelineBusy,
            pendingFallbackAsk,
            showMicTest,
            testingMicrophone,
            callStage,
            startPushToTalk: () => void startPushToTalk(),
            stopPushToTalk,
            setShowMicTest,
          }}
        />

        <CallSetupPane
          preflight={{ issues: preflightIssues, onAction: handlePreflightAction, disabled: recording }}
          sttReadyNotice={!recording && !pipelineBusy ? sttReadyNotice : null}
          micTest={{
            showMicTest,
            micTestResult,
            testingMicrophone,
            audioLevel,
            testMicrophone: () => void testMicrophone(),
            setShowMicTest,
            setMicTestResult,
          }}
          edgeNotices={{ edgeHandledSummary, audioUnavailableNotice, edgeFallbackNotice }}
          fallbackAsk={{ pendingFallbackAsk, fallbackAskResolverRef }}
          policyRow={{ fallbackPolicy, edgeOptInActive, setFallbackPolicyState }}
        />

        {/* Voice-over controls (reply voice pick + speaker mute + call voice +
          agent TTS provider) - the audio-controls row; see the component's
          own header for what each row does and when each is rendered. */}
        <VoiceoverControls
          agent={agent}
          repliesMuted={repliesMuted}
          onToggleRepliesMuted={toggleRepliesMuted}
          callVoice={callVoice}
          onCallVoiceChange={changeCallVoice}
        />

        <CallSummaryPane
          transcript={{
            callTranscript,
            pipelineBusy,
            lastTranscript,
            lastReply,
            transcriptScrollRef,
            replay: {
              state: replay,
              onReplay: (i, text) => void replayReply(i, text),
              onStop: stopReplay,
              onSkip: skipReplay,
            },
          }}
          error={{ callError, pipelineError, setShowMicTest }}
        />
      </div>
      <audio ref={audioElRef} className="hidden" />
      <div ref={remoteAudioContainerRef} className="hidden" />
    </div>
  );
}
