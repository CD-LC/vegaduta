// Browser-side runners (2026-09-19). A fake <audio> element stands in for the
// real one - what is under test is the promise/ownership bookkeeping that
// keeps one shared element from wedging the reply queue.

import { describe, expect, it, vi } from "vitest";

vi.mock("../edge/audio/kokoro", () => ({
  synthesizeSpeechLocally: vi.fn(async () => ({ handled: false, reason: "model-not-ready" })),
}));

import {
  createVoiceRunners,
  pickWebSpeechVoice,
  playSourceOnElement,
  prefetchKokoro,
  silentWavDataUri,
  speakWithWebSpeech,
  stopAllSpeech,
  unlockAudioElement,
} from "./voicePlayers";
import { synthesizeSpeechLocally } from "../edge/audio/kokoro";
import type { StepControl } from "./voiceOutput";

function fakeAudio(playImpl?: () => Promise<void>) {
  const el: any = {
    paused: true,
    muted: false,
    src: "",
    onended: null,
    onerror: null,
    onplaying: null,
    pause: vi.fn(() => {
      el.paused = true;
    }),
    play: vi.fn(() => {
      el.paused = false;
      return playImpl ? playImpl() : Promise.resolve();
    }),
  };
  return el as HTMLAudioElement & { pause: ReturnType<typeof vi.fn>; play: ReturnType<typeof vi.fn> };
}

function control(): StepControl & { cancel(): void; startedCalls: number } {
  const fns: (() => void)[] = [];
  let cancelled = false;
  const c: any = {
    startedCalls: 0,
    started() {
      c.startedCalls++;
    },
    isCancelled: () => cancelled,
    onCancel(fn: () => void) {
      fns.push(fn);
    },
    cancel() {
      cancelled = true;
      fns.forEach((fn) => fn());
    },
  };
  return c;
}

describe("silentWavDataUri", () => {
  it("is a well-formed, silent PCM WAV", () => {
    const uri = silentWavDataUri(8);
    expect(uri.startsWith("data:audio/wav;base64,")).toBe(true);
    const bin = atob(uri.split(",")[1]);
    expect(bin.slice(0, 4)).toBe("RIFF");
    expect(bin.slice(8, 12)).toBe("WAVE");
    expect(bin.slice(36, 40)).toBe("data");
    expect(bin.length).toBe(44 + 16);
    for (let i = 44; i < bin.length; i++) expect(bin.charCodeAt(i)).toBe(0);
  });
});

describe("unlockAudioElement", () => {
  it("plays a muted clip from the gesture and unmutes afterwards", async () => {
    const el = fakeAudio();
    unlockAudioElement(el);
    expect(el.play).toHaveBeenCalledTimes(1);
    expect(el.src.startsWith("data:audio/wav")).toBe(true);
    await Promise.resolve();
    await Promise.resolve();
    expect(el.muted).toBe(false);
  });

  it("leaves a playing element alone, tolerates null, and survives a refused play()", async () => {
    const busy = fakeAudio();
    (busy as any).paused = false;
    unlockAudioElement(busy);
    expect(busy.play).not.toHaveBeenCalled();
    expect(() => unlockAudioElement(null)).not.toThrow();
    const refused = fakeAudio(() => Promise.reject(new Error("NotAllowedError")));
    expect(() => unlockAudioElement(refused)).not.toThrow();
    await Promise.resolve();
    await Promise.resolve();
    expect(refused.muted).toBe(false);
  });
});

