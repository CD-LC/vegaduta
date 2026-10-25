"use client";

import { useEffect, useRef, useState } from "react";
import { Check, Loader2, MonitorX, X } from "lucide-react";
import { api } from "../../lib/api";
import type { DesktopRecipeStepResult } from "../../lib/api/desktopRecipes";
import { useTranslations } from "../../lib/i18n";

// Monitor pane for a desktop-recipe run: while the run is in flight it polls
// GET /api/desktop/monitor/frame about once a second and shows the newest frame (the response is image
// bytes, fetched as a Blob and shown through an object URL - nothing is stored). Below the frame a step
// ticker lists the step results the run response returned. 404/503 from the frame endpoint mean the
// monitor or the desktop sidecar is off - an expected state shown inline, never a toast.
//
// Rendered by DesktopRecipesCard under the recipe being run; hidden entirely until there is something
// to show (a running run, a fetched frame, or finished step results).

const POLL_MS = 1_000;

export default function DesktopMonitor({
  active,
  steps,
  live,
}: {
  /** True while the run call is in flight - frames are polled only then. */
  active: boolean;
  /** Step results from the finished run response; the ticker keeps them on screen after the run ends. */
  steps?: DesktopRecipeStepResult[] | null;
  /** Live-watch variant: frames of the whole screen while the person chose "Watch live" - the copy
   * says "what the agent is doing right now" instead of naming a recipe run. */
  live?: boolean;
}) {
  const t = useTranslations("desktopAutomation");
  const [frameUrl, setFrameUrl] = useState<string | null>(null);
  const [down, setDown] = useState(false);
  // The object URL currently handed to the <img>; kept in a ref so the unmount cleanup can revoke it.
  const frameRef = useRef<string | null>(null);

  useEffect(() => {
    if (!active) return undefined;
    let stopped = false;
    const tick = async () => {
      if (typeof document !== "undefined" && document.hidden) return;
      try {
        const blob = await api.fetchDesktopMonitorFrame();
        if (stopped) return;
        const url = URL.createObjectURL(blob);
        const prev = frameRef.current;
        frameRef.current = url;
        setFrameUrl(url);
        if (prev) URL.revokeObjectURL(prev);
        setDown(false);
      } catch {
        if (!stopped) setDown(true);
      }
    };
    void tick();
    const timer = setInterval(() => void tick(), POLL_MS);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [active]);

  // Revoke whichever object URL is still held when the pane unmounts.
  useEffect(
    () => () => {
      if (frameRef.current) {
        URL.revokeObjectURL(frameRef.current);
        frameRef.current = null;
      }
    },
    []
  );

  const hasSteps = !!steps && steps.length > 0;
  if (!active && !frameUrl && !hasSteps) return null;

  return (
    <div className="mt-2 rounded-md border border-border bg-card px-3 py-2" data-testid="desktop-monitor">
      <div className="flex items-center gap-2 text-xs font-medium text-foreground">
        {active ? (
          <Loader2 className="size-3.5 animate-spin text-muted-foreground" aria-hidden="true" />
        ) : (
          <span className="size-3.5" aria-hidden="true" />
        )}
        {live ? t("monitorLiveTitle") : t("monitorTitle")}
      </div>
      {active && (
        <p className="mt-0.5 text-[11px] text-muted-foreground">
          {live ? t("monitorLiveRunning") : t("monitorRunning")}
        </p>
      )}
      {down && (
        <p className="mt-0.5 flex items-center gap-1 text-[11px] text-muted-foreground" role="status">
          <MonitorX className="size-3.5" aria-hidden="true" />
          {t("monitorDown")}
        </p>
      )}
      {frameUrl ? (
        // eslint-disable-next-line @next/next/no-img-element -- an object URL from a same-origin API blob, not a site asset
        <img
          src={frameUrl}
          alt={t("monitorFrameAlt")}
          data-testid="desktop-monitor-frame"
          className="mt-2 block w-full rounded border border-border bg-muted"
        />
      ) : (
        active && !down && <p className="mt-2 text-[11px] text-muted-foreground">{t("monitorNoFrame")}</p>
      )}
      {hasSteps && (
        <ul className="mt-2 space-y-0.5 text-[11px]" data-testid="desktop-monitor-ticker" aria-live="polite">
          {steps!.map((s) => (
            <li key={s.index} className={s.ok ? "text-muted-foreground" : "text-destructive"}>
              <span className="mr-1 inline-flex align-middle">
                {s.ok ? (
                  <Check className="size-3" aria-hidden="true" />
                ) : (
                  <X className="size-3" aria-hidden="true" />
                )}
              </span>
              {t("monitorStep", { index: s.index, action: s.action })}
              {s.note ? ` — ${s.note}` : ""}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
