"use client";

import { useState } from "react";
import { ChevronDown, ChevronRight, MonitorPlay } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import DesktopMonitor from "./DesktopMonitor";
import type { DesktopRecipeStepResult } from "../../lib/api/desktopRecipes";

// The live screen inside a chat turn, in a collapsible panel: open while a desktop action runs,
// collapsible for a person who wants the thread back. The monitor polls only while `active` AND the
// panel is open - collapsing stops the per-second screenshot captures, not just the rendering. The
// header explains what it is ("you are watching the agent's live screen") so the first desktop turn
// doesn't look like a mystery iframe.

export default function DesktopMonitorPanel({
  active,
  steps,
  defaultOpen = true,
}: {
  /** True while the desktop action is in flight - frames are polled only then, and only while open. */
  active: boolean;
  /** Step results of a finished replay, kept under the frame. */
  steps?: DesktopRecipeStepResult[] | null;
  defaultOpen?: boolean;
}) {
  const t = useTranslations("desktopAutomation");
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div
      className="mt-1.5 w-full max-w-[85%] rounded-md border border-border bg-card"
      data-testid="desktop-monitor-panel"
    >
      <button
        type="button"
        className="flex w-full items-center gap-2 px-3 py-1.5 text-start text-xs font-medium text-foreground"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <MonitorPlay className="size-3.5 text-muted-foreground" aria-hidden="true" />
        {t("monitorPanelTitle")}
        {open ? (
          <ChevronDown className="ms-auto size-3.5 text-muted-foreground" aria-hidden="true" />
        ) : (
          <ChevronRight className="ms-auto size-3.5 text-muted-foreground" aria-hidden="true" />
        )}
      </button>
      {open && (
        <div className="px-3 pb-2">
          <p className="text-[11px] text-muted-foreground">{t("monitorPanelHint")}</p>
          <DesktopMonitor active={open && active} live steps={steps} />
        </div>
      )}
    </div>
  );
}
