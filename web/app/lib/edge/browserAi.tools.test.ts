// Track 2 — the browser's own built-in AI: local_translate,
// local_detect_language and the capability summary. Same fake globals and
// harness as browserAi.test.ts (see its header and browserAi.testkit.ts).

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  BROWSER_AI_KINDS,
  DETECT_CONFIDENCE_FLOOR,
  MAX_DETECT_CHARS,
  MAX_TRANSLATE_CHARS,
  getBrowserAiCapabilities,
} from "./browserAi";
import { clearGlobals, makeModern, setGlobal, tool } from "./browserAi.testkit";

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
// local_translate
// ---------------------------------------------------------------------------

describe("local_translate", () => {
  it("translates with an explicit source language", async () => {
    const fake = makeModern("translate", { answer: "hola" });
    setGlobal("Translator", fake.global);
    const out = await tool("local_translate").run({ text: "hello", to: "es", from: "en" });
    expect(out).toContain("hola");
    expect(out).toContain("your browser's own built-in model");
    expect(fake.record.createCalls[0].sourceLanguage).toBe("en");
    expect(fake.record.createCalls[0].targetLanguage).toBe("es");
  });

  it("asks availability() about the specific pair, not in general", async () => {
    const fake = makeModern("translate", { answer: "hola" });
    setGlobal("Translator", fake.global);
    await tool("local_translate").run({ text: "hello", to: "es", from: "en" });
    expect(fake.record.availabilityCalls[0]).toMatchObject({ sourceLanguage: "en", targetLanguage: "es" });
  });

  it("keeps region subtags instead of reducing them to the base language", async () => {
    const fake = makeModern("translate", { answer: "olá" });
    setGlobal("Translator", fake.global);
    await tool("local_translate").run({ text: "hello", to: "pt-BR", from: "en-GB" });
    // "pt-BR" is what the schema advertises and what the browser's Translator
    // takes; collapsing it to "pt" would ask for a pair nobody requested.
    expect(fake.record.createCalls[0]).toMatchObject({ sourceLanguage: "en-GB", targetLanguage: "pt-BR" });
    expect(fake.record.availabilityCalls[0]).toMatchObject({ sourceLanguage: "en-GB", targetLanguage: "pt-BR" });
  });

  it("keeps a script subtag, so zh-Hans to zh-Hant is a real translation", async () => {
    const fake = makeModern("translate", { answer: "繁體" });
    setGlobal("Translator", fake.global);
    const out = await tool("local_translate").run({ text: "简体", to: "zh-Hant", from: "zh-Hans" });
    expect(out).toContain("繁體");
    expect(fake.record.createCalls[0]).toMatchObject({ sourceLanguage: "zh-Hans", targetLanguage: "zh-Hant" });
  });

  it("does not call two regions of one language the same language", async () => {
    const fake = makeModern("translate", { answer: "olá" });
    setGlobal("Translator", fake.global);
    const out = await tool("local_translate").run({ text: "ola", to: "pt-PT", from: "pt-BR" });
    expect(out).not.toContain("already in");
    expect(fake.record.createCalls.length).toBe(1);
  });

  it("detects the source language when 'from' is omitted", async () => {
    setGlobal("Translator", makeModern("translate", { answer: "bonjour" }).global);
    setGlobal(
      "LanguageDetector",
      makeModern("detect", { answer: [{ detectedLanguage: "en", confidence: 0.97 }] }).global
    );
    const out = await tool("local_translate").run({ text: "hello", to: "fr" });
    expect(out).toContain("bonjour");
    expect(out).toContain("detected as");
    expect(out).toContain("97%");
  });

  it("asks for 'from' when there is no detector at all", async () => {
    setGlobal("Translator", makeModern("translate", { answer: "bonjour" }).global);
    const out = await tool("local_translate").run({ text: "hello", to: "fr" });
    expect(out).toContain('Pass "from"');
    expect(out).toContain("built-in language detector");
  });

  it("asks for 'from' rather than acting on a low-confidence guess", async () => {
    setGlobal("Translator", makeModern("translate", { answer: "bonjour" }).global);
    setGlobal(
      "LanguageDetector",
      makeModern("detect", { answer: [{ detectedLanguage: "en", confidence: DETECT_CONFIDENCE_FLOOR - 0.2 }] }).global
    );
    const out = await tool("local_translate").run({ text: "hello", to: "fr" });
    expect(out).toContain('Pass "from"');
    expect(out).toContain("not confident enough");
  });

  it("says there is nothing to do when source and target match", async () => {
    const fake = makeModern("translate", { answer: "should not run" });
    setGlobal("Translator", fake.global);
    const out = await tool("local_translate").run({ text: "hello", to: "en", from: "en-US" });
    expect(out).toContain("already in");
    expect(fake.record.createCalls.length).toBe(0);
  });

  it("names the language pair when the browser refuses it", async () => {
    setGlobal("Translator", makeModern("translate", { availability: "unavailable" }).global);
    const out = await tool("local_translate").run({ text: "hello", to: "is", from: "ta" });
    expect(out).toContain("per language pair");
    expect(out).toContain("(ta)");
    expect(out).toContain("(is)");
  });

  it("rejects a malformed language tag with advice", async () => {
    setGlobal("Translator", makeModern("translate", { answer: "x" }).global);
    const out = await tool("local_translate").run({ text: "hello", to: "not a tag!!" });
    expect(out).toContain("BCP-47");
  });

  it("refuses text longer than the caller's result cap can carry back", async () => {
    setGlobal("Translator", makeModern("translate", { answer: "x" }).global);
    const out = await tool("local_translate").run({ text: "x".repeat(MAX_TRANSLATE_CHARS + 1), to: "es", from: "en" });
    expect(out).toContain(String(MAX_TRANSLATE_CHARS));
  });

  it("works through the older translation.createTranslator shape", async () => {
    setGlobal("translation", {
      canTranslate: async (): Promise<string> => "readily",
      createTranslator: async (): Promise<unknown> => ({
        translate: async (): Promise<string> => "legacy hola",
        destroy: (): void => undefined,
      }),
    });
    const out = await tool("local_translate").run({ text: "hello", to: "es", from: "en" });
    expect(out).toContain("legacy hola");
  });
});

