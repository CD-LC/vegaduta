"use client";

// The first-run "your device can run replies locally" nudge — the one
// surface that turns a capable-but-empty device into an on-device one.
//
// Before this, EDGE_BRANDING.readyToEnable* existed but nothing rendered it:
// a FREE tenant on a WebGPU laptop defaulted to edge-first, had no model
// downloaded, and every turn silently fell through to the metered hosted
// pool unless they found the /edge marketplace on their own. This banner
// closes that loop inside the chat surfaces themselves. E-0b rules still
// hold: never auto-download — the exact size and source are shown BEFORE
// the person clicks, and the click is the opt-in.
//
// Shown only when ALL of: edge flag on · WebGPU available · routing
// preference isn't hosted-only · no tier-1 model is ready on this device ·
// a manifest model actually fits this device · not dismissed. Plain English
// on purpose, same as every other edge surface.

import { useEffect, useRef, useState, type ReactNode } from "react";
import Link from "next/link";
import { Cpu, X } from "lucide-react";
import { Button } from "../ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";
import {
  detectCapabilities,
  edgeEnabled,
  getEdgeRoutingPreference,
  selectBestModel,
  EDGE_PREFS_EVENT,
  type EdgeCapabilities,
  type ManifestModel,
} from "../../lib/edge/capabilities";
import {
  fetchTier1Manifest,
  isTier1ModelCached,
  startTier1Download,
  tier1DownloadDetails,
  tier1Ready,
} from "../../lib/edge/tier1";
import { assetProgress, downloadManager } from "../../lib/edge/downloadManager";
import { setRoutingMode } from "../../lib/edge/routingState";
import { EDGE_BRANDING } from "../../lib/edge/branding";
import {
  HOSTED_FALLBACK_LABEL,
  HOSTED_FALLBACK_NOTE,
  describeDownloadFailure,
  type EdgeDownloadFailureDescription,
} from "../../lib/edge/resilience/failures";
import { useTranslations } from "../../lib/i18n";

/** localStorage — "1" once the person dismissed this nudge. The /edge
 * marketplace and the setup chip remain the way back in. */
export const EDGE_SETUP_NUDGE_DISMISSED_KEY = "edge.setupNudgeDismissed";

function isDismissed(): boolean {
  try {
    return typeof localStorage !== "undefined" && localStorage.getItem(EDGE_SETUP_NUDGE_DISMISSED_KEY) === "1";
  } catch {
    return false;
  }
}

function markDismissed(): void {
  try {
    localStorage.setItem(EDGE_SETUP_NUDGE_DISMISSED_KEY, "1");
  } catch {
    // Best-effort — a blocked localStorage just means it shows again next visit.
  }
}

function formatSize(bytes: number): string {
  if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
  return `${Math.max(1, Math.round(bytes / 1_000_000))} MB`;
}

type Phase =
  | { kind: "hidden" }
  | { kind: "offer"; caps: EdgeCapabilities; model: ManifestModel }
  | { kind: "downloading"; caps: EdgeCapabilities; model: ManifestModel; pct: number }
  | { kind: "done"; model: ManifestModel }
  | { kind: "error"; caps: EdgeCapabilities; model: ManifestModel; failure: EdgeDownloadFailureDescription };

