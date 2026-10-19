// The voice-output chain's rules (2026-09-19): order, fall-through on any
// failure, both time bounds, and the fetch decision. No DOM - runners are
// plain fakes.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ABORT_POLL_MS,
  DEFAULT_MAX_PLAYBACK_MS,
  DEFAULT_START_TIMEOUT_MS,
  MAX_FRAGMENT_CHARS,
  MERGE_TARGET_CHARS,
  decideVoiceModelFetch,
  isLatinScriptText,
  kokoroMissedStart,
  plainSpokenExcerpt,
  planVoiceOutput,
  runVoiceChain,
  speakSequence,
  speechLangForText,
  splitForSpeech,
  type StepRunner,
  type VoiceChainResult,
  type VoiceFetchInput,
  type VoicePlanInput,
} from "./voiceOutput";

const planBase: VoicePlanInput = {
  text: "Hello there.",
  hasServerAudio: true,
  hasAudioElement: true,
  edgeOn: true,
  kokoroInstalled: true,
  preferOnDevice: false,
  webSpeechPresent: true,
};

describe("planVoiceOutput", () => {
  it("orders server audio, then Kokoro, then Web Speech by default", () => {
    expect(planVoiceOutput(planBase)).toEqual(["server-audio", "kokoro", "web-speech"]);
  });

  it("puts Kokoro first for someone who turned on-device replies on", () => {
    expect(planVoiceOutput({ ...planBase, preferOnDevice: true })).toEqual(["kokoro", "server-audio", "web-speech"]);
  });

  it("uses Kokoro as the default voice when the server sent no audio (the production case)", () => {
    expect(planVoiceOutput({ ...planBase, hasServerAudio: false })).toEqual(["kokoro", "web-speech"]);
  });

  it("skips Kokoro cleanly when the edge surface is off in this build", () => {
    expect(planVoiceOutput({ ...planBase, edgeOn: false })).toEqual(["server-audio", "web-speech"]);
  });

  it("skips Kokoro when it is not installed - planning never downloads", () => {
    expect(planVoiceOutput({ ...planBase, kokoroInstalled: false })).toEqual(["server-audio", "web-speech"]);
  });

  it("drops both element-based voices when there is no <audio> element", () => {
    expect(planVoiceOutput({ ...planBase, hasAudioElement: false })).toEqual(["web-speech"]);
  });

  it("is text-only (empty plan) when nothing can speak", () => {
    expect(
      planVoiceOutput({ ...planBase, hasServerAudio: false, kokoroInstalled: false, webSpeechPresent: false })
    ).toEqual([]);
  });

  it("still plays server audio for a fragment with no text", () => {
    expect(planVoiceOutput({ ...planBase, text: "  " })).toEqual(["server-audio"]);
  });

  it("skips Kokoro for the rest of a reply once it missed a start deadline", () => {
    expect(planVoiceOutput({ ...planBase, hasServerAudio: false, skipKokoro: true })).toEqual(["web-speech"]);
    expect(planVoiceOutput({ ...planBase, preferOnDevice: true, skipKokoro: true })).toEqual([
      "server-audio",
      "web-speech",
    ]);
  });

  it("never routes a non-Latin-script reply to Kokoro's English voice", () => {
    expect(planVoiceOutput({ ...planBase, hasServerAudio: false, text: "नमस्ते, आप कैसे हैं?" })).toEqual(["web-speech"]);
  });
});

describe("isLatinScriptText", () => {
  it("accepts English, incl. digits/punctuation/emoji noise", () => {
    expect(isLatinScriptText("Your order #4521 ships on 3 Oct! 🎉")).toBe(true);
  });
  it("accepts accented Latin", () => {
    expect(isLatinScriptText("¿Dónde está la estación?")).toBe(true);
  });
  it("rejects Devanagari, Kannada, Arabic and CJK", () => {
    expect(isLatinScriptText("नमस्ते दुनिया")).toBe(false);
    expect(isLatinScriptText("ನಮಸ್ಕಾರ")).toBe(false);
    expect(isLatinScriptText("مرحبا بالعالم")).toBe(false);
    expect(isLatinScriptText("你好，世界")).toBe(false);
  });
  it("tolerates a brand name inside an otherwise Hindi sentence, and vice versa", () => {
    expect(isLatinScriptText("VegaDūta में आपका स्वागत है दोस्तों")).toBe(false);
    expect(isLatinScriptText("The word नमस्ते means hello in Hindi, used as a greeting")).toBe(true);
  });
  it("rejects text with no letters at all", () => {
    expect(isLatinScriptText("1234 !!!")).toBe(false);
    expect(isLatinScriptText("")).toBe(false);
    expect(isLatinScriptText(null)).toBe(false);
  });
});

