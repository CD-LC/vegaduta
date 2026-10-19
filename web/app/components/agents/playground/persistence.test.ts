import { afterEach, describe, expect, it } from "vitest";
import { PLAYGROUND_STORAGE_PREFIX, loadPlaygroundState, savePlaygroundState } from "./persistence";
import { formatUsageCost, framesForHistory, visionFallbackLabel } from "./format";
import type { PlaygroundTurn } from "./types";

const TURNS: PlaygroundTurn[] = [
  { role: "user", content: "hi" },
  { role: "assistant", content: "hello" },
];

afterEach(() => window.localStorage.clear());

describe("playground persistence", () => {
  it("round-trips a transcript per agent", () => {
    savePlaygroundState("a1", "s1", TURNS);
    expect(loadPlaygroundState("a1")?.sessionId).toBe("s1");
    expect(loadPlaygroundState("a1")?.turns).toEqual(TURNS);
    expect(loadPlaygroundState("a2")).toBeNull();
  });

  it("clears the record when there is no session or no turns", () => {
    savePlaygroundState("a1", "s1", TURNS);
    savePlaygroundState("a1", null, TURNS);
    expect(window.localStorage.getItem(PLAYGROUND_STORAGE_PREFIX + "a1")).toBeNull();
  });

  it("drops a record older than 24 hours", () => {
    window.localStorage.setItem(
      PLAYGROUND_STORAGE_PREFIX + "a1",
      JSON.stringify({ sessionId: "s1", turns: TURNS, savedAt: Date.now() - 25 * 60 * 60 * 1000 })
    );
    expect(loadPlaygroundState("a1")).toBeNull();
  });
});

describe("playground format helpers", () => {
  it("never rounds a fractional-cent cost to $0.00", () => {
    expect(formatUsageCost(1.234)).toBe("$1.23");
    expect(formatUsageCost(0.0421)).toBe("$0.042");
    expect(formatUsageCost(0.00042)).toBe("$0.00042");
  });

  it("keeps a thumbnail and identity of staged frames, never the raw base64 again", () => {
    const frames = framesForHistory([
      { dataUrl: "data:image/jpeg;base64,AAA", fileName: "f.jpg", source: "camera", dataBase64: "AAA", mimeType: "image/jpeg" } as never,
    ]);
    expect(frames).toEqual([{ dataUrl: "data:image/jpeg;base64,AAA", fileName: "f.jpg", source: "camera" }]);
    expect(framesForHistory(null)).toEqual([]);
  });

  it("maps vision fallback reasons to plain language and passes unknown sentences through", () => {
    expect(visionFallbackLabel("model-not-ready")).toMatch(/isn't downloaded/);
    expect(visionFallbackLabel("custom sentence")).toBe("custom sentence");
    expect(visionFallbackLabel(undefined)).toBe("on-device analysis failed");
  });
});
