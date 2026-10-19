"use client";

// The compact, non-blocking edge status chip.
//
// Shows the current on-device mode at a glance ("On-device · ready",
// "Warming up…", a download % while one is running) and expands into a small
// panel with per-model statuses + the model picker (Auto (recommended) /
// every manifest model with a fits-indicator / Always use hosted). The picker
// writes the localStorage override that selectBestModel() and the tier router
// honour — user selection always wins over auto-scoring.
//
// Purely presentational + hooks into capabilities/downloadManager. No global
// state library: cross-component sync rides the EDGE_PREFS_EVENT window event
// the preference setters in capabilities.ts dispatch.
//
// Strings are deliberately plain English — this is flag-gated experimental UI
// and the i18n en.json file is fragile and off-limits (see web-i18n memory).
// Exception (TASK A2): the two NEW aria labels added with the /edge console
// go through t("edge.chip.*") — their keys ship in the task's i18n fragment
// for the later merge pass, per the parallel-agent common rule.

import { formatBytes } from "../../lib/format";
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { AlertTriangle, ChevronDown, Cpu, Pause, Play, X } from "lucide-react";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";
import {
  detectCapabilities,
  edgeEnabled,
  getEdgeOptIn,
  getStoredModelOverride,
  modelFitState,
  setEdgeOptIn,
  setModelOverride,
  EDGE_PREFS_EVENT,
  type EdgeCapabilities,
  type ManifestModel,
  type ModelManifest,
  type ModelOverride,
} from "../../lib/edge/capabilities";
import { downloadManager, type AssetStatus } from "../../lib/edge/downloadManager";
// Reliability blueprint PART1 "Edge AI hardening" / PART2 Phase 2: the chip
// must never sit on a stale "ready"/"warming up" label when the device is
// actually offline or the hosted circuit breaker has tripped — these three
// give it that visibility without inventing a new polling loop of its own.
import { subscribeEdgeOnlineStatus } from "../../lib/edge/offline";
import {
  EDGE_TIER0_CIRCUIT_TARGET,
  EDGE_TIER1_CIRCUIT_TARGET,
  subscribeEdgeRecovery,
  type EdgeRecoveryEvent,
} from "../../lib/edge/tierRouter";
import { getCircuitBreakerState } from "../../lib/requestLifecycle";
// Brand-first naming: ALL display copy comes from the branding
// seam — primary copy leads with the brand, model identities stay
// details-panel-only. White-label tenants swap these centrally later.
import { HOSTED_FALLBACK_NOTE, describeDownloadFailure } from "../../lib/edge/resilience/failures";
import { EDGE_BRANDING } from "../../lib/edge/branding";
import { TIER0_ASSET_ID } from "../../lib/edge/tier0";
// TASK A2: the in-use observable ({ active, modelId? }) — bridge shim until
// A1's lib/edge module lands (see edgeConsoleBridge's header).
import { getEdgeInUse, subscribeEdgeInUse, type EdgeInUseState } from "./edgeConsoleBridge";

// globals.css styles every bare <button> with padding/border/background —
// the same neutralization every icon button in this app carries.
const bareButtonClass = "flex items-center rounded border-0 bg-transparent p-0 shadow-none transition-colors";

function percentOf(asset: AssetStatus): number {
  if (!asset.totalBytes) return 0;
  return Math.min(100, Math.round((asset.receivedBytes / asset.totalBytes) * 100));
}

/** Byte counts are only meaningful when totalBytes is a real size — Chrome's
 * built-in model reports a 0..1 fraction with totalBytes 1 (see AssetStatus). */
function hasRealByteCounts(asset: AssetStatus): boolean {
  return asset.totalBytes > 1024;
}

const ASSET_STATE_LABEL: Record<AssetStatus["state"], string> = {
  queued: "Queued",
  downloading: "Downloading",
  paused: "Paused",
  verifying: "Verifying",
  ready: "Ready",
  error: "Failed",
  cancelled: "Cancelled",
};

