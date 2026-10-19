import { useCallback, useMemo, useReducer } from "react";
import {
  agentDraftReducer,
  draftStatus,
  initialDraftState,
  type DraftFields,
  type DraftModelPicker,
  type NullableString,
} from "./agentDraftReducer";

type Setter<T> = (value: T | ((current: T) => T)) => void;

function useFieldSetter<K extends keyof DraftFields>(
  dispatch: (a: Parameters<typeof agentDraftReducer>[1]) => void,
  key: K
): Setter<DraftFields[K]> {
  return useCallback(
    (value: DraftFields[K] | ((current: DraftFields[K]) => DraftFields[K])) =>
      dispatch({ type: "field", key, value: value as never }),
    [dispatch, key]
  );
}

function usePickerSetter<K extends keyof DraftModelPicker>(
  dispatch: (a: Parameters<typeof agentDraftReducer>[1]) => void,
  key: K
): Setter<DraftModelPicker[K]> {
  return useCallback(
    (value: DraftModelPicker[K] | ((current: DraftModelPicker[K]) => DraftModelPicker[K])) =>
      dispatch({ type: "picker", key, value: value as never }),
    [dispatch, key]
  );
}

/**
 * The draft form as one reducer, exposed under the same value/setter names the
 * workbench always used (name/setName, model/setModel, busy/setBusy ...) so
 * handlers read as before while every write goes through agentDraftReducer.
 */
export function useAgentDraftState(defaultProviderName?: string | null) {
  const [state, dispatch] = useReducer(agentDraftReducer, defaultProviderName, initialDraftState);
  const { fields, picker } = state;

  const setName = useFieldSetter(dispatch, "name");
  const setSystemPrompt = useFieldSetter(dispatch, "systemPrompt");
  const setProviderName = useFieldSetter(dispatch, "providerName");
  const setModel = useFieldSetter(dispatch, "model");
  const setVoiceModelProvider = useFieldSetter(dispatch, "voiceModelProvider");
  const setVoiceModel = useFieldSetter(dispatch, "voiceModel");
  const setVideoModelProvider = useFieldSetter(dispatch, "videoModelProvider");
  const setVideoModel = useFieldSetter(dispatch, "videoModel");
  const setTtsProvider = useFieldSetter(dispatch, "ttsProvider");
  const setTtsModel = useFieldSetter(dispatch, "ttsModel");
  const setKnowledgeBaseEnabled = useFieldSetter(dispatch, "knowledgeBaseEnabled");
  const setMaxTokens = useFieldSetter(dispatch, "maxTokens");
  const setTemperature = useFieldSetter(dispatch, "temperature");
  const setTopP = useFieldSetter(dispatch, "topP");
  const setNewAgentMode = useFieldSetter(dispatch, "newAgentMode");
  const setNewAgentTuningMode = useFieldSetter(dispatch, "newAgentTuningMode");
  const setActiveTemplateKey = useFieldSetter(dispatch, "activeTemplateKey");

  const setDraftModelCatalog = usePickerSetter(dispatch, "catalog");
  const setDraftModelLoading = usePickerSetter(dispatch, "loading");
  const setDraftModelUseCustom = usePickerSetter(dispatch, "useCustom");
  const setDraftModelCatalogError = usePickerSetter(dispatch, "catalogError");

  const setBusy = useCallback((value: boolean) => dispatch({ type: "saving", value }), []);
  const setError = useCallback((value: NullableString) => dispatch({ type: "error", value }), []);
  const setSuccess = useCallback((value: NullableString) => dispatch({ type: "success", value }), []);
  /** Back to a blank draft (fields, model picker, error and success). */
  const resetDraft = useCallback((providerName?: string | null) => dispatch({ type: "reset", providerName }), []);
  const status = useMemo(() => draftStatus(state), [state]);

  return {
    ...fields,
    draftModelCatalog: picker.catalog,
    draftModelLoading: picker.loading,
    draftModelUseCustom: picker.useCustom,
    draftModelCatalogError: picker.catalogError,
    busy: state.saving,
    error: state.error,
    success: state.success,
    draftStatus: status,
    setName,
    setSystemPrompt,
    setProviderName,
    setModel,
    setVoiceModelProvider,
    setVoiceModel,
    setVideoModelProvider,
    setVideoModel,
    setTtsProvider,
    setTtsModel,
    setKnowledgeBaseEnabled,
    setMaxTokens,
    setTemperature,
    setTopP,
    setNewAgentMode,
    setNewAgentTuningMode,
    setActiveTemplateKey,
    setDraftModelCatalog,
    setDraftModelLoading,
    setDraftModelUseCustom,
    setDraftModelCatalogError,
    setBusy,
    setError,
    setSuccess,
    resetDraft,
  };
}
