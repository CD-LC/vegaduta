"use client";

// Dictation for the question box via the Web Speech API. Feature-detected: on
// browsers without it (Firefox, some in-app webviews) `supported` is false and
// the page shows no microphone button. Nothing is sent anywhere by this code;
// the browser's own speech service does the recognition.

import { useCallback, useEffect, useRef, useState } from "react";

/** Join a spoken chunk onto existing text with exactly one space and no doubled blanks. */
export function appendTranscript(existing: string, spoken: string): string {
  const add = spoken.trim();
  if (!add) return existing;
  if (!existing.trim()) return add;
  return `${existing.replace(/\s+$/, "")} ${add}`;
}

function recognitionCtor(): any {
  if (typeof window === "undefined") return null;
  const w = window as any;
  return w.SpeechRecognition || w.webkitSpeechRecognition || null;
}

export function useDictation(onFinalText: (text: string) => void) {
  const [supported, setSupported] = useState(false);
  const [listening, setListening] = useState(false);
  const [failed, setFailed] = useState(false);
  const recRef = useRef<any>(null);
  const cb = useRef(onFinalText);
  useEffect(() => {
    cb.current = onFinalText;
  });

  useEffect(() => {
    setSupported(!!recognitionCtor());
    return () => {
      try {
        recRef.current?.abort();
      } catch {
        // already stopped
      }
    };
  }, []);

  const stop = useCallback(() => {
    try {
      recRef.current?.stop();
    } catch {
      // already stopped
    }
  }, []);

  const start = useCallback(() => {
    const Ctor = recognitionCtor();
    if (!Ctor) return;
    setFailed(false);
    const rec = new Ctor();
    rec.lang = typeof navigator !== "undefined" && navigator.language ? navigator.language : "en-US";
    rec.interimResults = false;
    rec.continuous = true;
    rec.onresult = (e: any) => {
      for (let i = e.resultIndex; i < e.results.length; i++) {
        if (e.results[i].isFinal) cb.current(String(e.results[i][0]?.transcript ?? ""));
      }
    };
    rec.onerror = () => {
      setFailed(true);
      setListening(false);
    };
    rec.onend = () => setListening(false);
    recRef.current = rec;
    try {
      rec.start();
      setListening(true);
    } catch {
      setFailed(true);
      setListening(false);
    }
  }, []);

  return { supported, listening, failed, start, stop };
}
