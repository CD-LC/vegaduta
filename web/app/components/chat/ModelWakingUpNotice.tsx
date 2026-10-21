"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "../../lib/i18n";

/** Seconds without any reply before the notice appears: a warm model has answered long before. */
export const WAKING_UP_AFTER_MS = 8000;

/**
 * Tells the person why the first reply is slow: a hosted model that scaled to zero, or a local one
 * that was unloaded, loads before it answers (often a minute). Shown only while a turn is in flight,
 * nothing has streamed yet and no step has been reported, after {@link WAKING_UP_AFTER_MS}; it goes
 * away the moment the reply starts.
 */
export function ModelWakingUpNotice({
  active,
  startedAt,
}: {
  /** In flight with no reply text and no step yet. */
  active: boolean;
  startedAt?: number | null;
}) {
  const t = useTranslations("chatPanel");
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return undefined;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [active]);
  if (!active || !startedAt || now - startedAt < WAKING_UP_AFTER_MS) return null;
  return (
    <p role="status" className="mb-2 text-xs text-muted-foreground">
      {t("modelWakingUp")}
    </p>
  );
}