export default function EdgeSetupChip() {
  // edgeEnabled() is a build-time constant, so this early-out is stable
  // across renders (hook order never varies at runtime).
  const enabled = edgeEnabled();
  const t = useTranslations("edge");
  const [caps, setCaps] = useState<EdgeCapabilities | null>(null);
  const [assets, setAssets] = useState<AssetStatus[]>([]);
  const [manifest, setManifest] = useState<ModelManifest | null>(null);
  const [override, setOverride] = useState<ModelOverride>("auto");
  const [optIn, setOptIn] = useState(false);
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const [inUse, setInUse] = useState<EdgeInUseState>({ active: false });
  const [open, setOpen] = useState(false);
  // Reliability blueprint: network reachability, tier-router recovery probing,
  // and hosted circuit-breaker state — all quiet-by-default, only surfaced in
  // the label/panel when something is actually degraded.
  const [online, setOnline] = useState(true);
  const [recovering, setRecovering] = useState(false);
  const [circuitOpen, setCircuitOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const chevronRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!enabled || typeof window === "undefined") return undefined;
    let cancelled = false;
    detectCapabilities()
      .then((c) => {
        if (!cancelled) setCaps(c);
      })
      .catch(() => {});
    downloadManager
      .fetchManifest()
      .then((m) => {
        if (!cancelled) setManifest(m);
      })
      .catch(() => {});
    setAssets(downloadManager.listAssets());
    setUpdateAvailable(downloadManager.getUpdateState().available);
    const unsubscribe = downloadManager.subscribe((event) => {
      if (event.type === "asset") setAssets(downloadManager.listAssets());
      else if (event.type === "manifest") setManifest(event.manifest);
      else setUpdateAvailable(event.available);
    });
    const syncPrefs = () => {
      setOverride(getStoredModelOverride());
      setOptIn(getEdgeOptIn());
    };
    syncPrefs();
    window.addEventListener(EDGE_PREFS_EVENT, syncPrefs);
    setInUse(getEdgeInUse());
    const unsubscribeInUse = subscribeEdgeInUse(setInUse);
    const unsubscribeOnline = subscribeEdgeOnlineStatus(setOnline);
    const unsubscribeRecovery = subscribeEdgeRecovery((event: EdgeRecoveryEvent) => {
      setRecovering(event.recovering);
    });
    // getCircuitBreakerState is a plain read, not an observable — poll it
    // instead. Called once synchronously so the chip doesn't sit on a stale
    // default for up to 5s after mount.
    const pollCircuitState = () => {
      const open =
        getCircuitBreakerState(EDGE_TIER0_CIRCUIT_TARGET).open ||
        getCircuitBreakerState(EDGE_TIER1_CIRCUIT_TARGET).open;
      setCircuitOpen(open);
    };
    pollCircuitState();
    const circuitInterval = setInterval(pollCircuitState, 5000);
    return () => {
      cancelled = true;
      unsubscribe();
      unsubscribeInUse();
      unsubscribeOnline();
      unsubscribeRecovery();
      clearInterval(circuitInterval);
      window.removeEventListener(EDGE_PREFS_EVENT, syncPrefs);
    };
  }, [enabled]);

  // Close the panel on any click outside it, or on Escape - and return focus
  // to the chevron toggle that opened it (audit: neither was handled before).
  useEffect(() => {
    if (!open || typeof document === "undefined") return undefined;
    const onPointerDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setOpen(false);
        chevronRef.current?.focus();
      }
    };
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  if (!enabled) return null;

  // ---- Chip label -------------------------------------------------------
  const downloading = assets.find((a) => a.state === "downloading");
  const verifying = assets.find((a) => a.state === "verifying");
  const anyReady = assets.some((a) => a.state === "ready");
  const availability = caps?.promptApi.availability ?? "unavailable";
  const tier0Usable = availability === "available" || availability === "downloadable" || availability === "downloading";

  // TASK A2: name of the model serving the current on-device turn (in-use
  // observable). Model identity in the COMPACT label is allowed here — same
  // honesty rule as the panel's details rows.
  const inUseModelName = inUse.active
    ? inUse.modelId === TIER0_ASSET_ID
      ? EDGE_BRANDING.tier0ModelDisplayName
      : inUse.modelId
        ? manifest?.models.find((m: ManifestModel) => m.id === inUse.modelId)?.displayName || inUse.modelId
        : null
    : null;

  let label: string;
  if (inUse.active)
    // An active on-device turn already in progress outranks everything below
    // — swapping the label mid-generation would just be confusing, and the
    // turn itself doesn't depend on network/hosted health to finish.
    label = inUseModelName ? `${EDGE_BRANDING.shortName} · ${inUseModelName}` : EDGE_BRANDING.shortName;
  // Reliability blueprint: "something is actually wrong" signals outrank the
  // routine ready/warming/available/hosted labels below — being offline or
  // having the hosted breaker open is more informative than a stale-looking
  // "ready"/"Hosted mode" label, but neither hides an in-flight turn (above).
  else if (!online) label = EDGE_BRANDING.offlineLabel;
  else if (circuitOpen) label = EDGE_BRANDING.degradedLabel;
  else if (override === "hosted") label = EDGE_BRANDING.hostedModeLabel;
  else if (downloading) label = `${EDGE_BRANDING.shortName} · ${percentOf(downloading)}%`;
  // Bug fix (2026-08-06 diagnosis): the "verifying" state already carries a
  // real percent (progressReporter in tier1.ts computes it proportionally to
  // WebLLM's own progress fraction, downloading OR cache/warm-up) — it was
  // just never rendered. Chrome's own tier-0 download monitor doesn't
  // publish a matching AssetStatus here, so it keeps the static label.
  else if (verifying) label = `${EDGE_BRANDING.warmingUpLabel} · ${percentOf(verifying)}%`;
  else if (availability === "downloading") label = EDGE_BRANDING.warmingUpLabel;
  // F-1: unobtrusive "update available" state — only when nothing louder
  // (an active download / warm-up) is happening. Applying is always a click.
  else if (updateAvailable) label = `${EDGE_BRANDING.shortName} · ${EDGE_BRANDING.updateBadge}`;
  else if (anyReady || availability === "available") label = `${EDGE_BRANDING.shortName} · ${EDGE_BRANDING.readyLabel}`;
  else if (availability === "downloadable") label = `${EDGE_BRANDING.shortName} · ${EDGE_BRANDING.availableLabel}`;
  else label = `${EDGE_BRANDING.shortName} · ${EDGE_BRANDING.unavailableLabel}`;

  // ---- Model picker rows ------------------------------------------------
  const pickerRow = (value: ModelOverride, title: string, subtitle: string | null, warn: boolean) => {
    const selected = override === value || (value === "auto" && override === "auto");
    return (
      <button
        key={value}
        type="button"
        role="radio"
        aria-checked={selected}
        onClick={() => setModelOverride(value)}
        className={cn(
          bareButtonClass,
          "w-full justify-between gap-2 rounded-md px-2 py-1.5 text-left text-xs hover:bg-secondary",
          selected ? "bg-secondary/60 text-foreground" : "text-muted-foreground"
        )}
      >
        <span className="min-w-0">
          <span className={cn("block truncate", selected && "font-medium")}>{title}</span>
          {subtitle && (
            // QA-06: a fit/no-fit hint is guidance, not a destructive state —
            // muted 12px text with a small accent-tinted triangle when the
            // model may not fit, never red.
            <span className="flex items-center gap-1 text-xs text-muted-foreground">
              {warn && <AlertTriangle size={12} className="shrink-0 text-primary" aria-hidden="true" />}
              <span className="min-w-0 truncate">{subtitle}</span>
            </span>
          )}
        </span>
        <span
          className={cn(
            "size-2 shrink-0 rounded-full border",
            selected ? "border-primary bg-primary" : "border-border bg-transparent"
          )}
          aria-hidden="true"
        />
      </button>
    );
  };

  const modelRows = (manifest?.models || []).map((model: ManifestModel) => {
    const fitState = caps ? modelFitState(model, caps) : "fits";
    return pickerRow(
      model.id,
      `${model.displayName} (${formatBytes(model.sizeBytes)})`,
      fitState === "fits"
        ? "Fits this device"
        : fitState === "unverified"
          ? "Memory can't be checked in this browser - may work"
          : "May be slow or fail on this device",
      fitState === "no"
    );
  });

  return (
    // PRODUCT-TOUR ANCHOR: the tour's "edge-chip" step spotlights this chip as
    // the topbar's on-device settings control (model picker / hosted mode).
    <div className="relative topbar-collapsible" ref={rootRef} data-tour="edge-chip">
      {/* TASK A2: the chip is now two targets — the label navigates to the
          /edge console (primary), the chevron keeps the existing expand
          panel (secondary). */}
      <span className="flex items-center rounded-full border border-border bg-secondary/40 text-xs text-muted-foreground">
        {/* Plain <a>-backed Link, not a button + router.push: this is
            primarily a navigation target, so it must be middle-clickable,
            ctrl-clickable and openable in a new tab (audit). The visible
            status label stays the accessible name (no aria-label override) -
            a trailing sr-only span adds the action without hiding the
            live status text from assistive tech. */}
        <Link
          href="/edge"
          title={label}
          className={cn(
            bareButtonClass,
            "gap-1.5 rounded-l-full py-1 pl-2.5 pr-1 hover:bg-secondary hover:text-foreground"
          )}
        >
          <Cpu size={12} aria-hidden="true" />
          {/* In-use pulse: a subtle activity dot while a local turn runs. */}
          {inUse.active && (
            <span className="size-1.5 shrink-0 animate-pulse rounded-full bg-primary" aria-hidden="true" />
          )}
          <span className="topbar-label max-w-40 truncate">{label}</span>
          <span className="sr-only"> — {t("chip.openConsole")}</span>
        </Link>
        <button
          ref={chevronRef}
          type="button"
          onClick={() => setOpen((o) => !o)}
          aria-expanded={open}
          aria-label={t("chip.expand")}
          className={cn(
            bareButtonClass,
            "min-h-6 min-w-6 justify-center rounded-r-full py-1 pl-1 pr-2 hover:bg-secondary hover:text-foreground"
          )}
        >
          <ChevronDown size={11} className={cn("transition-transform", open && "rotate-180")} aria-hidden="true" />
        </button>
      </span>

      {open && (
        <div className="absolute right-0 top-full z-50 mt-2 w-72 rounded-lg border border-border bg-popover p-3 text-popover-foreground shadow-lg">
          <div className="mb-2 flex items-start justify-between gap-2">
            <span className="min-w-0">
              <span className="block text-xs font-semibold text-foreground">{EDGE_BRANDING.name}</span>
              {/* The brand promise leads (brand-first rule) — model names stay
                  further down in the details rows. */}
              <span className="block text-xs text-muted-foreground">{EDGE_BRANDING.promise}</span>
            </span>
            <button
              type="button"
              onClick={() => setOpen(false)}
              aria-label={`Close ${EDGE_BRANDING.name} panel`}
              className={cn(
                bareButtonClass,
                "size-7 shrink-0 justify-center text-muted-foreground hover:text-foreground"
              )}
            >
              <X size={12} aria-hidden="true" />
            </button>
          </div>

          {/* On-device replies toggle — the same edge.optIn the playground
              banner writes, kept here so it can also be turned OFF. */}
          <button
            type="button"
            onClick={() => setEdgeOptIn(!optIn)}
            className={cn(
              bareButtonClass,
              "mb-2 w-full justify-between gap-2 rounded-md px-2 py-1.5 text-xs text-muted-foreground hover:bg-secondary"
            )}
          >
            <span className="text-foreground">{EDGE_BRANDING.panelToggleLabel}</span>
            <span
              className={cn(
                "relative inline-flex h-4 w-7 shrink-0 items-center rounded-full transition-colors",
                optIn ? "bg-primary" : "bg-border"
              )}
              aria-hidden="true"
            >
              <span
                className={cn(
                  "absolute size-3 rounded-full bg-background transition-transform",
                  optIn ? "translate-x-3.5" : "translate-x-0.5"
                )}
              />
            </span>
          </button>

          {/* F-1 update channel: quiet card, user-initiated apply. The old
              model version keeps serving until the new one fully verifies
              (double-buffer rule in downloadManager). updateAvailable is only
              ever emitted for ACTIONABLE updates — installed assets the
              manager can actually download (non-null manifest url). Assets
              owned by external engines (null url, e.g. WebLLM) never flag
              here, so "Update now" always has real work to do and the state
              always clears once it finishes. */}
          {updateAvailable && (
            <div className="mb-2 rounded-md border border-border bg-secondary/40 p-2">
              <span className="block text-xs font-medium text-foreground">{EDGE_BRANDING.updateTitle}</span>
              <span className="mt-0.5 block text-xs leading-relaxed text-muted-foreground">
                {EDGE_BRANDING.updateBody}
              </span>
              <button
                type="button"
                onClick={() => {
                  void downloadManager.applyUpdate();
                }}
                className={cn(
                  bareButtonClass,
                  "mt-1.5 rounded-md px-2 py-1 text-xs font-medium text-primary hover:bg-secondary"
                )}
              >
                {EDGE_BRANDING.updateAction}
              </button>
            </div>
          )}

          {/* Reliability blueprint: brief note while the tier router is
              actively probing a tripped hosted breaker to see if it has
              recovered — quiet, own row, doesn't touch the label cascade. */}
          {recovering && (
            <div className="mb-2 flex items-center gap-1.5 rounded-md border border-border bg-secondary/40 px-2 py-1 text-xs text-muted-foreground">
              <span className="size-1.5 shrink-0 animate-pulse rounded-full bg-primary" aria-hidden="true" />
              {EDGE_BRANDING.retryingLabel}
            </div>
          )}

          <div className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">Model</div>
          <div className="mb-2 space-y-0.5" role="radiogroup" aria-label="Model">
            {pickerRow("auto", EDGE_BRANDING.pickerAutoLabel, EDGE_BRANDING.pickerAutoSubtitle, false)}
            {modelRows}
            {modelRows.length === 0 && (
              <p className="px-2 py-1 text-xs text-muted-foreground">{EDGE_BRANDING.pickerNoModelsNote}</p>
            )}
            {pickerRow("hosted", EDGE_BRANDING.pickerHostedLabel, EDGE_BRANDING.pickerHostedSubtitle, false)}
          </div>

          <div className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">Status</div>
          <div className="space-y-1">
            <div className="flex items-center justify-between gap-2 px-2 py-1 text-xs">
              {/* Model identity is allowed HERE (details panel) per the brand-first rule. */}
              <span className="truncate text-foreground">{EDGE_BRANDING.tier0ModelDisplayName}</span>
              <span className="shrink-0 text-muted-foreground">
                {availability === "available"
                  ? "Ready"
                  : availability === "downloading"
                    ? "Downloading"
                    : availability === "downloadable"
                      ? "Available to set up"
                      : "Not supported"}
              </span>
            </div>
            {assets
              .filter((a) => a.id !== "chrome-built-in" || !tier0Usable || a.state !== "ready")
              .map((asset) => (
                <div key={asset.id} className="flex items-center justify-between gap-2 px-2 py-1 text-xs">
                  <span className="min-w-0">
                    <span className="block truncate text-foreground">{asset.displayName}</span>
                    <span className="block truncate text-xs text-muted-foreground">
                      {ASSET_STATE_LABEL[asset.state]}
                      {/* Bug fix (2026-08-06 diagnosis): "verifying" (the GPU
                          warm-up/cache-load phase) carries a real percent
                          from progressReporter — was silently dropped here,
                          same as the chip label above. */}
                      {(asset.state === "downloading" || asset.state === "paused" || asset.state === "verifying") &&
                        ` · ${percentOf(asset)}%`}
                      {asset.state === "downloading" &&
                        hasRealByteCounts(asset) &&
                        ` · ${formatBytes(asset.receivedBytes)} / ${formatBytes(asset.totalBytes)}`}
                      {asset.state === "error" && asset.error ? ` · ${asset.error}` : ""}
                    </span>
                    {/* 2026-09-19: the one-liner above truncates; when the
                        failure is typed, say what happened and what to do in
                        a full sentence (resilience/failures.ts). */}
                    {asset.state === "error" && asset.failureReason && (
                      <span className="mt-0.5 block whitespace-normal text-xs leading-snug text-muted-foreground">
                        {describeDownloadFailure(asset.failureReason).message} {HOSTED_FALLBACK_NOTE}
                      </span>
                    )}
                  </span>
                  <span className="flex shrink-0 items-center gap-1">
                    {/* 2026-09-19: pause/cancel only exist for transfers the
                        download manager itself drives. On external rows
                        (WebLLM, whisper, Kokoro, vision — everything shipping
                        today) they did nothing, and Resume parked the row on
                        "Queued" forever; see downloadManager.resume(). */}
                    {!asset.external && asset.state === "downloading" && (
                      <button
                        type="button"
                        onClick={() => downloadManager.pause(asset.id)}
                        aria-label={`Pause ${asset.displayName} download`}
                        className={cn(
                          bareButtonClass,
                          "size-7 justify-center text-muted-foreground hover:text-foreground"
                        )}
                      >
                        <Pause size={11} aria-hidden="true" />
                      </button>
                    )}
                    {(asset.state === "paused" || asset.state === "error") && downloadManager.canRetry(asset.id) && (
                      <button
                        type="button"
                        onClick={() => downloadManager.resume(asset.id)}
                        aria-label={`Resume ${asset.displayName} download`}
                        className={cn(
                          bareButtonClass,
                          "size-7 justify-center text-muted-foreground hover:text-foreground"
                        )}
                      >
                        <Play size={11} aria-hidden="true" />
                      </button>
                    )}
                    {!asset.external &&
                      (asset.state === "downloading" || asset.state === "queued" || asset.state === "paused") && (
                        <button
                          type="button"
                          onClick={() => downloadManager.cancel(asset.id)}
                          aria-label={`Cancel ${asset.displayName} download`}
                          className={cn(
                            bareButtonClass,
                            "size-7 justify-center text-muted-foreground hover:text-foreground"
                          )}
                        >
                          <X size={11} aria-hidden="true" />
                        </button>
                      )}
                  </span>
                </div>
              ))}
          </div>

          <p className="mt-2 border-t border-border pt-2 text-xs leading-relaxed text-muted-foreground">
            {EDGE_BRANDING.panelFooter}
          </p>
        </div>
      )}
    </div>
  );
}
