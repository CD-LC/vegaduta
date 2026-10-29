"use client";

import { useState } from "react";
import { Eye, EyeOff } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import DesktopMonitor from "./DesktopMonitor";
import DesktopRecipesCard from "./DesktopRecipesCard";
import { useDesktopAvailability } from "./useDesktopAvailability";

// Per-agent desktop automation: recorded desktop tasks (core /api/desktop/recipes), mounted in the
// workbench next to AgentBrowserAutomation. Core ships the feature OFF; until a deployment switches it
// on the availability probe fails quietly and this renders NOTHING (not even while probing), so a
// workbench without the feature is exactly the workbench it was.
//
// "Watch live" streams the whole screen through /api/desktop/monitor/frame - the pane for seeing the
// agent work in real time (a run in progress, a recipe replay, or anything else on screen), separate
// from the per-run monitor inside each recipe's run panel. It needs app.assistant.desktop.monitor
// enabled; when it is off the pane answers its own "monitor unavailable" line.

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
  const [watching, setWatching] = useState(false);
  if (!available) return null;

  return (
    <div className="mb-4" data-testid="agent-desktop-automation">
      <div className="mb-1 flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">{t("title")}</span>
        <Button
          type="button"
          size="sm"
          variant="outline"
          aria-pressed={watching}
          onClick={() => setWatching((w) => !w)}
        >
          {watching ? (
            <EyeOff className="me-1 size-3.5" aria-hidden="true" />
          ) : (
            <Eye className="me-1 size-3.5" aria-hidden="true" />
          )}
          {watching ? t("stopWatching") : t("watchLive")}
        </Button>
      </div>
      <p className="mb-2 text-xs text-muted-foreground">{t("subtitle")}</p>
      {watching && <DesktopMonitor active live />}
      <DesktopRecipesCard agentId={agentId} locked={locked} editLockedTooltip={editLockedTooltip} />
    </div>
  );
}
