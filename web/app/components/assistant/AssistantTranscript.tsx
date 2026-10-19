"use client";

import { useEffect, useRef } from "react";
import Link from "next/link";
import { CalendarClock, CloudUpload, Copy, RotateCcw, Search, Volume2 } from "lucide-react";
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
import type { ToolApprovalOutcome } from "../agents/toolApprovalMatching";
import TurnStopNotice from "../agents/TurnStopNotice";
import ConnectCard from "./ConnectCard";
import RequestInspector from "../agents/RequestInspector";
import TurnFeedback from "../agents/TurnFeedback";
import WorkedForBlock from "./WorkedForBlock";
import { needsBackground } from "./dispatch";
import { isSpeaking, speakText, stopSpeaking } from "./speakText";
import { stripReasoning } from "./replyText";
import TaskCard, { type TaskCardHandlers } from "./TaskCard";
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
  onApprovalDecided,
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
  /** A parked tool call on this thread was decided (approved and ran, failed, or rejected). */
  onApprovalDecided?: (outcome: ToolApprovalOutcome) => void;
  /** Show the model's raw reasoning in the work trace (default hides it). */
  showThinking?: boolean;
  /** Read each finished reply aloud (on-device Kokoro, browser voice as fallback). */
  speakReplies?: boolean;
}) {
  const logRef = useRef<HTMLDivElement>(null);
  const stickRef = useRef(true);

  // Follow the conversation only while the reader is already at the bottom.
  useEffect(() => {
    const el = logRef.current;
    if (!el || !stickRef.current) return;
    el.scrollTop = el.scrollHeight;
  }, [turns]);

  function onScroll() {
    const el = logRef.current;
    if (!el) return;
    stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < STICK_TO_BOTTOM_PX;
  }

  return (
    <div
      ref={logRef}
      onScroll={onScroll}
      className="min-h-0 flex-1 overflow-y-auto px-3 py-5 sm:px-6"
      role="log"
      aria-live="polite"
      aria-relevant="additions"
    >
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-4">
        {restoredNote && <p className="text-center text-xs text-muted-foreground">{restoredNote}</p>}
        {turns.length === 0 && (
          <AssistantLaunchpad onPrompt={onSuggestion} />
        )}
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
            onDecided={onApprovalDecided}
          />
        )}
      </div>
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
      <div className="flex flex-col items-end gap-1">
        <span className="sr-only">{t("roleYou")}</span>
        <TurnAttachments attachments={turn.attachments} align="end" />
        <div className="asst-bubble-user max-w-[85%] whitespace-pre-wrap px-4 py-2.5 text-sm leading-relaxed">
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
  return (
    <div className="flex flex-col items-start gap-1">
      <span className="text-xs font-medium text-muted-foreground">{agentName}</span>
      {turn.note && <p className="text-xs text-muted-foreground">{turn.note}</p>}
      <WorkedForBlock
        steps={turn.steps}
        startedAt={turn.startedAt}
        endedAt={live ? null : (turn.endedAt ?? turn.at)}
        showThinking={showThinking}
      />
      {(text || live) && (
        <div
          className={cn(
            "asst-bubble-agent max-w-[85%] py-2.5 pl-5 pr-4 text-sm leading-relaxed text-foreground"
          )}
        >
          {text ? <MediaCard text={text} /> : <span aria-label={t("thinking")}>…</span>}
        </div>
      )}
      {/* Request X-ray: the exact prompt, sampling options and tool list this
        reply's model calls carried — the same inspector the playground shows. */}
      {turn.requests && turn.requests.length > 0 && <RequestInspector snapshots={turn.requests} />}
      {turn.usage && (
        <p className="px-1.5 text-[11px] text-muted-foreground">
          {formatReplyMetrics(turn.startedAt, turn.endedAt, turn.usage, t)}
        </p>
      )}
      {text && !live && (
        <div className="flex items-center gap-0.5">
          <button
            type="button"
            onClick={() => {
              try {
                void navigator.clipboard?.writeText(text);
              } catch {
                // Clipboard unavailable - nothing to do.
              }
            }}
            aria-label={t("copyReply")}
            title={t("copyReply")}
            className="inline-flex items-center gap-1 px-1.5 py-0.5 text-xs text-muted-foreground hover:text-foreground"
          >
            <Copy size={12} aria-hidden="true" />
            {t("copyReply")}
          </button>
          <button
            type="button"
            onClick={() => (isSpeaking() ? stopSpeaking() : void speakText(text))}
            aria-label={t("listenToReply")}
            title={t("listenToReply")}
            className="inline-flex items-center gap-1 px-1.5 py-0.5 text-xs text-muted-foreground hover:text-foreground"
          >
            <Volume2 size={12} aria-hidden="true" />
            {t("listenToReply")}
          </button>
          {/* Regenerate = resend this reply's user message. Only on the last
            turn: retry() drops everything after the pair it replays, so an
            older turn would silently delete the rest of the thread. */}
          {isLast && index > 0 && (
            <button
              type="button"
              onClick={() => onRetry(index)}
              disabled={sending}
              aria-label={t("regenerate")}
              title={t("regenerate")}
              className="inline-flex items-center gap-1 px-1.5 py-0.5 text-xs text-muted-foreground hover:text-foreground disabled:opacity-50"
            >
              <RotateCcw size={12} aria-hidden="true" />
              {t("regenerate")}
            </button>
          )}
        </div>
      )}
      {agentId && turn.ref && !live && (
        <TurnFeedback agentId={agentId} sessionId={sessionId} turnRef={turn.ref} />
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
