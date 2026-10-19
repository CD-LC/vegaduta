"use client";

import { Coins, MessageSquare, Pencil } from "lucide-react";
import Link from "next/link";
import { cn } from "../../../lib/utils";
import { templateNameKey } from "../../agentTemplates";
import AskAiHelper from "../../AskAiHelper";
import { SetupGuideCard } from "../../setup/SetupLinks";
import TrialOfferButton from "../../TrialOfferButton";
import { Badge } from "../../ui/badge";
import { Button } from "../../ui/button";
import { Card } from "../../ui/card";
import { Input } from "../../ui/input";
import { Textarea } from "../../ui/textarea";
import { Tooltip, TooltipContent, TooltipTrigger } from "../../ui/tooltip";
import AgentGlanceCard from "../AgentGlanceCard";
import AgentHealthChip from "../AgentHealthChip";
import { AgentCategoryTag, AgentMark, AgentStatePill, CapabilityChip } from "../agentIdentity";
import { Notice } from "../AgentWorkbench/Notice";
import { SUB_TEXT } from "../AgentWorkbench/styles";
import { isPersonalInstall } from "../../../lib/personalInstall";
import { GUIDE_ANCHORS } from "../guide/agentChecklist";
import { findStarterTemplate, isStarterTemplateKey } from "../starterTemplates";
import { useWorkbench } from "./context";
import { WorkbenchSection } from "./WorkbenchSection";

