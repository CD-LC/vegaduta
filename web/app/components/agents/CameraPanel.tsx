"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "../../lib/api";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";
import { unlockAudioElement, unlockWebSpeech } from "../../lib/voice/voicePlayers";
import { edgeEnabled } from "../../lib/edge/capabilities";
// Contract signatures (docs/LIVE-VISION-SENSE-RESEARCH-2026-08-05.md + the
// W-wave shared contract): the module internals are owned by the edge agent;
// this file codes strictly against the three exported functions.
import { ensureVisionModel, analyzeFramesLocally, isVisionModelCached } from "../../lib/edge/vision";
import { Button } from "../ui/button";
import { Select } from "../ui/select";
import CaptureToolbar from "./camera/CaptureToolbar";
import LiveDot from "./camera/LiveDot";
import StagedFramesStrip from "./camera/StagedFramesStrip";
import WatchControls, { type EdgeProgress } from "./camera/WatchControls";
import {
  DIFF_H,
  DIFF_THRESHOLD,
  DIFF_W,
  MAX_STAGED_FRAMES,
  MIN_SEND_GAP_MS,
  SPEAK_MIN_CHARS,
  captureFrameWithRetry,
  meanAbsDiff,
  sampleGray,
  type StagedFrame,
} from "./camera/captureCore";
import { captureErrorMessage, watchError } from "./camera/cameraErrors";
import { useAlertSpeech } from "./camera/useAlertSpeech";
import { useEdgeVisionOffered } from "./camera/useEdgeVisionOffered";
import { useSingleShotAnalyzing } from "./camera/useSingleShotAnalyzing";

/**
 * Live vision ("Sight", Program S0) - a sibling strip to CallPanel inside
 * AgentPlayground, gated on the agent's resolved image-input capability (the
 * caller owns that gate via the canSee/capsLoading props - this panel renders
 * nothing when the model can't see).
 *
 * Two modes, both built on the same capture core (draw the live <video> to a
 * canvas, JPEG at quality 0.8, downscaled to <=1024px long edge):
 *
 *   SNAPSHOT - the "Show" button stages up to 3 frames that ride the NEXT
 *   normal playground send as inline attachments with source "camera"/
 *   "screen" (the staged list itself lives in AgentPlayground, since that's
 *   whose send path consumes it).
 *
 *   WATCH - a standing instruction + interval capture (2/5/15s) behind a
 *   pixel-diff gate: each candidate frame is drawn to a 64x36 grayscale
 *   canvas and compared (mean absolute difference) against the last frame
 *   actually SENT; below ~10% difference nothing is sent at all, and a hard
 *   3s floor between sends holds regardless of diff storms (camera shake).
 *   Accepted frames become real chat turns (streamChat with the frame + the
 *   standing instruction + the visionSessionId minted by
 *   api.startVisionSession), rendered into the normal transcript with a
 *   "watch" badge. Stop sends one final summarization turn then ends the
 *   session.
 *
 * Trust rules (binding, per the research doc §4.5): capture starts only from
 * an explicit click; a pulsing red on-air dot is visible the whole time a
 * track is live; stop releases every track immediately; watch capture pauses
 * while the tab is hidden (Page Visibility API); no frame is kept beyond the
 * last-diff reference and the staged thumbnails. And the on-device rule:
 * when the person chose "Analyze on this device", frames NEVER silently fall
 * back to the server - a local failure surfaces its reason and offers the
 * server path as an explicit choice.
 */

export type { StagedFrame } from "./camera/captureCore";

