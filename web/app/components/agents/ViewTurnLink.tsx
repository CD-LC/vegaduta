"use client";

import Link from "next/link";
import { ListTree } from "lucide-react";
import { useTranslations } from "../../lib/i18n";

/** Route of the turn timeline; RequestInspector and chat messages link here. */
export function turnTimelineHref(agentId: string, sessionId: string | null | undefined, turnId?: string | null): string {
  const q = new URLSearchParams({ agentId });
  if (sessionId) q.set("sessionId", sessionId);
  if (turnId) q.set("turnId", turnId);
  return `/agents/turns?${q.toString()}`;
}

/**
 * "View turn": a link from a chat message to the step-by-step timeline of its session. With a turn id it opens that
 * turn; without one (the usual case: the browser has the session id, not the turn id) the timeline lists the
 * session's turns and opens the newest. Renders nothing without an agent and a session.
 */
export default function ViewTurnLink({ agentId, sessionId, turnId }: { agentId?: string | null; sessionId?: string | null; turnId?: string | null }) {
  const t = useTranslations("agentPlayground");
  if (!agentId || !sessionId) return null;
  return (
    <Link
      href={turnTimelineHref(agentId, sessionId, turnId)}
      className="inline-flex items-center gap-1 text-xs text-muted-foreground underline-offset-2 hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
      title={t("viewTurnTitle")}
    >
      <ListTree size={12} aria-hidden="true" />
      {t("viewTurn")}
    </Link>
  );
}
