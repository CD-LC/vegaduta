package com.agenticai.core.voice;

import com.agenticai.core.speech.call.CallSpeechQuotaExceededException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.codec.ServerSentEvent;

/**
 * The server-sent events {@link VoiceCallController#turn} streams and the person-facing wording of
 * its {@code error} event. Extracted from {@code VoiceCallController} (extract-only, no behavior
 * change) so that class stays under the file-size ratchet.
 */
final class VoiceTurnEvents {

    private VoiceTurnEvents() {}

    static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    static final ObjectMapper SSE_MAPPER = com.agenticai.core.json.JsonMappers.plain();

    static String toJson(Object value) {
        try {
            return SSE_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize voice turn SSE payload", e);
        }
    }

    /**
     * The human half of an {@code error} event - deliberately generic rather than the raw exception
     * message, same posture as {@code GlobalExceptionHandler} takes for anything not explicitly
     * mapped: an upstream provider's internal error text is not something to hand straight to an
     * end user mid-call.
     */
    static String midStreamFailureMessage(Throwable error) {
        return "The voice reply was interrupted. Please try again.";
    }

    /**
     * Distinct wording for a {@code VoiceQuotaExceededException} reaching the top of the streaming
     * pipeline (2026-08-08 audit fix #1) - same "distinct message, same {@code error} SSE event"
     * idiom {@link #absoluteTimeoutMessage} already established for telling one failure reason
     * apart from another on this single event type. Passes {@code VoiceQuotaService}'s own
     * visitor-facing message straight through - it already says exactly what plan-tier action to
     * take (see {@code VoiceQuotaService#checkAllowed}'s javadoc), so the frontend/user sees
     * "upgrade for a higher limit" instead of a generic interruption.
     */
    static String quotaExceededMessage(CallSpeechQuotaExceededException e) {
        return e.getMessage();
    }

    /**
     * Part 5(d)'s absolute-ceiling message - distinct wording from {@link #midStreamFailureMessage}
     * so a person (and a support-log grep on "absolute ceiling" above) can tell "the reply itself
     * failed" apart from "the reply was taking too long and this server gave up waiting," even
     * though both currently render through the same generic {@code error} SSE event client-side.
     */
    static String absoluteTimeoutMessage() {
        return "That reply is taking longer than expected. Please try again with a shorter question.";
    }
}