describe("speechLangForText", () => {
  it("asserts no language for Latin script, empty text or an unknown script", () => {
    expect(speechLangForText("Hello there, how are you?")).toBeNull();
    expect(speechLangForText("¿Dónde está la estación?")).toBeNull();
    expect(speechLangForText("")).toBeNull();
    expect(speechLangForText("1234 !!!")).toBeNull();
    expect(speechLangForText(null)).toBeNull();
  });
  it("names the language of the dominant non-Latin script", () => {
    expect(speechLangForText("नमस्ते, आप कैसे हैं?")).toBe("hi-IN");
    expect(speechLangForText("ನಮಸ್ಕಾರ, ಹೇಗಿದ್ದೀರಿ?")).toBe("kn-IN");
    expect(speechLangForText("வணக்கம்")).toBe("ta-IN");
    expect(speechLangForText("مرحبا بالعالم")).toBe("ar");
    expect(speechLangForText("你好，世界")).toBe("zh-CN");
    expect(speechLangForText("안녕하세요")).toBe("ko-KR");
    expect(speechLangForText("VegaDūta में आपका स्वागत है दोस्तों")).toBe("hi-IN");
  });
  it("reads kanji next to kana as Japanese, not Chinese", () => {
    expect(speechLangForText("今日は天気がいいですね")).toBe("ja-JP");
  });
});

