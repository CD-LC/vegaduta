"use client";

import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import {
  describeCapabilityOutcome,
  enableQuietTemplateCapabilities,
  enableTemplateCapabilities,
  fileAnalysisUnavailableNote,
  templateCapabilityKeys,
  templateWantsFileAnalysis,
} from "../templateCapabilities";
import { defaultModelFor } from "../useModalityCatalog";
import type { WorkbenchProps } from "./types";
import type { useAgentDraft } from "./useAgentDraft";
import type { useAgentValidation } from "./useAgentValidation";
import type { useWorkbenchAccess } from "./useWorkbenchAccess";
import type { useWorkbenchBase } from "./useWorkbenchBase";
import type { useWorkbenchRouting } from "./useWorkbenchRouting";
import type { useWorkbenchTenant } from "./useWorkbenchTenant";

export function useAgentSave(
  p: WorkbenchProps,
  prev: ReturnType<typeof useWorkbenchBase> &
    ReturnType<typeof useWorkbenchTenant> &
    ReturnType<typeof useAgentDraft> &
    ReturnType<typeof useWorkbenchAccess> &
    ReturnType<typeof useWorkbenchRouting> &
    ReturnType<typeof useAgentValidation>
) {
  const { onSelect, providers, onChanged } = p;
  const {
    draftDefaultAppliedRef,
    draftTouchedRef,
    setDraftEpoch,
    draftRequestIdRef,
    setActiveTemplateKey,
    setError,
    resetDraft,
    setSuccess,
    setSwitcherOpen,
    nameInputRef,
    busy,
    validate,
    explainCreateBlocker,
    name,
    systemPrompt,
    needsProvider,
    atAgentLimit,
    agentLimit,
    setBusy,
    providerName,
    model,
    maxTokens,
    temperature,
    topP,
    newAgentMode,
    knowledgeBaseEnabled,
    newAgentTuningMode,
    voiceModelProvider,
    voiceModel,
    modalityCatalog,
    videoModelProvider,
    videoModel,
    ttsProvider,
    ttsModel,
    activeTemplateKey,
    tTemplates,
    t,
  } = prev;

  // ---- /try HANDOFF READER - end ------------------------------------------

  function startDraft() {
    draftDefaultAppliedRef.current = false;
    draftTouchedRef.current = false;
    setDraftEpoch((n) => n + 1);
    draftRequestIdRef.current += 1;
    onSelect(null);
    resetDraft(providers[0]?.providerName);
    setSwitcherOpen(false);
    setTimeout(() => nameInputRef.current?.focus(), 0);
  }

  async function handleCreate() {
    if (busy) return;
    setError(null);
    setSuccess(null);
    const validationError = validate();
    if (validationError) {
      // 2026-09-19 ("users get lost creating agents"): "Name is required." in
      // a banner at the top of a very tall page named a field in jargon and
      // did not say where it was. Same block decision, but the message is in
      // a consumer's words (English; other locales keep the translated string)
      // and the page scrolls to and highlights the field.
      setError(
        explainCreateBlocker({ name, systemPrompt, needsProvider, atAgentLimit, agentLimit }, validationError).message
      );
      return;
    }
    setBusy(true);
    // WB-003: this request's identity - if the user starts a new draft or
    // switches to a different agent before this resolves, draftRequestIdRef
    // will have moved on and the stale onSelect(created.id) below is skipped.
    const requestId = (draftRequestIdRef.current += 1);
    try {
      const created = await api.createAgent({
        name: name.trim(),
        systemPrompt,
        providerName,
        model: model.trim() || undefined,
        maxTokens,
        temperature,
        topP,
        knowledgeBaseEnabled: newAgentMode === "SIMPLE" ? true : knowledgeBaseEnabled,
        experienceMode: newAgentMode,
        tuningMode: newAgentTuningMode,
        voiceModelProvider: voiceModelProvider || undefined,
        // Same defensive backfill as handleUpdateVoiceModel - see that
        // function's comment.
        voiceModel: voiceModel || defaultModelFor(modalityCatalog?.speechToText, voiceModelProvider) || undefined,
        videoModelProvider: videoModelProvider || undefined,
        videoModel: videoModel || undefined,
        ttsProvider: ttsProvider || undefined,
        // Same defensive backfill as voiceModel above.
        ttsModel: ttsModel || defaultModelFor(modalityCatalog?.textToSpeech, ttsProvider) || undefined,
      });
      // Template-declared capabilities (data-analyst -> Code execution). No
      // request at all for a template that declares none; never throws.
      // The key is captured and then cleared: the template belonged to THIS
      // draft, so a later from-scratch draft must not inherit its capability.
      const createdFromTemplateKey = activeTemplateKey;
      setActiveTemplateKey(null);
      const capabilityNote = describeCapabilityOutcome(
        await enableTemplateCapabilities(api, created.id, templateCapabilityKeys(createdFromTemplateKey)),
        tTemplates
      );
      // File-analysis templates only (one extra list read, none otherwise):
      // say so when this agent cannot take data files and the server gave no
      // reason of its own - see fileAnalysisUnavailableNote.
      const fileNote = templateWantsFileAnalysis(createdFromTemplateKey)
        ? fileAnalysisUnavailableNote(
            await api
              .listAgents()
              .then((list: any) => (Array.isArray(list) ? list.find((a: any) => a?.id === created.id) : null))
              .catch((): any => null),
            tTemplates
          )
        : null;
      setSuccess(
        [t("agentCreatedSuccess", { name: created.name }), capabilityNote, fileNote].filter(Boolean).join(" ")
      );
      onChanged();
      await enableQuietTemplateCapabilities(createdFromTemplateKey, created.id, {
        list: api.listAgentCapabilitiesQuiet,
        set: api.setAgentCapabilityQuiet,
      });
      if (draftRequestIdRef.current === requestId) {
        onSelect(created.id);
      }
    } catch (err) {
      setError(errorMessage(err, t("createAgentError")));
    } finally {
      setBusy(false);
    }
  }

  return { startDraft, handleCreate };
}
