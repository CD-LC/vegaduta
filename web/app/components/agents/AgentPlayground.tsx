"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslations } from "../../lib/i18n";
import { Card } from "../ui/card";
import OnDeviceSetupNudge from "../edge/OnDeviceSetupNudge";
import { ArtifactLayout, useArtifactsPane } from "../artifacts/useArtifactsPane";
import type { StagedFrame } from "./CameraPanel";
import { edgeEnabled } from "../../lib/edge/capabilities";
import { resetEdgeSession } from "../../lib/edge/tierRouter";
import { useWebMcpTools } from "../../lib/edge/useWebMcpTools";
import { useRequestLifecycle, type RequestLifecycleController } from "../../lib/requestLifecycle";
import { EdgeOptInBanner } from "./playground/EdgeOptInBanner";
import { Composer } from "./playground/Composer";
import { LabOverlayHost } from "./playground/LabOverlayHost";
import { LiveStatusBar } from "./playground/LiveStatusBar";
import { deriveLiveChips } from "./playground/liveStatus";
import { RunSettingsDrawer } from "./playground/runSettings/RunSettingsDrawer";
import { MessageList } from "./playground/MessageList";
import { PlaygroundHeader } from "./playground/PlaygroundHeader";
import { SessionSidebar } from "./playground/SessionSidebar";
import { loadPlaygroundState, savePlaygroundState } from "./playground/persistence";
import type { PlaygroundContext } from "./playground/sendContext";
import type { PlaygroundAgent, VisionFallbackOffer } from "./playground/types";
import { useAttachments } from "./playground/useAttachments";
import { useChatStream } from "./playground/useChatStream";
import { useApprovalContinuation, type ApprovalContinuationWatch } from "./useApprovalContinuation";
import { useEdgeState } from "./playground/useEdgeState";
import { useEdgeTurns } from "./playground/useEdgeTurns";
import { useFaceRecognition } from "./playground/useFaceRecognition";
import { useSendActions } from "./playground/useSendActions";
import { useServerTurn } from "./playground/useServerTurn";
import { usePersistTranscript, useTranscript } from "./playground/useTranscript";
import { useVoiceSession } from "./playground/useVoiceSession";

// The playground's pieces live in ./playground (BIG-FILES-SPLIT-PLAN, file 4):
// useChatStream (the stream state machine), useTranscript, useAttachments,
// useVoiceSession, useEdgeState, useFaceRecognition and the send pipeline
// hooks, plus the presentational Composer / MessageList / SessionSidebar /
// LabOverlayHost. This file keeps the wiring and the cross-cutting agent-switch
// and new-session resets.
export { PLAYGROUND_STORAGE_PREFIX } from "./playground/persistence";

/**
 * The 492px live Playground pane from design_handoff_axon's Agents.dc.html -
 * a real chat against the selected agent, ported from ChatPanel.js's proven
 * streaming logic (api.streamChat + api.listToolCalls) rather than
 * reimplemented, just restyled to the AXON HUD language and laid out as a
 * message thread with an inline tool-call trace per agent turn.
 *
 * The design's per-turn metrics row shows only what has a real backend
 * source (this project's "never fabricate a number" rule): wall-clock
 * latency measured client-side around the streamChat call, the count of
 * tool calls this turn actually made (from a before/after diff against
 * api.listToolCalls, keyed by each call's own startedAt timestamp), and -
 * since AgentSessionLlmUsageController landed - token/cost sums computed by
 * the same before/after diff over GET
 * /api/agents/{id}/sessions/{sessionId}/llm-usage (one row per real
 * provider call on this session's llm_call_log ledger; the slice of it
 * recorded during this turn IS the turn's usage, retries included). When
 * the usage read isn't possible (the tenant's plan doesn't include
 * LLM-usage visibility - the same PREMIUM+ gate the admin usage surface
 * carries - or a transient failure) the row simply omits the numbers, the
 * same posture the trace takes on its own failures. The design's remaining
 * un-reproduced number is confidence ".94", which still has no real
 * backend source.
 */
