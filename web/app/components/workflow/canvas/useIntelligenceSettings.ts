"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "../../../lib/api";
import type { ProviderModelInfo } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { useLatestRef } from "../../../lib/hooks/useLatestRef";
import { toast } from "../../../lib/toastBus";

/**
 * Workflow Intelligence modal: reasoning depth, tuning mode, step caching,
 * cost routing and execution limits (the five Agent Intelligence controls,
 * see AgentWorkbench). Depth / tuning / caching are live-patched on click;
 * cost routing and execution limits have an explicit Save.
 */
export function useIntelligenceSettings(opts: {
  workflowId: any;
  workflow: any;
  setWorkflow: (w: any) => void;
  t: (key: string, values?: Record<string, unknown>) => string;
}) {
  const { workflowId, workflow, setWorkflow, t } = opts;
  const [showIntelligenceModal, setShowIntelligenceModal] = useState(false);
  const [reasoningDepthBusy, setReasoningDepthBusy] = useState(false);
  const [tuningModeBusy, setTuningModeBusy] = useState(false);
  const [stepCachingBusy, setStepCachingBusy] = useState(false);
  // `enabled` is tracked separately from provider/model: a tenant can flip the
  // toggle without changing which economy model is configured.
  const [costRoutingDraft, setCostRoutingDraft] = useState({ enabled: false, providerName: "", model: "" });
  // Read (not tracked) by the catalog fetch below: it re-runs per provider, not per keystroke.
  const costRoutingModelRef = useLatestRef(costRoutingDraft.model);
  const [costRoutingBusy, setCostRoutingBusy] = useState(false);
  // Live provider/model catalog for the cost-routing editor, fetched lazily the
  // first time the modal opens. A saved value that isn't in the catalog reopens
  // on the free-text path rather than being silently dropped.
  const [economyProviders, setEconomyProviders] = useState<any[]>([]);
  const economyProvidersLoadedRef = useRef(false);
  const [economyModelCatalog, setEconomyModelCatalog] = useState<ProviderModelInfo[]>([]);
  const [economyModelLoading, setEconomyModelLoading] = useState(false);
  const [economyModelUseCustom, setEconomyModelUseCustom] = useState(false);
  // Execution limits are strings while editing (the input can be temporarily
  // empty mid-edit) and parsed/validated on Save.
  const [executionLimitsDraft, setExecutionLimitsDraft] = useState({
    maxStepsPerRun: "",
    stepTimeoutSeconds: "",
    maxRetryAttempts: "",
  });
  const [executionLimitsBusy, setExecutionLimitsBusy] = useState(false);

  // Providers load once, on first open - workspace-level config.
  useEffect(() => {
    if (!showIntelligenceModal || economyProvidersLoadedRef.current) return;
    economyProvidersLoadedRef.current = true;
    api
      .listProviders()
      .then((list) => setEconomyProviders(Array.isArray(list) ? list : []))
      .catch(() => setEconomyProviders([]));
  }, [showIntelligenceModal]);

  // Provider-scoped model catalog, refetched whenever the picked economy
  // provider changes while the modal is open.
  useEffect(() => {
    if (!showIntelligenceModal) return undefined;
    const provider = costRoutingDraft.providerName;
    if (!provider) {
      setEconomyModelCatalog([]);
      return undefined;
    }
    let cancelled = false;
    setEconomyModelLoading(true);
    api
      .listProviderModels(provider)
      .then((models) => {
        if (cancelled) return;
        const catalog = Array.isArray(models) ? models : [];
        setEconomyModelCatalog(catalog);
        const savedModel = costRoutingModelRef.current;
        if (savedModel && !catalog.some((m) => m.modelId === savedModel)) setEconomyModelUseCustom(true);
      })
      .catch(() => {
        if (!cancelled) setEconomyModelCatalog([]);
      })
      .finally(() => {
        if (!cancelled) setEconomyModelLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // provider string, not the whole draft object (which changes identity on every keystroke).
  }, [showIntelligenceModal, costRoutingDraft.providerName, costRoutingModelRef]);

  // Seeds the drafts from the currently loaded workflow on open.
  function openIntelligenceModal() {
    if (!workflow) return;
    setCostRoutingDraft({
      enabled: !!workflow.costRoutingEnabled,
      providerName: workflow.economyProviderName || "",
      model: workflow.economyModel || "",
    });
    // Start on the catalog path; the catalog effect flips this to free text
    // if the saved model turns out not to be in the catalog.
    setEconomyModelUseCustom(false);
    setExecutionLimitsDraft({
      maxStepsPerRun: String(workflow.maxStepsPerRun ?? ""),
      stepTimeoutSeconds: String(workflow.stepTimeoutSeconds ?? ""),
      maxRetryAttempts: String(workflow.maxRetryAttempts ?? ""),
    });
    setShowIntelligenceModal(true);
  }

  // Each PATCH response already reflects the new value, so setWorkflow(updated) is all that's needed.
  async function handleSetReasoningDepth(reasoningDepth: any) {
    if (!workflow || reasoningDepth === workflow.reasoningDepth || reasoningDepthBusy) return;
    setReasoningDepthBusy(true);
    try {
      const updated = await api.setWorkflowReasoningDepth(workflowId, reasoningDepth);
      setWorkflow(updated);
      toast.success(t("reasoningDepthUpdatedSuccess"));
    } catch (err) {
      toast.error(errorMessage(err, t("reasoningDepthUpdateError")));
    } finally {
      setReasoningDepthBusy(false);
    }
  }

  async function handleSetTuningMode(tuningMode: any) {
    if (!workflow || tuningMode === workflow.tuningMode || tuningModeBusy) return;
    setTuningModeBusy(true);
    try {
      const updated = await api.setWorkflowTuningMode(workflowId, tuningMode);
      setWorkflow(updated);
      toast.success(t("tuningModeUpdatedSuccess"));
    } catch (err) {
      toast.error(errorMessage(err, t("tuningModeUpdateError")));
    } finally {
      setTuningModeBusy(false);
    }
  }

  async function handleToggleStepCaching(enabled: any) {
    if (!workflow || stepCachingBusy) return;
    setStepCachingBusy(true);
    try {
      const updated = await api.setWorkflowStepCaching(workflowId, enabled);
      setWorkflow(updated);
      toast.success(enabled ? t("stepCachingEnabledSuccess") : t("stepCachingDisabledSuccess"));
    } catch (err) {
      toast.error(errorMessage(err, t("stepCachingUpdateError")));
    } finally {
      setStepCachingBusy(false);
    }
  }

  async function handleSaveCostRouting() {
    if (!workflow) return;
    setCostRoutingBusy(true);
    try {
      const updated = await api.setWorkflowCostRouting(
        workflowId,
        costRoutingDraft.enabled,
        costRoutingDraft.providerName.trim() || null,
        costRoutingDraft.model.trim() || null
      );
      setWorkflow(updated);
      toast.success(t("costRoutingUpdatedSuccess"));
    } catch (err) {
      toast.error(errorMessage(err, t("costRoutingUpdateError")));
    } finally {
      setCostRoutingBusy(false);
    }
  }

  // economyProviderName: null clears the economy model regardless of `enabled`
  // (WorkflowCostRoutingUpdateRequest's javadoc): the explicit-clear shape.
  async function handleClearCostRouting() {
    if (!workflow) return;
    setCostRoutingBusy(true);
    try {
      const updated = await api.setWorkflowCostRouting(workflowId, false, null, null);
      setWorkflow(updated);
      setCostRoutingDraft({ enabled: false, providerName: "", model: "" });
      toast.success(t("costRoutingClearedSuccess"));
    } catch (err) {
      toast.error(errorMessage(err, t("costRoutingClearError")));
    } finally {
      setCostRoutingBusy(false);
    }
  }

  // Validated client-side against the same 1-200 / 1-300 / 0-5 bounds
  // WorkflowExecutionLimitsUpdateRequest enforces, so a bad value never round-trips to a 400.
  async function handleSaveExecutionLimits() {
    if (!workflow) return;
    const maxSteps = parseInt(executionLimitsDraft.maxStepsPerRun, 10);
    const timeoutSeconds = parseInt(executionLimitsDraft.stepTimeoutSeconds, 10);
    const retryAttempts = parseInt(executionLimitsDraft.maxRetryAttempts, 10);
    if (
      !Number.isFinite(maxSteps) ||
      maxSteps < 1 ||
      maxSteps > 200 ||
      !Number.isFinite(timeoutSeconds) ||
      timeoutSeconds < 1 ||
      timeoutSeconds > 300 ||
      !Number.isFinite(retryAttempts) ||
      retryAttempts < 0 ||
      retryAttempts > 5
    ) {
      toast.error(t("executionLimitsRangeError"));
      return;
    }
    setExecutionLimitsBusy(true);
    try {
      const updated = await api.setWorkflowExecutionLimits(workflowId, maxSteps, timeoutSeconds, retryAttempts);
      setWorkflow(updated);
      toast.success(t("executionLimitsUpdatedSuccess"));
    } catch (err) {
      toast.error(errorMessage(err, t("executionLimitsUpdateError")));
    } finally {
      setExecutionLimitsBusy(false);
    }
  }

  return {
    openIntelligenceModal,
    modalProps: {
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
      tuningModeBusy,
    },
  };
}