describe("playSourceOnElement", () => {
  it("resolves true at ended and reports the start", async () => {
    const el = fakeAudio();
    const c = control();
    const p = playSourceOnElement(el, "blob:x", c);
    (el as any).onplaying();
    expect(c.startedCalls).toBe(1);
    (el as any).onended();
    await expect(p).resolves.toBe(true);
    expect((el as any).onended).toBeNull();
  });

  it("resolves false when autoplay is refused", async () => {
    const el = fakeAudio(() => Promise.reject(new Error("NotAllowedError")));
    await expect(playSourceOnElement(el, "blob:x", control())).resolves.toBe(false);
  });

  it("resolves false on a decode error and runs onSettled exactly once", async () => {
    const el = fakeAudio();
    const settled = vi.fn();
    const p = playSourceOnElement(el, "blob:x", control(), settled);
    (el as any).onerror();
    (el as any).onended?.();
    await expect(p).resolves.toBe(false);
    expect(settled).toHaveBeenCalledTimes(1);
  });

  it("a new playback settles the previous one instead of leaving it hanging", async () => {
    const el = fakeAudio();
    const first = playSourceOnElement(el, "blob:1", control());
    const second = playSourceOnElement(el, "blob:2", control());
    await expect(first).resolves.toBe(false);
    expect(el.src).toBe("blob:2");
    (el as any).onended();
    await expect(second).resolves.toBe(true);
  });

  it("a late cancel of a REPLACED playback never pauses the new audio", async () => {
    const el = fakeAudio();
    const c1 = control();
    void playSourceOnElement(el, "blob:1", c1);
    void playSourceOnElement(el, "blob:2", control());
    el.pause.mockClear();
    c1.cancel();
    expect(el.pause).not.toHaveBeenCalled();
  });

  it("cancel pauses its own playback and resolves false", async () => {
    const el = fakeAudio();
    const c = control();
    const p = playSourceOnElement(el, "blob:1", c);
    c.cancel();
    await expect(p).resolves.toBe(false);
    expect(el.pause).toHaveBeenCalledTimes(1);
  });

  it("stopAllSpeech (barge-in) pauses and settles the pending playback", async () => {
    const el = fakeAudio();
    const p = playSourceOnElement(el, "blob:1", control());
    stopAllSpeech(el);
    await expect(p).resolves.toBe(false);
    expect(el.pause).toHaveBeenCalled();
    expect(() => stopAllSpeech(null)).not.toThrow();
  });
});

describe("createVoiceRunners", () => {
  it("server-audio picks the MIME type from the server's method (Sarvam = WAV)", async () => {
    const el = fakeAudio();
    const runners = createVoiceRunners({ text: "hi", audioBase64: "QUJD", method: "sarvam" }, () => el);
    const p = runners["server-audio"]!(control());
    expect(el.src).toBe("data:audio/wav;base64,QUJD");
    (el as any).onended();
    await expect(p).resolves.toBe(true);
  });

  it("server-audio and kokoro resolve false without an element or a model - never throw", async () => {
    const none = createVoiceRunners({ text: "hi", audioBase64: "QUJD" }, () => null);
    await expect(none["server-audio"]!(control())).resolves.toBe(false);
    await expect(none.kokoro!(control())).resolves.toBe(false);
  });

  it("web-speech resolves false where the browser has no speechSynthesis", async () => {
    const original = (window as any).speechSynthesis;
    try {
      delete (window as any).speechSynthesis;
      const runners = createVoiceRunners({ text: "hi" }, () => null);
      await expect(runners["web-speech"]!(control())).resolves.toBe(false);
    } finally {
      if (original) (window as any).speechSynthesis = original;
    }
  });
});

describe("kokoro prefetch", () => {
  it("plays audio that was synthesized ahead of time instead of synthesizing again", async () => {
    const synth = vi.mocked(synthesizeSpeechLocally);
    synth.mockClear();
    const createUrl = vi.fn(() => "blob:prefetched");
    const revokeUrl = vi.fn();
    const originalCreate = (URL as any).createObjectURL;
    const originalRevoke = (URL as any).revokeObjectURL;
    (URL as any).createObjectURL = createUrl;
    (URL as any).revokeObjectURL = revokeUrl;
    try {
      const el = fakeAudio();
      const kokoroPrefetch = Promise.resolve({
        handled: true as const,
        audioBlob: new Blob(["x"]),
        sampleRate: 24000,
      });
      const runners = createVoiceRunners({ text: "Hello.", kokoroPrefetch }, () => el);
      const p = runners.kokoro!(control());
      await Promise.resolve();
      await Promise.resolve();
      expect(synth).not.toHaveBeenCalled();
      expect(el.src).toBe("blob:prefetched");
      (el as any).onended();
      await expect(p).resolves.toBe(true);
      expect(revokeUrl).toHaveBeenCalledWith("blob:prefetched");
    } finally {
      (URL as any).createObjectURL = originalCreate;
      (URL as any).revokeObjectURL = originalRevoke;
    }
  });

  it("a step cancelled while the synth was pending never touches the element", async () => {
    const el = fakeAudio();
    const c = control();
    let finish: (v: any) => void = () => {};
    const kokoroPrefetch = new Promise<any>((resolve) => (finish = resolve));
    const p = createVoiceRunners({ text: "Hello.", kokoroPrefetch }, () => el).kokoro!(c);
    c.cancel(); // barge-in while Kokoro is still synthesizing
    finish({ handled: true, audioBlob: new Blob(["x"]), sampleRate: 24000 });
    await expect(p).resolves.toBe(false);
    expect(el.play).not.toHaveBeenCalled();
  });

  it("prefetchKokoro never rejects", async () => {
    vi.mocked(synthesizeSpeechLocally).mockRejectedValueOnce(new Error("worker died"));
    await expect(prefetchKokoro("Hello.")).resolves.toEqual({ handled: false, reason: "tts-failed" });
  });
});

