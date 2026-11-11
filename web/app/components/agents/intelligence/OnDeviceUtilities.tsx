"use client";

import type {} from "react";
import { Download, Loader2 } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { type AssetStatus } from "../../../lib/edge/downloadManager";
import { Button } from "../../ui/button";
import { UTILITY_DEFS, percentOf, type UtilityKind } from "./shared";

export default function OnDeviceUtilities({
  assets,
  handleUtilitySetup,
  reasoningModelReady,
  utilityAvailable,
  utilityBusy,
}: {
  assets: AssetStatus[];
  handleUtilitySetup: (def: (typeof UTILITY_DEFS)[number]) => void | Promise<void>;
  reasoningModelReady: boolean;
  utilityAvailable: Record<UtilityKind, boolean | null>;
  utilityBusy: Record<UtilityKind, boolean>;
}) {
  const tHC = useTranslations("hcComponentsAgentsIntelligenceOnDeviceUtilities");
  const t = useTranslations("edgeIntelligence");
  return (
    <div className="mt-2.5 border-t border-border pt-2.5">
      <div className="mb-1.5 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        {t("onDeviceAudioVisionHeading")}
      </div>
      {!reasoningModelReady && <p className="mb-1.5 text-[11px] leading-relaxed text-muted-foreground">{tHC("s1")}</p>}
      <div className="space-y-1">
        {UTILITY_DEFS.map((def) => {
          const cached = def.isCached();
          const busy = utilityBusy[def.kind];
          const available = utilityAvailable[def.kind];
          const asset = assets.find((a) => a.id === def.assetId) || null;
          const inFlight = busy || (!!asset && (asset.state === "downloading" || asset.state === "verifying"));
          return (
            <div key={def.kind} className="flex items-center justify-between gap-2 px-2 py-1.5 text-xs">
              <span className="min-w-0 flex items-center gap-1.5">
                {def.icon}
                <span className="min-w-0">
                  <span className="block truncate text-foreground">{def.displayName}</span>
                  <span className="block truncate text-[11px] text-muted-foreground">
                    {cached
                      ? t("tier0Ready")
                      : inFlight
                        ? asset && asset.state === "downloading"
                          ? t("downloadingPercent", { percent: percentOf(asset) })
                          : t("settingUpLabel")
                        : available === null
                          ? t("checkingLabel")
                          : available
                            ? t("notDownloadedTag")
                            : t("tier0NotSupported")}
                  </span>
                </span>
              </span>
              {!cached && available && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={inFlight}
                  onClick={() => {
                    void handleUtilitySetup(def);
                  }}
                >
                  {inFlight ? <Loader2 size={12} className="animate-spin" /> : <Download size={12} />}
                  {t("setUpAction")}
                </Button>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
