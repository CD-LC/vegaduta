"use client";

// Turns that finish on the client: in-browser Whisper then the text chat
// endpoint (sendTurnViaLocalStt), the whole on-device attempt (trySendTurnLocally)
// and the scrubbed-text hosted fallthrough (sendHostedTextTurn). Lifted out of
// CallPanel.tsx unchanged; takes the per-render CallCtx.

import { api } from "../../../lib/api";
import { edgeEnabled, edgeFallbackLabel, getEdgeOptIn, resolveAutomaticEdgeFallback } from "../../../lib/edge/capabilities";
import { isTtsModelCached } from "../../../lib/edge/audio/kokoro";
import { isSttModelCached, transcribeAudioLocally } from "../../../lib/edge/audio/whisper";
import { routeTurn } from "../../../lib/edge/tierRouter";
import { prepareHostedFallthrough } from "../../../lib/edge/edgeGuardrails";
import { fetchEdgeAgentPolicy } from "../../../lib/edge/agentPolicy";
import { recordEdgeConversationFact } from "../../../lib/edge/toolBridge";
import type { PushToTalkRouting } from "../CallPanel/callStage";
import { errorMessage } from "../../../lib/errors";
import type { CallCtx } from "./callContext";

// The server's speech-to-text is down (or just failed this very turn) and
// in-browser Whisper is installed: transcribe HERE and finish the turn over
// the text chat endpoint, which shares the voice pipeline's session (see
// sendHostedTextTurn's header). Verified 2026-09-19: POST
// /api/voice/agents/{id}/turn accepts ONLY multipart audio, so there is no
// way to hand IT a transcript - but VoiceCallController#turn feeds
// AgentChatService#chat exactly like /api/agents/{id}/chat does, so this is
// the same conversation, not a side channel. Returns false when Whisper
// could not produce a transcript (nothing was sent anywhere).
export async function sendTurnViaLocalSttImpl(ctx: CallCtx, blob: Blob): Promise<boolean> {
  const { agent, setPipelineBusy, setPipelineError, setLastTranscript, setLastReply, setCallStage, activeAgentIdRef, unmountedRef, sendHostedTextTurn } = ctx;
  if (!agent) return false;
  const sentAgentId = agent.id;
  const stillCurrentAgent = () => activeAgentIdRef.current === sentAgentId;
  setPipelineBusy(true);
  setPipelineError(null);
  setLastTranscript(null);
  setLastReply("");
  setCallStage("transcribing");
  let transcript: string | null = null;
  try {
    const result = await transcribeAudioLocally(blob);
    if (result.handled === false) {
      console.warn(`[Voice] on-device speech-to-text could not take over (${result.reason})`);
    } else {
      transcript = result.text;
    }
  } catch (err) {
    // transcribeAudioLocally's contract is to never throw - belt and braces.
    console.warn("[Voice] on-device speech-to-text threw:", err);
  }
  if (!stillCurrentAgent() || unmountedRef.current) {
    setPipelineBusy(false);
    return true; // stale, not failed - nothing to report against the new agent
  }
  if (!transcript) {
    setPipelineBusy(false);
    return false;
  }
  setLastTranscript(transcript);
  // scrub:false - this person never asked for on-device privacy handling;
  // the path this replaces uploads their raw audio, so the verbatim
  // transcript is already strictly less than that, and redacting it would
  // quietly change what they said ("my email is ...") for no promise made.
  await sendHostedTextTurn(transcript, { scrub: false });
  return true;
}

