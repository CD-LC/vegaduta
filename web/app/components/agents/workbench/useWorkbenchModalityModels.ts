"use client";

import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { defaultModelFor } from "../useModalityCatalog";
import type { WorkbenchProps } from "./types";
import type { WorkbenchModelDeps } from "./workbenchModelDeps";

/** The saved agent's speech-to-text, text-to-speech and video-to-text model editors: open and save. */
export function useWorkbenchModalityModels(p: WorkbenchProps, prev: WorkbenchModelDeps) {
  const { onChanged } = p;
  const {
    selectedAgent,
    setError,
    setSuccess,
    t,
    selectedAgentIdRef,
    setVoiceModelEditing,
    setVoiceModelDraft,
    modalityCatalog,
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
  } = prev;

  function openVoiceModelEditor() {
    if (!selectedAgent) return;
    setError(null);
    setSuccess(null);
    setVoiceModelEditing(true);
    setVoiceModelDraft({
      voiceModelProvider: selectedAgent.voiceModelProvider || null,
      // Self-heal a provider/model mismatch instead of just re-displaying it:
      // if this agent's stored voiceModelProvider is set but voiceModel is
      // somehow null (a stale row from before this pairing was enforced, or
      // any other data drift), backfill the known model for that provider
      // rather than opening the editor already in the exact broken state
      // that makes an untouched "Save" fail with "voiceModel is required
      // when voiceModelProvider is set" - 2026-08-03, direct bug report.
      voiceModel:
        selectedAgent.voiceModel ||
        defaultModelFor(modalityCatalog?.speechToText, selectedAgent.voiceModelProvider) ||
        null,
    });
  }

  function openTtsModelEditor() {
    if (!selectedAgent) return;
    setError(null);
    setSuccess(null);
    setTtsModelEditing(true);
    setTtsModelDraft({
      ttsProvider: selectedAgent.ttsProvider || null,
      // Same self-heal backfill as openVoiceModelEditor.
      ttsModel:
        selectedAgent.ttsModel || defaultModelFor(modalityCatalog?.textToSpeech, selectedAgent.ttsProvider) || null,
    });
  }

  function openVideoModelEditor() {
    if (!selectedAgent) return;
    setError(null);
    setSuccess(null);
    setVideoModelEditing(true);
    setVideoModelDraft({
      videoModelProvider: selectedAgent.videoModelProvider || null,
      videoModel: selectedAgent.videoModel || null,
    });
  }

  async function handleUpdateVoiceModel() {
    if (!selectedAgent || voiceModelBusy) return;
    setError(null);
    setSuccess(null);
    setVoiceModelBusy(true);
    const targetAgentId = selectedAgent.id;
    try {
      // Second layer of the same backfill openVoiceModelEditor already does
      // at hydration time - belt-and-braces so a provider/model mismatch can
      // never actually reach the API, regardless of how the draft state got
      // there.
      const provider = voiceModelDraft.voiceModelProvider;
      const model = voiceModelDraft.voiceModel || defaultModelFor(modalityCatalog?.speechToText, provider) || null;
      await api.updateAgentVoiceModel(selectedAgent.id, provider, model);
      setVoiceModelEditing(false);
      onChanged();
      if (targetAgentId === selectedAgentIdRef.current) {
        setSuccess(t("voiceProviderUpdatedSuccess"));
      }
    } catch (err) {
      if (targetAgentId === selectedAgentIdRef.current) {
        setError(errorMessage(err, t("updateVoiceProviderError")));
      }
    } finally {
      setVoiceModelBusy(false);
    }
  }

  async function handleUpdateTtsModel() {
    if (!selectedAgent || ttsModelBusy) return;
    setError(null);
    setSuccess(null);
    setTtsModelBusy(true);
    const targetAgentId = selectedAgent.id;
    try {
      const provider = ttsModelDraft.ttsProvider;
      const model = ttsModelDraft.ttsModel || defaultModelFor(modalityCatalog?.textToSpeech, provider) || null;
      await api.updateAgentTtsModel(selectedAgent.id, provider, model);
      setTtsModelEditing(false);
      onChanged();
      if (targetAgentId === selectedAgentIdRef.current) {
        setSuccess(t("ttsProviderUpdatedSuccess"));
      }
    } catch (err) {
      if (targetAgentId === selectedAgentIdRef.current) {
        setError(errorMessage(err, t("updateTtsProviderError")));
      }
    } finally {
      setTtsModelBusy(false);
    }
  }

  async function handleUpdateVideoModel() {
    if (!selectedAgent || videoModelBusy) return;
    setError(null);
    setSuccess(null);
    setVideoModelBusy(true);
    const targetAgentId = selectedAgent.id;
    try {
      await api.updateAgentVideoModel(selectedAgent.id, videoModelDraft.videoModelProvider, videoModelDraft.videoModel);
      setVideoModelEditing(false);
      onChanged();
      if (targetAgentId === selectedAgentIdRef.current) {
        setSuccess(t("videoProviderUpdatedSuccess"));
      }
    } catch (err) {
      if (targetAgentId === selectedAgentIdRef.current) {
        setError(errorMessage(err, t("updateVideoProviderError")));
      }
    } finally {
      setVideoModelBusy(false);
    }
  }

  return {
    openVoiceModelEditor,
    openVideoModelEditor,
    openTtsModelEditor,
    handleUpdateTtsModel,
    handleUpdateVoiceModel,
    handleUpdateVideoModel,
  };
}
