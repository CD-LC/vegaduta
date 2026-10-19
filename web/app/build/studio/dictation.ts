"use client";

// Dictation for the Studio request box, via the browser's Web Speech API.
// Feature-detected: where it is missing (Firefox, most WebViews) `supported`
// stays false and the UI hides the mic entirely. In Chrome/Edge/Safari the
// audio is processed by the BROWSER's speech service, not by VegaDuta - the
// button's tooltip says so.

import { useCallback, useEffect, useRef, useState } from "react";

interface RecognitionResultLike {
  isFinal: boolean;
  0: { transcript: string };
}
interface RecognitionEventLike {
  resultIndex: number;
  results: ArrayLike<RecognitionResultLike>;
}
interface RecognitionLike {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((e: RecognitionEventLike) => void) | null;
  onerror: ((e: { error?: string }) => void) | null;
  onend: (() => void) | null;
  start: () => void;
  stop: () => void;
  abort: () => void;
}
type RecognitionCtor = new () => RecognitionLike;

function ctor(): RecognitionCtor | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor };
  return w.SpeechRecognition || w.webkitSpeechRecognition || null;
}

const ERRORS: Record<string, string> = {
  "not-allowed": "Microphone access was blocked. Allow it in the browser's site settings to dictate.",
  "service-not-allowed": "Dictation is not allowed in this browser.",
  "no-speech": "I did not hear anything. Try again.",
  "audio-capture": "No microphone was found.",
  network: "Dictation needs a network connection in this browser.",
};

/** `onText` receives each FINAL phrase; the caller appends it to its box. */
export function useDictation(onText: (text: string) => void, lang?: string) {
  const [supported, setSupported] = useState(false);
  const [listening, setListening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const rec = useRef<RecognitionLike | null>(null);
  const cb = useRef(onText);
  cb.current = onText;

  useEffect(() => {
    setSupported(ctor() !== null);
    return () => rec.current?.abort();
  }, []);

  const stop = useCallback(() => rec.current?.stop(), []);

  const start = useCallback(() => {
    const C = ctor();
    if (!C || rec.current) return;
    setError(null);
    const r = new C();
    r.lang = lang || (typeof navigator !== "undefined" && navigator.language) || "en-US";
    r.continuous = false;
    r.interimResults = false;
    r.onresult = (e) => {
      let text = "";
      for (let i = e.resultIndex; i < e.results.length; i++) if (e.results[i].isFinal) text += e.results[i][0].transcript;
      text = text.trim();
      if (text) cb.current(text);
    };
    r.onerror = (e) => setError(ERRORS[e.error || ""] || "Dictation stopped unexpectedly.");
    r.onend = () => {
      rec.current = null;
      setListening(false);
    };
    rec.current = r;
    try {
      r.start();
      setListening(true);
    } catch {
      rec.current = null;
      setError("Could not start dictation.");
    }
  }, [lang]);

  return { supported, listening, error, start, stop };
}
