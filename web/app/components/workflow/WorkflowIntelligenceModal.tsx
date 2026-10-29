"use client";

import { useRef } from "react";
import { X as CloseIcon } from "lucide-react";
import { Label } from "../ui/label";
import { cn } from "../../lib/utils";
import Select from "../Select";
import { Input } from "../ui/input";
import { Button } from "../ui/button";
import ModelInfoCard, { modelOptionLabel } from "../agents/ModelInfoCard";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";

export default function WorkflowIntelligenceModal({
  costRoutingBusy,
  costRoutingDraft,
  economyModelCatalog,
  economyModelLoading,
  economyModelUseCustom,
  economyProviders,
  executionLimitsBusy,
  executionLimitsDraft,
  handleClearCostRouting,
  handleSaveCostRouting,
  handleSaveExecutionLimits,
  handleSetReasoningDepth,
  handleSetTuningMode,
  handleToggleStepCaching,
  reasoningDepthBusy,
  setCostRoutingDraft,
  setEconomyModelUseCustom,
  setExecutionLimitsDraft,
  setShowIntelligenceModal,
  showIntelligenceModal,
  stepCachingBusy,
  t,
  tAw,
  tuningModeBusy,
  workflow,
}: any) {
  // Focus in on open, Tab trap, Escape closes, focus back to the opener on close (FE-041).
  const dialogRef = useRef<HTMLDivElement>(null);
  useDialogFocus(dialogRef, null, () => setShowIntelligenceModal(false), {
    enabled: !!(showIntelligenceModal && workflow),
  });

  return (
    <>
      {/* Workflow Intelligence modal - the five per-workflow tuning
       * PATCH endpoints (WorkflowController.java lines 326-371), mirroring
       * the Agent Intelligence controls AgentWorkbench.tsx already has for
       * individual agents. */}
      {showIntelligenceModal && workflow && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
          role="presentation"
          onClick={(e) => {
            if (e.target === e.currentTarget) setShowIntelligenceModal(false);
          }}
        >
          <div
            className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-xl border border-border bg-card p-6 shadow-xl"
            ref={dialogRef}
            tabIndex={-1}
            role="dialog"
            aria-modal="true"
          >
            <div className="flex items-start justify-between gap-3">
              <h2 className="text-lg font-semibold tracking-tight text-foreground">{t("intelligenceModalTitle")}</h2>
              <button
                type="button"
                className="-me-1 -mt-1 flex size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
                onClick={() => setShowIntelligenceModal(false)}
                aria-label={t("close")}
              >
                <CloseIcon size={16} />
              </button>
            </div>
            <p className="mt-1.5 text-sm text-muted-foreground">{t("intelligenceModalHint")}</p>

            <div className="mt-5 space-y-5">
              {/* Reasoning depth - FAST/CAREFUL/RIGOROUS/PLANNED, four
               * explicitly-labeled pills same as AgentWorkbench.tsx's
               * Simple/Manual toggle, extended to four options. Live-patched
               * on click, no separate Save. */}
              <div className="space-y-1.5">
                <Label>{t("reasoningDepthLabel")}</Label>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  {["FAST", "CAREFUL", "RIGOROUS", "PLANNED"].map((depth) => (
                    <button
                      key={depth}
                      type="button"
                      className={cn(
                        "rounded-lg border px-3 py-2 text-sm font-medium transition-colors",
                        workflow.reasoningDepth === depth
                          ? "border-primary bg-primary/10 text-foreground"
                          : "border-border bg-card text-muted-foreground hover:bg-accent",
                        reasoningDepthBusy && "pointer-events-none opacity-50"
                      )}
                      disabled={reasoningDepthBusy}
                      onClick={() => handleSetReasoningDepth(depth)}
                    >
                      {t(`reasoningDepth${depth}`)}
                    </button>
                  ))}
                </div>
              </div>

              {/* Tuning mode - Simple/Manual, same two-pill pattern as
               * AgentWorkbench.tsx's generation-tuning toggle. */}
              <div className="space-y-1.5">
                <Label>{t("tuningModeLabel")}</Label>
                <div className="grid grid-cols-2 gap-2">
                  {["SIMPLE", "MANUAL"].map((mode) => (
                    <button
                      key={mode}
                      type="button"
                      className={cn(
                        "rounded-lg border px-3 py-2 text-sm font-medium transition-colors",
                        workflow.tuningMode === mode
                          ? "border-primary bg-primary/10 text-foreground"
                          : "border-border bg-card text-muted-foreground hover:bg-accent",
                        tuningModeBusy && "pointer-events-none opacity-50"
                      )}
                      disabled={tuningModeBusy}
                      onClick={() => handleSetTuningMode(mode)}
                    >
                      {mode === "SIMPLE" ? t("simpleLabel") : t("manualLabel")}
                    </button>
                  ))}
                </div>
              </div>

              {/* Step caching - a single checkbox, live-patched on toggle. */}
              <div className="flex items-center justify-between gap-3 rounded-lg border border-border bg-secondary/20 px-3.5 py-2.5">
                <div>
                  <div id="wf-step-caching-label" className="text-sm font-medium text-foreground">
                    {t("stepCachingLabel")}
                  </div>
                  <div id="wf-step-caching-hint" className="text-xs text-muted-foreground">
                    {t("stepCachingHint")}
                  </div>
                </div>
                <span className="flex items-center gap-1.5 text-sm text-foreground">
                  <input
                    type="checkbox"
                    aria-labelledby="wf-step-caching-label"
                    aria-describedby="wf-step-caching-hint"
                    checked={!!workflow.stepCachingEnabled}
                    disabled={stepCachingBusy}
                    onChange={(e) => handleToggleStepCaching(e.target.checked)}
                    className="accent-primary"
                  />
                </span>
              </div>

              {/* Cost routing - enable checkbox + provider/model catalog
               * dropdowns (self-fetched via api.listProviders /
               * api.listProviderModels since no `providers` prop reaches
               * this component), with AgentWorkbench's "Other (manual)"
               * free-text escape hatch and an explicit Save + Clear. */}
              <div className="rounded-lg border border-border bg-secondary/20 px-3.5 py-2.5">
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <div className="text-sm font-medium text-foreground">{t("costRoutingTitle")}</div>
                    <div className="text-xs text-muted-foreground">{t("costRoutingHint")}</div>
                  </div>
                  <label className="flex items-center gap-1.5 text-sm text-foreground">
                    <input
                      type="checkbox"
                      checked={costRoutingDraft.enabled}
                      disabled={costRoutingBusy}
                      onChange={(e) => setCostRoutingDraft((d: any) => ({ ...d, enabled: e.target.checked }))}
                      className="accent-primary"
                    />
                    {t("costRoutingEnableLabel")}
                  </label>
                </div>
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <div className="min-w-[160px] flex-1">
                    <Select
                      id="wf-economy-provider-select"
                      name="wf-economy-provider-select"
                      data-testid="wf-economy-provider-select"
                      placeholder={t("economyProviderPlaceholder")}
                      value={costRoutingDraft.providerName}
                      onChange={(e) => {
                        // Switching provider always clears the model (same
                        // rule as AgentWorkbench's editors - a model id from
                        // one provider can't be saved against another).
                        setCostRoutingDraft((d: any) => ({ ...d, providerName: e.target.value, model: "" }));
                        setEconomyModelUseCustom(false);
                      }}
                      options={[
                        ...economyProviders.map((p: any) => ({ value: p.providerName, label: p.providerName })),
                        // A saved provider that isn't in the activated list
                        // (deactivated since, or hand-entered before this
                        // dropdown existed) stays selectable as-is.
                        ...(costRoutingDraft.providerName &&
                        !economyProviders.some((p: any) => p.providerName === costRoutingDraft.providerName)
                          ? [{ value: costRoutingDraft.providerName, label: costRoutingDraft.providerName }]
                          : []),
                      ]}
                    />
                  </div>
                  {economyModelUseCustom || economyModelCatalog.length === 0 ? (
                    <div className="flex min-w-[160px] flex-1 items-center gap-2">
                      <Input
                        type="text"
                        placeholder={economyModelLoading ? tAw("loadingModels") : t("economyModelPlaceholder")}
                        value={costRoutingDraft.model}
                        onChange={(e) => setCostRoutingDraft((d: any) => ({ ...d, model: e.target.value }))}
                        className="w-full"
                      />
                      {economyModelCatalog.length > 0 && (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() => setEconomyModelUseCustom(false)}
                          title={tAw("useList")}
                        >
                          {tAw("useList")}
                        </Button>
                      )}
                    </div>
                  ) : (
                    <div className="min-w-[160px] flex-1">
                      <Select
                        id="wf-economy-model-select"
                        name="wf-economy-model-select"
                        data-testid="wf-economy-model-select"
                        placeholder={t("economyModelPlaceholder")}
                        searchable
                        value={costRoutingDraft.model}
                        onChange={(e) => {
                          if (e.target.value === "__custom__") {
                            setEconomyModelUseCustom(true);
                            return;
                          }
                          setCostRoutingDraft((d: any) => ({ ...d, model: e.target.value }));
                        }}
                        options={[
                          { value: "", label: t("economyModelPlaceholder") },
                          ...economyModelCatalog.map((m: any) => ({
                            value: m.modelId,
                            label: modelOptionLabel(m, tAw("modelDeprecatedLabel")),
                          })),
                          { value: "__custom__", label: tAw("otherManual") },
                        ]}
                      />
                    </div>
                  )}
                  <Button
                    type="button"
                    size="sm"
                    disabled={costRoutingBusy || (costRoutingDraft.enabled && !costRoutingDraft.providerName.trim())}
                    onClick={handleSaveCostRouting}
                  >
                    {costRoutingBusy ? t("saving") : t("save")}
                  </Button>
                  {workflow.economyProviderName && (
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
                </div>
                {!economyModelUseCustom && (
                  <ModelInfoCard
                    className="mt-2"
                    model={economyModelCatalog.find((m: any) => m.modelId === costRoutingDraft.model)}
                  />
                )}
              </div>

              {/* Execution limits - three number inputs saved together with
               * one explicit Save (they don't need to be independently
               * live-patched, per the task spec). Bounds mirror
               * WorkflowExecutionLimitsUpdateRequest's server-side
               * validation (1-200 / 1-300 / 0-5). maxRetryAttempts is
               * retries AFTER the first try (0 = never retry), not total
               * attempts - see WorkflowDefinition#maxRetryAttempts's javadoc. */}
              <div className="space-y-1.5">
                <Label>{t("executionLimitsLabel")}</Label>
                <div className="flex flex-wrap items-end gap-2">
                  <div className="space-y-1">
                    <Label htmlFor="wf-max-steps" className="text-xs font-normal text-muted-foreground">
                      {t("maxStepsPerRunLabel")}
                    </Label>
                    <Input
                      id="wf-max-steps"
                      type="number"
                      min={1}
                      max={200}
                      value={executionLimitsDraft.maxStepsPerRun}
                      onChange={(e) => setExecutionLimitsDraft((d: any) => ({ ...d, maxStepsPerRun: e.target.value }))}
                      className="w-28"
                    />
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="wf-step-timeout" className="text-xs font-normal text-muted-foreground">
                      {t("stepTimeoutSecondsLabel")}
                    </Label>
                    <Input
                      id="wf-step-timeout"
                      type="number"
                      min={1}
                      max={300}
                      value={executionLimitsDraft.stepTimeoutSeconds}
                      onChange={(e) =>
                        setExecutionLimitsDraft((d: any) => ({ ...d, stepTimeoutSeconds: e.target.value }))
                      }
                      className="w-28"
                    />
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="wf-max-retries" className="text-xs font-normal text-muted-foreground">
                      {t("maxRetryAttemptsLabel")}
                    </Label>
                    <Input
                      id="wf-max-retries"
                      type="number"
                      min={0}
                      max={5}
                      value={executionLimitsDraft.maxRetryAttempts}
                      onChange={(e) =>
                        setExecutionLimitsDraft((d: any) => ({ ...d, maxRetryAttempts: e.target.value }))
                      }
                      className="w-28"
                    />
                  </div>
                  <Button type="button" size="sm" disabled={executionLimitsBusy} onClick={handleSaveExecutionLimits}>
                    {executionLimitsBusy ? t("saving") : t("save")}
                  </Button>
                </div>
              </div>
            </div>

            <div className="mt-6 flex justify-end">
              <Button type="button" variant="outline" size="sm" onClick={() => setShowIntelligenceModal(false)}>
                {t("close")}
              </Button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
