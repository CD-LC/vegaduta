"use client";

// The LiveKit call: dial, hang up, mic / camera / agent-audio toggles. Lifted
// out of CallPanel.tsx unchanged; every function takes the component's per-render
// CallCtx (state, setters, refs, sibling actions) so it closes over exactly what
// the in-component version did.

import { ConnectionState, Room, RoomEvent, Track } from "livekit-client";
import { api } from "../../../lib/api";
import { edgeEnabled } from "../../../lib/edge/capabilities";
import { isVisionModelCached } from "../../../lib/edge/vision";
import { AGENT_JOIN_TIMEOUT_MS } from "../CallPanel/constants";
import type { CallCtx } from "./callContext";

// withCamera=true is the "Call + camera" path: the token is minted with
// mode "sight" (room prefix "sight-" server-side, so the perception worker
// knows to widen its subscription to video), and the local camera is
// published right after connect. Called with no argument this is the
// original voice-only flow, byte-for-byte - the token request carries no
// body and no camera API is ever touched.
//
// S-edge exception (2026-08-07): when the local VLM is already installed,
// "Call + camera" instead requests a plain VOICE room and runs
// startEdgeSight - the camera never leaves the device, and the worker gets
// text reports over the "edge-sight" data topic (edge_sight.py). The
// server-sight room is the fallback for devices without the model.
export async function startCallImpl(ctx: CallCtx, withCamera = false) {
  const { agent, t, status, setStatus, setMuted, setCameraOn, setAgentAudioMuted, setCallError, setCallNotice, setReconnecting, setAgentMissing, setCallTranscript, capturedTurnKeyRef, setCallStage, roomRef, remoteAudioContainerRef, agentAudioMutedRef, liveTranscriptIdsRef, callVoiceRef, agentJoinTimerRef, edgeSightModeRef, sightRoomRef, startEdgeSight, stopEdgeSight, armReconnectCeiling, disarmReconnectCeiling, markDialing, markConnected, disconnectMessage, startFailureMessage, primeVoiceOutputFromGesture, speakWholeReply } = ctx;
  if (!agent || status === "connecting" || status === "connected") return;
  // 2026-09-19: the Call button is a user gesture too - unlock audio here
  // so a later hold-to-talk reply is audible even on Safari.
  primeVoiceOutputFromGesture();
  setCallError(null);
  setCallNotice(null);
  setAgentMissing(false);
  setReconnecting(false);
  setStatus("connecting");
  markDialing();
  agentAudioMutedRef.current = false;
  setAgentAudioMuted(false);
  setCameraOn(false);
  setCallStage("idle");
  // A fresh call is a fresh conversation - clear any transcript left over
  // from an earlier call/hold-to-talk session with this same agent (NOT
  // done in endCall(), so a person can still read back the just-finished
  // call after hanging up; this is the "starting over" boundary instead).
  setCallTranscript([]);
  capturedTurnKeyRef.current = null;
  // New call, new transcript stream: the data-channel dedupe set must not
  // carry last call's ids (a new room's turn ids restart from scratch).
  liveTranscriptIdsRef.current.clear();
  // Fix #4 (P1): a redial must always reflect the mic's real (unmuted)
  // state - startCall() unconditionally calls setMicrophoneEnabled(true)
  // below, but before this fix `muted` was only ever reset from endCall()/
  // toggleMute(), so a person who muted before a server-initiated drop
  // (JOIN_FAILURE/MEDIA_FAILURE/server-kick - see the Disconnected handler)
  // saw a stale "muted" icon on the next call despite the live mic being on.
  setMuted(false);
  // S-edge eligibility, computed ONCE per call: flag on + model installed.
  // Also remembered for mid-call toggles (toggleCamera) - a voice call's
  // camera toggle can only ever be useful via the edge path (the worker in
  // a voice room never subscribes to video at all).
  edgeSightModeRef.current = edgeEnabled() && isVisionModelCached();
  const edgeSightCall = withCamera && edgeSightModeRef.current;
  sightRoomRef.current = withCamera && !edgeSightCall;
  try {
    // getVoiceToken's POST /api/voice/agents/{id}/token call is already
    // authenticated with this browser's own current Keycloak access token
    // - api.ts's apiFetch calls auth.getToken() and attaches
    // `Authorization: Bearer <token>` to every request, this one included.
    // core's VoiceCallController#issueToken reads that same token
    // server-side (@AuthenticationPrincipal Jwt) and embeds it in the
    // minted LiveKit token's participant `metadata` claim, so the
    // server-side voice-agent worker (LiveKit Agents, voice-agent/ - a
    // different session's scope) can read it once it joins the room and
    // call back into /api/agents/{id}/chat as this same user. No separate
    // client-side step is needed to "carry" the token into the call.
    //
    // A client-side alternative was considered and rejected:
    // room.localParticipant.setMetadata() (confirmed against the installed
    // livekit-client 2.21.0's LocalParticipant.d.ts - the method is real)
    // only runs AFTER room.connect() succeeds, requires the token to
    // separately grant canUpdateOwnMetadata before the browser is even
    // allowed to call it, and races the worker's own job pickup - the
    // token-issuance path above has none of those problems since the
    // metadata is already on the participant the instant they join.
    // The caller's per-call voice pick rides on the token request
    // (ttsVoice/ttsVoiceProvider -> participant metadata -> the worker's
    // _extract_tts_voice); null means "whatever the worker is configured
    // with", the pre-existing behaviour.
    const callVoice = callVoiceRef.current;
    const { token, url } = await api.getVoiceToken(
      agent.id,
      sightRoomRef.current ? "sight" : undefined,
      callVoice ? { voice: callVoice.voiceId, provider: callVoice.provider } : undefined
    );
    const room = new Room();
    room.on(RoomEvent.Disconnected, (reason) => {
      if (agentJoinTimerRef.current) clearTimeout(agentJoinTimerRef.current);
      disarmReconnectCeiling();
      stopEdgeSight();
      setCameraOn(false);
      setAgentMissing(false);
      setReconnecting(false);
      setCallNotice(null);
      // Fix #4 (P1): a server-initiated/unexpected drop never runs
      // endCall()/toggleMute() - reset here too so a redial's mic icon
      // can't stay stuck "muted" from before this drop.
      setMuted(false);
      roomRef.current = null;
      if (remoteAudioContainerRef.current) remoteAudioContainerRef.current.innerHTML = "";
      // An unexpected drop must never look like a clean hang-up - map the
      // reason to a real message (see disconnectMessage) instead of the
      // silent return-to-idle this handler used to do for every reason.
      const message = disconnectMessage(reason);
      if (message) {
        setCallError(message);
        setStatus("error");
      } else {
        setStatus("idle");
      }
    });
    // Reconnect attempts (resume after a network blip) were previously
    // invisible - the panel just said "In call" while nothing worked.
    room.on(RoomEvent.ConnectionStateChanged, (state) => {
      const isReconnecting = state === ConnectionState.Reconnecting || state === ConnectionState.SignalReconnecting;
      setReconnecting(isReconnecting);
      if (isReconnecting) {
        armReconnectCeiling(room);
      } else {
        disarmReconnectCeiling();
      }
    });
    // Mid-call device failures (mic unplugged, OS revoked the camera) - a
    // notice, not a teardown; the room itself is still healthy.
    room.on(RoomEvent.MediaDevicesError, (mediaErr) => {
      setCallNotice(t("deviceError", { message: mediaErr?.message || String(mediaErr) }));
    });
    // Any remote participant is the voice-agent worker (rooms are 1:1) -
    // its arrival stands down the agent-join watchdog armed after connect.
    room.on(RoomEvent.ParticipantConnected, () => {
      if (agentJoinTimerRef.current) clearTimeout(agentJoinTimerRef.current);
      setAgentMissing(false);
    });
    // The other half of the LiveKit call actually being audible: subscribe
    // to the voice-agent worker's published TTS track and attach it to a
    // real playable element. room.connect() below auto-subscribes to
    // remote tracks by default, but subscribing is not the same as
    // playing - nothing plays a track until .attach() runs.
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
    // Serializes locally-spoken live-call replies (the `voiced:false` path in
    // the call-transcript handler below) - a plain Promise chain, one
    // speakWholeReply at a time, never two voices overlapping.
    let liveLocalVoiceChain: Promise<unknown> = Promise.resolve();
    // Part 5(b) step 6: the LiveKit-side agent (voice-agent/, a different
    // session's scope in parallel) is specced to publish uniform
    // {stage, turnId, error} JSON on this one data-channel topic for all 7
    // stages - see stage_signal.py's TOPIC constant in that spec. Wired
    // ahead of the backend landing it: harmless no-op until it does, since
    // DataReceived simply never fires on "agent-stage" until then. Reuses
    // the SAME `callStage` state push-to-talk drives (see sendTurn below)
    // so both call surfaces render through one shared indicator.
    room.on(RoomEvent.DataReceived, (payload, _participant, _kind, topic) => {
      // "call-transcript": the retained text of the live call (voice-agent's
      // llm_core_bridge.py publishes {id, role, text, turnId} per turn) -
      // appended to the same callTranscript state push-to-talk writes, so
      // "response in voice" keeps its text on screen too. Deduped by event
      // id; the set was cleared at call start above.
      if (topic === "call-transcript") {
        try {
          const decoded = JSON.parse(new TextDecoder().decode(payload));
          const role = decoded?.role === "user" || decoded?.role === "agent" ? decoded.role : null;
          const text = typeof decoded?.text === "string" ? decoded.text.trim() : "";
          const id = typeof decoded?.id === "string" ? decoded.id : `${decoded?.turnId ?? "?"}:${role}`;
          if (!role || !text || liveTranscriptIdsRef.current.has(id)) return;
          liveTranscriptIdsRef.current.add(id);
          setCallTranscript((prev: { role: string; text: string }[]) => [...prev, { role, text }]);
          // The worker reports `voiced:false` when this deployment has no
          // usable server-side TTS key - then the reply text is all there
          // is, so speak it here through the same client-side chain
          // push-to-talk uses (on-device Kokoro, else the browser's voice -
          // free, no key needed). Serialized so consecutive lines never
          // talk over each other; keepGoing stops on disconnect/mute.
          if (role === "agent" && decoded.voiced === false) {
            liveLocalVoiceChain = liveLocalVoiceChain.then(() =>
              speakWholeReply(text, () => roomRef.current === room)
            );
          }
        } catch (err) {
          console.warn(`[Voice] Failed to parse call-transcript data message:`, err);
        }
        return;
      }
      if (topic !== "agent-stage") return;
      try {
        const decoded = JSON.parse(new TextDecoder().decode(payload));
        if (decoded?.stage) setCallStage(decoded.stage);
        if (decoded?.error) setCallError(decoded.error);
      } catch (err) {
        console.warn(`[Voice] Failed to parse agent-stage data message:`, err);
      }
    });
    await room.connect(url, token);
    roomRef.current = room;
    try {
      await room.localParticipant.setMicrophoneEnabled(true);
    } catch (micErr) {
      // A denied mic makes a voice call pointless - tear the room down so
      // it doesn't linger connected-but-silent, then surface the reason.
      roomRef.current = null;
      void room.disconnect();
      throw micErr;
    }
    setStatus("connected");
    markConnected();
    // Arm the agent-join watchdog: connected-but-alone past the timeout
    // means the server-side worker never picked the job up. The call stays
    // up (the WebRTC leg is proven fine) - the hint names the real culprit.
    agentJoinTimerRef.current = setTimeout(() => {
      if (roomRef.current === room && room.remoteParticipants.size === 0) {
        setAgentMissing(true);
      }
    }, AGENT_JOIN_TIMEOUT_MS);
    if (withCamera) {
      // Camera comes up AFTER the call is connected - a camera permission
      // denial should degrade to a working voice call, not kill the call.
      // Edge-sight calls capture locally instead of publishing; both
      // failure paths degrade to the same working voice-only call.
      try {
        if (edgeSightCall) {
          await startEdgeSight(room);
        } else {
          await room.localParticipant.setCameraEnabled(true);
          setCameraOn(true);
        }
      } catch (cameraErr) {
        console.warn(`[Voice] Camera failed after connect - continuing voice-only:`, cameraErr);
        setCallNotice(t("cameraVoiceOnly"));
      }
    }
  } catch (err) {
    console.error(`[Voice] Call start failed:`, err);
    setCallError(startFailureMessage(err));
    setStatus("error");
  }
}

