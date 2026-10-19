// Track 2 — the browser's own built-in AI (Summarizer / Translator /
// LanguageDetector).
//
// Everything here runs against FAKE globals. None of these APIs exists in Node
// or jsdom, so a suite that tried to exercise the real ones would either be
// skipped everywhere or would pass by doing nothing. What CAN be proved
// without a browser is exactly what this module promises: which call is made
// in which state, that create() is never reached from a tool, that nothing
// touches the network, and that every degraded state produces a sentence.
//
// Two kinds of test, following localTools.test.ts:
//
//  * BEHAVIOUR — each tool's happy path, both API shapes, and the degraded
//    states that are NORMAL here: no such browser feature, a model the browser
//    has not fetched, a language pair it will not do, a detector that is not
//    sure, an empty answer, and a hang.
//  * PROPERTY — the promises the header makes. That the emitted module has no
//    static value import; that the source contains no network primitive; that
//    every handler still works with fetch/XHR/WebSocket/sendBeacon/EventSource
//    replaced by throwing stubs; and that no tool can trigger a download.

//
// Split by area: this file holds detection, availability and local_summarize;
// browserAi.tools.test.ts the translate / detect-language tools and the
// capability summary; browserAi.properties.test.ts the property proofs.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  BROWSER_AI_BUDGET_MS,
  BROWSER_AI_KINDS,
  MAX_SUMMARIZE_CHARS,
  browserAiAvailability,
  resolveBrowserAiSurface,
} from "./browserAi";
import { clearGlobals, makeLegacyCapabilities, makeModern, setGlobal, tool } from "./browserAi.testkit";

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

beforeEach(() => {
  clearGlobals();
});

