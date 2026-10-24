// "Take me there" for the agent checklist (2026-09-19).
//
// The workbench's sections are independent, UNCONTROLLED Radix accordions
// (CollapsibleSection), most of them collapsed by default - so "scroll to the
// knowledge controls" is not enough, the section has to be opened too, and
// there is no state to set from outside. This does it through the DOM the way
// a person would: find the section by id, click its closed header trigger,
// scroll it into view, focus the field, and flash an outline so the eye lands
// on it.
//
// Fail-proof by construction: every step is optional and guarded. A missing
// anchor (section not rendered for this plan/agent, id renamed) returns false
// and the caller shows words instead - a checklist button must never be a
// silent no-op.

const FLASH_MS = 1800;
const flashTimers = new WeakMap<HTMLElement, ReturnType<typeof setTimeout>>();

function prefersReducedMotion(): boolean {
  try {
    return typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  } catch {
    return false;
  }
}

function flash(el: HTMLElement) {
  try {
    const previous = flashTimers.get(el);
    if (previous) clearTimeout(previous);
    // Remember the author's own outline exactly once, so a second flash
    // during the first cannot "restore" our outline as if it were theirs.
    if (el.dataset.guideOutline === undefined) {
      el.dataset.guideOutline = el.style.outline || "";
      el.dataset.guideOutlineOffset = el.style.outlineOffset || "";
    }
    el.style.outline = "2px solid var(--primary)";
    el.style.outlineOffset = "3px";
    flashTimers.set(
      el,
      setTimeout(() => {
        el.style.outline = el.dataset.guideOutline || "";
        el.style.outlineOffset = el.dataset.guideOutlineOffset || "";
        delete el.dataset.guideOutline;
        delete el.dataset.guideOutlineOffset;
        flashTimers.delete(el);
      }, FLASH_MS)
    );
  } catch {
    // Cosmetic only.
  }
}

/**
 * Opens (if collapsed), scrolls to, focuses and highlights the control with
 * this id. Returns false when the control is not on the page.
 */
export function goToGuideAnchor(id: string): boolean {
  if (typeof document === "undefined") return false;
  let el: HTMLElement | null = null;
  try {
    el = document.getElementById(id);
  } catch {
    el = null;
  }
  if (!el) return false;

  try {
    // A section on a tab that is not showing (the workbench's Setup / Behavior tabs hide their panels): show the
    // panel's own tab first, otherwise the scroll and focus below would land on something with no layout.
    const hiddenPanel = el.closest('[role="tabpanel"][hidden]');
    const tabId = hiddenPanel?.getAttribute("aria-labelledby");
    if (tabId) document.getElementById(tabId)?.click();
  } catch {
    // Without the tab switch the section stays out of sight; the caller's words cover that.
  }

  try {
    // CollapsibleSection marks its ONE header trigger with data-guide-trigger.
    // The query is deliberately that narrow: this also runs when the section
    // is already open, and a looser "any closed Radix trigger" selector would
    // then click whatever closed popover/dialog/accordion trigger happened to
    // be nested in the section. Anchors that are not collapsible sections
    // (name input, playground pane) carry no marked trigger - nothing clicked.
    const trigger = el.querySelector<HTMLElement>("[data-guide-trigger]");
    if (trigger && trigger.getAttribute("data-state") === "closed") trigger.click();
  } catch {
    // Section stays closed; scrolling to its header is still useful.
  }

  try {
    el.scrollIntoView({ behavior: prefersReducedMotion() ? "auto" : "smooth", block: "center" });
  } catch {
    try {
      el.scrollIntoView();
    } catch {
      // Very old engine - the flash below still marks the spot.
    }
  }

  // Focus after the accordion has mounted its content.
  const target = el;
  setTimeout(() => {
    try {
      // A field marked data-guide-focus wins: header actions (e.g. the Ask-AI
      // helper's own textarea) come before the section body in DOM order, so
      // "first textarea" can be the wrong box. Unmarked sections fall back to
      // the first enabled text field.
      const focusable = target.matches("input, textarea")
        ? target
        : (target.querySelector<HTMLElement>("[data-guide-focus]:not([disabled])") ??
          target.querySelector<HTMLElement>("textarea:not([disabled]), input:not([disabled]):not([type=checkbox])"));
      focusable?.focus({ preventScroll: true });
    } catch {
      // Focus is a nicety.
    }
  }, 60);

  flash(el);
  return true;
}
