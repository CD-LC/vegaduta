"use client";

import { Coins, Pencil } from "lucide-react";
import { cn } from "../../../../lib/utils";
import Select from "../../../Select";
import { Button } from "../../../ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "../../../ui/tooltip";
import { UpgradeLock } from "../../../ui/upgrade-lock";
import { useWorkbench } from "../context";
import { isPersonalInstall } from "../../../../lib/personalInstall";

/** The section's first row: the provider picker (draft) or the model in use with its Change button (saved agent), each gated on the platform pool and on-device. */
export default function ProviderModelHeader() {
  const {
    t,
    isDraft,
    onPlatformLlm,
    isFreePlan,
    intelSource,
    providerName,
    draftTouchedRef,
    setProviderName,
    activeProviders,
    providers,
    platformLlmInfo,
    selectedAgent,
    locked,
    modelEditing,
    setModelEditing,
    openModelEditor,
  } = useWorkbench();
  return (
    <div className="mb-3 flex flex-wrap items-center gap-2">
      {isDraft ? (
        onPlatformLlm ? (
          // 2026-07-21 fix: this creation-time provider picker had NO isFreePlan gate at
          // all - a FREE tenant could freely choose any BYOK provider/model while creating
          // a new agent, exactly the "letting a FREE tenant 'successfully' pick a
          // provider/model was misleading" problem the POST-creation "Change model" button
          // below was already gated against (disabled={locked || isFreePlan}). FREE always
          // routes chat through the platform-hosted pool regardless of what's saved here
          // (AgentChatService#buildChatClientFor) - AgentService#create itself stays
          // permissive by design (providerName is @NotNull on AgentCreateRequest, so a
          // real value must still be sent; it's just never used for FREE-tier chat), so
          // this is a UI-only fix: replaces the editable Select with the same fixed, non-interactive
          // "Platform LLM (Free tier)" badge shown elsewhere in this file, so there's no
          // illusion of choice during creation either.
          // 2026-08-07 (WB-014): condition generalized from isFreePlan to onPlatformLlm -
          // a paid tenant who opted into the platform pool has the exact same "picking a
          // provider/model here is a no-op" problem as FREE.
          <UpgradeLock reason={t("changeModelFreePlanTooltip")} label={t("freeTierUpgradeButton")}>
            {/* Same plan-signal treatment as the identity hero's
                    platform-LLM pill — one --state-warning token instead of
                    a duplicated amber-500 ramp. */}
            <span className="state-pill" style={{ ["--state" as any]: "var(--state-warning)" } as any}>
              <Coins size={11} />
              {/* Plan-aware copy: "Free tier" only on FREE - a paid tenant
                      opted into the pool sees the neutral "managed" label
                      (same neutral wording the platformLlm note uses). */}
              {isPersonalInstall() ? t("localModelBadgeLabel") : isFreePlan ? t("platformLlmBadgeLabel") : t("platformLlmBadgeLabelNeutral")}
            </span>
          </UpgradeLock>
        ) : (
          // Reliability blueprint Phase 3 Part 4: this picker
          // previously had zero relationship to the IntelligenceSourceControl
          // instance below (same component, ~140 lines down) — picking
          // "On-device" there left this Select fully interactive and
          // unrelated to that choice, unlike the existing-agent header
          // picker's `intelSource` gate above. Same technique as that
          // gate (aria-disabled + click/keyboard suppression instead of
          // native `disabled`, so the wrapping Tooltip still opens on
          // hover+focus) — implemented on a wrapper span rather than a
          // Select prop, since Select.tsx (out of this task's scope)
          // has no aria-disabled/click-suppression support of its own,
          // only a native `disabled` that would kill the tooltip too.
          <Tooltip>
            <TooltipTrigger asChild>
              <span
                className="inline-flex"
                // Bug fix (2026-08-06, live report: "I have select
                // platform, still i am able to change the model?"):
                // this gate previously only checked intelSource ===
                // "on-device", never onPlatformLlm - a paid tenant
                // who opted into the platform-managed pool
                // (tenantUsePlatformLlm, independent of on-device)
                // could still open and "successfully" change this
                // picker even though the platform pool ignores it
                // entirely, same underlying issue the on-device gate
                // was already built for.
                aria-disabled={intelSource === "on-device" || onPlatformLlm ? true : undefined}
                onClickCapture={(e) => {
                  if (intelSource === "on-device" || onPlatformLlm) {
                    e.preventDefault();
                    e.stopPropagation();
                  }
                }}
                onKeyDownCapture={(e) => {
                  // Mirrors the exact key set Select.tsx's own
                  // onKeyDown treats as "open the popover" (Enter /
                  // Space / ArrowDown / ArrowUp) — suppressed here,
                  // before Select's own handler ever runs, rather
                  // than blanket-blocking all keys (which would also
                  // break Tab navigation off this control).
                  if (
                    (intelSource === "on-device" || onPlatformLlm) &&
                    ["Enter", " ", "ArrowDown", "ArrowUp"].includes(e.key)
                  ) {
                    e.preventDefault();
                    e.stopPropagation();
                  }
                }}
              >
                <Select
                  aria-label={t("chooseProvider")}
                  id="agent-provider-select"
                  name="agent-provider-select"
                  data-testid="agent-provider-select"
                  placeholder={t("chooseProvider")}
                  value={providerName}
                  onChange={(e) => {
                    draftTouchedRef.current = true;
                    setProviderName(e.target.value);
                  }}
                  options={(activeProviders.length > 0 ? activeProviders : providers).map((p) => ({
                    value: p.providerName,
                    label: p.providerName,
                  }))}
                />
              </span>
            </TooltipTrigger>
            {(intelSource === "on-device" || onPlatformLlm) && (
              <TooltipContent>
                {intelSource === "on-device"
                  ? t("notUsedWhileOnDeviceTooltip")
                  : t(isPersonalInstall() ? "notUsedWhileLocalModelTooltip" : "notUsedWhilePlatformLlmTooltip")}
              </TooltipContent>
            )}
          </Tooltip>
        )
      ) : (
        <span
          className={cn(
            "group flex flex-wrap items-center gap-2",
            (intelSource === "on-device" || onPlatformLlm) && "opacity-40"
          )}
        >
          {/* Show the REAL model in use, not the raw provider·model pair.
                  For FREE-tier the saved provider/model is a placeholder
                  ("PLATFORM_HOSTED · platform-pool") — chat actually routes
                  through the platform pool, so surface that pool's real
                  model. The provider moves to the hover tooltip.
                  Bug B fix (reliability blueprint Phase 3 Part 4): greyed
                  out (opacity only, NOT pointer-events-none — that also
                  killed hover/focus on this span's own children, hiding
                  the tooltip that explains why) while
                  IntelligenceSourceControl reports on-device is selected.
                  The actual gate is on the Button below (aria-disabled +
                  click-suppression, same technique as button.tsx's
                  upgradeLocked), not on this wrapper — keyboard/
                  screen-reader users now get a correct disabled signal
                  instead of a control that merely ignores the mouse. */}
          <Tooltip>
            <TooltipTrigger asChild>
              <span className="inline-flex cursor-help items-center rounded-md border border-border bg-secondary/40 px-2.5 py-1 font-mono text-xs text-foreground">
                {/* BUG-001 fix: was isFreePlan-only - a paid tenant opted into
                        the managed platform pool has the exact same saved-provider/
                        model-is-a-placeholder situation as FREE, so this needs to
                        reflect the pool's real model (or the "not reporting"
                        fallback) for any onPlatformLlm tenant, not just FREE. */}
                {onPlatformLlm && platformLlmInfo?.available && platformLlmInfo.model
                  ? platformLlmInfo.model
                  : selectedAgent.model || t("serverDefault")}
              </span>
            </TooltipTrigger>
            <TooltipContent>
              {intelSource === "on-device"
                ? t("notUsedWhileOnDeviceTooltip")
                : onPlatformLlm
                  ? t(isPersonalInstall() ? "notUsedWhileLocalModelTooltip" : "notUsedWhilePlatformLlmTooltip")
                  : `Provider: ${selectedAgent.providerName}`}
            </TooltipContent>
          </Tooltip>
          {/* Unlimited-plan capability, ported from AgentsPanel.js -
           * this control simply didn't exist here before. `locked`
           * mirrors EditLockPolicy via AgentResponse.editable; a
           * locked FREE-tier agent still shows the button but
           * disabled, with a tooltip pointing at Duplicate instead.
           * isFreePlan is a separate, plan-based gate (not
           * time-based like `locked`) - FREE always routes chat
           * through the platform-hosted pool regardless of what's
           * saved here (AgentChatService), so letting a FREE
           * tenant "successfully" pick a BYOK provider/model was
           * misleading. The backend now rejects this too
           * (AgentService#updateModel), this is just the matching
           * UI-level gate so a FREE tenant never hits that error
           * in the first place. */}
          {/* FREE-tier: locked via the shared Button's upgrade treatment
                  (upsell on hover). The separate edit-lock (`locked`, time-
                  based) stays a plain native-disabled state with its own
                  explanatory tooltip. */}
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={locked}
            // aria-disabled here has to account for BOTH gates this
            // button is subject to: the isFreePlan case Button's own
            // upgradeLocked below already sets true for, and the new
            // on-device case, which upgradeLocked knows nothing
            // about — combined explicitly rather than passed
            // separately, since Button spreads its rest props over
            // its own computed aria-disabled and a value passed here
            // would otherwise silently replace (not OR with) that
            // computed one.
            // Bug fix (2026-08-06, live report: "I have select
            // platform, still i am able to change the model?"):
            // onPlatformLlm (isFreePlan OR a paid tenant's own
            // tenantUsePlatformLlm opt-in) was missing from this
            // gate entirely - only the FREE-tier subset of it
            // (isFreePlan) and the unrelated on-device case were
            // checked, so a PAID tenant on platform-managed mode
            // could still open and edit this picker even though
            // AgentChatService ignores it for that tenant. onPlatformLlm
            // already includes isFreePlan by definition (see its
            // declaration above), so this also simplifies the
            // isFreePlan check away without changing FREE-tier
            // behavior.
            // BUG-001 (cont'd): upgradeLocked/upgradeReason were still
            // isFreePlan-only, unlike aria-disabled/onClick above -
            // a paid tenant on the platform pool would still see the
            // hover-upgrade treatment misfire (or not fire at all,
            // leaving just the plain on-device/locked title). Generalized
            // to onPlatformLlm, but the copy stays plan-aware: FREE
            // keeps its upsell wording (there's something to upgrade
            // to), a paid-but-opted-in tenant gets plain explanatory
            // copy instead (there's nothing to sell them).
            aria-disabled={onPlatformLlm || intelSource === "on-device" ? true : undefined}
            upgradeLocked={onPlatformLlm}
            upgradeReason={isFreePlan ? t("changeModelFreePlanTooltip") : t(isPersonalInstall() ? "changeModelOnLocalModelTooltip" : "changeModelOnPlatformLlmTooltip")}
            upgradeLabel={t("freeTierUpgradeButton")}
            title={
              intelSource === "on-device"
                ? t("notUsedWhileOnDeviceTooltip")
                : onPlatformLlm
                  ? undefined
                  : locked
                    ? t("editLockedTooltip")
                    : t("changeProviderModelTooltip")
            }
            onClick={(e) => {
              // Same technique as button.tsx's upgradeLocked swallow
              // (preventDefault/stopPropagation instead of native
              // `disabled`, so the title/tooltip above still fires on
              // hover+focus) — not that prop itself, since there's no
              // upgrade path involved in "on-device/platform-managed
              // is selected so this picker doesn't apply". When
              // onPlatformLlm is also true, Button's own upgradeLocked
              // swallow already takes over before this ever runs.
              if (intelSource === "on-device" || onPlatformLlm) {
                e.preventDefault();
                e.stopPropagation();
                return;
              }
              modelEditing ? setModelEditing(false) : openModelEditor();
            }}
          >
            <Pencil size={12} />
            {t("changeModel")}
          </Button>
        </span>
      )}
    </div>
  );
}
