"use client";

import { useTranslations } from "../../lib/i18n";
import DesktopRecipesCard from "./DesktopRecipesCard";
import { useDesktopAvailability } from "./useDesktopAvailability";

// Per-agent desktop automation: recorded desktop tasks (core /api/desktop/recipes), mounted in the
// workbench next to AgentBrowserAutomation. Core ships the feature OFF; until a deployment switches it
// on the availability probe fails quietly and this renders NOTHING (not even while probing), so a
// workbench without the feature is exactly the workbench it was.

export default function AgentDesktopAutomation({
  agentId,
  locked,
  editLockedTooltip,
}: {
  agentId: string;
  locked: boolean;
  editLockedTooltip?: string;
}) {
  const t = useTranslations("desktopAutomation");
  const available = useDesktopAvailability();
  if (!available) return null;

  return (
    <div className="mb-4" data-testid="agent-desktop-automation">
      <div className="mb-1 text-xs text-muted-foreground">{t("title")}</div>
      <p className="mb-2 text-xs text-muted-foreground">{t("subtitle")}</p>
      <DesktopRecipesCard agentId={agentId} locked={locked} editLockedTooltip={editLockedTooltip} />
    </div>
  );
}
