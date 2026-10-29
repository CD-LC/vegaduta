"use client";

import { RotateCcw } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";
import RunCostCard from "../agents/RunCostCard";
import ChatExportMenu from "../ChatExportMenu";

/** Title row: agent name, session id, run cost, export, and "Clear context". */
export default function ChatHeader({
  agent,
  sessionId,
  sending,
  onNewSession,
}: {
  agent: { id: string; name: string };
  sessionId: string | null;
  sending: boolean;
  onNewSession: () => void;
}) {
  const t = useTranslations("chatPanel");
  return (
    <header className="flex items-center justify-between gap-3 border-b border-border px-6 py-4">
      <div className="min-w-0 space-y-0.5">
        <h2 className="truncate text-base font-semibold tracking-tight text-foreground">
          {t("titlePrefix", { name: agent.name })}
        </h2>
        <span className="block truncate text-xs text-muted-foreground" title={sessionId || undefined}>
          {sessionId ? t("sessionPrefix", { sessionId }) : t("noSessionYet")}
        </span>
        <RunCostCard agentId={agent.id} sessionId={sessionId} turnInFlight={sending} className="-ms-1.5" />
        <ChatExportMenu agentId={agent.id} sessionId={sessionId} className="-ms-1.5" />
      </div>
      {/* "Clear context" (user ask 2026-08-07) - same newSession handler as
        the old New-session control: a null sessionId makes the next
        streamChat start a fresh server session, and resetEdgeSession drops
        the on-device model's local context. Inline English on purpose -
        newer surfaces skip the fragile en.json. */}
      <Tooltip>
        <TooltipTrigger asChild>
          <Button type="button" variant="ghost" size="sm" onClick={onNewSession}>
            <RotateCcw aria-hidden="true" size={12} className="size-3" />
            Clear context
          </Button>
        </TooltipTrigger>
        <TooltipContent>
          Start a fresh conversation — clears on-device and server-side session memory for this chat
        </TooltipContent>
      </Tooltip>
    </header>
  );
}
