"use client";

// The share page's LiveKit live call, lifted out of ShareCallPanel.tsx
// (file-size ratchet) - a plain async function taking the panel's deps,
// same contract the in-component version had. POST .../voice/token mints a
// share-scoped join token (shareId is the only credential), the room
// carries the mic and optional camera, and each turn's transcript + stage
// arrive over the "call-transcript" / "agent-stage" data topics. Agent
// lines the worker could not voice (voiced:false - no server-side TTS key)
// are spoken by the on-device/browser voices through speakAgentLine,
// serialized so consecutive replies never overlap.

import { Room, RoomEvent, Track } from "livekit-client";
import type { getShareVoiceToken } from "./api";
import type { MutableRefObject, RefObject } from "react";
import type { ShareStage } from "./shareTurn";

export interface ShareLiveCallDeps {
  shareId: string;
  mode: "idle" | "push-to-talk" | "call";
  callStatus: "idle" | "connecting" | "connected" | "error";
  getVoiceToken: typeof getShareVoiceToken;
  roomRef: MutableRefObject<Room | null>;
  remoteAudioContainerRef: RefObject<HTMLDivElement | null>;
  agentAudioMutedRef: MutableRefObject<boolean>;
  liveTranscriptIdsRef: MutableRefObject<Set<string>>;
  speakAgentLine: (text: string) => Promise<void>;
  appendCallLog: (entry: { role: "user" | "agent"; text: string }) => void;
  setError: (e: string | null) => void;
  setMode: (m: "idle" | "push-to-talk" | "call") => void;
  setCallStatus: (s: "idle" | "connecting" | "connected" | "error") => void;
  setAgentAudioMuted: (m: boolean) => void;
  setCameraOn: (on: boolean) => void;
  setStage: (s: ShareStage) => void;
}

export async function startShareLiveCall(deps: ShareLiveCallDeps, withCamera: boolean) {
  const {
    shareId,
    mode,
    callStatus,
    getVoiceToken,
    roomRef,
    remoteAudioContainerRef,
    agentAudioMutedRef,
    liveTranscriptIdsRef,
    speakAgentLine,
    appendCallLog,
    setError,
    setMode,
    setCallStatus,
    setAgentAudioMuted,
    setCameraOn,
    setStage,
  } = deps;

  if (mode === "push-to-talk" || callStatus === "connecting" || callStatus === "connected") return;
  setError(null);
  setMode("call");
  setCallStatus("connecting");
  agentAudioMutedRef.current = false;
  setAgentAudioMuted(false);
  liveTranscriptIdsRef.current.clear();
  try {
    const result = await getVoiceToken(shareId, withCamera ? "sight" : "voice");
    if (!result.ok || !result.voice) {
      setError(
        result.error === "rate_limited" ? "Too many calls right now - try again shortly." : "Couldn't start the call."
      );
      setCallStatus("error");
      setMode("idle");
      return;
    }
    const room = new Room();
    room.on(RoomEvent.Disconnected, () => {
      roomRef.current = null;
      setCallStatus("idle");
      setCameraOn(false);
      setMode("idle");
      if (remoteAudioContainerRef.current) remoteAudioContainerRef.current.innerHTML = "";
    });
    room.on(RoomEvent.TrackSubscribed, (track) => {
      if (track.kind === Track.Kind.Audio) {
        const el = track.attach();
        el.autoplay = true;
        el.muted = agentAudioMutedRef.current;
        remoteAudioContainerRef.current?.appendChild(el);
      }
    });
    room.on(RoomEvent.TrackUnsubscribed, (track) => {
      track.detach().forEach((el) => el.remove());
    });
    // Serializes locally-spoken agent replies (the `voiced:false` path in
    // the call-transcript handler below) - one speakShareLine at a time.
    let localSpeakChain: Promise<unknown> = Promise.resolve();
    // Retained call transcript + stage over the data channel: the
    // voice-agent worker publishes each turn's user/agent text on
    // "call-transcript" and lifecycle stages on "agent-stage" - "the
    // response in voice keeps its text on screen too". Deduped by the
    // event id the worker mints.
    room.on(RoomEvent.DataReceived, (payload, _participant, _kind, topic) => {
      try {
        const decoded = JSON.parse(new TextDecoder().decode(payload));
        if (topic === "call-transcript") {
          const role = decoded?.role === "user" || decoded?.role === "agent" ? decoded.role : null;
          const text = typeof decoded?.text === "string" ? decoded.text.trim() : "";
          const id = typeof decoded?.id === "string" ? decoded.id : `${decoded?.turnId ?? "?"}:${role}`;
          if (!role || !text || liveTranscriptIdsRef.current.has(id)) return;
          liveTranscriptIdsRef.current.add(id);
          appendCallLog({ role, text });
          // `voiced:false` = the worker has no server-side TTS key, so
          // the reply text is all there is - speak it through the same
          // on-device/browser chain the replay button uses (free, no
          // key needed). Chained so consecutive replies never overlap.
          if (role === "agent" && decoded.voiced === false) {
            localSpeakChain = localSpeakChain.then(() => speakAgentLine(text));
          }
        } else if (topic === "agent-stage") {
          const s = decoded?.stage;
          if (s === "listening") setStage("listening");
          else if (s === "understanding" || s === "generating") setStage("thinking");
          else if (s === "speaking") setStage("speaking");
          else if (s === "idle") setStage("idle");
          if (decoded?.error) setError(String(decoded.error));
        }
      } catch {
        // Malformed data message - ignore.
      }
    });
    await room.connect(result.voice.url, result.voice.token);
    roomRef.current = room;
    await room.localParticipant.setMicrophoneEnabled(true);
    if (withCamera) {
      try {
        await room.localParticipant.setCameraEnabled(true);
        setCameraOn(true);
      } catch {
        // Camera denial degrades to a working voice-only call.
      }
    }
    setCallStatus("connected");
  } catch (err: any) {
    setError(err?.message || "Couldn't start the call.");
    setCallStatus("error");
    setMode("idle");
  }
}

export function endShareLiveCall(deps: ShareLiveCallDeps) {
  const { roomRef, setCallStatus, setCameraOn, setMode } = deps;
  void roomRef.current?.disconnect();
  roomRef.current = null;
  setCallStatus("idle");
  setCameraOn(false);
  setMode("idle");
}
