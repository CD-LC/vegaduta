"use client";

import { AlertTriangle } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { EDGE_BRANDING } from "../../../lib/edge/branding";
import type { ManifestModel } from "../../../lib/edge/capabilities";
import type { EdgeMemoryWriteStatus } from "../../../lib/edge/toolBridge";
import type { Tier0Status } from "./shared";

/**
 * The bottom of IntelligenceSourceControl's on-device section: the honest "what actually answers"
 * footer and the off-by-default on-device learning opt-in.
 */
export default function OnDeviceFooter({
  runningLocalModel,
  tier0Status,
  hostedFallbackTarget,
  learningOptIn,
  onLearningOptInChange,
  memoryWriteStatus,
}: {
  runningLocalModel: ManifestModel | null;
  tier0Status: Tier0Status;
  hostedFallbackTarget: string;
  learningOptIn: boolean;
  onLearningOptInChange: (on: boolean) => void;
  memoryWriteStatus: EdgeMemoryWriteStatus | null;
}) {
  const tHC = useTranslations("hcComponentsAgentsIntelligenceOnDeviceFooter");
  const t = useTranslations("edgeIntelligence");
  return (
    <>
      {/* Honest footer: name what actually answers, and where a miss
          lands. The pre-A3 tier-0 sentence is kept verbatim for the
          built-in-only case. */}
      {runningLocalModel ? (
        <p className="mt-2 text-[11px] leading-relaxed text-muted-foreground">
          {t("onDeviceFooterModel", {
            model: runningLocalModel.displayName,
            fallback: hostedFallbackTarget,
          })}
        </p>
      ) : tier0Status === "available" || tier0Status === "downloadable" || tier0Status === "downloading" ? (
        <p className="mt-2 text-[11px] leading-relaxed text-muted-foreground">
          {tHC("s3")} {EDGE_BRANDING.tier0ModelDisplayName}
          {tHC("s2")} {hostedFallbackTarget} {tHC("s1")}
        </p>
      ) : (
        <p className="mt-2 text-[11px] leading-relaxed text-muted-foreground">
          {t("onDeviceFooterNoModel", { fallback: hostedFallbackTarget })}
        </p>
      )}
      {/* On-device conversations are local-only by design (the whole
          pitch of this mode is "stays on your device") - this is the
          ONE explicit, off-by-default exception: when turned on, a
          completed on-device turn also becomes part of this agent's
          durable memory, via the same remember_fact tool a hosted turn
          can already call (POST /api/edge/tools/execute - see
          toolBridge.ts), so the agent can actually learn from it later.
          Never automatic; a stored "off" default is the entire point. */}
      <label className="mt-2 flex items-start gap-2 rounded-md border border-border bg-secondary/20 px-2.5 py-1.5 text-[11px] leading-relaxed text-muted-foreground">
        <input
          type="checkbox"
          className="mt-0.5"
          checked={learningOptIn}
          onChange={(e) => onLearningOptInChange(e.target.checked)}
        />
        <span className="flex-1">{t("edgeLearningOptIn")}</span>
        {learningOptIn && memoryWriteStatus && !memoryWriteStatus.ok && (
          <span
            className="mt-0.5 shrink-0 text-amber-600 dark:text-amber-400"
            title={
              memoryWriteStatus.detail
                ? `Last on-device memory save failed: ${memoryWriteStatus.detail}`
                : "Last on-device memory save failed."
            }
          >
            <AlertTriangle size={12} />
          </span>
        )}
      </label>
    </>
  );
}
