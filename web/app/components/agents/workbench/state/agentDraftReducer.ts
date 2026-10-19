import type { ProviderModelInfo } from "../../../../lib/api";

/**
 * One reducer for the new-agent draft form. It replaces the two dozen
 * independent useState calls the workbench used to keep for the draft, so a
 * "start a new draft" reset, a template apply and a save are each ONE
 * transition instead of a hand-ordered run of setters.
 *
 * The explicit save status (clean / dirty / saving / error) is derived from
 * the state by {@link draftStatus}; nothing stores it twice.
 */

export type NullableString = string | null;

/** The editable fields of a not-yet-created agent. */
export interface DraftFields {
  name: string;
  systemPrompt: string;
  providerName: string;
  model: string;
  voiceModelProvider: NullableString;
  voiceModel: NullableString;
  videoModelProvider: NullableString;
  videoModel: NullableString;
  ttsProvider: NullableString;
  ttsModel: NullableString;
  knowledgeBaseEnabled: boolean;
  maxTokens: number;
  temperature: number;
  topP: number;
  /** SIMPLE | ADVANCED - tool/knowledge auto-attachment axis. */
  newAgentMode: string;
  /** SIMPLE | MANUAL - temperature/top-p/max-tokens axis. */
  newAgentTuningMode: string;
  activeTemplateKey: NullableString;
}

/** The provider-scoped model picker for the draft's model field. */
export interface DraftModelPicker {
  catalog: ProviderModelInfo[];
  loading: boolean;
  useCustom: boolean;
  /** true = the catalog fetch itself failed (vs a provider with no catalog). */
  catalogError: boolean;
}

export interface AgentDraftState {
  fields: DraftFields;
  /** Snapshot taken at the last reset; "dirty" means fields differ from it. */
  baseline: DraftFields;
  picker: DraftModelPicker;
  saving: boolean;
  error: NullableString;
  success: NullableString;
}

export type DraftStatus = "clean" | "dirty" | "saving" | "error";

export const DEFAULT_SYSTEM_PROMPT = "You are a concise, helpful assistant.";

export function initialDraftFields(providerName?: string | null): DraftFields {
  return {
    name: "",
    systemPrompt: DEFAULT_SYSTEM_PROMPT,
    providerName: providerName || "ANTHROPIC",
    model: "",
    voiceModelProvider: null,
    voiceModel: null,
    videoModelProvider: null,
    videoModel: null,
    ttsProvider: null,
    ttsModel: null,
    knowledgeBaseEnabled: false,
    maxTokens: 1024,
    temperature: 0.7,
    topP: 1.0,
    newAgentMode: "SIMPLE",
    newAgentTuningMode: "MANUAL",
    activeTemplateKey: null,
  };
}

export const EMPTY_PICKER: DraftModelPicker = { catalog: [], loading: false, useCustom: false, catalogError: false };

export function initialDraftState(providerName?: string | null): AgentDraftState {
  const fields = initialDraftFields(providerName);
  return { fields, baseline: fields, picker: EMPTY_PICKER, saving: false, error: null, success: null };
}

type Updater<T> = T | ((current: T) => T);

export type AgentDraftAction =
  | { type: "field"; key: keyof DraftFields; value: Updater<DraftFields[keyof DraftFields]> }
  | { type: "picker"; key: keyof DraftModelPicker; value: Updater<DraftModelPicker[keyof DraftModelPicker]> }
  | { type: "saving"; value: boolean }
  | { type: "error"; value: NullableString }
  | { type: "success"; value: NullableString }
  /** Start a fresh draft: fields, picker choices, error and success return to the baseline. */
  | { type: "reset"; providerName?: string | null };

function resolve<T>(value: Updater<T>, current: T): T {
  return typeof value === "function" ? (value as (c: T) => T)(current) : value;
}

export function agentDraftReducer(state: AgentDraftState, action: AgentDraftAction): AgentDraftState {
  switch (action.type) {
    case "field": {
      const current = state.fields[action.key];
      const next = resolve(action.value, current);
      if (Object.is(next, current)) return state;
      return { ...state, fields: { ...state.fields, [action.key]: next } };
    }
    case "picker": {
      const current = state.picker[action.key];
      const next = resolve(action.value, current);
      if (Object.is(next, current)) return state;
      return { ...state, picker: { ...state.picker, [action.key]: next } };
    }
    case "saving":
      return state.saving === action.value ? state : { ...state, saving: action.value };
    case "error":
      return state.error === action.value ? state : { ...state, error: action.value };
    case "success":
      return state.success === action.value ? state : { ...state, success: action.value };
    case "reset": {
      // An in-flight create stays "saving" (its finally clears it), and the
      // picker's loading/catalogError belong to the provider fetch effect,
      // which owns them: only the draft's own choices go back to blank.
      const fresh = initialDraftState(action.providerName);
      return {
        ...fresh,
        picker: { ...fresh.picker, loading: state.picker.loading, catalogError: state.picker.catalogError },
        saving: state.saving,
      };
    }
    default:
      return state;
  }
}

function sameFields(a: DraftFields, b: DraftFields): boolean {
  return (Object.keys(a) as (keyof DraftFields)[]).every((k) => Object.is(a[k], b[k]));
}

/** clean: untouched; dirty: edited since the last reset; saving: a create call is in flight; error: the last attempt failed. */
export function draftStatus(state: AgentDraftState): DraftStatus {
  if (state.saving) return "saving";
  if (state.error) return "error";
  return sameFields(state.fields, state.baseline) ? "clean" : "dirty";
}