// Edge extension: push-to-talk previously ALWAYS went to the server, even
// with on-device mode enabled and a local model ready - the only edge
// participation was substituting HOW a server-authored reply got spoken
// (kokoro playback above). This closes that gap for the one voice surface
// that actually can run locally: push-to-talk is client-mediated (a Blob
// this component already owns), unlike the live LiveKit call above, which
// is inherently server-mediated (a separate Python worker process runs
// that whole pipeline - there is no client-side hook a browser's on-device
// model could ever reach into for that path).
//
// Same sessionKey (agent.id) as AgentPlayground's sendViaEdge, on purpose:
// the on-device model keeps ONE continuous local conversation regardless
// of whether a given turn arrived by typing or by speaking.
//
// Edge Wave 2 seam change (EDGE-A/B, finding E-2/F-25): this used to
// return a bare boolean, and ANY local failure re-uploaded the raw audio
// blob to the server - inverting the privacy story at the exact moment it
// mattered. It now returns a small result object so sendTurn can tell four
// outcomes apart:
//   { outcome: "local" }        - whole turn stayed on-device (old `true`);
//   { outcome: "server-audio" } - nothing local succeeded, use the full
//                                 existing raw-audio server pipeline
//                                 (old `false`, unchanged behavior);
//   { outcome: "server-text", transcript } - local STT ALREADY produced a
//                                 transcript but the local reasoning leg
//                                 fell through: prefer sending the (PII-
//                                 scrubbed) TEXT hosted over re-uploading
//                                 raw audio - see sendHostedTextTurn;
//   { outcome: "refused" }      - the agent's server policy is EDGE_ONLY
//                                 and no local path could serve the turn:
//                                 surfaced as an error, NOTHING is sent to
//                                 the server (tierRouter's
//                                 "edge-only-refused" contract).
// The old "never partially local" rule is thereby refined, deliberately:
// a turn may now be locally-transcribed + hosted-reasoned, because that
// strictly REDUCES what leaves the device compared to the old raw-audio
// re-upload it replaces.
export async function trySendTurnLocallyImpl(ctx: CallCtx, blob: Blob): Promise<PushToTalkRouting> {
  const { agent, setPipelineBusy, setLastTranscript, setLastReply, setCallStage, setAudioUnavailableNotice, setEdgeFallbackNotice, setEdgeHandledSummary, activeAgentIdRef, voiceEpochRef, noteServerVoiceMissing, askToFallback, speakWholeReply } = ctx;
  if (!agent) return { outcome: "server-audio" };
  // Fix #1 (P0): same staleness guard sendTurn uses - this function has
  // several await points of its own (the policy fetch below, STT,
  // routeTurn, local TTS), any of which can outlast an agent switch.
  // sendTurn re-checks stillCurrentAgent() on the value this returns, but
  // that alone can't stop THIS function's own setState calls from firing
  // for a since-abandoned agent while it's still running - hence the
  // additional checks after each await below.
  const sentAgentId = agent.id;
  const stillCurrentAgent = () => activeAgentIdRef.current === sentAgentId;
  if (!edgeEnabled()) return { outcome: "server-audio" };
  // Wave 2 (EDGE-B): server policy outranks the per-device opt-in flag -
  // same precedence tierRouter enforces for text turns (see its header).
  let policyInfo = null;
  try {
    policyInfo = agent?.id ? await fetchEdgeAgentPolicy(agent.id) : null;
  } catch {
    // fetchEdgeAgentPolicy never rejects - belt-and-braces only.
  }
  if (!stillCurrentAgent()) return { outcome: "refused" }; // sendTurn drops this - see its own stillCurrentAgent() check
  const edgeOnly = policyInfo?.policy === "EDGE_ONLY";
  if (policyInfo?.policy === "HOSTED_ONLY") {
    // Honest provenance, same surface the other fallback notices use.
    setEdgeFallbackNotice("This agent is configured to always use the platform — sent to the platform.");
    return { outcome: "server-audio" };
  }
  if (!edgeOnly && !getEdgeOptIn()) return { outcome: "server-audio" };
  const refuse = (why: string): PushToTalkRouting => {
    setEdgeFallbackNotice(why);
    setCallStage("failed");
    return { outcome: "refused" };
  };
  // Shared gate for the AUTOMATIC fallback exit points below ONLY
  // (STT-not-cached, STT-declined, routeTurn-declined) - every edgeOnly
  // refuse() call above/below is the server-policy path and calls refuse()
  // directly, never through here. Honors the edge.fallbackPolicy
  // client-side preference (capabilities.ts):
  //   "auto"  (default) - byte-for-byte today's behavior: post the notice
  //           and proceed with whatever `proceed` already was.
  //   "never" - reuses the EXISTING refuse() helper instead of falling
  //             back automatically (same plumbing the edgeOnly branches
  //             use, not a new outcome type).
  //   "ask"   - awaits the inline confirm/cancel prompt (askToFallback,
  //             with its own timeout ceiling) before deciding; confirmed
  //             proceeds exactly like "auto", cancelled/timed-out reuses
  //             refuse() exactly like "never".
  // Fix #6 (P1): the decision itself now lives in capabilities.ts's
  // resolveAutomaticEdgeFallback, shared with AgentPlayground.tsx's
  // sendViaEdge and ChatPanel.tsx's routeTurn call site, so "never fall
  // back"/"ask me first" mean the same thing on every surface, not just
  // this one - this wrapper is now just the shared decision plus this
  // panel's own refuse()/notice/askToFallback plumbing.
  const resolveAutomaticFallback = async (message: string, proceed: PushToTalkRouting): Promise<PushToTalkRouting> => {
    const shouldProceed = await resolveAutomaticEdgeFallback(() => askToFallback(message));
    if (!shouldProceed) return refuse(message);
    setEdgeFallbackNotice(message);
    return proceed;
  };
  // Readiness checks only (never triggers a download - E-0b consent rule):
  // the local STT model must already be installed, or there is nothing to
  // transcribe with. A person who enabled on-device but never downloaded
  // the STT model deserves to know that's why this turn went to the
  // server, so this is the one early-return worth surfacing before
  // pipelineBusy/lastTranscript/etc are even set. For an EDGE_ONLY agent
  // there is no server to quietly fall back to: refuse instead - raw audio
  // reaching the platform is exactly what that policy forbids.
  if (!isSttModelCached()) {
    if (edgeOnly) {
      return refuse(
        "This agent is configured to stay on-device, but on-device speech-to-text isn't set up on this device yet."
      );
    }
    return resolveAutomaticFallback(
      "On-device speech-to-text isn't set up on this device yet — sent to the platform instead.",
      { outcome: "server-audio" }
    );
  }
  setPipelineBusy(true);
  setLastTranscript(null);
  setLastReply("");
  setAudioUnavailableNotice(null);
  setEdgeFallbackNotice(null);
  setEdgeHandledSummary(null);
  try {
    setCallStage("transcribing");
    const sttResult = await transcribeAudioLocally(blob);
    if (!stillCurrentAgent()) return { outcome: "refused" };
    if (!sttResult.handled) {
      // transcribeAudioLocally's own contract (like routeTurn's) is to
      // never throw - a local STT failure surfaces as handled:false, not
      // an exception, so it would never reach the catch block below. This
      // was previously silent - no way to tell "STT never even ran" apart
      // from "STT ran but the LLM leg fell through" apart from "nothing
      // was attempted at all" (see routed.reason logging below).
      console.warn("[CallPanel] on-device STT declined to handle this turn, falling back to hosted");
      if (edgeOnly) {
        return refuse(
          "This agent is configured to stay on-device, but on-device speech-to-text couldn't transcribe this."
        );
      }
      return resolveAutomaticFallback(
        "On-device speech-to-text couldn't transcribe this — sent to the platform instead.",
        { outcome: "server-audio", localSttTried: true }
      );
    }
    setLastTranscript(sttResult.text);
    setCallStage("understanding");
    let replyText = "";
    let generatingStarted = false;
    const routed = await routeTurn(sttResult.text, {
      sessionKey: agent.id,
      // Wave 2: explicit so the router's policy/guardrail lookup never
      // depends on the sessionKey-looks-like-a-UUID heuristic here.
      agentId: agent.id,
      systemPrompt: agent.systemPrompt || agent.instructions || undefined,
      onToken: (delta) => {
        if (!stillCurrentAgent()) return; // fix #1 - agent switched mid-flight
        if (!generatingStarted) {
          generatingStarted = true;
          setCallStage("generating");
        }
        replyText += delta;
        setLastReply(replyText);
      },
    });
    if (!stillCurrentAgent()) return { outcome: "refused" };
    if (routed.handled === false) {
      // routeTurn's contract is to never throw - every local-tier failure
      // (circuit breaker open, no ready model, a genuine tier1/tier0
      // error) surfaces here as handled:false with a reason/detail, not an
      // exception. This was previously silent - the single most likely
      // explanation for "on-device selected but nothing processed" was
      // unloggable until now. `=== false` (not `!routed.handled`): this
      // repo's strict:false tsconfig only narrows RouteTurnResult's
      // discriminated union through a literal comparison (same rule
      // AgentPlayground.tsx's sendFramesLocally already documents).
      console.warn(
        `[CallPanel] on-device routing declined this turn (reason: ${routed.reason}${
          routed.detail ? `, detail: ${routed.detail}` : ""
        }), falling back to hosted`
      );
      // Wave 2 (EDGE-B): "edge-only-refused" must never reach any server
      // path - surface it and stop, per tierRouter's contract.
      if (routed.reason === "edge-only-refused") {
        return refuse(edgeFallbackLabel(routed.reason));
      }
      // Wave 2 (EDGE-A, finding E-2): local STT already succeeded, so the
      // hosted fallthrough sends the TRANSCRIPT (scrubbed in
      // sendHostedTextTurn) instead of re-uploading the raw audio blob.
      return resolveAutomaticFallback(
        "Transcribed on-device, but " +
          edgeFallbackLabel(routed.reason).toLowerCase() +
          " — sent only the transcribed text to the platform to finish; your audio never left this device.",
        { outcome: "server-text", transcript: sttResult.text }
      );
    }
    setLastReply(routed.text);
    // Opt-in only (off by default); fire-and-forget, never awaited.
    void recordEdgeConversationFact(agent?.id, sttResult.text, routed.text);
    // Which on-device reasoning model actually answered, honestly
    // including a downgrade caveat (routeTurn tried tier 1 - the
    // "most capable" model - first; tier 0's Chrome built-in only
    // answers when tier 1 failed or was skipped).
    const llmSummary =
      routed.tier === 1
        ? "on-device (most capable model)"
        : routed.downgradedFrom
          ? `on-device — Chrome built-in (most-capable model failed: ${routed.downgradedFrom.detail})`
          : "on-device (Chrome built-in)";
    setEdgeHandledSummary(`Whisper (speech-to-text) → ${llmSummary}`);
    // Speak it - prefer on-device synthesis (nothing leaves the browser
    // at any point in this turn), then the browser's own built-in
    // speechSynthesis. Both keep this turn fully local; unlike the
    // per-chunk queue below (which by definition already reached the
    // server), there is no "server already synthesized this" leg here.
    setCallStage("speaking");
    // 2026-09-19: same chain as every other reply (Kokoro -> Web Speech ->
    // text; there is no server audio on this path by definition). Not
    // awaited past the first decision: pipelineBusy must clear as soon as
    // the TEXT is final so the transcript commits and the button frees up,
    // exactly as before - speech carries on in the background.
    // The fully on-device path has no server voice by definition, so an
    // uninstalled Kokoro is worth fetching/offering here too.
    if (!isTtsModelCached()) noteServerVoiceMissing(routed.text);
    const localEpoch = voiceEpochRef.current;
    const localKeepGoing = () => stillCurrentAgent() && voiceEpochRef.current === localEpoch;
    void speakWholeReply(routed.text, localKeepGoing).then((spokenBy) => {
      if (!localKeepGoing()) return;
      if (spokenBy === "kokoro") {
        setEdgeHandledSummary((prev) => `${prev} → Kokoro (voice)`);
      } else if (spokenBy === "web-speech") {
        setEdgeHandledSummary((prev) => `${prev} → browser voice`);
      } else {
        setAudioUnavailableNotice("Reply is text-only — audio unavailable.");
      }
      setCallStage("completed");
    });
    return { outcome: "local" };
  } catch (err) {
    // Falls through to the full server pipeline below (this function's own
    // contract - see its header comment) - NOT a terminal failure, so
    // callStage is deliberately left alone here rather than set "failed";
    // sendTurn's own transcribing/... progression picks back up right
    // after this returns false. Previously `err` was discarded entirely
    // with zero trace anywhere - an actual thrown exception (as opposed to
    // the handled:false cases logged above) is the third and last blind
    // spot in "why did on-device fail" diagnostics.
    console.warn("[CallPanel] on-device push-to-talk attempt threw, falling back to hosted:", err);
    if (edgeOnly) {
      return refuse("This agent is configured to stay on-device, and the on-device attempt failed.");
    }
    setEdgeFallbackNotice("On-device attempt failed — sent to the platform instead.");
    return { outcome: "server-audio" };
  } finally {
    setPipelineBusy(false);
  }
}