export default function OnDeviceSetupNudge({ className }: { className?: string }) {
    const tHC = useTranslations("hcComponentsEdgeOnDeviceSetupNudge");
  // edgeEnabled() is a build-time constant — stable across renders, so the
  // hooks below always run in the same order.
  const enabled = edgeEnabled();
  const [phase, setPhase] = useState<Phase>({ kind: "hidden" });
  const mountedRef = useRef(true);
  /** True while download() below is awaiting startTier1Download — its result
   * is then the authority on failure, not the row. */
  const awaitingOwnDownloadRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (!enabled || typeof window === "undefined") return undefined;
    let cancelled = false;

    const evaluate = async () => {
      if (isDismissed() || getEdgeRoutingPreference() === "hosted-only") {
        if (!cancelled) setPhase((p) => (p.kind === "downloading" ? p : { kind: "hidden" }));
        return;
      }
      try {
        const caps = await detectCapabilities();
        if (cancelled || !caps.webgpu.available) return;
        if (await tier1Ready(caps)) {
          if (!cancelled) setPhase((p) => (p.kind === "downloading" ? p : { kind: "hidden" }));
          return;
        }
        const manifest = await fetchTier1Manifest();
        const tier1 = manifest.models.filter((m) => m.tier === 1);
        const selection = selectBestModel({ ...manifest, models: tier1 }, caps, "auto", { messageLength: 0 });
        if (cancelled || selection.kind !== "model" || !selection.model || !selection.fits) return;
        const model = selection.model;
        // A download already running for this model (started from the chip
        // or /edge) is shown as progress, not re-offered.
        const asset = downloadManager.getAsset(model.id);
        if (asset && (asset.state === "downloading" || asset.state === "verifying" || asset.state === "queued")) {
          setPhase({ kind: "downloading", caps, model, pct: assetProgress(asset).pct });
          return;
        }
        setPhase((p) => (p.kind === "downloading" || p.kind === "done" ? p : { kind: "offer", caps, model }));
      } catch {
        // Capability/manifest probes are best-effort — no nudge is the safe default.
      }
    };

    const onPrefsChanged = () => {
      void evaluate();
    };
    onPrefsChanged();
    window.addEventListener(EDGE_PREFS_EVENT, onPrefsChanged);
    return () => {
      cancelled = true;
      window.removeEventListener(EDGE_PREFS_EVENT, onPrefsChanged);
    };
  }, [enabled]);

  // Live progress while a download runs (whether this banner or another
  // surface started it) — rides the shared download manager, no polling.
  //
  // Also followed while an ERROR is showing (2026-09-19). A WebLLM download
  // cannot be aborted, so after "we stopped waiting" (the stall watchdog) it
  // may well still be running: the row then goes back to "downloading" and
  // later to "ready". Without this the banner kept showing the failure next
  // to a chip showing progress, and never acknowledged the model becoming
  // ready. The row is the single source of truth for both.
  const followedModelId = phase.kind === "downloading" || phase.kind === "error" ? phase.model.id : null;
  useEffect(() => {
    if (!followedModelId) return undefined;
    const modelId = followedModelId;
    return downloadManager.subscribe((event) => {
      if (event.type !== "asset" || event.asset.id !== modelId) return;
      const asset = event.asset;
      setPhase((p) => {
        if ((p.kind !== "downloading" && p.kind !== "error") || p.model.id !== modelId) return p;
        if (asset.state === "ready") {
          // "ready" on the row also fires for a plain engine warm-up; only the
          // install marker means the download really completed.
          return isTier1ModelCached(modelId) ? { kind: "done", model: p.model } : p;
        }
        if (asset.state === "downloading" || asset.state === "verifying") {
          return { kind: "downloading", caps: p.caps, model: p.model, pct: assetProgress(asset).pct };
        }
        if (asset.state === "error" && p.kind === "downloading" && !awaitingOwnDownloadRef.current) {
          // Nobody is awaiting a result that would report this (the download
          // was started elsewhere, or it outlived our wait and then failed).
          return {
            kind: "error",
            caps: p.caps,
            model: p.model,
            failure: describeDownloadFailure(asset.failureReason ?? undefined),
          };
        }
        return p;
      });
    });
  }, [followedModelId]);

  useEffect(() => {
    if (phase.kind !== "done") return undefined;
    const timer = window.setTimeout(() => {
      if (mountedRef.current) setPhase({ kind: "hidden" });
    }, 6000);
    return () => window.clearTimeout(timer);
  }, [phase.kind]);

  if (!enabled || phase.kind === "hidden") return null;

  const dismiss = () => {
    markDismissed();
    setPhase({ kind: "hidden" });
  };

  const download = async (caps: EdgeCapabilities, model: ManifestModel) => {
    // The click IS the opt-in: persist the on-device-first preference so the
    // next turn routes locally the moment the weights land, then download.
    setRoutingMode("hybrid");
    setPhase({ kind: "downloading", caps, model, pct: 0 });
    awaitingOwnDownloadRef.current = true;
    let result: Awaited<ReturnType<typeof startTier1Download>>;
    try {
      result = await startTier1Download(caps, model.id);
    } catch {
      // startTier1Download is written never to reject; if it somehow does,
      // the person still gets a legible failure with both buttons.
      if (mountedRef.current) setPhase({ kind: "error", caps, model, failure: describeDownloadFailure("unknown") });
      return;
    } finally {
      awaitingOwnDownloadRef.current = false;
    }
    if (!mountedRef.current) return;
    if (result.ok === true) {
      setPhase({ kind: "done", model: result.model });
      return;
    }
    // 2026-09-19: every failure used to collapse into one of three strings,
    // and the only button was "Try again" — shown even for "no WebGPU" or
    // "disk full", where trying again can only fail the same way. The result
    // now carries a typed reason with its own sentence and ONE fitting action
    // (resilience/failures.ts). The fallback covers a mocked/older
    // startTier1Download that returns no `failure` field.
    setPhase({ kind: "error", caps, model, failure: result.failure ?? describeDownloadFailure(result.reason) });
  };

  // The escape hatch that is ALWAYS offered next to a failure: one click and
  // every reply comes from the hosted model, so a broken on-device setup can
  // never be the reason someone cannot chat. Same call the routing picker's
  // "Hosted" option makes; reversible from there or from /edge.
  const useHosted = () => {
    setRoutingMode("hosted");
    setPhase({ kind: "hidden" });
  };

  const shell = (children: ReactNode, showDismiss: boolean) => (
    <div
      role="status"
      className={
        "flex items-center justify-between gap-3 border-b border-border bg-primary/[0.06] px-5 py-2.5 " +
        (className || "")
      }
    >
      <div className="flex min-w-0 items-center gap-2.5">
        <Cpu size={14} className="size-3.5 shrink-0 text-primary" aria-hidden="true" />
        <div className="min-w-0 text-xs text-muted-foreground">{children}</div>
      </div>
      {showDismiss && (
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              type="button"
              onClick={dismiss}
              aria-label={tHC("hcComponentsEdgeOnDeviceSetupNudge.s1")}
              className="flex size-6 shrink-0 items-center justify-center rounded border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-secondary hover:text-foreground"
            >
              <X size={12} aria-hidden="true" />
            </button>
          </TooltipTrigger>
          <TooltipContent>Dismiss</TooltipContent>
        </Tooltip>
      )}
    </div>
  );

  if (phase.kind === "offer") {
    const { caps, model } = phase;
    const saveData = caps.connection.saveData;
    return (
      <div
        role="status"
        className={
          "flex flex-wrap items-center justify-between gap-3 border-b border-border bg-primary/[0.06] px-5 py-2.5 " +
          (className || "")
        }
      >
        <div className="flex min-w-0 items-center gap-2.5">
          <Cpu size={14} className="size-3.5 shrink-0 text-primary" aria-hidden="true" />
          <p className="min-w-0 text-xs text-muted-foreground" title={tier1DownloadDetails(model)}>
            <span className="font-medium text-foreground">{EDGE_BRANDING.readyToEnableTitle}</span>
            {" — "}
            {EDGE_BRANDING.readyToEnableBody}{" "}
            <span className="whitespace-nowrap text-foreground/80">
              One-time {formatSize(model.sizeBytes)} {tHC("hcComponentsEdgeOnDeviceSetupNudge.s5")}
            </span>
            {saveData && (
              <span className="text-[var(--state-warning)]">
                {" "}
                {tHC("hcComponentsEdgeOnDeviceSetupNudge.s4")}
              </span>
            )}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          <Button type="button" variant="outline" size="sm" onClick={() => void download(caps, model)}>
            {EDGE_BRANDING.readyToEnableAction}
          </Button>
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                onClick={dismiss}
                aria-label={tHC("hcComponentsEdgeOnDeviceSetupNudge.s1")}
                className="flex size-6 items-center justify-center rounded border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-secondary hover:text-foreground"
              >
                <X size={12} aria-hidden="true" />
              </button>
            </TooltipTrigger>
            <TooltipContent>Dismiss</TooltipContent>
          </Tooltip>
        </div>
      </div>
    );
  }

  if (phase.kind === "downloading") {
    return shell(
      <span>
        <span className="font-medium text-foreground">Downloading {phase.model.displayName}</span>
        {" · "}
        <span className="tabular-nums">{phase.pct}%</span>
        {" — "}{tHC("hcComponentsEdgeOnDeviceSetupNudge.s3")}
      </span>,
      false
    );
  }

  if (phase.kind === "done") {
    return shell(
      <span>
        <span className="font-medium text-foreground">{EDGE_BRANDING.name} {tHC("hcComponentsEdgeOnDeviceSetupNudge.s2")}</span> {EDGE_BRANDING.promise}
      </span>,
      false
    );
  }

  return (
    <div
      role="status"
      className={
        "flex flex-wrap items-center justify-between gap-3 border-b border-border bg-primary/[0.06] px-5 py-2.5 " +
        (className || "")
      }
    >
      <div className="flex min-w-0 items-center gap-2.5">
        <Cpu size={14} className="size-3.5 shrink-0 text-primary" aria-hidden="true" />
        <p className="min-w-0 text-xs text-muted-foreground">
          <span className="font-medium text-foreground">{phase.failure.title}</span>
          {" — "}
          {phase.failure.message} {HOSTED_FALLBACK_NOTE}
        </p>
      </div>
      <div className="flex shrink-0 flex-wrap items-center gap-1.5">
        {/* ONE primary action, chosen by the failure reason. */}
        {phase.failure.action === "retry" || phase.failure.action === "redownload" ? (
          <Button type="button" variant="outline" size="sm" onClick={() => void download(phase.caps, phase.model)}>
            {phase.failure.actionLabel}
          </Button>
        ) : phase.failure.action === "pick-smaller-model" || phase.failure.action === "free-space" ? (
          // Both are done in the model manager: it lists every model with its
          // size and whether it fits, and what is installed with a delete button.
          <Button asChild variant="outline" size="sm">
            <Link href="/edge">{phase.failure.actionLabel}</Link>
          </Button>
        ) : null}
        <Button type="button" variant="ghost" size="sm" onClick={useHosted}>
          {HOSTED_FALLBACK_LABEL}
        </Button>
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              type="button"
              onClick={dismiss}
              aria-label={tHC("hcComponentsEdgeOnDeviceSetupNudge.s1")}
              className="flex size-6 items-center justify-center rounded border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-secondary hover:text-foreground"
            >
              <X size={12} aria-hidden="true" />
            </button>
          </TooltipTrigger>
          <TooltipContent>Dismiss</TooltipContent>
        </Tooltip>
      </div>
    </div>
  );
}
