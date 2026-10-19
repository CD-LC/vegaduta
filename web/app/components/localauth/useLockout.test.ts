import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useLockout } from "./useLockout";

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

describe("useLockout", () => {
  it("counts down once a second to zero", () => {
    const { result } = renderHook(() => useLockout());
    expect(result.current.remaining).toBe(0);
    act(() => result.current.start(3));
    expect(result.current.remaining).toBe(3);
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current.remaining).toBe(2);
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(result.current.remaining).toBe(0);
  });

  it("rounds a fractional wait up and never starts below one second", () => {
    const { result } = renderHook(() => useLockout());
    act(() => result.current.start(0.2));
    expect(result.current.remaining).toBe(1);
    act(() => result.current.start(2.1));
    expect(result.current.remaining).toBe(3);
  });

  it("clear ends it at once", () => {
    const { result } = renderHook(() => useLockout());
    act(() => result.current.start(30));
    act(() => result.current.clear());
    expect(result.current.remaining).toBe(0);
  });
});
