"use client";

// Spoken output wiring for a call: audio unlock + barge-in, one fragment through
// the voice chain, a whole reply, the audio-chunk queue. Lifted out of
// CallPanel.tsx unchanged; takes the per-render CallCtx. The rules themselves
// live in lib/voice/voiceOutput.ts.

import { edgeEnabled, getEdgeOptIn } from "../../../lib/edge/capabilities";
import { ensureTtsModel, isTtsModelCached } from "../../../lib/edge/audio/kokoro";
import { isLatinScriptText, planVoiceOutput, runVoiceChain, speakSequence, splitForSpeech, type VoiceChainResult } from "../../../lib/voice/voiceOutput";
import { createVoiceRunners, prefetchKokoro, stopAllSpeech, unlockAudioElement, unlockWebSpeech, webSpeechPresent, type VoiceFragment } from "../../../lib/voice/voicePlayers";
import { getVoiceoverPref, preferredStepFor } from "../../../lib/voice/voiceover";
import { readServerVoiceMissingFlag } from "./voiceHelpers";
import { createAudioChunkQueue } from "../CallPanel/audioChunkQueue";
import type { CallCtx } from "./callContext";

// ---- Default voice + preflight plumbing (2026-09-19) --------------------
//
// PRODUCTION EVIDENCE that led here: every consumer voice turn logged
// "ElevenLabs TTS generation failed (credential...)" then "Sarvam TTS
// synthesis failed: No active Sarvam AI credential configured" and finished
// text-only - both server voices are per-tenant BYOK and consumers have
// neither. This panel then went straight to the robotic Web Speech voice
// (or, where that never starts, silence), while Kokoro - already vendored
// for in-browser speech - was only ever used for people who had separately
// turned "on-device replies" on. lib/voice/voiceOutput.ts now owns the
// order (server audio -> Kokoro -> Web Speech -> text) and its time bounds;
// the functions below are this component's thin wiring onto it.

// Called synchronously from the gesture that starts a hold or a call.
export function primeVoiceOutputFromGestureImpl(ctx: CallCtx) {
  const { voiceEpochRef, audioUnlockedRef, callStartedRef, audioElRef } = ctx;
  callStartedRef.current = true;
  // Barge-in: the agent stops talking the moment the person starts. Apart
  // from being what a caller expects, the microphone would otherwise
  // record the agent's own voice into the next turn. The epoch bump is what
  // stops sound that has NOT started yet: every voice chain polls it
  // (speakFragment's isAborted), so a Kokoro synth still running now never
  // plays, and a paused server clip does not fall through to another voice.
  voiceEpochRef.current += 1;
  stopAllSpeech(audioElRef.current);
  if (!audioUnlockedRef.current) {
    audioUnlockedRef.current = true;
    unlockAudioElement(audioElRef.current);
    unlockWebSpeech();
  }
  // Warm an ALREADY-INSTALLED Kokoro while the person is still speaking, so
  // the first sentence isn't spent re-initialising the model from the
  // browser cache. Only when it will plausibly be used (no server voice
  // seen this session, or on-device replies are on) - it is a large wasm
  // heap that a tenant with ElevenLabs configured never needs.
  if (edgeEnabled() && isTtsModelCached() && (getEdgeOptIn() || readServerVoiceMissingFlag())) {
    void ensureTtsModel().catch((): undefined => undefined);
  }
}

