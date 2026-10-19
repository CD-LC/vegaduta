// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { visibleInterval } from "./visibleInterval";

function setHidden(hidden: boolean): void {
  Object.defineProperty(document, "hidden", { configurable: true, get: () => hidden });
}

describe("visibleInterval", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    setHidden(false);
  });
  afterEach(() => {
    vi.useRealTimers();
    setHidden(false);
  });

  it("ticks while visible and skips ticks while hidden", () => {
    const fn = vi.fn();
    const stop = visibleInterval(fn, 1000);
    vi.advanceTimersByTime(3000);
    expect(fn).toHaveBeenCalledTimes(3);
    setHidden(true);
    vi.advanceTimersByTime(5000);
    expect(fn).toHaveBeenCalledTimes(3);
    stop();
  });

  it("fires once immediately when the tab becomes visible again", () => {
    const fn = vi.fn();
    const stop = visibleInterval(fn, 1000);
    setHidden(true);
    document.dispatchEvent(new Event("visibilitychange"));
    expect(fn).not.toHaveBeenCalled();
    setHidden(false);
    document.dispatchEvent(new Event("visibilitychange"));
    expect(fn).toHaveBeenCalledTimes(1);
    stop();
  });

  it("cleanup stops ticks and the listener", () => {
    const fn = vi.fn();
    const stop = visibleInterval(fn, 1000);
    stop();
    vi.advanceTimersByTime(5000);
    document.dispatchEvent(new Event("visibilitychange"));
    expect(fn).not.toHaveBeenCalled();
  });
});
