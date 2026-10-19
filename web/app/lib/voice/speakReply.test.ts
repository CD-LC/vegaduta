// speakReply (2026-09-19): the one-call wrapper the playground's read-aloud,
// the camera panel and the two public share pages use. What matters here is
// ORDER, what is never done (a download, a second voice after a stop), and
// that it never rejects.

import { beforeEach, describe, expect, it, vi } from "vitest";

const state = vi.hoisted(() => ({
  edgeOn: true,
  optIn: false,
  installed: true,
  filesPresent: true,
  webSpeech: true,
  /** step -> what that voice does for a fragment */
  behaviour: {} as Record<string, (text: string) => boolean | "hang">,
  calls: [] as string[],
  prefetched: [] as string[],
}));

vi.mock("../edge/capabilities", () => ({
  edgeEnabled: () => state.edgeOn,
  getEdgeOptIn: () => state.optIn,
}));
vi.mock("../edge/audio/kokoro", () => ({
  isTtsModelCached: () => state.installed,
  ttsModelFilesPresent: vi.fn(async () => state.filesPresent),
}));
vi.mock("./voicePlayers", () => ({
  webSpeechPresent: () => state.webSpeech,
  prefetchKokoro: (text: string) => {
    state.prefetched.push(text);
    return Promise.resolve({ handled: false, reason: "test" });
  },
  createVoiceRunners: (fragment: { text: string }) => {
    const make = (step: string) => (control: { started(): void; isCancelled(): boolean }) => {
      state.calls.push(`${step}:${fragment.text}`);
      const outcome = (state.behaviour[step] || (() => false))(fragment.text);
      if (outcome === "hang") return new Promise<boolean>(() => {});
      if (outcome) control.started();
      return Promise.resolve(outcome);
    };
    return { "server-audio": make("server-audio"), kokoro: make("kokoro"), "web-speech": make("web-speech") };
  },
}));

import { kokoroReadyFor, speakReply } from "./speakReply";
import { ttsModelFilesPresent } from "../edge/audio/kokoro";

const el = {} as HTMLAudioElement;
const getAudioEl = () => el;

beforeEach(() => {
  state.edgeOn = true;
  state.optIn = false;
  state.installed = true;
  state.filesPresent = true;
  state.webSpeech = true;
  state.behaviour = {};
  state.calls = [];
  state.prefetched = [];
  vi.mocked(ttsModelFilesPresent).mockClear();
});