describe("runVoiceChain", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  const speaks: StepRunner = async (c) => {
    c.started();
    return true;
  };
  const fails: StepRunner = async () => false;
  const throws: StepRunner = async () => {
    throw new Error("worker crashed");
  };
  const throwsSync: StepRunner = () => {
    throw new Error("sync boom");
  };
  const hangs: StepRunner = () => new Promise<boolean>(() => {});

  it("stops at the first voice that speaks", async () => {
    const webSpeech = vi.fn(speaks);
    const result = await runVoiceChain(["server-audio", "web-speech"], { "server-audio": speaks, "web-speech": webSpeech });
    expect(result.spokenBy).toBe("server-audio");
    expect(webSpeech).not.toHaveBeenCalled();
  });

  it("falls through a false, a rejection and a synchronous throw", async () => {
    const result = await runVoiceChain(["server-audio", "kokoro", "web-speech"], {
      "server-audio": fails,
      kokoro: throws,
      "web-speech": speaks,
    });
    expect(result.spokenBy).toBe("web-speech");
    expect(result.attempts.map((a) => a.outcome)).toEqual(["failed", "threw", "spoken"]);

    const sync = await runVoiceChain(["kokoro", "web-speech"], { kokoro: throwsSync, "web-speech": speaks });
    expect(sync.spokenBy).toBe("web-speech");
    expect(sync.attempts[0].outcome).toBe("threw");
  });

  it("resolves text-only when every voice fails - and never rejects", async () => {
    const result = await runVoiceChain(["server-audio", "kokoro", "web-speech"], {
      "server-audio": fails,
      kokoro: throws,
      "web-speech": fails,
    });
    expect(result.spokenBy).toBeNull();
  });

  it("resolves text-only for an empty plan", async () => {
    expect(await runVoiceChain([], {})).toEqual({ spokenBy: null, attempts: [] });
  });

  it("skips a step with no runner", async () => {
    const result = await runVoiceChain(["kokoro", "web-speech"], { "web-speech": speaks });
    expect(result.attempts[0]).toEqual({ step: "kokoro", outcome: "no-runner" });
    expect(result.spokenBy).toBe("web-speech");
  });

  it("gives up on a voice that never STARTS and tells it to be quiet", async () => {
    const cancelled = vi.fn();
    let sawCancelled = false;
    const neverStarts: StepRunner = (c) => {
      c.onCancel(() => {
        cancelled();
        sawCancelled = c.isCancelled();
      });
      return new Promise<boolean>(() => {});
    };
    const pending = runVoiceChain(["web-speech"], { "web-speech": neverStarts });
    await vi.advanceTimersByTimeAsync(DEFAULT_START_TIMEOUT_MS["web-speech"] + 1);
    const result = await pending;
    expect(result).toEqual({ spokenBy: null, attempts: [{ step: "web-speech", outcome: "start-timeout" }] });
    expect(cancelled).toHaveBeenCalledTimes(1);
    expect(sawCancelled).toBe(true);
  });

  it("moves to the next voice after a start timeout", async () => {
    const pending = runVoiceChain(["kokoro", "web-speech"], { kokoro: hangs, "web-speech": speaks });
    await vi.advanceTimersByTimeAsync(DEFAULT_START_TIMEOUT_MS.kokoro + 1);
    const result = await pending;
    expect(result.spokenBy).toBe("web-speech");
    expect(result.attempts[0].outcome).toBe("start-timeout");
  });

  it("bounds a voice that started but never reports the end (Chrome's missing onend)", async () => {
    const next = vi.fn(speaks);
    const startsThenHangs: StepRunner = (c) => {
      c.started();
      return new Promise<boolean>(() => {});
    };
    const pending = runVoiceChain(["web-speech", "kokoro"], { "web-speech": startsThenHangs, kokoro: next });
    // The start deadline must NOT fire once started() was called.
    await vi.advanceTimersByTimeAsync(DEFAULT_START_TIMEOUT_MS["web-speech"] + 1000);
    await vi.advanceTimersByTimeAsync(DEFAULT_MAX_PLAYBACK_MS);
    const result = await pending;
    expect(result.attempts).toEqual([{ step: "web-speech", outcome: "playback-timeout" }]);
    // It WAS heard - falling through would say the sentence twice.
    expect(result.spokenBy).toBe("web-speech");
    expect(next).not.toHaveBeenCalled();
  });

  it("does not repeat a sentence on another voice once one started and then errored", async () => {
    const next = vi.fn(speaks);
    const startsThenFails: StepRunner = async (c) => {
      c.started();
      return false;
    };
    const result = await runVoiceChain(["server-audio", "web-speech"], {
      "server-audio": startsThenFails,
      "web-speech": next,
    });
    expect(result.spokenBy).toBe("server-audio");
    expect(next).not.toHaveBeenCalled();
  });

  it("honours per-step timeout overrides", async () => {
    const pending = runVoiceChain(["kokoro"], { kokoro: hangs }, { startTimeoutMs: { kokoro: 50 } });
    await vi.advanceTimersByTimeAsync(51);
    expect((await pending).attempts[0].outcome).toBe("start-timeout");
  });

  it("runs an onCancel registered after the step was already given up on", async () => {
    const late = vi.fn();
    const runner: StepRunner = (c) =>
      new Promise<boolean>(() => {
        setTimeout(() => c.onCancel(late), 100);
      });
    const pending = runVoiceChain(["kokoro"], { kokoro: runner }, { startTimeoutMs: { kokoro: 50 } });
    await vi.advanceTimersByTimeAsync(200);
    await pending;
    expect(late).toHaveBeenCalledTimes(1);
  });
});

