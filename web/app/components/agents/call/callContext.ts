// The per-render bag CallPanel hands to the call action modules (liveCall,
// pushToTalk, voiceActions, localTurn, serverTurn). The actions used to be
// closures inside the component; they now live in their own files and read the
// same values through this object, which is rebuilt on every render so each
// action sees exactly the state and sibling actions its in-component version did.

import type { Dispatch, MutableRefObject, SetStateAction } from "react";
import type { Room } from "livekit-client";
import type { CallStage, PushToTalkRouting } from "../CallPanel/callStage";
import type { createAudioChunkQueue } from "../CallPanel/audioChunkQueue";
import type { VoiceChainResult } from "../../../lib/voice/voiceOutput";
import type { VoiceFragment } from "../../../lib/voice/voicePlayers";
import type { MicState } from "../../../lib/voice/callPreflight";

/** The slice of the agent object CallPanel reads. */
export interface CallPanelAgent {
  id: string;
  providerName?: string | null;
  model?: string | null;
  systemPrompt?: string | null;
  instructions?: string | null;
  // The agent's configured TTS provider/model (AgentResponse fields) - read by
  // the voice-over row's "agent voice" display and editable via
  // api.updateAgentTtsModel. Optional because some call sites (the assistant
  // overlay) hand in a narrower agent object.
  ttsProvider?: string | null;
  ttsModel?: string | null;
}

type Setter<T = any> = Dispatch<SetStateAction<T>>;
type Ref<T> = MutableRefObject<T>;

export interface CallCtx {
  agent: CallPanelAgent | null | undefined;
  sessionId: string | null;
  onSessionId?: (sessionId: string) => void;
  t: (key: string, values?: Record<string, string | number>) => string;

  status: string;
  muted: boolean;
  cameraOn: boolean;
  agentAudioMuted: boolean;
  recording: boolean;
  pipelineBusy: boolean;

  setStatus: Setter<string>;
  setMuted: Setter<boolean>;
  setCameraOn: Setter<boolean>;
  setAgentAudioMuted: Setter<boolean>;
  setCallError: Setter;
  setCallNotice: Setter;
  setReconnecting: Setter<boolean>;
  setAgentMissing: Setter<boolean>;
  setRecording: Setter<boolean>;
  setPipelineBusy: Setter<boolean>;
  setPipelineError: Setter;
  setLastTranscript: Setter;
  setLastReply: Setter;
  setCallTranscript: Setter;
  setCallStage: Setter<CallStage>;
  setAudioUnavailableNotice: Setter<string | null>;
  setEdgeFallbackNotice: Setter<string | null>;
  setEdgeHandledSummary: Setter<string | null>;
  setPendingFallbackAsk: Setter<string | null>;
  setSttReadyNotice: Setter<string | null>;
  setServerSttFailedAt: Setter<number | null>;
  setMicState: Setter<MicState>;
  setRecorderSupported: Setter<boolean>;

  capturedTurnKeyRef: Ref<string | null>;
  fallbackAskResolverRef: Ref<((confirmed: boolean) => void) | null>;
  serverSttFailedAtRef: Ref<number | null>;
  pendingSttRef: Ref<{ blob: Blob; at: number } | null>;
  systemHealthRef: Ref<{ checked: boolean; sidecarAvailable: boolean; refresh: () => void }>;
  activeAgentIdRef: Ref<string | undefined>;
  unmountedRef: Ref<boolean>;
  voiceEpochRef: Ref<number>;
  audioUnlockedRef: Ref<boolean>;
  callStartedRef: Ref<boolean>;
  recordingStartedAtRef: Ref<number | null>;
  roomRef: Ref<Room | null>;
  mediaStreamRef: Ref<MediaStream | null>;
  mediaRecorderRef: Ref<MediaRecorder | null>;
  chunksRef: Ref<Blob[]>;
  audioElRef: Ref<HTMLAudioElement | null>;
  remoteAudioContainerRef: Ref<HTMLDivElement | null>;
  agentAudioMutedRef: Ref<boolean>;
  // Dedupe for "call-transcript" data-channel events (voice-agent's
  // llm_core_bridge.py publishes each line once; the set survives reconnects
  // so a rejoined room cannot double-list a line). Cleared on each call start.
  liveTranscriptIdsRef: Ref<Set<string>>;
  // The speaker toggle for the panel's OWN playback (push-to-talk replies and
  // replayed lines) - muting it keeps replies text-only without touching the
  // live call's remote audio track (that is agentAudioMutedRef's job).
  repliesMutedRef: Ref<boolean>;
  // The caller's per-call voice pick for the LIVE call's server-side voice
  // (an ElevenLabs voice id / Sarvam speaker), sent on the token request's
  // ttsVoice fields - voice-agent's _extract_tts_voice applies it. null = the
  // worker's configured default.
  callVoiceRef: Ref<{ voiceId: string; provider: string } | null>;
  agentJoinTimerRef: Ref<ReturnType<typeof setTimeout> | null>;
  edgeSightModeRef: Ref<boolean>;
  edgeSightSource: "camera" | "screen" | null;
  sightRoomRef: Ref<boolean>;

  startEdgeSight: (room: Room, source?: "camera" | "screen") => Promise<void>;
  stopEdgeSight: () => void;
  armReconnectCeiling: (room: Room) => void;
  disarmReconnectCeiling: () => void;
  markDialing: () => void;
  markConnected: () => void;
  markHangingUp: () => void;
  markEnded: () => void;
  noteServerVoiceMissing: (text: string | null | undefined) => void;

  askToFallback: (message: string) => Promise<boolean>;
  speechLikelyEnglish: () => boolean;
  disconnectMessage: (reason: any) => string | null;
  startFailureMessage: (err: unknown) => string;
  primeVoiceOutputFromGesture: () => void;
  speakFragment: (
    fragment: VoiceFragment,
    onStart?: () => void,
    options?: { skipKokoro?: boolean }
  ) => Promise<VoiceChainResult>;
  kokoroWouldSpeak: (text: string | null | undefined) => boolean;
  speakWholeReply: (text: string, keepGoing: () => boolean, onStart?: () => void) => Promise<VoiceChainResult["spokenBy"]>;
  makeAudioChunkQueue: (
    callbacks?: { onSpeakStart?: () => void; onIdle?: () => void; onAudioUnavailable?: () => void }
  ) => ReturnType<typeof createAudioChunkQueue>;
  sendTurnViaLocalStt: (blob: Blob) => Promise<boolean>;
  trySendTurnLocally: (blob: Blob) => Promise<PushToTalkRouting>;
  sendHostedTextTurn: (transcript: string, options?: { scrub?: boolean }) => Promise<void>;
  sendTurn: (blob: Blob) => Promise<void>;
  endCall: () => Promise<void> | undefined;
}
