package com.agenticai.core.devices.sentinel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.server.ResponseStatusException;

class MobileSentinelServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<MobileSentinelEvent> store = new ArrayList<>();
    private MobileSentinelPolicy policyRow;

    private MobileSentinelPolicyRepository policies;
    private MobileSentinelEventRepository events;
    private NotificationService notifications;
    private JavaMailSender mailSender;
    private MobileSentinelService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        policies = mock(MobileSentinelPolicyRepository.class);
        when(policies.findById(MobileSentinelPolicy.SINGLETON_ID)).thenAnswer(inv -> Optional.ofNullable(policyRow));
        when(policies.save(any())).thenAnswer(inv -> {
            policyRow = inv.getArgument(0);
            return policyRow;
        });

        events = mock(MobileSentinelEventRepository.class);
        when(events.saveAndFlush(any())).thenAnswer(inv -> put(inv.getArgument(0)));
        when(events.save(any())).thenAnswer(inv -> put(inv.getArgument(0)));
        when(events.existsByUserIdAndClientEventId(anyString(), anyString())).thenAnswer(inv -> store.stream()
            .anyMatch(e -> e.getUserId().equals(inv.getArgument(0)) && e.getClientEventId().equals(inv.getArgument(1))));
        when(events.findFirstByUserIdAndSentinelIdAndEscalatedAtIsNotNullOrderByEscalatedAtDesc(anyString(), any()))
            .thenAnswer(inv -> store.stream()
                .filter(e -> e.getUserId().equals(inv.getArgument(0)))
                .filter(e -> java.util.Objects.equals(e.getSentinelId(), inv.getArgument(1)))
                .filter(e -> e.getEscalatedAt() != null)
                .max(Comparator.comparing(MobileSentinelEvent::getEscalatedAt)));
        when(events.deleteAllOfUser(anyString())).thenAnswer(inv -> {
            int before = store.size();
            store.removeIf(e -> e.getUserId().equals(inv.getArgument(0)));
            return before - store.size();
        });

        notifications = mock(NotificationService.class);
        ObjectProvider<NotificationService> np = mock(ObjectProvider.class);
        when(np.getIfAvailable()).thenReturn(notifications);

        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        ObjectProvider<JavaMailSender> mp = mock(ObjectProvider.class);
        when(mp.getIfAvailable()).thenReturn(mailSender);

        service = new MobileSentinelService(policies, events, np, mp, mapper, "no-reply@test");
        as("alice", Set.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MobileSentinelEvent put(MobileSentinelEvent e) {
        store.removeIf(s -> s.getId().equals(e.getId()));
        store.add(e);
        return e;
    }

    private static void as(String user, Set<String> roles) {
        TenantContext.set(TenantContext.of("acme", user, roles));
    }

    private void policy(boolean sync, boolean raw, List<String> kinds, boolean escalation) {
        as("admin", Set.of("ROLE_tenant-admin"));
        service.updatePolicy(new MobileSentinelService.PolicyRequest(true, sync, raw, kinds, escalation, 30));
        as("alice", Set.of());
    }

    private MobileSentinelService.EventIn event(
        String id, String sentinelId, String severity, List<String> kinds, JsonNode readings,
        MobileSentinelService.Escalate escalate
    ) {
        return new MobileSentinelService.EventIn(id, sentinelId, "Heart Guardian", "HEART_GUARDIAN", severity,
            "Resting heart rate high", "Your resting HR is above baseline.", kinds,
            mapper.createArrayNode().add(mapper.createObjectNode().put("kind", "RESTING_HEART_RATE").put("value", 88.0)),
            readings, Instant.now().minus(5, ChronoUnit.MINUTES), escalate);
    }

    private JsonNode readings() {
        return mapper.createArrayNode().add(mapper.createObjectNode()
            .put("kind", "RESTING_HEART_RATE").put("value", 88.0).put("at", "2026-09-29T06:10:00Z"));
    }

    private static final MobileSentinelService.Escalate ASHA =
        new MobileSentinelService.Escalate("Asha", "asha@example.com");

    // ------------------------------------------------------------------

    @Test
    void defaultsWhenNoPolicyRow() {
        MobileSentinelService.PolicyView p = service.getPolicy();
        assertTrue(p.enabled());
        assertFalse(p.syncEnabled());
        assertFalse(p.allowRawReadings());
        assertFalse(p.caregiverEscalationEnabled());
        assertEquals(30, p.retentionDays());
        assertEquals(SentinelKind.values().length, p.allowedKinds().size());
        assertNull(p.updatedBy());
        assertNull(p.updatedAt());
    }

    @Test
    void putRejectedForNonAdminAndValidatesKindsForAdmin() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.updatePolicy(
            new MobileSentinelService.PolicyRequest(true, true, false, null, false, 30)));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(policies, never()).save(any());

        as("root", Set.of("ROLE_platform-admin"));
        ResponseStatusException bad = assertThrows(ResponseStatusException.class, () -> service.updatePolicy(
            new MobileSentinelService.PolicyRequest(true, true, false, List.of("HEART_RATE", "NOPE"), false, 30)));
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());

        MobileSentinelService.PolicyView ok = service.updatePolicy(
            new MobileSentinelService.PolicyRequest(true, true, false, List.of("heart_rate"), false, 14));
        assertEquals(List.of("HEART_RATE"), ok.allowedKinds());
        assertEquals("root", ok.updatedBy());
        assertNotNull(ok.updatedAt());
    }

    @Test
    void ingestForbiddenWhenSyncDisabled() {
        assertThrows(SentinelSyncDisabledException.class, () -> service.ingest(new MobileSentinelService.IngestRequest(
            "Pixel 9", List.of(event("e1", "hg", "INFO", List.of("HEART_RATE"), null, null)))));
        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void kindNotAllowedIsRejectedOthersAccepted() {
        policy(true, false, List.of("HEART_RATE", "RESTING_HEART_RATE"), false);
        MobileSentinelService.IngestResult r = service.ingest(new MobileSentinelService.IngestRequest("Pixel 9", List.of(
            event("e1", "hg", "INFO", List.of("RESTING_HEART_RATE"), null, null),
            event("e2", "hg", "INFO", List.of("BLOOD_GLUCOSE"), null, null),
            event("e3", "hg", "INFO", List.of("NOT_A_KIND"), null, null),
            event("e4", "hg", "LOUD", List.of("HEART_RATE"), null, null))));
        assertEquals(1, r.accepted());
        assertEquals(3, r.rejected().size());
        assertEquals(new MobileSentinelService.Rejection("e2", "kind-not-allowed"), r.rejected().get(0));
        assertEquals("unknown-kind", r.rejected().get(1).reason());
        assertEquals("invalid-severity", r.rejected().get(2).reason());
        assertEquals(1, store.size());
    }

    @Test
    void readingsDroppedWhenRawReadingsNotAllowed() {
        policy(true, false, null, false);
        MobileSentinelService.IngestResult r = service.ingest(new MobileSentinelService.IngestRequest("Pixel 9",
            List.of(event("e1", "hg", "WATCH", List.of("RESTING_HEART_RATE"), readings(), null))));
        assertEquals(1, r.accepted());
        assertTrue(r.readingsDropped());
        assertNull(store.get(0).getReadings());
        assertNotNull(store.get(0).getFindings());

        policy(true, true, null, false);
        MobileSentinelService.IngestResult r2 = service.ingest(new MobileSentinelService.IngestRequest("Pixel 9",
            List.of(event("e2", "hg", "WATCH", List.of("RESTING_HEART_RATE"), readings(), null))));
        assertFalse(r2.readingsDropped());
        assertTrue(store.get(1).getReadings().contains("88.0"));
    }

    @Test
    void duplicateClientEventIdIsIdempotent() {
        policy(true, false, null, true);
        service.ingest(new MobileSentinelService.IngestRequest("Pixel 9",
            List.of(event("e1", "hg", "ALERT", List.of("HEART_RATE"), null, ASHA))));
        verify(mailSender, times(1)).send(any(MimeMessage.class));

        MobileSentinelService.IngestResult r = service.ingest(new MobileSentinelService.IngestRequest("Pixel 9", List.of(
            event("e1", "hg", "ALERT", List.of("HEART_RATE"), null, ASHA),
            event("e2", "other", "INFO", List.of("HEART_RATE"), null, null),
            event("e2", "other", "INFO", List.of("HEART_RATE"), null, null))));
        assertEquals(3, r.accepted());
        assertEquals(2, r.duplicates());
        assertEquals(0, r.escalated());
        assertEquals(2, store.size());
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void escalationStopsOnceThePerUserHourlyBudgetIsSpent() {
        // Rotating sentinelId defeats the per-sentinel cooldown; the per-user cap must not be defeatable.
        policy(true, false, null, true);
        when(events.countByUserIdAndEscalatedAtGreaterThanEqual(eq("alice"), any()))
            .thenReturn((long) MobileSentinelService.MAX_ESCALATIONS_PER_USER_PER_HOUR);
        MobileSentinelService.IngestResult r = service.ingest(new MobileSentinelService.IngestRequest("Pixel 9",
            List.of(event("b1", "s1", "CRITICAL", List.of("HEART_RATE"), null, ASHA))));
        assertEquals(1, r.accepted());
        assertEquals(0, r.escalated());
        verify(mailSender, times(0)).send(any(MimeMessage.class));
    }

    @Test
    void escalationNeedsAlertSeverityPolicyAndRespectsRateLimit() {
        // Policy off: an ALERT with an escalate block does nothing.
        policy(true, false, null, false);
        MobileSentinelService.IngestResult off = service.ingest(new MobileSentinelService.IngestRequest("Pixel 9",
            List.of(event("e0", "hg", "CRITICAL", List.of("HEART_RATE"), null, ASHA))));
        assertEquals(0, off.escalated());

        policy(true, false, null, true);
        MobileSentinelService.IngestResult r = service.ingest(new MobileSentinelService.IngestRequest("Pixel 9", List.of(
            event("e1", "hg", "WATCH", List.of("HEART_RATE"), null, ASHA),   // below ALERT
            event("e2", "hg", "ALERT", List.of("HEART_RATE"), null, ASHA),   // escalates
            event("e3", "hg", "CRITICAL", List.of("HEART_RATE"), null, ASHA), // same sentinel within 30 min
            event("e4", "sleep", "ALERT", List.of("SLEEP"), null, null))));    // no escalate block
        assertEquals(4, r.accepted());
        assertEquals(1, r.escalated());

        MobileSentinelEvent e2 = store.stream().filter(e -> e.getClientEventId().equals("e2")).findFirst().orElseThrow();
        assertNotNull(e2.getEscalatedAt());
        assertEquals("asha@example.com", e2.getEscalatedTo());

        ArgumentCaptor<MimeMessage> mail = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(1)).send(mail.capture());
        try {
            assertTrue(mail.getValue().getSubject().startsWith("VegaDūta Sentinel alert: "));
            String body = mail.getValue().getContent().toString();
            assertTrue(body.contains("not a medical device"));
            assertTrue(body.contains("Heart Guardian"));
            assertFalse(body.contains("\"value\""), "no raw readings/findings in the email");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        verify(notifications, times(1)).create(eq("alice"), eq(NotificationCategory.SAFETY), anyString(),
            anyString(), eq(NotificationSeverity.WARNING), isNull(), isNull());

        // Invalid caregiver address: no email, but the user's bell still fires on another sentinel.
        MobileSentinelService.IngestResult bad = service.ingest(new MobileSentinelService.IngestRequest("Pixel 9",
            List.of(event("e5", "bp", "ALERT", List.of("BLOOD_PRESSURE"), null,
                new MobileSentinelService.Escalate("X", "not-an-email\r\nBcc: evil@x.com")))));
        assertEquals(1, bad.escalated());
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void eraseDeletesOnlyOwnRows() {
        policy(true, false, null, false);
        service.ingest(new MobileSentinelService.IngestRequest("Pixel 9",
            List.of(event("a1", "hg", "INFO", List.of("HEART_RATE"), null, null))));
        as("bob", Set.of());
        service.ingest(new MobileSentinelService.IngestRequest("Pixel 8",
            List.of(event("b1", "hg", "INFO", List.of("HEART_RATE"), null, null))));

        as("alice", Set.of());
        assertEquals(1, service.eraseOwn());
        verify(events).deleteAllOfUser("alice");
        assertEquals(1, store.size());
        assertEquals("bob", store.get(0).getUserId());
    }

    @Test
    void adminSummaryIsAdminOnly() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.adminSummary());
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        as("admin", Set.of("ROLE_tenant-admin"));
        when(events.countBySeveritySince(any())).thenReturn(List.<Object[]>of(new Object[] {"ALERT", 3L}));
        MobileSentinelService.AdminSummary s = service.adminSummary();
        assertEquals(3L, s.bySeverity().get("ALERT"));
        assertEquals(0L, s.bySeverity().get("INFO"));
        assertEquals(7, s.windowDays());
    }

    @Test
    void noUserIs401() {
        TenantContext.clear();
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.getPolicy());
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
    }
}