describe("runVoiceChain abort (barge-in, unmount, a newer reply)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("tries nothing when already aborted", async () => {
    const runner = vi.fn(async () => true);
    const result = await runVoiceChain(["kokoro", "web-speech"], { kokoro: runner, "web-speech": runner }, {
      isAborted: () => true,
    });
    expect(result).toEqual({ spokenBy: null, attempts: [], aborted: true });
    expect(runner).not.toHaveBeenCalled();
  });

  it("an abort during a pending Kokoro synth: the late audio never plays and no later voice runs", async () => {
    let aborted = false;
    let finishSynth: () => void = () => {};
    const play = vi.fn();
    const cancel = vi.fn();
    // Shaped like voicePlayers' kokoro runner: synthesize, then check
    // isCancelled() before touching the <audio> element.
    const kokoro: StepRunner = async (c) => {
      c.onCancel(cancel);
      await new Promise<void>((resolve) => (finishSynth = resolve));
      if (c.isCancelled()) return false;
      play();
      c.started();
      return true;
    };
    const webSpeech = vi.fn(async () => true);
    const pending = runVoiceChain(["kokoro", "web-speech"], { kokoro, "web-speech": webSpeech }, {
      isAborted: () => aborted,
    });
    await vi.advanceTimersByTimeAsync(2_000); // synth still running
    aborted = true; // the person holds to talk again
    await vi.advanceTimersByTimeAsync(ABORT_POLL_MS + 1);
    const result = await pending;
    expect(result.aborted).toBe(true);
    expect(result.spokenBy).toBeNull();
    expect(result.attempts).toEqual([{ step: "kokoro", outcome: "aborted" }]);
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(webSpeech).not.toHaveBeenCalled();
    // The synth completes seconds later, into the person's recording.
    finishSynth();
    await vi.advanceTimersByTimeAsync(10);
    expect(play).not.toHaveBeenCalled();
  });

  it("server audio silenced before it started does not fall through to another voice", async () => {
    let aborted = false;
    let settle: (ok: boolean) => void = () => {};
    const serverAudio: StepRunner = () => new Promise<boolean>((resolve) => (settle = resolve));
    const kokoro = vi.fn(async () => true);
    const pending = runVoiceChain(["server-audio", "kokoro"], { "server-audio": serverAudio, kokoro }, {
      isAborted: () => aborted,
    });
    await vi.advanceTimersByTimeAsync(10);
    // stopAllSpeech(): pause() + settle(false), in the same tick as the epoch bump.
    aborted = true;
    settle(false);
    const result = await pending;
    expect(result.aborted).toBe(true);
    expect(kokoro).not.toHaveBeenCalled();
  });

  it("reports the voice that was already audible when the abort came", async () => {
    let aborted = false;
    const playing: StepRunner = (c) => {
      c.started();
      return new Promise<boolean>(() => {});
    };
    const pending = runVoiceChain(["web-speech"], { "web-speech": playing }, { isAborted: () => aborted });
    await vi.advanceTimersByTimeAsync(500);
    aborted = true;
    await vi.advanceTimersByTimeAsync(ABORT_POLL_MS + 1);
    expect(await pending).toMatchObject({ spokenBy: "web-speech", aborted: true });
  });

  it("an isAborted that throws is treated as not aborted", async () => {
    const result = await runVoiceChain(["web-speech"], { "web-speech": async (c) => (c.started(), true) }, {
      isAborted: () => {
        throw new Error("boom");
      },
    });
    expect(result.spokenBy).toBe("web-speech");
  });
});

