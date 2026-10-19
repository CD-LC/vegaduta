// Voice members of chatApi (split out of chat.ts, which spreads them into chatApi):
// the LiveKit token, the push-to-talk SSE turn stream and the "play this reply" TTS call.
import { runRequestLifecycle, type RequestFailureClass } from "../requestLifecycle";
import { apiFetch, toJson, toJsonQuietOn, jsonBody, voiceBlobExtension } from "./core";
import {
  parseAtBoundary,
  VoiceAudioChunkEventSchema,
  VoiceDoneEventSchema,
  VoiceTranscriptEventSchema,
  type VoiceAudioChunkEvent,
  type VoiceDoneEvent,
  type VoiceTranscriptEvent,
} from "./schemas";
import { parseSpendCapPayload, spendCapMessage } from "../spendCap";

export const voiceChatApi = {
  // Self-hosted in-app voice/video calling (core/voice, Phase 1 of the
  // self-reliance initiative - LiveKit, no PSTN, no Twilio). getVoiceToken
  // mints a short-lived, room-scoped LiveKit access token this same
  // JWT-authenticated caller can join with (CallPanel.tsx uses it to
  // connect livekit-client's Room). sendVoiceTurnStream is the separate,
  // deliberately simpler push-to-talk pipeline (STT -> agent -> TTS,
  // streamed as SSE) that actually gets the user's words to/from the agent
  // - see VoiceCallController#turn's javadoc for why this is NOT routed
  // through the LiveKit room's own media tracks.
  // `mode` is optional: "sight" mints the room with the "sight-" prefix so
  // the perception worker treats the session as camera+mic rather than
  // voice-only (see CallPanel's "Call + camera"); omitted (or "voice") keeps
  // the original body-less POST byte-identical, so every voice-only caller
  // is untouched. `voice` is the caller's optional per-call reply-voice pick
  // ({ voice: "<elevenlabs voice_id|sarvam speaker>", provider }) - carried as
  // ttsVoice/ttsVoiceProvider on VoiceTokenRequest, applied by the voice-agent
  // worker for this call only (its _extract_tts_voice reads the metadata).
  getVoiceToken: (agentId: string, mode?: "voice" | "sight", voice?: { voice: string; provider: string }) =>
    apiFetch(`/api/voice/agents/${agentId}/token`, {
      method: "POST",
      ...(mode || voice
        ? jsonBody({
            ...(mode ? { mode } : {}),
            ...(voice ? { ttsVoice: voice.voice, ttsVoiceProvider: voice.provider } : {}),
          })
        : {}),
    }).then(toJson<{ token: string; url: string; [key: string]: unknown }>),
  // Streams the push-to-talk pipeline's result as it arrives instead of
  // waiting for the whole reply + its audio (see VoiceCallController#turn's
  // javadoc for the full event sequence: transcript once, then interleaved
  // reply-delta/audio-chunk events, then one done summary). Named SSE events
  // (event:/data: framing), unlike streamChat's flat data:-only stream below,
  // so this can't reuse streamChat's simpler per-line parser - a data payload
  // can itself legitimately span multiple `data:` lines per the SSE spec
  // (Spring's writer splits on embedded newlines in a reply-delta token), so
  // consecutive data: lines within one event are joined with "\n" rather than
  // treated as separate events.
  sendVoiceTurnStream: async (
    agentId: string,
    audioBlob: Blob,
    sessionId: string,
    callbacks: {
      onTranscript?: (payload: VoiceTranscriptEvent) => void;
      onReplyDelta?: (text: string) => void;
      onAudioChunk?: (payload: VoiceAudioChunkEvent) => void;
      onDone?: (payload: VoiceDoneEvent) => void;
      // `code` is new and optional - every existing single-arg onError caller
      // (CallPanel.tsx included) keeps working byte-identical. Only set for
      // the reply-stream idle/absolute timeout below (see that comment); the
      // server-signaled "error" SSE event further down still calls onError
      // with just a message, exactly as before this change.
      onError?: (message: string, code?: RequestFailureClass) => void;
    }
  ) => {
    const formData = new FormData();
    const ext = `turn.${voiceBlobExtension(audioBlob.type)}`;
    formData.append("audio", audioBlob, ext);
    if (sessionId) formData.append("sessionId", sessionId);
    console.log(
      `[API] Sending voice turn: agentId=${agentId}, audioSize=${audioBlob.size}, type=${audioBlob.type}, filename=${ext}, sessionId=${sessionId}`
    );

    // This 45s timeoutMs only bounds the upload leg (POST resolving to
    // response headers) - it does NOT cover reading the SSE reply body below,
    // which is a separate, unbounded read loop once headers arrive.
    const res = await apiFetch(`/api/voice/agents/${agentId}/turn`, {
      method: "POST",
      body: formData,
      timeoutMs: 45000,
    });
    if (!res.ok) {
      console.error(`[API] Voice turn request failed with status=${res.status}`);
      // Always throws for a non-ok response. QUIET on the statuses CallPanel
      // recovers from by itself (no speech-to-text configured, sidecar asleep,
      // upstream down): the server's text there is admin-facing ("Add a Sarvam
      // credential") and used to pop as a red toast over a consumer's call
      // while the panel was already offering the on-device route. The error
      // still throws with .status, which CallPanel reads.
      await toJsonQuietOn([400, 500, 502, 503, 504])(res);
      return;
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let eventType = "message";
    let dataLines: string[] = [];

    const dispatch = () => {
      if (dataLines.length === 0) return;
      const data = dataLines.join("\n");
      dataLines = [];
      const type = eventType;
      eventType = "message";
      // JSON events are validated at the boundary (./schemas). A JSON syntax
      // error still throws (unchanged - it fails the turn visibly); a shape
      // mismatch is logged once and the valid fields pass through; a
      // non-object payload is dropped.
      switch (type) {
        case "transcript": {
          const event = parseAtBoundary(VoiceTranscriptEventSchema, JSON.parse(data), "voice-turn:transcript");
          if (event) callbacks.onTranscript?.(event);
          break;
        }
        case "reply-delta":
          callbacks.onReplyDelta?.(data);
          break;
        case "audio-chunk": {
          const event = parseAtBoundary(VoiceAudioChunkEventSchema, JSON.parse(data), "voice-turn:audio-chunk");
          if (event) callbacks.onAudioChunk?.(event);
          break;
        }
        case "done": {
          const event = parseAtBoundary(VoiceDoneEventSchema, JSON.parse(data), "voice-turn:done");
          if (event) callbacks.onDone?.(event);
          break;
        }
        case "error": {
          // Spend cap hit mid-call: the same JSON as the 402 body.
          const cap = parseSpendCapPayload(data);
          callbacks.onError?.(cap ? spendCapMessage(cap) : data);
          break;
        }
        default:
          break;
      }
    };

    // CONFIRMED BUG this closes (latency audit 2026-08-06): once the POST
    // above resolves, its 45s timeoutMs has done its job - nothing then
    // guarded the reader.read() loop pumping the SSE reply body itself, so a
    // server that went silent mid-reply (after headers, before the next
    // event) hung this loop, and the whole call, forever, with zero
    // user-visible failure. Runs under the same Universal Request Lifecycle
    // framework streamChat uses above (requestLifecycle.ts) for JUST this
    // loop's own idle timeout (DEFAULT_TIMEOUT_POLICY.streamIdleTimeoutMs,
    // reset on every raw chunk received - not merely once per SSE event) -
    // the upload leg's own 45s apiFetch timeoutMs above is untouched.
    //
    // ReadableStreamDefaultReader#read() takes no AbortSignal of its own, so
    // ctx.signal is bridged manually: nextChunkOrAbort() races the pending
    // read() against the signal firing, so an idle/absolute-timeout abort
    // makes the operation actually THROW (classified by the framework)
    // rather than let a reader.cancel()-induced done:true quietly look like
    // a normal stream end - reader.cancel() is still called best-effort on
    // abort to stop the underlying network read, but the loop's exit path
    // never depends on how that resolves.
    let lastLoopError: unknown = null;
    const controller = runRequestLifecycle<void>(
      async (ctx) => {
        ctx.markConnected(); // headers already in hand by the time this runs
        const onAbort = () => {
          reader.cancel().catch(() => {});
        };
        ctx.signal.addEventListener("abort", onAbort);
        const nextChunkOrAbort = (): Promise<ReadableStreamReadResult<Uint8Array>> =>
          new Promise((resolve, reject) => {
            if (ctx.signal.aborted) {
              reject(new DOMException("Aborted", "AbortError"));
              return;
            }
            const onRaceAbort = () => reject(new DOMException("Aborted", "AbortError"));
            ctx.signal.addEventListener("abort", onRaceAbort, { once: true });
            reader.read().then(
              (result) => {
                ctx.signal.removeEventListener("abort", onRaceAbort);
                resolve(result);
              },
              (err) => {
                ctx.signal.removeEventListener("abort", onRaceAbort);
                reject(err);
              }
            );
          });
        try {
          while (true) {
            const { done, value } = await nextChunkOrAbort();
            if (done) break;
            ctx.onChunk(); // any bytes at all reset the idle timer
            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split("\n");
            buffer = lines.pop() ?? "";
            for (const line of lines) {
              if (line === "") {
                dispatch();
              } else if (line.startsWith("event:")) {
                eventType = line.slice(6).trim();
              } else if (line.startsWith("data:")) {
                // No space-eating beyond the 5-char "data:" prefix - same
                // reasoning as streamChat above: Spring's writer emits
                // "data:<content>" with no separator space, so a payload
                // that legitimately starts with a space (or a
                // Jackson-escaped JSON string) must not be trimmed.
                dataLines.push(line.slice(5));
              }
            }
          }
          dispatch(); // flush a final event not followed by a trailing blank line
        } catch (err) {
          lastLoopError = err;
          throw err;
        } finally {
          ctx.signal.removeEventListener("abort", onAbort);
        }
      },
      {
        target: "voice-turn-stream:core",
        surface: "voice-turn-stream",
        // Exactly one attempt: by the time any byte has reached the caller,
        // the framework already refuses to retry (see requestLifecycle.ts's
        // header); before that, a fresh read() on this SAME reader on a
        // second attempt isn't a meaningfully different request the way a
        // fresh streamChat fetch is, so retry/circuit-breaker are switched
        // off entirely here rather than relying on that implicit guard.
        retryPolicy: false,
        circuitBreaker: false,
        // 2026-08-12 fix, same root cause as streamChat's own timeoutPolicy
        // widening above: this call site had NO override at all, so it was
        // silently running on DEFAULT_TIMEOUT_POLICY's 10s/20s/120s - even
        // tighter than the pre-fix streamChat numbers - despite hitting the
        // exact same AgentChatService backend (same buffered CAREFUL/
        // RIGOROUS/PLANNED depths, same up-to-15-call tool loop, same
        // hosted-pool fallback chain). A voice turn that takes a genuinely
        // healthy while to think or to run a tool was surfacing as "The
        // reply stopped arriving and timed out" well before the turn had a
        // real chance to finish. Same widened ceilings as streamChat, same
        // reasoning - see that call site's comment for the full breakdown.
        timeoutPolicy: { connectTimeoutMs: 90_000, streamIdleTimeoutMs: 60_000, absoluteTimeoutMs: 300_000 },
      }
    );

    const result = await controller.done;
    if (result.ok) return;

    const failureClass = result.snapshot.failureClass;
    if (failureClass === "stream-idle-timeout" || failureClass === "absolute-timeout") {
      // The new, distinct case this change adds: no normal "error"/"done"
      // SSE event ever arrived, and the reply just stopped. Routed through
      // onError (not thrown) with a `code` a caller can branch on, per the
      // audit's ask - CallPanel.tsx's existing single-arg onError handler
      // still works unchanged (setPipelineError + Failed stage), a future
      // UI pass can key off `code` for a more specific message.
      callbacks.onError?.(
        result.snapshot.error || "The reply stopped arriving and timed out. Please try again.",
        failureClass
      );
      return;
    }
    if (failureClass === "aborted") {
      // Only reachable via an external cancel(), which nothing wires up to
      // this call today - a silent no-op here, same as if the loop had
      // simply never resumed after a caller-initiated stop.
      return;
    }
    // Any other failure (a genuine stream/network error) - preserve the
    // pre-lifecycle behavior byte-for-byte: rethrow the original error so it
    // propagates out of sendVoiceTurnStream exactly as it did before this
    // wrapper existed, for CallPanel.tsx's own outer try/catch to handle.
    throw lastLoopError ?? new Error(result.snapshot.error || "The voice reply stream failed.");
  },

  // "Play this reply" control on a plain text chat turn (AgentPlayground) -
  // reuses VoiceCallController's ElevenLabs-then-fallback TTS leg without
  // going through the STT/chat pipeline sendVoiceTurnStream does. method comes
  // back "text-only-fallback" (empty audioBase64) when ElevenLabs isn't
  // configured - the caller is expected to fall back to the browser's Web
  // Speech API (see CallPanel's playBrowserTTS) in that case.
  speakText: (agentId: string, text: string) =>
    apiFetch(`/api/voice/agents/${agentId}/speak`, {
      method: "POST",
      ...jsonBody({ text }),
    }).then(toJsonQuietOn([400, 500, 502, 503, 504])),
};
