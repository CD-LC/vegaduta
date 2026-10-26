"use client";

import { useState } from "react";
import { MonitorPlay, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import DesktopMonitor from "./DesktopMonitor";
import { useDesktopAvailability } from "./useDesktopAvailability";

// Persistent live-screen dock: a floating pill at the bottom-right of the console that expands
// into a small always-on-top window streaming the person's screen (~1 fps). It is the "watch the
// agent work" surface that stays visible while they keep chatting or navigate elsewhere - the
// in-turn panel (DesktopMonitorPanel) is the same feed anchored to the reply that is driving the
// actions.
//
// Lifecycle: renders nothing until the desktop feature answers (same probe as the cards); the
// stream polls only while the dock is OPEN - a collapsed pill costs one availability check per
// page load and nothing per second. Monitor unavailable shows inside the open window, never
// collapses the dock silently mid-watch.

export default function DesktopScreenDock() {
  const t = useTranslations("desktopAutomation");
  const available = useDesktopAvailability();
  const [open, setOpen] = useState(false);
  if (!available) return null;
  return (
    <div className="fixed bottom-4 right-4 z-40" data-testid="desktop-screen-dock">
      {open ? (
        <div className="w-80 overflow-hidden rounded-lg border border-border bg-card shadow-lg">
          <div className="flex items-center gap-2 border-b border-border px-3 py-1.5">
            <MonitorPlay className="size-3.5 text-muted-foreground" aria-hidden="true" />
            <span className="text-xs font-medium text-foreground">{t("screenDockTitle")}</span>
            <button
              type="button"
              className="ml-auto text-muted-foreground hover:text-foreground"
              aria-label={t("screenDockClose")}
              onClick={() => setOpen(false)}
            >
              <X className="size-3.5" aria-hidden="true" />
            </button>
          </div>
          <div className="p-2">
            <DesktopMonitor active live />
          </div>
        </div>
      ) : (
        <button
          type="button"
          className="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3 py-1.5 text-xs font-medium text-foreground shadow-md hover:bg-accent"
          aria-label={t("screenDockOpen")}
          onClick={() => setOpen(true)}
        >
          <MonitorPlay className="size-3.5" aria-hidden="true" />
          {t("screenDockTitle")}
        </button>
      )}
    </div>
  );
}