describe("speakSequence", () => {
  const spoken = (step: "kokoro" | "web-speech"): VoiceChainResult => ({
    spokenBy: step,
    attempts: [{ step, outcome: "spoken" }],
  });

  it("starts preparing fragment N+1 while N is still being spoken", async () => {
    const events: string[] = [];
    let release: (r: VoiceChainResult) => void = () => {};
    const pending = speakSequence<string>(["one", "two"], {
      keepGoing: () => true,
      prefetch: (text) => {
        events.push(`prefetch:${text}`);
        return `audio-for-${text}`;
      },
      speak: (text, ctx) => {
        events.push(`speak:${text}:${ctx.prefetched ?? "-"}`);
        if (text === "one") return new Promise<VoiceChainResult>((resolve) => (release = resolve));
        return Promise.resolve(spoken("kokoro"));
      },
    });
    await Promise.resolve();
    // "two" is already being prepared although "one" has not finished.
    expect(events).toEqual(["speak:one:-", "prefetch:two"]);
    release(spoken("kokoro"));
    expect(await pending).toBe("kokoro");
    expect(events).toEqual(["speak:one:-", "prefetch:two", "speak:two:audio-for-two"]);
  });

  it("skips Kokoro - and stops prefetching - for the rest of a reply after a start-timeout", async () => {
    const prefetch = vi.fn((text: string) => `audio-for-${text}`);
    const contexts: { prefetched: string | undefined; skipKokoro: boolean }[] = [];
    const result = await speakSequence<string>(["one", "two", "three"], {
      keepGoing: () => true,
      prefetch,
      speak: async (text, ctx) => {
        contexts.push(ctx);
        if (text === "one") {
          return {
            spokenBy: "web-speech",
            attempts: [
              { step: "kokoro", outcome: "start-timeout" },
              { step: "web-speech", outcome: "spoken" },
            ],
          };
        }
        return spoken("web-speech");
      },
    });
    expect(result).toBe("web-speech");
    expect(contexts.map((c) => c.skipKokoro)).toEqual([false, true, true]);
    // The prefetch made for "two" before the timeout is known must not be used.
    expect(contexts[1].prefetched).toBeUndefined();
    expect(prefetch).toHaveBeenCalledTimes(1);
  });

  it("stops when the turn went stale, when a chain was aborted, and when nothing could speak", async () => {
    let go = true;
    const speakStale = vi.fn(async () => {
      go = false;
      return spoken("web-speech");
    });
    expect(await speakSequence(["a", "b"], { keepGoing: () => go, speak: speakStale })).toBe("web-speech");
    expect(speakStale).toHaveBeenCalledTimes(1);

    const speakAborted = vi.fn(async (): Promise<VoiceChainResult> => ({ spokenBy: null, attempts: [], aborted: true }));
    expect(await speakSequence(["a", "b"], { keepGoing: () => true, speak: speakAborted })).toBeNull();
    expect(speakAborted).toHaveBeenCalledTimes(1);

    const speakSilent = vi.fn(async (): Promise<VoiceChainResult> => ({ spokenBy: null, attempts: [] }));
    expect(await speakSequence(["a", "b"], { keepGoing: () => true, speak: speakSilent })).toBeNull();
    expect(speakSilent).toHaveBeenCalledTimes(1);
  });

  it("survives a prefetch that throws and a speak that rejects", async () => {
    const result = await speakSequence<string>(["a", "b"], {
      keepGoing: () => true,
      prefetch: () => {
        throw new Error("no worker");
      },
      speak: async (text) => {
        if (text === "b") throw new Error("boom");
        return spoken("web-speech");
      },
    });
    expect(result).toBe("web-speech");
  });

  it("kokoroMissedStart only reacts to Kokoro's start deadline", () => {
    expect(kokoroMissedStart({ spokenBy: null, attempts: [{ step: "kokoro", outcome: "start-timeout" }] })).toBe(true);
    expect(kokoroMissedStart({ spokenBy: null, attempts: [{ step: "kokoro", outcome: "failed" }] })).toBe(false);
    expect(kokoroMissedStart({ spokenBy: null, attempts: [{ step: "web-speech", outcome: "start-timeout" }] })).toBe(false);
  });
});

describe("splitForSpeech", () => {
  it("returns nothing for empty text", () => {
    expect(splitForSpeech("")).toEqual([]);
    expect(splitForSpeech(null)).toEqual([]);
    expect(splitForSpeech("   \n ")).toEqual([]);
  });

  it("keeps a short reply whole", () => {
    expect(splitForSpeech("Sure. I can do that!")).toEqual(["Sure. I can do that!"]);
  });

  it("never builds a long fragment out of sentences - each synth must start sounding quickly", () => {
    const sentence = "This is a sentence that is about sixty characters long, okay.";
    const parts = splitForSpeech(Array(10).fill(sentence).join(" "));
    // 61 + 1 + 61 > MERGE_TARGET_CHARS, so every sentence stands alone.
    expect(parts).toEqual(Array(10).fill(sentence));
    const long = "A".repeat(130) + ".";
    expect(splitForSpeech(`Sure. ${long} Okay.`)).toEqual(["Sure.", long, "Okay."]);
    expect(MERGE_TARGET_CHARS).toBeLessThanOrEqual(MAX_FRAGMENT_CHARS);
    expect(MAX_FRAGMENT_CHARS).toBeLessThanOrEqual(200);
  });

  it("joins only tiny pieces, such as a list marker the sentence regex cuts off", () => {
    expect(splitForSpeech("1. Open the app. 2. Tap Call.")).toEqual(["1. Open the app. 2. Tap Call."]);
  });

  it("splits on sentence boundaries and never exceeds the cap", () => {
    const sentence = "This is a sentence that is about sixty characters long, okay. ";
    const parts = splitForSpeech(sentence.repeat(20));
    expect(parts.length).toBeGreaterThan(1);
    for (const part of parts) expect(part.length).toBeLessThanOrEqual(MAX_FRAGMENT_CHARS);
  });

  it("never drops text - not even one over-long unpunctuated run", () => {
    const words = Array.from({ length: 300 }, (_, i) => `word${i}`).join(" ");
    const parts = splitForSpeech(words, 100);
    for (const part of parts) expect(part.length).toBeLessThanOrEqual(100);
    expect(parts.join(" ")).toBe(words);

    const unbroken = "x".repeat(950);
    const hard = splitForSpeech(unbroken, 400);
    expect(hard.join("")).toBe(unbroken);
    for (const part of hard) expect(part.length).toBeLessThanOrEqual(400);
  });

  it("stays under kokoroWorker's silent 600-character clip", () => {
    expect(MAX_FRAGMENT_CHARS).toBeLessThan(600);
  });
});

