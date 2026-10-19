"use client";

import { useEffect } from "react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import type { WorkbenchProps } from "./types";
import type { WorkbenchModelDeps } from "./workbenchModelDeps";

/** The saved agent's fallback model and economy (cost-routing) model editors: catalog loading, open, save, clear. */
export function useWorkbenchFallbackRouting(p: WorkbenchProps, prev: WorkbenchModelDeps) {
  const { onChanged } = p;
  const {
    selectedAgent,
    setError,
    setSuccess,
    t,
    selectedAgentIdRef,
    fallbackEditing,
    fallbackDraft,
    setFallbackDraftLoading,
    setFallbackDraftCatalog,
    setFallbackDraft,
    setFallbackDraftUseCustom,
    setFallbackDraftCatalogError,
    locked,
    setFallbackEditing,
    fallbackBusy,
    setFallbackBusy,
    costRoutingEditing,
    costRoutingDraft,
    setCostRoutingDraftLoading,
    setCostRoutingDraftCatalog,
    setCostRoutingDraft,
    setCostRoutingDraftUseCustom,
    setCostRoutingDraftCatalogError,
    setCostRoutingEditing,
    costRoutingBusy,
    setCostRoutingBusy,
  } = prev;

  useEffect(() => {
    if (!fallbackEditing || !fallbackDraft.providerName) return undefined;
    let cancelled = false;
    setFallbackDraftLoading(true);
    api
      .listProviderModels(fallbackDraft.providerName)
      .then((models) => {
        if (cancelled) return;
        setFallbackDraftCatalog(models || []);
        setFallbackDraft((d) => ({
          ...d,
          model: models?.some((m) => m.modelId === d.model) ? d.model : "",
        }));
        setFallbackDraftUseCustom(false);
        setFallbackDraftCatalogError(false);
      })
      .catch(() => {
        if (cancelled) return;
        setFallbackDraftCatalog([]);
        setFallbackDraftUseCustom(true);
        setFallbackDraftCatalogError(true);
      })
      .finally(() => {
        if (!cancelled) setFallbackDraftLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [
    fallbackEditing,
    fallbackDraft.providerName,
    setFallbackDraft,
    setFallbackDraftCatalog,
    setFallbackDraftCatalogError,
    setFallbackDraftLoading,
    setFallbackDraftUseCustom,
  ]);

  function openFallbackEditor() {
    // Belt-and-suspenders alongside the button's own disabled={locked} - see
    // openModelEditor's identical guard/comment above.
    if (!selectedAgent || locked) return;
    setError(null);
    setSuccess(null);
    setFallbackEditing(true);
    setFallbackDraft({
      providerName: selectedAgent.fallbackProviderName || "",
      model: selectedAgent.fallbackModel || "",
    });
    setFallbackDraftCatalog([]);
    setFallbackDraftUseCustom(false);
    setFallbackDraftCatalogError(false);
  }

  async function handleUpdateFallback() {
    if (!selectedAgent || !fallbackDraft.providerName || fallbackBusy) return;
    setError(null);
    setSuccess(null);
    setFallbackBusy(true);
    const targetAgentId = selectedAgent.id;
    try {
      const trimmedModel = fallbackDraft.model.trim();
      await api.setAgentFallback(selectedAgent.id, fallbackDraft.providerName, trimmedModel);
      setFallbackEditing(false);
      onChanged();
      if (targetAgentId === selectedAgentIdRef.current) {
        setSuccess(t("fallbackSetSuccess", { provider: fallbackDraft.providerName, model: trimmedModel }));
      }
    } catch (err) {
      if (targetAgentId === selectedAgentIdRef.current) {
        setError(errorMessage(err, t("setFallbackError")));
      }
    } finally {
      setFallbackBusy(false);
    }
  }

  // providerName: null clears the fallback - AgentFallbackUpdateRequest's
  // javadoc, same "explicit clear" shape the backend expects.
  async function handleClearFallback() {
    if (!selectedAgent || fallbackBusy) return;
    setError(null);
    setSuccess(null);
    setFallbackBusy(true);
    const targetAgentId = selectedAgent.id;
    try {
      await api.setAgentFallback(selectedAgent.id, null, null);
      setFallbackEditing(false);
      onChanged();
      if (targetAgentId === selectedAgentIdRef.current) {
        setSuccess(t("fallbackClearedSuccess"));
      }
    } catch (err) {
      if (targetAgentId === selectedAgentIdRef.current) {
        setError(errorMessage(err, t("clearFallbackError")));
      }
    } finally {
      setFallbackBusy(false);
    }
  }

  useEffect(() => {
    if (!costRoutingEditing || !costRoutingDraft.providerName) return undefined;
    let cancelled = false;
    setCostRoutingDraftLoading(true);
    api
      .listProviderModels(costRoutingDraft.providerName)
      .then((models) => {
        if (cancelled) return;
        setCostRoutingDraftCatalog(models || []);
        setCostRoutingDraft((d) => ({
          ...d,
          model: models?.some((m) => m.modelId === d.model) ? d.model : "",
        }));
        setCostRoutingDraftUseCustom(false);
        setCostRoutingDraftCatalogError(false);
      })
      .catch(() => {
        if (cancelled) return;
        setCostRoutingDraftCatalog([]);
        setCostRoutingDraftUseCustom(true);
        setCostRoutingDraftCatalogError(true);
      })
      .finally(() => {
        if (!cancelled) setCostRoutingDraftLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [
    costRoutingEditing,
    costRoutingDraft.providerName,
    setCostRoutingDraft,
    setCostRoutingDraftCatalog,
    setCostRoutingDraftCatalogError,
    setCostRoutingDraftLoading,
    setCostRoutingDraftUseCustom,
  ]);

  function openCostRoutingEditor() {
    // Belt-and-suspenders alongside the button's own disabled={locked} - see
    // openModelEditor's identical guard/comment above.
    if (!selectedAgent || locked) return;
    setError(null);
    setSuccess(null);
    setCostRoutingEditing(true);
    setCostRoutingDraft({
      enabled: !!selectedAgent.costRoutingEnabled,
      providerName: selectedAgent.economyProviderName || "",
      model: selectedAgent.economyModel || "",
      jevThreshold: selectedAgent.costRoutingJevThreshold != null ? String(selectedAgent.costRoutingJevThreshold) : "",
    });
    setCostRoutingDraftCatalog([]);
    setCostRoutingDraftUseCustom(false);
    setCostRoutingDraftCatalogError(false);
  }

  async function handleUpdateCostRouting() {
    if (!selectedAgent || !costRoutingDraft.providerName || costRoutingBusy) return;
    setError(null);
    setSuccess(null);
    setCostRoutingBusy(true);
    const targetAgentId = selectedAgent.id;
    try {
      const trimmedModel = costRoutingDraft.model.trim();
      const parsedThreshold =
        costRoutingDraft.jevThreshold.trim() === "" ? null : Number(costRoutingDraft.jevThreshold);
      await api.setAgentCostRouting(
        selectedAgent.id,
        costRoutingDraft.enabled,
        costRoutingDraft.providerName,
        trimmedModel,
        Number.isFinite(parsedThreshold) ? parsedThreshold : null
      );
      setCostRoutingEditing(false);
      onChanged();
      if (targetAgentId === selectedAgentIdRef.current) {
        setSuccess(t("costRoutingSetSuccess", { provider: costRoutingDraft.providerName, model: trimmedModel }));
      }
    } catch (err) {
      if (targetAgentId === selectedAgentIdRef.current) {
        setError(errorMessage(err, t("setCostRoutingError")));
      }
    } finally {
      setCostRoutingBusy(false);
    }
  }

  // economyProviderName: null clears the economy model regardless of
  // `enabled` - AgentCostRoutingUpdateRequest's javadoc, same "explicit
  // clear" shape handleClearFallback uses above.
  async function handleClearCostRouting() {
    if (!selectedAgent || costRoutingBusy) return;
    setError(null);
    setSuccess(null);
    setCostRoutingBusy(true);
    const targetAgentId = selectedAgent.id;
    try {
      await api.setAgentCostRouting(selectedAgent.id, false, null, null);
      setCostRoutingEditing(false);
      onChanged();
      if (targetAgentId === selectedAgentIdRef.current) {
        setSuccess(t("costRoutingClearedSuccess"));
      }
    } catch (err) {
      if (targetAgentId === selectedAgentIdRef.current) {
        setError(errorMessage(err, t("clearCostRoutingError")));
      }
    } finally {
      setCostRoutingBusy(false);
    }
  }

  return {
    openFallbackEditor,
    handleUpdateFallback,
    handleClearFallback,
    openCostRoutingEditor,
    handleUpdateCostRouting,
    handleClearCostRouting,
  };
}