export default function AgentPlayground({
  agent,
  initialLabOpen,
  initialRunSettingsOpen,
}: {
  agent: PlaygroundAgent | null;
  // Deep-link entry (?lab=open via /agents - see AgentWorkbench): starts the
  // lab overlay open so a quality-page link lands inside the lab, not next
  // to it. Read once at mount; closing the lab stays local state.
  initialLabOpen?: boolean;
  // Starts with the "Run settings" drawer open (previews, deep links). Read once at mount.
  initialRunSettingsOpen?: boolean;
}) {
  const t = useTranslations("agentPlayground");
  // WebMCP (E-2b): publish this agent's edge-safe tools to the browser's
  // model context while the playground is open. No-op where
  // navigator.modelContext doesn't exist.
  useWebMcpTools(agent?.id ?? null);

  const { sending, error, setSending, setError } = useChatStream();
  // Owned here (not in useVoiceSession) because the transcript window needs the
  // value and the voice hook needs the transcript's turns.
  const [speakingTurnIndex, setSpeakingTurnIndex] = useState<number | null>(null);
  const transcript = useTranscript(speakingTurnIndex);
  const { sessionId, setSessionId, turns, setTurns, stickToBottomRef } = transcript;

  const [input, setInput] = useState("");
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  // A decided tool approval: core carries the task on itself; its reply is appended to the transcript here and
  // the composer stays off until it has (a message typed meanwhile would race the agent's own turn).
  const approvalContinuationRef = useRef<ApprovalContinuationWatch | null>(null);
  const approvalContinuation = useApprovalContinuation({
    agentId: agent?.id ?? null,
    sessionId,
    onReply: (reply) => {
      setTurns((prev) => [
        ...prev,
        reply.failed
          ? { role: "assistant", content: reply.content, failed: true, continuation: true }
          : { role: "assistant", content: reply.content, continuation: true },
      ]);
      // The reply itself is the receipt: dismiss the "completed" status line
      // now instead of letting it sit under the final answer for seconds.
      approvalContinuationRef.current?.dismiss();
    },
  });
  approvalContinuationRef.current = approvalContinuation;
  const busy = sending || approvalContinuation.continuing;
  // Artifacts pane (flag-gated, default off), the same hook ChatPanel uses, so
  // a reply gets the same code block and "Open as artifact" offer whichever
  // path produced it. Inert when the flag is off.
  const artifacts = useArtifactsPane({
    turns,
    sending,
    onAskPrefill: (text) => {
      setInput(text);
      inputRef.current?.focus();
    },
  });
  // "Run settings" drawer, and how many parked tool calls are waiting for a decision (feeds the status chips).
  const [runSettingsOpen, setRunSettingsOpen] = useState(!!initialRunSettingsOpen);
  // The tool switches of the Run settings drawer: a selection for the runs of THIS playground session only. It
  // is sent with each message as `toolNames` (narrowing the agent's own tools for that run) and is never saved to
  // the agent. Kept with the agent id so a selection made for one agent can never ride a message to another.
  const [runToolsState, setRunToolsState] = useState<{ agentId: string; names: string[] } | null>(null);
  const runToolNames = runToolsState && runToolsState.agentId === agent?.id ? runToolsState.names : null;
  const runSettingsButtonRef = useRef<HTMLButtonElement | null>(null);
  // False until this component has run its agent effect once (the mount itself is not a switch).
  const agentSwitchSeenRef = useRef(false);
  const [waitingApprovals, setWaitingApprovals] = useState(0);
  const [labOpen, setLabOpen] = useState(!!initialLabOpen);
  // useState's initial value only applies at mount - a client-side
  // navigation to /agents?lab=open while already on /agents must also open
  // the overlay.
  useEffect(() => {
    if (initialLabOpen) setLabOpen(true);
  }, [initialLabOpen]);

  // Live vision (Program S0): frames staged by CameraPanel's "Show" button ride
  // the NEXT normal send as inline attachments, so the state lives here.
  const [stagedFrames, setStagedFrames] = useState<StagedFrame[]>([]);
  // "Analyze on this device" - lifted here (toggle UI is in CameraPanel)
  // because the SNAPSHOT send path must also honor it. useEdgeState turns it on
  // once on-device is opted in AND a vision model is ready; it never forces it
  // back off, so an explicit manual toggle-off is respected.
  const [visionOnDevice, setVisionOnDevice] = useState(false);
  // Pending server-fallback offer after a local snapshot analysis declined;
  // frames only go to the server from here on an explicit click.
  const [visionFallback, setVisionFallback] = useState<VisionFallbackOffer | null>(null);

  const edge = useEdgeState({ agentId: agent?.id, sending, setVisionOnDevice });
  const attach = useAttachments(agent);
  const face = useFaceRecognition(agent);
  const voice = useVoiceSession({ agent, turns, setSpeakingTurnIndex });

  // The agent this component is CURRENTLY showing a transcript for. The
  // component is never unmounted on agent switch, so an in-flight send started
  // against the previous agent can still resolve after the switch. Every async
  // continuation compares its own captured agent id against this ref before
  // touching `turns`/`sessionId`/`sending`. Kept in sync by the reset effect below.
  const activeAgentIdRef = useRef(agent?.id);
  // Request X-ray: snapshot ids already attributed to an earlier turn.
  const seenRequestIdsRef = useRef<Set<string>>(new Set());
  // Client-minted stable id per assistant turn - streamChat returns only text
  // + a session id, so this is what the RL feedback control keys on.
  const turnSeq = useRef(0);
  // The in-flight server streamChat call, if any. Shared across the normal
  // send, the vision-fallback button and the watch loop, so exactly one can
  // ever be in flight at a time.
  const [chatController, setChatController] = useState<RequestLifecycleController<string | null> | null>(null);
  const { snapshot: chatSnapshot, cancel: cancelChat } = useRequestLifecycle(chatController);
  // "View in full screen" for a long reply: the turn shown in the overlay, and
  // the trigger button so focus can return to it on close.
  const [expandedTurnIndex, setExpandedTurnIndex] = useState<number | null>(null);
  const expandedReplyTriggerRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    // Must be set before anything else in this effect: it's what tells any
    // still-in-flight send from the previous agent that it's now stale, so its
    // eventual resolution can't corrupt the transcript loaded below.
    activeAgentIdRef.current = agent?.id;
    const saved = loadPlaygroundState(agent?.id);
    setSessionId(saved?.sessionId || null);
    setTurns(saved?.turns || []);
    // A restored transcript's turns are all already "old" - mark every one as
    // already-auto-spoken so switching to an agent with history doesn't
    // immediately read its last message aloud.
    voice.autoSpokenIndexRef.current = (saved?.turns?.length || 0) - 1;
    // A restored transcript opens collapsed to its tail, scrolled to the latest turn.
    transcript.resetWindow();
    setError(null);
    attach.setAttachment(null);
    attach.setAttachError(null);
    setStagedFrames([]);
    setVisionFallback(null);
    // A pending "ask before falling back" prompt belongs to the previous
    // agent's turn - it must not linger into the new agent's composer.
    edge.setPendingFallbackAsk(null);
    edge.fallbackAskResolverRef.current = null;
    voice.stopSpeaking();
    // The previous agent's in-flight request can no longer mutate `turns`, but
    // `sending`/`edgePhase` are this component's own composer state - reset
    // them so the new agent's composer isn't stuck disabled.
    setSending(false);
    edge.setEdgePhase(null);
    edge.setEdgeWarmingPercent(null);
    // The drawer shows the previous agent's settings; close it rather than leave a stale panel over the new one
    // (not on the first run: a drawer opened at mount stays open).
    if (agentSwitchSeenRef.current) setRunSettingsOpen(false);
    setRunToolsState(null);
    agentSwitchSeenRef.current = true;
    setWaitingApprovals(0);
    // Re-running on agent change is the whole point.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent?.id]);

  usePersistTranscript(agent?.id, sessionId, turns, sending);

  // The live status chips: derived from the turn in flight (its step events), the approvals waiting for a person
  // and the request lifecycle's retry counter. An on-device turn has its own indicator, so it adds no chips.
  const lastTurn = turns[turns.length - 1];
  const liveSteps = lastTurn?.role === "assistant" && lastTurn.pending ? lastTurn.steps : undefined;
  const liveHasText = Boolean(lastTurn?.role === "assistant" && lastTurn.pending && lastTurn.content);
  const liveChips = useMemo(
    () =>
      edge.edgePhase
        ? []
        : deriveLiveChips({
            steps: liveSteps,
            sending,
            hasText: liveHasText,
            waitingApprovals,
            approvalContinuing: approvalContinuation.continuing,
            attempt: chatSnapshot?.attempt,
            maxAttempts: chatSnapshot?.maxAttempts,
          }),
    [
      edge.edgePhase,
      liveSteps,
      sending,
      liveHasText,
      waitingApprovals,
      approvalContinuation.continuing,
      chatSnapshot?.attempt,
      chatSnapshot?.maxAttempts,
    ]
  );

  function newSession() {
    setSessionId(null);
    setTurns([]);
    artifacts.reset();
    transcript.resetWindow();
    setError(null);
    attach.setAttachment(null);
    attach.setAttachError(null);
    setStagedFrames([]);
    setVisionFallback(null);
    // The turn that was being read aloud is about to vanish from the
    // transcript - stop the audio too rather than leave a voice with no way to silence it.
    voice.stopSpeaking();
    // A reset is a fresh conversation for the on-device model too.
    if (edgeEnabled()) resetEdgeSession(agent?.id);
  }

  const ctx: PlaygroundContext = {
    agent,
    t,
    activeAgentIdRef,
    stickToBottomRef,
    seenRequestIdsRef,
    turnSeq,
    turns,
    setTurns,
    sessionId,
    setSessionId,
    sending,
    setSending,
    setError,
    input,
    setInput,
    inputRef,
    attachment: attach.attachment,
    setAttachment: attach.setAttachment,
    attaching: attach.attaching,
    stagedFrames,
    setStagedFrames,
    visionOnDevice,
    setVisionFallback,
    setFaceIntroNotice: face.setFaceIntroNotice,
    setChatController,
    setEdgePhase: edge.setEdgePhase,
    setEdgeWarmingPercent: edge.setEdgeWarmingPercent,
    edgeAbortControllerRef: edge.edgeAbortControllerRef,
    lastEdgeModelRef: edge.lastEdgeModelRef,
    askToFallback: edge.askToFallback,
    edgeOptIn: edge.edgeOptIn,
    matchRecognizedFace: face.matchRecognizedFace,
    withRecognizedName: face.withRecognizedName,
    tryEnrollFaceFromNameIntroduction: face.tryEnrollFaceFromNameIntroduction,
    runToolNames,
  };
  const { sendServerTurn } = useServerTurn(ctx);
  const { sendViaEdge, sendFramesLocally } = useEdgeTurns(ctx);
  const send = useSendActions(ctx, { sendServerTurn, sendViaEdge, sendFramesLocally });

  function forkRerun(keepTurnCount: number, message: string) {
    // The fork was confirmed for the agent this closure belongs to. If another
    // agent has been selected since, its transcript is not the one to cut.
    if (activeAgentIdRef.current !== agent?.id) return;
    voice.stopSpeaking();
    setError(null);
    voice.autoSpokenIndexRef.current = keepTurnCount - 1;
    setTurns((prev) => prev.slice(0, keepTurnCount));
    // Same reason as newSession(): the on-device model keeps its own session copy.
    if (edgeEnabled()) resetEdgeSession(agent?.id);
    // Busy from this moment, not from after sendServerTurn's baseline reads:
    // until `sending` is true the composer and the camera watch loop are free
    // to start a turn of their own, which would interleave with the re-send.
    // The cut transcript is stored here because the persist effect is paused
    // while `sending`.
    savePlaygroundState(agent?.id, sessionId, turns.slice(0, keepTurnCount));
    setSending(true);
    void sendServerTurn(message, null, []);
  }

  return (
    <ArtifactLayout enabled={artifacts.enabled} pane={artifacts.pane}>
      <Card className="relative flex h-full min-h-[32rem] flex-col gap-0 overflow-hidden p-0">
        <PlaygroundHeader
          agent={agent}
          sessionId={sessionId}
          sending={sending}
          autoSpeakEnabled={voice.autoSpeakEnabled}
          setAutoSpeakEnabled={voice.setAutoSpeakEnabled}
          stopSpeaking={voice.stopSpeaking}
          onOpenLab={() => setLabOpen(true)}
          onNewSession={newSession}
          runSettingsOpen={runSettingsOpen}
          onToggleRunSettings={() => setRunSettingsOpen((open) => !open)}
          runSettingsButtonRef={runSettingsButtonRef}
          runToolsLimited={runToolNames !== null}
        />
        <EdgeOptInBanner
          edgeTier0Ready={edge.edgeTier0Ready}
          edgeOptIn={edge.edgeOptIn}
          edgeBannerDismissed={edge.edgeBannerDismissed}
        />
        {/* First-run on-device setup (tier 1 / WebLLM): self-gating; renders
          nothing once a model is ready, when the device can't run one, or after dismissal. */}
        <OnDeviceSetupNudge />
        <SessionSidebar
          agent={agent}
          sessionId={sessionId}
          onSessionId={setSessionId}
          capabilities={attach.capabilities}
          capsLoading={attach.capsLoading}
          edgeOptIn={edge.edgeOptIn}
          edgeVisionReady={edge.edgeVisionReady}
          busy={busy || attach.attaching}
          stagedFrames={stagedFrames}
          onStagedFramesChange={setStagedFrames}
          visionOnDevice={visionOnDevice}
          onVisionOnDeviceChange={setVisionOnDevice}
          handlers={{
            runWatchTurn: send.runWatchTurn,
            appendLocalTurn: send.appendLocalVisionTurn,
            matchRecognizedFace: face.matchRecognizedFace,
            withRecognizedName: face.withRecognizedName,
          }}
        />
        <LiveStatusBar chips={liveChips} />
        <MessageList
          agent={agent}
          sessionId={sessionId}
          sending={sending}
          approvalContinuation={approvalContinuation}
          error={error}
          turns={turns}
          visibleTurns={transcript.visibleTurns}
          windowStart={transcript.windowStart}
          hiddenCount={transcript.hiddenCount}
          canCollapse={transcript.canCollapse}
          bodyRef={transcript.bodyRef}
          onScroll={transcript.handleBodyScroll}
          onShowEarlier={transcript.showEarlier}
          onCollapseEarlier={transcript.collapseEarlier}
          edgePhase={edge.edgePhase}
          edgeWarmingPercent={edge.edgeWarmingPercent}
          chatSnapshot={chatSnapshot}
          artifacts={artifacts}
          voice={{
            speakingTurnIndex,
            speakPhase: voice.speakPhase,
            speakSeekable: voice.speakSeekable,
            play: (index, text) => void voice.handleSpeak(index, text),
            stop: voice.stopSpeaking,
            skip: voice.skipAhead,
          }}
          setInput={setInput}
          retryUserText={send.retryUserText}
          retryFailedTurn={send.retryFailedTurn}
          onResendHosted={(message) => void sendServerTurn(message, null, [])}
          onExpand={(index, trigger) => {
            expandedReplyTriggerRef.current = trigger;
            setExpandedTurnIndex(index);
          }}
          onWaitingApprovalsChange={setWaitingApprovals}
        />
        <Composer
          agent={agent}
          input={input}
          onInputChange={setInput}
          inputRef={inputRef}
          onSend={(e) => void send.handleSend(e)}
          sending={busy}
          attaching={attach.attaching}
          stagedFrames={stagedFrames}
          chatSnapshot={chatSnapshot}
          onCancelChat={cancelChat}
          edgePhase={edge.edgePhase}
          edgeWarmingPercent={edge.edgeWarmingPercent}
          onCancelEdgeTurn={edge.cancelEdgeTurn}
          faceIntroNotice={face.faceIntroNotice}
          attachError={attach.attachError}
          pendingFallbackAsk={edge.pendingFallbackAsk}
          fallbackAskResolverRef={edge.fallbackAskResolverRef}
          visionFallback={visionFallback}
          onDismissVisionFallback={() => setVisionFallback(null)}
          onSendVisionFallback={(message, frames) => void sendServerTurn(message, null, frames)}
          attachment={attach.attachment}
          onRemoveAttachment={attach.removeAttachment}
          routingPreview={edge.routingPreview}
          tier1PreparingNotice={edge.tier1PreparingNotice}
          canAttach={attach.canAttach}
          fileInputRef={attach.fileInputRef}
          acceptTypes={attach.acceptTypes}
          capabilities={attach.capabilities}
          onAttachFile={attach.handleAttachFile}
        />
        {/* Hidden sink for synthesized speech; the spoken text is already shown in the transcript. */}
        {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
        <audio ref={voice.speakAudioRef} className="hidden" />
        <LabOverlayHost
          agent={agent}
          sessionId={sessionId}
          turns={turns}
          busy={busy || attach.attaching}
          expandedTurnIndex={expandedTurnIndex}
          onCloseExpanded={() => {
            setExpandedTurnIndex(null);
            expandedReplyTriggerRef.current?.focus();
          }}
          labOpen={labOpen}
          onCloseLab={() => setLabOpen(false)}
          onCancelTurn={() => {
            cancelChat();
            edge.cancelEdgeTurn();
          }}
          onForkRerun={forkRerun}
        />
        {runSettingsOpen && agent && (
          <RunSettingsDrawer
            agent={agent}
            sessionId={sessionId}
            onClose={() => setRunSettingsOpen(false)}
            returnFocusRef={runSettingsButtonRef}
            runTools={runToolNames}
            onRunToolsChange={(names) => setRunToolsState(names ? { agentId: agent.id, names } : null)}
          />
        )}
      </Card>
    </ArtifactLayout>
  );
}
