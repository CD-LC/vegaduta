"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ArrowDown, CalendarClock, CloudUpload, Search } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { isBillingEnabled } from "../../lib/billing-flag";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import AssistantLaunchpad from "./AssistantLaunchpad";
import MediaCard from "../MediaCard";
import FailedReplyBubble from "../FailedReplyBubble";
import { GenerativeUiRenderer } from "../generative-ui/GenerativeUiRenderer";
import { extractUiSpec } from "../generative-ui/extractUiSpec";
import { TurnAttachments } from "../ChatAttachments";
import PendingToolApprovals from "../agents/PendingToolApprovals";
import ApprovalContinuationStatus from "../agents/ApprovalContinuationStatus";
import type { ApprovalContinuationWatch } from "../agents/useApprovalContinuation";
import TurnStopNotice from "../agents/TurnStopNotice";
import ConnectCard from "./ConnectCard";
import RequestInspector from "../agents/RequestInspector";
import TurnFeedback from "../agents/TurnFeedback";
import WorkedForBlock from "./WorkedForBlock";
import { needsBackground } from "./dispatch";
import { speakText } from "./speakText";
import { stripReasoning } from "./replyText";
import ReplyActions from "./ReplyActions";
import SourceChips from "./SourceChips";
import { replySources } from "./sources";
import TaskCard, { type TaskCardHandlers } from "./TaskCard";
import DesktopMonitorPanel from "../desktop/DesktopMonitorPanel";
import { usesDesktopTools } from "../desktop/SaveRunAsDesktopRecipe";
import type { AssistantHandoff, AssistantTurn } from "./assistantTypes";

