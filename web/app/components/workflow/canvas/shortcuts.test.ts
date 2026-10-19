import { describe, expect, it, vi } from "vitest";
import { handleCanvasKey, type CanvasShortcutActions } from "./shortcuts";

function actions(): CanvasShortcutActions {
  return {
    undo: vi.fn(),
    redo: vi.fn(),
    copySelected: vi.fn(),
    pasteCopied: vi.fn(),
    duplicateSelected: vi.fn(),
    tidyLayout: vi.fn(),
    fitToScreen: vi.fn(),
  };
}
const key = (init: KeyboardEventInit) => new KeyboardEvent("keydown", { cancelable: true, ...init });

describe("handleCanvasKey", () => {
  it("dispatches undo and redo", () => {
    const a = actions();
    expect(handleCanvasKey(key({ key: "z", ctrlKey: true }), a, false)).toBe(true);
    expect(a.undo).toHaveBeenCalled();
    handleCanvasKey(key({ key: "z", ctrlKey: true, shiftKey: true }), a, false);
    handleCanvasKey(key({ key: "y", metaKey: true }), a, false);
    expect(a.redo).toHaveBeenCalledTimes(2);
  });

  it("dispatches copy, paste, duplicate, tidy, fit", () => {
    const a = actions();
    handleCanvasKey(key({ key: "c", ctrlKey: true }), a, false);
    handleCanvasKey(key({ key: "v", ctrlKey: true }), a, false);
    handleCanvasKey(key({ key: "d", ctrlKey: true }), a, false);
    handleCanvasKey(key({ key: "L", shiftKey: true }), a, false);
    handleCanvasKey(key({ key: "!", code: "Digit1", shiftKey: true }), a, false);
    expect(a.copySelected).toHaveBeenCalled();
    expect(a.pasteCopied).toHaveBeenCalled();
    expect(a.duplicateSelected).toHaveBeenCalled();
    expect(a.tidyLayout).toHaveBeenCalled();
    expect(a.fitToScreen).toHaveBeenCalled();
  });

  it("is read-only while observing except fit", () => {
    const a = actions();
    expect(handleCanvasKey(key({ key: "z", ctrlKey: true }), a, true)).toBe(false);
    expect(a.undo).not.toHaveBeenCalled();
    handleCanvasKey(key({ key: "!", code: "Digit1", shiftKey: true }), a, true);
    expect(a.fitToScreen).toHaveBeenCalled();
  });
});
