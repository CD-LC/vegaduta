"use client";

import { useEffect, useState } from "react";
import { api, type ProviderModelInfo } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import type { AgentReliabilitySummary } from "../AgentWorkbench/types";
import type { WorkbenchProps } from "./types";
import type { useAgentDraft } from "./useAgentDraft";
import type { useAgentSave } from "./useAgentSave";
import type { useAgentValidation } from "./useAgentValidation";
import type { useWorkbenchAccess } from "./useWorkbenchAccess";
import type { useWorkbenchBase } from "./useWorkbenchBase";
import type { useWorkbenchRouting } from "./useWorkbenchRouting";
import type { useWorkbenchTenant } from "./useWorkbenchTenant";

export function useWorkbenchEditors(
  p: WorkbenchProps,
  prev: ReturnType<typeof useWorkbenchBase> &
    ReturnType<typeof useWorkbenchTenant> &
    ReturnType<typeof useAgentDraft> &
    ReturnType<typeof useWorkbenchAccess> &
    ReturnType<typeof useWorkbenchRouting> &
    ReturnType<typeof useAgentValidation> &
    ReturnType<typeof useAgentSave>
) {
  const { onChanged } = p;
  const {
    selectedAgent,
    setError,
    setSuccess,
    selectedAgentIdRef,
    t,
    modelEditing,
    modelDraft,
    setModelDraftLoading,
    modelEditorInitialLoadRef,
    setModelDraftCatalog,
    setModelDraftCatalogError,
    setModelDraftUseCustom,
    setModelDraft,
  } = prev;

  // Direct feedback: "set a fallback provider/model so future provider
  // outages degrade gracefully instead of failing the run" - same
  // editing-toggle/draft-state shape as modelEditing/modelDraft above
  // (Change model), pointed at PATCH /api/agents/{id}/fallback instead.
  const [fallbackEditing, setFallbackEditing] = useState(false);

  const [fallbackDraft, setFallbackDraft] = useState({ providerName: "", model: "" });

  const [fallbackDraftCatalog, setFallbackDraftCatalog] = useState<ProviderModelInfo[]>([]);

  const [fallbackDraftLoading, setFallbackDraftLoading] = useState(false);

  const [fallbackDraftUseCustom, setFallbackDraftUseCustom] = useState(false);

  // WB-028: see draftModelCatalogError above - same "fetch actually failed"
  // signal, distinct from "provider has no published catalog".
  const [fallbackDraftCatalogError, setFallbackDraftCatalogError] = useState(false);

  const [fallbackBusy, setFallbackBusy] = useState(false);

  // #1077 fast-follow (Master Roadmap #18, cost optimization: routing) -
  // RouteLLM/NotDiamond-style opt-in: route simple turns to a cheaper
  // "economy" model. Same editing-toggle/draft-state shape as
  // fallbackEditing/fallbackDraft above, pointed at PATCH
  // /api/agents/{id}/cost-routing instead. `enabled` is tracked separately
  // from the provider/model draft since a tenant can flip the toggle
  // without necessarily also changing which economy model is configured.
  const [costRoutingEditing, setCostRoutingEditing] = useState(false);

  const [costRoutingDraft, setCostRoutingDraft] = useState({
    enabled: false,
    providerName: "",
    model: "",
    jevThreshold: "",
  });

  const [costRoutingDraftCatalog, setCostRoutingDraftCatalog] = useState<ProviderModelInfo[]>([]);

  const [costRoutingDraftLoading, setCostRoutingDraftLoading] = useState(false);

  const [costRoutingDraftUseCustom, setCostRoutingDraftUseCustom] = useState(false);

  // WB-028: see draftModelCatalogError above - same "fetch actually failed"
  // signal, distinct from "provider has no published catalog".
  const [costRoutingDraftCatalogError, setCostRoutingDraftCatalogError] = useState(false);

  const [costRoutingBusy, setCostRoutingBusy] = useState(false);

  // Voice model provider selection (STT) - Premium feature, similar to LLM
  // provider selection above. Edit-lock gated same as model/fallback/cost-routing.
  // Pointed at PATCH /api/agents/{id}/voice-model instead.
  const [voiceModelEditing, setVoiceModelEditing] = useState(false);

  const [voiceModelDraft, setVoiceModelDraft] = useState({ voiceModelProvider: null, voiceModel: null });

  const [voiceModelBusy, setVoiceModelBusy] = useState(false);

  // TTS provider selection (Stage 2, 2026-08-09) - output-side twin of the
  // voice (STT) block above, same shape, pointed at PATCH
  // /api/agents/{id}/tts-model instead.
  const [ttsModelEditing, setTtsModelEditing] = useState(false);

  const [ttsModelDraft, setTtsModelDraft] = useState({ ttsProvider: null, ttsModel: null });

  const [ttsModelBusy, setTtsModelBusy] = useState(false);

  // Video model provider selection (Video-to-Text) - Premium feature, similar to voice
  // provider selection above. Edit-lock gated same as model/fallback/cost-routing.
  // Pointed at PATCH /api/agents/{id}/video-model instead.
  const [videoModelEditing, setVideoModelEditing] = useState(false);

  const [videoModelDraft, setVideoModelDraft] = useState({ videoModelProvider: null, videoModel: null });

  const [videoModelBusy, setVideoModelBusy] = useState(false);

  // Consumer-facing capability: "consumers should be able to edit the agent
  // prompt, give a small edit icon and once modified save should get
  // enabled" - promptDraft only diverges from selectedAgent.systemPrompt
  // once the user actually types something, so Save stays disabled until
  // there's a real change to persist (not just "editor is open").
  const [promptEditing, setPromptEditing] = useState(false);

  const [promptDraft, setPromptDraft] = useState("");

  const [promptBusy, setPromptBusy] = useState(false);

  // Task #959 ("Agent performance: response time + reliability + failure
  // handling") - undefined (not yet fetched) | "loading" | "error" | the
  // real AgentReliabilitySummary from GET /api/agents/{id}/reliability.
  // Same three-state sentinel shape toolsResolved already uses above.
  const [reliability, setReliability] = useState<undefined | "loading" | "error" | AgentReliabilitySummary>(undefined);

  const promptDirty = selectedAgent && promptDraft.trim() !== "" && promptDraft !== selectedAgent.systemPrompt;

  // Client-side pagination for the "Recent failures" list (Reliability section)
  // and search + pagination for the Knowledge collections list. All reset per
  // agent switch (see the selectedAgentId effect below) and, for search, back
  // to page 1 whenever the query changes.
  const [failurePage, setFailurePage] = useState(0);

  function openPromptEditor() {
    if (!selectedAgent) return;
    setError(null);
    setSuccess(null);
    setPromptDraft(selectedAgent.systemPrompt || "");
    setPromptEditing(true);
  }

  async function handleUpdatePrompt() {
    if (!selectedAgent || !promptDirty) return;
    setError(null);
    setSuccess(null);
    setPromptBusy(true);
    // WB-012: this save's target - guards setSuccess/setError below so a
    // slow response for one agent can't land its banner on whatever agent
    // is on screen by the time it resolves.
    const targetAgentId = selectedAgent.id;
    try {
      await api.updateAgentPrompt(selectedAgent.id, promptDraft.trim());
      setPromptEditing(false);
      onChanged();
      if (targetAgentId === selectedAgentIdRef.current) {
        setSuccess(t("promptUpdated"));
      }
    } catch (err) {
      if (targetAgentId === selectedAgentIdRef.current) {
        setError(errorMessage(err, t("updatePromptError")));
      }
    } finally {
      setPromptBusy(false);
    }
  }

  useEffect(() => {
    if (!modelEditing || !modelDraft.providerName) return undefined;
    let cancelled = false;
    setModelDraftLoading(true);
    // Distinguish "editor was just opened with the agent's already-saved
    // provider" (this effect's own dependency array fires on that initial
    // mount too, since providerName goes from "" -> the saved value) from a
    // genuine user-driven provider switch. Only the latter should clear the
    // model - otherwise a valid saved model that simply isn't present in the
    // fetched catalog (BYOK custom/fine-tuned id, or a provider whose live
    // per-tenant fetch fell back to the static seed catalog) gets silently
    // blanked the instant "Change model" is opened.
    const isInitialProviderLoad = modelEditorInitialLoadRef.current;
    modelEditorInitialLoadRef.current = false;
    api
      .listProviderModels(modelDraft.providerName)
      .then((models) => {
        if (cancelled) return;
        setModelDraftCatalog(models || []);
        setModelDraftCatalogError(false);
        const modelInCatalog = models?.some((m) => m.modelId === modelDraft.model);
        if (isInitialProviderLoad) {
          // Preserve the saved model even if it's not in the catalog; fall
          // back to the free-text field so it's still visible/editable
          // rather than disappearing.
          setModelDraftUseCustom(!modelInCatalog);
        } else {
          setModelDraft((d) => ({ ...d, model: modelInCatalog ? d.model : "" }));
          setModelDraftUseCustom(false);
        }
      })
      .catch(() => {
        if (cancelled) return;
        setModelDraftCatalog([]);
        setModelDraftUseCustom(true);
        setModelDraftCatalogError(true);
      })
      .finally(() => {
        if (!cancelled) setModelDraftLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [modelEditing, modelDraft.providerName]);

  return {
    fallbackEditing,
    fallbackDraft,
    setFallbackDraftLoading,
    setFallbackDraftCatalog,
    setFallbackDraft,
    setFallbackDraftUseCustom,
    setFallbackDraftCatalogError,
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
    setVoiceModelEditing,
    setVoiceModelDraft,
    setTtsModelEditing,
    setTtsModelDraft,
    setVideoModelEditing,
    setVideoModelDraft,
    voiceModelBusy,
    setVoiceModelBusy,
    voiceModelDraft,
    ttsModelBusy,
    setTtsModelBusy,
    ttsModelDraft,
    videoModelBusy,
    setVideoModelBusy,
    videoModelDraft,
    setPromptEditing,
    setFailurePage,
    setReliability,
    promptBusy,
    reliability,
    promptEditing,
    promptDraft,
    setPromptDraft,
    openPromptEditor,
    promptDirty,
    handleUpdatePrompt,
    fallbackDraftUseCustom,
    fallbackDraftCatalog,
    fallbackDraftLoading,
    fallbackDraftCatalogError,
    costRoutingDraftUseCustom,
    costRoutingDraftCatalog,
    costRoutingDraftLoading,
    costRoutingDraftCatalogError,
    voiceModelEditing,
    videoModelEditing,
    ttsModelEditing,
    failurePage,
  };
}