describe("speakReply", () => {
  it("plays the server's audio when there is some, and nothing else", async () => {
    state.behaviour["server-audio"] = () => true;
    const onStart = vi.fn();
    const result = await speakReply({ text: "Hello there.", audioBase64: "QUJD", method: "sarvam", getAudioEl, onStart });
    expect(result).toEqual({ spokenBy: "server-audio", aborted: false });
    expect(state.calls).toEqual(["server-audio:Hello there."]);
    expect(onStart).toHaveBeenCalledTimes(1);
    expect(onStart).toHaveBeenCalledWith("server-audio");
  });

  it("uses an installed Kokoro when the reply has no server audio - Kokoro is the default voice", async () => {
    state.behaviour.kokoro = () => true;
    const result = await speakReply({ text: "Hello there.", getAudioEl });
    expect(result.spokenBy).toBe("kokoro");
    expect(state.calls).toEqual(["kokoro:Hello there."]);
  });

  it("falls back from failed server audio to Kokoro, then to the browser voice", async () => {
    state.behaviour["web-speech"] = () => true;
    const result = await speakReply({ text: "Hello there.", audioBase64: "QUJD", getAudioEl });
    expect(result.spokenBy).toBe("web-speech");
    expect(state.calls).toEqual(["server-audio:Hello there.", "kokoro:Hello there.", "web-speech:Hello there."]);
  });

  it("keeps Kokoro first for someone who turned on-device replies on, with server audio as the last resort", async () => {
    state.optIn = true;
    state.behaviour["server-audio"] = () => true;
    const result = await speakReply({ text: "Hello there.", audioBase64: "QUJD", getAudioEl });
    expect(result.spokenBy).toBe("server-audio");
    expect(state.calls).toEqual(["kokoro:Hello there.", "web-speech:Hello there.", "server-audio:Hello there."]);
  });

  it("never hands a non-Latin reply to the English-only Kokoro", async () => {
    state.behaviour["web-speech"] = () => true;
    await speakReply({ text: "नमस्ते, आप कैसे हैं?", getAudioEl });
    expect(state.calls).toEqual(["web-speech:नमस्ते, आप कैसे हैं?"]);
    expect(kokoroReadyFor("नमस्ते")).toBe(false);
    expect(kokoroReadyFor("Hello")).toBe(true);
  });

  it("speaks a long reply in short fragments and prepares the next one ahead", async () => {
    state.behaviour.kokoro = () => true;
    const sentence = "This is a sentence that is about sixty characters long, okay.";
    await speakReply({ text: `${sentence} ${sentence} ${sentence}`, getAudioEl });
    expect(state.calls).toEqual([`kokoro:${sentence}`, `kokoro:${sentence}`, `kokoro:${sentence}`]);
    expect(state.prefetched).toEqual([sentence, sentence]);
  });

  it("`voices` restricts the call (the playground's Kokoro-only first pass)", async () => {
    state.behaviour["web-speech"] = () => true;
    const result = await speakReply({ text: "Hello there.", audioBase64: "QUJD", getAudioEl, voices: ["kokoro"] });
    expect(result).toEqual({ spokenBy: null, aborted: false });
    expect(state.calls).toEqual(["kokoro:Hello there."]);
  });

  it("neverFetch: skips Kokoro when its files are not verifiably in the browser cache", async () => {
    state.filesPresent = false;
    state.behaviour["web-speech"] = () => true;
    const result = await speakReply({ text: "Hello there.", getAudioEl, neverFetch: true });
    expect(ttsModelFilesPresent).toHaveBeenCalledTimes(1);
    expect(result.spokenBy).toBe("web-speech");
    expect(state.calls).toEqual(["web-speech:Hello there."]);
    expect(state.prefetched).toEqual([]);
  });

  it("neverFetch: uses Kokoro when the files are there; without the flag the cache is not consulted", async () => {
    state.behaviour.kokoro = () => true;
    expect((await speakReply({ text: "Hello there.", getAudioEl, neverFetch: true })).spokenBy).toBe("kokoro");
    vi.mocked(ttsModelFilesPresent).mockClear();
    await speakReply({ text: "Hello there.", getAudioEl });
    expect(ttsModelFilesPresent).not.toHaveBeenCalled();
  });

  it("does nothing when already stale, and stops instead of trying another voice once stopped", async () => {
    expect(await speakReply({ text: "Hello.", getAudioEl, isStale: () => true })).toEqual({
      spokenBy: null,
      aborted: true,
    });
    expect(state.calls).toEqual([]);

    vi.useFakeTimers();
    try {
      let stale = false;
      state.behaviour.kokoro = () => "hang";
      state.behaviour["web-speech"] = () => true;
      const pending = speakReply({ text: "Hello.", getAudioEl, isStale: () => stale });
      await vi.advanceTimersByTimeAsync(1_000);
      stale = true; // the person pressed stop while Kokoro was synthesizing
      await vi.advanceTimersByTimeAsync(200);
      expect(await pending).toEqual({ spokenBy: null, aborted: true });
      expect(state.calls).toEqual(["kokoro:Hello."]);
    } finally {
      vi.useRealTimers();
    }
  });

  it("resolves text-only, never rejects, when nothing can speak or a dependency throws", async () => {
    state.webSpeech = false;
    state.installed = false;
    expect(await speakReply({ text: "Hello.", getAudioEl })).toEqual({ spokenBy: null, aborted: false });
    expect(await speakReply({ text: "", getAudioEl })).toEqual({ spokenBy: null, aborted: false });
    await expect(
      speakReply({
        text: "Hello.",
        getAudioEl: () => {
          throw new Error("ref gone");
        },
        onStart: () => {
          throw new Error("ui");
        },
      })
    ).resolves.toEqual({ spokenBy: null, aborted: false });
  });
});