export default function IdentitySection() {
  const {
    headCategory,
    identityAgent,
    isDraft,
    nameInputRef,
    t,
    name,
    setName,
    selectedAgent,
    headState,
    mutating,
    activeTemplateKey,
    tTry,
    tTemplates,
    providerName,
    onPlatformLlm,
    isFreePlan,
    platformLlmInfo,
    effectiveMode,
    headCapabilities,
    reliability,
    locked,
    onChanged,
    needsProvider,
    promptEditing,
    systemPrompt,
    promptDraft,
    setSystemPrompt,
    setPromptDraft,
    openPromptEditor,
    promptDirty,
    promptBusy,
    handleUpdatePrompt,
    setPromptEditing,
  } = useWorkbench();
  return (
    <>
      {/* ---- IDENTITY HERO ----
              Was: a generic <Bot/> in the primary tint (identical for every
              agent) over a row of five hand-picked Tailwind palette badges.
              Now: the agent's own mark — category hue + mandatory category
              icon + a deterministic 5x5 sigil hashed from its id, so the same
              agent always looks the same and two agents in one category are
              still told apart without reading names — plus a state pill and a
              capability read-out derived from real AgentResponse fields. */}
      <Card className="flex flex-row items-start gap-4 p-5" style={{ ["--cat" as any]: headCategory.token } as any}>
        <AgentMark agent={identityAgent} size="hero" className="mt-0.5" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            {isDraft ? (
              <Input
                aria-label={t("nameAgentPlaceholder")}
                ref={nameInputRef}
                id={GUIDE_ANCHORS.name}
                className="h-9 flex-1 text-base font-semibold"
                placeholder={t("nameAgentPlaceholder")}
                value={name}
                onChange={(e) => setName(e.target.value)}
                maxLength={255}
                aria-describedby="agent-name-help"
              />
            ) : (
              <div className="min-w-0 truncate text-base font-semibold text-foreground">{selectedAgent.name}</div>
            )}
            <AgentStatePill
              state={headState}
              label={isDraft && !mutating ? t("draftStatus") : mutating ? headState.label : t("liveStatus")}
            />
          </div>
          {isDraft && (
            <p id="agent-name-help" className="mt-1 text-xs text-muted-foreground">
              Name it after the job it does, for example “Order status helper”.
            </p>
          )}
          <div className={cn("mt-1.5", SUB_TEXT)}>
            {isDraft
              ? activeTemplateKey
                ? isStarterTemplateKey(activeTemplateKey)
                  ? tTry("starter_usingTemplate", {
                      template: tTry(findStarterTemplate(activeTemplateKey)?.labelKey ?? "try_justExploring"),
                    })
                  : t("usingTemplatePrefix", { template: tTemplates(templateNameKey(activeTemplateKey)) })
                : t("draftHint")
              : (selectedAgent.systemPrompt || "").slice(0, 96) + (selectedAgent.systemPrompt?.length > 96 ? "…" : "")}
          </div>
          {/* Identity row: what kind of agent (category tag — icon+hue,
                  never hue alone), which model backs it, and the plan/mode
                  facts. Every tint here resolves from a category or state
                  token; the old slate/amber/teal/sky/emerald ramps are gone. */}
          <div className="mt-3 flex flex-wrap items-center gap-1.5">
            <AgentCategoryTag agent={identityAgent} />
            <Badge variant="outline" className="font-mono">
              {isDraft ? providerName : selectedAgent.providerName}
            </Badge>
            {/* Any tenant on the managed platform-hosted pool - FREE
             * (always) or a paid tenant who opted in via "Use platform
             * LLM" - routes through it regardless of the provider badge
             * above (AgentChatService), so this badge shows what they're
             * actually getting. FREE keeps its own upsell-flavored
             * title; the opted-in-paid case gets plain explanatory copy
             * since there's no upgrade to sell them. */}
            {onPlatformLlm && (
              <span
                className="state-pill"
                title={isFreePlan ? t("platformLlmFreeTierNote") : t("platformLlmOptedInNote")}
                style={{ ["--state" as any]: "var(--state-warning)" } as any}
              >
                <Coins size={11} />
                {isPersonalInstall()
                  ? platformLlmInfo?.available
                    ? t("localModelBadgeLabelWithModel", { model: platformLlmInfo.model })
                    : t("localModelBadgeLabel")
                  : isFreePlan
                  ? platformLlmInfo?.available
                    ? t("platformLlmBadgeLabelWithModel", { model: platformLlmInfo.model })
                    : t("platformLlmBadgeLabel")
                  : platformLlmInfo?.available
                    ? t("platformLlmBadgeLabelWithModelNeutral", { model: platformLlmInfo.model })
                    : t("platformLlmBadgeLabelNeutral")}
              </span>
            )}
            {!isDraft && (
              <>
                <Badge variant="outline">
                  {effectiveMode(selectedAgent) === "SIMPLE" ? t("experienceModeSimple") : t("experienceModeAdvanced")}
                </Badge>
                <Badge variant={selectedAgent.knowledgeBaseEnabled ? "secondary" : "outline"}>
                  {selectedAgent.knowledgeBaseEnabled ? t("kbOn") : t("kbOff")}
                </Badge>
                <AgentHealthChip agentId={selectedAgent.id} />
              </>
            )}
          </div>
          {/* Capability read-out — only what is actually switched on, each
                  with its own icon. An empty row is a legitimate answer for a
                  plain assistant, so nothing renders in that case. */}
          {headCapabilities.length > 0 && (
            <div className="mt-2 flex flex-wrap items-center gap-1.5">
              {headCapabilities.map((capability) => (
                <CapabilityChip key={capability.key} capability={capability} />
              ))}
            </div>
          )}
        </div>
      </Card>

      {/* 24h edit-lock upgrade moment (pricing audit 2026-07-31 §5.3/B5):
              a FREE tenant whose agent's edit window has closed used to learn
              it only from disabled-button tooltips. Sell the fix instead:
              banner + event-triggered 7-day Premium trial offer (rendered
              only when /api/tenant/trial/status says eligible; on grant,
              onChanged() re-reads agents so the server-computed `editable`
              unlocks the screen). */}
      {!isDraft && selectedAgent && <AgentGlanceCard agentId={selectedAgent.id} reliability={reliability} />}
      {!isDraft && locked && isFreePlan && (
        <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-3">
          <p className="m-0 text-sm text-amber-600 dark:text-amber-400">
            <span className="font-semibold">{t("editLockBannerSell")}</span> {t("editLockBannerText")}
          </p>
          <div className="mt-2">
            <TrialOfferButton trigger="edit-lock" onGranted={onChanged} />
          </div>
        </div>
      )}

      {/* Neither FREE tenants nor paid tenants who opted into the
       * platform-hosted pool ever have a BYOK provider active - both
       * always route through the platform-hosted pool (AgentChatService),
       * so this banner would otherwise show a scary "not set up" error
       * for either's normal, expected state. Nor does a new signup on the
       * complimentary grant, nor anyone whose next turn runs on-device -
       * needsProvider is true only when every route is genuinely closed. */}
      {needsProvider && (
        <Notice>
          {t("noActiveProviderPrefix")}
          <Link href="/providers" className="font-medium text-primary underline-offset-4 hover:underline">
            {t("activateProviderLink")}
          </Link>
          {t("noActiveProviderSuffix")}
        </Notice>
      )}

      <SetupGuideCard steps={["providers", "tools", "mcp", "secrets", "knowledge", "edge", "channels"]} />

      {/* Persona / system prompt — collapsible; header edit/AI controls
              stay as sibling actions so they don't toggle the section. */}
      <WorkbenchSection
        icon={<MessageSquare size={13} />}
        label={t("personaSystemPromptLabel")}
        cat="var(--cat-assistant)"
        anchorId={GUIDE_ANCHORS.instructions}
        tourId="workbench-system-prompt"
        actions={
          <>
            {/* Ask-AI helper, pointed at whichever draft state is live. */}
            {(isDraft || promptEditing) && (
              <AskAiHelper
                fieldLabel={t("agentSystemPromptFieldLabel")}
                currentValue={isDraft ? systemPrompt : promptDraft}
                onApply={isDraft ? setSystemPrompt : setPromptDraft}
                placeholder={t("agentSystemPromptExamplePlaceholder")}
              />
            )}
            {/* Edit icon — same edit-lock gate as Change model. */}
            {!isDraft && !promptEditing && (
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="size-8"
                    disabled={locked}
                    title={locked ? t("editLockedTooltip") : undefined}
                    onClick={openPromptEditor}
                    aria-label={t("editPromptAriaLabel")}
                  >
                    <Pencil size={12} />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>{locked ? t("editLockedTooltip") : t("editPromptTooltip")}</TooltipContent>
              </Tooltip>
            )}
          </>
        }
      >
        {isDraft ? (
          <Textarea
            data-guide-focus=""
            className="min-h-28"
            aria-label={t("personaSystemPromptLabel")}
            value={systemPrompt}
            onChange={(e) => setSystemPrompt(e.target.value)}
            required
            maxLength={20000}
          />
        ) : promptEditing ? (
          <>
            <Textarea
              data-guide-focus=""
              className="min-h-28"
              aria-label={t("personaSystemPromptLabel")}
              value={promptDraft}
              onChange={(e) => setPromptDraft(e.target.value)}
              autoFocus
              maxLength={20000}
            />
            <div className="mt-2 flex gap-2">
              <Button type="button" size="sm" disabled={!promptDirty || promptBusy} onClick={handleUpdatePrompt}>
                {promptBusy ? t("saving") : t("save")}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={promptBusy}
                onClick={() => setPromptEditing(false)}
              >
                {t("cancel")}
              </Button>
            </div>
          </>
        ) : (
          <p className="whitespace-pre-wrap text-sm leading-relaxed text-foreground">{selectedAgent.systemPrompt}</p>
        )}
      </WorkbenchSection>

      {/* Model & generation — collapsible. The provider/model controls
              move to the top of the section body since the label + chevron is
              now the toggle. */}
    </>
  );
}