// ---------------------------------------------------------------------------
// local_detect_language
// ---------------------------------------------------------------------------

describe("local_detect_language", () => {
  it("ranks the candidates and labels the confident one", async () => {
    setGlobal(
      "LanguageDetector",
      makeModern("detect", {
        answer: [
          { detectedLanguage: "ja", confidence: 0.91 },
          { detectedLanguage: "zh", confidence: 0.06 },
        ],
      }).global
    );
    const out = await tool("local_detect_language").run({ text: "こんにちは" });
    expect(out).toContain("Most likely");
    expect(out).toContain("(ja)");
    expect(out).toContain("91%");
  });

  it("calls a low-confidence top candidate a guess", async () => {
    setGlobal(
      "LanguageDetector",
      makeModern("detect", { answer: [{ detectedLanguage: "de", confidence: 0.2 }] }).global
    );
    const out = await tool("local_detect_language").run({ text: "hm" });
    expect(out).toContain("No confident answer");
    expect(out).toContain("read the list as a guess");
  });

  it("distinguishes 'the detector had no answer' from 'I could not look'", async () => {
    setGlobal("LanguageDetector", makeModern("detect", { answer: [] }).global);
    const out = await tool("local_detect_language").run({ text: "????" });
    expect(out).toContain("The detector ran");
    expect(out).not.toBe("");

    clearGlobals();
    const missing = await tool("local_detect_language").run({ text: "????" });
    expect(missing).toContain("does not provide");
    expect(missing).not.toContain("The detector ran");
  });

  it("drops malformed rows rather than inventing a language", async () => {
    setGlobal(
      "LanguageDetector",
      makeModern("detect", {
        answer: [{ detectedLanguage: 42 }, null, { confidence: 0.9 }, { detectedLanguage: "fi", confidence: 0.8 }],
      }).global
    );
    const out = await tool("local_detect_language").run({ text: "moi" });
    expect(out).toContain("(fi)");
    expect(out).not.toContain("42");
  });

  it("refuses an oversize passage", async () => {
    setGlobal("LanguageDetector", makeModern("detect", { answer: [] }).global);
    const out = await tool("local_detect_language").run({ text: "x".repeat(MAX_DETECT_CHARS + 1) });
    expect(out).toContain(String(MAX_DETECT_CHARS));
  });

  it("works through the older translation.createDetector shape", async () => {
    setGlobal("translation", {
      canDetect: async (): Promise<string> => "readily",
      createDetector: async (): Promise<unknown> => ({
        detect: async (): Promise<unknown> => [{ detectedLanguage: "nl", confidence: 0.88 }],
        destroy: (): void => undefined,
      }),
    });
    const out = await tool("local_detect_language").run({ text: "hallo" });
    expect(out).toContain("(nl)");
  });
});

