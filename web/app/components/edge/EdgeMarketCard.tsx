"use client";

import {    useState } from "react";
import {
  Check,
  
  
  
  
  
  
  
  
  
  Pause,
  Play,
  
  
  
  
  X,
  
} from "lucide-react";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";
import { downloadManager, type AssetStatus } from "../../lib/edge/downloadManager";
import { HOSTED_FALLBACK_LABEL, describeDownloadFailure } from "../../lib/edge/resilience/failures";

import { ProgressRow, percentOf } from "./edgeConsoleHelpers";

// ---------------------------------------------------------------------------
// Marketplace card
// ---------------------------------------------------------------------------

export default function MarketCard({
  icon,
  name,
  purpose,
  sizeLabel,
  fitsLabel,
  fitsWarn,
  detail,
  note,
  asset,
  installed,
  footer,
  problem,
  onUseHosted,
}: {
  icon: React.ReactNode;
  name: string;
  purpose: string;
  sizeLabel: string;
  fitsLabel: string;
  fitsWarn: boolean;
  detail: string | null;
  /** Optional small cross-reference note (e.g. "needs a text model too"),
   * rendered plain/non-alarming below `detail`. Omit/null when nothing to
   * say — kept separate from `detail` so callers don't have to mutate
   * shared branding copy just to add a conditional aside. */
  note?: string | null;
  asset: AssetStatus | null;
  installed: boolean;
  footer: React.ReactNode;
  /** An INSTALLED asset that is known not to work, with the one action that
   * fixes it. Plain strings from resilience/failures.ts (not t()). */
  problem?: {
    title: string;
    message: string;
    actionLabel: string;
    onAction: () => void;
    disabled?: boolean;
  } | null;
  /** Switch every reply to the hosted model. Passed ONLY by text-model cards:
   * the switch is global (it turns on-device text off too), so a failed
   * optional vision/voice download must not offer it. Omitted = not shown. */
  onUseHosted?: () => void;
}) {
  const t = useTranslations("edge");
  const [switchedToHosted, setSwitchedToHosted] = useState(false);
  // 2026-09-19: typed failure → a sentence and, where this card can perform
  // it, one action. Rows reported by engines that don't set failureReason yet
  // keep their own `error` string.
  const failure =
    asset && asset.state === "error" && asset.failureReason
      ? describeDownloadFailure(asset.failureReason, { neededBytes: asset.totalBytes || undefined })
      : null;
  // "Pick a smaller model" / "Free up space" are not buttons here because
  // there is nothing to navigate to: this card already sits in the model list,
  // next to every other model's size and the installed models' Delete buttons.
  // A plain pointer says so instead of leaving the failure with no next step.
  const inPageHint =
    failure?.action === "pick-smaller-model"
      ? failure.reason === "model-unavailable"
        ? "The models that are available are listed on this page."
        : "Smaller models are listed on this page, each with its size."
      : failure?.action === "free-space"
        ? "Free up space on this device (installed models can be deleted from this page), then download again."
        : null;
  // External engines (WebLLM, the transformers.js workers, Chrome's built-in
  // model) cannot be paused or cancelled from the download manager, so those
  // buttons were dead on every card that ships today — and Retry left the row
  // stuck on "Queued". See downloadManager.pause()/resume().
  const controllable = !!asset && !asset.external;
  const retryable = !!asset && downloadManager.canRetry(asset.id) && (failure ? failure.retryable : true);
  const inFlight = asset && (asset.state === "downloading" || asset.state === "verifying" || asset.state === "queued");
  return (
    <div className="flex flex-col rounded-lg border border-border bg-card p-4">
      <div className="mb-1 flex items-start justify-between gap-2">
        <div className="inline-flex min-w-0 items-center gap-2 text-sm font-medium text-foreground">
          <span className="shrink-0 text-muted-foreground">{icon}</span>
          <span className="truncate">{name}</span>
        </div>
        {installed && (
          <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-primary/10 px-1.5 py-0.5 text-xs font-medium text-primary">
            <Check size={12} aria-hidden="true" />
            {t("market.installed")}
          </span>
        )}
      </div>
      <div className="text-xs text-muted-foreground">{purpose}</div>
      <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]">
        <span className="text-muted-foreground">{sizeLabel}</span>
        <span className={cn(fitsWarn ? "text-destructive" : "text-muted-foreground")}>{fitsLabel}</span>
      </div>
      {detail && <p className="mt-2 line-clamp-3 text-[11px] leading-relaxed text-muted-foreground">{detail}</p>}
      {note && <p className="mt-1 text-[11px] leading-relaxed text-muted-foreground">{note}</p>}

      {/* Live download state (shared asset row) */}
      {asset && inFlight && (
        <div className="mt-auto pt-2">
          <div className="flex items-center justify-between text-[11px] text-muted-foreground">
            <span>
              {asset.state === "verifying"
                ? t("market.verifying")
                : asset.state === "queued"
                  ? t("market.queued")
                  : t("market.downloading")}
            </span>
            <span className="flex items-center gap-1">
              {controllable && asset.state === "downloading" && (
                <button
                  type="button"
                  onClick={() => downloadManager.pause(asset.id)}
                  aria-label={`${t("market.pause")} ${asset.displayName}`}
                  className="flex size-6 items-center justify-center rounded border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:text-foreground"
                >
                  <Pause size={11} aria-hidden="true" />
                </button>
              )}
              {controllable && (asset.state === "downloading" || asset.state === "queued") && (
                <button
                  type="button"
                  onClick={() => downloadManager.cancel(asset.id)}
                  aria-label={`${t("market.cancel")} ${asset.displayName}`}
                  className="flex size-6 items-center justify-center rounded border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:text-foreground"
                >
                  <X size={11} aria-hidden="true" />
                </button>
              )}
            </span>
          </div>
          {asset.state !== "verifying" && <ProgressRow asset={asset} />}
        </div>
      )}
      {asset && asset.state === "paused" && (
        <div className="mt-auto pt-2">
          <div className="flex items-center justify-between text-[11px] text-muted-foreground">
            <span>
              {t("market.paused")} · {percentOf(asset)}%
            </span>
            <button
              type="button"
              onClick={() => downloadManager.resume(asset.id)}
              aria-label={`${t("market.resume")} ${asset.displayName}`}
              className="flex size-6 items-center justify-center rounded border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:text-foreground"
            >
              <Play size={11} aria-hidden="true" />
            </button>
          </div>
        </div>
      )}
      {asset && asset.state === "error" && (
        <div className="mt-auto pt-2 text-[11px]" role="alert">
          <p className="font-medium text-destructive">{failure ? failure.title : asset.error || t("market.failed")}</p>
          {failure && <p className="mt-0.5 leading-relaxed text-muted-foreground">{failure.message}</p>}
          {inPageHint && <p className="mt-0.5 leading-relaxed text-muted-foreground">{inPageHint}</p>}
          <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
            {retryable && (
              <button
                type="button"
                onClick={() => downloadManager.resume(asset.id)}
                className="inline-flex min-h-6 shrink-0 items-center rounded border-0 bg-transparent p-0 font-medium text-primary shadow-none hover:underline"
              >
                {failure && (failure.action === "retry" || failure.action === "redownload")
                  ? failure.actionLabel
                  : t("market.retry")}
              </button>
            )}
            {/* Text-model cards only (see onUseHosted): a failed on-device
                text setup must never be the reason someone can't chat. Same
                switch as the mode control at the top of this page, and
                reversible from there — which the confirmation says, because
                the click otherwise changes nothing visible on this card. */}
            {onUseHosted && !switchedToHosted && (
              <button
                type="button"
                onClick={() => {
                  onUseHosted();
                  setSwitchedToHosted(true);
                }}
                className="shrink-0 rounded border-0 bg-transparent p-0 font-medium text-muted-foreground shadow-none hover:text-foreground hover:underline"
              >
                {HOSTED_FALLBACK_LABEL}
              </button>
            )}
          </div>
          {onUseHosted && switchedToHosted && (
            <p className="mt-1 leading-relaxed text-muted-foreground" role="status">
              Switched to the hosted model. You can change it back with the mode setting at the top of this page.
            </p>
          )}
        </div>
      )}
      {problem && !(asset && asset.state === "error") && !inFlight && (
        <div className="mt-2 rounded-md border border-destructive/30 bg-destructive/5 p-2 text-[11px]" role="alert">
          <p className="font-medium text-destructive">{problem.title}</p>
          <p className="mt-0.5 leading-relaxed text-muted-foreground">{problem.message}</p>
          <button
            type="button"
            onClick={problem.onAction}
            disabled={problem.disabled}
            className="mt-1 rounded border-0 bg-transparent p-0 font-medium text-primary shadow-none hover:underline disabled:opacity-50"
          >
            {problem.actionLabel}
          </button>
        </div>
      )}

      {footer && <div className={cn("pt-3", !inFlight && "mt-auto")}>{footer}</div>}
    </div>
  );
}
