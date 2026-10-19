"use client";

import { useSyncExternalStore } from "react";
import { isSpeaking, subscribeSpeaking } from "./speakText";

/** True while a reply is being read aloud (see speakText.ts). */
export function useSpeaking(): boolean {
  return useSyncExternalStore(subscribeSpeaking, isSpeaking, () => false);
}
