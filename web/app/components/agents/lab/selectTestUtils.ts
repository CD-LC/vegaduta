// Test helpers for the components/ui Select (a combobox button + listbox), which
// replaced the native <select> in the lab panels.
import { fireEvent, screen } from "@testing-library/react";

/** Opens the select (when closed) and returns its option elements. */
export function optionsOf(trigger: HTMLElement): HTMLElement[] {
  if (trigger.getAttribute("aria-expanded") !== "true") fireEvent.click(trigger);
  return screen.getAllByRole("option");
}

/** Index of the currently selected option. */
export function selectedIndex(trigger: HTMLElement): number {
  return optionsOf(trigger).findIndex((o) => o.getAttribute("aria-selected") === "true");
}

/** Picks the option at `index`; the list closes. */
export function choose(trigger: HTMLElement, index: number): void {
  fireEvent.click(optionsOf(trigger)[index]);
}

/** Picks the option whose label is exactly `label`. */
export function chooseLabel(trigger: HTMLElement, label: string): void {
  const option = optionsOf(trigger).find((o) => o.textContent === label);
  if (!option) throw new Error(`no option labelled ${label}`);
  fireEvent.click(option);
}
