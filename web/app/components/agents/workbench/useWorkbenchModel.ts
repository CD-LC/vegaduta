"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import type { WorkbenchProps } from "./types";
import { useWorkbenchFallbackRouting } from "./useWorkbenchFallbackRouting";
import { useWorkbenchModalityModels } from "./useWorkbenchModalityModels";
import type { WorkbenchModelDeps } from "./workbenchModelDeps";

// The workbench's model handlers: the draft's default model and catalog, the primary model editor,
// the tenant-wide pool/BYOK switch, delete/duplicate, plus the fallback/cost-routing and
// voice/TTS/video editors composed from their own hooks (same hook order as before the split).
export function useWorkbenchModel(p: WorkbenchProps, prev: WorkbenchModelDeps) {
  const { providers, onChanged, onSelect } = p;
  const {
    isDraft,
    draftDefaultAppliedRef,
    setDefaultModelNotice,
    draftTouchedRef,
    setProviderName,
    setModel,
    draftEpoch,
    providerName,
    setDraftModelLoading,
    setDraftModelCatalog,
    setDraftModelUseCustom,
    setDraftModelCatalogError,
    selectedAgent,
    onPlatformLlm,
    setError,
    setSuccess,
    setModelEditing,
    setModelDraft,
    setModelDraftCatalog,
    setModelDraftUseCustom,
    setModelDraftCatalogError,
    modelEditorInitialLoadRef,
    switchByokBusy,
    setSwitchByokBusy,
    tenantDefaultMode,
    setTenantUsePlatformLlm,
    t,
    switchToPoolBusy,
    setSwitchToPoolBusy,
    modelBusy,
    setModelBusy,
    modelDraft,
    selectedAgentIdRef,
    deleteDuplicateBusy,
    setDeleteDuplicateBusy,
  } = prev;


  useEffect(() => {
    if (!isDraft || draftDefaultAppliedRef.current || providers.length === 0) return undefined;
    draftDefaultAppliedRef.current = true;
    let cancelled = false;
    api
      .getDefaultAgentModel()
      .then((d) => {
        if (!cancelled && d?.needsAttention === true) {
          setDefaultModelNotice({
            providerName: d.providerName ?? null,
            needsAttention: true,
            message: d.message ?? null,
          });
        }
        if (cancelled || draftTouchedRef.current || !d?.providerName || d.source === "PLATFORM_POOL") return;
        if (!providers.some((p) => p.providerName === d.providerName && p.status === "ACTIVE")) return;
        setProviderName(d.providerName);
        if (d.model) setModel(d.model);
      })
      .catch(() => {
        /* keep the list-based default */
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isDraft, providers.length, draftEpoch]);

  // Draft model catalog loader - loads the selected provider's models so the
  // creation form's model picker is a searchable dropdown (parity with the
  // "Change model" editor above). Falls back to the free-text input if the
  // provider exposes no catalog (or the lookup fails), so a provider without a
  // published model list is never a dead end.
  useEffect(() => {
    if (!isDraft || !providerName) return undefined;
    let cancelled = false;
    setDraftModelLoading(true);
    api
      .listProviderModels(providerName)
      .then((models) => {
        if (cancelled) return;
        setDraftModelCatalog(models || []);
        setDraftModelUseCustom(false);
        setDraftModelCatalogError(false);
      })
      .catch(() => {
        if (cancelled) return;
        setDraftModelCatalog([]);
        setDraftModelUseCustom(true);
        setDraftModelCatalogError(true);
      })
      .finally(() => {
        if (!cancelled) setDraftModelLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // The setters are stable (reducer dispatch / useState); listed for the lint rule only.
  }, [
    isDraft,
    providerName,
    setDraftModelCatalog,
    setDraftModelCatalogError,
    setDraftModelLoading,
    setDraftModelUseCustom,
  ]);

  function openModelEditor() {
    // Belt-and-suspenders alongside the button's own upgradeLocked={onPlatformLlm}
    // + disabled={locked} above - both current call sites already gate on
    // `locked` before calling this, but this keeps the guard co-located with
    // the actual state change instead of relying solely on the callers
    // staying in sync with it. Note this function does not itself check
    // `locked` - fine today since every call site does, but a single point
    // of failure if a third call site is ever added without that check.
    // Guards onPlatformLlm (not just isFreePlan) since a paid tenant on the
    // managed platform-LLM pool has the same "picking a model here is a
    // no-op" problem as a FREE tenant - AgentChatService ignores the
    // per-agent provider/model for both.
    if (!selectedAgent || onPlatformLlm) return;
    setError(null);
    setSuccess(null);
    setModelEditing(true);
    setModelDraft({ providerName: selectedAgent.providerName, model: selectedAgent.model || "" });
    setModelDraftCatalog([]);
    setModelDraftUseCustom(false);
    setModelDraftCatalogError(false);
    modelEditorInitialLoadRef.current = true;
  }

  // Browser capability for on-device (Edge) inference, read after mount so
  // server and client markup agree. Shown in Device & routing even when the
  // build flag is off, so the section never just disappears.
  const [edgeBrowser, setEdgeBrowser] = useState<{ webgpu: boolean; wasm: boolean } | null>(null);

  useEffect(() => {
    try {
      setEdgeBrowser({
        webgpu: typeof navigator !== "undefined" && "gpu" in navigator,
        wasm: typeof WebAssembly !== "undefined",
      });
    } catch {
      setEdgeBrowser({ webgpu: false, wasm: false });
    }
  }, []);

  // Paid tenants that opted into the managed pool can flip back to BYOK
  // straight from the Platform-LLM note, then pick their own provider/model.
  // FREE tenants can't (they get an upgrade hint instead of this button).
  async function handleSwitchToByok() {
    if (switchByokBusy) return;
    setError(null);
    setSuccess(null);
    setSwitchByokBusy(true);
    try {
      const updated = await api.setTenantUsePlatformLlm(tenantDefaultMode, false);
      setTenantUsePlatformLlm(!!updated?.usePlatformLlm);
      setSuccess(t("switchedToByokSuccess"));
    } catch (err) {
      setError(errorMessage(err, t("switchToByokError")));
    } finally {
      setSwitchByokBusy(false);
    }
  }

  // Symmetric "switch TO the platform pool" — same tenant-wide
  // usePlatformLlm=true call ProvidersPanel.tsx's own toggle already makes,
  // just triggered from the new IntelligenceSourceControl instead. Plain
  // English copy (not t()) to match the rest of the edge flag-gated UI —
  // this button only renders behind edgeEnabled().
  async function handleSwitchToPlatformPool() {
    if (switchToPoolBusy) return;
    setError(null);
    setSuccess(null);
    setSwitchToPoolBusy(true);
    try {
      const updated = await api.setTenantUsePlatformLlm(tenantDefaultMode, true);
      setTenantUsePlatformLlm(!!updated?.usePlatformLlm);
      setSuccess("Switched this workspace to the platform pool.");
    } catch (err) {
      setError(errorMessage(err, "Couldn't switch to the platform pool."));
    } finally {
      setSwitchToPoolBusy(false);
    }
  }

  async function handleUpdateModel() {
    if (!selectedAgent || modelBusy) return;
    setError(null);
    setSuccess(null);
    setModelBusy(true);
    const targetAgentId = selectedAgent.id;
    try {
      const trimmedModel = modelDraft.model.trim();
      await api.updateAgentModel(selectedAgent.id, modelDraft.providerName, trimmedModel);
      setModelEditing(false);
      onChanged();
      if (targetAgentId === selectedAgentIdRef.current) {
        setSuccess(t("modelUpdatedSuccess", { provider: modelDraft.providerName, model: trimmedModel }));
      }
    } catch (err) {
      if (targetAgentId === selectedAgentIdRef.current) {
        setError(errorMessage(err, t("updateModelError")));
      }
    } finally {
      setModelBusy(false);
    }
  }

  // Two independent gates, and this comment used to describe only the first:
  // (1) the 24h edit-lock - delete deliberately skips assertEditable, so a
  // locked agent is still deletable (AgentService#delete's own javadoc); (2)
  // the plan - AgentService#delete throws TenantLimitExceededException for
  // FREE (WB-001, closing a hole where a direct API call beat the paywall).
  // So this handler is paid-plan-only in practice, and the action bar must
  // never route a FREE tenant here: it renders an upgrade-locked button
  // instead, so the paywall is learned before the click, not from a 403 toast.
  // Confirmation is handled by the AlertDialog wrapping the Delete button (see
  // the action bar) — an accessible, focus-trapped modal that replaces the old
  // window.confirm() blocking prompt.
  async function handleDeleteAgent() {
    if (!selectedAgent || deleteDuplicateBusy) return;
    setError(null);
    setSuccess(null);
    setDeleteDuplicateBusy(true);
    try {
      await api.deleteAgent(selectedAgent.id);
      setSuccess(t("agentDeletedSuccess", { name: selectedAgent.name }));
      onSelect(null);
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("deleteAgentError")));
    } finally {
      setDeleteDuplicateBusy(false);
    }
  }

  // A FREE tenant's only way to change a locked (24h+) agent - available
  // regardless of plan, not just Unlimited.
  async function handleDuplicateAgent() {
    if (!selectedAgent || deleteDuplicateBusy) return;
    setError(null);
    setSuccess(null);
    setDeleteDuplicateBusy(true);
    try {
      const created = await api.duplicateAgent(selectedAgent.id);
      setSuccess(t("agentDuplicatedSuccess", { name: created.name }));
      onChanged();
      onSelect(created.id);
    } catch (err) {
      setError(errorMessage(err, t("duplicateAgentError")));
    } finally {
      setDeleteDuplicateBusy(false);
    }
  }

  const fallbackRouting = useWorkbenchFallbackRouting(p, prev);
  const modalityModels = useWorkbenchModalityModels(p, prev);

  return {
    handleDuplicateAgent,
    handleDeleteAgent,
    openModelEditor,
    handleSwitchToByok,
    handleSwitchToPlatformPool,
    handleUpdateModel,
    edgeBrowser,
    ...fallbackRouting,
    ...modalityModels,
  };
}
