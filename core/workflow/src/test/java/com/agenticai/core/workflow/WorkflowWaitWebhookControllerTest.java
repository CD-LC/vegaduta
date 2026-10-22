package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The public signed resume URL: forged/disabled = 404, replay = 409, limits, and tenant scoping
 * around the call.
 */
class WorkflowWaitWebhookControllerTest {

    private final WorkflowWaitResumeTokens tokens =
            new WorkflowWaitResumeTokens("platform-key", "https://api.example.com");
    private WorkflowWaitResumeService resumeService;
    private WorkflowPublicRateLimiter rateLimiter;
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resumeService = mock(WorkflowWaitResumeService.class);
        rateLimiter = mock(WorkflowPublicRateLimiter.class);
        when(rateLimiter.tryAcquire(anyString(), anyString(), anyLong())).thenReturn(true);
    }

    private WorkflowWaitWebhookController controller(boolean enabled, int maxBodyChars) {
        return new WorkflowWaitWebhookController(
                tokens,
                resumeService,
                rateLimiter,
                com.agenticai.core.json.JsonMappers.plain(),
                enabled,
                30,
                maxBodyChars);
    }

    private String token() {
        return tokens.createToken("tenant_a", runId, "w1");
    }

    @Test
    void aValidTokenResumesTheRunInsideTheTokensTenantAndClearsItAfter() {
        AtomicReference<String> tenantDuringCall = new AtomicReference<>();
        when(resumeService.resumeByWebhook(eq(runId), eq("w1"), any()))
                .thenAnswer(
                        invocation -> {
                            tenantDuringCall.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null));
                            return WorkflowWaitResumeService.ResumeOutcome.RESUMED;
                        });

        ResponseEntity<?> response =
                controller(true, 65_536).resume(token(), Map.of("approved", true));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("tenant_a", tenantDuringCall.get());
        assertFalse(TenantContext.current().isPresent());
        verify(resumeService).resumeByWebhook(runId, "w1", Map.of("approved", true));
    }

    @Test
    void aForgedOrGarbageTokenIsA404AndTouchesNothing() {
        WorkflowWaitWebhookController c = controller(true, 65_536);
        String valid = token();

        assertEquals(HttpStatus.NOT_FOUND, c.resume("garbage", null).getStatusCode());
        assertEquals(
                HttpStatus.NOT_FOUND,
                c.resume(valid.substring(0, valid.length() - 3) + "abc", null).getStatusCode());
        verify(resumeService, never()).resumeByWebhook(any(), anyString(), any());
    }

    @Test
    void withTheFeatureOffEvenAValidTokenIsA404() {
        assertEquals(
                HttpStatus.NOT_FOUND,
                controller(false, 65_536).resume(token(), null).getStatusCode());
        verify(resumeService, never()).resumeByWebhook(any(), anyString(), any());
    }

    @Test
    void aReplayedUrlIsAConflictNotAnError() {
        when(resumeService.resumeByWebhook(any(), anyString(), any()))
                .thenReturn(WorkflowWaitResumeService.ResumeOutcome.NOT_WAITING);

        assertEquals(
                HttpStatus.CONFLICT,
                controller(true, 65_536).resume(token(), null).getStatusCode());
    }

    @Test
    void theRateLimitAndTheBodyCapApplyBeforeAnyResume() {
        when(rateLimiter.tryAcquire(anyString(), anyString(), anyLong())).thenReturn(false);
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                controller(true, 65_536).resume(token(), null).getStatusCode());

        when(rateLimiter.tryAcquire(anyString(), anyString(), anyLong())).thenReturn(true);
        assertEquals(
                HttpStatus.PAYLOAD_TOO_LARGE,
                controller(true, 10)
                        .resume(token(), Map.of("data", "x".repeat(100)))
                        .getStatusCode());

        verify(resumeService, never()).resumeByWebhook(any(), anyString(), any());
    }

    @Test
    void anInternalFailureIsA503AndStillClearsTheTenant() {
        when(resumeService.resumeByWebhook(any(), anyString(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                controller(true, 65_536).resume(token(), null).getStatusCode());
        assertFalse(TenantContext.current().isPresent());
    }
}
