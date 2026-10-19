import { describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { appendExchange, useCallTranscript } from "./useCallTranscript";
import { computeCanSee } from "./useCallCapabilities";
import { grayFrameDiff, rgbaToGray } from "./useEdgeSight";
import { pipelineErrorForFailedStt } from "./voiceHelpers";

describe("appendExchange", () => {
  it("appends user then agent, skipping an empty user line", () => {
    expect(appendExchange([], "hi", "hello")).toEqual([
      { role: "user", text: "hi" },
      { role: "agent", text: "hello" },
    ]);
    expect(appendExchange([{ role: "agent", text: "a" }], null, "b")).toEqual([
      { role: "agent", text: "a" },
      { role: "agent", text: "b" },
    ]);
  });
});

describe("useCallTranscript", () => {
  it("captures a settled exchange once and not while the pipeline is busy", () => {
    const { result, rerender } = renderHook(({ busy }) => useCallTranscript(busy), { initialProps: { busy: true } });
    act(() => {
      result.current.setLastTranscript("question");
      result.current.setLastReply("answer");
    });
    expect(result.current.callTranscript).toHaveLength(0);
    rerender({ busy: false });
    expect(result.current.callTranscript).toEqual([
      { role: "user", text: "question" },
      { role: "agent", text: "answer" },
    ]);
    rerender({ busy: true });
    rerender({ busy: false });
    expect(result.current.callTranscript).toHaveLength(2);
  });

  it("resetTranscript clears everything so the same pair can be captured again", () => {
    const { result } = renderHook(() => useCallTranscript(false));
    act(() => {
      result.current.setLastTranscript("q");
      result.current.setLastReply("a");
    });
    expect(result.current.callTranscript).toHaveLength(2);
    act(() => result.current.resetTranscript());
    expect(result.current.callTranscript).toHaveLength(0);
    expect(result.current.lastReply).toBeNull();
  });
});

describe("computeCanSee", () => {
  it("allows sight from a vision model or a ready on-device model", () => {
    expect(computeCanSee(true, false, false)).toBe(true);
    expect(computeCanSee(false, true, true)).toBe(true);
    expect(computeCanSee(false, true, false)).toBe(false);
    expect(computeCanSee(false, false, true)).toBe(false);
  });
});

describe("edge sight frame gate", () => {
  it("converts rgba to luma and measures the mean diff", () => {
    const white = rgbaToGray([255, 255, 255, 255, 255, 255, 255, 255], 2);
    const black = rgbaToGray([0, 0, 0, 255, 0, 0, 0, 255], 2);
    expect(Array.from(white)).toEqual([255, 255]);
    expect(grayFrameDiff(white, black)).toBe(1);
    expect(grayFrameDiff(white, white)).toBe(0);
  });
});

describe("pipelineErrorForFailedStt", () => {
  it("passes through the no-speech message and rewrites admin copy", () => {
    expect(pipelineErrorForFailedStt("No speech was detected")).toBe("No speech was detected");
    expect(pipelineErrorForFailedStt("Add a Sarvam credential")).toMatch(/could not be turned into text/);
  });
});