// Mid-call camera on/off. Off releases the device immediately (LiveKit
// stops the underlying MediaStreamTrack); on republishes. A failure here
// is NEVER call-fatal: the room stays up, the notice explains, and the
// same button remains the retry path.
export async function toggleCameraImpl(ctx: CallCtx) {
  const { t, cameraOn, setCameraOn, setCallNotice, roomRef, edgeSightModeRef, sightRoomRef, startEdgeSight, stopEdgeSight } = ctx;
  const room = roomRef.current;
  if (!room) return;
  const next = !cameraOn;
  // Edge path for every non-sight-room camera toggle where the local VLM
  // is installed: in a voice room the worker never subscribes to video, so
  // publishing there was always a no-op - local analysis is the only
  // camera mode that actually does something in that room type.
  if (!sightRoomRef.current && edgeSightModeRef.current) {
    if (next) {
      try {
        await startEdgeSight(room);
        setCallNotice(null);
      } catch (err) {
        console.warn(`[Voice] Edge sight start failed (call stays up):`, err);
        setCallNotice(t("cameraVoiceOnly"));
      }
    } else {
      stopEdgeSight();
    }
    return;
  }
  try {
    await room.localParticipant.setCameraEnabled(next);
    setCameraOn(next);
    // A successful retry clears the earlier "continuing voice-only" line.
    if (next) setCallNotice(null);
  } catch (err) {
    console.warn(`[Voice] Camera toggle failed (call stays up):`, err);
    setCallNotice(next ? t("cameraVoiceOnly") : t("cameraToggleFailed"));
  }
}

