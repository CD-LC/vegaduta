"use client";

// TASK A2 — the "VegaDūta Edge" control center: device verdict + mode master
// toggle + live on-device indicator (hero), the model MARKETPLACE with real
// downloads and visible progress, the installed-models manager, and the
// privacy band. Rendered by app/edge/page.tsx inside AppShell.
//
// lib/edge is consumed READ-ONLY (agent A1 extends it in parallel); the three
// contracts that don't exist there yet (tri-state preference, in-use
// observable, engine-owned deletes) come from ./edgeConsoleBridge — see that
// file's header for the reconciliation plan.
//
// User-facing strings go through t("edge.*") with the fragment file this
// task ships (en.json itself is fragile and off-limits); EDGE_BRANDING
// strings are reused where copy already exists there (branding is a
// deliberate seam, not scattered literals).
//
// Split for the file-size cap: the per-model cards live in ./EdgeModelCards
// and the Installed rows are derived by ./edgeInstalledRows.

import { useEdgeConsoleData } from "./useEdgeConsoleData";
import EdgeHeroStrip from "./EdgeHeroStrip";
import MarketCard from "./EdgeMarketCard";
import { InstalledActions, InstalledRowsList } from "./EdgeInstalledList";
import { EdgeDataShieldSection, EdgeBrowserToolsSection } from "./EdgeSections";
import { OnDeviceModelCard, TextModelCard } from "./EdgeModelCards";
import { buildInstalledRows } from "./edgeInstalledRows";
import { deviceVerdict, type DeviceVerdict } from "./edgeConsoleHelpers";
import { formatBytes } from "../../lib/format";
import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Download, Eye, Film, Globe, Image as ImageIcon, Mic, Music, ShieldCheck, Trash2, Volume2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { useEdgeModelFilter } from "./EdgeModelFilter";
import { usePageReady } from "../RouteTransition";
import BrandText from "../BrandText";
import { Button } from "../ui/button";
import { ConfirmDialog } from "../ui/confirm-dialog";
import {
  edgeEnabled,
  modelFitState,
  getDeclaredMemoryGB,
  setDeclaredMemoryGB,
  selectBestModel,
  type ManifestModel,
} from "../../lib/edge/capabilities";
import { downloadManager, type AssetStatus } from "../../lib/edge/downloadManager";
import { isTier1ModelCached, redownloadTier1Model, startTier1Download } from "../../lib/edge/tier1";
import { createTier0Session, tier0Availability, TIER0_ASSET_ID } from "../../lib/edge/tier0";
import { ensureVisionModel } from "../../lib/edge/vision";
import { ensureSttModel } from "../../lib/edge/audio/whisper";
import { ensureTtsModel } from "../../lib/edge/audio/kokoro";
import { ensureImageGenModel } from "../../lib/edge/imagegen";
import { ensureAudioGenModel } from "../../lib/edge/audiogen";
import { listMediaArtifacts } from "../../lib/edge/mediaRegistry";
import { EDGE_BRANDING } from "../../lib/edge/branding";
import {
  AUDIO_GEN_ASSET_ID,
  AUDIO_GEN_MODEL_SIZE_BYTES,
  deleteAudioGenModel,
  deleteImageGenModel,
  deleteSttModel,
  deleteTier1Model,
  deleteTtsModel,
  deleteVisionModel,
  IMAGE_GEN_ASSET_ID,
  IMAGE_GEN_MODEL_SIZE_BYTES,
  isAudioGenModelInstalled,
  isImageGenModelInstalled,
  isSttModelInstalled,
  isTtsModelInstalled,
  isVisionModelInstalled,
  setEdgeMode,
  STT_ASSET_ID,
  STT_MODEL_SIZE_BYTES,
  TTS_ASSET_ID,
  TTS_MODEL_SIZE_BYTES,
  VISION_ASSET_ID,
  VISION_MODEL_SIZE_BYTES,
  type EdgeMode,
} from "./edgeConsoleBridge";

