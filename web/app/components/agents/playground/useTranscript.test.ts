import { afterEach, describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { INITIAL_VISIBLE_TURNS, LOAD_MORE_STEP, useTranscript } from "./useTranscript";
import type { PlaygroundTurn } from "./types";

afterEach(() => window.localStorage.clear());

function turns(n: number): PlaygroundTurn[] {
  return Array.from({ length: n }, (_, i) => ({ role: i % 2 ? "assistant" : "user", content: `m${i}` }) as PlaygroundTurn);
}

describe("useTranscript render window", () => {
  it("renders only the trailing window and keeps absolute indices", () => {
    const { result } = renderHook(() => useTranscript(null));
    act(() => result.current.setTurns(turns(30)));
    expect(result.current.visibleTurns).toHaveLength(INITIAL_VISIBLE_TURNS);
    expect(result.current.windowStart).toBe(30 - INITIAL_VISIBLE_TURNS);
    expect(result.current.hiddenCount).toBe(30 - INITIAL_VISIBLE_TURNS);
    expect(result.current.canCollapse).toBe(false);
  });

  it("show earlier widens the window and collapse brings it back", () => {
    const { result } = renderHook(() => useTranscript(null));
    act(() => result.current.setTurns(turns(40)));
    act(() => result.current.showEarlier());
    expect(result.current.visibleTurns).toHaveLength(INITIAL_VISIBLE_TURNS + LOAD_MORE_STEP);
    act(() => result.current.setTurns(turns(20)));
    expect(result.current.hiddenCount).toBe(0);
    expect(result.current.canCollapse).toBe(true);
    act(() => result.current.collapseEarlier());
    expect(result.current.visibleTurns).toHaveLength(INITIAL_VISIBLE_TURNS);
  });

  it("never lets the turn being read aloud fall out of the window", () => {
    const { result, rerender } = renderHook(({ speaking }) => useTranscript(speaking), {
      initialProps: { speaking: null as number | null },
    });
    act(() => result.current.setTurns(turns(40)));
    rerender({ speaking: 2 });
    expect(result.current.windowStart).toBeLessThanOrEqual(2);
  });

  it("resetWindow returns to the collapsed tail", () => {
    const { result } = renderHook(() => useTranscript(null));
    act(() => result.current.setTurns(turns(40)));
    act(() => result.current.showEarlier());
    act(() => result.current.resetWindow());
    expect(result.current.visibleTurns).toHaveLength(INITIAL_VISIBLE_TURNS);
  });
});
