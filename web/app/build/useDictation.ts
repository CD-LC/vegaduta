// @ts-nocheck
"use client";

// Split out of ChatPane.tsx (behaviour unchanged): browser dictation for the
// build chat composer through the Web Speech API. Recognised speech is
// appended to whatever was already typed.
import { useEffect, useRef, useState } from "react";
import { speechLang } from "./suggestions";

/** Web Speech API constructor, when the browser has one (Chrome/Edge/Safari). */
function getSpeechCtor() {
  if (typeof window === "undefined") return null;
  return (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition || null;
}

export function useDictation(instruction, setInstruction) {
  const [speechOk, setSpeechOk] = useState(false);
  const [listening, setListening] = useState(false);
  const [voiceNote, setVoiceNote] = useState("");
  const recRef = useRef(null);

  useEffect(
    () => () => {
      try {
        recRef.current?.abort?.();
      } catch {
        /* ignore */
      }
    },
    []
  );

  useEffect(() => {
    setSpeechOk(!!getSpeechCtor());
  }, []);

  const toggleVoice = () => {
    if (listening) {
      recRef.current?.stop?.();
      return;
    }
    const Ctor = getSpeechCtor();
    if (!Ctor) return;
    const rec = new Ctor();
    rec.lang = speechLang(document.documentElement.lang);
    rec.interimResults = true;
    rec.continuous = false;
    const base = instruction.trim() ? instruction.replace(/\s*$/, " ") : "";
    rec.onresult = (ev) => {
      let text = "";
      for (let i = 0; i < ev.results.length; i++) text += ev.results[i][0].transcript;
      setInstruction(base + text.trim());
    };
    rec.onerror = (ev) => {
      setVoiceNote(
        ev.error === "not-allowed" || ev.error === "service-not-allowed"
          ? "Microphone access is blocked. Allow it in your browser settings to dictate."
          : ev.error === "no-speech"
            ? "Didn't catch anything - try again."
            : `Dictation stopped (${ev.error}).`
      );
    };
    rec.onend = () => {
      setListening(false);
      recRef.current = null;
    };
    setVoiceNote("");
    try {
      rec.start();
      recRef.current = rec;
      setListening(true);
    } catch {
      setVoiceNote("Couldn't start dictation.");
    }
  };

  const stopDictation = () => recRef.current?.stop?.();

  return { speechOk, listening, voiceNote, toggleVoice, stopDictation };
}