// ---------------------------------------------------------------------------
// The capability summary
// ---------------------------------------------------------------------------

describe("capability summary", () => {
  it("returns one row per feature, each with a real sentence, on a browser with none", async () => {
    const summary = await getBrowserAiCapabilities();
    expect(summary.features.length).toBe(BROWSER_AI_KINDS.length);
    expect(summary.anyPresent).toBe(false);
    expect(summary.anyReady).toBe(false);
    expect(summary.headline.length).toBeGreaterThan(20);
    for (const feature of summary.features) {
      expect(feature.present).toBe(false);
      expect(feature.explanation.length).toBeGreaterThan(40);
      expect(feature.explanation).toContain("does not provide");
    }
  });

  it("reports features independently instead of one verdict", async () => {
    setGlobal("LanguageDetector", makeModern("detect", { availability: "available" }).global);
    setGlobal("Summarizer", makeModern("summarize", { availability: "downloadable" }).global);
    const summary = await getBrowserAiCapabilities();
    const byKind = Object.fromEntries(summary.features.map((f) => [f.kind, f]));
    expect(byKind.languageDetector.ready).toBe(true);
    expect(byKind.summarizer.ready).toBe(false);
    expect(byKind.summarizer.needsDownload).toBe(true);
    expect(byKind.summarizer.explanation).toContain("starts only when you ask for it");
    expect(byKind.translator.present).toBe(false);
    expect(summary.anyPresent).toBe(true);
    expect(summary.anyReady).toBe(true);
  });

  it("marks the translator as per-pair rather than guessing a state", async () => {
    setGlobal("Translator", makeModern("translate", {}).global);
    const summary = await getBrowserAiCapabilities();
    const translator = summary.features.filter((f) => f.kind === "translator")[0];
    expect(translator.present).toBe(true);
    expect(translator.perRequest).toBe(true);
    expect(translator.explanation).toContain("language pair");
  });

  it("does not print a 'none is ready, each row says what it is waiting for' headline over a row that is waiting for nothing", async () => {
    // Translator only. Its row is deliberately left "unknown"/ready false
    // because the language pair decides, so the generic not-ready headline
    // would contradict the row printed directly under it.
    setGlobal("Translator", makeModern("translate", {}).global);
    const summary = await getBrowserAiCapabilities();
    expect(summary.anyPresent).toBe(true);
    expect(summary.anyReady).toBe(false);
    expect(summary.headline).toContain("built-in translator");
    expect(summary.headline).toContain("per request");
    expect(summary.headline).not.toContain("waiting for");
  });

  it("still names what the other rows are waiting for when both kinds are present", async () => {
    setGlobal("Translator", makeModern("translate", {}).global);
    setGlobal("Summarizer", makeModern("summarize", { availability: "downloadable" }).global);
    const summary = await getBrowserAiCapabilities();
    expect(summary.headline).toContain("waiting for");
    expect(summary.headline).toContain("built-in translator");
    expect(summary.headline).toContain("per request");
  });

  it("says what is free and ready, by name", async () => {
    setGlobal("Summarizer", makeModern("summarize", { availability: "available" }).global);
    const summary = await getBrowserAiCapabilities();
    expect(summary.headline).toContain("built-in summarizer");
    expect(summary.headline).toContain("no account");
  });

  it("never rejects, even when every probe throws", async () => {
    const explode = {
      availability: async (): Promise<string> => {
        throw new Error("nope");
      },
      create: async (): Promise<unknown> => {
        throw new Error("nope");
      },
    };
    setGlobal("Summarizer", explode);
    setGlobal("Translator", explode);
    setGlobal("LanguageDetector", explode);
    const summary = await getBrowserAiCapabilities();
    expect(summary.features.length).toBe(3);
    for (const feature of summary.features) {
      expect(feature.explanation.length).toBeGreaterThan(20);
    }
  });
});
