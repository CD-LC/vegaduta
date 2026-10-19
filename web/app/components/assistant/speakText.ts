"use client";

// Speaks assistant replies aloud. On-device Kokoro first (when the person
// installed the model — synthesizeSpeechLocally answers {handled:false}
// without downloading anything); the browser's built-in speechSynthesis is
// the fallback so the control works on every device. Only one playback runs
// at a time — starting a new one (or stopSpeaking) cancels the old.

import { synthesizeSpeechLocally } from "../../lib/edge/audio/kokoro";
import { speakableText } from "./replyText";

// One Kokoro synth call is a short-utterance path (the worker clips at 600
// chars); replies can be longer, so they are spoken in sentence chunks.
const CHUNK_CHARS = 480;
const GENERATE_CLIP_HINT = 600;

let audio: HTMLAudioElement | null = null;
let audioUrl: string | null = null;
let generation = 0;
// True from the moment a reply starts being spoken (including while the voice is still being generated and
// between chunks) until it ends or is stopped. audio/speechSynthesis alone miss those gaps.
let active = false;
// Bumped each time a new reply starts, so a delayed cancel from an earlier stop never kills it.
let session = 0;
const listeners = new Set<() => void>();

function setActive(next: boolean): void {
  if (active === next) return;
  active = next;
  listeners.forEach((l) => l());
}

/** useSyncExternalStore pair: re-render when speech starts or stops. */
export function subscribeSpeaking(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function releaseAudio(): void {
  if (audio) {
    try {
      audio.pause();
    } catch {
      // Best-effort.
    }
    audio = null;
  }
  if (audioUrl) {
    try {
      URL.revokeObjectURL(audioUrl);
    } catch {
      // Best-effort.
    }
    audioUrl = null;
  }
}

export function stopSpeaking(): void {
  generation += 1;
  releaseAudio();
  setActive(false);
  try {
    const synth = window.speechSynthesis;
    // Chrome can ignore cancel() while paused or while an utterance is starting: resume first, cancel, and
    // cancel once more on the next tick to catch an utterance that was just handed over.
    const stoppedSession = session;
    synth?.resume();
    synth?.cancel();
    setTimeout(() => {
      if (stoppedSession !== session) return; // a new reply has started since: leave it alone
      try {
        synth?.cancel();
      } catch {
        // Best-effort.
      }
    }, 50);
  } catch {
    // Best-effort.
  }
}

export function isSpeaking(): boolean {
  if (active) return true;
  if (audio && !audio.paused && !audio.ended) return true;
  try {
    return !!window.speechSynthesis?.speaking;
  } catch {
    return false;
  }
}

/** Split into speakable pieces at sentence/line boundaries. */
export function splitForSpeech(text: string, maxChars = CHUNK_CHARS): string[] {
  const clean = (text || "").replace(/\s+/g, " ").trim();
  if (!clean) return [];
  const pieces: string[] = [];
  let rest = clean;
  while (rest.length > maxChars) {
    let cut = rest.lastIndexOf(". ", maxChars);
    for (const mark of ["? ", "! ", "; ", ", ", ": "]) {
      const at = rest.lastIndexOf(mark, maxChars);
      if (at > cut) cut = at;
    }
    if (cut < maxChars * 0.4) cut = maxChars; // No boundary nearby — hard-split.
    pieces.push(rest.slice(0, cut + 1).trim());
    rest = rest.slice(cut + 1).trim();
  }
  if (rest) pieces.push(rest);
  return pieces;
}

async function speakChunkOnDevice(text: string, gen: number): Promise<boolean> {
  const res = await synthesizeSpeechLocally(text.slice(0, GENERATE_CLIP_HINT * 4));
  if (!res.handled || !res.audioBlob || gen !== generation) return res.handled;
  return await new Promise<boolean>((resolve) => {
    const url = URL.createObjectURL(res.audioBlob as Blob);
    const el = new Audio(url);
    audio = el;
    audioUrl = url;
    const done = (ok: boolean) => {
      if (audio === el) {
        audio = null;
        audioUrl = null;
      }
      try {
        URL.revokeObjectURL(url);
      } catch {
        // Best-effort.
      }
      resolve(ok && gen === generation);
    };
    el.onended = () => done(true);
    el.onerror = () => done(false);
    el.play().then(
      () => {},
      () => done(false)
    );
  });
}

function speakWithBrowser(text: string, gen: number, last: boolean): void {
  try {
    if (!window.speechSynthesis || gen !== generation) return;
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.rate = 1;
    if (last) {
      const finish = () => {
        if (gen === generation) setActive(false);
      };
      utterance.onend = finish;
      utterance.onerror = finish;
    }
    window.speechSynthesis.speak(utterance);
  } catch {
    // speechSynthesis can throw where it is stubbed — nothing further to do.
  }
}

/** Speak `text`; resolves when playback finishes or is cancelled. */
export async function speakText(text: string): Promise<void> {
  if (typeof window === "undefined") return;
  stopSpeaking();
  session += 1;
  const gen = generation;
  const chunks = splitForSpeech(speakableText(text));
  if (chunks.length === 0) return;
  setActive(true);
  let onDevice = true;
  let spokeWithBrowser = false;
  for (let i = 0; i < chunks.length; i++) {
    if (gen !== generation) return;
    if (onDevice) {
      onDevice = await speakChunkOnDevice(chunks[i], gen);
      if (onDevice) continue;
      if (gen !== generation) return;
    }
    // Browser TTS queues utterances itself; every remaining chunk goes in (it used to stop after
    // the first, so long replies were read only up to about 480 characters).
    speakWithBrowser(chunks[i], gen, i === chunks.length - 1);
    spokeWithBrowser = true;
  }
  // On-device playback is awaited chunk by chunk, so it is over here; browser speech ends on its own event.
  if (gen === generation && !spokeWithBrowser) setActive(false);
}
