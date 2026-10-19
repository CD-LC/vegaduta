// Agent Lab - which elements inside a container the Tab key can actually land
// on, in DOM order. The lab's two focus traps (the overlay and the fork
// dialog) wrap between the first and last of these, so the list has to match
// what the browser does: a control inside a `hidden` panel, a disabled one,
// or one taken out of the tab order (tabindex -1, e.g. the inactive tabs of
// a tab list) is skipped by the browser and must not be counted here either.
// Counting one as "last" means Tab from the real last control is never
// caught, and focus walks out of the dialog.
const CANDIDATES = "a[href], button, textarea, input, select, [tabindex]";

function isUncheckedRadioOfCheckedGroup(el: HTMLElement, candidates: readonly HTMLElement[]): boolean {
  if (!(el instanceof HTMLInputElement) || el.type !== "radio" || el.checked || !el.name) return false;
  // Tab stops only on the checked radio of a group; the others are reached
  // with the arrow keys.
  return candidates.some(
    (other) => other instanceof HTMLInputElement && other.type === "radio" && other.checked && other.name === el.name && other.form === el.form
  );
}

export function tabbableWithin(root: HTMLElement): HTMLElement[] {
  const candidates = Array.from(root.querySelectorAll<HTMLElement>(CANDIDATES));
  return candidates.filter((el) => {
    if (el.matches(":disabled")) return false;
    const tabindex = el.getAttribute("tabindex");
    if (tabindex !== null && Number(tabindex) < 0) return false;
    if (el instanceof HTMLInputElement && el.type === "hidden") return false;
    if (el.closest("[hidden], [inert]")) return false;
    return !isUncheckedRadioOfCheckedGroup(el, candidates);
  });
}
