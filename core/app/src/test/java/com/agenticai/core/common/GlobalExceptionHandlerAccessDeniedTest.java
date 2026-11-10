package com.agenticai.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;

/**
 * A failed {@code @PreAuthorize} is an {@code AuthorizationDeniedException}. It must come back as a
 * 403 with the generic message. Before this handler existed, {@code FallbackExceptionHandler}'s
 * {@code Exception.class} answered it with a 500 "Something went wrong" and recorded a platform
 * issue.
 */
class GlobalExceptionHandlerAccessDeniedTest {

    private final ClientRequestExceptionHandler handler =
            new ClientRequestExceptionHandler(
                    mock(DiagnosticService.class),
                    mock(AiDiagnosticAnalysisService.class),
                    new ErrorResponseFactory(true, ErrorResponseFactory.DEFAULT_TYPE_PREFIX));

    @Test
    void aMethodSecurityDenialIs403WithTheGenericMessage() {
        AccessDeniedException denied =
                new AuthorizationDeniedException("Access Denied", new AuthorizationDecision(false));

        ResponseEntity<Map<String, Object>> res = handler.handleAccessDenied(denied);

        assertEquals(403, res.getStatusCode().value());
        assertEquals(
                "You do not have permission to perform this action.", res.getBody().get("error"));
    }

    @Test
    void theExceptionTextIsNeverEchoed() {
        ResponseEntity<Map<String, Object>> res =
                handler.handleAccessDenied(new AccessDeniedException("secret-internal-detail"));

        assertFalse(res.getBody().toString().contains("secret-internal-detail"));
    }
}