const STICK_TO_BOTTOM_PX = 120;
/** The centre column: user and assistant entries, hand-off cards, this session's parked approvals. */
export default function AssistantTranscript({
  turns,
  sending,
  agentId,
  agentName,
  sessionId,
  canBackground,
  restoredNote,
  onRetry,
  onAction,
  onResumeHeld,
  onBackground,
  onSuggestion,
  approvalContinuation,
  showThinking = false,
  speakReplies = false,
  task,
}: {
  /** Quick Task cards' confirm / cancel / channel (useQuickTask); absent where the surface has no task mode. */
  task?: TaskCardHandlers;
  turns: AssistantTurn[];
  sending: boolean;
  agentId: string | null;
  agentName: string;
  sessionId: string | null;
  canBackground: boolean;
  restoredNote: string | null;
  onRetry: (index: number) => void;
  /** A generative-UI button or form sends a message back through the normal turn pipeline. */
  onAction: (message: string) => void;
  /** A connect card's "I connected it" (force false) or "answer without it" (force true). */
  onResumeHeld: (cardId: string, force: boolean) => void;
  /** "Keep going in the background" under a turn the step cap stopped. */
  onBackground: (index: number) => void;
  onSuggestion: (text: string) => void;
  /** Follows the Assistant carrying on after a parked tool call on this thread is decided (core continues the task itself). */
  approvalContinuation: ApprovalContinuationWatch;
  /** Show the model's raw reasoning in the work trace (default hides it). */
  showThinking?: boolean;
  /** Read each finished reply aloud (on-device Kokoro, browser voice as fallback). */
  speakReplies?: boolean;
}) {
  const t = useTranslations("assistant");
  const logRef = useRef<HTMLDivElement>(null);
  const stickRef = useRef(true);
  const [awayFromBottom, setAwayFromBottom] = useState(false);
  const lastTurn = turns[turns.length - 1];

  // A message the person just sent always brings the view down to it, wherever they had scrolled.
  useEffect(() => {
    if (lastTurn?.role === "user" || (sending && lastTurn?.content === "")) stickRef.current = true;
  }, [lastTurn?.id, lastTurn?.role, lastTurn?.content, sending]);

  // Follow the conversation (and the live status lines under it) only while the reader is at the bottom.
  useEffect(() => {
    const el = logRef.current;
    if (!el || !stickRef.current) return;
    el.scrollTop = el.scrollHeight;
    setAwayFromBottom(false);
  }, [turns, approvalContinuation.continuing, approvalContinuation.phase]);

  function onScroll() {
    const el = logRef.current;
    if (!el) return;
    stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < STICK_TO_BOTTOM_PX;
    setAwayFromBottom(!stickRef.current);
  }

  function jumpToLatest() {
    const el = logRef.current;
    if (!el) return;
    stickRef.current = true;
    const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    if (typeof el.scrollTo === "function") el.scrollTo({ top: el.scrollHeight, behavior: reduce ? "auto" : "smooth" });
    else el.scrollTop = el.scrollHeight;
    setAwayFromBottom(false);
  }

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      <div
        ref={logRef}
        onScroll={onScroll}
        className="asst-log min-h-0 flex-1 overflow-y-auto px-4 pb-8 pt-6 sm:px-6"
        role="log"
        aria-live="polite"
        aria-relevant="additions"
        aria-busy={sending || undefined}
        aria-label={t("conversationLabel")}
      >
        <div className="asst-column mx-auto flex w-full flex-col gap-6">
          {restoredNote && <p className="text-center text-xs text-muted-foreground">{restoredNote}</p>}
          {turns.length === 0 && <AssistantLaunchpad onPrompt={onSuggestion} />}
          {turns.map((turn, i) => (
            <TurnEntry
              key={turn.id}
              turn={turn}
              index={i}
              isLast={i === turns.length - 1}
              sending={sending}
              agentId={agentId}
              sessionId={sessionId}
              agentName={agentName}
              canBackground={canBackground}
              onRetry={onRetry}
              onAction={onAction}
              onResumeHeld={onResumeHeld}
              onBackground={onBackground}
              showThinking={showThinking}
              speakReplies={speakReplies}
              task={task}
            />
          ))}
          {agentId && (
            <PendingToolApprovals
              agentId={agentId}
              sessionId={sessionId}
              turnInFlight={sending}
              onDecided={(_outcome, requestId) => approvalContinuation.watch(requestId)}
            />
          )}
          <ApprovalContinuationStatus continuation={approvalContinuation} />
        </div>
      </div>
      {awayFromBottom && turns.length > 0 && (
        <button
          type="button"
          onClick={jumpToLatest}
          aria-label={t("jumpToLatest")}
          title={t("jumpToLatest")}
          data-testid="jump-to-latest"
          className="asst-jump absolute bottom-3 left-1/2 inline-flex size-9 -translate-x-1/2 items-center justify-center rounded-full border border-border bg-card text-muted-foreground shadow-md transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
        >
          <ArrowDown size={16} aria-hidden="true" />
        </button>
      )}
    </div>
  );
}

