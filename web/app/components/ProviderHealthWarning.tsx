"use client";

import Link from "next/link";
import { AlertTriangle } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { isProviderRejected } from "../lib/providerHealth";

/**
 * Inline warning under a provider picker when the selected provider's credential has been rejected recently, so a
 * person is told BEFORE they save an agent whose every reply would fail. Renders nothing for a healthy provider, a
 * provider without health information, or an older core that sends none.
 */
export function ProviderHealthWarning({
  providerName,
  providers,
  className,
}: {
  providerName: string | null | undefined;
  providers: ReadonlyArray<{ providerName?: unknown; health?: unknown }> | null | undefined;
  className?: string;
}) {
  const t = useTranslations("providerHealth");
  if (!isProviderRejected(providers, providerName)) return null;
  return (
    <p
      data-testid="provider-health-warning"
      className={`flex w-full items-start gap-1.5 text-xs text-destructive ${className ?? ""}`}
    >
      <AlertTriangle className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
      <span>
        {t("inlineWarning")}{" "}
        <Link href="/providers" className="font-medium underline underline-offset-2">
          {t("replaceKey")}
        </Link>
      </span>
    </p>
  );
}

/**
 * The server's own note about the default model for a new agent (GET /api/providers/default-model answers
 * `needsAttention: true` with a `message` when even its best pick is shaky). Shown only while that provider is still
 * the one selected; nothing for a healthy default.
 */
export function DefaultModelNotice({
  notice,
  providerName,
  className,
}: {
  notice: { providerName?: string | null; needsAttention?: boolean; message?: string | null } | null | undefined;
  providerName: string | null | undefined;
  className?: string;
}) {
  const t = useTranslations("providerHealth");
  if (!notice || notice.needsAttention !== true) return null;
  if (notice.providerName && providerName && notice.providerName !== providerName) return null;
  return (
    <p data-testid="default-model-notice" className={`flex w-full items-start gap-1.5 text-xs text-muted-foreground ${className ?? ""}`}>
      <AlertTriangle className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
      <span>
        {notice.message?.trim() || t("defaultNeedsAttention")}{" "}
        <Link href="/providers" className="font-medium text-primary underline underline-offset-2">
          {t("checkProviders")}
        </Link>
      </span>
    </p>
  );
}

/** The badge on a provider card: "Credentials rejected - recent calls failed". */
export function ProviderRejectedBadge({ lastError, recentFailures }: { lastError?: string | null; recentFailures?: number }) {
  const t = useTranslations("providerHealth");
  return (
    <span
      data-testid="provider-rejected-badge"
      title={lastError || undefined}
      className="inline-flex items-center gap-1 rounded-full border border-destructive/40 bg-destructive/10 px-2 py-0.5 text-[11px] font-medium text-destructive"
    >
      <AlertTriangle className="size-3" aria-hidden="true" />
      {t("rejectedBadge")}
      {recentFailures && recentFailures > 1 ? <span className="sr-only">{t("recentFailuresSr", { count: recentFailures })}</span> : null}
    </span>
  );
}