// Wave 2 (EDGE-A, finding E-2): the text-preferred hosted fallthrough.
// When local STT already produced a transcript, send prepareHostedFallthrough
// (the PII scrub - applied UNCONDITIONALLY on this voice surface, unlike
// tierRouter's guardrail-gated scrubbedInput, because ADR-008 decision 3
// names on-device preprocessing before hosted fallthrough as the voice
// privacy posture and raw speech is the acutely sensitive case) through the
// SAME AgentChatService session the voice pipeline itself uses -
// VoiceCallController#turn hands its sessionId straight to
// AgentChatService#chat, the same session space /api/agents/{id}/chat
// (api.streamChat) writes to, so conversation continuity survives the
// switch. The reply is spoken with the same local-first TTS ladder the
// fully-local path uses. Tradeoff, named honestly: the server's own TTS
// (the audio-chunk SSE stream) is not available on this path, so a device
// with neither Kokoro nor browser speechSynthesis gets a text-only reply -
// the price of the audio never leaving the device, surfaced via
// audioUnavailableNotice rather than hidden.
export async function sendHostedTextTurnImpl(ctx: CallCtx, transcript: string, options: { scrub?: boolean } = {}) {
  const { agent, sessionId, onSessionId, setPipelineBusy, setLastReply, setCallStage, setAudioUnavailableNotice, setEdgeFallbackNotice, activeAgentIdRef, voiceEpochRef, noteServerVoiceMissing, speakWholeReply } = ctx;
  // Fix #1 (P0): same staleness guard as sendTurn/trySendTurnLocally - this
  // function's own streamChat/synthesizeSpeechLocally awaits can each
  // outlast an agent switch.
  const sentAgentId = agent?.id;
  const stillCurrentAgent = () => activeAgentIdRef.current === sentAgentId;
  setPipelineBusy(true);
  setLastReply("");
  setAudioUnavailableNotice(null);
  setCallStage("understanding");
  // `scrub` defaults to true - the edge privacy path this was written for.
  // sendTurnViaLocalStt (server speech-to-text down) passes false; see there.
  const scrubbed = options.scrub === false ? transcript : prepareHostedFallthrough(transcript);
  let replyText = "";
  let generatingStarted = false;
  try {
    const newSessionId = await api.streamChat(agent.id, scrubbed, sessionId, (chunk: string) => {
      if (!stillCurrentAgent()) return; // fix #1 - agent switched mid-flight
      if (!generatingStarted) {
        generatingStarted = true;
        setCallStage("generating");
      }
      replyText += chunk;
      setLastReply(replyText);
    });
    if (!stillCurrentAgent()) return;
    if (newSessionId) onSessionId?.(newSessionId);
    setCallStage("speaking");
    // This path has no server audio-chunk stream, so with no server voice
    // possible here at all it is the clearest case for fetching Kokoro.
    if (!isTtsModelCached()) noteServerVoiceMissing(replyText);
    const textEpoch = voiceEpochRef.current;
    const textKeepGoing = () => stillCurrentAgent() && voiceEpochRef.current === textEpoch;
    void speakWholeReply(replyText, textKeepGoing).then((spokenBy) => {
      if (!textKeepGoing()) return;
      if (!spokenBy) setAudioUnavailableNotice("Reply is text-only — audio unavailable.");
      setCallStage("completed");
    });
  } catch (err) {
    console.warn("[CallPanel] hosted text fallthrough failed:", err);
    setEdgeFallbackNotice(errorMessage(err, "The platform couldn't finish this turn."));
    setCallStage("failed");
  } finally {
    setPipelineBusy(false);
  }
}
