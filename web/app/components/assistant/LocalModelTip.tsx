"use client";

// A one-line note under the Assistant header while the active agent runs on a model on this
// computer (Ollama): replies depend on the laptop's speed, and the way to trade speed against
// quality is Agent settings. Dismissed once per browser; storage may be unavailable, so the note
// simply shows again then.

import { X, Zap } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";

export const LOCAL_TIP_STORAGE_KEY = "assistant.localModelTip.dismissed";

export function isLocalProvider(providerName: string | null | undefined): boolean {
  return (providerName ?? "").trim().toLowerCase() === "ollama";
}

export default function LocalModelTip({
  agentId,
  providerName,
}: {
  agentId: string;
  providerName: string | null | undefined;
}) {
  const t = useTranslations("assistant");
  const [dismissed, setDismissed] = useState(true);

  useEffect(() => {
    try {
      setDismissed(window.localStorage.getItem(LOCAL_TIP_STORAGE_KEY) === "1");
    } catch {
      setDismissed(false);
    }
  }, []);

  if (dismissed || !isLocalProvider(providerName)) return null;

  const dismiss = () => {
    setDismissed(true);
    try {
      window.localStorage.setItem(LOCAL_TIP_STORAGE_KEY, "1");
    } catch {
      // The note comes back next visit; nothing else depends on it.
    }
  };

  return (
    <div
      role="note"
      data-testid="local-model-tip"
      className="mx-4 mt-3 flex items-start gap-2 rounded-lg border border-border bg-muted/40 px-3 py-2 text-sm text-muted-foreground"
    >
      <Zap size={14} className="mt-0.5 shrink-0 text-primary" aria-hidden="true" />
      <p className="min-w-0 flex-1">
        {t("localModelTip")}{" "}
        <Link
          href={`/agents?agent=${encodeURIComponent(agentId)}`}
          className="font-medium text-foreground underline underline-offset-2"
        >
          {t("localModelTipLink")}
        </Link>
      </p>
      <Button type="button" size="icon" variant="ghost" className="size-6 shrink-0" aria-label={t("localModelTipDismiss")} title={t("localModelTipDismiss")} onClick={dismiss}>
        <X size={14} aria-hidden="true" />
      </Button>
    </div>
  );
}
