"use client";

import { agentCapabilities, agentCategory, agentState } from "../agentIdentity";
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
import type { useWorkbenchTuning } from "./useWorkbenchTuning";

export function useWorkbenchView(
  p: WorkbenchProps,
  prev: ReturnType<typeof useWorkbenchBase> &
    ReturnType<typeof useWorkbenchTenant> &
    ReturnType<typeof useAgentDraft> &
    ReturnType<typeof useWorkbenchAccess> &
    ReturnType<typeof useWorkbenchRouting> &
    ReturnType<typeof useAgentValidation> &
    ReturnType<typeof useAgentSave> &
    ReturnType<typeof useWorkbenchEditors> &
    ReturnType<typeof useWorkbenchModel> &
    ReturnType<typeof useWorkbenchTuning>
) {
  const { collections } = p;
  const {
    selectedAgent,
    name,
    t,
    isDraft,
    newAgentTuningMode,
    model,
    onPlatformLlm,
    platformLlmInfo,
    intelSource,
    routingPreview,
    maxTokens,
    temperature,
    genDraft,
    reasoningDepth,
    autonomyTier,
    newAgentMode,
    knowledgeBaseEnabled,
    voiceModelProvider,
    voiceModel,
    videoModelProvider,
    videoModel,
    ttsProvider,
    ttsModel,
    providerName,
    busy,
    genBusy,
    knowledgeBusy,
    modeBusy,
    tuningBusy,
    modelBusy,
    fallbackBusy,
    costRoutingBusy,
    voiceModelBusy,
    videoModelBusy,
    promptBusy,
    switchByokBusy,
    switchToPoolBusy,
    deleteDuplicateBusy,
    codeIntelReposBusy,
    toolBusyName,
  } = prev;

  // ---- Render -------------------------------------------------------

  const headName = selectedAgent ? selectedAgent.name : name || t("newAgent");

  const tuningMode = isDraft ? newAgentTuningMode : selectedAgent.tuningMode || "MANUAL";

  // QA-05 (2026-09-24): one-line header summaries for the collapsed-by-default
  // sections below (Device & routing, Generation tuning, Transcription,
  // Reasoning & autonomy), so a closed header still says what is set.
  const routingModelLabel = isDraft ? model : selectedAgent.model;

  const hostedRouteSummary = onPlatformLlm
    ? `Platform LLM${platformLlmInfo?.available && platformLlmInfo.model ? ` · ${platformLlmInfo.model}` : ""}`
    : `Own provider${routingModelLabel ? ` · ${routingModelLabel}` : ""}`;

  // On-device leads only when it is the selected route; tier 0/1 in the live
  // preview means a local model is ready (same test resolveProviderGate uses).
  const deviceRoutingSummary =
    intelSource === "on-device"
      ? routingPreview && (routingPreview.tier === 0 || routingPreview.tier === 1)
        ? `On-device: ready · falls back to ${hostedRouteSummary}`
        : `On-device: not set up · using ${hostedRouteSummary}`
      : hostedRouteSummary;

  // Manual shows the live sampling values only when both are real numbers -
  // a preview/mock agent without them must not read as "0 tokens".
  const tuningValues = isDraft ? { maxTokens, temperature } : genDraft;

  const tuningSummary =
    tuningMode === "SIMPLE"
      ? `${t("simpleLabel")} · auto-tuned`
      : tuningValues && typeof tuningValues.maxTokens === "number" && typeof tuningValues.temperature === "number"
        ? `${t("manualLabel")} · ${tuningValues.maxTokens} tokens · temp ${tuningValues.temperature.toFixed(2)}`
        : t("manualLabel");

  const transcriptionParts = isDraft
    ? []
    : [
        selectedAgent.voiceModelProvider ? `Voice: ${selectedAgent.voiceModelProvider}` : null,
        selectedAgent.videoModelProvider ? `Video: ${selectedAgent.videoModelProvider}` : null,
        selectedAgent.ttsProvider ? `TTS: ${selectedAgent.ttsProvider}` : null,
      ].filter(Boolean);

  const transcriptionSummary = transcriptionParts.length
    ? transcriptionParts.join(", ")
    : "Platform defaults (voice, video, TTS)";

  const autonomyLabels: Record<string, string> = {
    SUPERVISED: "Supervised",
    SEMI_AUTONOMOUS: "Semi-autonomous",
    FULLY_AUTONOMOUS: "Fully autonomous",
  };

  const cachingSummary = isDraft
    ? "Save first"
    : [
        selectedAgent.responseCachingEnabled ? "cache on" : "cache off",
        selectedAgent.shareMemoryWithTenant ? "memory shared" : "memory private",
        selectedAgent.knowledgeBaseEnabled ? "KB on" : "KB off",
      ].join(" · ");

  const behaviourSummary = isDraft
    ? ""
    : `${t(`reasoningDepth${reasoningDepth}`)} · ${autonomyLabels[autonomyTier] ?? autonomyTier}`;

  // ---- Identity + state (visual only) ---------------------------------
  // A not-yet-created agent has no AgentResponse to categorise, so mirror the
  // draft form state under the same field names the taxonomy reads. This is a
  // read-only projection for rendering — nothing here is sent anywhere.
  const draftDescriptor = {
    // A not-yet-created agent has no id; the annotation (rather than a bare
    // null, which noImplicitAny would widen to any) keeps this projection's
    // shape aligned with WorkbenchAgent.id for the taxonomy helpers below.
    id: null as string | null,
    name: headName,
    knowledgeBaseEnabled: newAgentMode === "SIMPLE" ? true : knowledgeBaseEnabled,
    voiceModelProvider,
    voiceModel,
    videoModelProvider,
    videoModel,
    ttsProvider,
    ttsModel,
    providerName,
    model,
  };

  const identityAgent = selectedAgent || draftDescriptor;

  // "Working" is the only honest running signal this screen has: a mutating
  // call is genuinely in flight. Everything else resolves from real
  // AgentResponse fields (fallback counters, server-computed `editable`).
  const mutating =
    busy ||
    genBusy ||
    knowledgeBusy ||
    modeBusy ||
    tuningBusy ||
    modelBusy ||
    fallbackBusy ||
    costRoutingBusy ||
    voiceModelBusy ||
    videoModelBusy ||
    promptBusy ||
    switchByokBusy ||
    switchToPoolBusy ||
    deleteDuplicateBusy ||
    codeIntelReposBusy ||
    toolBusyName != null;

  const headState = agentState(selectedAgent, { busy: mutating });

  const headCategory = agentCategory(identityAgent);

  const headCapabilities = agentCapabilities(selectedAgent);

  const restrictedKnowledge = selectedAgent?.allowedKnowledgeCollectionIds?.length > 0;

  const shownCollections = selectedAgent
    ? restrictedKnowledge
      ? collections.filter((c) => selectedAgent.allowedKnowledgeCollectionIds.includes(c.id))
      : collections
    : collections;

  return {
    identityAgent,
    headName,
    headState,
    mutating,
    headCategory,
    headCapabilities,
    deviceRoutingSummary,
    tuningSummary,
    tuningMode,
    transcriptionSummary,
    behaviourSummary,
    cachingSummary,
    shownCollections,
  };
}
