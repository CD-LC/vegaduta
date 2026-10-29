"use client";

import { useCallback, useState } from "react";
import { Check, Copy, Link2, Loader2 } from "lucide-react";
import { Button } from "../ui/button";
import { createShare } from "./demoClient";
import { useTranslations } from "../../lib/i18n";

// ---------------------------------------------------------------------------
// "Share your agent" — mints a public link (/a/<shareId>) anyone can chat
// with, no account needed on either side.
//
// Honesty contract, stated on the panel itself before the visitor commits:
// anyone with the link can talk to their agent, capped at 5 people, spending
// the same free demo limits, and it dies when the sandbox does. No hidden
// virality mechanics — the visitor knows exactly what they are handing out.
//
// Inherits /try's one hard rule: no error surface, ever. createShare() never
// rejects; a failure arrives as a sentence and is rendered as a sentence.
//
// Controlled-open from the parent so the guided lab's "Open the share panel"
// action can pop it; the panel's own button toggles the same state.
// ---------------------------------------------------------------------------

export default function SharePanel({
  token,
  open,
  onOpenChange,
  onShared,
}: {
  token: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Guided-lab observer: fired once a share link actually exists. */
  onShared?: () => void;
}) {
  const t = useTranslations("try");
  const [busy, setBusy] = useState(false);
  const [shareUrl, setShareUrl] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const create = useCallback(async () => {
    if (!token || busy) return;
    setBusy(true);
    setMessage(null);
    const result = await createShare(token);
    if (result.ok && result.shareUrl) {
      setShareUrl(result.shareUrl);
      onShared?.();
    } else {
      // A soft sentence, never a wall — the server's own words when it has
      // any, the client's honest fallback when it doesn't.
      setMessage(result.message || t("share_defaultErrorMessage"));
    }
    setBusy(false);
  }, [token, busy, onShared, t]);

  const copy = useCallback(async () => {
    if (!shareUrl) return;
    try {
      await navigator.clipboard.writeText(shareUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      // Clipboard blocked — the URL is right there in the input to select by
      // hand. Nothing to report.
    }
  }, [shareUrl]);

  if (!token) return null;

  return (
    <div className="flex flex-col gap-2 rounded-xl border border-border/60 bg-card/40 px-4 py-3">
      {!open ? (
        <button
          type="button"
          onClick={() => onOpenChange(true)}
          className="flex items-center gap-2 self-start border-0 bg-transparent p-0 text-xs text-muted-foreground transition-colors hover:text-foreground"
        >
          <Link2 className="size-3.5" aria-hidden="true" />
          {t("share_shareYourAgent")}
        </button>
      ) : (
        <div className="space-y-2.5">
          <div className="flex items-center gap-2">
            <Link2 className="size-3.5 shrink-0 text-primary" aria-hidden="true" />
            <p className="text-xs font-medium text-foreground">{t("share_shareYourAgent")}</p>
            <button
              type="button"
              onClick={() => onOpenChange(false)}
              className="ms-auto border-0 bg-transparent p-0 text-xs text-muted-foreground transition-colors hover:text-foreground"
            >
              {t("share_close")}
            </button>
          </div>

          {/* The honest terms, shown BEFORE the link exists — the visitor
              knows what they're minting before they mint it. */}
          <p className="text-xs text-muted-foreground">{t("share_termsText")}</p>

          {shareUrl ? (
            <div className="flex items-center gap-2">
              <input
                readOnly
                value={shareUrl}
                onFocus={(e) => e.target.select()}
                aria-label={t("share_shareLinkAria")}
                className="min-w-0 flex-1 rounded-md border border-border/70 bg-background/40 px-2.5 py-1.5 font-mono text-xs text-foreground outline-none"
              />
              <Button size="sm" variant="outline" onClick={copy} aria-label={t("share_copyLinkAria")}>
                {copied ? (
                  <Check aria-hidden="true" className="size-3.5" />
                ) : (
                  <Copy aria-hidden="true" className="size-3.5" />
                )}
                {copied ? t("share_copied") : t("share_copy")}
              </Button>
            </div>
          ) : (
            <Button size="sm" onClick={create} disabled={busy}>
              {busy ? (
                <Loader2 aria-hidden="true" className="size-3.5 animate-spin" />
              ) : (
                <Link2 aria-hidden="true" className="size-3.5" />
              )}
              {t("share_createShareLink")}
            </Button>
          )}

          {message && (
            <p className="text-xs text-muted-foreground" role="status">
              {message}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