function TurnEntry({
  turn,
  index,
  isLast,
  sending,
  agentName,
  canBackground,
  onRetry,
  onAction,
  onResumeHeld,
  onBackground,
  showThinking,
  speakReplies,
  agentId,
  sessionId,
  task,
}: {
  turn: AssistantTurn;
  index: number;
  isLast: boolean;
  sending: boolean;
  agentId: string | null;
  sessionId: string | null;
  agentName: string;
  canBackground: boolean;
  onRetry: (index: number) => void;
  onAction: (message: string) => void;
  onResumeHeld: (cardId: string, force: boolean) => void;
  onBackground: (index: number) => void;
  showThinking: boolean;
  speakReplies: boolean;
  task?: TaskCardHandlers;
}) {
  const t = useTranslations("assistant");
  const live = sending && isLast && turn.endedAt == null;
  // Auto-speak only fires on a live->settled transition seen in THIS session —
  // a restored thread must not read its whole history aloud on open.
  const wasLiveRef = useRef(false);
  useEffect(() => {
    if (live) {
      wasLiveRef.current = true;
      return;
    }
    // Speak only on the live->settled edge: switching read-aloud on later must not replay a finished reply.
    if (!wasLiveRef.current) return;
    wasLiveRef.current = false;
    if (!speakReplies) return;
    if (turn.role !== "assistant" || turn.failed || turn.connect || turn.handoff || turn.task) return;
    const raw = stripReasoning(turn.content);
    const text = (extractUiSpec(raw)?.text ?? raw).trim();
    if (text) void speakText(text);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only the live flag marks the edge; the setting is read at that moment
  }, [live]);
  if (turn.role === "user") {
    return (
      <div className="asst-turn asst-enter flex flex-col items-end gap-1" data-role="user">
        <span className="sr-only">{t("roleYou")}</span>
        <TurnAttachments attachments={turn.attachments} align="end" />
        <div className="asst-bubble-user max-w-[85%] whitespace-pre-wrap break-words px-4 py-2.5 text-[0.9375rem] leading-relaxed">
          {turn.content}
        </div>
        {turn.note && <p className="text-xs text-muted-foreground">{turn.note}</p>}
      </div>
    );
  }
  if (turn.failed) {
    return (
      <div className="flex flex-col items-start gap-1">
        <span className="text-xs font-medium text-muted-foreground">{agentName}</span>
        <FailedReplyBubble
          text={turn.content}
          onRetry={index > 0 ? () => onRetry(index) : undefined}
          retryDisabled={sending}
        />
      </div>
    );
  }
  if (turn.task && task) {
    return (
      <div className="flex flex-col items-start gap-1">
        <span className="text-xs font-medium text-muted-foreground">{turn.task.preview?.agentName || agentName}</span>
        <TaskCard turn={turn} task={turn.task} busy={sending} handlers={task} />
      </div>
    );
  }
  if (turn.connect) {
    return (
      <div className="flex flex-col items-start gap-1">
        <span className="text-xs font-medium text-muted-foreground">{agentName}</span>
        <ConnectCard connect={turn.connect} busy={sending} onResume={(force) => onResumeHeld(turn.id, force)} />
      </div>
    );
  }
  if (turn.handoff) {
    return (
      <div className="flex flex-col items-start gap-1">
        <span className="text-xs font-medium text-muted-foreground">{agentName}</span>
        <HandoffCard handoff={turn.handoff} />
      </div>
    );
  }
  const shown = stripReasoning(turn.content);
  const uiBlock = shown ? extractUiSpec(shown) : null;
  const text = uiBlock ? uiBlock.text : shown;
  const offerBackground = canBackground && !live && needsBackground(turn.steps);
  const sources = replySources(turn.steps);
  return (
    <div
      className="asst-turn asst-enter flex flex-col items-start gap-1.5"
      data-role="assistant"
      data-latest={isLast || undefined}
    >
      <span className="text-xs font-medium text-muted-foreground">{agentName}</span>
      {turn.note && <p className="text-xs text-muted-foreground">{turn.note}</p>}
      <WorkedForBlock
        steps={turn.steps}
        startedAt={turn.startedAt}
        endedAt={live ? null : (turn.endedAt ?? turn.at)}
        showThinking={showThinking}
        hasText={!!text}
        lastEventAt={turn.lastEventAt ?? null}
      />
      {/* Live screen while the agent drives the desktop: the pane appears inside the turn the
        moment the first local_desktop_* step lands - "what is it doing right now" belongs where the
        action is, not buried in the agent workbench. Answers its own "monitor unavailable" line
        when app.assistant.desktop.monitor is off. */}
      {live && usesDesktopTools(turn.steps) && <DesktopMonitorPanel active />}
      {text && (
        <div
          className={cn(
            "asst-bubble-agent w-full text-[0.9375rem] leading-relaxed text-foreground",
            live && "asst-streaming"
          )}
        >
          <MediaCard text={text} />
        </div>
      )}
      {!live && <SourceChips sources={sources} />}
      {/* Request X-ray: the exact prompt, sampling options and tool list this
        reply's model calls carried — the same inspector the playground shows. */}
      {turn.requests && turn.requests.length > 0 && <RequestInspector snapshots={turn.requests} />}
      {((text && !live) || turn.usage || (agentId && turn.ref && !live)) && (
        <div className="flex w-full flex-wrap items-center gap-x-2 gap-y-1">
          {/* Regenerate = resend this reply's user message. Only on the last
            turn: retry() drops everything after the pair it replays, so an
            older turn would silently delete the rest of the thread. */}
          {text && !live && (
            <ReplyActions
              text={text}
              onRegenerate={isLast && index > 0 ? () => onRetry(index) : undefined}
              regenerateDisabled={sending}
            />
          )}
          {agentId && turn.ref && !live && (
            <div className="asst-actions">
              <TurnFeedback agentId={agentId} sessionId={sessionId} turnRef={turn.ref} />
            </div>
          )}
          {turn.usage && (
            <p className="asst-actions ms-auto px-1 text-[11px] text-muted-foreground">
              {formatReplyMetrics(turn.startedAt, turn.endedAt, turn.usage, t)}
            </p>
          )}
        </div>
      )}
      {uiBlock && (
        <div className="max-w-[85%]">
          <GenerativeUiRenderer
            spec={uiBlock.spec}
            errors={uiBlock.errors}
            onAction={(action) => {
              if (!sending) onAction(action.message);
            }}
          />
        </div>
      )}
      <TurnStopNotice steps={turn.steps} />
      {offerBackground && (
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="gap-1"
          onClick={() => onBackground(index)}
          disabled={sending}
        >
          <CloudUpload size={14} aria-hidden="true" />
          {t("keepGoingBackground")}
        </Button>
      )}
    </div>
  );
}