export default function EdgeConsole() {
  const t = useTranslations("edge");
  const enabled = edgeEnabled();

  const {
    caps,
    textManifest,
    manifestLoaded,
    assets,
    mode,
    override,
    inUse,
    tier0Status,
    setTier0Status,
    motionEngines,
    motionBytesById,
    storage,
    installedTick,
    setInstalledTick,
    refreshStorage,
  } = useEdgeConsoleData(enabled);
  const [declaredGB, setDeclaredGB] = useState<number | null>(null);
  useEffect(() => setDeclaredGB(getDeclaredMemoryGB()), []);
  const [tier0Busy, setTier0Busy] = useState(false);
  const [busyIds, setBusyIds] = useState<Record<string, boolean>>({});

  usePageReady(!enabled || (caps !== null && manifestLoaded));

  const assetById = useMemo(() => {
    const map = new Map<string, AssetStatus>();
    for (const a of assets) map.set(a.id, a);
    return map;
  }, [assets]);

  const textModels = useMemo(() => (textManifest?.models || []).filter((m) => m.tier === 1), [textManifest]);

  // The model auto mode would run for an ORDINARY turn — the "Auto pick"
  // marker. EDGE-C call-site wiring: the console previews a turn that
  // doesn't exist yet, so the complexity signal is an explicit empty one
  // ("simple" — the default conversational case, which picks the smallest
  // fast-tagged model); complex turns escalate at route time, not here.
  const autoPick = useMemo(() => {
    if (!caps || !textManifest) return null;
    const selection = selectBestModel({ ...textManifest, models: textModels }, caps, "auto", { messageLength: 0 });
    return selection.kind === "model" ? selection.model : null;
  }, [caps, textManifest, textModels]);

  const nowRunningName = useMemo(() => {
    if (!inUse.active) return null;
    if (!inUse.modelId) return EDGE_BRANDING.shortName;
    if (inUse.modelId === TIER0_ASSET_ID) return EDGE_BRANDING.tier0ModelDisplayName;
    const model = textModels.find((m) => m.id === inUse.modelId);
    return model?.displayName || inUse.modelId;
  }, [inUse, textModels]);

  const setBusy = (id: string, value: boolean) => setBusyIds((prev) => ({ ...prev, [id]: value }));

  // Client-side paging for the two catalog lists (above the `!enabled` early return: hook order).
  const textModelFilter = useEdgeModelFilter(textModels, (m) =>
    caps ? modelFitState(m, caps, declaredGB) === "no" : false
  );
  const textModelsPagination = usePagination(textModelFilter.visible);
  const enginesPagination = usePagination(motionEngines);

  // ---- Actions ----------------------------------------------------------

  const downloadTextModel = async (model: ManifestModel) => {
    if (!caps || busyIds[model.id]) return;
    setBusy(model.id, true);
    try {
      await startTier1Download(caps, model.id);
      setInstalledTick((n) => n + 1);
      refreshStorage();
    } finally {
      setBusy(model.id, false);
    }
  };

  // 2026-09-19: the way out for an installed model that no longer loads
  // (getTier1CacheHealth) — evict the old files and download fresh. Explicit
  // click only, like every other download here.
  const redownloadTextModel = async (model: ManifestModel) => {
    if (!caps || busyIds[model.id]) return;
    setBusy(model.id, true);
    try {
      await redownloadTier1Model(caps, model.id);
      setInstalledTick((n) => n + 1);
      refreshStorage();
    } finally {
      setBusy(model.id, false);
    }
  };

  // The optional vision / speech-to-text / text-to-speech models share one flow.
  const downloadAsset = async (id: string, ensure: () => Promise<unknown>) => {
    if (busyIds[id]) return;
    setBusy(id, true);
    try {
      await ensure();
      setInstalledTick((n) => n + 1);
      refreshStorage();
    } catch {
      // Failure state already surfaced through the shared asset row.
    } finally {
      setBusy(id, false);
    }
  };

  const setUpTier0 = async () => {
    if (tier0Busy) return;
    setTier0Busy(true);
    try {
      // Creating a Prompt API session triggers/joins Chrome's own model
      // download; progress is mirrored into the shared asset rows by tier0.ts.
      const session = await createTier0Session();
      session.destroy();
    } catch {
      // Availability re-check below tells the honest state either way.
    } finally {
      try {
        setTier0Status(await tier0Availability());
      } catch {
        setTier0Status("unavailable");
      }
      setTier0Busy(false);
    }
  };

  const [pendingDelete, setPendingDelete] = useState<{ id: string; name: string; run: () => Promise<void> } | null>(
    null
  );
  const confirmAndDelete = async (id: string, name: string, run: () => Promise<void>) => {
    setPendingDelete({ id, name, run });
  };
  const runDelete = async (id: string, run: () => Promise<void>) => {
    setBusy(id, true);
    try {
      await run();
      setInstalledTick((n) => n + 1);
      refreshStorage();
    } finally {
      setBusy(id, false);
    }
  };

  // ---- Flag-off state ---------------------------------------------------

  if (!enabled) {
    return (
      <div className="rounded-lg border border-border bg-card p-6 text-sm text-muted-foreground">
        <BrandText text={t("disabled")} />
      </div>
    );
  }

  // ---- Derived hero state ----------------------------------------------

  const verdict: DeviceVerdict | null = caps ? deviceVerdict(caps) : null;
  const verdictLabel =
    verdict === "great"
      ? t("hero.verdictGreat")
      : verdict === "good"
        ? t("hero.verdictGood")
        : verdict === "basic"
          ? t("hero.verdictBasic")
          : verdict === "none"
            ? t("hero.verdictNone")
            : t("hero.checking");

  const modeOptions: Array<{ value: EdgeMode; label: string; hint: string; disabled?: boolean }> = [
    {
      value: "edge-first",
      label: t("mode.edgeFirst"),
      hint: t("mode.edgeFirstHint"),
      disabled: verdict === "none",
    },
    { value: "manual", label: t("mode.manual"), hint: t("mode.manualHint") },
    { value: "hosted-only", label: t("mode.hostedOnly"), hint: t("mode.hostedOnlyHint") },
  ];

  const tier0Installed = tier0Status === "available";
  const visionInstalled = isVisionModelInstalled();
  const sttInstalled = isSttModelInstalled();
  const ttsInstalled = isTtsModelInstalled();
  const imageGenInstalled = isImageGenModelInstalled();
  const audioGenInstalled = isAudioGenModelInstalled();
  const generatedMedia = listMediaArtifacts();

  // UX gap fix: vision/STT/TTS are structurally useless for a chat/call turn
  // without a reasoning (text) model also ready — routeTurn's "never
  // partially local" rule discards a successful local transcript and falls
  // all the way back to hosted if no local reasoning model is ready. Used
  // below to gate a small cross-reference note on those three cards only;
  // it disappears the moment either the built-in model or any text model is
  // actually installed.
  const reasoningModelReady = tier0Installed || textModels.some((m) => isTier1ModelCached(m.id));

  // ---- Installed rows (feeds section 3) ---------------------------------

  const installedRows = buildInstalledRows({
    tier0Installed,
    visionInstalled,
    sttInstalled,
    ttsInstalled,
    textModels,
    motionEngines,
    motionBytesById,
    override,
    mode,
    autoPickId: autoPick?.id ?? null,
    confirmAndDelete: (id, name, run) => void confirmAndDelete(id, name, run),
  });
  // installedTick only exists to invalidate the localStorage-derived reads
  // above on install/delete; reference it so the dependency is honest.
  void installedTick;

  return (
    <div className="space-y-8">
      {/* ---------------- 1. HERO strip ---------------- */}
      <EdgeHeroStrip
        caps={caps}
        verdictLabel={verdictLabel}
        declaredGB={declaredGB}
        onDeclaredGB={(v) => {
          setDeclaredMemoryGB(v);
          setDeclaredGB(v);
        }}
        tier0Status={tier0Status}
        inUse={inUse}
        nowRunningName={nowRunningName}
        mode={mode}
        modeOptions={modeOptions}
      />

      {/* ---------------- 2. MARKETPLACE grid ---------------- */}
      <section>
        <div className="mb-3">
          <h2 className="text-base font-semibold text-foreground">{t("market.title")}</h2>
          <p className="text-sm text-muted-foreground">{t("market.subtitle")}</p>
        </div>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
          {/* Chrome built-in card */}
          <MarketCard
            icon={<Globe size={14} aria-hidden="true" />}
            name={EDGE_BRANDING.tier0ModelDisplayName}
            purpose={t("market.purposeBuiltin")}
            sizeLabel={t("market.builtinManaged")}
            fitsLabel={
              tier0Status === "checking"
                ? t("hero.checking")
                : tier0Status === "available"
                  ? t("market.builtinReady")
                  : tier0Status === "downloadable"
                    ? t("market.builtinAvailable")
                    : tier0Status === "downloading"
                      ? t("market.builtinDownloading")
                      : t("market.builtinUnavailable")
            }
            fitsWarn={tier0Status === "unavailable" || tier0Status === "unknown"}
            detail={t("market.builtinDetail")}
            asset={assetById.get(TIER0_ASSET_ID) || null}
            installed={tier0Installed}
            footer={
              tier0Status === "downloadable" ? (
                <Button size="sm" onClick={() => void setUpTier0()} disabled={tier0Busy}>
                  <Download size={13} aria-hidden="true" />
                  {tier0Busy ? t("market.builtinDownloading") : t("market.builtinSetup")}
                </Button>
              ) : tier0Installed ? (
                // The built-in model isn't a manifest entry to pin — "Use
                // now" turns edge-first routing on, which serves tier 0 when
                // no downloaded model outranks it.
                <InstalledActions
                  useNowLabel={t("market.useNow")}
                  onUseNow={() => setEdgeMode("edge-first")}
                  active={mode === "edge-first" && !textModels.some((m) => isTier1ModelCached(m.id))}
                  activeLabel={t("market.active")}
                />
              ) : null
            }
          />

          {/* Text models (tier 1) */}
          {textModelFilter.controls}
          {textModelsPagination.pageItems.map((model) => (
            <TextModelCard
              key={model.id}
              model={model}
              asset={assetById.get(model.id) || null}
              caps={caps}
              declaredGB={declaredGB}
              busy={!!busyIds[model.id]}
              active={override === model.id}
              t={t}
              onDownload={() => void downloadTextModel(model)}
              onRedownload={() => void redownloadTextModel(model)}
              onDelete={() => void confirmAndDelete(model.id, model.displayName, () => deleteTier1Model(model.id))}
            />
          ))}
          {textModelsPagination.totalItems > 0 && (
            <div className="md:col-span-2 xl:col-span-3">
              <PaginationControls
                page={textModelsPagination.page}
                totalPages={textModelsPagination.totalPages}
                totalItems={textModelsPagination.totalItems}
                pageSize={textModelsPagination.pageSize}
                onPageChange={textModelsPagination.setPage}
                onPageSizeChange={textModelsPagination.setPageSize}
                itemLabel="models"
              />
            </div>
          )}

          {/* On-device vision (SmolVLM) */}
          <OnDeviceModelCard
            icon={<Eye size={14} aria-hidden="true" />}
            name={EDGE_BRANDING.visionModelDisplayName}
            purpose={t("market.purposeVision")}
            sizeBytes={VISION_MODEL_SIZE_BYTES}
            detail={EDGE_BRANDING.visionDownloadDetails}
            caps={caps}
            reasoningModelReady={reasoningModelReady}
            asset={assetById.get(VISION_ASSET_ID) || null}
            installed={visionInstalled}
            busy={!!busyIds[VISION_ASSET_ID]}
            t={t}
            onDownload={() => void downloadAsset(VISION_ASSET_ID, ensureVisionModel)}
            onDelete={() =>
              void confirmAndDelete(VISION_ASSET_ID, EDGE_BRANDING.visionModelDisplayName, deleteVisionModel)
            }
          />

          {/* On-device speech-to-text (Whisper) */}
          <OnDeviceModelCard
            icon={<Mic size={14} aria-hidden="true" />}
            name={EDGE_BRANDING.sttModelDisplayName}
            purpose={t("market.purposeStt")}
            sizeBytes={STT_MODEL_SIZE_BYTES}
            detail={EDGE_BRANDING.sttDownloadDetails}
            caps={caps}
            reasoningModelReady={reasoningModelReady}
            asset={assetById.get(STT_ASSET_ID) || null}
            installed={sttInstalled}
            busy={!!busyIds[STT_ASSET_ID]}
            t={t}
            onDownload={() => void downloadAsset(STT_ASSET_ID, ensureSttModel)}
            onDelete={() => void confirmAndDelete(STT_ASSET_ID, EDGE_BRANDING.sttModelDisplayName, deleteSttModel)}
          />

          {/* On-device text-to-speech (Kokoro) */}
          <OnDeviceModelCard
            icon={<Volume2 size={14} aria-hidden="true" />}
            name={EDGE_BRANDING.ttsModelDisplayName}
            purpose={t("market.purposeTts")}
            sizeBytes={TTS_MODEL_SIZE_BYTES}
            detail={EDGE_BRANDING.ttsDownloadDetails}
            caps={caps}
            reasoningModelReady={reasoningModelReady}
            asset={assetById.get(TTS_ASSET_ID) || null}
            installed={ttsInstalled}
            busy={!!busyIds[TTS_ASSET_ID]}
            t={t}
            onDownload={() => void downloadAsset(TTS_ASSET_ID, ensureTtsModel)}
            onDelete={() => void confirmAndDelete(TTS_ASSET_ID, EDGE_BRANDING.ttsModelDisplayName, deleteTtsModel)}
          />

          {/* On-device image generation (SD-Turbo) — powers local_generate_image */}
          <OnDeviceModelCard
            icon={<ImageIcon size={14} aria-hidden="true" />}
            name={EDGE_BRANDING.imageGenModelDisplayName}
            purpose={t("market.purposeImageGen")}
            sizeBytes={IMAGE_GEN_MODEL_SIZE_BYTES}
            detail={EDGE_BRANDING.imageGenDownloadDetails}
            caps={caps}
            reasoningModelReady={reasoningModelReady}
            requiresFp16
            asset={assetById.get(IMAGE_GEN_ASSET_ID) || null}
            installed={imageGenInstalled}
            busy={!!busyIds[IMAGE_GEN_ASSET_ID]}
            t={t}
            onDownload={() => void downloadAsset(IMAGE_GEN_ASSET_ID, ensureImageGenModel)}
            onDelete={() =>
              void confirmAndDelete(IMAGE_GEN_ASSET_ID, EDGE_BRANDING.imageGenModelDisplayName, deleteImageGenModel)
            }
          />

          {/* On-device audio generation (MusicGen) — powers local_generate_audio */}
          <OnDeviceModelCard
            icon={<Music size={14} aria-hidden="true" />}
            name={EDGE_BRANDING.audioGenModelDisplayName}
            purpose={t("market.purposeAudioGen")}
            sizeBytes={AUDIO_GEN_MODEL_SIZE_BYTES}
            detail={EDGE_BRANDING.audioGenDownloadDetails}
            caps={caps}
            reasoningModelReady={reasoningModelReady}
            asset={assetById.get(AUDIO_GEN_ASSET_ID) || null}
            installed={audioGenInstalled}
            busy={!!busyIds[AUDIO_GEN_ASSET_ID]}
            t={t}
            onDownload={() => void downloadAsset(AUDIO_GEN_ASSET_ID, ensureAudioGenModel)}
            onDelete={() =>
              void confirmAndDelete(AUDIO_GEN_ASSET_ID, EDGE_BRANDING.audioGenModelDisplayName, deleteAudioGenModel)
            }
          />

          {/* Clip Studio motion engine(s) — one card per published/candidate
              interpolation engine (fast RIFE-class, quality FILM-class). */}
          {enginesPagination.pageItems.map((engine) => {
            const bytes = motionBytesById[engine.id] || 0;
            const installed = bytes > 0;
            return (
              <MarketCard
                key={engine.id}
                icon={<Film size={14} aria-hidden="true" />}
                name={engine.displayName}
                purpose={t("market.purposeMotion")}
                sizeLabel={
                  installed ? formatBytes(bytes) : engine.sizeBytes ? formatBytes(engine.sizeBytes) : t("hero.unknown")
                }
                fitsLabel={engine.url || installed ? t("market.fits") : t("market.motionNotPublished")}
                fitsWarn={false}
                detail={engine.detailName || t("market.motionDetail")}
                asset={assetById.get(engine.id) || null}
                installed={installed}
                footer={
                  <div className="flex items-center gap-2">
                    <Button size="sm" variant="outline" asChild>
                      <Link href="/clips">{t("market.motionOpenClips")}</Link>
                    </Button>
                    {installed && (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() =>
                          void confirmAndDelete(engine.id, engine.displayName, () =>
                            downloadManager.deleteAsset(engine.id)
                          )
                        }
                        disabled={!!busyIds[engine.id]}
                      >
                        <Trash2 size={13} aria-hidden="true" />
                        {t("market.delete")}
                      </Button>
                    )}
                  </div>
                }
              />
            );
          })}
          {enginesPagination.totalItems > 0 && (
            <div className="md:col-span-2 xl:col-span-3">
              <PaginationControls
                page={enginesPagination.page}
                totalPages={enginesPagination.totalPages}
                totalItems={enginesPagination.totalItems}
                pageSize={enginesPagination.pageSize}
                onPageChange={enginesPagination.setPage}
                onPageSizeChange={enginesPagination.setPageSize}
                itemLabel="engines"
              />
            </div>
          )}
        </div>
      </section>

      {/* ------- 2b. GENERATED MEDIA (this session) ------- */}
      {(imageGenInstalled || audioGenInstalled || generatedMedia.length > 0) && (
        <section>
          <div className="mb-3">
            <h2 className="text-base font-semibold text-foreground">{t("market.generatedMediaTitle")}</h2>
          </div>
          <div className="overflow-hidden rounded-lg border border-border">
            {generatedMedia.length === 0 ? (
              <div className="p-4 text-sm text-muted-foreground">{t("market.generatedMediaEmpty")}</div>
            ) : (
              <ul className="divide-y divide-border">
                {generatedMedia.map((m) => (
                  <li key={m.id} className="flex items-center gap-3 px-4 py-3">
                    {m.kind === "image" ? (
                      // eslint-disable-next-line @next/next/no-img-element -- session-local blob URL, not a remote asset
                      <img
                        src={m.url}
                        alt={m.description}
                        className="h-12 w-12 rounded border border-border object-cover"
                      />
                    ) : (
                      // eslint-disable-next-line jsx-a11y/media-has-caption -- generated clip previews have no caption source
                      <audio src={m.url} controls preload="metadata" className="h-8 w-56" />
                    )}
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm text-foreground">{m.description}</p>
                      <p className="text-xs text-muted-foreground">
                        {m.kind === "image" ? `${m.width}×${m.height}` : `${m.durationSeconds?.toFixed(1)}s`}
                        {" · "}
                        {formatBytes(m.byteSize)}
                      </p>
                    </div>
                    <Button size="sm" variant="ghost" asChild>
                      <a href={m.url} download={`${m.id}${m.kind === "image" ? ".png" : ".wav"}`}>
                        <Download size={13} aria-hidden="true" />
                      </a>
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>
      )}

      {/* ---------------- 3. INSTALLED section ---------------- */}
      <section>
        <div className="mb-3">
          <h2 className="text-base font-semibold text-foreground">{t("installed.title")}</h2>
          <p className="text-sm text-muted-foreground">{t("installed.subtitle")}</p>
        </div>
        <div className="overflow-hidden rounded-lg border border-border">
          {installedRows.length === 0 ? (
            <div className="p-4 text-sm text-muted-foreground">{t("installed.empty")}</div>
          ) : (
            <InstalledRowsList rows={installedRows} t={t} busyIds={busyIds} />
          )}
          {storage && storage.quotaBytes > 0 && (
            <div className="border-t border-border bg-secondary/30 px-4 py-2 text-[11px] text-muted-foreground">
              {t("installed.storage", {
                used: formatBytes(storage.usageBytes),
                quota: formatBytes(storage.quotaBytes),
              })}
            </div>
          )}
        </div>
      </section>

      {/* ------- 3b. DATA SHIELD + BROWSER TOOLS (E-3 / E-2b) ------- */}
      <EdgeDataShieldSection />
      <EdgeBrowserToolsSection />

      {/* ---------------- 4. PRIVACY band ---------------- */}
      <section className="rounded-xl border border-border bg-secondary/30 p-5">
        <div className="flex items-start gap-3">
          <ShieldCheck size={18} className="mt-0.5 shrink-0 text-primary" aria-hidden="true" />
          <div>
            <h2 className="text-sm font-semibold text-foreground">{t("privacy.title")}</h2>
            <p className="mt-1 max-w-3xl text-sm leading-relaxed text-muted-foreground">{t("privacy.body")}</p>
          </div>
        </div>
      </section>
      <ConfirmDialog
        open={pendingDelete !== null}
        onOpenChange={(o) => {
          if (!o) setPendingDelete(null);
        }}
        title={pendingDelete ? t("installed.deleteConfirm", { model: pendingDelete.name }) : ""}
        confirmLabel="OK"
        destructive
        onConfirm={async () => {
          const d = pendingDelete;
          if (d) await runDelete(d.id, d.run);
        }}
      />
    </div>
  );
}
