"use client";

import { Eye, Loader2, Square } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import { Select } from "../../ui/select";
import LiveDot from "./LiveDot";
import { INTERVAL_OPTIONS } from "./captureCore";

export type EdgeProgress = { loaded: number; total: number; status: string };

/**
 * CameraPanel's watch-mode box: the standing instruction, the capture interval, start/stop (or the
 * upgrade-locked start after a plan-tier 403), and the status line with the frame counter, the
 * spoken-alerts and on-device toggles and the on-device model download progress.
 */
export default function WatchControls({
  instruction,
  onInstructionChange,
  watching,
  intervalSec,
  onIntervalChange,
  planLocked,
  visionOnDevice,
  edgeOffered,
  edgeProgress,
  frameCaptureNotice,
  framesAnalyzed,
  speakAlerts,
  onSpeakAlertsChange,
  onStartWatching,
  onStopWatching,
  onToggleOnDevice,
}: {
  instruction: string;
  onInstructionChange: (value: string) => void;
  watching: boolean;
  intervalSec: number;
  onIntervalChange: (seconds: number) => void;
  planLocked: boolean;
  visionOnDevice: boolean;
  edgeOffered: boolean;
  edgeProgress: EdgeProgress | null;
  frameCaptureNotice: string | null;
  framesAnalyzed: number;
  speakAlerts: boolean;
  onSpeakAlertsChange: (on: boolean) => void;
  onStartWatching: () => void;
  onStopWatching: () => void;
  onToggleOnDevice: () => void;
}) {
  const t = useTranslations("cameraPanel");
  return (
    <div className="flex flex-col gap-2 rounded-lg border border-border bg-secondary/30 p-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <input
          type="text"
          className="h-8 min-w-0 flex-1 rounded-md border border-border bg-secondary/40 px-2.5 text-xs text-foreground outline-none placeholder:text-muted-foreground focus-visible:border-ring"
          placeholder={t("watchPlaceholder")}
          aria-label={t("watchInstruction")}
          value={instruction}
          onChange={(e) => onInstructionChange(e.target.value)}
          disabled={watching}
        />
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <span id="camera-interval-label">{t("intervalLabel")}</span>
          <Select
            aria-labelledby="camera-interval-label"
            className="w-28"
            value={String(intervalSec)}
            onValueChange={(next) => onIntervalChange(Number(next))}
            options={INTERVAL_OPTIONS.map((sec) => ({
              value: String(sec),
              label: t("intervalOption", { seconds: sec }),
            }))}
          />
        </div>
        {watching ? (
          <Button type="button" variant="destructive" size="sm" onClick={onStopWatching}>
            <Square size={12} />
            {t("stopWatching")}
          </Button>
        ) : planLocked && !visionOnDevice ? (
          // Plan gate (403 from startVisionSession): the shared
          // upgrade-lock Button keeps the upsell on the control itself.
          <Button type="button" size="sm" upgradeLocked upgradeReason={t("upgradeReason")}>
            <Eye size={12} />
            {t("startWatching")}
          </Button>
        ) : (
          <Button type="button" size="sm" onClick={onStartWatching} disabled={!instruction.trim() || !!edgeProgress}>
            {edgeProgress ? <Loader2 size={12} className="animate-spin" /> : <Eye size={12} />}
            {t("startWatching")}
          </Button>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
        {watching && (
          <span className="flex items-center gap-1.5">
            <LiveDot state="--state-running" />
            {t("watchingLabel")}
          </span>
        )}
        {frameCaptureNotice && <span>{frameCaptureNotice}</span>}
        <span className="tabular-nums">
          {framesAnalyzed === 1 ? t("framesAnalyzedOne") : t("framesAnalyzed", { count: framesAnalyzed })}
        </span>
        <label className="flex cursor-pointer items-center gap-1.5">
          <input type="checkbox" checked={speakAlerts} onChange={(e) => onSpeakAlertsChange(e.target.checked)} />
          {t("speakAlerts")}
        </label>
        {edgeOffered && (
          <label className="flex cursor-pointer items-center gap-1.5" title={t("onDeviceToggleTitle")}>
            <input
              type="checkbox"
              checked={visionOnDevice}
              disabled={!!edgeProgress || watching}
              onChange={onToggleOnDevice}
            />
            {t("onDeviceToggle")}
          </label>
        )}
        {edgeProgress && (
          <span className="flex items-center gap-1.5">
            <Loader2 size={12} className="animate-spin" />
            {t("edgePreparing", { status: edgeProgress.status })}
            {edgeProgress.total > 0 && (
              <span className="tabular-nums">{Math.round((edgeProgress.loaded / edgeProgress.total) * 100)}%</span>
            )}
          </span>
        )}
      </div>
    </div>
  );
}