afterEach(() => {
  clearGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

// ---------------------------------------------------------------------------
// Detection is independent per feature
// ---------------------------------------------------------------------------

describe("feature detection is independent", () => {
  it("finds nothing when the browser provides nothing", () => {
    for (const kind of BROWSER_AI_KINDS) {
      expect(resolveBrowserAiSurface(kind).shape).toBe("none");
    }
  });

  it("a browser with only a detector reports only a detector", () => {
    setGlobal("LanguageDetector", makeModern("detect", {}).global);
    expect(resolveBrowserAiSurface("languageDetector").shape).toBe("modern");
    expect(resolveBrowserAiSurface("summarizer").shape).toBe("none");
    expect(resolveBrowserAiSurface("translator").shape).toBe("none");
  });

  it("a browser with only a summarizer reports only a summarizer", () => {
    setGlobal("Summarizer", makeModern("summarize", {}).global);
    expect(resolveBrowserAiSurface("summarizer").shape).toBe("modern");
    expect(resolveBrowserAiSurface("languageDetector").shape).toBe("none");
  });

  it("falls back to the older ai.* / translation.* shapes", () => {
    setGlobal("ai", { summarizer: makeLegacyCapabilities("summarize", {}).global });
    setGlobal("translation", {
      canTranslate: async (): Promise<string> => "readily",
      createTranslator: async (): Promise<unknown> => ({}),
      canDetect: async (): Promise<string> => "readily",
      createDetector: async (): Promise<unknown> => ({}),
    });
    expect(resolveBrowserAiSurface("summarizer").shape).toBe("legacy");
    expect(resolveBrowserAiSurface("translator").shape).toBe("legacy");
    expect(resolveBrowserAiSurface("languageDetector").shape).toBe("legacy");
  });

  it("prefers the modern global when both shapes are present", () => {
    setGlobal("Summarizer", makeModern("summarize", {}).global);
    setGlobal("ai", { summarizer: makeLegacyCapabilities("summarize", {}).global });
    expect(resolveBrowserAiSurface("summarizer").shape).toBe("modern");
  });
});

describe("availability normalization", () => {
  it("maps the four modern states through unchanged", async () => {
    for (const state of ["available", "downloadable", "downloading", "unavailable"]) {
      setGlobal("Summarizer", makeModern("summarize", { availability: state }).global);
      expect(await browserAiAvailability("summarizer")).toBe(state);
    }
  });

  it("maps the older three strings onto the same vocabulary", async () => {
    const cases: Array<[string, string]> = [
      ["readily", "available"],
      ["after-download", "downloadable"],
      ["no", "unavailable"],
    ];
    for (const [legacy, expected] of cases) {
      setGlobal("ai", { summarizer: makeLegacyCapabilities("summarize", { availability: legacy }).global });
      expect(await browserAiAvailability("summarizer")).toBe(expected);
    }
  });

  it("answers 'unknown', never 'available', when the browser says something unrecognised", async () => {
    setGlobal("Summarizer", makeModern("summarize", { availability: "maybe-later" }).global);
    expect(await browserAiAvailability("summarizer")).toBe("unknown");
  });

  it("answers 'unknown', never 'available', when availability() throws", async () => {
    setGlobal("Summarizer", {
      availability: async (): Promise<string> => {
        throw new Error("boom");
      },
      create: async (): Promise<unknown> => ({}),
    });
    expect(await browserAiAvailability("summarizer")).toBe("unknown");
  });

  it("answers 'unknown' when create() exists but there is no way to ask first", async () => {
    setGlobal("Summarizer", { create: async (): Promise<unknown> => ({}) });
    expect(await browserAiAvailability("summarizer")).toBe("unknown");
  });
});

// ---------------------------------------------------------------------------
// local_summarize
// ---------------------------------------------------------------------------

describe("local_summarize", () => {
  it("summarizes through the modern global and says whose model did it", async () => {
    const fake = makeModern("summarize", { answer: "Three bullet points." });
    setGlobal("Summarizer", fake.global);
    const out = await tool("local_summarize").run({ text: "a long passage about otters" });
    expect(out).toContain("Three bullet points.");
    expect(out).toContain("your browser's own built-in model");
    expect(fake.record.answerCalls).toEqual(["a long passage about otters"]);
    expect(fake.record.destroyCalls).toBe(1);
  });

  it("passes type/length/context through to create()", async () => {
    const fake = makeModern("summarize", { answer: "ok" });
    setGlobal("Summarizer", fake.global);
    await tool("local_summarize").run({ text: "x", type: "tldr", length: "long", context: "for a child" });
    const created = fake.record.createCalls[0];
    expect(created.type).toBe("tldr");
    expect(created.length).toBe("long");
    expect(created.sharedContext).toBe("for a child");
  });

  it("works through the older ai.summarizer shape", async () => {
    setGlobal("ai", { summarizer: makeLegacyCapabilities("summarize", { answer: "legacy summary" }).global });
    const out = await tool("local_summarize").run({ text: "hello" });
    expect(out).toContain("legacy summary");
  });

  it("explains itself, rather than erroring, on a browser without the feature", async () => {
    const out = await tool("local_summarize").run({ text: "hello" });
    expect(out).toContain("does not provide");
    expect(out).toContain("built-in summarizer");
    expect(out.length).toBeGreaterThan(40);
  });

  it("names the allowed values for a bad type", async () => {
    setGlobal("Summarizer", makeModern("summarize", { answer: "ok" }).global);
    const out = await tool("local_summarize").run({ text: "x", type: "poem" });
    expect(out).toContain("key-points");
    expect(out).toContain("headline");
  });

  it("refuses an oversize passage instead of cutting it", async () => {
    setGlobal("Summarizer", makeModern("summarize", { answer: "ok" }).global);
    const out = await tool("local_summarize").run({ text: "x".repeat(MAX_SUMMARIZE_CHARS + 1) });
    expect(out).toContain(String(MAX_SUMMARIZE_CHARS));
    expect(out).toContain("refused rather than silently cut");
  });

  it("says so when the browser runs but returns nothing", async () => {
    setGlobal("Summarizer", makeModern("summarize", { answer: "   " }).global);
    const out = await tool("local_summarize").run({ text: "x" });
    expect(out).toContain("returned an empty summary");
    expect(out).not.toBe("");
  });

  it("says so when the browser returns a non-string", async () => {
    setGlobal("Summarizer", makeModern("summarize", { answer: { not: "a string" } }).global);
    const out = await tool("local_summarize").run({ text: "x" });
    expect(out).toContain("returned an empty summary");
    expect(out).not.toContain("[object Object]");
  });

  it("turns a browser-side throw into a typed failure that names the browser", async () => {
    setGlobal("Summarizer", makeModern("summarize", { answerError: new Error("model exploded") }).global);
    await expect(tool("local_summarize").run({ text: "x" })).rejects.toThrow(/failed while running/);
  });

  it("reports the browser's own quota error in words", async () => {
    const quota = new Error("too big");
    quota.name = "QuotaExceededError";
    setGlobal("Summarizer", makeModern("summarize", { answerError: quota }).global);
    await expect(tool("local_summarize").run({ text: "x" })).rejects.toThrow(/larger than your browser/);
  });

  it("stops and says so when the browser never answers", async () => {
    vi.useFakeTimers();
    setGlobal("Summarizer", makeModern("summarize", { hangUntilAborted: true }).global);
    const pending = tool("local_summarize").run({ text: "x" });
    const assertion = expect(pending).rejects.toThrow(/did not answer within/);
    // Flush the availability()/create() microtasks first, so the budget timer
    // is actually registered before the clock is moved.
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(BROWSER_AI_BUDGET_MS + 10);
    await assertion;
  });
});
