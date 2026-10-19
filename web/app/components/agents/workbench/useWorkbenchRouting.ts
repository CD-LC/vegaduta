"use client";

import { canEditAgent } from "../agentAccess";
import { useEffect, useRef, useState } from "react";
import { type ProviderModelInfo } from "../../../lib/api";
import { setAgentEdgeRoutingPolicy, type EdgeRoutingPolicy } from "../../../lib/edge/agentPolicy";
import { edgeEnabled } from "../../../lib/edge/capabilities";
import { previewRouting, type RoutingPreview, type TenantProviderState } from "../../../lib/edge/routingState";
import { errorMessage } from "../../../lib/errors";
import { resolveProviderGate } from "../../../lib/providerGate";
import type { WorkbenchProps } from "./types";
import type { useAgentDraft } from "./useAgentDraft";
import type { useWorkbenchAccess } from "./useWorkbenchAccess";
import type { useWorkbenchBase } from "./useWorkbenchBase";
import type { useWorkbenchTenant } from "./useWorkbenchTenant";

export function useWorkbenchRouting(
  p: WorkbenchProps,
  prev: ReturnType<typeof useWorkbenchBase> &
    ReturnType<typeof useWorkbenchTenant> &
    ReturnType<typeof useAgentDraft> &
    ReturnType<typeof useWorkbenchAccess>
) {
  const { onChanged } = p;
  const { selectedAgent, onPlatformLlm, activeProviders, isDraft, gateInput, setError, setSuccess } = prev;

  // Unlimited-plan capability: change model, delete, duplicate. Ported
  // from AgentsPanel.js (the pre-AXON component) - AgentWorkbench never got
  // these when it replaced AgentsPanel as the routed /agents screen, even
  // though AgentController/api.js have always supported them (updateAgentModel,
  // deleteAgent, duplicateAgent). Real production bug: an Unlimited-plan
  // tenant had nothing to click to actually use that "everything editable"
  // capability. `locked` mirrors AgentsPanel.js's own gate (EditLockPolicy
  // via AgentResponse.editable - undefined/older cached shape treated as
  // editable, never locked).
  const locked = selectedAgent && (selectedAgent.editable === false || !canEditAgent(selectedAgent));

  const [modelEditing, setModelEditing] = useState(false);

  const [modelDraft, setModelDraft] = useState({ providerName: "", model: "" });

  const [modelDraftCatalog, setModelDraftCatalog] = useState<ProviderModelInfo[]>([]);

  const [modelDraftLoading, setModelDraftLoading] = useState(false);

  const [modelDraftUseCustom, setModelDraftUseCustom] = useState(false);

  // WB-028: see draftModelCatalogError above - same "fetch actually failed"
  // signal, distinct from "provider has no published catalog".
  const [modelDraftCatalogError, setModelDraftCatalogError] = useState(false);

  // Tracks whether the next modelDraftCatalog fetch is the one triggered by
  // opening the editor (vs. a subsequent genuine provider switch) - see the
  // effect below. Reset in openModelEditor().
  const modelEditorInitialLoadRef = useRef(true);

  const [modelBusy, setModelBusy] = useState(false);

  const [switchByokBusy, setSwitchByokBusy] = useState(false);

  // Symmetric counterpart to handleSwitchToByok below, for the new
  // "Platform pool / On-device / Your own provider" control — same
  // api.setTenantUsePlatformLlm call, just the other boolean.
  const [switchToPoolBusy, setSwitchToPoolBusy] = useState(false);

  const [deleteDuplicateBusy, setDeleteDuplicateBusy] = useState(false);

  // Bug B fix: IntelligenceSourceControl computes "platform / on-device /
  // own-provider" internally but never surfaced it here, so this header's
  // hosted-model badge + "Change model" button had no way to grey themselves
  // out while on-device is actually selected — reported back via the new
  // onSourceSelected prop.
  const [intelSource, setIntelSource] = useState<"platform" | "on-device" | "own-provider">("platform");

  // Part 4 requirement 4 — "Your request will be processed by {X}" live
  // preview, next to the IntelligenceSourceControl instance. Re-resolved
  // (not just re-read from storage) via routingState.ts's previewRouting()
  // whenever the routing-relevant inputs below change, so it reflects what
  // the NEXT turn will actually do, not just the stored preference.
  //
  // Wave 2 fix (audit finding P0): now also passes selectedAgent's id so
  // previewRouting() can consult that agent's server-declared routing
  // policy FIRST (fetchEdgeAgentPolicy, same TTL-cached GET routeTurn()
  // itself calls) — see previewRouting()'s own doc comment. A draft agent
  // (isDraft, no id yet) passes null, same as before this fix (no policy
  // can exist for it). selectedAgent?.id added to the deps below so
  // switching the selected agent re-resolves the preview against the new
  // agent's policy, not the previous one's.
  const [routingPreview, setRoutingPreview] = useState<RoutingPreview | null>(null);

  // Keyed on the id (a stable primitive), not the selectedAgent object, which
  // is re-derived from the `agents` array on every render.
  const resolvedAgentId = selectedAgent?.id ?? null;

  useEffect(() => {
    if (!edgeEnabled()) {
      setRoutingPreview(null);
      return undefined;
    }
    let cancelled = false;
    const tenant: TenantProviderState = {
      usePlatformLlm: onPlatformLlm,
      activeProviderCount: activeProviders.length,
    };
    void previewRouting(tenant, isDraft ? null : resolvedAgentId).then((preview) => {
      if (!cancelled) setRoutingPreview(preview);
    });
    return () => {
      cancelled = true;
    };
  }, [intelSource, onPlatformLlm, activeProviders.length, isDraft, resolvedAgentId]);

  // The ONE place that decides "this tenant has no way to run an agent", read
  // by both the warning banner and validate()'s create-gate so they cannot
  // disagree. True only when every route is genuinely closed: the state is
  // loaded, the server does not route this tenant to the hosted pool, there is
  // no BYOK key, AND the next turn would not be served on-device either
  // (routingPreview tier 0/1 = a local model is ready and selected - an agent
  // that runs on the visitor's own machine needs no provider at all).
  const { needsProvider } = resolveProviderGate({ ...gateInput, routingTier: routingPreview?.tier });

  // Audit finding P1: the per-agent EDGE_ONLY/HOSTED_ONLY server routing
  // policy (AgentController's PATCH /api/agents/{id}/edge-routing-policy,
  // agentService.setEdgeRoutingPolicy) had a complete backend with no
  // product surface anywhere to view or set it. selectedAgent.edgeRoutingPolicy
  // already carries the current value (AgentResponse's own field, returned
  // by the same GET /api/agents this component's `agents` prop is built
  // from) — no separate fetch needed to populate this draft, same
  // "read straight off the server object" pattern the tuningMode/
  // experienceMode toggles above use. "" means "Platform default" (the
  // PATCH's own null-clears-the-override contract).
  const [edgeRoutingPolicyDraft, setEdgeRoutingPolicyDraft] = useState<EdgeRoutingPolicy | "">("");

  const [edgeRoutingPolicyBusy, setEdgeRoutingPolicyBusy] = useState(false);

  const savedEdgeRoutingPolicy = selectedAgent?.edgeRoutingPolicy || "";

  // resolvedAgentId is a dep on purpose: switching agents resets the draft
  // even when both agents share the same saved policy.
  useEffect(() => {
    setEdgeRoutingPolicyDraft(savedEdgeRoutingPolicy);
  }, [resolvedAgentId, savedEdgeRoutingPolicy]);

  async function handleSaveEdgeRoutingPolicy() {
    if (!selectedAgent || edgeRoutingPolicyBusy) return;
    setEdgeRoutingPolicyBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await setAgentEdgeRoutingPolicy(selectedAgent.id, edgeRoutingPolicyDraft || null);
      setSuccess("Saved this agent's routing policy.");
      onChanged();
    } catch (err) {
      setError(errorMessage(err, "Couldn't save this agent's routing policy."));
    } finally {
      setEdgeRoutingPolicyBusy(false);
    }
  }

  return {
    needsProvider,
    modelEditing,
    modelDraft,
    setModelDraftLoading,
    modelEditorInitialLoadRef,
    setModelDraftCatalog,
    setModelDraftCatalogError,
    setModelDraftUseCustom,
    setModelDraft,
    setModelEditing,
    switchByokBusy,
    setSwitchByokBusy,
    switchToPoolBusy,
    setSwitchToPoolBusy,
    modelBusy,
    setModelBusy,
    locked,
    deleteDuplicateBusy,
    setDeleteDuplicateBusy,
    resolvedAgentId,
    intelSource,
    routingPreview,
    modelDraftUseCustom,
    modelDraftCatalog,
    modelDraftLoading,
    modelDraftCatalogError,
    setIntelSource,
    edgeRoutingPolicyDraft,
    setEdgeRoutingPolicyDraft,
    edgeRoutingPolicyBusy,
    handleSaveEdgeRoutingPolicy,
  };
}
