// Text insertion for the on-screen keyboard. Two constraints drive the shape:
//
// 1. React controlled inputs ignore plain `el.value = x` writes — React owns
//    `value` and reverts it on the next render. The fix is the native setter
//    trick used by every automation tool: call the PROTOTYPE's value setter
//    (which React did not wrap) and dispatch a bubbling `input` event; React's
//    delegated listener picks the change up exactly as if it were typed.
// 2. The board must not steal focus — callers press keys with
//    `onPointerDown={e => e.preventDefault()}`, so `document.activeElement`
//    stays the input the person was typing in.

export type KeyboardTarget = HTMLInputElement | HTMLTextAreaElement;

/** The element the board types into: the focused input/textarea, else null. */
export function keyboardTarget(): KeyboardTarget | null {
  const el = typeof document !== "undefined" ? document.activeElement : null;
  return el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement ? el : null;
}

function setNativeValue(el: KeyboardTarget, value: string, caret: number): void {
  const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(proto, "value")?.set;
  if (setter) setter.call(el, value);
  else el.value = value;
  el.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: null }));
  try {
    el.setSelectionRange(caret, caret);
  } catch {
    // Inputs whose type forbids selection (number, date) — caret just stays put.
  }
}

/** Insert `text` at the caret (replacing the selection, like typing does). */
export function insertAtCaret(el: KeyboardTarget, text: string): void {
  const start = el.selectionStart ?? el.value.length;
  const end = el.selectionEnd ?? start;
  el.focus({ preventScroll: true });
  setNativeValue(el, el.value.slice(0, start) + text + el.value.slice(end), start + text.length);
}

/** Delete one grapheme before the caret (or the selection), like Backspace. */
export function backspaceAtCaret(el: KeyboardTarget): void {
  const start = el.selectionStart ?? 0;
  const end = el.selectionEnd ?? start;
  if (start !== end) {
    el.focus({ preventScroll: true });
    setNativeValue(el, el.value.slice(0, start) + el.value.slice(end), start);
    return;
  }
  if (start === 0) return;
  // Walk back one code point, not one UTF-16 unit, so a surrogate pair
  // (most Indic conjuncts are single code points; emoji aren't) doesn't
  // leave a broken half behind.
  const before = el.value.slice(0, start);
  const chars = Array.from(before);
  chars.pop();
  const remaining = chars.join("").length;
  el.focus({ preventScroll: true });
  setNativeValue(el, el.value.slice(0, remaining) + el.value.slice(start), remaining);
}

/** Forward a functional key to the target's own handler (e.g. Enter → the
 * composer's send-on-Enter), so the board honours each input's semantics
 * instead of deciding what Enter "means" globally. Returns whether a
 * handler consumed it (preventDefault) — callers fall back to the literal
 * behaviour (a newline) when nothing did. */
export function dispatchKey(el: KeyboardTarget, key: "Enter" | "Tab"): boolean {
  const event = new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true });
  el.dispatchEvent(event);
  return event.defaultPrevented;
}
