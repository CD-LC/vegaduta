"use client";

import { AlertTriangle, RotateCcw } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { Button } from "./ui/button";

/**
 * A reply that failed, shown inline in the transcript where the reply would have been - the same bubble live and
 * after a reload (see lib/failedTurn.ts). A plain paragraph, not role="alert": a reloaded history must not
 * announce an old error as if it had just happened. The "Reply failed" lead-in is the accessible label.
 */
export default function FailedReplyBubble({
  text,
  onRetry,
  retryDisabled,
}: {
  text: string;
  /** Omitted when the previous message cannot simply be sent again. */
  onRetry?: () => void;
  retryDisabled?: boolean;
}) {
  const t = useTranslations("failedReply");
  return (
    <div
      data-testid="failed-reply"
      className="flex max-w-[85%] items-start gap-2.5 rounded-xl border border-destructive/40 bg-destructive/10 px-4 py-2.5 text-sm leading-relaxed text-foreground"
    >
      <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive" aria-hidden="true" />
      <div className="min-w-0 space-y-2">
        <p className="[overflow-wrap:anywhere]">
          <strong className="font-semibold text-destructive">{t("label")}.</strong> {text || t("noDetail")}
        </p>
        {onRetry && (
          <Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={retryDisabled} aria-label={t("tryAgainLabel")}>
            <RotateCcw className="size-3" aria-hidden="true" />
            {t("tryAgain")}
          </Button>
        )}
      </div>
    </div>
  );
}
