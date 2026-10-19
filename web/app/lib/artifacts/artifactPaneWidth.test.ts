import { describe, it, expect } from "vitest";
import {
  CHAT_MIN_WIDTH,
  PANE_DEFAULT_WIDTH,
  PANE_KEY_STEP,
  PANE_MAX_WIDTH,
  PANE_MIN_WIDTH,
  clampPaneWidth,
  widthAfterDrag,
  widthAfterKey,
} from "./artifactPaneWidth";

describe("clampPaneWidth", () => {
  it("clamps to the minimum and maximum", () => {
    expect(clampPaneWidth(10, 2000)).toBe(PANE_MIN_WIDTH);
    expect(clampPaneWidth(5000, 4000)).toBe(PANE_MAX_WIDTH);
    expect(clampPaneWidth(600, 2000)).toBe(600);
  });

  it("never squeezes the chat below its minimum on a narrow viewport", () => {
    expect(clampPaneWidth(900, 1000)).toBe(1000 - CHAT_MIN_WIDTH);
  });

  it("keeps the minimum even on a very small viewport", () => {
    expect(clampPaneWidth(900, 400)).toBe(PANE_MIN_WIDTH);
  });

  it("falls back to the default for non-finite input", () => {
    expect(clampPaneWidth(Number.NaN, 2000)).toBe(PANE_DEFAULT_WIDTH);
    expect(clampPaneWidth(Number.POSITIVE_INFINITY, 2000)).toBe(PANE_DEFAULT_WIDTH);
  });
});

describe("drag and keyboard", () => {
  it("dragging left widens the right-hand pane", () => {
    expect(widthAfterDrag(500, 800, 700, 2000)).toBe(600);
    expect(widthAfterDrag(500, 800, 900, 2000)).toBe(400);
  });

  it("maps arrow and home/end keys, ignoring the rest", () => {
    expect(widthAfterKey("ArrowLeft", 500, 2000)).toBe(500 + PANE_KEY_STEP);
    expect(widthAfterKey("ArrowRight", 500, 2000)).toBe(500 - PANE_KEY_STEP);
    expect(widthAfterKey("Home", 500, 2000)).toBe(PANE_MIN_WIDTH);
    expect(widthAfterKey("End", 500, 2000)).toBe(PANE_MAX_WIDTH);
    expect(widthAfterKey("a", 500, 2000)).toBeNull();
  });
});
