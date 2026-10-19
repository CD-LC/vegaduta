"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { isTrifectaBlockError } from "../AgentTrifectaWarning";
import type { WorkbenchAgent } from "../AgentWorkbench/types";
import type { WorkbenchProps } from "./types";
import type { useAgentDraft } from "./useAgentDraft";
import type { useAgentSave } from "./useAgentSave";
import type { useAgentValidation } from "./useAgentValidation";
import type { useWorkbenchAccess } from "./useWorkbenchAccess";
import type { useWorkbenchBase } from "./useWorkbenchBase";
import type { useWorkbenchEditors } from "./useWorkbenchEditors";
import type { useWorkbenchModel } from "./useWorkbenchModel";
import type { useWorkbenchRouting } from "./useWorkbenchRouting";
import type { useWorkbenchTenant } from "./useWorkbenchTenant";

export function useWorkbenchTuning(
  p: WorkbenchProps,
  prev: ReturnType<typeof useWorkbenchBase> &
    ReturnType<typeof useWorkbenchTenant> &
    ReturnType<typeof useAgentDraft> &
    ReturnType<typeof useWorkbenchAccess> &
    ReturnType<typeof useWorkbenchRouting> &
    ReturnType<typeof useAgentValidation> &
    ReturnType<typeof useAgentSave> &
    ReturnType<typeof useWorkbenchEditors> &
    ReturnType<typeof useWorkbenchModel>
) {
  const { selectedAgentId, onChanged } = p;
  const {
    setError,
    setSuccess,
    setEditingKnowledge,
    setModelEditing,
    setFallbackEditing,
    setCostRoutingEditing,
    setVoiceModelEditing,
    setVideoModelEditing,
    setVideoModelDraft,
    setPromptEditing,
    setEditingCodeIntelRepos,
    setFailurePage,
    selectedAgent,
    setGenDraft,
    setKnowledgeDraft,
    setCodeIntelRepoDraft,
    setToolsResolved,
    resolvedAgentId,
    setReliability,
    tenantDefaultMode,
    toolsResolved,
    genDraft,
    genBusy,
    setGenBusy,
    t,
    modeBusy,
    setModeBusy,
    locked,
    toolBusyName,
    setToolBusyName,
    knowledgeBusy,
    setKnowledgeBusy,
    knowledgeDraft,
    codeIntelReposBusy,
    setCodeIntelReposBusy,
    codeIntelRepoDraft,
  } = prev;

  useEffect(() => {
    setError(null);
    setSuccess(null);
    setEditingKnowledge(false);
    setModelEditing(false);
    setFallbackEditing(false);
    setCostRoutingEditing(false);
    setVoiceModelEditing(false);
    setVideoModelEditing(false);
    setVideoModelDraft({ videoModelProvider: null, videoModel: null });
    setPromptEditing(false);
    setEditingCodeIntelRepos(false);
    setFailurePage(0);
    if (selectedAgent) {
      setGenDraft({
        maxTokens: selectedAgent.maxTokens,
        temperature: selectedAgent.temperature,
        topP: selectedAgent.topP,
      });
      setKnowledgeDraft(new Set(selectedAgent.allowedKnowledgeCollectionIds || []));
      setCodeIntelRepoDraft(new Set(selectedAgent.allowedCodeIntelRepoIds || []));
      setToolsResolved(undefined);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedAgentId]);

  // Task #959 - fetch this agent's reliability summary whenever the
  // selected agent changes. A draft (no id saved yet) has nothing to
  // query, so reliability stays undefined and the card below doesn't
  // render at all for it - same "existing agents only" gating every other
  // per-agent read (tool calls, memories) on this screen already has.
  //
  // Bug fix (2026-08-07, live user report: panel stuck on the loading
  // skeleton forever). This depended on [selectedAgentId] (a prop), but
  // the guard below checks `selectedAgent` - a value DERIVED fresh every
  // render from `agents.find(...)` (line ~224), not itself a dependency.
  // If selectedAgentId was already set before `agents` finished loading
  // (e.g. restored on mount from a URL/localStorage id), this effect fired
  // exactly once with selectedAgent still null, hit the early return, set
  // reliability=undefined (the loading-skeleton state), and then NEVER
  // ran again once `agents` populated and selectedAgent became truthy -
  // selectedAgentId itself hadn't changed, so the effect had nothing left
  // to re-trigger it. Zero calls ever reached the server, matching the
  // report exactly. Depending on `selectedAgent?.id` instead (a stable
  // primitive, not the `agents` array reference) fixes the race without
  // reintroducing the "refetch on every unrelated agents-array reference
  // change" concern the disabled exhaustive-deps lint was guarding against.
  useEffect(() => {
    if (!resolvedAgentId) {
      setReliability(undefined);
      return undefined;
    }
    let cancelled = false;
    setReliability("loading");
    api
      .getAgentReliability(resolvedAgentId)
      .then((data) => {
        if (!cancelled) setReliability(data);
      })
      .catch(() => {
        if (!cancelled) setReliability("error");
      });
    return () => {
      cancelled = true;
    };
  }, [resolvedAgentId, setReliability]);

  function effectiveMode(agent: WorkbenchAgent) {
    return agent.experienceMode || tenantDefaultMode;
  }

  useEffect(() => {
    if (!selectedAgent) return undefined;
    if (effectiveMode(selectedAgent) !== "ADVANCED") return undefined;
    if (toolsResolved !== undefined) return undefined;
    let cancelled = false;
    setToolsResolved("loading");
    api
      .listAvailableTools(selectedAgent.id)
      .then((tools) => {
        // Rapidly switching agents (A -> B -> A) can let an earlier,
        // now-irrelevant fetch resolve after a later one - same "cancelled"
        // guard the sibling reliability effect above already uses, so a
        // stale response can't overwrite the currently-selected agent's
        // tool/MCP access list with a different agent's.
        if (!cancelled) setToolsResolved(tools);
      })
      .catch(() => {
        if (!cancelled) setToolsResolved("error");
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedAgent?.id, selectedAgent?.experienceMode]);

  const genDirty =
    genDraft &&
    selectedAgent &&
    (genDraft.maxTokens !== selectedAgent.maxTokens ||
      genDraft.temperature !== selectedAgent.temperature ||
      genDraft.topP !== selectedAgent.topP);

  async function saveGen() {
    if (!selectedAgent || genBusy) return;
    setGenBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await api.setAgentGenerationParams(selectedAgent.id, genDraft);
      setSuccess(t("generationSettingsUpdated"));
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("updateGenerationSettingsError")));
    } finally {
      setGenBusy(false);
    }
  }

  async function toggleMode() {
    if (!selectedAgent || modeBusy) return;
    const next = effectiveMode(selectedAgent) === "SIMPLE" ? "ADVANCED" : "SIMPLE";
    setModeBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await api.setAgentExperienceMode(selectedAgent.id, next);
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("switchModeError")));
    } finally {
      setModeBusy(false);
    }
  }

  const [tuningBusy, setTuningBusy] = useState(false);

  async function toggleTuningMode() {
    if (!selectedAgent || tuningBusy || locked) return;
    const next = selectedAgent.tuningMode === "SIMPLE" ? "MANUAL" : "SIMPLE";
    setTuningBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await api.setAgentTuningMode(selectedAgent.id, next);
      setSuccess(next === "SIMPLE" ? t("switchedToSimpleTuning") : t("switchedToManualTuning"));
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("updateTuningModeError")));
    } finally {
      setTuningBusy(false);
    }
  }

  // Reasoning depth (Agent Intelligence research doc, July 2026) - the
  // backend PATCH /api/agents/{id}/reasoning-depth has existed since that
  // work landed (api.setAgentReasoningDepth), but no UI ever called it, so
  // every agent silently stayed on the FAST default (single-pass chatbot
  // behavior) with no way to opt into planning/verification. Same
  // live-patch-on-click shape as toggleTuningMode above, plus an optimistic
  // override so the pill flips instantly instead of waiting for onChanged()'s
  // refetch. The override is keyed by agent id (not reset in the
  // selectedAgentId effect) so a stale in-flight value can never bleed onto a
  // different agent after a quick A -> B switch.
  const [reasoningDepthBusy, setReasoningDepthBusy] = useState(false);

  const [reasoningDepthPending, setReasoningDepthPending] = useState<{ agentId: string; depth: string } | null>(null);

  // Never null on the server (defaults to FAST), but older rows / partial
  // DTOs may omit it - fall back to the same FAST default the backend uses.
  const reasoningDepth =
    (reasoningDepthPending && selectedAgent && reasoningDepthPending.agentId === selectedAgent.id
      ? reasoningDepthPending.depth
      : selectedAgent?.reasoningDepth) || "FAST";

  async function handleSetReasoningDepth(depth: string) {
    // Belt-and-suspenders: the buttons below are already disabled+locked-
    // tooltipped when `locked`, same as every other post-creation control
    // in this file (model/voice/video/TTS provider, fallback, cost
    // routing) - this guard just keeps the handler itself honest if it's
    // ever reachable another way (e.g. a future keyboard shortcut).
    if (!selectedAgent || reasoningDepthBusy || locked || depth === reasoningDepth) return;
    setReasoningDepthBusy(true);
    setError(null);
    setSuccess(null);
    // Optimistic: show the new depth immediately; revert on failure.
    setReasoningDepthPending({ agentId: selectedAgent.id, depth });
    try {
      await api.setAgentReasoningDepth(selectedAgent.id, depth);
      setSuccess(t("reasoningDepthUpdatedSuccess"));
      onChanged();
    } catch (err) {
      setReasoningDepthPending(null);
      setError(errorMessage(err, t("reasoningDepthUpdateError")));
    } finally {
      setReasoningDepthBusy(false);
    }
  }

  // #815 Governance follow-up - the autonomy tier picker. The endpoint
  // (PATCH /api/agents/{id}/autonomy-tier) and api.setAgentAutonomyTier have
  // existed since that work landed; nothing in the console called either, so
  // the one governance control that actually changes how an agent behaves -
  // its tool-call ceiling, and whether every tool call needs human sign-off -
  // could only be set by curl. Same optimistic live-patch shape as
  // handleSetReasoningDepth above, including the agent-id-keyed override so a
  // stale in-flight value cannot bleed onto a different agent.
  const [autonomyTierBusy, setAutonomyTierBusy] = useState(false);

  const [autonomyTierPending, setAutonomyTierPending] = useState<{ agentId: string; tier: string } | null>(null);

  const autonomyTier =
    (autonomyTierPending && selectedAgent && autonomyTierPending.agentId === selectedAgent.id
      ? autonomyTierPending.tier
      : selectedAgent?.autonomyTier) || "SEMI_AUTONOMOUS";

  async function handleSetAutonomyTier(tier: string) {
    if (!selectedAgent || autonomyTierBusy || locked || tier === autonomyTier) return;
    setAutonomyTierBusy(true);
    setError(null);
    setSuccess(null);
    setAutonomyTierPending({ agentId: selectedAgent.id, tier });
    try {
      await api.setAgentAutonomyTier(selectedAgent.id, tier);
      setSuccess("Autonomy updated.");
      onChanged();
    } catch (err) {
      setAutonomyTierPending(null);
      setError(errorMessage(err, "Could not change this agent's autonomy."));
    } finally {
      setAutonomyTierBusy(false);
    }
  }

  const [trifectaRefreshKey, setTrifectaRefreshKey] = useState(0);

  // The 400 a block-mode save is rejected with (lethal-trifecta policy), keyed by agent so it can never show on another one.
  const [trifectaBlock, setTrifectaBlock] = useState<{ agentId: string; message: string } | null>(null);

  async function toggleTool(toolName: string) {
    if (!selectedAgent || toolBusyName) return;
    const current = new Set(selectedAgent.allowedToolNames || []);
    if (current.has(toolName)) current.delete(toolName);
    else current.add(toolName);
    setToolBusyName(toolName);
    setError(null);
    setSuccess(null);
    setTrifectaBlock(null);
    try {
      await api.setAgentToolAccess(selectedAgent.id, Array.from(current));
      setTrifectaRefreshKey((k) => k + 1);
      onChanged();
    } catch (err) {
      if (isTrifectaBlockError(err)) {
        setTrifectaBlock({ agentId: selectedAgent.id, message: errorMessage(err) });
      } else {
        setError(errorMessage(err, t("updateToolAccessError")));
      }
    } finally {
      setToolBusyName(null);
    }
  }

  function toggleKnowledgeDraft(id: string) {
    setKnowledgeDraft((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function saveKnowledge() {
    if (!selectedAgent || knowledgeBusy) return;
    setKnowledgeBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await api.setAgentKnowledgeAccess(selectedAgent.id, Array.from(knowledgeDraft));
      setSuccess(t("knowledgeAccessUpdated"));
      setEditingKnowledge(false);
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("updateKnowledgeAccessError")));
    } finally {
      setKnowledgeBusy(false);
    }
  }

  // The four per-provider toggle functions (toggleCodeWorkspace/toggleE2b/
  // toggleXaiServerTools/toggleSarvam) were retired with their dedicated
  // endpoints - capability on/off now goes through the generic
  // AgentCapabilities section (PATCH /api/agents/{id}/capabilities/{key}).

  function toggleCodeIntelRepoDraft(repoId: string) {
    setCodeIntelRepoDraft((prev) => {
      const next = new Set(prev);
      if (next.has(repoId)) next.delete(repoId);
      else next.add(repoId);
      return next;
    });
  }

  async function saveCodeIntelRepoAccess() {
    // Belt-and-suspenders alongside the Button's own disabled={locked} above -
    // this agent's edit window has closed (EditLockPolicy via
    // selectedAgent.editable), so this call would 403 anyway; the button
    // being disabled with editLockedTooltip is what actually informs the
    // tenant, not this silent no-op.
    if (!selectedAgent || selectedAgent.editable === false || codeIntelReposBusy) return;
    setCodeIntelReposBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await api.setAgentCodeIntelRepoAccess(selectedAgent.id, Array.from(codeIntelRepoDraft));
      setSuccess(t("codeIntelRepoAccessUpdated"));
      setEditingCodeIntelRepos(false);
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("updateCodeIntelRepoAccessError")));
    } finally {
      setCodeIntelReposBusy(false);
    }
  }

  return {
    reasoningDepth,
    autonomyTier,
    tuningBusy,
    effectiveMode,
    toggleTuningMode,
    genDirty,
    saveGen,
    reasoningDepthBusy,
    handleSetReasoningDepth,
    autonomyTierBusy,
    handleSetAutonomyTier,
    toggleKnowledgeDraft,
    saveKnowledge,
    toggleMode,
    trifectaRefreshKey,
    trifectaBlock,
    toggleTool,
    toggleCodeIntelRepoDraft,
    saveCodeIntelRepoAccess,
  };
}