// Screen share rides the same edge-sight path as the camera: frames are
// analysed on-device by the local VLM and only text descriptions leave the
// device - a raw screen feed is never published to the room. It shares the
// camera's gate (edge sight eligible, not a server-sight room) precisely
// because "publish the screen" is a much bigger privacy surface than a
// local analysis loop; that choice is deliberate, not a missing feature.
export async function toggleScreenShareImpl(ctx: CallCtx) {
  const { t, setCallNotice, roomRef, edgeSightModeRef, sightRoomRef, edgeSightSource, startEdgeSight, stopEdgeSight } = ctx;
  const room = roomRef.current;
  if (!room) return;
  if (!sightRoomRef.current && edgeSightModeRef.current) {
    if (edgeSightSource === "screen") {
      stopEdgeSight();
      return;
    }
    // One analysed stream at a time: a camera sight in progress is stopped
    // before the screen picker opens.
    stopEdgeSight();
    try {
      await startEdgeSight(room, "screen");
      setCallNotice(null);
    } catch (err) {
      // Esc/closed picker rejects the promise - that is "cancelled", not an
      // error worth alarming over.
      console.warn(`[Voice] Screen share not started:`, err);
      setCallNotice(t("screenShareFailed"));
    }
    return;
  }
  setCallNotice(t("screenShareUnavailable"));
}

