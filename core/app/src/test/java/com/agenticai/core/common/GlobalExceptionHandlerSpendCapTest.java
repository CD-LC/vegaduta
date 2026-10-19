package com.agenticai.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.spendcap.SpendCapReachedException;
import com.agenticai.core.security.ImpersonationReadOnlyException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Contract bodies for the spend cap (402) and read-only view-as (403), in the client-request
 * advice.
 */
class GlobalExceptionHandlerSpendCapTest {

    private final ClientRequestExceptionHandler handler =
            new ClientRequestExceptionHandler(
                    mock(DiagnosticService.class),
                    mock(AiDiagnosticAnalysisService.class),
                    new ErrorResponseFactory(true, ErrorResponseFactory.DEFAULT_TYPE_PREFIX));

    @Test
    void spendCapIs402WithTheContractBody() {
        ResponseEntity<Map<String, Object>> res =
                handler.handleSpendCapReached(
                        new SpendCapReachedException(
                                new BigDecimal("50.00"),
                                new BigDecimal("50.1200"),
                                Instant.parse("2026-11-01T00:00:00Z")));
        assertEquals(402, res.getStatusCode().value());
        Map<String, Object> body = res.getBody();
        assertEquals("spend_cap_reached", body.get("error"));
        assertEquals(new BigDecimal("50.00"), body.get("capUsd"));
        assertEquals(new BigDecimal("50.1200"), body.get("monthToDateUsd"));
        assertEquals("2026-11-01T00:00:00Z", body.get("resetsAt"));
        assertEquals(true, ((String) body.get("message")).contains("$50.00"));
    }

    @Test
    void readOnlyViewAsIs403() {
        ResponseEntity<Map<String, Object>> res =
                handler.handleImpersonationReadOnly(
                        new ImpersonationReadOnlyException("email-send", UUID.randomUUID()));
        assertEquals(403, res.getStatusCode().value());
        assertEquals("impersonation_read_only", res.getBody().get("error"));
    }
}
