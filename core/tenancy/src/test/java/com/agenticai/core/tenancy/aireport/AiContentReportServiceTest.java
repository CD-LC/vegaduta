package com.agenticai.core.tenancy.aireport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AiContentReportServiceTest {

    private AiContentReportRepository repository;
    private AiContentReportService service;

    @BeforeEach
    void setUp() {
        repository = mock(AiContentReportRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new AiContentReportService(repository);
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of("ROLE_tenant-member")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static AiContentReportRequest request(String surface, String reason, String content) {
        return new AiContentReportRequest(surface, reason, "rude", content, "say something", "agent-7",
            "sess-1", "msg-3", true, "android", "0.5.0");
    }

    @Test
    void validReportIsSavedWithTenantAndReporterFromContext() {
        when(repository.countByReporterIdAndCreatedAtAfter(eq("user-1"), any())).thenReturn(0L);

        AiContentReport saved = service.submit(request("chat", "OFFENSIVE", "bad output"));

        ArgumentCaptor<AiContentReport> captor = ArgumentCaptor.forClass(AiContentReport.class);
        verify(repository).save(captor.capture());
        AiContentReport row = captor.getValue();
        assertEquals("acme", row.getTenantId());
        assertEquals("user-1", row.getReporterId());
        assertEquals("CHAT", row.getSurface());
        assertEquals("OFFENSIVE", row.getReason());
        assertEquals("ANDROID", row.getAppPlatform());
        assertEquals("bad output", row.getContent());
        assertTrue(row.isOnDevice());
        assertEquals("OPEN", saved.getStatus());
    }

    @Test
    void invalidReasonIs400AndNothingSaved() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.submit(request("CHAT", "BORING", "x")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void invalidSurfaceAndMissingContentAre400() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.submit(request("EMAIL", "OTHER", "x"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.submit(request("CHAT", "OTHER", "  "))).getStatusCode());
    }

    @Test
    void longFieldsAreTruncatedNotRejected() {
        when(repository.countByReporterIdAndCreatedAtAfter(anyString(), any())).thenReturn(0L);
        String longText = "x".repeat(20_000);
        AiContentReportRequest req = new AiContentReportRequest("ASSIST", "OTHER", longText, longText, longText,
            longText, longText, longText, false, "IOS", longText);

        service.submit(req);

        ArgumentCaptor<AiContentReport> captor = ArgumentCaptor.forClass(AiContentReport.class);
        verify(repository).save(captor.capture());
        AiContentReport row = captor.getValue();
        assertEquals(8000, row.getContent().length());
        assertEquals(1000, row.getComment().length());
        assertEquals(4000, row.getPrompt().length());
        assertEquals(128, row.getAgentId().length());
        assertEquals(255, row.getSessionId().length());
        assertEquals(255, row.getMessageRef().length());
        assertEquals(32, row.getAppVersion().length());
        assertFalse(row.isOnDevice());
    }

    @Test
    void thirtyFirstReportInAnHourIs429() {
        when(repository.countByReporterIdAndCreatedAtAfter(eq("user-1"), any())).thenReturn(30L);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.submit(request("CHAT", "HATEFUL", "x")));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void listAndPatchAre403ForNonPlatformAdmin() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.list("OPEN", 50)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.review(UUID.randomUUID(), new AiContentReportReviewRequest("REVIEWED", "ok"))).getStatusCode());
        verify(repository, never()).findByStatusOrderByCreatedAtDesc(anyString(), any());
        verify(repository, never()).findById(any());
    }

    @Test
    void platformAdminCanListAndReview() {
        TenantContext.set(TenantContext.of(null, "admin-1", Set.of("ROLE_platform-admin")));
        AiContentReport report = new AiContentReport("acme", "user-1", "CHAT", "OTHER", null, "c", null,
            null, null, null, false, null, null);
        when(repository.findByStatusOrderByCreatedAtDesc(eq("OPEN"), any(Pageable.class))).thenReturn(List.of(report));
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(report));

        assertEquals(1, service.list("open", 500).size());
        AiContentReport reviewed = service.review(id, new AiContentReportReviewRequest("actioned", "removed agent"));
        assertEquals("ACTIONED", reviewed.getStatus());
        assertEquals("removed agent", reviewed.getReviewNote());
        assertEquals("admin-1", reviewed.getReviewedBy());

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.review(id, new AiContentReportReviewRequest("OPEN", null))).getStatusCode());
        assertNull(AiContentReportService.truncate(null, 5));
    }

    @Test
    void listPagesWithOffsetAndCountsMatchingStatus() {
        TenantContext.set(TenantContext.of(null, "admin-1", Set.of("ROLE_platform-admin")));
        AiContentReport report = new AiContentReport("acme", "user-1", "CHAT", "OTHER", null, "c", null,
            null, null, null, false, null, null);
        when(repository.findByStatusOrderByCreatedAtDesc(eq("OPEN"), any(Pageable.class))).thenReturn(List.of(report));
        when(repository.countByStatus("OPEN")).thenReturn(7L);

        assertEquals(1, service.list("open", 1, 3L).size());
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByStatusOrderByCreatedAtDesc(eq("OPEN"), captor.capture());
        assertEquals(3L, captor.getValue().getOffset());
        assertEquals(1, captor.getValue().getPageSize());
        assertEquals(7L, service.count("open"));
    }
}
