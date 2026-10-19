import { DEFAULT_TIMEOUT_POLICY } from "../../../lib/requestLifecycle";

// A quick tap-and-release can land before the MediaRecorder's encoder has
// buffered enough samples to produce even one real frame - "works in Edge,
// fails in Chrome" (a real bug report) was exactly this: Chrome's Opus
// encoder needs a touch more lead time than Edge's before stop() reliably
// captures anything. Rather than reject a fast tap outright, stopPushToTalk
// below waits out the rest of this window before actually calling
// recorder.stop(), giving the encoder (now flushing every 250ms via the
// timeslice passed to start()) a real chance to have produced a chunk.
export const MIN_HOLD_MS = 350;
// A bare WebM/Opus container with no audio frames muxed in is roughly
// 100-150 bytes; this floor sits comfortably above that and comfortably
// below what even a very short (>=MIN_HOLD_MS) real utterance encodes to.
export const MIN_VIABLE_AUDIO_BYTES = 1000;
// How long after room.connect() succeeds before an empty room is treated as
// "the voice-agent worker never picked the job up". The worker normally
// joins within 1-2s of the participant appearing; 10s is comfortably past
// any healthy dispatch delay while still short enough to be a useful hint.
export const AGENT_JOIN_TIMEOUT_MS = 10_000;
// Reuses requestLifecycle.ts's existing streamIdleTimeoutMs (20s) as the
// natural precedent for "how long is too long to sit in an unbounded wait
// state" (reliability blueprint Part 5(c)#3), rather than inventing a new
// number - livekit-client will keep retrying a dropped connection with no
// ceiling of its own, so nothing today stops "reconnecting…" from sitting on
// screen forever if the underlying network never actually recovers.
export const RECONNECT_CEILING_MS = DEFAULT_TIMEOUT_POLICY.streamIdleTimeoutMs;
// edge.fallbackPolicy "ask" (capabilities.ts): how long trySendTurnLocally's
// inline confirm/cancel prompt (askToFallback below) waits for a person to
// answer before treating silence as Cancel - generous enough for someone to
// actually read+decide, short enough that a call can never hang forever on
// an unanswered prompt.
export const ASK_FALLBACK_TIMEOUT_MS = 20_000;
