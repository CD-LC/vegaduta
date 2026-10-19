package com.agenticai.core.documentgen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.documentgen.DocumentGenerationQuotaExceededException;
import com.agenticai.core.documentgen.model.DocumentGenerationStatus;
import com.agenticai.core.documentgen.repository.DocumentGenerationJobRepository;
import com.agenticai.core.documentgen.service.DocumentGenerationQuotaService;
import com.agenticai.core.documentgen.service.DocumentGenerationService;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** A refused document request is a readable 403/503/429, never a 500. */
class DocumentGenerationQuotaMappingTest {

    private final DocumentGenerationController controller =
        new DocumentGenerationController(mock(DocumentGenerationService.class), mock(FileStorageService.class));

    private DocumentGenerationQuotaService quotaService(boolean enabled, long usedToday) {
        DocumentGenerationJobRepository jobs = mock(DocumentGenerationJobRepository.class);
        when(jobs.countByTenantIdAndCreatedAtAfterAndStatusNot(
            anyString(), any(Instant.class), any(DocumentGenerationStatus.class))).thenReturn(usedToday);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenReturn(enabled);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        return new DocumentGenerationQuotaService(jobs, settings, true, 50, 500);
    }

    private ResponseEntity<Map<String, Object>> handle(DocumentGenerationQuotaExceededException e) {
        return controller.quotaExceeded(e);
    }

    @Test
    void freePlanIs403WithTheUpgradeMessage() {
        DocumentGenerationQuotaExceededException e = assertThrows(DocumentGenerationQuotaExceededException.class,
            () -> quotaService(true, 0).checkAllowed(TenantPlan.FREE, "t1"));
        assertEquals(DocumentGenerationQuotaExceededException.Reason.PLAN, e.getReason());
        ResponseEntity<Map<String, Object>> response = handle(e);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("paid feature"));
    }

    @Test
    void featureSwitchedOffIs503() {
        DocumentGenerationQuotaExceededException e = assertThrows(DocumentGenerationQuotaExceededException.class,
            () -> quotaService(false, 0).checkAllowed(TenantPlan.PREMIUM, "t1"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, handle(e).getStatusCode());
    }

    @Test
    void anExhaustedDailyQuotaIs429WithAReadableMessage() {
        DocumentGenerationQuotaExceededException e = assertThrows(DocumentGenerationQuotaExceededException.class,
            () -> quotaService(true, 50).checkAllowed(TenantPlan.PREMIUM, "t1"));
        assertEquals(DocumentGenerationQuotaExceededException.Reason.LIMIT, e.getReason());
        ResponseEntity<Map<String, Object>> response = handle(e);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("today's document generation limit"));
        assertEquals(429, response.getBody().get("status"));
    }

    @Test
    void theOneArgumentConstructorStaysACapRefusal() {
        assertEquals(DocumentGenerationQuotaExceededException.Reason.LIMIT,
            new DocumentGenerationQuotaExceededException("x").getReason());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
            handle(new DocumentGenerationQuotaExceededException("x")).getStatusCode());
    }
}