// Fix #3 (P1): returns room.disconnect()'s own promise (or undefined when
// no room is up) so the reconnect-ceiling handler above can AWAIT the
// disconnect actually completing before it sets its own error/status -
// every other caller (the End-call button's onClick, the reconnect-ceiling
// handler) is free to ignore the returned promise, same as before this fix.
export function endCallImpl(ctx: CallCtx) {
  const { setStatus, setMuted, cameraOn, setCameraOn, setCallNotice, setReconnecting, setAgentMissing, setCallStage, roomRef, agentJoinTimerRef, stopEdgeSight, disarmReconnectCeiling, markHangingUp, markEnded } = ctx;
  // Belt-and-braces: disconnect() stops published tracks, but turn the
  // camera off explicitly first so the device light goes out even if the
  // disconnect handshake stalls. Edge sight owns its own (unpublished)
  // stream, so it needs its own explicit stop for the same reason.
  stopEdgeSight();
  if (cameraOn) {
    roomRef.current?.localParticipant?.setCameraEnabled(false).catch(() => {});
  }
  if (agentJoinTimerRef.current) clearTimeout(agentJoinTimerRef.current);
  disarmReconnectCeiling();
  setAgentMissing(false);
  setReconnecting(false);
  setCallNotice(null);
  setCameraOn(false);
  setCallStage("idle");
  const room = roomRef.current;
  roomRef.current = null;
  setStatus("idle");
  setMuted(false);
  const disconnected = room?.disconnect();
  if (disconnected !== undefined) {
    markHangingUp();
    void Promise.resolve(disconnected).then(markEnded, markEnded);
  } else {
    markEnded();
  }
  return disconnected;
}

export async function toggleMuteImpl(ctx: CallCtx) {
  const { muted, setMuted, roomRef } = ctx;
  const room = roomRef.current;
  if (!room) return;
  const next = !muted;
  await room.localParticipant.setMicrophoneEnabled(!next);
  setMuted(next);
}

// Stops/resumes the agent's voice without leaving the call - mutes every
// currently-attached remote audio element (and, via agentAudioMutedRef,
// any attached later in the same call) rather than tearing down the
// track subscription, so unmuting resumes instantly with no
// resubscribe/rejoin delay.
export function toggleAgentAudioImpl(ctx: CallCtx) {
  const { setAgentAudioMuted, remoteAudioContainerRef, agentAudioMutedRef } = ctx;
  const next = !agentAudioMutedRef.current;
  agentAudioMutedRef.current = next;
  setAgentAudioMuted(next);
  remoteAudioContainerRef.current?.querySelectorAll("audio").forEach((el) => {
    el.muted = next;
  });
}