describe("Web Speech language", () => {
  const voice = (lang: string, localService = true) => ({ lang, localService, name: lang }) as SpeechSynthesisVoice;

  it("pickWebSpeechVoice: a match, an unlisted engine, and a listed engine with no match", () => {
    const hindi = voice("hi-IN");
    expect(pickWebSpeechVoice([voice("en-US"), hindi], "hi-IN")).toEqual({ voice: hindi });
    expect(pickWebSpeechVoice([voice("hi_IN")], "hi-IN")).toMatchObject({ voice: { lang: "hi_IN" } });
    expect(pickWebSpeechVoice([], "hi-IN")).toEqual({ voice: null });
    expect(pickWebSpeechVoice(null, "hi-IN")).toEqual({ voice: null });
    expect(pickWebSpeechVoice([voice("en-US"), voice("fr-FR")], "hi-IN")).toBe("none");
  });

  function withSpeech(voices: SpeechSynthesisVoice[], run: (spoken: any[]) => Promise<void>) {
    const spoken: any[] = [];
    const originalSynth = (window as any).speechSynthesis;
    const originalUtterance = (globalThis as any).SpeechSynthesisUtterance;
    (window as any).speechSynthesis = {
      getVoices: () => voices,
      cancel: vi.fn(),
      speak: (u: any) => {
        spoken.push(u);
        u.onstart?.();
        u.onend?.();
      },
    };
    (globalThis as any).SpeechSynthesisUtterance = class {
      text: string;
      lang = "";
      voice: any = null;
      constructor(text: string) {
        this.text = text;
      }
    };
    return run(spoken).finally(() => {
      (window as any).speechSynthesis = originalSynth;
      (globalThis as any).SpeechSynthesisUtterance = originalUtterance;
    });
  }

  it("speaks Hindi with a Hindi voice and the language set", () =>
    withSpeech([voice("en-US"), voice("hi-IN")], async (spoken) => {
      const c = control();
      await expect(speakWithWebSpeech("नमस्ते, आप कैसे हैं?", c)).resolves.toBe(true);
      expect(spoken).toHaveLength(1);
      expect(spoken[0].lang).toBe("hi-IN");
      expect(spoken[0].voice?.lang).toBe("hi-IN");
      expect(c.startedCalls).toBe(1);
    }));

  it("refuses - so the text-only notice shows - when the browser lists voices and none is Hindi", () =>
    withSpeech([voice("en-US"), voice("en-GB")], async (spoken) => {
      const c = control();
      await expect(speakWithWebSpeech("नमस्ते, आप कैसे हैं?", c)).resolves.toBe(false);
      expect(spoken).toHaveLength(0);
      expect(c.startedCalls).toBe(0);
    }));

  it("still tries, with the language set, when the engine has not listed its voices", () =>
    withSpeech([], async (spoken) => {
      await expect(speakWithWebSpeech("ನಮಸ್ಕಾರ", control())).resolves.toBe(true);
      expect(spoken[0].lang).toBe("kn-IN");
      expect(spoken[0].voice).toBeNull();
    }));

  it("leaves Latin-script text to the browser's default voice", () =>
    withSpeech([voice("en-US")], async (spoken) => {
      await expect(speakWithWebSpeech("Hello there.", control())).resolves.toBe(true);
      expect(spoken[0].lang).toBe("");
      expect(spoken[0].voice).toBeNull();
    }));
});
