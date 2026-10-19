"use client";

// Mic + video for the public agent-share page (/agent/[shareId]).
//
// Two independent surfaces, both anonymous (no Keycloak session, shareId is
// the only credential - see api.ts's header comment):
//   1. Push-to-talk: hold the mic button, speak, release - one request/
//      response turn (POST .../voice/turn). No LiveKit involved at all.
//   2. Live Call: a LiveKit room (POST .../voice/token mints the join
//      token), continuous listening, optional camera. The camera toggle is
//      gated on supportsImageInput.
//
// Both are ALWAYS shown, matching the authenticated console's CallPanel:
// voice goes speech-to-text -> LLM -> text-to-speech server-side, so the
// agent's model never sees raw audio and its supportsAudioInput flag is
// irrelevant here (gating on it hid these buttons for text-model agents).
//
// Deliberately NOT a generalization of CallPanel.tsx (the authenticated
// console's call surface) - this page has no agent.id-scoped edge/vision
// policy to check, no Keycloak token to forward, and no on-device ROUTING
// (speech-to-text and the model always run on the server here). A smaller,
// dedicated component is more honest than threading an `isShare` flag
// through that 2000+ line file.
//
// Voice OUTPUT does share code with CallPanel since 2026-09-19 (lib/voice):
// a reply sentence that arrives without server audio used to be skipped in
// silence. It is now spoken by the on-device voice when that is ALREADY on
// this device and verifiably still in the browser cache, else by the
// browser's built-in voice, and the panel says so when nothing can speak.
// This page NEVER starts a model download by itself: an anonymous visitor
// only ever gets a one-tap offer with the size stated (VOICE_OFFER below).
import { useEffect, useRef, useState } from "react";
import { Room, RoomEvent, Track } from "livekit-client";
import { Loader2, Mic, Phone, PhoneOff, Video, VideoOff, Volume2, VolumeX } from "lucide-react";
import { Button } from "../../components/ui/button";
import { cn } from "../../lib/utils";
import { getShareVoiceToken, sendSharedVoiceTurn } from "./api";
import { sendShareTurn, speakShareLine } from "./shareTurn";
import CallPreflight from "../../components/agents/call/CallPreflight";
import ShareCallLog from "./ShareCallLog";
import ShareVoiceoverRow from "./ShareVoiceoverRow";
import { edgeEnabled } from "../../lib/edge/capabilities";
import { TTS_MODEL_SIZE_BYTES, edgeTtsAvailable, ensureTtsModel, isTtsModelCached } from "../../lib/edge/audio/kokoro";
import { stopAllSpeech, unlockAudioElement, unlockWebSpeech, webSpeechPresent } from "../../lib/voice/voicePlayers";
import {
  displaySafeDownloadError,
  evaluateCallPreflight,
  type DownloadState,
  type PreflightActionKind,
} from "../../lib/voice/callPreflight";
import { progressPercent } from "../../lib/voice/callEnvironment";

// Same floors CallPanel.tsx uses, same reasoning: a quick tap-and-release
// can land before the encoder has buffered a real frame, and a bare
// container with no audio muxed in is ~100-150 bytes.
const MIN_HOLD_MS = 350;
const MIN_VIABLE_AUDIO_BYTES = 1000;

type CallStage = "idle" | "listening" | "thinking" | "speaking" | "error";

function stageLabel(stage: CallStage): string | null {
  switch (stage) {
    case "listening":
      return "Listening…";
    case "thinking":
      return "Thinking…";
    case "speaking":
      return "Speaking…";
    default:
      return null;
  }
}

function pickRecorderMimeType(): string {
  if (typeof MediaRecorder === "undefined") return "";
  if (MediaRecorder.isTypeSupported("audio/wav")) return "audio/wav";
  if (MediaRecorder.isTypeSupported("audio/webm")) return "audio/webm";
  return "";
}

