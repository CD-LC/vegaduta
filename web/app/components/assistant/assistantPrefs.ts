"use client";

// Per-browser Assistant preferences (localStorage): whether the reasoning
// stream is shown and whether replies are read aloud. Both default OFF —
// reasoning is the model's private chain-of-thought and voice is opt-in.
// Components stay in sync through a window CustomEvent (same tab) plus the
// storage event (other tabs).

import { useCallback, useEffect, useState } from "react";

const STORAGE_KEY = "assistant.prefs.v1";
const CHANGE_EVENT = "assistant:prefs-changed";

export interface AssistantPrefs {
  showThinking: boolean;
  speakReplies: boolean;
  /** Desktop only: the thread list / work rail folded away. Below lg/xl they are overlays anyway. */
  leftCollapsed: boolean;
  rightCollapsed: boolean;
}

// Reasoning is shown by default: on a local open-weight model the thinking stream is often the
// only sign of life for the first half minute of a turn, and hiding it is what made the surface
// look hung. The header toggle still turns it off, and a stored choice wins.
const DEFAULTS: AssistantPrefs = { showThinking: true, speakReplies: false, leftCollapsed: false, rightCollapsed: false };

export function readAssistantPrefs(): AssistantPrefs {
  if (typeof window === "undefined") return DEFAULTS;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return DEFAULTS;
    const parsed = JSON.parse(raw) as Partial<AssistantPrefs>;
    return {
      showThinking: typeof parsed.showThinking === "boolean" ? parsed.showThinking : DEFAULTS.showThinking,
      speakReplies: parsed.speakReplies === true,
      leftCollapsed: parsed.leftCollapsed === true,
      rightCollapsed: parsed.rightCollapsed === true,
    };
  } catch {
    return DEFAULTS;
  }
}

export function writeAssistantPrefs(patch: Partial<AssistantPrefs>): AssistantPrefs {
  const next = { ...readAssistantPrefs(), ...patch };
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    // Private-mode storage denial — the in-memory state still updates below.
  }
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT));
  return next;
}

export function useAssistantPrefs(): [AssistantPrefs, (patch: Partial<AssistantPrefs>) => void] {
  const [prefs, setPrefs] = useState<AssistantPrefs>(DEFAULTS);
  useEffect(() => {
    setPrefs(readAssistantPrefs());
    const refresh = () => setPrefs(readAssistantPrefs());
    window.addEventListener(CHANGE_EVENT, refresh);
    window.addEventListener("storage", refresh);
    return () => {
      window.removeEventListener(CHANGE_EVENT, refresh);
      window.removeEventListener("storage", refresh);
    };
  }, []);
  const update = useCallback((patch: Partial<AssistantPrefs>) => {
    setPrefs(writeAssistantPrefs(patch));
  }, []);
  return [prefs, update];
}
