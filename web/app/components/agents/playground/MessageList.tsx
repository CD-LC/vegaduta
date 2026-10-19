"use client";

import type { Dispatch, MutableRefObject, SetStateAction } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import type { RequestLifecycleSnapshot } from "../../../lib/requestLifecycle";
import { sessionUsedBrowser } from "../../../lib/browserTakeover";
import FailedReplyBubble from "../../FailedReplyBubble";
import { Button } from "../../ui/button";
import type { useArtifactsPane } from "../../artifacts/useArtifactsPane";
import PendingToolApprovals from "../PendingToolApprovals";
import BrowserTakeoverPanel from "../BrowserTakeoverPanel";
import TurnStopNotice from "../TurnStopNotice";
import { AssistantTurn, type TurnVoiceControls } from "./AssistantTurn";
import { UserTurn } from "./UserTurn";
import type { PlaygroundAgent, PlaygroundTurn } from "./types";

/**
 * The scrolling transcript: the empty states, the "show earlier" / "collapse"
 * window controls, one row per rendered turn (user, failed reply, assistant),
 * then the pending tool approvals, browser takeover and the live error line.
 */
export function MessageList({
  agent,
  sessionId,
  sending,
  error,
  turns,
  visibleTurns,
  windowStart,
  hiddenCount,
  canCollapse,
  bodyRef,
  onScroll,
  onShowEarlier,
  onCollapseEarlier,
  edgePhase,
  edgeWarmingPercent,
  chatSnapshot,
  artifacts,
  voice,
  setInput,
  retryUserText,
  retryFailedTurn,
  onResendHosted,
  onExpand,
}: {
  agent: PlaygroundAgent | null;
  sessionId: string | null;
  sending: boolean;
  error: string | null;
  turns: PlaygroundTurn[];
  visibleTurns: PlaygroundTurn[];
  /** Absolute index of the first rendered turn (per-turn identity is keyed on absolute indices). */
  windowStart: number;
  hiddenCount: number;
  canCollapse: boolean;
  bodyRef: MutableRefObject<HTMLDivElement | null>;
  onScroll: () => void;
  onShowEarlier: () => void;
  onCollapseEarlier: () => void;
  edgePhase: "checking" | "warming" | "generating" | null;
  edgeWarmingPercent: number | null;
  chatSnapshot: RequestLifecycleSnapshot | null;
  artifacts: ReturnType<typeof useArtifactsPane>;
  voice: TurnVoiceControls;
  setInput: Dispatch<SetStateAction<string>>;
  retryUserText: (index: number) => string | null | undefined;
  retryFailedTurn: (index: number) => void;
  onResendHosted: (userMessage: string) => void;
  onExpand: (index: number, trigger: HTMLButtonElement) => void;
}) {
  const t = useTranslations("agentPlayground");
  return (
    <div className="flex-[7] space-y-5 overflow-y-auto px-5 py-5" ref={bodyRef} onScroll={onScroll}>
      {!agent && <p className="text-sm text-muted-foreground">{t("emptyNoAgent")}</p>}
      {agent && turns.length === 0 && (
        <p className="text-sm text-muted-foreground">{t("emptySelectAgent", { name: agent.name })}</p>
      )}
      {/* Older turns are hidden, not discarded - they are still in `turns`,
        still written to localStorage, and still in the agent's own
        server-side session memory. Using the shared <Button> rather than a
        bare <button> keeps clear of globals.css's blanket button padding. */}
      {hiddenCount > 0 && (
        <div className="flex flex-col items-center gap-1">
          <Button type="button" variant="ghost" size="sm" onClick={onShowEarlier}>
            <ChevronUp size={12} />
            {hiddenCount === 1 ? t("showEarlierOne") : t("showEarlier", { count: hiddenCount })}
          </Button>
          <span className="text-xs text-muted-foreground">{t("historyKeptNote")}</span>
        </div>
      )}
      {canCollapse && (
        <div className="flex justify-center">
          <Button type="button" variant="ghost" size="sm" onClick={onCollapseEarlier}>
            <ChevronDown size={12} />
            {t("collapseEarlier")}
          </Button>
        </div>
      )}
      {visibleTurns.map((turn, offset) => {
        const i = windowStart + offset;
        if (turn.role === "user") return <UserTurn key={i} turn={turn} />;
        if (turn.failed) {
          return (
            <div className="flex flex-col items-start gap-1.5" key={i}>
              <FailedReplyBubble
                text={turn.content}
                onRetry={retryUserText(i) != null ? () => retryFailedTurn(i) : undefined}
                retryDisabled={sending}
              />
            </div>
          );
        }
        return (
          <AssistantTurn
            key={i}
            turn={turn}
            index={i}
            turns={turns}
            agent={agent}
            sessionId={sessionId}
            sending={sending}
            edgePhase={edgePhase}
            edgeWarmingPercent={edgeWarmingPercent}
            chatSnapshot={chatSnapshot}
            artifacts={artifacts}
            voice={voice}
            onAddMessage={(text) => setInput((prev) => (prev ? `${prev}\n${text}` : text))}
            onResendHosted={onResendHosted}
            onExpand={onExpand}
          />
        );
      })}
      <PendingToolApprovals agentId={agent?.id} sessionId={sessionId} turnInFlight={sending} />
      {/* Human takeover of the agent's browser (captcha / 2FA / login): self-hiding unless core has it on. */}
      <BrowserTakeoverPanel
        agentId={agent?.id}
        sessionId={sessionId}
        usedBrowser={turns.some((turn) => sessionUsedBrowser(turn.steps))}
        onHandedBack={(message) => setInput((current) => current || message)}
      />
      {/* A failed reply already shows its reason in the transcript (FailedReplyBubble): do not say it twice. */}
      {error && !(turns[turns.length - 1]?.failed && turns[turns.length - 1].content === error) && (
        <p className="text-sm text-destructive">{error}</p>
      )}
      <TurnStopNotice message={error} />
    </div>
  );
}
