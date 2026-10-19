"use client";

import { Phone } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import CallPanel from "../agents/CallPanel";
import type { CallPanelAgent } from "../agents/call/callContext";
import AssistantOverlay from "./AssistantOverlay";

/**
 * Talk to the Assistant: the console's call panel (hold to talk, or a live call with optional camera)
 * in a sheet, bound to the Assistant's own session so what is said lands in the same conversation as
 * what is typed. The panel hangs up when the sheet closes; the pane then reloads the transcript.
 */
export default function CallOverlay({
  agent,
  sessionId,
  onSessionId,
  onClose,
}: {
  agent: CallPanelAgent & { name?: string };
  sessionId: string | null;
  onSessionId: (id: string) => void;
  onClose: () => void;
}) {
  const t = useTranslations("assistant");
  return (
    <AssistantOverlay title={t("callTitle")} closeLabel={t("callClose")} onClose={onClose}>
      <div className="px-4 pb-4">
        <div className="mb-3 flex items-center gap-3 rounded-2xl bg-gradient-to-br from-primary/15 via-primary/5 to-transparent p-4">
          <span className="grid size-11 shrink-0 place-items-center rounded-full bg-primary text-primary-foreground shadow-sm">
            <Phone size={18} aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-foreground">{agent.name ?? t("title")}</p>
            <p className="text-xs text-muted-foreground">{t("callHint")}</p>
          </div>
        </div>
        <CallPanel agent={agent} sessionId={sessionId} onSessionId={onSessionId} />
      </div>
    </AssistantOverlay>
  );
}
