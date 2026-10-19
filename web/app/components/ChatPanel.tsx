"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import { ToolCallsSection, MemoriesSection } from "./chat/ChatSidePanels";
import ChatTurn from "./chat/ChatTurn";
import ChatComposerStatus from "./chat/ChatComposerStatus";
import { useChatSideData } from "./chat/useChatSideData";
import ChatHeader from "./chat/ChatHeader";
import { useToolState } from "./chat/useToolState";
import { tryEdgeTurn } from "./chat/edgeTurn";
import { useComposerAttachments } from "./chat/useComposerAttachments";
import { useFallbackAsk } from "./chat/useFallbackAsk";
import { attachRequestSnapshots } from "./chat/requestSnapshots";
import ComposerLimitNote, { composerLimitProps, isOverComposerLimit } from "./ComposerLimitNote";
import { Button } from "./ui/button";
import OnDeviceSetupNudge from "./edge/OnDeviceSetupNudge";
import { Card } from "./ui/card";
import { Input } from "./ui/input";
import { isCancelledError, retryableUserText } from "../lib/failedTurn";
import { AttachButton, splitForSend, type PendingAttachment } from "./ChatAttachments";
import { ArtifactLayout, useArtifactsPane } from "./artifacts/useArtifactsPane";
// Honest tool state (2026-09-12). Until now this panel rendered nothing at all
// when a turn used no tools AND nothing when the tool surface was broken, so
// the two were indistinguishable. Two surfaces answer, and they become
// unavailable for different reasons, so they are counted separately and never
// summed into one number that hides which half is missing:
//   listLocalTools(caps) — in-page handlers. No account, no server, no HTTP.
//   fetchEdgeTools(agentId) — the account-scoped edge subset, resolved null
//     for any failure at all (see its javadoc), and only ASKED when
//     canUseServerTools says this session was entitled to it — so a signed-out
//     console states the ordinary fact instead of reporting a failure.
// The on-device leg of a turn (routeTurn, EDGE_ONLY policy, fallback ask)
// lives in chat/edgeTurn.ts; this panel only resets the edge session.
import { edgeEnabled } from "../lib/edge/capabilities";
import { resetEdgeSession } from "../lib/edge/tierRouter";
// Warm-up problem fix (2026-08-07) + Finding #8 (2026-08-10 edge/WebLLM audit
// follow-up): this surface had NO prewarm call AND no warm/loading-percent
// feedback at all — a turn sent from here always paid the full cold
// engine-load cost on the first send (up to tier1.ts's own 60s
// ENGINE_ACQUIRE_TIMEOUT_MS ceiling) with only a bare "sending" boolean, so a
// cold load read as a hung/broken UI. useEdgeWarmState is the shared hook
// extracted from AgentPlayground.tsx's equivalent inline state/effects (see
// that hook's own header comment for the full rationale and the scope note
// on why AgentPlayground itself isn't repointed at it in this change).
import { useEdgeWarmState } from "../lib/edge/useEdgeWarmState";
// WebMCP (E-2b): while this chat is open, the agent's edge-safe tools are
// published to navigator.modelContext so a browser-side agent can call them
// as typed tools. No-op everywhere the API doesn't exist. See webmcp.ts.
import { useWebMcpTools } from "../lib/edge/useWebMcpTools";
// Universal Request Lifecycle wiring for the server chat path (see
// requestLifecycle.ts's header + api.ts's streamChat javadoc) - live
// connecting/streaming state, a real Cancel button, and a distinct message
// per failure class instead of the bare "sending" boolean this used to be.
import { useRequestLifecycle, type RequestLifecycleController } from "../lib/requestLifecycle";
// Resumable streams (core: ResumableChatStreamService): a quiet reconnecting line, and page-load restore of a reply
// this tab left in flight (api/chatResume.ts, lib/resumableStream.ts).
import  { type StreamReconnectingState } from "./StreamReconnectingNotice";
import { loadPendingTurn } from "../lib/resumableStream";
// Opt-in activity trace (2026-08-17), extracted 2026-09-09 into the shared
// AgentActivityTrace so this panel and AgentPlayground render one trace from
// one vocabulary - server step events off the SSE stream AND the on-device
// tool events routeTurn's onToolCall fires (badged "on-device"). See that
// component's header for why the trace is a nicety that must never fail a
// turn, and why unknown event types render generically rather than crash.
//
// Why this replaces nothing: the separate "Tools used" listing this panel
// already refreshes after a turn (refreshToolCalls -> listToolCalls) is a
// server-side history read that only arrives once the turn is over. These
// events arrive DURING the turn, off the same SSE stream, which is the whole
// point - a person watching a slow multi-tool turn can see it working instead
// of a motionless "…".
import PendingToolApprovals from "./agents/PendingToolApprovals";
import BrowserTakeoverPanel from "./agents/BrowserTakeoverPanel";
import { sessionUsedBrowser } from "../lib/browserTakeover";
import TurnStopNotice from "./agents/TurnStopNotice";
import { normalizeServerStep, type ActivityStep } from "./agents/AgentActivityTrace";
import { useWarmLocalModel } from "../lib/useWarmLocalModel";
// Evals hook (2026-09-09): "Save as eval case" under a finished hosted turn.
// Self-hiding when the core lacks the import route - see its header.
import { errorMessage } from "../lib/errors";
// Chat branching: per-message edit / regenerate / version switcher, behind app.chat.branching.enabled.
import { useChatBranching } from "./useChatBranching";
// Memory in chat: project switcher (active_project block) + link to /memory.
import { ActiveProjectSwitcher } from "./memory/ChatMemoryControls";
import BrandText from "./BrandText";

