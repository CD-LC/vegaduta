"use client";

import type {} from "react";
import Link from "next/link";
import { Download, Loader2 } from "lucide-react";
import { cn } from "../../../lib/utils";
import { useTranslations } from "../../../lib/i18n";
import {
  detectCapabilities,
  modelFitState,
  type EdgeCapabilities,
  type ManifestModel,
} from "../../../lib/edge/capabilities";
import { setModelOverride, type ModelOverride } from "../../../lib/edge/routingState";
import { TIER0_ASSET_ID } from "../../../lib/edge/tier0";
import { isTier1ModelCached, prewarmTier1Engine } from "../../../lib/edge/tier1";
import { type AssetStatus } from "../../../lib/edge/downloadManager";
import { describeDownloadFailure } from "../../../lib/edge/resilience/failures";
import { EDGE_BRANDING } from "../../../lib/edge/branding";
import { Button } from "../../ui/button";
import { formatBytes, percentOf, type Tier0Status } from "./shared";

export default function OnDeviceModelList({
  assets,
  caps,
  handleTier0Setup,
  handleTier1Download,
  hostedFallbackTarget,
  installedModels,
  liveModelId,
  override,
  tier0Asset,
  tier0Busy,
  tier0Status,
  tier1Models,
  webgpuReady,
}: {
  assets: AssetStatus[];
  caps: EdgeCapabilities | null;
  handleTier0Setup: () => void | Promise<void>;
  handleTier1Download: (model: ManifestModel) => void | Promise<void>;
  hostedFallbackTarget: string;
  installedModels: ManifestModel[];
  liveModelId: string | null;
  override: ModelOverride;
  tier0Asset: AssetStatus | null;
  tier0Busy: boolean;
  tier0Status: Tier0Status;
  tier1Models: ManifestModel[];
  webgpuReady: boolean | null;
}) {
  const t = useTranslations("edgeIntelligence");
  return (
    <div className="mt-2.5 border-t border-border pt-2.5">
      {/* Bug A fix: catches the "several models downloaded, all
          blocked" case in one place instead of repeating the warning
          per row below. */}
      {webgpuReady === false && installedModels.length > 0 && (
        <p className="mb-1.5 rounded-md border border-destructive/30 bg-destructive/10 px-2 py-1.5 text-[11px] leading-relaxed text-destructive">
          {t("downloadedNoWebgpuTag", { fallback: hostedFallbackTarget })}
        </p>
      )}
      <div className="mb-1.5 flex items-center justify-between gap-2">
        <span className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
          {t("onDeviceModelsHeading")}
        </span>
        <Link href="/edge" className="text-[11px] font-medium text-primary hover:underline">
          {t("marketplaceLink")}
        </Link>
      </div>
      <div className="space-y-1">
        {/* Auto (recommended) — the plan's binding best-fit rule. */}
        <button
          type="button"
          onClick={() => setModelOverride("auto")}
          aria-pressed={override === "auto"}
          className={cn(
            "flex w-full items-center justify-between gap-2 rounded-md border-0 bg-transparent p-0 px-2 py-1.5 text-left text-xs shadow-none transition-colors hover:bg-secondary/60",
            override === "auto" ? "bg-secondary/60 text-foreground" : "text-muted-foreground"
          )}
        >
          <span className="min-w-0">
            <span className={cn("block truncate", override === "auto" && "font-medium text-foreground")}>
              {EDGE_BRANDING.pickerAutoLabel}
            </span>
            <span className="block truncate text-[11px] text-muted-foreground">{EDGE_BRANDING.pickerAutoSubtitle}</span>
          </span>
          <span
            className={cn(
              "size-2 shrink-0 rounded-full border",
              override === "auto" ? "border-primary bg-primary" : "border-border bg-transparent"
            )}
            aria-hidden="true"
          />
        </button>

        {/* Chrome built-in model (tier 0) — status row, with an
            explicit Set up affordance while Chrome reports it
            downloadable. Model identity is details-row copy, allowed
            here per the brand-first rule. Ring highlight while this exact
            model is the one actually answering the current turn. */}
        <div
          className={cn(
            "flex items-center justify-between gap-2 rounded-md px-2 py-1.5 text-xs transition-colors",
            liveModelId === TIER0_ASSET_ID && "bg-primary/10 ring-1 ring-primary"
          )}
        >
          <span className="min-w-0">
            <span className="block truncate text-foreground">{EDGE_BRANDING.tier0ModelDisplayName}</span>
            <span className="block truncate text-[11px] text-muted-foreground">
              {tier0Status === "available"
                ? t("tier0Ready")
                : tier0Busy || tier0Status === "downloading"
                  ? tier0Asset && tier0Asset.state === "downloading"
                    ? t("downloadingPercent", { percent: percentOf(tier0Asset) })
                    : t("settingUpLabel")
                  : tier0Status === "downloadable"
                    ? "Available — one-time setup"
                    : tier0Status === "checking"
                      ? "Checking…"
                      : t("tier0NotSupported")}
            </span>
          </span>
          {(tier0Status === "downloadable" || tier0Busy) && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={tier0Busy}
              onClick={() => {
                void handleTier0Setup();
              }}
            >
              {tier0Busy ? <Loader2 size={12} className="animate-spin" /> : <Download size={12} />}
              {t("setUpAction")}
            </Button>
          )}
        </div>

        {/* Tier-1 (WebLLM) manifest models. */}
        {tier1Models.map((model) => {
          const asset = assets.find((a) => a.id === model.id) || null;
          const installed = isTier1ModelCached(model.id);
          const inFlight =
            !!asset && (asset.state === "downloading" || asset.state === "verifying" || asset.state === "queued");
          const failed = !!asset && asset.state === "error";
          const fitState = caps ? modelFitState(model, caps) : "fits";
          const fits = fitState === "fits";
          const active = override === model.id;
          const live = liveModelId === model.id;
          // Bug A fix: downloaded weights alone don't mean this model
          // can execute — WebGPU must be confirmed available too.
          const runnableNow = webgpuReady === true;

          if (installed) {
            // Downloaded → immediately switchable as the active
            // on-device model (user pin always wins over auto). Ring
            // highlight while THIS model is the one actually answering
            // the current turn (live !== active: active is the user's
            // pinned choice, live is real-time "is it working now").
            return (
              <button
                key={model.id}
                type="button"
                onClick={() => {
                  setModelOverride(model.id);
                  // Warm-up problem fix: pinning a specific downloaded
                  // model should warm THAT model immediately, not on
                  // the next send — same rationale as selectOnDevice
                  // above.
                  void detectCapabilities().then((c) => prewarmTier1Engine(c));
                }}
                aria-pressed={active}
                className={cn(
                  "flex w-full items-center justify-between gap-2 rounded-md border-0 bg-transparent p-0 px-2 py-1.5 text-left text-xs shadow-none transition-colors hover:bg-secondary/60",
                  active ? "bg-secondary/60 text-foreground" : "text-muted-foreground",
                  live && runnableNow && "bg-primary/10 ring-1 ring-primary"
                )}
              >
                <span className="min-w-0">
                  <span className={cn("block truncate", active && "font-medium text-foreground")}>
                    {model.displayName}
                  </span>
                  <span
                    className={cn(
                      "block truncate text-[11px]",
                      runnableNow ? "text-muted-foreground" : "text-destructive"
                    )}
                  >
                    {runnableNow ? (
                      <>
                        {formatBytes(model.sizeBytes)} · {t("downloadedTag")}
                        {active ? ` · ${t("activeTag")}` : ""}
                        {live ? ` · ${t("runningNowTag")}` : ""}
                      </>
                    ) : (
                      <>
                        {formatBytes(model.sizeBytes)} ·{" "}
                        {t("downloadedNoWebgpuTag", { fallback: hostedFallbackTarget })}
                      </>
                    )}
                  </span>
                </span>
                <span
                  className={cn(
                    "size-2 shrink-0 rounded-full border",
                    !runnableNow
                      ? "border-destructive bg-destructive/60"
                      : active
                        ? "border-primary bg-primary"
                        : "border-border bg-transparent"
                  )}
                  aria-hidden="true"
                />
              </button>
            );
          }

          if (inFlight) {
            return (
              <div key={model.id} className="px-2 py-1.5 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <span className="min-w-0 truncate text-foreground">{model.displayName}</span>
                  <span className="shrink-0 text-[11px] tabular-nums text-muted-foreground">
                    {asset!.state === "verifying"
                      ? t("verifyingLabel")
                      : asset!.state === "queued"
                        ? t("queuedLabel")
                        : t("downloadingPercent", { percent: percentOf(asset!) })}
                  </span>
                </div>
                <div className="mt-1 h-1 w-full overflow-hidden rounded-full bg-border">
                  <div
                    className="h-full rounded-full bg-primary transition-[width]"
                    style={{ width: `${percentOf(asset!)}%` }}
                  />
                </div>
              </div>
            );
          }

          // Not downloaded (or a failed attempt) → greyed with size +
          // an explicit Download/Retry affordance. A poor-fit model
          // stays downloadable with a plain warning (never a block —
          // the router's fall-through catches a hard failure).
          return (
            <div key={model.id} className="flex items-center justify-between gap-2 px-2 py-1.5 text-xs">
              <span className="min-w-0">
                <span className="block truncate text-foreground">{model.displayName}</span>
                <span
                  className={cn(
                    "block truncate text-[11px]",
                    failed || fitState === "no" ? "text-destructive" : "text-muted-foreground"
                  )}
                >
                  {formatBytes(model.sizeBytes)} ·{" "}
                  {failed && asset?.error
                    ? asset.error
                    : fits
                      ? t("notDownloadedTag")
                      : fitState === "unverified"
                        ? t("fitsUnverified")
                        : t("fitsWarning")}
                </span>
                {/* The typed failure's full sentence (what happened and
                    what retrying does). The line above is only its
                    title, truncated. Plain English from
                    resilience/failures.ts, like the other surfaces. */}
                {failed && asset?.failureReason && (
                  <span className="mt-0.5 block whitespace-normal text-[11px] leading-relaxed text-muted-foreground">
                    {describeDownloadFailure(asset.failureReason, { neededBytes: model.sizeBytes }).message}
                  </span>
                )}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  void handleTier1Download(model);
                }}
              >
                <Download size={12} />
                {failed ? t("retryDownloadAction") : t("downloadAction")}
              </Button>
            </div>
          );
        })}
        {tier1Models.length === 0 && (
          <p className="px-2 py-1 text-[11px] text-muted-foreground">{t("noModelsAvailable")}</p>
        )}
      </div>
    </div>
  );
}
