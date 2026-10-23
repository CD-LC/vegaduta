package com.agenticai.core.agents.tools;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The vocabulary of the turn-lifecycle step events {@link AgentStepEventEmitter} sends besides the tool and
 * run events: the five things a client needs to say what a turn is doing beyond "working", as structured
 * fields instead of wording it would have to match.
 *
 * <pre>
 * finish             {"type":"finish","finishReason":"length","detail":"MAX_TOKENS"}
 * retry              {"type":"retry","attempt":2,"maxAttempts":3,"reason":"rate_limited"}
 * failover           {"type":"failover","toModel":"gpt-4o-mini","reason":"provider_unavailable"}
 * continuation       {"type":"continuation","pass":1,"maxPasses":2,"reason":"cut_off"}
 * approval_required  {"type":"approval_required","tool":"send_email","approvalId":"c0ffee00-..."}
 * </pre>
 *
 * <ul>
 *   <li>{@code finish} is sent once per reply, when it ends: {@code finishReason} is one of {@link
 *       #FINISH_REASONS}; {@code detail} (optional) is the provider's own wording of a refusal. A client
 *       keys "Continue" on {@link #FINISH_LENGTH} (and {@link #FINISH_INTERRUPTED}) and the refusal
 *       notice on {@link #FINISH_SAFETY}, never on the text of the notice the platform also appends.
 *   <li>{@code retry} is sent when the same model is called again after a failure before its first
 *       token, or when a reply is asked for again after an unusable tool call. {@code attempt} is the try
 *       about to run (1 is the first), {@code maxAttempts} how many the platform will make.
 *   <li>{@code failover} is sent when the turn moves to another model; {@code toModel} may be absent
 *       when the new model is not known by name.
 *   <li>{@code continuation} is sent when another model pass carries the reply on; {@code reason} is
 *       one of {@link #CONTINUE_CUT_OFF}, {@link #CONTINUE_UNFINISHED_STEP}, {@link #CONTINUE_APPROVAL}.
 *   <li>{@code approval_required} is sent after a tool call has been recorded as a pending approval;
 *       {@code approvalId} is that request's id.
 * </ul>
 *
 * Reasons for {@code retry} and {@code failover} are short codes ({@code rate_limited}, {@code timeout},
 * {@code provider_unavailable}, ...), see {@code TurnLifecycleEvents} in the turn module; a client may show
 * an unknown one with its underscores read as spaces. All five are additive and sent only to a client that
 * asked for step events.
 */
public final class TurnLifecycleStepEvents {

    private TurnLifecycleStepEvents() {}

    public static final String FINISH = "finish";
    public static final String RETRY = "retry";
    public static final String FAILOVER = "failover";
    public static final String CONTINUATION = "continuation";
    public static final String APPROVAL_REQUIRED = "approval_required";

    /** Every lifecycle event type, in the order the documentation lists them. */
    public static final List<String> TYPES =
            List.of(FINISH, RETRY, FAILOVER, CONTINUATION, APPROVAL_REQUIRED);

    /** The reply is an answer. */
    public static final String FINISH_STOP = "stop";

    /** The reply was cut off at the output limit (and could not be continued further). */
    public static final String FINISH_LENGTH = "length";

    /** The provider's safety system stopped the reply. */
    public static final String FINISH_SAFETY = "safety";

    /** The provider kept producing a tool call it could not use. */
    public static final String FINISH_MALFORMED_TOOL_CALL = "malformed_tool_call";

    /** The model produced nothing. */
    public static final String FINISH_EMPTY = "empty";

    /** The stream broke after some text; the reply is closed with the interrupted marker. */
    public static final String FINISH_INTERRUPTED = "interrupted";

    /** Every value {@code finishReason} can take. */
    public static final List<String> FINISH_REASONS =
            List.of(
                    FINISH_STOP,
                    FINISH_LENGTH,
                    FINISH_SAFETY,
                    FINISH_MALFORMED_TOOL_CALL,
                    FINISH_EMPTY,
                    FINISH_INTERRUPTED);

    /** The reply hit the output limit and another pass continues it. */
    public static final String CONTINUE_CUT_OFF = "cut_off";

    /** The reply stopped at a step it announced but did not take. */
    public static final String CONTINUE_UNFINISHED_STEP = "unfinished_step";

    /** The platform is carrying the task on after a person decided a held tool call. */
    public static final String CONTINUE_APPROVAL = "approval";

    /** Every value a {@code continuation} event's {@code reason} can take. */
    public static final List<String> CONTINUATION_REASONS =
            List.of(CONTINUE_CUT_OFF, CONTINUE_UNFINISHED_STEP, CONTINUE_APPROVAL);

    /**
     * The sender of the lifecycle events on {@code emitter} (null is treated as a disabled one). A send on a turn
     * that did not ask for step events does nothing.
     */
    public static Sender on(AgentStepEventEmitter emitter) {
        return new Sender(emitter);
    }

    /** Sends the lifecycle events through an {@link AgentStepEventEmitter}. Fail-open: a trace never fails a turn. */
    public static final class Sender {

        private final AgentStepEventEmitter emitter;

        private Sender(AgentStepEventEmitter emitter) {
            this.emitter = emitter;
        }

        /** The reply ended; see {@link #FINISH_REASONS}. {@code detail} (the provider's own wording) may be null. */
        public void finish(String finishReason, String detail) {
            send(() -> AgentStepEventPayloads.finish(finishReason, detail));
        }

        /** The same model is being called again: {@code attempt} is the try about to run (1 = the first). */
        public void retry(int attempt, int maxAttempts, String reason) {
            send(() -> AgentStepEventPayloads.retry(attempt, maxAttempts, reason));
        }

        /** The turn moved to another model; {@code toModel} may be null when it is not known by name. */
        public void failover(String toModel, String reason) {
            send(() -> AgentStepEventPayloads.failover(toModel, reason));
        }

        /** Another model pass carries the reply on; {@code reason} is one of {@link #CONTINUATION_REASONS}. */
        public void continuation(int pass, int maxPasses, String reason) {
            send(() -> AgentStepEventPayloads.continuation(pass, maxPasses, reason));
        }

        /** A tool call is held for a person; {@code approvalId} is the pending request's id. */
        public void approvalRequired(String tool, String approvalId) {
            send(() -> AgentStepEventPayloads.approvalRequired(tool, approvalId));
        }

        private void send(Supplier<Map<String, Object>> event) {
            if (emitter == null || !emitter.isEnabled()) {
                return;
            }
            try {
                emitter.emit(event.get());
            } catch (RuntimeException ignored) {
                // A step event is a trace; losing one never fails the turn.
            }
        }
    }
}
