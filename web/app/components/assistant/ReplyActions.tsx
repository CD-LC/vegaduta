"use client";

import { useEffect, useState } from "react";
import { Check, Copy, RotateCcw, Volume2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { isSpeaking, speakText, stopSpeaking } from "./speakText";

const ACTION =
  "inline-flex size-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:opacity-50";

/**
 * Copy / Listen / Regenerate under a finished reply, as quiet icon buttons (the words are their names and
 * tooltips). On a pointer that can hover they appear with the reply's hover or keyboard focus (and always on
 * the latest reply); on touch they are always there (CSS: .asst-actions in components-assistant.css).
 */
export default function ReplyActions({
  text,
  onRegenerate,
  regenerateDisabled,
}: {
  text: string;
  /** Present only on the last reply: retry() drops everything after the pair it replays. */
  onRegenerate?: () => void;
  regenerateDisabled?: boolean;
}) {
  const t = useTranslations("assistant");
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    if (!copied) return undefined;
    const id = setTimeout(() => setCopied(false), 1800);
    return () => clearTimeout(id);
  }, [copied]);

  return (
    <div className="asst-actions flex items-center gap-0.5" data-testid="reply-actions">
      <button
        type="button"
        onClick={() => {
          try {
            void navigator.clipboard?.writeText(text);
            setCopied(true);
          } catch {
            // Clipboard unavailable - nothing to do.
          }
        }}
        aria-label={t("copyReply")}
        title={t("copyReply")}
        className={ACTION}
      >
        {copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
      </button>
      <button
        type="button"
        onClick={() => (isSpeaking() ? stopSpeaking() : void speakText(text))}
        aria-label={t("listenToReply")}
        title={t("listenToReply")}
        className={ACTION}
      >
        <Volume2 size={14} aria-hidden="true" />
      </button>
      {onRegenerate && (
        <button
          type="button"
          onClick={onRegenerate}
          disabled={regenerateDisabled}
          aria-label={t("regenerate")}
          title={t("regenerate")}
          className={ACTION}
        >
          <RotateCcw size={14} aria-hidden="true" />
        </button>
      )}
      <span className="sr-only" role="status">
        {copied ? t("copiedReply") : ""}
      </span>
    </div>
  );
}
