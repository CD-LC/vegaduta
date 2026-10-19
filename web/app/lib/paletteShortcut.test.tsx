// Prod signed-in pass 2026-10-05, bug 6: Ctrl+K on the workflow canvas opened
// the global "Jump to..." palette AND the canvas "Add a node" palette, Esc
// closed only one, and a stray click then added a node.

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { claimPaletteShortcut, isPaletteShortcut, listenGlobalPaletteShortcut } from "./paletteShortcut";
import WorkflowCommandPalette from "../components/workflow/WorkflowCommandPalette";

vi.mock("../components/workflow/useNodeDefTranslations", () => ({
  useNodeDefTranslations: () => (key: string) => key,
}));

function pressCtrlK(target: EventTarget = document.body, init: KeyboardEventInit = {}) {
  const e = new KeyboardEvent("keydown", { key: "k", ctrlKey: true, bubbles: true, cancelable: true, ...init });
  target.dispatchEvent(e);
  return e;
}

describe("paletteShortcut - one chord opens one palette", () => {
  const releases: Array<() => void> = [];
  afterEach(() => {
    releases.splice(0).forEach((r) => r());
    cleanup();
  });

  it("recognises Ctrl+K and Cmd+K in either case, and nothing else", () => {
    expect(isPaletteShortcut({ key: "k", ctrlKey: true, metaKey: false })).toBe(true);
    expect(isPaletteShortcut({ key: "K", ctrlKey: false, metaKey: true })).toBe(true);
    expect(isPaletteShortcut({ key: "k", ctrlKey: false, metaKey: false })).toBe(false);
    expect(isPaletteShortcut({ key: "j", ctrlKey: true, metaKey: false })).toBe(false);
  });

  it("the canvas claim wins: the global palette does not open too", () => {
    const canvas = vi.fn();
    const global = vi.fn();
    // Registration order mirrors the app: child (canvas) effects run before
    // the parent (AppShell) effect - and the result must not depend on it.
    releases.push(claimPaletteShortcut(canvas));
    releases.push(listenGlobalPaletteShortcut(global));

    const e = pressCtrlK();

    expect(canvas).toHaveBeenCalledTimes(1);
    expect(global).not.toHaveBeenCalled();
    expect(e.defaultPrevented).toBe(true);
  });

  it("is order-independent (global registered first)", () => {
    const canvas = vi.fn();
    const global = vi.fn();
    releases.push(listenGlobalPaletteShortcut(global));
    releases.push(claimPaletteShortcut(canvas));
    pressCtrlK();
    expect(canvas).toHaveBeenCalledTimes(1);
    expect(global).not.toHaveBeenCalled();
  });

  it("off the canvas (claim released) the global palette opens", () => {
    const canvas = vi.fn();
    const global = vi.fn();
    const release = claimPaletteShortcut(canvas);
    releases.push(listenGlobalPaletteShortcut(global));
    release();
    pressCtrlK();
    expect(canvas).not.toHaveBeenCalled();
    expect(global).toHaveBeenCalledTimes(1);
  });

  it("a claim that stands down (read-only canvas) lets the global palette open", () => {
    const canvas = vi.fn();
    const global = vi.fn();
    releases.push(claimPaletteShortcut(canvas, () => false));
    releases.push(listenGlobalPaletteShortcut(global));
    pressCtrlK();
    expect(canvas).not.toHaveBeenCalled();
    expect(global).toHaveBeenCalledTimes(1);
  });

  it("the global listener ignores a keystroke something else already handled", () => {
    const global = vi.fn();
    releases.push(listenGlobalPaletteShortcut(global));
    const swallow = (e: Event) => e.preventDefault();
    document.body.addEventListener("keydown", swallow);
    pressCtrlK();
    document.body.removeEventListener("keydown", swallow);
    expect(global).not.toHaveBeenCalled();
  });
});

describe("WorkflowCommandPalette - Escape closes it wherever focus is, and nothing is added", () => {
  afterEach(cleanup);
  // jsdom has no layout, so no scrollIntoView.
  Element.prototype.scrollIntoView = vi.fn();

  it("closes on Escape pressed outside the dialog without picking a node", () => {
    const onClose = vi.fn();
    const onPick = vi.fn();
    render(<WorkflowCommandPalette open onClose={onClose} onPick={onPick} />);
    expect(screen.getByRole("dialog")).toBeTruthy();

    // Focus is somewhere else on the page (the bug: another palette held it).
    fireEvent.keyDown(document.body, { key: "Escape" });

    expect(onClose).toHaveBeenCalled();
    expect(onPick).not.toHaveBeenCalled();
  });

  it("clicking the scrim closes it without adding a node", () => {
    const onClose = vi.fn();
    const onPick = vi.fn();
    render(<WorkflowCommandPalette open onClose={onClose} onPick={onPick} />);
    const scrim = screen.getByRole("dialog").parentElement as HTMLElement;
    fireEvent.click(scrim);
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onPick).not.toHaveBeenCalled();
  });
});
