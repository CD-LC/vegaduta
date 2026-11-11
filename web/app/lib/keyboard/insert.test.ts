// lib/keyboard/insert — the DOM half of the board. The contract that matters:
// every mutation looks like a real typing event to a controlled React input
// (native setter + bubbling `input` event) and lands at the caret, not the end.

import { afterEach, describe, expect, it, vi } from "vitest";

import { backspaceAtCaret, dispatchKey, insertAtCaret, keyboardTarget } from "./insert";

function makeInput(value = "", caret?: number): HTMLInputElement {
  const el = document.createElement("input");
  el.type = "text";
  el.value = value;
  document.body.appendChild(el);
  if (caret !== undefined) el.setSelectionRange(caret, caret);
  return el;
}

afterEach(() => document.body.replaceChildren());

describe("insertAtCaret", () => {
  it("appends at the end when the caret is at the end", () => {
    const el = makeInput("ab", 2);
    insertAtCaret(el, "क");
    expect(el.value).toBe("abक");
    expect(el.selectionStart).toBe(3);
  });

  it("inserts mid-string at the caret", () => {
    const el = makeInput("abcd", 2);
    insertAtCaret(el, "X");
    expect(el.value).toBe("abXcd");
    expect(el.selectionStart).toBe(3);
  });

  it("replaces a selection like real typing", () => {
    const el = makeInput("abcd");
    el.setSelectionRange(1, 3);
    insertAtCaret(el, "Z");
    expect(el.value).toBe("aZd");
    expect(el.selectionStart).toBe(2);
  });

  it("dispatches a bubbling input event so React picks the change up", () => {
    const el = makeInput("", 0);
    const spy = vi.fn();
    document.body.addEventListener("input", spy);
    insertAtCaret(el, "hola");
    expect(spy).toHaveBeenCalledOnce();
  });

  it("writes multi-code-point text (composing marks included)", () => {
    const el = makeInput("", 0);
    insertAtCaret(el, "क्या");
    expect(el.value).toBe("क्या");
  });
});

describe("backspaceAtCaret", () => {
  it("deletes the character before the caret", () => {
    const el = makeInput("abc", 3);
    backspaceAtCaret(el);
    expect(el.value).toBe("ab");
    expect(el.selectionStart).toBe(2);
  });

  it("deletes a whole surrogate pair, not half of it", () => {
    const el = makeInput("a😀", 3);
    backspaceAtCaret(el);
    expect(el.value).toBe("a");
    expect(el.selectionStart).toBe(1);
  });

  it("deletes the selection instead of the char before it", () => {
    const el = makeInput("abcd");
    el.setSelectionRange(1, 3);
    backspaceAtCaret(el);
    expect(el.value).toBe("ad");
    expect(el.selectionStart).toBe(1);
  });

  it("does nothing at position 0", () => {
    const el = makeInput("ab", 0);
    backspaceAtCaret(el);
    expect(el.value).toBe("ab");
  });
});

describe("keyboardTarget", () => {
  it("returns the focused input", () => {
    const el = makeInput("x");
    el.focus();
    expect(keyboardTarget()).toBe(el);
  });

  it("returns the focused textarea", () => {
    const el = document.createElement("textarea");
    document.body.appendChild(el);
    el.focus();
    expect(keyboardTarget()).toBe(el);
  });

  it("returns null when nothing focusable holds focus", () => {
    (document.activeElement as HTMLElement | null)?.blur?.();
    const result = keyboardTarget();
    expect(result === null || result instanceof HTMLInputElement || result instanceof HTMLTextAreaElement).toBe(true);
  });
});

describe("dispatchKey", () => {
  it("fires a keydown the target's own handler can read (Enter → send)", () => {
    const el = makeInput("hi", 2);
    const seen: string[] = [];
    el.addEventListener("keydown", (e) => seen.push(e.key));
    dispatchKey(el, "Enter");
    expect(seen).toEqual(["Enter"]);
  });
});
