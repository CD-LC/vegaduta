// The thin wrappers CallPanel puts around the call/ module's *Impl functions:
// each binds the render's CallCtx so the JSX handlers read as one-argument
// actions (startCall(withCamera), toggleMute(), ...). Split out for the
// line-count cap; behaviour lives in the impls. Two of them (endCall,
// sendTurnViaLocalStt) are handed to hooks BEFORE ctx is built, so CallPanel
// keeps those as hoisted declarations and they are not repeated here.
//
// The getter is lazy: the actions' *Impl calls all take ctx at invocation
// time, so ctx is read when the person clicks, not when the panel renders.

import type { CallCtx } from "./callContext";
import {
  startCallImpl,
  toggleCameraImpl,
  toggleScreenShareImpl,
  toggleMuteImpl,
  toggleAgentAudioImpl,
} from "./liveCall";
import { startPushToTalkImpl, stopPushToTalkImpl } from "./pushToTalk";
import {
  primeVoiceOutputFromGestureImpl,
  speakFragmentImpl,
  kokoroWouldSpeakImpl,
  speakWholeReplyImpl,
  makeAudioChunkQueueImpl,
} from "./voiceActions";
import { trySendTurnLocallyImpl, sendHostedTextTurnImpl } from "./localTurn";
import { sendTurnImpl } from "./serverTurn";

export function bindCallActions(getCtx: () => CallCtx) {
  return {
    startCall: (withCamera = false) => startCallImpl(getCtx(), withCamera),
    toggleCamera: () => toggleCameraImpl(getCtx()),
    toggleScreenShare: () => toggleScreenShareImpl(getCtx()),
    toggleMute: () => toggleMuteImpl(getCtx()),
    toggleAgentAudio: () => toggleAgentAudioImpl(getCtx()),
    startPushToTalk: () => startPushToTalkImpl(getCtx()),
    stopPushToTalk: () => stopPushToTalkImpl(getCtx()),
    primeVoiceOutputFromGesture: () => primeVoiceOutputFromGestureImpl(getCtx()),
    speakFragment: (...args: Parameters<CallCtx["speakFragment"]>) => speakFragmentImpl(getCtx(), ...args),
    kokoroWouldSpeak: (text: string | null | undefined) => kokoroWouldSpeakImpl(getCtx(), text),
    speakWholeReply: (...args: Parameters<CallCtx["speakWholeReply"]>) => speakWholeReplyImpl(getCtx(), ...args),
    makeAudioChunkQueue: (callbacks: Parameters<CallCtx["makeAudioChunkQueue"]>[0] = {}) =>
      makeAudioChunkQueueImpl(getCtx(), callbacks),
    trySendTurnLocally: (blob: Blob) => trySendTurnLocallyImpl(getCtx(), blob),
    sendHostedTextTurn: (transcript: string, options: { scrub?: boolean } = {}) =>
      sendHostedTextTurnImpl(getCtx(), transcript, options),
    sendTurn: (blob: Blob) => sendTurnImpl(getCtx(), blob),
  };
}
