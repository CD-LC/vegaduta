"use client";

// The share page's ON-DEVICE voice turn: the recording never leaves the
// visitor's browser. whisper (lib/edge/audio/whisper) turns it into text on
// the device, that text goes through the ordinary shared chat endpoint
// (POST .../chat, same call the composer makes), and the reply is spoken by
// the on-device voice (kokoro) or the browser's built-in voice via
// speakShareLine.
//
// Why this exists: the server voice pipeline (STT sidecar + LiveKit worker
// + server TTS) needs deployment pieces a shared-link visitor cannot fix or
// wait for. When the two small models are on this device, voice works even
// when every server-side speech leg is down - and no audio ever crosses the
// wire, only the transcript text.
//
// Contract: returns true when the local path handled the turn end-to-end.
// Any miss - model absent, transcription came back empty/unreadable, the
// chat call failed - resolves false and the caller falls back to the server
// voice/turn path unchanged, exactly like localTurn.ts's routing in the
// authenticated console. NEVER throws and NEVER downloads a model.

import { transcribeAudioLocally } from "../../lib/edge/audio/whisper";
import { speakShareLine } from "./shareTurn";
import type { sendSharedChat } from "./api";
import type { MutableRefObject, RefObject } from "react";
import type { ShareStage } from "./shareTurn";

export interface ShareLocalTurnDeps {
  shareId: string;
  unmountedRef: MutableRefObject<boolean>;
  voiceEpochRef: MutableRefObject<number>;
  repliesMutedRef: MutableRefObject<boolean>;
  audioElRef: RefObject<HTMLAudioElement | null>;
  sendChat: typeof sendSharedChat;
  setStage: (s: ShareStage) => void;
  setTranscript: (t: string | null) => void;
  setReply: (r: string) => void;
  appendCallLog: (entry: { role: "user" | "agent"; text: string }) => void;
  setMode: (m: "idle" | "push-to-talk" | "call") => void;
}

export async function sendLocalShareTurn(deps: ShareLocalTurnDeps, blob: Blob): Promise<boolean> {
  const {
    shareId,
    unmountedRef,
    voiceEpochRef,
    repliesMutedRef,
    audioElRef,
    sendChat,
    setStage,
    setTranscript,
    setReply,
    appendCallLog,
    setMode,
  } = deps;

  const epoch = voiceEpochRef.current;
  const isStale = () => unmountedRef.current || voiceEpochRef.current !== epoch;

  setStage("thinking");
  setTranscript(null);
  setReply("");

  const local = await transcribeAudioLocally(blob);
  if (isStale()) return true; // the turn is abandoned, not "unhandled"
  if (!local.handled || !local.text.trim()) return false;

  const transcript = local.text.trim();
  setTranscript(transcript);

  const result = await sendChat(shareId, transcript);
  if (isStale()) return true;
  if (!result.ok || typeof result.reply !== "string" || !result.reply.trim()) {
    // Let the server voice path try the same audio before we give up.
    return false;
  }

  appendCallLog({ role: "user", text: transcript });
  const reply = result.reply.trim();
  setReply(reply);
  appendCallLog({ role: "agent", text: reply });

  if (repliesMutedRef.current) {
    setStage("idle");
    setMode("idle");
    return true;
  }
  setStage("speaking");
  await speakShareLine(reply, () => audioElRef.current, isStale);
  if (!isStale()) {
    setStage("idle");
    setMode("idle");
  }
  return true;
}
