"use client";

import { useState } from "react";
import { Check, Copy, KeyRound } from "lucide-react";
import { Button } from "../../components/ui/button";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../components/ui/alert-dialog";
import { API_BASE } from "../../lib/api/core";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";

type Translator = (key: string, values?: Record<string, string | number>) => string;

/** The host the curl example targets: the console's configured API base, else this origin. */
export function apiHostForExample(): string {
  if (API_BASE) return API_BASE.replace(/\/+$/, "");
  return typeof window !== "undefined" ? window.location.origin : "";
}

/** One-time reveal of a freshly minted/rotated key. Not dismissed by an outside click: the key cannot be shown again. */
export function KeyRevealDialog({
  title,
  rawApiKey,
  onClose,
  t,
}: {
  title: string;
  rawApiKey: string;
  onClose: () => void;
  t: Translator;
}) {
  const [copied, setCopied] = useState(false);

  function handleCopy() {
    navigator.clipboard?.writeText(rawApiKey).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      },
      () => toast.error(t("copyFailedToast"))
    );
  }

  return (
    <AlertDialog open onOpenChange={(open) => !open && onClose()}>
      <AlertDialogContent className="max-w-xl">
        <AlertDialogHeader>
          <AlertDialogTitle className="flex items-center gap-2">
            <KeyRound size={16} aria-hidden="true" />
            {title}
          </AlertDialogTitle>
          <AlertDialogDescription>{t("revealWarning")}</AlertDialogDescription>
        </AlertDialogHeader>

        <div className="flex items-start gap-2">
          <code className="block min-w-0 flex-1 break-all rounded-lg border border-border bg-[var(--ax-track)] px-3 py-2 font-mono text-[0.78rem]">
            {rawApiKey}
          </code>
          <Button type="button" variant="outline" size="sm" onClick={handleCopy}>
            {copied ? <Check size={13} aria-hidden="true" /> : <Copy size={13} aria-hidden="true" />}
            {copied ? t("copiedButton") : t("copyButton")}
          </Button>
        </div>

        <p className="m-0 text-sm text-muted-foreground">{t("revealUsageHint")}</p>
        <pre className="m-0 overflow-x-auto rounded-lg border border-border bg-[var(--ax-track)] px-3 py-2 text-[0.72rem]">
          {`curl -H "Authorization: Bearer ${rawApiKey}" \\`}
          {"\n  "}
          {`-X POST ${apiHostForExample()}/api/workflows/<id>/run`}
        </pre>

        <AlertDialogFooter>
          <AlertDialogAction onClick={onClose}>{t("revealDoneButton")}</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** Rotate / revoke confirmation. Stays open while the request runs. */
export function ConfirmDialog({
  title,
  body,
  confirmLabel,
  busy,
  onCancel,
  onConfirm,
}: {
  title: string;
  body: string;
  confirmLabel: string;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const tl = useTranslations("adminLeftoversUi");
  return (
    <AlertDialog open onOpenChange={(open) => !open && !busy && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{body}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>{tl("cancel")}</AlertDialogCancel>
          <AlertDialogAction
            disabled={busy}
            className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            onClick={(e) => {
              e.preventDefault();
              onConfirm();
            }}
          >
            {confirmLabel}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
