import type { SelectedElement } from "./runtime";
import type { Device, SidePanel } from "./sessionTypes";

/** The workspace's UI state: what is shown, tapped or waiting for a yes. Pure, so it is unit-tested. */
export interface UiState {
  device: Device;
  panel: SidePanel;
  /** Tap-to-select mode in the preview. */
  inspect: boolean;
  selected: SelectedElement | null;
  /** A whole-page request waiting on the person's "yes". */
  scopeAsk: string | null;
  /** Version the compare view opened from, or null when closed. */
  diffFrom: number | null;
}

export const initialUiState: UiState = {
  device: "phone",
  panel: "design",
  inspect: false,
  selected: null,
  scopeAsk: null,
  diffFrom: null,
};

export type Updater<T> = T | ((prev: T) => T);

export type UiAction = { [K in keyof UiState]: { type: "set"; key: K; value: Updater<UiState[K]> } }[keyof UiState] | { type: "select"; element: SelectedElement };

export function uiReducer(state: UiState, action: UiAction): UiState {
  if (action.type === "select") {
    // A tap in the preview selects the element and opens the Element panel.
    return { ...state, selected: action.element, panel: "element" };
  }
  const { key } = action;
  const prev = state[key];
  const next = typeof action.value === "function" ? (action.value as (p: typeof prev) => typeof prev)(prev) : action.value;
  return Object.is(prev, next) ? state : { ...state, [key]: next };
}
