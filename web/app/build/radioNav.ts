import type { KeyboardEvent } from "react";

/** Roving-tabindex arrow-key handling for a role="radiogroup" container.
 * Put `onKeyDown={radioGroupKeyDown}` on the group and give each radio
 * `tabIndex={checked ? 0 : -1}`. Arrow keys move focus AND selection (the
 * WAI-ARIA radio pattern); Home/End jump to the ends. */
export function radioGroupKeyDown(e: KeyboardEvent<HTMLElement>): void {
  const key = e.key;
  if (!["ArrowRight", "ArrowDown", "ArrowLeft", "ArrowUp", "Home", "End"].includes(key)) return;
  const radios = Array.from(e.currentTarget.querySelectorAll<HTMLElement>('[role="radio"]:not([disabled])'));
  const at = radios.findIndex((r) => r === document.activeElement || r.contains(document.activeElement));
  if (at < 0 || radios.length === 0) return;
  e.preventDefault();
  const last = radios.length - 1;
  const next = key === "Home" ? 0 : key === "End" ? last : key === "ArrowRight" || key === "ArrowDown" ? (at >= last ? 0 : at + 1) : at <= 0 ? last : at - 1;
  radios[next].focus();
  radios[next].click();
}
