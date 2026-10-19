import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../../lib/edge/audio/kokoro", () => ({ synthesizeSpeechLocally: vi.fn(async () => ({ handled: false })) }));

import { isSpeaking, speakText, stopSpeaking, subscribeSpeaking } from "./speakText";

describe("speaking state", () => {
  const utterances: { onend?: () => void }[] = [];
  const synth = { speak: vi.fn((u: { onend?: () => void }) => utterances.push(u)), cancel: vi.fn(), resume: vi.fn(), speaking: false };

  beforeEach(() => {
    utterances.length = 0;
    vi.stubGlobal("speechSynthesis", synth);
    vi.stubGlobal("SpeechSynthesisUtterance", class { constructor(public text: string) {} onend?: () => void; onerror?: () => void; rate = 1 });
    (window as unknown as { speechSynthesis: unknown }).speechSynthesis = synth;
    synth.speak.mockClear();
    synth.cancel.mockClear();
  });
  afterEach(() => {
    stopSpeaking();
    vi.unstubAllGlobals();
  });

  it("reports speaking from the start of a reply until the last utterance ends", async () => {
    const seen: boolean[] = [];
    const off = subscribeSpeaking(() => seen.push(isSpeaking()));
    await speakText("Hello there. This is a reply.");
    expect(isSpeaking()).toBe(true);
    utterances[utterances.length - 1].onend?.();
    expect(isSpeaking()).toBe(false);
    expect(seen).toEqual([true, false]);
    off();
  });

  it("a delayed cancel from the previous stop does not kill a reply that has just started", async () => {
    vi.useFakeTimers();
    try {
      await speakText("First reply.");
      await speakText("Second reply starts right after.");
      synth.cancel.mockClear();
      vi.advanceTimersByTime(200);
      expect(synth.cancel).not.toHaveBeenCalled();
      expect(isSpeaking()).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it("stopSpeaking silences it at once, even before the first sound", async () => {
    await speakText("A reply that is playing.");
    expect(isSpeaking()).toBe(true);
    stopSpeaking();
    expect(isSpeaking()).toBe(false);
    expect(synth.cancel).toHaveBeenCalled();
    expect(synth.resume).toHaveBeenCalled();
  });
});
