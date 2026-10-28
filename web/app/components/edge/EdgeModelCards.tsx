"use client";

// The per-model marketplace cards of the Edge console (EdgeConsole owns the
// state and actions): a tier-1 text model card with its health/redownload
// problem, and the shared card for the optional on-device vision / speech-to-text
// / text-to-speech models.

import type { ReactNode } from "react";
import { Download, MessageSquareText, Trash2 } from "lucide-react";
import MarketCard from "./EdgeMarketCard";
import { InstalledActions } from "./EdgeInstalledList";
import { formatBytes } from "../../lib/format";
import { Button } from "../ui/button";
import { modelFitState, type EdgeCapabilities, type ManifestModel } from "../../lib/edge/capabilities";
import type { AssetStatus } from "../../lib/edge/downloadManager";
import { getTier1CacheHealth, isTier1ModelCached, tier1DownloadDetails } from "../../lib/edge/tier1";
import { describeDownloadFailure } from "../../lib/edge/resilience/failures";
import { pinModel, setEdgeMode } from "./edgeConsoleBridge";
import type { useTranslations } from "../../lib/i18n";

type T = ReturnType<typeof useTranslations>;

const NEEDS_TEXT_MODEL_NOTE =
  "Works together with a text model above — on-device replies need one installed too, or this falls back to hosted.";

/** One tier-1 text model: fit, download / use-now / delete, and the redownload way out of a broken install. */
export function TextModelCard({
  model,
  asset,
  caps,
  declaredGB,
  busy,
  active,
  t,
  onDownload,
  onRedownload,
  onDelete,
}: {
  model: ManifestModel;
  asset: AssetStatus | null;
  caps: EdgeCapabilities | null;
  declaredGB: number | null;
  busy: boolean;
  /** This model is the pinned override. */
  active: boolean;
  t: T;
  onDownload: () => void;
  onRedownload: () => void;
  onDelete: () => void;
}) {
  const installed = isTier1ModelCached(model.id);
  const fitState = caps ? modelFitState(model, caps, declaredGB) : null;
  const inFlight = asset && (asset.state === "downloading" || asset.state === "verifying" || asset.state === "queued");
  // 2026-09-19: an installed model that has stopped loading (engine
  // upgrade, registry bump, damaged files) used to look perfectly
  // healthy here while every reply quietly ran on the hosted model.
  const health = installed && !inFlight ? getTier1CacheHealth(model.id) : null;
  // neededBytes: the only action offered here (redownloadTextModel)
  // deletes the stored files and fetches the WHOLE model again, so
  // the copy states that and the size before the click.
  const healthFailure =
    health && health.offerRedownload && health.reason
      ? describeDownloadFailure(health.reason, { neededBytes: model.sizeBytes })
      : null;
  return (
    <MarketCard
      // Text models only: the hosted switch is global, and offering it
      // on an optional vision/voice card would turn off working
      // on-device text replies over an unrelated failure.
      onUseHosted={() => setEdgeMode("hosted-only")}
      problem={
        healthFailure
          ? {
              title: healthFailure.title,
              message: healthFailure.message,
              actionLabel: healthFailure.actionLabel,
              onAction: onRedownload,
              disabled: busy || !caps,
            }
          : null
      }
      icon={<MessageSquareText size={14} aria-hidden="true" />}
      name={model.displayName}
      purpose={t("market.purposeText")}
      sizeLabel={formatBytes(model.sizeBytes)}
      fitsLabel={
        fitState == null
          ? t("hero.checking")
          : fitState === "fits"
            ? t("market.fits")
            : fitState === "unverified"
              ? t("market.unverified")
              : t("market.noFit")
      }
      fitsWarn={fitState === "no"}
      detail={model.detailName || tier1DownloadDetails(model)}
      asset={asset}
      installed={installed}
      footer={
        installed ? (
          <div className="flex items-center gap-2">
            <InstalledActions
              useNowLabel={t("market.useNow")}
              onUseNow={() => pinModel(model.id)}
              active={active}
              activeLabel={t("market.active")}
            />
            <Button
              size="sm"
              variant="ghost"
              onClick={onDelete}
              disabled={busy}
              aria-label={`${t("market.delete")} ${model.displayName}`}
            >
              <Trash2 size={13} aria-hidden="true" />
              {t("market.delete")}
            </Button>
          </div>
        ) : inFlight ? null : (
          <Button size="sm" onClick={onDownload} disabled={busy || !caps}>
            <Download size={13} aria-hidden="true" />
            {t("market.download")}
          </Button>
        )
      }
    />
  );
}

/** An optional on-device model (vision, speech-to-text, text-to-speech): download or delete. */
export function OnDeviceModelCard({
  icon,
  name,
  purpose,
  sizeBytes,
  detail,
  caps,
  reasoningModelReady,
  requiresFp16,
  asset,
  installed,
  busy,
  t,
  onDownload,
  onDelete,
}: {
  icon: ReactNode;
  name: string;
  purpose: string;
  sizeBytes: number;
  detail: string;
  caps: EdgeCapabilities | null;
  /** A text model (or the built-in one) is ready; otherwise a cross-reference note shows. */
  reasoningModelReady: boolean;
  /** Weights are fp16-only: adapters without the shader-f16 feature can't run
   * them — label honestly and hold the download rather than charge the person
   * gigabytes for a session-create that always fails. */
  requiresFp16?: boolean;
  asset: AssetStatus | null;
  installed: boolean;
  busy: boolean;
  t: T;
  onDownload: () => void;
  onDelete: () => void;
}) {
  const fp16Missing =
    requiresFp16 === true && caps != null && caps.webgpu.available === true && caps.webgpu.shaderF16 !== true;
  return (
    <MarketCard
      icon={icon}
      name={name}
      purpose={purpose}
      sizeLabel={formatBytes(sizeBytes)}
      fitsLabel={
        caps == null
          ? t("hero.checking")
          : fp16Missing
            ? t("market.noFit")
            : caps.webgpu.available
              ? t("market.fits")
              : t("market.fitsWasm")
      }
      fitsWarn={fp16Missing}
      detail={detail}
      note={reasoningModelReady ? null : NEEDS_TEXT_MODEL_NOTE}
      asset={asset}
      installed={installed}
      footer={
        installed ? (
          <Button size="sm" variant="ghost" onClick={onDelete} disabled={busy}>
            <Trash2 size={13} aria-hidden="true" />
            {t("market.delete")}
          </Button>
        ) : (
          <Button size="sm" onClick={onDownload} disabled={busy || fp16Missing}>
            <Download size={13} aria-hidden="true" />
            {t("market.download")}
          </Button>
        )
      }
    />
  );
}