/** "2.4 s · 1,204 tokens · $0.0021 · ollama/llama3.2" — one compact line. */
function formatReplyMetrics(
  startedAt: number | null | undefined,
  endedAt: number | null | undefined,
  usage: NonNullable<AssistantTurn["usage"]>,
  t: (key: string, vars?: Record<string, unknown>) => string
): string {
  const parts: string[] = [];
  if (startedAt && endedAt) parts.push(t("metricsSeconds", { n: ((endedAt - startedAt) / 1000).toFixed(1) }));
  if (usage.totalTokens > 0) parts.push(t("metricsTokens", { n: usage.totalTokens.toLocaleString() }));
  // $ only where billing exists - on a personal/self-hosted install the
  // estimate is noise against a local model.
  if (isBillingEnabled() && usage.estimatedCostUsd > 0) parts.push(`$${usage.estimatedCostUsd.toFixed(4)}`);
  const engine = (usage.poweredBy ?? [])
    .map((e) => [e.providerName, e.model].filter(Boolean).join("/"))
    .filter(Boolean)
    .join(", ");
  if (engine) parts.push(engine);
  return parts.join(" · ");
}

function HandoffCard({ handoff }: { handoff: AssistantHandoff }) {
  const t = useTranslations("assistant");
  const Icon = handoff.kind === "job" ? CloudUpload : handoff.kind === "research" ? Search : CalendarClock;
  const titleKey =
    handoff.kind === "job" ? "handoffJob" : handoff.kind === "research" ? "handoffResearch" : "handoffSchedule";
  const followKey =
    handoff.kind === "job" ? "followJob" : handoff.kind === "research" ? "followResearch" : "followSchedule";
  return (
    <div
      className="w-full max-w-[85%] rounded-xl border border-border bg-secondary/40 px-4 py-3 text-sm"
      data-testid={`handoff-${handoff.kind}`}
    >
      <div className="flex items-center gap-2 font-medium text-foreground">
        <Icon size={16} className="shrink-0 text-primary" aria-hidden="true" />
        {t(titleKey)}
      </div>
      <p className="mt-1 truncate text-muted-foreground" title={handoff.title}>
        {handoff.title}
      </p>
      {handoff.detail && <p className="mt-0.5 text-xs text-muted-foreground">{handoff.detail}</p>}
      {handoff.href && (
        <Link
          href={handoff.href}
          className="mt-2 inline-block text-xs font-medium text-primary underline-offset-2 hover:underline"
        >
          {t(followKey)}
        </Link>
      )}
    </div>
  );
}
