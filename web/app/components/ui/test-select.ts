// Test helpers for the ui/select combobox (a button + listbox, not a native <select>).
import { fireEvent, screen, within } from "@testing-library/react";

/** Open the combobox with the given accessible name and click the option with the given visible label. */
export function pickOption(comboboxName: string | RegExp, optionLabel: string | RegExp): void {
  fireEvent.click(screen.getByRole("combobox", { name: comboboxName }));
  const list = screen.getByRole("listbox");
  fireEvent.click(within(list).getByRole("option", { name: optionLabel }));
}

/** The visible label of the combobox's current value. */
export function selectedLabel(comboboxName: string | RegExp): string {
  return screen.getByRole("combobox", { name: comboboxName }).textContent ?? "";
}

/** The visible labels of every option (opens, reads, closes). */
export function optionLabels(comboboxName: string | RegExp): string[] {
  const box = screen.getByRole("combobox", { name: comboboxName });
  fireEvent.click(box);
  const labels = within(screen.getByRole("listbox")).getAllByRole("option").map((o) => o.textContent ?? "");
  fireEvent.click(box);
  return labels;
}
