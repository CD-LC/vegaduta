"use client";

import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { MonitorPlay, PictureInPicture2, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import DesktopMonitor from "./DesktopMonitor";
import { useDesktopAvailability } from "./useDesktopAvailability";

// Persistent live-screen dock: a floating pill at the bottom-right of the console that expands
// into a small always-on-top window streaming the person's screen (~1 fps). It is the "watch the
// agent work" surface that stays visible while they keep chatting or navigate elsewhere - the
// in-turn panel (DesktopMonitorPanel) is the same feed anchored to the reply that is driving the
// actions.
//
// "Pop out" moves the feed into a real OS-level Picture-in-Picture window
// (window.documentPictureInPicture - Chromium; absent in Firefox/Safari, where the button simply
// does not render). The PIP window survives the console tab being minimized, so the desktop stays
// free for the agent to work in while the person watches from the floating window. Frames keep
// polling while the tab is hidden (DesktopMonitor `background`) because that is exactly what the
// pop-out is for.
//
// Lifecycle: renders nothing until the desktop feature answers (same probe as the cards); the
// stream polls only while the dock is OPEN or popped out - a collapsed pill costs one availability
// check per page load and nothing per second.

interface DocumentPictureInPicture {
  requestWindow(options?: { width?: number; height?: number }): Promise<Window>;
}

function pipApi(): DocumentPictureInPicture | null {
  if (typeof window === "undefined") return null;
  const w = window as Window & { documentPictureInPicture?: DocumentPictureInPicture };
  return w.documentPictureInPicture ?? null;
}

/** Copies the console's stylesheets into the PiP document so Tailwind classes resolve there. */
function copyStyles(pip: Window) {
  for (const sheet of Array.from(document.styleSheets)) {
    try {
      if (sheet.href) {
        const link = pip.document.createElement("link");
        link.rel = "stylesheet";
        link.href = sheet.href;
        pip.document.head.appendChild(link);
      } else if (sheet.cssRules) {
        const style = pip.document.createElement("style");
        for (const rule of Array.from(sheet.cssRules)) {
          style.textContent += rule.cssText + "\n";
        }
        pip.document.head.appendChild(style);
      }
    } catch {
      // A cross-origin sheet throws on cssRules; its <link> copy above covers the content.
    }
  }
}

export default function DesktopScreenDock() {
  const t = useTranslations("desktopAutomation");
  const available = useDesktopAvailability();
  const [open, setOpen] = useState(false);
  const [pip, setPip] = useState<{ win: Window; mount: HTMLElement } | null>(null);

  useEffect(
    () => () => {
      pip?.win.close();
    },
    [pip]
  );

  if (!available) return null;

  const popOut = async () => {
    const api = pipApi();
    if (!api) return;
    const win = await api.requestWindow({ width: 400, height: 300 });
    copyStyles(win);
    win.document.title = t("screenDockTitle");
    // Inherit the console's theme classes (light/dark) so the feed matches.
    win.document.documentElement.className = document.documentElement.className;
    win.document.body.className = document.body.className;
    win.document.body.style.margin = "0";
    const mount = win.document.createElement("div");
    mount.style.padding = "8px";
    win.document.body.appendChild(mount);
    win.addEventListener("pagehide", () => setPip(null));
    setPip({ win, mount });
  };

  if (pip) {
    // The feed lives in the PiP window; the dock itself collapses to nothing in the console.
    return (
      <>
        <div className="fixed bottom-4 right-4 z-40" data-testid="desktop-screen-dock" />
        {createPortal(<DesktopMonitor active live background />, pip.mount)}
      </>
    );
  }

  return (
    <div className="fixed bottom-4 right-4 z-40" data-testid="desktop-screen-dock">
      {open ? (
        <div className="w-80 overflow-hidden rounded-lg border border-border bg-card shadow-lg">
          <div className="flex items-center gap-2 border-b border-border px-3 py-1.5">
            <MonitorPlay className="size-3.5 text-muted-foreground" aria-hidden="true" />
            <span className="text-xs font-medium text-foreground">{t("screenDockTitle")}</span>
            {pipApi() && (
              <button
                type="button"
                className="ml-auto text-muted-foreground hover:text-foreground"
                aria-label={t("screenDockPopOut")}
                title={t("screenDockPopOut")}
                data-testid="desktop-screen-dock-popout"
                onClick={() => void popOut()}
              >
                <PictureInPicture2 className="size-3.5" aria-hidden="true" />
              </button>
            )}
            <button
              type="button"
              className={`${pipApi() ? "" : "ml-auto "}text-muted-foreground hover:text-foreground`}
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
