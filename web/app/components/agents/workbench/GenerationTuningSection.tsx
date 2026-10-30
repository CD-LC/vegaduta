"use client";

import { Coins, Gauge, Lock, Pencil, ShieldCheck } from "lucide-react";
import Link from "next/link";
import { cn } from "../../../lib/utils";
import Select from "../../Select";
import { Badge } from "../../ui/badge";
import { Button } from "../../ui/button";
import { Input } from "../../ui/input";
import AgentSamplingControls from "../AgentSamplingControls";
import { SUB_TEXT } from "../AgentWorkbench/styles";
import HudSlider from "../HudSlider";
import ModelInfoCard, { modelOptionLabel } from "../ModelInfoCard";
import { useWorkbench } from "./context";
import { BEHAVIOR_ANCHORS } from "../AgentWorkbench/behaviorModel";
import { WorkbenchSection } from "./WorkbenchSection";
import { useTranslations } from "../../../lib/i18n";

export default function GenerationTuningSection() {
    const tHC = useTranslations("hcComponentsAgentsWorkbenchGenerationTuningSection");
  const {
    tuningSummary,
    isDraft,
    locked,
    t,
    tuningMode,
    tuningBusy,
    setNewAgentTuningMode,
    toggleTuningMode,
    maxTokens,
    genDraft,
    setMaxTokens,
    setGenDraft,
    temperature,
    setTemperature,
    topP,
    setTopP,
    genDirty,
    saveGen,
    genBusy,
    selectedAgent,
    onChanged,
    fallbackEditing,
    onPlatformLlm,
    isFreePlan,
    openFallbackEditor,
    fallbackDraft,
    setFallbackDraft,
    providers,
    fallbackDraftUseCustom,
    fallbackDraftCatalog,
    fallbackDraftLoading,
    setFallbackDraftUseCustom,
    fallbackDraftCatalogError,
    fallbackBusy,
    handleUpdateFallback,
    handleClearFallback,
    setFallbackEditing,
    costRoutingEditing,
    openCostRoutingEditor,
    costRoutingDraft,
    setCostRoutingDraft,
    costRoutingDraftUseCustom,
    costRoutingDraftCatalog,
    costRoutingDraftLoading,
    setCostRoutingDraftUseCustom,
    costRoutingDraftCatalogError,
    costRoutingBusy,
    handleUpdateCostRouting,
    handleClearCostRouting,
    setCostRoutingEditing,
  } = useWorkbench();
  return (
    <>
      {/* Generation tuning — Simple/Manual, the sampling sliders and (in
       * Manual) fallback + cost routing, collapsed by default (QA-05).
       * The header summary shows the mode and the live sampling values so
       * nobody has to open it to know what the agent runs with. */}
      <WorkbenchSection
        anchorId={BEHAVIOR_ANCHORS.generation}
        icon={<Gauge size={13} />}
        label={tHC("s3")}
        cat="var(--cat-comms)"
        actions={<WorkbenchSection.Summary>{tuningSummary}</WorkbenchSection.Summary>}
      >
        {/* Simple/Manual generation tuning - direct feedback: "in agents
         * there should be simple and manual tuning selection - in simple
         * it automatically selects the temp, tokens, p based on the
         * given system prompt - manual will allow users to select the
         * generation control". tuningMode is a distinct axis from
         * newAgentMode/experienceMode above (that one governs
         * tool/knowledge auto-attachment, not generation params).
         *
         * Two explicitly-labeled pills, not a bare toggle switch - same
         * "Auto-select / Manual" pattern as the TOOLS · MCP card below,
         * so both this control's two states are always visible at once
         * instead of only the currently-active one showing as text next
         * to an otherwise-unlabeled switch. */}
        <div className="mb-3 grid grid-cols-2 gap-2">
          <button
            type="button"
            title={!isDraft && locked ? t("editLockedTooltip") : undefined}
            className={cn(
              "rounded-lg border px-3 py-2 text-sm font-medium transition-colors",
              tuningMode === "SIMPLE"
                ? "border-primary bg-primary/10 text-foreground"
                : "border-border bg-card text-muted-foreground hover:bg-accent",
              !isDraft && (tuningBusy || locked) && "pointer-events-none opacity-50"
            )}
            disabled={!isDraft && (tuningBusy || locked)}
            onClick={() => (isDraft ? setNewAgentTuningMode("SIMPLE") : tuningMode !== "SIMPLE" && toggleTuningMode())}
          >
            {t("simpleLabel")}
          </button>
          <button
            type="button"
            title={!isDraft && locked ? t("editLockedTooltip") : undefined}
            className={cn(
              "rounded-lg border px-3 py-2 text-sm font-medium transition-colors",
              tuningMode === "MANUAL"
                ? "border-primary bg-primary/10 text-foreground"
                : "border-border bg-card text-muted-foreground hover:bg-accent",
              !isDraft && (tuningBusy || locked) && "pointer-events-none opacity-50"
            )}
            disabled={!isDraft && (tuningBusy || locked)}
            onClick={() => (isDraft ? setNewAgentTuningMode("MANUAL") : tuningMode !== "MANUAL" && toggleTuningMode())}
          >
            {t("manualLabel")}
          </button>
        </div>
        <p className={cn("mb-2", SUB_TEXT)}>
          {tuningMode === "SIMPLE"
            ? isDraft
              ? t("tuningSimpleDraftHint")
              : t("tuningSimpleLiveHint")
            : t("tuningManualHint")}
        </p>
        {/* Contextual bridge to the workflow guide's cost-tiering + multi-agent
                patterns (economy-tier triage, premium-on-demand, budgets). */}
        <p className="mb-3">
          <Link
            href="/help/workflow-recipes#multi-agent-patterns"
            className="inline-flex items-center gap-1 text-xs font-medium text-primary underline-offset-4 hover:underline"
          >
            <Coins size={12} />
            {t("costPatternsGuideLink")}
          </Link>
        </p>

        {
          <>
            {tuningMode === "SIMPLE" && (
              <p className="mb-2 text-[11px] text-muted-foreground">
                {tHC("s2")}
              </p>
            )}
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              {/* The hint lives inside the same grid cell as the slider
               * (wrapper div, not a 4th grid child) so it stays glued to
               * maxTokens on every breakpoint. WHY it exists: on Gemini
               * 2.5 models the "thinking" budget is carved out of this
               * same max-tokens value server-side, so a low cap silently
               * truncates the visible reply - users kept reading that as
               * a platform bug (see the maxTokens self-exhaustion fix,
               * commit b5468522). */}
              <div data-tour="workbench-max-tokens">
                <HudSlider
                  label={t("maxTokensLabel")}
                  value={isDraft ? maxTokens : (genDraft?.maxTokens ?? 0)}
                  displayValue={isDraft ? maxTokens : (genDraft?.maxTokens ?? "—")}
                  min={64}
                  max={8192}
                  step={64}
                  onChange={(v: number) => (isDraft ? setMaxTokens(v) : setGenDraft((d) => ({ ...d, maxTokens: v })))}
                  disabled={tuningMode === "SIMPLE" || (!isDraft && tuningBusy)}
                />
                <p className="mt-1 text-xs text-muted-foreground">{t("maxTokensThinkingHint")}</p>
              </div>
              <HudSlider
                label={t("temperatureLabel")}
                value={isDraft ? temperature : (genDraft?.temperature ?? 0)}
                displayValue={(isDraft ? temperature : (genDraft?.temperature ?? 0)).toFixed(2)}
                min={0}
                max={1}
                step={0.05}
                onChange={(v: number) => (isDraft ? setTemperature(v) : setGenDraft((d) => ({ ...d, temperature: v })))}
                disabled={tuningMode === "SIMPLE" || (!isDraft && tuningBusy)}
              />
              <HudSlider
                label={t("topPLabel")}
                value={isDraft ? topP : (genDraft?.topP ?? 0)}
                displayValue={(isDraft ? topP : (genDraft?.topP ?? 0)).toFixed(2)}
                min={0}
                max={1}
                step={0.05}
                onChange={(v: number) => (isDraft ? setTopP(v) : setGenDraft((d) => ({ ...d, topP: v })))}
                disabled={tuningMode === "SIMPLE" || (!isDraft && tuningBusy)}
              />
            </div>
            {!isDraft && tuningMode !== "SIMPLE" && genDirty && (
              <Button type="button" size="sm" className="mt-4" onClick={saveGen} disabled={genBusy}>
                {genBusy ? t("saving") : t("saveGenerationSettings")}
              </Button>
            )}
            {/* Extra sampling controls (topK, stop sequences, penalties, seed). */}
            {isDraft ? (
              <p
                aria-disabled="true"
                className="mt-4 rounded-md border border-dashed border-border px-3 py-2 text-[12.5px] text-muted-foreground opacity-80"
              >
                {tHC("s1")}
              </p>
            ) : (
              <div className="mt-4">
                <AgentSamplingControls
                  agent={selectedAgent}
                  disabled={!!locked}
                  disabledReason={locked ? t("editLockedTooltip") : undefined}
                  onSaved={() => onChanged()}
                />
              </div>
            )}
          </>
        }
        {/* Direct feedback: "set a fallback provider/model so future
         * provider outages degrade gracefully instead of failing the
         * run" - only for existing agents (a draft's primary provider
         * isn't even saved yet). Direct feedback (follow-up): "on
         * manual selection - fallback setup should get enabled, not in
         * simple" - Simple tuning mode is meant to stay a one-decision,
         * no-extra-knobs flow; fallback (like the rest of manual
         * generation control) only shows once the agent is in Manual
         * tuning mode. Still routes through the same assertEditable
         * check server-side, so a locked agent's Save simply surfaces
         * that error rather than the button being disabled up front. */}
        {/* Fallback + cost routing rendered as a clean, aligned settings
         * list (icon · title · current value on the left, action pinned
         * right) instead of loose mono chips — direct feedback: "arrange
         * Fallback / Cost routing properly, follow industry standards". */}
        {!isDraft && tuningMode === "MANUAL" && !fallbackEditing && (
          <div className="mb-2 flex items-center justify-between gap-3 rounded-lg border border-border bg-secondary/20 px-3.5 py-2.5">
            <div className="flex min-w-0 items-center gap-2.5">
              <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
                <ShieldCheck size={15} />
              </span>
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-foreground">{t("fallbackTitle")}</span>
                  {/* #955 - per-agent fallback counter: only worth a pill
                          once it's actually fired at least once. */}
                  {selectedAgent.fallbackTriggeredCount > 0 && (
                    <Badge
                      variant="outline"
                      title={
                        selectedAgent.lastFallbackAt
                          ? t("lastUsedPrefix", { date: new Date(selectedAgent.lastFallbackAt).toLocaleString() })
                          : undefined
                      }
                    >
                      {t("usedCount", { count: selectedAgent.fallbackTriggeredCount })}
                      {selectedAgent.fallbackFailedCount > 0
                        ? t("alsoFailedSuffix", { count: selectedAgent.fallbackFailedCount })
                        : ""}
                    </Badge>
                  )}
                </div>
                <div className="truncate text-xs text-muted-foreground">
                  {selectedAgent.fallbackProviderName
                    ? `${selectedAgent.fallbackProviderName} · ${selectedAgent.fallbackModel}`
                    : t("fallbackTooltip")}
                </div>
              </div>
            </div>
            {onPlatformLlm ? (
              // WB-010-style generalization (same fix as cost routing below):
              // was isFreePlan-only. A paid tenant on the managed pool has
              // the same "this control is a no-op" problem - the fallback
              // branch is never reached while chat routes through the pool.
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="shrink-0"
                upgradeLocked
                upgradeReason={isFreePlan ? t("fallbackFreePlanTooltip") : t("fallbackOnPlatformLlmTooltip")}
                upgradeLabel={t("freeTierUpgradeButton")}
              >
                <Lock size={12} />
                {t("setFallback")}
              </Button>
            ) : (
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="shrink-0"
                disabled={locked}
                onClick={openFallbackEditor}
                title={locked ? t("editLockedTooltip") : t("fallbackTooltip")}
              >
                <Pencil size={12} />
                {selectedAgent.fallbackProviderName ? t("changeFallback") : t("setFallback")}
              </Button>
            )}
          </div>
        )}
        {!isDraft && tuningMode === "MANUAL" && fallbackEditing && (
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <Select
              aria-label={t("chooseProvider")}
              id="agent-fallback-provider-select"
              name="agent-fallback-provider-select"
              data-testid="agent-fallback-provider-select"
              placeholder={t("chooseProvider")}
              value={fallbackDraft.providerName}
              onChange={(e) => setFallbackDraft((d) => ({ ...d, providerName: e.target.value, model: "" }))}
              options={[
                { value: "", label: t("chooseProvider") },
                ...providers
                  .filter((p) => p.providerName !== selectedAgent.providerName)
                  .map((p) => ({ value: p.providerName, label: p.providerName })),
              ]}
            />
            {fallbackDraftUseCustom || fallbackDraftCatalog.length === 0 ? (
              <div className="flex min-w-[200px] flex-1 items-center gap-2">
                <Input
                  aria-label={t("chooseModel")}
                  type="text"
                  value={fallbackDraft.model}
                  onChange={(e) => setFallbackDraft((d) => ({ ...d, model: e.target.value }))}
                  placeholder={fallbackDraftLoading ? t("loadingModels") : t("fallbackExamplePlaceholder")}
                  className="flex-1"
                  maxLength={128}
                />
                {fallbackDraftCatalog.length > 0 && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setFallbackDraftUseCustom(false)}
                    title={t("useList")}
                  >
                    {t("useList")}
                  </Button>
                )}
              </div>
            ) : (
              <Select
                aria-label={t("chooseModel")}
                id="agent-fallback-model-select"
                name="agent-fallback-model-select"
                data-testid="agent-fallback-model-select"
                placeholder={t("chooseModel")}
                searchable
                value={fallbackDraft.model}
                onChange={(e) => {
                  if (e.target.value === "__custom__") {
                    setFallbackDraftUseCustom(true);
                    return;
                  }
                  setFallbackDraft((d) => ({ ...d, model: e.target.value }));
                }}
                options={[
                  { value: "", label: t("chooseModel") },
                  ...fallbackDraftCatalog.map((m) => ({
                    value: m.modelId,
                    label: modelOptionLabel(m, t("modelDeprecatedLabel")),
                  })),
                  { value: "__custom__", label: t("otherManual") },
                ]}
              />
            )}
            {fallbackDraftCatalogError && (
              <p className="w-full text-xs text-muted-foreground">{t("modelCatalogLoadError")}</p>
            )}
            <Button
              type="button"
              size="sm"
              disabled={fallbackBusy || !fallbackDraft.providerName || !fallbackDraft.model.trim()}
              onClick={handleUpdateFallback}
            >
              {fallbackBusy ? t("saving") : t("save")}
            </Button>
            {selectedAgent.fallbackProviderName && (
              <Button type="button" variant="outline" size="sm" disabled={fallbackBusy} onClick={handleClearFallback}>
                {t("clearFallback")}
              </Button>
            )}
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={fallbackBusy}
              onClick={() => setFallbackEditing(false)}
            >
              {t("cancel")}
            </Button>
            {!fallbackDraftUseCustom && (
              <ModelInfoCard
                className="w-full"
                model={fallbackDraftCatalog.find((m) => m.modelId === fallbackDraft.model)}
              />
            )}
          </div>
        )}

        {/* #1077 fast-follow (Master Roadmap #18, cost optimization:
         * routing) - RouteLLM/NotDiamond-style opt-in: route simple
         * turns to a cheaper "economy" model instead of this agent's
         * primary model. Same Manual-tuning-only gating as fallback
         * above - a one-decision Simple flow shouldn't grow a second
         * provider/model picker. BYOK-tier only in practice (FREE
         * tenants already route through the platform's pooled hosted
         * models, so there's no per-agent cost to optimize there) - the
         * UI doesn't hide it on FREE plans though, since the PATCH
         * endpoint itself is harmless to call either way and the
         * setting simply won't do anything until the tenant is BYOK. */}
        {!isDraft && tuningMode === "MANUAL" && !costRoutingEditing && (
          <div className="mb-3 flex items-center justify-between gap-3 rounded-lg border border-border bg-secondary/20 px-3.5 py-2.5">
            <div className="flex min-w-0 items-center gap-2.5">
              <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
                <Gauge size={15} />
              </span>
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-foreground">{t("costRoutingTitle")}</span>
                  {selectedAgent.economyProviderName && (
                    <Badge variant={selectedAgent.costRoutingEnabled ? "secondary" : "outline"}>
                      {selectedAgent.costRoutingEnabled ? t("costRoutingOn") : t("costRoutingOff")}
                    </Badge>
                  )}
                </div>
                <div className="truncate text-xs text-muted-foreground">
                  {selectedAgent.economyProviderName
                    ? `${selectedAgent.economyProviderName} · ${selectedAgent.economyModel}`
                    : t("costRoutingTooltip")}
                </div>
              </div>
            </div>
            {onPlatformLlm ? (
              // WB-010 fix: was isFreePlan-only. AgentChatService's routing
              // logic returns early for any onPlatformLlm tenant before it
              // ever reaches the cost-routing branch, so a paid tenant
              // opted into the platform pool has the same "this control
              // is a no-op" problem as FREE. Copy stays plan-aware: FREE
              // gets the upsell wording, paid-but-on-pool gets plain
              // explanatory copy (nothing to upsell them to).
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="shrink-0"
                upgradeLocked
                upgradeReason={isFreePlan ? t("costRoutingFreePlanTooltip") : t("costRoutingOnPlatformLlmTooltip")}
                upgradeLabel={t("freeTierUpgradeButton")}
              >
                <Lock size={12} />
                {t("setCostRouting")}
              </Button>
            ) : (
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="shrink-0"
                disabled={locked}
                onClick={openCostRoutingEditor}
                title={locked ? t("editLockedTooltip") : t("costRoutingTooltip")}
              >
                <Pencil size={12} />
                {selectedAgent.economyProviderName ? t("changeCostRouting") : t("setCostRouting")}
              </Button>
            )}
          </div>
        )}
        {!isDraft && tuningMode === "MANUAL" && costRoutingEditing && (
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <label className="flex items-center gap-1.5 text-sm text-foreground">
              <input
                type="checkbox"
                checked={costRoutingDraft.enabled}
                onChange={(e) => setCostRoutingDraft((d) => ({ ...d, enabled: e.target.checked }))}
                className="accent-primary"
              />
              {t("costRoutingEnableLabel")}
            </label>
            <Select
              aria-label={t("chooseProvider")}
              id="agent-cost-routing-provider-select"
              name="agent-cost-routing-provider-select"
              data-testid="agent-cost-routing-provider-select"
              placeholder={t("chooseProvider")}
              value={costRoutingDraft.providerName}
              onChange={(e) => setCostRoutingDraft((d) => ({ ...d, providerName: e.target.value, model: "" }))}
              options={[
                { value: "", label: t("chooseProvider") },
                ...providers.map((p) => ({ value: p.providerName, label: p.providerName })),
              ]}
            />
            {costRoutingDraftUseCustom || costRoutingDraftCatalog.length === 0 ? (
              <div className="flex min-w-[200px] flex-1 items-center gap-2">
                <Input
                  aria-label={t("chooseModel")}
                  type="text"
                  value={costRoutingDraft.model}
                  onChange={(e) => setCostRoutingDraft((d) => ({ ...d, model: e.target.value }))}
                  placeholder={costRoutingDraftLoading ? t("loadingModels") : t("fallbackExamplePlaceholder")}
                  className="flex-1"
                  maxLength={128}
                />
                {costRoutingDraftCatalog.length > 0 && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setCostRoutingDraftUseCustom(false)}
                    title={t("useList")}
                  >
                    {t("useList")}
                  </Button>
                )}
              </div>
            ) : (
              <Select
                aria-label={t("chooseModel")}
                id="agent-cost-routing-model-select"
                name="agent-cost-routing-model-select"
                data-testid="agent-cost-routing-model-select"
                placeholder={t("chooseModel")}
                searchable
                value={costRoutingDraft.model}
                onChange={(e) => {
                  if (e.target.value === "__custom__") {
                    setCostRoutingDraftUseCustom(true);
                    return;
                  }
                  setCostRoutingDraft((d) => ({ ...d, model: e.target.value }));
                }}
                options={[
                  { value: "", label: t("chooseModel") },
                  ...costRoutingDraftCatalog.map((m) => ({
                    value: m.modelId,
                    label: modelOptionLabel(m, t("modelDeprecatedLabel")),
                  })),
                  { value: "__custom__", label: t("otherManual") },
                ]}
              />
            )}
            {costRoutingDraftCatalogError && (
              <p className="w-full text-xs text-muted-foreground">{t("modelCatalogLoadError")}</p>
            )}
            {/* V334: this agent's own bar for the decision provider's "is
                    this turn simple" second opinion - blank inherits the
                    tenant policy, which
                    itself inherits the platform default. Bounded 0.5-0.99 on
                    the server; the same bounds gate Save client-side. */}
            <label className="flex items-center gap-1.5 text-xs text-muted-foreground" title={t("jevThresholdTooltip")}>
              {t("jevThresholdLabel")}
              <Input
                type="number"
                min={0.5}
                max={0.99}
                step={0.05}
                value={costRoutingDraft.jevThreshold}
                onChange={(e) => setCostRoutingDraft((d) => ({ ...d, jevThreshold: e.target.value }))}
                placeholder="0.75"
                className="w-24"
              />
            </label>
            <Button
              type="button"
              size="sm"
              disabled={
                costRoutingBusy ||
                !costRoutingDraft.providerName ||
                !costRoutingDraft.model.trim() ||
                (costRoutingDraft.jevThreshold.trim() !== "" &&
                  (!Number.isFinite(Number(costRoutingDraft.jevThreshold)) ||
                    Number(costRoutingDraft.jevThreshold) < 0.5 ||
                    Number(costRoutingDraft.jevThreshold) > 0.99))
              }
              onClick={handleUpdateCostRouting}
            >
              {costRoutingBusy ? t("saving") : t("save")}
            </Button>
            {selectedAgent.economyProviderName && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={costRoutingBusy}
                onClick={handleClearCostRouting}
              >
                {t("clearCostRouting")}
              </Button>
            )}
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={costRoutingBusy}
              onClick={() => setCostRoutingEditing(false)}
            >
              {t("cancel")}
            </Button>
            {!costRoutingDraftUseCustom && (
              <ModelInfoCard
                className="w-full"
                model={costRoutingDraftCatalog.find((m) => m.modelId === costRoutingDraft.model)}
              />
            )}
          </div>
        )}
      </WorkbenchSection>
    </>
  );
}
