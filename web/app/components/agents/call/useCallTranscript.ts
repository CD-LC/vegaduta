"use client";

// The running, append-only record of every completed exchange in the CURRENT
// call/hold session (direct report 2026-08-12: lastTranscript/lastReply are
// single "most recent turn" values reset at every hold, so a second exchange
// silently erased the first from the screen).
//
// Capture is keyed on the pipelineBusy true->false edge every push-to-talk
// path funnels through in its `finally`, so no individual send path needs its
// own bookkeeping. Cleared on agent switch and on starting a fresh call, NOT
// on endCall, so a person can still read back what was discussed.

import { useEffect, useRef, useState } from "react";

export type CallTranscriptLine = { role: "user" | "agent"; text: string };

/** Pure: the transcript after one settled exchange is appended. */
export function appendExchange(
  prev: CallTranscriptLine[],
  userText: string | null,
  agentText: string
): CallTranscriptLine[] {
  const next = [...prev];
  if (userText) next.push({ role: "user", text: userText });
  next.push({ role: "agent", text: agentText });
  return next;
}

export function useCallTranscript(pipelineBusy: boolean) {
  const [lastTranscript, setLastTranscript] = useState<string | null>(null);
  const [lastReply, setLastReply] = useState<string | null>(null);
  const [callTranscript, setCallTranscript] = useState<CallTranscriptLine[]>([]);
  // Dedupe key: pipelineBusy can bounce true->false without a NEW reply
  // landing, so the same completed pair must never be captured twice.
  const capturedTurnKeyRef = useRef<string | null>(null);
  // Auto-scroll the height-capped transcript box to the newest line.
  const transcriptScrollRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const el = transcriptScrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [callTranscript, lastTranscript, lastReply]);

  useEffect(() => {
    if (pipelineBusy) return;
    if (!lastReply) return;
    const key = `${lastTranscript || ""}::${lastReply}`;
    if (capturedTurnKeyRef.current === key) return;
    capturedTurnKeyRef.current = key;
    setCallTranscript((prev) => appendExchange(prev, lastTranscript, lastReply));
  }, [pipelineBusy, lastTranscript, lastReply]);

  /** Forget the whole record (agent switch / fresh call). */
  function resetTranscript() {
    setLastTranscript(null);
    setLastReply(null);
    setCallTranscript([]);
    capturedTurnKeyRef.current = null;
  }

  return {
    lastTranscript,
    setLastTranscript,
    lastReply,
    setLastReply,
    callTranscript,
    setCallTranscript,
    capturedTurnKeyRef,
    transcriptScrollRef,
    resetTranscript,
  };
}
