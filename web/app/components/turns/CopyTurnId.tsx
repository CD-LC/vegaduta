"use client";

import { useEffect, useRef, useState } from "react";
import { Check, Copy } from "lucide-react";
import { Button } from "../ui/button";
import { useTranslations } from "../../lib/i18n";

/** Copies a turn id. The id stays visible next to it, so a failed clipboard write still leaves it selectable. */
export default function CopyTurnId({ turnId, showId = true }: { turnId: string; showId?: boolean }) {
  const t = useTranslations("turnInspector");
  const [state, setState] = useState<"idle" | "copied" | "failed">("idle");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(turnId);
      setState("copied");
    } catch {
      setState("failed");
    }
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setState("idle"), 3000);
  };

  return (
    <span className="inline-flex flex-wrap items-center gap-2">
      {showId && <code className="break-all font-mono text-xs text-muted-foreground">{turnId}</code>}
      <Button type="button" size="sm" variant="outline" onClick={() => void copy()} aria-label={t("tlCopyTurnId")}>
        {state === "copied" ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
        {t("tlCopyTurnIdShort")}
      </Button>
      <span role="status" aria-live="polite" className="text-xs text-muted-foreground">
        {state === "copied" ? t("tlCopied") : state === "failed" ? t("tlCopyFailed") : ""}
      </span>
    </span>
  );
}