export default function ShareCallPanel({
  shareId,
  agentName,
  supportsImageInput,
}: {
  shareId: string;
  agentName: string;
  supportsImageInput: boolean;
}) {
  const [mode, setMode] = useState<"idle" | "push-to-talk" | "call">("idle");
  const [recording, setRecording] = useState(false);
  const [stage, setStage] = useState<CallStage>("idle");
  const [transcript, setTranscript] = useState<string | null>(null);
  const [reply, setReply] = useState<string>("");
  const [error, setError] = useState<string | null>(null);
  // The running record of completed exchanges - retained for the whole visit
  // (push-to-talk turns AND live-call turns, which arrive over the room's
  // "call-transcript" data topic), same as the console panel's transcript.
  const [callLog, setCallLog] = useState<{ role: "user" | "agent"; text: string }[]>([]);

  const [callStatus, setCallStatus] = useState<"idle" | "connecting" | "connected" | "error">("idle");
  const [cameraOn, setCameraOn] = useState(false);
  // Mutes the live call's remote audio track (attached elements + future
  // ones) - mirrors the console panel's agentAudioMuted.
  const [agentAudioMuted, setAgentAudioMuted] = useState(false);
  const agentAudioMutedRef = useRef(false);
  // Dedupe set for "call-transcript" data-channel lines (each carries a
  // unique id the worker mints; cleared per call).
  const liveTranscriptIdsRef = useRef<Set<string>>(new Set());
  // Speaker toggle for spoken replies (push-to-talk + replayed lines) -
  // persisted device-level, same key the console panel uses. The voice-over
  // picker itself lives in ShareVoiceoverRow (its state is self-contained -
  // the runners read the persisted pref at play time).
  const [repliesMuted, setRepliesMuted] = useState(false);
  const repliesMutedRef = useRef(false);
  // "Play this reply" per retained agent line - local voices only (the share
  // API has no /speak leg; replay never fetches a model).
  const [replayIndex, setReplayIndex] = useState<number | null>(null);
  const replayTokenRef = useRef(0);

  const mediaStreamRef = useRef<MediaStream | null>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const recordingStartedAtRef = useRef<number | null>(null);
  const sessionIdRef = useRef<string | null>(null);
  const audioElRef = useRef<HTMLAudioElement | null>(null);

  // ---- Voice output (lib/voice) ---------------------------------------
  // Bumped by a new hold (barge-in) and on unmount: whatever is still queued
  // or still being prepared from an earlier reply stops.
  const voiceEpochRef = useRef(0);
  const unmountedRef = useRef(false);
  const audioUnlockedRef = useRef(false);
  const ttsDownloadingRef = useRef(false);
  const [voiceNotice, setVoiceNotice] = useState<string | null>(null);
  // VOICE_OFFER: a reply the server marked voiceless, in a script the
  // on-device voice can speak. Only ever leads to an OFFER (see the header).
  const [serverVoiceMissing, setServerVoiceMissing] = useState(false);
  const [localTtsSupported, setLocalTtsSupported] = useState(false);
  const [localTtsInstalled, setLocalTtsInstalled] = useState(false);
  const [ttsDownload, setTtsDownload] = useState<DownloadState>({ status: "idle" });

  useEffect(() => {
    let cancelled = false;
    try {
      setLocalTtsInstalled(edgeEnabled() && isTtsModelCached());
    } catch {
      // Stays false.
    }
    void edgeTtsAvailable().then(
      (ok) => !cancelled && setLocalTtsSupported(ok),
      () => {}
    );
    return () => {
      cancelled = true;
    };
  }, []);

  const roomRef = useRef<Room | null>(null);
  const remoteAudioContainerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    unmountedRef.current = false;
    // Captured now: React detaches the <audio> ref before this cleanup runs
    // on unmount, so reading audioElRef.current there would stop nothing.
    const audioEl = audioElRef.current;
    return () => {
      unmountedRef.current = true;
      voiceEpochRef.current += 1;
      stopAllSpeech(audioEl);
      mediaRecorderRef.current?.state !== "inactive" && mediaRecorderRef.current?.stop();
      mediaStreamRef.current?.getTracks().forEach((t) => t.stop());
      void roomRef.current?.disconnect();
    };
  }, []);

  // Hydrate the persisted speaker toggle after mount (localStorage is not
  // SSR-safe; this page is a client component but pre-rendered).
  useEffect(() => {
    try {
      const muted = localStorage.getItem("callpanel.repliesMuted") === "1";
      repliesMutedRef.current = muted;
      setRepliesMuted(muted);
    } catch {
      // Private-mode: the toggle still works for the session via the ref.
    }
  }, []);

  // Speaker toggle: muting also silences whatever is being spoken now (same
  // barge-in mechanics a new hold uses). Manual replay still works muted.
  function toggleRepliesMuted() {
    const next = !repliesMutedRef.current;
    repliesMutedRef.current = next;
    setRepliesMuted(next);
    try {
      localStorage.setItem("callpanel.repliesMuted", next ? "1" : "0");
    } catch {
      // Best-effort persist only.
    }
    if (next) {
      voiceEpochRef.current += 1;
      stopAllSpeech(audioElRef.current);
      setReplayIndex(null);
    }
  }

  // In-call agent-audio mute: silence the remote track's elements without
  // leaving the call (mirrors the console panel's toggleAgentAudio).
  function toggleAgentAudio() {
    const next = !agentAudioMutedRef.current;
    agentAudioMutedRef.current = next;
    setAgentAudioMuted(next);
    remoteAudioContainerRef.current?.querySelectorAll("audio").forEach((el) => {
      el.muted = next;
    });
  }

  // Replay a retained agent line through the on-device/browser voices (this
  // page has no hosted /speak endpoint, and replay must never fetch).
  function stopReplay() {
    replayTokenRef.current += 1;
    stopAllSpeech(audioElRef.current);
    setReplayIndex(null);
  }
  async function replayLine(index: number, text: string) {
    if (!text) return;
    stopReplay();
    const token = replayTokenRef.current;
    const epoch = voiceEpochRef.current;
    const isStale = () =>
      unmountedRef.current || replayTokenRef.current !== token || voiceEpochRef.current !== epoch;
    unlockAudioElement(audioElRef.current);
    unlockWebSpeech();
    setReplayIndex(index);
    await speakShareLine(text, () => audioElRef.current, isStale);
    if (!isStale()) setReplayIndex(null);
  }

  // Speaks one live-call agent line through the on-device/browser voices -
  // the path taken when the worker has no server-side TTS key and reports
  // `voiced:false` on the transcript event. Same contract as replayLine:
  // speaker toggle respected, epoch guards a hang-up mid-utterance.
  async function speakAgentLine(text: string) {
    if (repliesMutedRef.current) return;
    const epoch = voiceEpochRef.current;
    await speakShareLine(text, () => audioElRef.current,
      () => unmountedRef.current || voiceEpochRef.current !== epoch);
  }

  // ---- Push-to-talk ---------------------------------------------------

  async function startPushToTalk() {
    if (recording || mode === "call") return;
    setError(null);
    setVoiceNotice(null);
    setMode("push-to-talk");
    // This handler is the user gesture: stop the agent talking (barge-in -
    // the microphone would otherwise record its voice into this turn) and
    // unlock audio now, because the reply plays seconds later, after awaits
    // (Safari only honours a play() issued during the gesture).
    voiceEpochRef.current += 1;
    stopAllSpeech(audioElRef.current);
    if (!audioUnlockedRef.current) {
      audioUnlockedRef.current = true;
      unlockAudioElement(audioElRef.current);
      unlockWebSpeech();
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, sampleRate: 16000 },
      });
      mediaStreamRef.current = stream;
      const mimeType = pickRecorderMimeType();
      const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : {});
      chunksRef.current = [];
      recorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) chunksRef.current.push(e.data);
      };
      recorder.onerror = () => setError("Recording failed - please try again.");
      recorder.onstop = () => {
        stream.getTracks().forEach((t) => t.stop());
        mediaStreamRef.current = null;
        if (chunksRef.current.length === 0) {
          setError("No audio was recorded - please speak and try again.");
          return;
        }
        const blob = new Blob(chunksRef.current, { type: recorder.mimeType || mimeType || "audio/webm" });
        if (blob.size < MIN_VIABLE_AUDIO_BYTES) {
          setError("That was too short to capture any speech - hold the button a little longer.");
          return;
        }
        void sendTurn(blob);
      };
      recorder.start(250);
      recordingStartedAtRef.current = Date.now();
      mediaRecorderRef.current = recorder;
      setRecording(true);
      setStage("listening");
    } catch (err: any) {
      let message = err?.message || "Microphone access was denied.";
      if (err?.name === "NotAllowedError") message = "Microphone access was denied. Check your browser permissions.";
      else if (err?.name === "NotFoundError") message = "No microphone found on this device.";
      setError(message);
      setMode("idle");
    }
  }

  function stopPushToTalk() {
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
      setTimeout(() => {
        if (recorder.state !== "inactive") recorder.stop();
      }, remaining);
    } else {
      recorder.stop();
    }
  }

  // The push-to-talk turn itself lives in ./shareTurn.ts (file-size ratchet);
  // this is the thin wrapper handing it the panel's state.
  function sendTurn(blob: Blob) {
    return sendShareTurn(
      {
        shareId,
        sessionIdRef,
        voiceEpochRef,
        unmountedRef,
        repliesMutedRef,
        audioElRef,
        sendTurnApi: sendSharedVoiceTurn,
        setStage,
        setTranscript,
        setReply,
        setError,
        setVoiceNotice,
        setServerVoiceMissing,
        appendCallLog: (entry) => setCallLog((prev) => [...prev, entry]),
        setMode,
      },
      blob
    );
  }

  // The one-tap offer's Download button. ensureTtsModel is bounded by
  // kokoro.ts's idle watchdog, so "downloading" cannot spin forever, and it
  // rejects with a reason we turn into a plain line - never a toast.
  async function startTtsDownload() {
    if (ttsDownloadingRef.current) return;
    ttsDownloadingRef.current = true;
    setTtsDownload({ status: "downloading", percent: null });
    try {
      await ensureTtsModel((p) => {
        if (unmountedRef.current) return;
        const pct = progressPercent(p);
        if (pct === null) return;
        setTtsDownload((prev) => (prev.status === "downloading" ? { status: "downloading", percent: pct } : prev));
      });
      if (unmountedRef.current) return;
      setLocalTtsInstalled(true);
      setTtsDownload({ status: "idle" });
      setVoiceNotice(null);
    } catch (err) {
      console.warn("[Voice] on-device voice download failed:", err);
      if (!unmountedRef.current) setTtsDownload({ status: "failed", error: displaySafeDownloadError(err) });
    } finally {
      ttsDownloadingRef.current = false;
    }
  }

  function handlePreflightAction(kind: PreflightActionKind) {
    if (kind === "download-tts") void startTtsDownload();
  }

  // Only the voice rules of the shared preflight apply here; the microphone
  // and speech-to-text inputs are passed as "nothing known", which makes
  // those rules say nothing (this panel has its own error line for them).
  const voiceIssues = evaluateCallPreflight({
    mic: "unknown",
    mediaRecorderSupported: true,
    serverSttChecked: false,
    serverSttAvailable: true,
    serverSttFailedThisCall: false,
    agentHasOwnSttProvider: false,
    speechLikelyEnglish: false,
    edgeOn: edgeEnabled(),
    localSttSupported: false,
    localSttInstalled: false,
    localTtsSupported,
    localTtsInstalled,
    serverVoiceMissing,
    webSpeechPresent: webSpeechPresent(),
    sttDownload: { status: "idle" },
    ttsDownload,
    sttSizeBytes: 0,
    ttsSizeBytes: TTS_MODEL_SIZE_BYTES,
  });

  // ---- Live call (LiveKit) --------------------------------------------

  async function startCall(withCamera: boolean) {
    if (mode === "push-to-talk" || callStatus === "connecting" || callStatus === "connected") return;
    setError(null);
    setMode("call");
    setCallStatus("connecting");
    agentAudioMutedRef.current = false;
    setAgentAudioMuted(false);
    liveTranscriptIdsRef.current.clear();
    try {
      const result = await getShareVoiceToken(shareId, withCamera ? "sight" : "voice");
      if (!result.ok || !result.voice) {
        setError(
          result.error === "rate_limited" ? "Too many calls right now - try again shortly." : "Couldn't start the call."
        );
        setCallStatus("error");
        setMode("idle");
        return;
      }
      const room = new Room();
      room.on(RoomEvent.Disconnected, () => {
        roomRef.current = null;
        setCallStatus("idle");
        setCameraOn(false);
        setMode("idle");
        if (remoteAudioContainerRef.current) remoteAudioContainerRef.current.innerHTML = "";
      });
      room.on(RoomEvent.TrackSubscribed, (track) => {
        if (track.kind === Track.Kind.Audio) {
          const el = track.attach();
          el.autoplay = true;
          el.muted = agentAudioMutedRef.current;
          remoteAudioContainerRef.current?.appendChild(el);
        }
      });
      room.on(RoomEvent.TrackUnsubscribed, (track) => {
        track.detach().forEach((el) => el.remove());
      });
      // Serializes locally-spoken agent replies (the `voiced:false` path in
      // the call-transcript handler below) - one speakReply at a time.
      let localSpeakChain: Promise<unknown> = Promise.resolve();
      // Retained call transcript + stage over the data channel: the
      // voice-agent worker publishes each turn's user/agent text on
      // "call-transcript" and lifecycle stages on "agent-stage" - "the
      // response in voice keeps its text on screen too". Deduped by the
      // event id the worker mints.
      room.on(RoomEvent.DataReceived, (payload, _participant, _kind, topic) => {
        try {
          const decoded = JSON.parse(new TextDecoder().decode(payload));
          if (topic === "call-transcript") {
            const role = decoded?.role === "user" || decoded?.role === "agent" ? decoded.role : null;
            const text = typeof decoded?.text === "string" ? decoded.text.trim() : "";
            const id = typeof decoded?.id === "string" ? decoded.id : `${decoded?.turnId ?? "?"}:${role}`;
            if (!role || !text || liveTranscriptIdsRef.current.has(id)) return;
            liveTranscriptIdsRef.current.add(id);
            setCallLog((prev) => [...prev, { role, text }]);
            // `voiced:false` = the worker has no server-side TTS key, so
            // the reply text is all there is - speak it through the same
            // on-device/browser chain the replay button uses (free, no
            // key needed). Chained so consecutive replies never overlap.
            if (role === "agent" && decoded.voiced === false) {
              localSpeakChain = localSpeakChain.then(() => speakAgentLine(text));
            }
          } else if (topic === "agent-stage") {
            const s = decoded?.stage;
            if (s === "listening") setStage("listening");
            else if (s === "understanding" || s === "generating") setStage("thinking");
            else if (s === "speaking") setStage("speaking");
            else if (s === "idle") setStage("idle");
            if (decoded?.error) setError(String(decoded.error));
          }
        } catch {
          // Malformed data message - ignore.
        }
      });
      await room.connect(result.voice.url, result.voice.token);
      roomRef.current = room;
      await room.localParticipant.setMicrophoneEnabled(true);
      if (withCamera) {
        try {
          await room.localParticipant.setCameraEnabled(true);
          setCameraOn(true);
        } catch {
          // Camera denial degrades to a working voice-only call.
        }
      }
      setCallStatus("connected");
    } catch (err: any) {
      setError(err?.message || "Couldn't start the call.");
      setCallStatus("error");
      setMode("idle");
    }
  }

  function endCall() {
    void roomRef.current?.disconnect();
    roomRef.current = null;
    setCallStatus("idle");
    setCameraOn(false);
    setMode("idle");
  }

  const stageText = stageLabel(stage);

  return (
    <div className="flex flex-col gap-2 rounded-xl border border-border/70 bg-card/50 p-3">
      <audio ref={audioElRef} className="hidden" />
      <div ref={remoteAudioContainerRef} className="hidden" />

      {/* Voice-over controls: the visitor's reply-voice pick (this device:
          Auto / an installed on-device Kokoro voice / a browser voice) plus
          the spoken-replies speaker toggle - the same controls the
          playground's audio row has, minus anything needing credentials. */}
      <ShareVoiceoverRow repliesMuted={repliesMuted} onToggleRepliesMuted={toggleRepliesMuted} />

      <div className="flex items-center justify-center gap-3">
        {/* Push-to-talk */}
        <Button
          type="button"
          size="lg"
          variant={recording ? "default" : "outline"}
          disabled={mode === "call"}
          onMouseDown={startPushToTalk}
          onMouseUp={stopPushToTalk}
          onMouseLeave={() => recording && stopPushToTalk()}
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
              stopPushToTalk();
            }
          }}
          onBlur={() => recording && stopPushToTalk()}
          className={cn("gap-2 rounded-full px-5", recording && "animate-pulse")}
          aria-label={recording ? "Recording - release to send" : `Hold to talk to ${agentName}`}
        >
          <Mic className="size-4" aria-hidden="true" />
          {recording ? "Release to send" : "Hold to talk"}
        </Button>

        {/* Live call */}
        {callStatus === "connected" ? (
          <Button type="button" size="lg" variant="destructive" onClick={endCall} className="gap-2 rounded-full px-5">
            <PhoneOff className="size-4" aria-hidden="true" />
            End call
          </Button>
        ) : (
          <Button
            type="button"
            size="lg"
            variant="outline"
            disabled={mode === "push-to-talk" || callStatus === "connecting"}
            onClick={() => startCall(false)}
            className="gap-2 rounded-full px-5"
          >
            {callStatus === "connecting" ? (
              <Loader2 className="size-4 animate-spin" aria-hidden="true" />
            ) : (
              <Phone className="size-4" aria-hidden="true" />
            )}
            Call
          </Button>
        )}

        {supportsImageInput && callStatus === "connected" && (
          <Button
            type="button"
            size="icon"
            variant="outline"
            onClick={() =>
              cameraOn
                ? roomRef.current?.localParticipant.setCameraEnabled(false).then(() => setCameraOn(false))
                : roomRef.current?.localParticipant.setCameraEnabled(true).then(() => setCameraOn(true))
            }
            aria-label={cameraOn ? "Turn camera off" : "Turn camera on"}
            className="rounded-full"
          >
            {cameraOn ? (
              <Video className="size-4" aria-hidden="true" />
            ) : (
              <VideoOff className="size-4" aria-hidden="true" />
            )}
          </Button>
        )}

        {/* In-call agent audio mute (mirrors the console panel's speaker
            button): silences the agent's voice without leaving the call. */}
        {callStatus === "connected" && (
          <Button
            type="button"
            size="icon"
            variant="outline"
            onClick={toggleAgentAudio}
            aria-label={agentAudioMuted ? "Resume agent audio" : "Stop agent audio"}
            title={agentAudioMuted ? "Resume agent audio" : "Stop agent audio"}
            className="rounded-full"
          >
            {agentAudioMuted ? (
              <VolumeX className="size-4" aria-hidden="true" />
            ) : (
              <Volume2 className="size-4" aria-hidden="true" />
            )}
          </Button>
        )}
      </div>

      {/* The retained transcript: every completed exchange (push-to-talk and
          live-call turns) stays listed for the visit, plus the in-flight
          turn's stage/partial text below it. Agent lines get a replay
          button (on-device/browser voices - this page has no hosted /speak). */}
      <ShareCallLog
        callLog={callLog}
        stageText={stageText}
        transcript={transcript}
        reply={reply}
        replayIndex={replayIndex}
        onReplay={(i, text) => void replayLine(i, text)}
        onStopReplay={stopReplay}
      />

      {voiceNotice && (
        <p className="text-center text-xs text-muted-foreground" role="status">
          {voiceNotice}
        </p>
      )}
      <CallPreflight issues={voiceIssues} onAction={handlePreflightAction} disabled={recording} />
      {error && <p className="text-center text-xs text-destructive">{error}</p>}

      {callStatus === "connected" && (
        <p className="text-center text-[11px] text-muted-foreground">
          {supportsImageInput
            ? "On a call" + (cameraOn ? " with camera" : "") + " - just speak."
            : "On a call - just speak."}
        </p>
      )}
    </div>
  );
}
