"use client";

import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { AttachmentChips, type PendingAttachment } from "../ChatAttachments";
import SandboxDataHint from "../SandboxDataHint";
import { RequestStatusIndicator } from "../reliability/RequestStatusIndicator";
import StreamReconnectingNotice from "../StreamReconnectingNotice";
import TurnProgress from "./TurnProgress";
import type { ActivityStep } from "../agents/AgentActivityTrace";
import type { ToolState } from "./toolStatus";

/**
 * Everything that sits between the log and the composer input: request
 * lifecycle, on-device phase, live tool round, fallback prompt, attachment
 * state and the "what can this agent reach" line. Purely presentational.
 */
export default function ChatComposerStatus(p: {
  agent: any;
  sending: boolean;
  chatSnapshot: any;
  reconnecting: any;
  cancelChat: () => void;
  edgePhase: string | null | undefined;
  edgeWarmingPercent: number | null | undefined;
  liveToolCall: string | null;
  pendingFallbackAsk: string | null;
  answerFallbackAsk: (confirmed: boolean) => void;
  attachError: string | null;
  attaching: boolean;
  attachments: PendingAttachment[];
  removeAttachment: (id: string) => void;
  tier1PreparingNotice: boolean;
  toolState: ToolState | null;
  /** The in-flight turn's step events, when it asked for them: switches the status row to TurnProgress. */
  liveSteps?: readonly ActivityStep[] | null;
  liveStartedAt?: number | null;
  /** True once any reply text has streamed for the in-flight turn. */
  liveHasText?: boolean;
}) {
  const t = useTranslations("chatPanel");
  const tAttach = useTranslations("sandboxDataHint");
  const {
    agent, sending, chatSnapshot, reconnecting, cancelChat, edgePhase, edgeWarmingPercent, liveToolCall,
    pendingFallbackAsk, answerFallbackAsk, attachError, attaching, attachments, removeAttachment,
    tier1PreparingNotice, toolState, liveSteps, liveStartedAt, liveHasText,
  } = p;
  // Once the server has said where the turn is (a `phase`/`thinking`/tool step event), the
  // phase line replaces the generic "Processing…": it names the stage and keeps a clock, which is
  // the difference between "working" and "hung" on a slow local model.
  const showProgress = sending && !reconnecting && (liveSteps?.length ?? 0) > 0;
  return (
    <>
  {/* Server chat path status - not shown while an on-device (edge)
    reply is in flight, since that path never sets chatController.
    Live CONNECTING/PROCESSING/STREAMING state plus a real Cancel
    button instead of a bare "sending" boolean with no way out. */}
  {showProgress && (
    <div className="mb-2">
      <TurnProgress steps={liveSteps} startedAt={liveStartedAt} hasText={liveHasText === true} onCancel={cancelChat} />
    </div>
  )}
  {sending && chatSnapshot && !reconnecting && !showProgress && (
    <div className="mb-2">
      <RequestStatusIndicator snapshot={chatSnapshot} onCancel={cancelChat} />
    </div>
  )}
  {sending && reconnecting && <StreamReconnectingNotice state={reconnecting} />}
  {/* Finding #8: real stage feedback for an in-flight on-device
    attempt - same "sending" indicator styling this panel already
    uses (RequestStatusIndicator's row above), just driven by
    edgePhase/edgeWarmingPercent instead of a bare boolean, so a cold
    engine load (up to tier1.ts's own 60s ceiling) reads as genuine
    progress instead of a hung UI. Mirrors AgentPlayground's
    equivalent indicator (same phase labels, same real percent). */}
  {sending && edgePhase && (
    <div className="mb-2 flex items-center gap-1.5 rounded-md border border-border bg-secondary/30 px-2.5 py-1.5 text-xs text-muted-foreground">
      <span className="size-1.5 animate-pulse rounded-full bg-primary" aria-hidden="true" />
      {edgePhase === "warming"
        ? edgeWarmingPercent != null
          ? t("edgeWarmingUpPercent", { percent: edgeWarmingPercent })
          : t("edgeWarmingUp")
        : edgePhase === "generating"
          ? t("edgeGenerating")
          : t("edgeChecking")}
    </div>
  )}
  {/* Which tool is running and what it was asked. Sits beside the
    edgePhase row rather than replacing it: the phase says the model is
    working, this says what it is working ON, and a long tool round is
    exactly when the two differ. */}
  {sending && liveToolCall && !showProgress && (
    <div className="mb-2 flex items-center gap-1.5 rounded-md border border-border bg-secondary/30 px-2.5 py-1.5 text-xs text-muted-foreground">
      <span className="size-1.5 animate-pulse rounded-full bg-primary" aria-hidden="true" />
      <span className="min-w-0 truncate">{liveToolCall}</span>
    </div>
  )}
  {/* Fix #6 (P1): the "ask me first" edge.fallbackPolicy prompt - same
    shape/timeout as CallPanel's own pendingFallbackAsk. */}
  {pendingFallbackAsk && (
    <div className="mb-2 flex flex-wrap items-center gap-2 rounded-lg border border-border bg-secondary/40 px-3 py-2 text-xs text-muted-foreground">
      <span className="min-w-0">{pendingFallbackAsk}</span>
      <Button type="button" variant="outline" size="sm" onClick={() => answerFallbackAsk(true)}>
        Send to platform
      </Button>
      <Button type="button" variant="ghost" size="sm" onClick={() => answerFallbackAsk(false)}>
        Cancel
      </Button>
    </div>
  )}
  {attachError && (
    <p className="mb-2 text-sm text-destructive" role="alert">
      {attachError}
    </p>
  )}
  <SandboxDataHint agent={agent} />
  {attaching && (
    <p className="mb-2 text-xs text-muted-foreground" role="status" data-testid="chat-attach-uploading">
      {tAttach("uploading")}
    </p>
  )}
  {attachments.length > 0 && (
    <div className="mb-2">
      <AttachmentChips attachments={attachments} onRemove={removeAttachment} disabled={sending || attaching} />
    </div>
  )}
  {/* Finding #8: honest "getting ready" notice for the window before
    any send has started - only while nothing is sending yet (once a
    send starts, the edgePhase indicator above is the live, accurate
    signal and this would be redundant/stale). Mirrors
    AgentPlayground's tier1PreparingNotice row. */}
  {!sending && agent && tier1PreparingNotice && (
    <p className="mb-1.5 text-xs text-muted-foreground">{t("edgePreparingOnDevice")}</p>
  )}
  {/* What this agent can reach, stated in the same muted register as the
    row above. Alongside the on-device readiness copy on purpose —
    "ready" and "ready to do what" are one thought. Renders only once
    both surfaces have settled, so it never flashes a wrong state. */}
  {toolState && (
    <p className="mb-1.5 text-xs text-muted-foreground">
      {toolState.kind === "both"
        ? t("toolsBoth", { local: toolState.local, server: toolState.server })
        : toolState.kind === "server-unavailable"
          ? t("toolsServerUnavailable", { local: toolState.local })
          : toolState.kind === "needs-model"
            ? t("toolsNeedModel", { local: toolState.local })
            : toolState.kind === "no-agent"
              ? t("toolsNoAgent", { local: toolState.local })
              : t("toolsOnDeviceOnly", { local: toolState.local })}
    </p>
  )}
    </>
  );
}