// One fragment through the voice chain. NEVER rejects; resolves within
// voiceOutput.ts's bounds. The caller has ALREADY rendered the text.
//
// Abort: the epoch is captured HERE, so anything that bumps voiceEpochRef
// afterwards (the person holding to talk again, an agent switch, unmount)
// aborts this chain - including a Kokoro synth that has not produced its
// audio yet, which previously finished and played seconds into the
// person's next recording.
export async function speakFragmentImpl(ctx: CallCtx, 
  fragment: VoiceFragment,
  onStart?: () => void,
  options: { skipKokoro?: boolean } = {}
): Promise<VoiceChainResult> {
  const { unmountedRef, voiceEpochRef, audioElRef, repliesMutedRef } = ctx;
  if (unmountedRef.current) return { spokenBy: null, attempts: [], aborted: true };
  // The panel's speaker toggle: replies stay text-only when the person muted
  // spoken replies. aborted:true (not a bare miss) so the chunk queue's
  // onAudioUnavailable notice does NOT fire - silence here is a choice.
  if (repliesMutedRef.current) return { spokenBy: null, attempts: [], aborted: true };
  const epoch = voiceEpochRef.current;
  const isAborted = () => unmountedRef.current || voiceEpochRef.current !== epoch;
  try {
    // The caller's voice-over pick promotes its leg to the front of this
    // fragment's plan and is carried to the runners via the fragment.
    const voiceover = fragment.voiceover ?? getVoiceoverPref();
    const steps = planVoiceOutput({
      text: fragment.text,
      hasServerAudio: !!fragment.audioBase64,
      hasAudioElement: !!audioElRef.current,
      edgeOn: edgeEnabled(),
      kokoroInstalled: isTtsModelCached(),
      preferOnDevice: getEdgeOptIn(),
      webSpeechPresent: webSpeechPresent(),
      skipKokoro: options.skipKokoro === true,
      preferredStep: preferredStepFor(voiceover),
    });
    const base = createVoiceRunners({ ...fragment, voiceover }, () => (isAborted() ? null : audioElRef.current));
    const runners: typeof base = {};
    for (const step of steps) {
      const runner = base[step];
      if (!runner) continue;
      runners[step] = (control) =>
        runner({
          ...control,
          started() {
            try {
              onStart?.();
            } catch {
              // A UI callback never breaks playback.
            }
            control.started();
          },
        });
    }
    const result = await runVoiceChain(steps, runners, { isAborted });
    if (!result.aborted && result.attempts.some((a) => a.outcome !== "spoken")) {
      console.warn("[Voice] voice chain fell through:", JSON.stringify(result.attempts));
    }
    return result;
  } catch (err) {
    console.warn("[Voice] voice chain threw, reply stays text-only:", err);
    return { spokenBy: null, attempts: [] };
  }
}

// Would an already-installed Kokoro be asked to speak this text? Used only
// to decide whether synthesizing it AHEAD of its turn is worth starting.
export function kokoroWouldSpeakImpl(ctx: CallCtx, text: string | null | undefined): boolean {
  const { audioElRef } = ctx;
  return edgeEnabled() && isTtsModelCached() && !!audioElRef.current && isLatinScriptText(text);
}

// A WHOLE reply (the two paths with no server audio-chunk stream) through
// the same chain, one short fragment at a time (splitForSpeech) -
// kokoroWorker clips a single synth at 600 characters without saying so,
// which used to cut long replies off mid-thought. speakSequence starts
// synthesizing fragment N+1 while N plays, skips Kokoro for the rest of the
// reply once it misses a start deadline, stops at the first fragment
// nothing could speak and whenever `keepGoing` says the turn is stale.
export async function speakWholeReplyImpl(ctx: CallCtx,
  text: string,
  keepGoing: () => boolean,
  onStart?: () => void
): Promise<VoiceChainResult["spokenBy"]> {
  const { unmountedRef, repliesMutedRef, speakFragment, kokoroWouldSpeak } = ctx;
  // Speaker-muted: nothing is spoken but the reply's text is already rendered.
  if (repliesMutedRef.current) return null;
  const voiceover = getVoiceoverPref();
  return speakSequence<ReturnType<typeof prefetchKokoro>>(splitForSpeech(text), {
    keepGoing: () => keepGoing() && !unmountedRef.current,
    prefetch: (next) =>
      kokoroWouldSpeak(next)
        ? prefetchKokoro(next, voiceover.kind === "kokoro" ? voiceover.voiceId : undefined)
        : undefined,
    speak: (fragmentText, context) =>
      speakFragment({ text: fragmentText, kokoroPrefetch: context.prefetched ?? null }, onStart, {
        skipKokoro: context.skipKokoro,
      }),
  });
}

// Plays queued audio-chunk events one at a time - see CallPanel/audioChunkQueue.ts.
export function makeAudioChunkQueueImpl(ctx: CallCtx, 
  callbacks: { onSpeakStart?: () => void; onIdle?: () => void; onAudioUnavailable?: () => void } = {}
) {
  const { voiceEpochRef, noteServerVoiceMissing, speakFragment, kokoroWouldSpeak } = ctx;
  return createAudioChunkQueue({ voiceEpochRef, kokoroWouldSpeak, noteServerVoiceMissing, speakFragment }, callbacks);
}
