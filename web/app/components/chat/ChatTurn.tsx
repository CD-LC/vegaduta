"use client";

import { Badge } from "../ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";
import { cn } from "../../lib/utils";
import MediaCard from "../MediaCard";
import FailedReplyBubble from "../FailedReplyBubble";
import { retryableUserText } from "../../lib/failedTurn";
import { GenerativeUiRenderer } from "../generative-ui/GenerativeUiRenderer";
import { extractUiSpec } from "../generative-ui/extractUiSpec";
import { TurnAttachments } from "../ChatAttachments";
import { edgeFallbackLabel, edgeFallbackShortLabel } from "../../lib/edge/capabilities";
import RequestInspector from "../agents/RequestInspector";
import TurnStopNotice from "../agents/TurnStopNotice";
import AgentActivityTrace from "../agents/AgentActivityTrace";
import SaveAsEvalCaseButton from "../../agents/quality/SaveAsEvalCaseButton";
import { ChatBranchTurnBar, EditMessageForm } from "../ChatBranchControls";
import TurnFeedback from "../agents/TurnFeedback";
import { useTranslations } from "../../lib/i18n";

/** One transcript entry (user bubble, assistant bubble or failed reply) and its per-turn extras. */
export default function ChatTurn({
  turn,
  i,
  turns,
  agent,
  sessionId,
  sending,
  branching,
  artifacts,
  toolCalls,
  retryFailedTurn,
  runTurn,
  setInput,
}: {
  turn: any;
  i: number;
  turns: any[];
  agent: any;
  sessionId: string | null;
  sending: boolean;
  branching: any;
  artifacts: any;
  toolCalls: any[];
  retryFailedTurn: (index: number) => void;
  runTurn: (message: string, attachments: any[]) => unknown;
  setInput: (updater: (prev: string) => string) => void;
}) {
    const tHC = useTranslations("hcComponentsChatChatTurn");
  // Generative UI: an assistant turn may embed a ```json_ui block (the
  // contract in components/generative-ui/schema.ts). Extracted per
  // render - cheap for ordinary turns (one regex, no match => null).
  const uiBlock = turn.role === "assistant" && turn.content ? extractUiSpec(turn.content) : null;
  const displayText = uiBlock ? uiBlock.text : turn.content;
  if (turn.role === "assistant" && turn.failed) {
    return (
      <div className="flex flex-col items-start gap-1" key={i}>
        <span className="sr-only">{turn.role}</span>
        <span className="text-xs font-medium text-muted-foreground" aria-hidden="true">
          {agent.name}
        </span>
        <FailedReplyBubble
          text={turn.content}
          onRetry={retryableUserText(turns, i) != null ? () => retryFailedTurn(i) : undefined}
          retryDisabled={sending}
        />
      </div>
    );
  }
  return (
    <div
      className={cn(
        "flex flex-col gap-1",
        turn.role === "user" ? "items-end" : "items-start",
        branching.available && "group"
      )}
      key={i}
    >
      <span className="sr-only">{turn.role}</span>
      {turn.role !== "user" && (
        <span className="text-xs font-medium text-muted-foreground" aria-hidden="true">
          {agent.name}
        </span>
      )}
      {turn.role === "user" && <TurnAttachments attachments={turn.attachments} align="end" />}
      {branching.editingIndex === i && turn.role === "user" && (
        <EditMessageForm
          initialText={turn.content}
          busy={sending}
          onSave={(text) => branching.submitEdit(i, text)}
          onCancel={branching.cancelEdit}
        />
      )}
      <div
        className={cn(
          "max-w-[85%] rounded-xl px-4 py-2.5 text-sm leading-relaxed",
          turn.role === "user"
            ? "whitespace-pre-wrap break-words bg-primary text-primary-foreground"
            : "border border-border bg-secondary/40 text-foreground",
          branching.editingIndex === i && turn.role === "user" && "hidden"
        )}
      >
        {/* Only the ASSISTANT side goes through MediaCard: the user's own
          text renders exactly as typed (no markdown surprises), while
          model output needs clickable links + real formatting (via
          RichText internally) plus real inline media for any
          generated-artifact URL it references - see MediaCard.tsx's
          stopgap notice. */}
        {turn.role === "user"
          ? turn.content
          : displayText
            ? artifacts.renderAssistantText(displayText, i, sending && i === turns.length - 1, (segment: string) => (
                <MediaCard text={segment} trace={toolCalls} />
              ))
            : sending && i === turns.length - 1
              ? "…"
              : ""}
      </div>
      {/* Generative UI (json_ui spec in the turn's text) renders beside
        the bubble, not inside it - the card owns its own width/border
        and its actions re-enter this panel via runTurn, so a rendered
        button is the same kind of send as typing. Gated on `sending`
        so a mid-stream click can't interleave a second turn into the
        one still arriving. */}
      {uiBlock && (
        <div className="max-w-[85%]">
          <GenerativeUiRenderer
            spec={uiBlock.spec}
            errors={uiBlock.errors}
            onAction={(action) => {
              if (!sending) void runTurn(action.message, []);
            }}
          />
        </div>
      )}
      {/* Opt-in activity trace (2026-08-17) - renders nothing at all for
        a turn that produced no events (server steps OR on-device tool
        rounds), so the panel is unchanged everywhere it was before. */}
      {turn.role === "assistant" && (
        <AgentActivityTrace
          steps={turn.steps}
          appHost={
            agent?.id
              ? {
                  agentId: agent.id,
                  onAddMessage: (text: string) => setInput((prev: string) => (prev ? `${prev}\n${text}` : text)),
                }
              : undefined
          }
        />
      )}
      {turn.role === "assistant" && <TurnStopNotice steps={turn.steps} />}
      {turn.role === "assistant" && turn.requests?.length > 0 && <RequestInspector snapshots={turn.requests} />}
      {/* Provenance marker - same honesty rule as AgentPlayground's
        edgeTier badge: say plainly when a reply ran on-device, and
        when it didn't despite on-device being selected, say why
        instead of leaving "still uses platform llm" undiagnosable. */}
      {turn.role === "assistant" && turn.edgeTier != null && (
        <Tooltip>
          <TooltipTrigger asChild>
            <Badge
              variant="secondary"
              className="cursor-default px-2 py-0 text-[10px] uppercase tracking-wide text-muted-foreground"
            >
              {tHC("hcComponentsChatChatTurn.s2")} {turn.edgeTier})
            </Badge>
          </TooltipTrigger>
          <TooltipContent>
            {turn.edgeTier === 0
              ? "Answered by the browser's built-in on-device model"
              : "Answered by a downloaded on-device model"}
          </TooltipContent>
        </Tooltip>
      )}
      {turn.role === "assistant" && turn.edgeTier == null && turn.edgeFallbackReason && (
        /* Short reason stays always-visible on the badge itself
         (edgeFallbackShortLabel); the full sentence (edgeFallbackLabel)
         moves from a raw title= to the styled tooltip. */
        <Tooltip>
          <TooltipTrigger asChild>
            <Badge variant="state" state="var(--state-warning)" className="cursor-default">
              {tHC("hcComponentsChatChatTurn.s1")} {edgeFallbackShortLabel(turn.edgeFallbackReason)}
            </Badge>
          </TooltipTrigger>
          <TooltipContent>{edgeFallbackLabel(turn.edgeFallbackReason)}</TooltipContent>
        </Tooltip>
      )}
      {/* Evals hook: only on a finished hosted turn that has a ref the
        backend can resolve (an on-device reply never reached the
        server, so there is nothing persisted to import). The
        preceding user turn is passed along as the case's question. */}
      {turn.role === "assistant" && turn.content && turn.ref && !(sending && i === turns.length - 1) && (
        <SaveAsEvalCaseButton
          agentId={agent.id}
          sessionId={sessionId}
          turnRef={turn.ref}
          userMessage={turns[i - 1]?.role === "user" ? turns[i - 1].content : undefined}
          agentResponse={turn.content}
        />
      )}
      {/* Chat branching (app.chat.branching.enabled): edit / regenerate /
        version switcher, and the 1-5 star + thumbs feedback the
        Playground already has. All of it renders only when the server
        reports the flag on, so a deployment without it is unchanged. */}
      {branching.available && turn.messageId && branching.editingIndex !== i && !sending && (
        <ChatBranchTurnBar
          role={turn.role}
          siblingIndex={turn.siblingIndex ?? 0}
          siblingCount={turn.siblingCount ?? 1}
          busy={branching.switching}
          onEdit={() => branching.startEdit(i)}
          onRegenerate={() => branching.regenerate(i)}
          onSwitch={(delta) => void branching.switchSibling(i, delta)}
        />
      )}
      {branching.enabled &&
        turn.role === "assistant" &&
        turn.content &&
        turn.ref &&
        !(sending && i === turns.length - 1) && (
          <TurnFeedback key={turn.ref} agentId={agent.id} sessionId={sessionId} turnRef={turn.ref} />
        )}
    </div>
  );
}
