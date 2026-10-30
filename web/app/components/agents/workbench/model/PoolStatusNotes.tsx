"use client";

import { Info } from "lucide-react";
import Link from "next/link";
import { Button } from "../../../ui/button";
import { useWorkbench } from "../context";
import { isPersonalInstall, poolLabel } from "../../../../lib/personalInstall";
import { useTranslations } from "../../../../lib/i18n";

/** Platform pool vs own provider key status, each with the tenant-wide switch to the other. */
export default function PoolStatusNotes() {
    const tHC = useTranslations("hcComponentsAgentsWorkbenchModelPoolStatusNotes");
  const {
    t,
    onPlatformLlm,
    isFreePlan,
    activeProviders,
    platformLlmInfo,
    handleSwitchToByok,
    switchByokBusy,
    handleSwitchToPlatformPool,
    switchToPoolBusy,
  } = useWorkbench();
  return (
    <>
      {/* "You're on Platform LLM" note: shown whenever the tenant routes
       * through the managed pool (FREE plan, or a paid tenant that opted
       * into usePlatformLlm), where a per-agent provider/model pick is
       * ignored. Paid tenants get a one-click switch back to BYOK; FREE
       * tenants get an upgrade hint (they can't BYOK without upgrading). */}
      {onPlatformLlm && (
        <div
          className="mb-3 flex flex-col gap-1.5 rounded-lg border px-3 py-2.5"
          style={
            {
              ["--cat" as any]: "var(--cat-comms)",
              background: "color-mix(in srgb, var(--cat) 10%, transparent)",
              borderColor: "color-mix(in srgb, var(--cat) 28%, transparent)",
            } as any
          }
        >
          <span className="inline-flex items-center gap-1.5">
            <span className="cat-chip cat-chip-sm">
              <Info size={12} />
            </span>
            <span className="cat-tint font-mono text-[11px] uppercase tracking-wide">
              {isPersonalInstall() ? t("localModelNoteTitle") : t("platformLlmNoteTitle")}
            </span>
          </span>
          <p className="m-0 text-[12.5px] leading-relaxed text-foreground">
            {isPersonalInstall()
              ? platformLlmInfo?.available
                ? t("localModelNote", { model: platformLlmInfo.model })
                : t("localModelNoteUnavailable")
              : platformLlmInfo?.available
                ? t("platformLlmNote", { label: platformLlmInfo.label, model: platformLlmInfo.model })
                : t("platformLlmNoteUnavailable")}
          </p>
          {isFreePlan ? (
            <p className="m-0 text-[12.5px] leading-relaxed text-muted-foreground">
              {t("platformLlmFreeUpgradeHint")}
            </p>
          ) : (
            <div className="mt-0.5">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={handleSwitchToByok}
                disabled={switchByokBusy}
              >
                {switchByokBusy ? t("switchingToByok") : t("switchToByok")}
              </Button>
            </div>
          )}
        </div>
      )}
      {/* Always-visible pool vs own-key status. The block above only shows
       * on the pool; this is its counterpart for tenants on their own
       * provider key, with the switch back to the pool. Same
       * tenant-wide usePlatformLlm call as the pool -> BYOK switch. */}
      {!onPlatformLlm && (
        <div className="mb-3 flex flex-col gap-1.5 rounded-lg border border-border bg-secondary/20 px-3 py-2.5">
          <span className="font-mono text-[11px] uppercase tracking-wide text-muted-foreground">
            {tHC("s5")}
          </span>
          <p className="m-0 text-[12.5px] leading-relaxed text-foreground">
            {tHC("s4")}
            {activeProviders.length === 0 ? (
              <>
                {tHC("s3")}{" "}
                <Link href="/providers" className="font-medium text-primary underline-offset-4 hover:underline">
                  {tHC("s2")}
                </Link>{" "}
                {tHC("s1")}
              </>
            ) : (
              "."
            )}{" "}
            {poolLabel("The shared platform pool is off.", "The local model (Ollama) is not used for this workspace.")}
          </p>
          <div className="mt-0.5">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={handleSwitchToPlatformPool}
              disabled={switchToPoolBusy}
            >
              {switchToPoolBusy ? "Switching…" : poolLabel("Switch to platform pool", "Switch to the local model")}
            </Button>
          </div>
        </div>
      )}
    </>
  );
}
