"use client";

import { RotateCcw, Volume2, VolumeX } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "../../ui/tooltip";
import BrandText from "../../BrandText";
import RunCostCard from "../RunCostCard";
import AgentLabLauncher from "../lab/AgentLabLauncher";
import type { PlaygroundAgent } from "./types";

/** Title row: busy dot, agent name, run cost, Agent Lab launcher, mute toggle and "Clear context". */
export function PlaygroundHeader({
  agent,
  sessionId,
  sending,
  autoSpeakEnabled,
  setAutoSpeakEnabled,
  stopSpeaking,
  onOpenLab,
  onNewSession,
}: {
  agent: PlaygroundAgent | null;
  sessionId: string | null;
  sending: boolean;
  autoSpeakEnabled: boolean;
  setAutoSpeakEnabled: (on: boolean) => void;
  stopSpeaking: () => void;
  onOpenLab: () => void;
  onNewSession: () => void;
}) {
  const t = useTranslations("agentPlayground");
  return (
    <div
      data-testid="playground-header"
      className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 border-b border-border px-5 py-4"
    >
      <div className="flex min-w-0 max-w-full items-center gap-2.5">
        {/* Was a hardcoded emerald dot that meant "green" and nothing else —
          always the same whether a turn was in flight, idle, or no agent
          was even selected. Now the shared .state-dot system: it ANIMATES
          (expanding ring) only while a turn is genuinely running, so
          running vs. idle is distinguishable by motion, not only hue, and
          "no agent selected" reads as idle grey. */}
        <span
          className={`state-dot${sending ? " state-dot-live" : ""}`}
          style={
            {
              ["--state" as any]: sending
                ? "var(--state-running)"
                : agent
                  ? "var(--state-success)"
                  : "var(--state-idle)",
            } as any
          }
          title={sending ? "Running a turn" : agent ? "Ready" : "No agent selected"}
          aria-hidden="true"
        />
        <span
          data-testid="playground-title"
          className="shrink-0 text-sm font-semibold tracking-tight text-foreground"
        >
          <BrandText text={t("title")} />
        </span>
        <span data-testid="playground-agent-name" className="min-w-0 truncate text-xs text-muted-foreground">
          {agent ? t("testingPrefix", { name: agent.name }) : t("noAgentSelected")}
        </span>
      </div>
      <div data-testid="playground-actions" className="flex min-w-0 flex-wrap items-center gap-2">
        <RunCostCard agentId={agent?.id} sessionId={sessionId} turnInFlight={sending} showPlanNote />
        <AgentLabLauncher disabled={!agent} onOpen={onOpenLab} />
        {/* Mute-the-whole-playground control. Default is MUTED (ask
          2026-09-30); unmuting opts back into auto-speak for every
          reply. Stopping any in-flight playback on mute is deliberate -
          flipping this off should be instantly silent, not just
          suppress future auto-speak. */}
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => {
                const next = !autoSpeakEnabled;
                setAutoSpeakEnabled(next);
                if (!next) stopSpeaking();
              }}
              aria-label={autoSpeakEnabled ? t("muteRepliesTitle") : t("unmuteRepliesTitle")}
              aria-pressed={!autoSpeakEnabled}
            >
              {autoSpeakEnabled ? <Volume2 size={12} /> : <VolumeX size={12} />}
            </Button>
          </TooltipTrigger>
          <TooltipContent>{autoSpeakEnabled ? t("muteRepliesTitle") : t("unmuteRepliesTitle")}</TooltipContent>
        </Tooltip>
        {/* "Clear context" (user ask 2026-08-07: on-device context fills up
          every chat) - same newSession handler as the old Reset control:
          a null sessionId makes the next streamChat start a fresh server
          session, and resetEdgeSession drops the on-device model's local
          context. Inline English on purpose - newer surfaces skip the
          fragile en.json (see CLAUDE.md note). */}
        <Tooltip>
          <TooltipTrigger asChild>
            <Button type="button" variant="ghost" size="sm" onClick={onNewSession} disabled={!agent}>
              <RotateCcw size={12} className="size-3" />
              Clear context
            </Button>
          </TooltipTrigger>
          <TooltipContent>
            Start a fresh conversation — clears on-device and server-side session memory for this chat
          </TooltipContent>
        </Tooltip>
      </div>
    </div>
  );
}
