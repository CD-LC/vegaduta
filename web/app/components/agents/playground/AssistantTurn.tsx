"use client";

import { useTranslations } from "../../../lib/i18n";
import type { RequestLifecycleSnapshot } from "../../../lib/requestLifecycle";
import { RequestStatusIndicator } from "../../reliability/RequestStatusIndicator";
import TurnProgress from "../../chat/TurnProgress";
import MediaCard from "../../MediaCard";
import TurnFeedback from "../TurnFeedback";
import RequestInspector from "../RequestInspector";
import SaveRunAsRecipe from "../SaveRunAsRecipe";
import TurnStopNotice from "../TurnStopNotice";
import { StopLimitAction } from "./StopLimitAction";
import { TurnOutcomeNotice } from "./TurnOutcomeNotice";
import { deriveTurnOutcome } from "./turnOutcome";
import AgentActivityTrace from "../AgentActivityTrace";
import SaveAsEvalCaseButton from "../../../agents/quality/SaveAsEvalCaseButton";
import ViewTurnLink from "../ViewTurnLink";
import type { useArtifactsPane } from "../../artifacts/useArtifactsPane";
import { ToolCallCard } from "./ToolCallCard";
import { TurnMetricsRow } from "./TurnMetricsRow";
import { TurnAudioControls } from "./TurnAudioControls";
import type { PlaygroundAgent, PlaygroundTurn } from "./types";

/** The read-aloud transport the shell owns (useVoiceSession), narrowed to what a turn row needs. */
export interface TurnVoiceControls {
  speakingTurnIndex: number | null;
  speakPhase: "idle" | "loading" | "playing";
  speakSeekable: boolean;
  play: (index: number, text: string) => void;
  stop: () => void;
  skip: () => void;
}

