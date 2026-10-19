"use client";

import { ExternalLink, Lightbulb } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { ProviderRejectedBadge } from "../ProviderHealthWarning";
import { providerHealthOf } from "../../lib/providerHealth";
import type { LlmProviderGuide } from "../../lib/credentialGuides";
import { formatActivatedDate } from "./providerCatalog";

/* eslint-disable @typescript-eslint/no-explicit-any */
// Pieces of one provider card on ProvidersPanel's grid.

/** The "Activated <date>" / "Not connected" line, plus the rejected-key badge with its way out. */
export function ProviderCardStatus({
  activated,
  implemented,
  canReplaceKey,
  onReplaceKey,
}: {
  activated: any;
  implemented: boolean;
  canReplaceKey: boolean;
  onReplaceKey: () => void;
}) {
  const t = useTranslations("providersPanel");
  const tHealth = useTranslations("providerHealth");
  return (
    <>
      {(() => {
        const activatedDate = activated ? formatActivatedDate(activated.createdAt) : null;
        const line = activated
          ? activatedDate
            ? t("activatedLabel", { date: activatedDate })
            : null
          : implemented
            ? t("notConnectedLabel")
            : null;
        return line ? <div className="mt-0.5 text-xs text-muted-foreground">{line}</div> : null;
      })()}
      {/* A key the provider has been rejecting: say so on the card, with the way out. */}
      {(() => {
        const health = activated ? providerHealthOf(activated) : null;
        if (!health?.rejected) return null;
        return (
          <div
            className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1"
            role="presentation"
            onClick={(e) => e.stopPropagation()}
          >
            <ProviderRejectedBadge lastError={health.lastError} recentFailures={health.recentFailures} />
            {canReplaceKey && (
              <Button
                type="button"
                variant="link"
                size="sm"
                className="h-auto p-0 text-xs"
                onClick={onReplaceKey}
              >
                {tHealth("replaceKey")}
              </Button>
            )}
          </div>
        );
      })()}
    </>
  );
}

/**
 * Guidance affordances on the card itself (not just inside the activation
 * form): a "good first pick" hint for someone who's never configured a
 * provider, and a direct link to the provider's own pricing page -
 * prices/credits/offers are LINKED, never hardcoded. stopPropagation keeps
 * the link click from toggling card selection.
 */
export function ProviderGuideLinks({ guide }: { guide: LlmProviderGuide | undefined }) {
  const t = useTranslations("providersPanel");
  if (!(guide?.startHint || guide?.pricingHref)) return null;
  return (
    <div
      className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1"
      role="presentation"
      onClick={(e) => e.stopPropagation()}
    >
      {guide?.startHint && (
        <span
          className="inline-flex items-center gap-1 rounded-full border border-[var(--accent-200)] bg-[var(--accent-50)] px-2 py-0.5 text-[11px] font-medium text-[var(--accent-text)]"
          title={guide.startHint}
        >
          <Lightbulb aria-hidden="true" size={11} /> {t("goodFirstPickBadge")}
        </span>
      )}
      {guide?.pricingHref && (
        <a
          href={guide.pricingHref}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline"
        >
          {t("pricingLink")} <ExternalLink aria-hidden="true" size={11} />
        </a>
      )}
    </div>
  );
}

/** Edit / Delete (two-step confirm) for an activated provider's key. */
export function ProviderKeyActions({
  confirmingDelete,
  deleting,
  onEdit,
  onRequestDelete,
  onCancelDelete,
  onConfirmDelete,
}: {
  confirmingDelete: boolean;
  deleting: boolean;
  onEdit: () => void;
  onRequestDelete: () => void;
  onCancelDelete: () => void;
  onConfirmDelete: () => void;
}) {
  const t = useTranslations("providersPanel");
  return (
    <div
      className="mt-4 flex flex-wrap items-center gap-2"
      role="presentation"
      onClick={(e) => e.stopPropagation()}
    >
      {confirmingDelete ? (
        <>
          <span className="text-xs text-muted-foreground">{t("confirmDeleteProviderNote")}</span>
          <Button
            type="button"
            size="sm"
            variant="destructive"
            disabled={deleting}
            onClick={onConfirmDelete}
          >
            {deleting ? t("deletingButton") : t("confirmDeleteButton")}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            disabled={deleting}
            onClick={onCancelDelete}
          >
            {t("cancelButton")}
          </Button>
        </>
      ) : (
        <>
          <Button type="button" size="sm" variant="outline" onClick={onEdit}>
            {t("editButton")}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={onRequestDelete}
          >
            {t("deleteButton")}
          </Button>
        </>
      )}
    </div>
  );
}
