"use client";

import { useEffect, useState } from "react";
import { Megaphone, X, ExternalLink } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import {
  STATUS_POLL_MS,
  announcementActive,
  dismissKey,
  readDismissed,
  refreshPlatformStatus,
  usePlatformStatus,
  writeDismissed,
} from "../lib/platformStatus";

// Global announcement banner (set by platform admins in web-admin
// /maintenance). Mounted once in the root layout, so it shows on every page,
// public or signed in. Also owns the /api/public/status poll (on load + every
// five minutes) that AppShell's maintenance gate reads from the same store.
// Fails silent: no status, no banner. Dismissal is per message text
// (localStorage key derived from a hash of it), so a new message shows again.

const LEVEL_STYLE: Record<string, React.CSSProperties> = {
  info: { backgroundColor: "color-mix(in srgb, var(--primary) 10%, var(--background))" },
  warning: { backgroundColor: "color-mix(in srgb, var(--state-warning) 16%, var(--background))" },
  critical: { backgroundColor: "color-mix(in srgb, var(--state-error) 16%, var(--background))" },
};

export default function PlatformStatusBanner() {
  const t = useTranslations("platformStatus");
  const { status } = usePlatformStatus();
  const [dismissedKey, setDismissedKey] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    void refreshPlatformStatus();
    const id = window.setInterval(() => {
      setNow(Date.now());
      void refreshPlatformStatus();
    }, STATUS_POLL_MS);
    return () => window.clearInterval(id);
  }, []);

  const a = status?.announcement ?? null;
  const key = a ? dismissKey(a) : null;

  // Read the dismissal after mount only (localStorage is client-only).
  useEffect(() => {
    if (key && readDismissed(key)) setDismissedKey(key);
  }, [key]);

  if (!a || !key || !announcementActive(a, now) || dismissedKey === key) return null;

  return (
    <div
      role={a.level === "critical" ? "alert" : "status"}
      className="relative z-[60] flex items-center gap-3 border-b border-border px-4 py-2 text-sm text-foreground"
      style={LEVEL_STYLE[a.level] || LEVEL_STYLE.info}
      data-testid="platform-announcement"
      data-level={a.level}
    >
      <Megaphone className="size-4 shrink-0 text-primary" aria-hidden="true" />
      <span className="min-w-0 flex-1">{a.message}</span>
      {a.linkUrl && (
        <a
          href={a.linkUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex shrink-0 items-center gap-1 text-xs font-medium underline underline-offset-2"
        >
          {t("learnMore")} <ExternalLink className="size-3" aria-hidden="true" />
        </a>
      )}
      <button
        type="button"
        onClick={() => {
          writeDismissed(key);
          setDismissedKey(key);
        }}
        aria-label={t("dismiss")}
        className="flex size-7 shrink-0 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground hover:bg-accent hover:text-foreground"
      >
        <X aria-hidden="true" className="size-4" />
      </button>
    </div>
  );
}
