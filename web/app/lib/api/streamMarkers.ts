// In-band SSE stream markers shared with core/agents, plus the request-lifecycle
// failure classes whose framework message wins (split out of core.ts, which
// re-exports them).
// Universal Request Lifecycle (see requestLifecycle.ts's header) - streamChat
// is this framework's first real call-site: connect/idle/absolute timeouts,
// abort support, bounded retry (only before any chunk has reached the caller),
// and telemetry on the SSE chat stream.
import { type RequestFailureClass } from "../requestLifecycle";

// Mirror of AgentController.STREAM_ERROR_MARKER (core/agents) - see that
// constant's javadoc. Namespaced with a random-looking suffix so it can't
// realistically collide with real streamed model text.
export const STREAM_ERROR_MARKER = "@@AGENT_CHAT_STREAM_ERROR_9f2b1c@@";

// Mirror of AgentStepEventEmitter.STEP_EVENT_MARKER (core/agents,
// com.agenticai.core.tools) - the activity-trace sibling of the marker above,
// following the exact same in-band-prefix convention rather than a second
// framing scheme. Only ever appears on a stream the caller explicitly opted
// into by passing `steps` to streamChat below (which sets
// ChatRequest.stepEvents); a turn that didn't ask never sees one of these, and
// the backend guarantees the bytes are identical to before this existed.
// Keep byte-identical with the Java constant - two divergent literals here
// would silently paste raw JSON into the chat bubble.
export const STEP_EVENT_MARKER = "@@AGENT_STEP_EVENT_4d81ba@@";

// One entry of an agent's activity trace, as parsed off the SSE stream.
//   - tool_call   : a tool is about to run. `args` is redacted + capped
//                   server-side (AgentStepEventRedactor) - credential-shaped
//                   values and secret-named fields never leave the backend.
//   - tool_result : that tool returned. `preview` is a short, already-
//                   sanitised excerpt, not the full payload; the complete
//                   input/output stays in tool_call_log, readable through
//                   listToolCalls.
//   - error       : the tool threw and nothing downstream could turn it into
//                   an answer for the model.
// `id` pairs a tool_call with whichever result/error closes it - tools can run
// concurrently, so ordering alone is not enough to match them up.
// `type` is an OPEN union ((string & {}) - same trick ActivityStep uses):
// core ships new event types before this build knows them (run_started,
// run_finished, plan, guardrail, handoff, swarm), including `heartbeat`
// liveness beats (2026-09-25) that carry no id/tool at all - which is why
// those two fields are optional here. normalizeServerStep accepts unknown
// for the same reason.
export type AgentStepEvent = {
  type: "tool_call" | "tool_result" | "error" | "heartbeat" | (string & {});
  id?: number;
  tool?: string;
  args?: string;
  preview?: string;
  message?: string;
  ms?: number;
  /** `phase` events only: the pre-token stage that began (guardrails, routing, context, model). */
  phase?: string;
};

// streamChat's failure classes where the Universal Request Lifecycle
// framework itself builds a more precise message than whatever the
// operation happened to throw (see requestLifecycle.ts's runAttempt/finish
// for exactly what each says) - "circuit-open" never even runs the
// operation, so there is nothing else to prefer there either.
export const FRAMEWORK_MESSAGE_FAILURE_CLASSES = new Set<RequestFailureClass>([
  "connect-timeout",
  "stream-idle-timeout",
  "absolute-timeout",
  "aborted",
  "circuit-open",
]);