describe("plainSpokenExcerpt", () => {
  it("returns short text whole, without markdown symbols or code blocks", () => {
    expect(plainSpokenExcerpt("## Done\n\n**3 rows** updated.\n```sql\nSELECT 1;\n```", 500)).toBe("Done 3 rows updated.");
    expect(plainSpokenExcerpt(null, 500)).toBe("");
  });
  it("cuts a long text at a sentence end within the cap", () => {
    const sentence = "This is a sentence that is about sixty characters long, okay. ";
    const excerpt = plainSpokenExcerpt(sentence.repeat(30), 500);
    expect(excerpt.length).toBeLessThanOrEqual(500);
    expect(excerpt.endsWith("okay.")).toBe(true);
  });
  it("hard-cuts text with no sentence end", () => {
    expect(plainSpokenExcerpt("x".repeat(900), 500)).toHaveLength(500);
  });
});

describe("decideVoiceModelFetch", () => {
  const base: VoiceFetchInput = {
    edgeOn: true,
    kokoroSupported: true,
    kokoroInstalled: false,
    serverVoiceMissing: true,
    callStarted: true,
    alreadyAttempted: false,
    saveData: false,
    effectiveType: "4g",
    cellular: false,
    mobile: false,
    connectionKnown: true,
    replySpeakable: true,
  };

  it("fetches automatically on an unmetered-looking desktop", () => {
    expect(decideVoiceModelFetch(base)).toBe("auto");
    expect(decideVoiceModelFetch({ ...base, effectiveType: null })).toBe("auto");
  });

  it("asks first on anything that looks metered", () => {
    expect(decideVoiceModelFetch({ ...base, saveData: true })).toBe("offer");
    expect(decideVoiceModelFetch({ ...base, effectiveType: "3g" })).toBe("offer");
    expect(decideVoiceModelFetch({ ...base, cellular: true })).toBe("offer");
    expect(decideVoiceModelFetch({ ...base, mobile: true })).toBe("offer");
  });

  it("only offers when the browser reports nothing about the connection (Firefox, Safari)", () => {
    expect(decideVoiceModelFetch({ ...base, connectionKnown: false, effectiveType: null })).toBe("offer");
  });

  it("neither fetches nor offers for a reply Kokoro cannot speak (Hindi, Kannada, Arabic, CJK)", () => {
    expect(decideVoiceModelFetch({ ...base, replySpeakable: false })).toBe("none");
    expect(decideVoiceModelFetch({ ...base, replySpeakable: false, mobile: true })).toBe("none");
  });

  it("never fetches before the person started a call", () => {
    expect(decideVoiceModelFetch({ ...base, callStarted: false })).toBe("none");
  });

  it("never fetches while the server is providing a voice", () => {
    expect(decideVoiceModelFetch({ ...base, serverVoiceMissing: false })).toBe("none");
  });

  it("does nothing when edge is off, unsupported, installed, or already tried", () => {
    expect(decideVoiceModelFetch({ ...base, edgeOn: false })).toBe("none");
    expect(decideVoiceModelFetch({ ...base, kokoroSupported: false })).toBe("none");
    expect(decideVoiceModelFetch({ ...base, kokoroInstalled: true })).toBe("none");
    expect(decideVoiceModelFetch({ ...base, alreadyAttempted: true })).toBe("none");
  });
});
