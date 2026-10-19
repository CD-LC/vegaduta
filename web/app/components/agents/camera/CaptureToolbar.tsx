"use client";

import { Camera, ChevronDown, ChevronUp, Eye, MonitorUp, RefreshCcw, Square } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import BrandText from "../../BrandText";
import { Button } from "../../ui/button";
import LiveDot from "./LiveDot";
import { MAX_STAGED_FRAMES } from "./captureCore";

/**
 * CameraPanel's header row: the title with the binding on-air indicator, and the capture controls
 * (start camera/screen when idle; show, flip, preview toggle and stop while a track is live).
 */
export default function CaptureToolbar({
  active,
  source,
  stagedCount,
  previewCollapsed,
  onStart,
  onShow,
  onFlip,
  onTogglePreview,
  onStop,
}: {
  active: boolean;
  source: "camera" | "screen";
  stagedCount: number;
  previewCollapsed: boolean;
  onStart: (source: "camera" | "screen") => void;
  onShow: () => void;
  onFlip: () => void;
  onTogglePreview: () => void;
  onStop: () => void;
}) {
  const t = useTranslations("cameraPanel");
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <div className="flex min-w-0 items-center gap-2 text-xs text-muted-foreground">
        <Eye size={12} aria-hidden="true" />
        <span className="font-medium text-foreground">
          <BrandText text={t("title")} />
        </span>
        {active && (
          <span className="flex items-center gap-1.5">
            {/* The binding on-air rule: pulsing red the entire time a track
                is live, with copy that says exactly what leaves the device. */}
            <LiveDot state="--state-error" />
            {source === "camera" ? t("onAirCamera") : t("onAirScreen")}
          </span>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-1.5">
        {!active ? (
          <>
            <Button type="button" variant="outline" size="sm" onClick={() => onStart("camera")}>
              <Camera size={12} />
              {t("startCamera")}
            </Button>
            <Button type="button" variant="outline" size="sm" onClick={() => onStart("screen")}>
              <MonitorUp size={12} />
              {t("startScreen")}
            </Button>
          </>
        ) : (
          <>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={onShow}
              disabled={stagedCount >= MAX_STAGED_FRAMES}
              title={t("showTitle")}
            >
              <Camera size={12} />
              {t("show")}
            </Button>
            {source === "camera" && (
              <Button
                type="button"
                variant="outline"
                size="icon"
                onClick={onFlip}
                aria-label={t("flipCamera")}
                title={t("flipCamera")}
              >
                <RefreshCcw size={14} />
              </Button>
            )}
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={onTogglePreview}
              aria-label={previewCollapsed ? t("showPreview") : t("hidePreview")}
              title={previewCollapsed ? t("showPreview") : t("hidePreview")}
            >
              {previewCollapsed ? <ChevronDown size={14} /> : <ChevronUp size={14} />}
            </Button>
            <Button type="button" variant="destructive" size="sm" onClick={onStop}>
              <Square size={12} />
              {t("stop")}
            </Button>
          </>
        )}
      </div>
    </div>
  );
}
