package com.agenticai.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import com.agenticai.core.omnichannel.session.InboundRateLimitedException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** The shared inbound-channel turn cap surfaces as 429 + Retry-After with the friendly text, never the limit. */
class GlobalExceptionHandlerInboundRateLimitTest {

    private final ClientRequestExceptionHandler handler =
            new ClientRequestExceptionHandler(
                    mock(DiagnosticService.class),
                    mock(AiDiagnosticAnalysisService.class),
                    new ErrorResponseFactory(true, ErrorResponseFactory.DEFAULT_TYPE_PREFIX));

    @Test
    void inboundRateLimitIs429WithRetryAfterAndTheFriendlyMessage() {
        ResponseEntity<Map<String, Object>> res =
                handler.handleInboundRateLimited(new InboundRateLimitedException(42, false));

        assertEquals(429, res.getStatusCode().value());
        assertEquals("42", res.getHeaders().getFirst("Retry-After"));
        String message = String.valueOf(res.getBody().get("error"));
        assertEquals(InboundRateLimitedException.FRIENDLY_MESSAGE, message);
        assertFalse(message.matches(".*\\d.*"), "the message must not reveal a limit or a count");
    }
}