export default function CameraPanel({
  agent,
  canSee,
  capsLoading,
  busy,
  stagedFrames,
  onStagedFramesChange,
  visionOnDevice,
  onVisionOnDeviceChange,
  runWatchTurn,
  appendLocalTurn,
  matchRecognizedFace,
  withRecognizedName,
}: {
  agent: any;
  /** The agent's resolved image-input capability (AgentPlayground's caps). */
  canSee: boolean;
  capsLoading: boolean;
  /** The playground has a turn in flight - watch ticks skip while true. */
  busy: boolean;
  stagedFrames: StagedFrame[];
  onStagedFramesChange: (frames: StagedFrame[]) => void;
  visionOnDevice: boolean;
  onVisionOnDeviceChange: (on: boolean) => void;
  /** Server watch turn - appends transcript turns, streams, returns the reply text. Throws with err.status. */
  runWatchTurn: (opts: { message: string; frames: StagedFrame[]; visionSessionId: string | null }) => Promise<string>;
  /** On-device watch turn result - appends a completed user+assistant pair. */
  appendLocalTurn: (opts: { message: string; frames: StagedFrame[]; text: string }) => void;
  /** Face recognition (2026-08-06) - budget-capped, session-cached on-device
   * face match; resolves to a recognized person's name or null (not
   * enrolled/opted-in/matched/anything failed - all the same "don't enrich
   * this turn" outcome). Owned by AgentPlayground (it holds `agent` and the
   * session-scoped candidate cache), passed down the same way
   * runWatchTurn/appendLocalTurn already are. */
  matchRecognizedFace: (dataUrl: string) => Promise<string | null>;
  /** Splices a recognized name into a message for both the LLM call and the
   * transcript display; no-op when personName is null. */
  withRecognizedName: (baseMessage: string, personName: string | null) => string;
}) {
  const t = useTranslations("cameraPanel");

  const [source, setSource] = useState<"camera" | "screen">("camera");
  const [active, setActive] = useState(false);
  const [previewCollapsed, setPreviewCollapsed] = useState(false);
  const [facingMode, setFacingMode] = useState<"user" | "environment">("user");
  const [devices, setDevices] = useState<any[]>([]);
  const [deviceId, setDeviceId] = useState("");
  const [panelError, setPanelError] = useState<string | null>(null);
  // Set once a vision-session start comes back 403 - the start control turns
  // into the shared upgrade-lock Button instead of a dead error message.
  const [planLocked, setPlanLocked] = useState(false);

  const [watching, setWatching] = useState(false);
  const [instruction, setInstruction] = useState("");
  const [intervalSec, setIntervalSec] = useState(5);
  const [framesAnalyzed, setFramesAnalyzed] = useState(0);
  const [speakAlerts, setSpeakAlerts] = useState(false);
  const { alertAudioRef, stopAlertSpeech, speakAlert } = useAlertSpeech(agent?.id, canSee);
  const [sessionNote, setSessionNote] = useState<string | null>(null);
  // Soft, transient signal for a mistimed capture during the watch tick -
  // never stops the loop (the next tick retries on its own), just tells the
  // person why nothing was sent this cycle instead of staying silent.
  const [frameCaptureNotice, setFrameCaptureNotice] = useState<string | null>(null);
  const { singleShotAnalyzing, markStaged, clearPending } = useSingleShotAnalyzing(stagedFrames, busy, agent?.id);

  // Edge ("analyze on this device") state. Enabling the toggle runs
  // ensureVisionModel first (with download progress).
  const edgeOffered = useEdgeVisionOffered();
  const [edgeProgress, setEdgeProgress] = useState<EdgeProgress | null>(null);
  // Non-null while a mid-watch local analysis failed: holds the reason, and
  // the watch loop stays paused until the person explicitly picks "continue
  // on the server" or stops - never a silent escalation.
  const [edgeFallback, setEdgeFallback] = useState<string | null>(null);

  const videoRef = useRef<any>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const diffCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const lastSentGrayRef = useRef<Uint8ClampedArray | null>(null);
  const lastSentAtRef = useRef(0);
  const inFlightRef = useRef(false);
  const frameSeqRef = useRef(0);
  const sessionRef = useRef<any>(null);
  // Latest-callback pattern: the interval always invokes the newest tick
  // closure, so the loop reads current state without re-subscribing per
  // keystroke of the instruction field.
  const tickRef = useRef<() => Promise<void>>(async () => {});
  // Same pattern for the hard stop: track.onended (the browser's own "stop
  // sharing" button) is registered once at capture start, and a stale
  // closure there would read `watching` as it was at that moment - i.e. a
  // watch session started later would never be closed out.
  const stopCaptureRef = useRef<() => void>(() => {});

  // Agent switch / unmount: release the device immediately and drop all watch
  // state - a camera must never survive the component that showed its dot.
  // canSee is a dep because the <audio> only mounts once canSee is true, and
  // losing sight unmounts the preview - the device must be released then too.
  // (useAlertSpeech silences a spoken alert on the same triggers.)
  useEffect(() => {
    return () => {
      streamRef.current?.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    };
  }, [agent?.id, canSee]);
  useEffect(() => {
    setActive(false);
    setWatching(false);
    setFramesAnalyzed(0);
    setSessionNote(null);
    setPanelError(null);
    setEdgeFallback(null);
    setFrameCaptureNotice(null);
    // A stale plan-lock from a previous agent must never bleed into a
    // different agent's start button - this agent may never have been
    // blocked at all.
    setPlanLocked(false);
    sessionRef.current = null;
    lastSentGrayRef.current = null;
  }, [agent?.id]);

  function stopTracks() {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
  }

  // Explicit click only - nothing here runs on mount.
  async function startCapture(
    nextSource: "camera" | "screen",
    opts: { facingMode?: "user" | "environment"; deviceId?: string } = {}
  ) {
    stopTracks();
    setPanelError(null);
    setSessionNote(null);
    // Outside a secure context (plain http on a LAN IP, some embedded
    // webviews) mediaDevices simply doesn't exist - say so up front instead
    // of letting the call below throw a TypeError that reads like a bug.
    if (typeof window !== "undefined" && (!window.isSecureContext || !navigator.mediaDevices)) {
      setPanelError(t("insecureContext"));
      return;
    }
    if (nextSource === "screen" && !(navigator.mediaDevices as any).getDisplayMedia) {
      setPanelError(t("screenNotSupported"));
      return;
    }
    try {
      let stream: MediaStream;
      if (nextSource === "screen") {
        stream = await (navigator.mediaDevices as any).getDisplayMedia({ video: true });
      } else {
        const fm = opts.facingMode || facingMode;
        const dev = opts.deviceId !== undefined ? opts.deviceId : deviceId;
        stream = await navigator.mediaDevices.getUserMedia({
          video: { width: { ideal: 1280 }, ...(dev ? { deviceId: { exact: dev } } : { facingMode: fm }) },
        });
      }
      streamRef.current = stream;
      setSource(nextSource);
      setActive(true);
      // The person ending a screen share from the browser's own UI must tear
      // the panel down exactly like our Stop button would - via the ref, so
      // the handler sees the CURRENT watch state, not a snapshot of it.
      const track = stream.getVideoTracks()[0];
      if (track) track.onended = () => stopCaptureRef.current();
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        videoRef.current.play?.().catch(() => {});
      }
      if (nextSource === "camera") {
        // Labels are only populated after permission - enumerate now, not on mount.
        try {
          const list = await navigator.mediaDevices.enumerateDevices();
          setDevices(list.filter((d) => d.kind === "videoinput"));
        } catch {
          // Picker just stays empty.
        }
      }
    } catch (err: any) {
      setActive(false);
      setPanelError(captureErrorMessage(err, nextSource, t));
    }
  }

  // Hard stop: tracks released FIRST (the on-air light must die instantly),
  // then the watch session is closed out (summarization needs no camera).
  function stopCapture() {
    const wasWatching = watching;
    stopTracks();
    setActive(false);
    lastSentGrayRef.current = null;
    if (wasWatching) void stopWatching(true);
  }
  stopCaptureRef.current = stopCapture;

  // Maps a watch failure to its line; a plan-tier 403 also locks the start control.
  function watchErrorMessage(err: any): string {
    const { message, planLocked: locked } = watchError(err, t);
    if (locked) setPlanLocked(true);
    return message;
  }

  async function startWatching() {
    if (!agent || watching || !instruction.trim() || !streamRef.current) return;
    setPanelError(null);
    setSessionNote(null);
    setEdgeFallback(null);
    setFrameCaptureNotice(null);
    setFramesAnalyzed(0);
    lastSentGrayRef.current = null;
    lastSentAtRef.current = 0;
    if (visionOnDevice) {
      // Pure on-device watch: no server session at all - the model just has
      // to actually be resident before the loop starts.
      try {
        setEdgeProgress({ loaded: 0, total: 0, status: "starting" });
        await ensureVisionModel((p) => setEdgeProgress(p));
      } catch (err: any) {
        setPanelError(err?.message || t("edgePrepareFailed"));
        return;
      } finally {
        setEdgeProgress(null);
      }
      sessionRef.current = null;
      // A successful start (even the pure-on-device path, which never calls
      // the server) means any prior plan-lock no longer applies.
      setPlanLocked(false);
      setWatching(true);
      return;
    }
    try {
      const session = await api.startVisionSession(agent.id, "WATCH", source === "camera" ? "CAMERA" : "SCREEN");
      sessionRef.current = session;
      setPlanLocked(false);
      setWatching(true);
    } catch (err: any) {
      setPanelError(watchErrorMessage(err));
    }
  }

  // summarize=false is the error path (a summarization turn against an API
  // that just 429'd would only fail again); the session end still runs so
  // the server can close its books.
  async function stopWatching(summarize: boolean) {
    setWatching(false);
    setEdgeFallback(null);
    setFrameCaptureNotice(null);
    const session = sessionRef.current;
    sessionRef.current = null;
    if (!session) {
      setSessionNote(t("sessionEnded", { count: framesAnalyzed }));
      return;
    }
    if (summarize) {
      try {
        await runWatchTurn({ message: t("summarizePrompt"), frames: [], visionSessionId: session.sessionId });
      } catch {
        // The end call below still runs - a failed summary shouldn't leak the session.
      }
    }
    try {
      const ended = await api.endVisionSession(session.sessionId);
      setSessionNote(t("sessionEnded", { count: ended?.framesAnalyzed ?? framesAnalyzed }));
    } catch {
      setSessionNote(t("sessionEnded", { count: framesAnalyzed }));
    }
  }

  // The person picked "continue on the server" after a local failure: flips
  // the toggle off (visibly - this is their explicit escalation choice) and
  // opens the server session the pure-edge watch never had.
  async function continueWatchOnServer() {
    if (!agent) return;
    try {
      const session = await api.startVisionSession(agent.id, "WATCH", source === "camera" ? "CAMERA" : "SCREEN");
      sessionRef.current = session;
      setPlanLocked(false);
      onVisionOnDeviceChange(false);
      setEdgeFallback(null);
    } catch (err: any) {
      setEdgeFallback(null);
      setWatching(false);
      setPanelError(watchErrorMessage(err));
    }
  }

  tickRef.current = async () => {
    if (!watching || busy || inFlightRef.current || edgeFallback) return;
    // Route change / backgrounded tab pauses capture (trust rule) - the
    // interval keeps ticking but nothing is sampled or sent.
    if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
    const video = videoRef.current;
    if (!video || !streamRef.current) return;
    let canvas = diffCanvasRef.current;
    if (!canvas) {
      canvas = document.createElement("canvas");
      canvas.width = DIFF_W;
      canvas.height = DIFF_H;
      diffCanvasRef.current = canvas;
    }
    const gray = sampleGray(video, canvas);
    if (!gray) return;
    const last = lastSentGrayRef.current;
    if (last && meanAbsDiff(gray, last) < DIFF_THRESHOLD) return;
    if (Date.now() - lastSentAtRef.current < MIN_SEND_GAP_MS) return;
    const frame = await captureFrameWithRetry(video, source, frameSeqRef);
    if (!frame) {
      // Never fail the watch loop silently - this tick just didn't produce a
      // frame; the next tick tries again on its own (that's the "automatic
      // retry" the interval already gives us), but the person should still
      // see why nothing happened just now.
      setFrameCaptureNotice(t("cameraNotReadyRetrying"));
      return;
    }
    if (frameCaptureNotice) setFrameCaptureNotice(null);
    // The diff reference is the last frame SENT, not the last frame seen -
    // slow continuous drift eventually accumulates past the threshold.
    lastSentGrayRef.current = gray;
    lastSentAtRef.current = Date.now();
    inFlightRef.current = true;
    try {
      let replyText = "";
      // Face recognition (2026-08-06) - budget-capped (see
      // AgentPlayground's matchRecognizedFace), so this never meaningfully
      // delays a watch tick; resolves to null (no splice) for every
      // "nothing to enrich with" case, never an error.
      const recognizedName = await matchRecognizedFace(frame.dataUrl);
      const effectiveInstruction = withRecognizedName(instruction, recognizedName);
      if (visionOnDevice) {
        const result = await analyzeFramesLocally([{ dataUrl: frame.dataUrl }], effectiveInstruction);
        // `=== false` (not `!`): with this repo's strict:false tsconfig,
        // truthiness checks don't narrow the contract's discriminated union,
        // literal comparison does.
        if (result.handled === false) {
          // NEVER silently send camera frames to the server when the person
          // chose on-device - pause and ask instead.
          setEdgeFallback(result.reason);
          return;
        }
        replyText = result.text;
        appendLocalTurn({ message: effectiveInstruction, frames: [frame], text: replyText });
      } else {
        replyText = await runWatchTurn({
          message: effectiveInstruction,
          frames: [frame],
          visionSessionId: sessionRef.current?.sessionId || null,
        });
      }
      setFramesAnalyzed((n) => n + 1);
      if (speakAlerts && replyText && replyText.trim().length >= SPEAK_MIN_CHARS) {
        speakAlert(replyText);
      }
    } catch (err: any) {
      // Server-fail -> edge rescue (S-edge ladder, LIVE-VISION-SENSE-RESEARCH
      // §3: edge is the FREE/default tier; the consent rule only guards the
      // OTHER direction). A failing SERVER watch turn - hosted vision model
      // down (502/503), frame allowance spent (429), plan gate (403) - no
      // longer kills the whole session when the local VLM is already
      // installed: flip to on-device analysis, which is strictly MORE
      // private than what the person was already doing, and keep watching.
      // The visible toggle flip + session note make the switch explicit.
      // analyzeFramesLocally never throws, so reaching this catch implies
      // the SERVER path failed - the !visionOnDevice guard is belt-and-
      // braces for future refactors, not load-bearing today.
      if (!visionOnDevice && edgeEnabled() && isVisionModelCached()) {
        const failedSession = sessionRef.current;
        sessionRef.current = null;
        if (failedSession) {
          // Close the server session's books best-effort; its vision model
          // just failed, so the end call may fail too - that's fine.
          api.endVisionSession(failedSession.sessionId).catch(() => {});
        }
        onVisionOnDeviceChange(true);
        setSessionNote(t("serverVisionRescued"));
      } else {
        setPanelError(watchErrorMessage(err));
        void stopWatching(false);
      }
    } finally {
      inFlightRef.current = false;
    }
  };

  useEffect(() => {
    if (!watching) return undefined;
    const id = setInterval(() => {
      void tickRef.current();
    }, intervalSec * 1000);
    return () => clearInterval(id);
  }, [watching, intervalSec]);

  async function stageFrame() {
    const video = videoRef.current;
    if (!video || !streamRef.current) return;
    if (stagedFrames.length >= MAX_STAGED_FRAMES) {
      setPanelError(t("maxFrames"));
      return;
    }
    const frame = await captureFrameWithRetry(video, source, frameSeqRef);
    if (!frame) {
      // Mistimed click (e.g. right after "Start camera", before the video
      // element has painted a frame) - visible instead of a silent no-op.
      setPanelError(t("cameraNotReady"));
      return;
    }
    setPanelError(null);
    markStaged();
    onStagedFramesChange([...stagedFrames, frame]);
  }

  async function toggleOnDevice() {
    if (visionOnDevice) {
      onVisionOnDeviceChange(false);
      return;
    }
    try {
      setEdgeProgress({ loaded: 0, total: 0, status: "starting" });
      await ensureVisionModel((p) => setEdgeProgress(p));
      onVisionOnDeviceChange(true);
    } catch (err: any) {
      setPanelError(err?.message || t("edgePrepareFailed"));
    } finally {
      setEdgeProgress(null);
    }
  }

  if (!agent) return null;
  // Caller-owned capability gate: nothing renders for a model that can't see,
  // and while capabilities are still resolving only a one-line hint shows.
  if (!canSee) {
    if (!capsLoading) return null;
    return (
      <div className="border-t border-border px-5 py-2">
        <span className="text-xs text-muted-foreground">{t("capsLoading")}</span>
      </div>
    );
  }

  const cameraDevices = devices.filter((d) => d.deviceId);

  return (
    <div className="flex flex-col gap-2 border-t border-border px-5 py-3">
      <CaptureToolbar
        active={active}
        source={source}
        stagedCount={stagedFrames.length}
        previewCollapsed={previewCollapsed}
        onStart={(next) => void startCapture(next)}
        onShow={() => void stageFrame()}
        onFlip={() => {
          const next = facingMode === "user" ? "environment" : "user";
          setFacingMode(next);
          setDeviceId("");
          void startCapture("camera", { facingMode: next, deviceId: "" });
        }}
        onTogglePreview={() => setPreviewCollapsed((c) => !c)}
        onStop={stopCapture}
      />

      <audio ref={alertAudioRef} className="hidden" />
      {/* Always mounted while the panel shows: capture draws from this element
          even when the preview is collapsed, so it hides via class, never by
          unmounting. Muted - it's the user's own camera/screen. */}
      {}
      <video
        ref={videoRef}
        autoPlay
        muted
        playsInline
        className={cn(
          "w-full max-w-xs rounded-lg border border-border object-cover",
          active && !previewCollapsed ? "h-40" : "hidden"
        )}
      />

      {active && source === "camera" && cameraDevices.length > 1 && (
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span id="camera-device-label">{t("deviceLabel")}</span>
          <Select
            aria-labelledby="camera-device-label"
            className="w-56"
            value={deviceId}
            onValueChange={(next) => {
              setDeviceId(next);
              void startCapture("camera", { deviceId: next });
            }}
            options={[
              { value: "", label: t("startCamera") },
              ...cameraDevices.map((d, i) => ({ value: d.deviceId, label: d.label || `${t("startCamera")} ${i + 1}` })),
            ]}
          />
        </div>
      )}

      <StagedFramesStrip
        stagedFrames={stagedFrames}
        onRemove={(frameId) => {
          const next = stagedFrames.filter((f) => f.id !== frameId);
          // A manual removal down to zero is "changed my mind", not a
          // send - clear the pending-send flag so a later unrelated
          // send doesn't wrongly light up "AI is looking at this frame".
          if (next.length === 0) clearPending();
          onStagedFramesChange(next);
        }}
      />

      {/* Real-time status parity with watch mode's pulsing "watching"
          indicator, for the single-shot Show -> send path - the frames
          themselves are already gone from `stagedFrames` by the time the
          turn is in flight (see the effect that derives this), so this is
          the only persistent "AI is looking at this frame right now" signal
          that path has. */}
      {singleShotAnalyzing && (
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <LiveDot state="--state-running" />
          {t("singleShotAnalyzing")}
        </div>
      )}

      {active && (
        <WatchControls
          instruction={instruction}
          onInstructionChange={setInstruction}
          watching={watching}
          intervalSec={intervalSec}
          onIntervalChange={setIntervalSec}
          planLocked={planLocked}
          visionOnDevice={visionOnDevice}
          edgeOffered={edgeOffered}
          edgeProgress={edgeProgress}
          frameCaptureNotice={frameCaptureNotice}
          framesAnalyzed={framesAnalyzed}
          speakAlerts={speakAlerts}
          onSpeakAlertsChange={(on) => {
            setSpeakAlerts(on);
            if (on) {
              // This click is the only user gesture spoken alerts ever
              // get - they fire later from the watch loop - so unlock
              // audio playback and the speech engine here.
              unlockAudioElement(alertAudioRef.current);
              unlockWebSpeech();
            } else {
              stopAlertSpeech();
            }
          }}
          onStartWatching={() => void startWatching()}
          onStopWatching={() => void stopWatching(true)}
          onToggleOnDevice={() => void toggleOnDevice()}
        />
      )}

      {edgeFallback && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-secondary/40 p-2.5 text-xs text-muted-foreground">
          <span className="min-w-0">{t("edgeFallbackReason", { reason: edgeFallback })}</span>
          <Button type="button" variant="outline" size="sm" onClick={() => void continueWatchOnServer()}>
            {t("edgeContinueServer")}
          </Button>
          <Button type="button" variant="ghost" size="sm" onClick={() => void stopWatching(false)}>
            {t("edgeStopInstead")}
          </Button>
        </div>
      )}

      {sessionNote && <p className="text-xs text-muted-foreground">{sessionNote}</p>}
      {panelError && (
        <div className="rounded-lg border border-destructive/20 bg-destructive/5 p-2.5 text-xs text-destructive">
          {panelError}
        </div>
      )}
    </div>
  );
}