/** One agent reply: header with live status and provenance badges, live trace, text, metrics, read-aloud and feedback controls. */
export function AssistantTurn({
  turn,
  index,
  turns,
  agent,
  sessionId,
  sending,
  edgePhase,
  edgeWarmingPercent,
  chatSnapshot,
  artifacts,
  voice,
  onAddMessage,
  onResendHosted,
  onExpand,
}: {
  turn: PlaygroundTurn;
  index: number;
  turns: PlaygroundTurn[];
  agent: PlaygroundAgent | null;
  sessionId: string | null;
  sending: boolean;
  edgePhase: "checking" | "warming" | "generating" | null;
  edgeWarmingPercent: number | null;
  chatSnapshot: RequestLifecycleSnapshot | null;
  artifacts: ReturnType<typeof useArtifactsPane>;
  voice: TurnVoiceControls;
  onAddMessage: (text: string) => void;
  onResendHosted: (userMessage: string) => void;
  onExpand: (index: number, trigger: HTMLButtonElement) => void;
}) {
  const t = useTranslations("agentPlayground");
  const i = index;
  const outcome = deriveTurnOutcome(turn);
  return (
        <div className="flex flex-col items-start gap-1.5">
          <div className="flex items-center gap-2">
            <span className="flex size-5 items-center justify-center rounded-full bg-primary/10 text-xs text-primary">
              ◕
            </span>
            <span className="text-xs font-medium text-foreground">{agent?.name || t("agentFallback")}</span>
            {turn.pending &&
              (turn.edgeAttempt ? (
                <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                  <span className="size-1.5 animate-pulse rounded-full bg-primary" aria-hidden="true" />
                  {edgePhase === "warming"
                    ? edgeWarmingPercent != null
                      ? t("edgeWarmingUpPercent", { percent: edgeWarmingPercent })
                      : t("edgeWarmingUp")
                    : edgePhase === "generating"
                      ? t("edgeGenerating")
                      : t("edgeChecking")}
                </span>
              ) : (turn.steps?.length ?? 0) > 0 ? (
                // The server has said where the turn is: phase, tool or
                // reasoning, with a clock. Compact and read-only (the
                // composer below has the one shared Cancel control).
                <TurnProgress steps={turn.steps} hasText={Boolean(turn.content)} compact />
              ) : chatSnapshot ? (
                // Real CONNECTING/PROCESSING/STREAMING state instead of a
                // static "thinking" label - no cancel button here (the
                // composer below has the one shared Cancel control) so
                // this stays a compact, per-turn read-only indicator.
                <RequestStatusIndicator snapshot={chatSnapshot} className="text-xs" />
              ) : (
                <span className="text-xs text-muted-foreground">{t("thinking")}</span>
              ))}
            {/* Provenance badges: "watch" marks a turn the watch
              loop sent automatically; "on-device" marks a reply the local
              model produced (frames never left the browser). */}
            {turn.watch && (
              <span
                className="rounded-full border border-border px-1.5 py-px text-[11px] uppercase tracking-wide text-muted-foreground"
                title={t("watchBadgeTitle")}
              >
                {t("watchBadge")}
              </span>
            )}
            {turn.onDevice && (
              <span
                className="rounded-full border border-border px-1.5 py-px text-[11px] uppercase tracking-wide text-muted-foreground"
                title={t("onDeviceBadgeTitle")}
              >
                {t("onDeviceBadge")}
              </span>
            )}
          </div>
          {/* Live activity trace (server step events + on-device tool
            rounds) - opens itself while the turn is still pending so a
            slow multi-tool turn is visibly working. The post-hoc
            tool_call_log block below is the fallback for a turn whose
            core sent no step events, so the two never show together. */}
          {turn.steps?.length > 0 && (
            <AgentActivityTrace
              steps={turn.steps}
              defaultOpen={turn.pending}
              appHost={
                agent?.id
                  ? {
                      agentId: agent.id,
                      onAddMessage: (text: string) =>
                        onAddMessage(text),
                    }
                  : undefined
              }
            />
          )}
          {/* "Save this run as a recipe": self-hiding unless recipes are on and this turn drove the browser. */}
          {!turn.pending && agent?.id && (
            <SaveRunAsRecipe agentId={agent.id} sessionId={sessionId} steps={turn.steps} />
          )}
          <TurnStopNotice steps={turn.steps} action={(kind) => <StopLimitAction agent={agent} kind={kind} />} />
          {!turn.pending && outcome && (
            <TurnOutcomeNotice
              outcome={outcome}
              agent={agent}
              canContinue={!sending && i === turns.length - 1}
              onContinue={() => onResendHosted(t("continueMessage"))}
            />
          )}
          {!(turn.steps?.length > 0) && turn.trace?.length > 0 && <ToolCallCard trace={turn.trace} />}
          {turn.requests && turn.requests.length > 0 && <RequestInspector snapshots={turn.requests} />}
          {/* Assistant output goes through MediaCard, which renders
            markdown (links, lists, code, bold - via RichText
            internally) AND detects the known generated-artifact URL
            shapes (image/audio/video), rendering those as real inline
            media with a download button instead of inert plain text -
            see MediaCard.tsx's stopgap notice. */}
          <div className="max-w-[85%] rounded-xl border border-border bg-secondary/40 px-4 py-2.5 text-sm leading-relaxed text-foreground">
            {artifacts.renderAssistantText(
              turn.content,
              i,
              !!turn.pending || (sending && i === turns.length - 1),
              (segment) => (
                <MediaCard text={segment} trace={turn.trace} />
              )
            )}
            {turn.pending && (
              <span
                className="ml-0.5 inline-block h-3.5 w-px animate-pulse bg-primary align-middle"
                aria-hidden="true"
              />
            )}
          </div>
          {!turn.pending && turn.content && (
            <div className="flex w-full max-w-[85%] flex-wrap items-center gap-x-2 gap-y-1">
              <TurnMetricsRow turn={turn} index={i} turns={turns} sending={sending} onResendHosted={onResendHosted} />
              <TurnAudioControls
                index={i}
                content={turn.content}
                speakingTurnIndex={voice.speakingTurnIndex}
                speakPhase={voice.speakPhase}
                speakSeekable={voice.speakSeekable}
                onPlay={voice.play}
                onStop={voice.stop}
                onSkip={voice.skip}
                onExpand={onExpand}
              />
            </div>
          )}
          {/* Reinforcement-learning feedback - only on a completed turn with
            real content, so the reward signal maps to a finished answer. */}
          {!turn.pending && turn.content && agent && turn.ref && (
            <div className="flex w-full max-w-[85%] flex-wrap items-center gap-x-3 gap-y-1">
              <TurnFeedback agentId={agent.id} sessionId={sessionId} turnRef={turn.ref} />
              {/* Evals hook - same "finished server turn with a ref" gate
                as the feedback control, for the same reason: the import
                resolves the persisted turn by that ref. The preceding
                user turn is the case's question. */}
              <SaveAsEvalCaseButton
                agentId={agent.id}
                sessionId={sessionId}
                turnRef={turn.ref}
                userMessage={turns[i - 1]?.role === "user" ? turns[i - 1].content : undefined}
                agentResponse={turn.content}
              />
              {/* Step-by-step timeline of this conversation's recorded turns (the journal is off by default;
                the page explains that when nothing was recorded). */}
              <ViewTurnLink agentId={agent.id} sessionId={sessionId} />
            </div>
          )}
        </div>
  );
}