export default function ChatPanel({
  agent,
  initialInput,
  onTurnComplete,
}: {
  agent: any;
  /** Optional composer text (the /start flow). Placed in the box, never sent - a send spends model budget, so the person presses Send. */
  initialInput?: string;
  /** Called once after a turn that produced a reply (hosted or on-device). */
  onTurnComplete?: () => void;
}) {
  const t = useTranslations("chatPanel");
  useWebMcpTools(agent?.id ?? null);
  const [sessionId, setSessionId] = useState(null);
  const seenRequestIdsRef = useRef<Set<string>>(new Set());
  const [turns, setTurns] = useState([]);
  const [input, setInput] = useState("");
  // Latest callback without re-running the send path when the parent re-renders.
  const onTurnCompleteRef = useRef(onTurnComplete);
  useEffect(() => void (onTurnCompleteRef.current = onTurnComplete), [onTurnComplete]);
  // Pre-fill, applied when the agent (or the supplied text) changes.
  useEffect(() => {
    if (initialInput) setInput(initialInput);
  }, [agent?.id, initialInput]);
  const [sending, setSending] = useState(false);
  // Artifacts pane (flag-gated, default off - see lib/artifacts/artifactsFlag.ts). Inert when off.
  const composerRef = useRef<HTMLInputElement>(null);
  const artifacts = useArtifactsPane({
    turns,
    sending,
    onAskPrefill: (text) => {
      setInput(text);
      composerRef.current?.focus();
    },
  });
  const [error, setError] = useState(null);
  const {
    modalities,
    attachments,
    setAttachments,
    attachError,
    setAttachError,
    attaching,
    handleFilesPicked,
    removeAttachment,
  } = useComposerAttachments(agent, sending);
  const logRef = useRef(null);
  // The in-flight server streamChat call, if any - null while idle or while
  // an on-device (edge) reply is being answered instead (that path never
  // creates one). See useRequestLifecycle's own doc for the IDLE fallback.
  const [chatController, setChatController] = useState<RequestLifecycleController<string | null> | null>(null);
  const { snapshot: chatSnapshot, cancel: cancelChat } = useRequestLifecycle(chatController);
  // Set while a dropped stream is being resumed (or a reloaded page restores its reply); hides the request status
  // line so a recoverable drop never flashes "Failed".
  const [reconnecting, setReconnecting] = useState<StreamReconnectingState | null>(null);
  const tResume = useTranslations("chatResume");
  // Finding #8: real processing-stage + warm-up-percent feedback for an
  // in-flight on-device attempt (previously this surface had none at all -
  // see the import comment above). Same shared hook ChatPanel now uses in
  // place of AgentPlayground's inline copy of this exact state.
  const { edgePhase, edgeWarmingPercent, tier1PreparingNotice, beginEdgeAttempt, endEdgeAttempt } = useEdgeWarmState(
    agent?.id ?? null,
    sending
  );
  const { pendingFallbackAsk, askToFallback, answerFallbackAsk, resetFallbackAsk } = useFallbackAsk();
  // The tool round in flight right now, as a sentence. Cleared by the effect
  // below on `sending` falling — one reset point, rather than one beside each
  // of this function's several setSending(false) exits.
  const [liveToolCall, setLiveToolCall] = useState<string | null>(null);
  // When the in-flight turn was sent, for TurnProgress's clock.
  const [turnStartedAt, setTurnStartedAt] = useState<number | null>(null);
  // Load the local model (if this install has one) as soon as the panel opens, not on first send.
  useWarmLocalModel();
  // The tool-call log and the memory list under the chat are unbounded
  // record lists - same shared client-side pagination the other panels use.
  // (Placed above the `!agent` early return below so hook order is stable.)
  // Client-generated turn id, same shape AgentPlayground's turnSeq mints:
  // streamChat sends it up front so the backend records the turn under a
  // ref the "Save as eval case" import (and RL feedback) can later resolve.
  // Monotonic within the component; a new session is a fresh sequence anyway.
  const turnSeq = useRef(0);
  // True while THIS mounted panel has its own streamChat call in flight. The page-load restore below must not start a
  // second consumer of the same turn (switching agents away and back re-runs it while the original stream is alive).
  const liveStreamRef = useRef(false);
  // Chat branching (edit / regenerate / switch versions). Inert unless the server reports
  // app.chat.branching.enabled; placed above the `!agent` early return so hook order is stable.
  const branching = useChatBranching({
    agentId: agent?.id ?? null,
    sessionId,
    turns,
    setTurns,
    sending,
    setSending,
    setError,
    setSessionId,
    setChatController,
    onTurnComplete: () => onTurnCompleteRef.current?.(),
  });

  const toolState = useToolState(agent?.id ?? null);

  // One reset point for the in-flight tool line (see its declaration).
  useEffect(() => {
    if (!sending) setLiveToolCall(null);
  }, [sending]);

  // New agent selected - start a fresh conversation (Stage 3 session isolation).
  useEffect(() => {
    setSessionId(null);
    setTurns([]);
    setToolCalls([]);
    setMemories([]);
    setError(null);
    setAttachments([]);
    setAttachError(null);
    // A pending "ask before falling back" prompt belongs to the previous
    // agent's turn - it must not linger into the newly-selected agent's chat.
    resetFallbackAsk();
    // A new agent is a fresh conversation for the on-device model too - drop
    // its (append-only) local session so old context can't leak forward,
    // same rule AgentPlayground's resetConversation applies.
    if (edgeEnabled()) resetEdgeSession(agent?.id);
  }, [agent?.id]);

  // Resumable streams: a reply this tab left in flight (a reload, a crash, a tab the browser restored) is picked up
  // where it is - replayed from the buffer the server kept, then followed live to its end. Declared AFTER the
  // conversation reset above so it runs second on a fresh mount. The abort on unmount keeps the stored record (a
  // navigation away and back restores the reply too); see api/chatResume.ts#resumeChatTurn.
  useEffect(() => {
    const agentId = agent?.id;
    if (!agentId) return undefined;
    if (liveStreamRef.current) return undefined;
    const pending = loadPendingTurn(agentId);
    if (!pending) return undefined;
    const abort = new AbortController();
    let restoredText = "";
    const showReply = (text: string) => {
      restoredText = text;
      setTurns((t) => {
        const copy = [...t];
        copy[copy.length - 1] = { ...copy[copy.length - 1], role: "assistant", content: text };
        return copy;
      });
    };
    setSessionId(pending.sessionId);
    setTurns([
      { role: "user", content: pending.userMessage },
      { role: "assistant", content: "" },
    ]);
    setSending(true);
    void api
      .resumeChatTurn(agentId, pending, (chunk: string) => showReply(restoredText + chunk), undefined, {
        signal: abort.signal,
        onController: setChatController,
        onReconnecting: (s) =>
          setReconnecting(s ? { attempt: s.attempt, max: s.max, cancel: s.cancel, restoring: s.attempt === 1 } : null),
        onReconcile: showReply,
      })
      .then((newSessionId) => {
        if (abort.signal.aborted) return;
        if (newSessionId) setSessionId(newSessionId);
        setSending(false);
        setReconnecting(null);
        if (restoredText.trim()) onTurnCompleteRef.current?.();
        void refreshToolCalls();
        void refreshMemories();
      })
      .catch(() => {
        if (abort.signal.aborted) return;
        setSending(false);
        setReconnecting(null);
        setError(tResume("unrecoverable"));
        // Keep whatever was recovered; an empty assistant bubble is just noise.
        if (!restoredText) setTurns((t) => t.slice(0, -1));
      });
    return () => abort.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent?.id]);

  useEffect(() => {
    logRef.current?.scrollTo({ top: logRef.current.scrollHeight });
  }, [turns]);

  const { toolCalls, setToolCalls, memories, setMemories, refreshToolCalls, refreshMemories, handleForget } =
    useChatSideData(agent, sessionId);


  async function handleSend(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    // Enter submits even with Send disabled, so the length cap is re-checked here.
    if ((!input.trim() && attachments.length === 0) || !agent || attaching || isOverComposerLimit(input.length)) return;
    // ChatRequest.message is @NotBlank server-side - an attachment-only send
    // still needs non-empty text, same fallback posture as AgentPlayground.
    const userMessage = input.trim() ? input : "Please review the attached file(s).";
    const sentAttachments = attachments;
    setInput("");
    setAttachments([]);
    setAttachError(null);
    await runTurn(userMessage, sentAttachments);
  }

  // One user message in, one assistant turn out. Split from handleSend so a
  // generative-UI action (a rendered spec's button/form sends a message back
  // to the agent - see components/generative-ui/) goes through the exact same
  // turn pipeline: edge routing, streaming, step trace, eval refs.
  async function runTurn(userMessage: string, sentAttachments: PendingAttachment[]) {
    if (!agent) return;
    setError(null);
    setTurns((t) => [
      ...t,
      {
        role: "user",
        content: userMessage,
        // Display copies only (no base64 retained) - previewUrl keeps image
        // thumbnails renderable in the history.
        attachments: sentAttachments.map(({ fileName, mimeType, sizeBytes, previewUrl }) => ({
          fileName,
          mimeType,
          sizeBytes,
          previewUrl,
        })),
      },
    ]);
    setSending(true);
    setTurnStartedAt(Date.now());

    let assistantText = "";
    // This turn's activity trace, accumulated off the SSE stream (server
    // steps) and off routeTurn's onToolCall (on-device tool rounds). Kept in
    // a local rather than component state for the same reason assistantText
    // is: every event needs the full list so far, and reading that back out
    // of setTurns' async state would race with the token updates landing
    // beside it. Reassigned (never mutated) so each snapshot handed to
    // setTurns is a distinct array React can diff.
    let turnSteps: ActivityStep[] = [];
    const showSteps = (snapshot: ActivityStep[]) => {
      setTurns((t) => {
        const copy = [...t];
        copy[copy.length - 1] = { ...copy[copy.length - 1], role: "assistant", steps: snapshot };
        return copy;
      });
    };
    const publishSteps = () => showSteps(turnSteps);
    const turnRef = `turn-${Date.now()}-${turnSeq.current++}`;
    setTurns((t) => [...t, { role: "assistant", content: "" }]);
    const hostedMessage = await tryEdgeTurn(userMessage, sentAttachments, {
      agent,
      t,
      setTurns,
      setSending,
      setError,
      setLiveToolCall,
      publishSteps: showSteps,
      beginEdgeAttempt,
      endEdgeAttempt,
      askToFallback,
      onReplied: () => onTurnCompleteRef.current?.(),
      refreshToolCalls,
      refreshMemories,
    });
    if (hostedMessage === null) return;

    liveStreamRef.current = true;
    try {
      const newSessionId = await api.streamChat(
        agent.id,
        hostedMessage,
        sessionId,
        (chunk: string) => {
          assistantText += chunk;
          setTurns((t) => {
            const copy = [...t];
            copy[copy.length - 1] = { ...copy[copy.length - 1], role: "assistant", content: assistantText };
            return copy;
          });
        },
        // The stored sandbox data file, if one was attached (attachmentKey).
        splitForSend(sentAttachments).stored,
        // Sent up front so the backend records this turn under a ref the
        // "Save as eval case" import can resolve (same triple RL feedback
        // keys on: agent + session + turnRef).
        turnRef,
        // Only included in the POST body when non-empty - api.streamChat
        // drops the key entirely otherwise, keeping plain text turns
        // byte-identical to the pre-attachment payload.
        splitForSend(sentAttachments).inline,
        undefined,
        {
          onController: setChatController,
          // Resumable streams: remember the turn so a reload restores it, say so quietly while a dropped connection
          // is being resumed, and swap in the server's copy of a resumed reply if it differs from what streamed.
          resumable: true,
          persistPending: true,
          onReconnecting: (s) => setReconnecting(s ? { attempt: s.attempt, max: s.max, cancel: s.cancel } : null),
          onReconcile: (text: string) => {
            assistantText = text;
            setTurns((t) => {
              const copy = [...t];
              copy[copy.length - 1] = { ...copy[copy.length - 1], role: "assistant", content: text };
              return copy;
            });
          },
        },
        // Opt-in activity trace. Passing this is what sets stepEvents:true on
        // the request; the events arrive interleaved with the tokens above and
        // are attached to THIS assistant turn so the trace stays with the reply
        // it belongs to once the conversation scrolls on. normalizeServerStep
        // never throws, so a newer core's event types (run_started, plan,
        // guardrail, handoff...) render generically instead of breaking the turn.
        {
          onEvent: (event) => {
            // Heartbeats are liveness beats (see AgentPlayground's onEvent)
            // - they keep the wire alive but are not trace activity.
            if (event?.type === "heartbeat") return;
            turnSteps = [...turnSteps, normalizeServerStep(event, turnSteps.length + 1)];
            publishSteps();
          },
        }
      );
      // The ref only becomes actionable once the reply is complete and the
      // server has it persisted - stamp it now, not on the placeholder, so a
      // failed turn (dropped below) never shows a save action.
      setTurns((t) => {
        const copy = [...t];
        copy[copy.length - 1] = { ...copy[copy.length - 1], role: "assistant", ref: turnRef };
        return copy;
      });
      if (newSessionId) setSessionId(newSessionId);
      if (assistantText.trim()) onTurnCompleteRef.current?.();
      void attachRequestSnapshots(agent.id, newSessionId || sessionId, turnRef, seenRequestIdsRef.current, setTurns);
      void refreshToolCalls();
      void refreshMemories();
      // Chat branching: let the server number this exchange so it can be edited / regenerated.
      void branching.syncAfterTurn(newSessionId || sessionId);
    } catch (err) {
      // Direct feedback ("proper meaningful messages to consumer, not raw
      // http responses") - err.message is already a clean, plain-language
      // sentence (GlobalExceptionHandler#handleUpstreamProvider now returns
      // UpstreamProviderException#getConsumerMessage(), see its javadoc) -
      // appending "(upstream status 429)" on top of that undid the point,
      // showing a raw HTTP status code right next to the friendly text.
      setError(errorMessage(err));
      // A failed reply stays in the transcript as an error bubble ("Reply failed" + Try again) so it is still there
      // after a reload; a cancel (the person asked to stop) just drops the empty turn.
      const keepAsFailedBubble = !assistantText && !isCancelledError(err);
      setTurns(
        (t) =>
          keepAsFailedBubble
            ? [...t.slice(0, -1), { role: "assistant", content: errorMessage(err, ""), failed: true }]
            : t.slice(0, -1) // drop the empty assistant turn that never got a response
      );
      // Chips only clear on a SUCCESSFUL send - put them back so a failed
      // turn (413/415/400 from the backend included) can be fixed and
      // resent without re-picking every file.
      if (sentAttachments.length > 0) setAttachments(sentAttachments);
    } finally {
      liveStreamRef.current = false;
      setSending(false);
      setReconnecting(null);
    }
  }

  // "Try again" on a failed reply: drop the failed exchange and send the same message through the normal turn
  // pipeline again (only offered for a plain text message).
  function retryFailedTurn(index: number) {
    const text = retryableUserText(turns, index);
    if (text == null || sending) return;
    setTurns((t) => t.slice(0, index - 1));
    void runTurn(text, []);
  }

  function newSession() {
    setSessionId(null);
    setTurns([]);
    setError(null);
    setAttachments([]);
    setAttachError(null);
    if (edgeEnabled()) resetEdgeSession(agent?.id);
  }

  if (!agent) {
    return (
      <Card className="gap-0 p-6">
        <h2 className="text-base font-semibold tracking-tight text-foreground">
          <BrandText text={t("title")} />
        </h2>
        <div className="mt-6 flex min-h-40 items-center justify-center rounded-xl border border-dashed border-border bg-card/40">
          <p className="text-sm text-muted-foreground">{t("selectAgentPrompt")}</p>
        </div>
      </Card>
    );
  }

  return (
    <ArtifactLayout enabled={artifacts.enabled} pane={artifacts.pane}>
      <Card className="gap-0 overflow-hidden p-0">
        <ChatHeader agent={agent} sessionId={sessionId} sending={sending} onNewSession={newSession} />

        {/* First-run on-device setup (see AgentPlayground's identical mount). */}
        <OnDeviceSetupNudge className="px-6" />
        <ActiveProjectSwitcher agent={agent} className="px-6 pt-3" />

        {error && !(turns[turns.length - 1]?.failed && turns[turns.length - 1].content === error) && (
          <div
            role="alert"
            className="mx-6 mt-4 rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            {error}
          </div>
        )}
        <TurnStopNotice message={error} className="mx-6 mt-2 w-auto max-w-none" />

        <div
          className="max-h-[28rem] space-y-4 overflow-y-auto px-6 py-5"
          ref={logRef}
          role="log"
          aria-live="polite"
          aria-relevant="additions"
        >
          {turns.length === 0 && <p className="text-sm text-muted-foreground">{t("noMessagesYet")}</p>}
          {turns.map((turn, i) => (
            <ChatTurn
              key={i}
              turn={turn}
              i={i}
              turns={turns}
              agent={agent}
              sessionId={sessionId}
              sending={sending}
              branching={branching}
              artifacts={artifacts}
              toolCalls={toolCalls}
              retryFailedTurn={retryFailedTurn}
              runTurn={runTurn}
              setInput={setInput}
            />
          ))}
          <PendingToolApprovals agentId={agent.id} sessionId={sessionId} turnInFlight={sending} />
          <BrowserTakeoverPanel
            agentId={agent.id}
            sessionId={sessionId}
            usedBrowser={turns.some((turn: { steps?: { tool?: string }[] }) => sessionUsedBrowser(turn.steps))}
            onHandedBack={(message: string) => setInput((current: string) => current || message)}
          />
        </div>

        <div className="border-t border-border px-6 py-4">
          <ChatComposerStatus
            agent={agent}
            sending={sending}
            chatSnapshot={chatSnapshot}
            reconnecting={reconnecting}
            cancelChat={cancelChat}
            edgePhase={edgePhase}
            edgeWarmingPercent={edgeWarmingPercent}
            liveToolCall={liveToolCall}
            pendingFallbackAsk={pendingFallbackAsk}
            answerFallbackAsk={answerFallbackAsk}
            attachError={attachError}
            attaching={attaching}
            attachments={attachments}
            removeAttachment={removeAttachment}
            tier1PreparingNotice={tier1PreparingNotice}
            toolState={toolState}
            liveSteps={(turns[turns.length - 1] as { steps?: ActivityStep[] } | undefined)?.steps ?? null}
            liveStartedAt={turnStartedAt}
            liveHasText={Boolean((turns[turns.length - 1] as { content?: string } | undefined)?.content)}
          />
          <form onSubmit={handleSend} className="flex items-center gap-2">
            {modalities && (
              <AttachButton modalities={modalities} disabled={sending || attaching} onFiles={handleFilesPicked} />
            )}
            <Input
              ref={composerRef}
              type="text"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder={t("sendPlaceholder")}
              aria-label={t("sendPlaceholder")}
              {...composerLimitProps(input.length)}
              disabled={sending}
            />
            <Button type="submit" disabled={sending || attaching || isOverComposerLimit(input.length) || (!input.trim() && !attachments.length)}>
              {sending ? t("sending") : t("send")}
            </Button>
          </form>
          <ComposerLimitNote length={input.length} />
        </div>

        <div className="grid gap-px border-t border-border bg-border sm:grid-cols-2">
          <ToolCallsSection toolCalls={toolCalls} onRefresh={refreshToolCalls} />

          <MemoriesSection memories={memories} onRefresh={refreshMemories} onForget={handleForget} />
        </div>
      </Card>
    </ArtifactLayout>
  );
}
