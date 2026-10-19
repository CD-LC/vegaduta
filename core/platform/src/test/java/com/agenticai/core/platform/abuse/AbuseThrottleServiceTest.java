package com.agenticai.core.platform.abuse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AbuseThrottleService} is the reversible enforcement arm of the P5
 * anomaly layer - the two regressions worth guarding against are (1) a
 * throttle that never expires (a principal stays punished after the TTL, the
 * exact opposite of the documented fail-OPEN posture) and (2) an audit-layer
 * failure taking down the safeguard itself. Expiry is tested with an
 * already-in-the-past TTL rather than sleeping - the lazy-purge branch is
 * identical either way and the suite stays instant.
 */
class AbuseThrottleServiceTest {

    private AuditLogService auditLogService;
    private AbuseThrottleService service;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        service = new AbuseThrottleService(auditLogService);
    }

    @Test
    void principalKeyUsesWildcardForNullUser() {
        // The tenant-wide marker: userId=null must map to a stable key, not "tenant:null".
        assertEquals("t1:*", AbuseThrottleService.principalKey("t1", null));
        assertEquals("t1:u1", AbuseThrottleService.principalKey("t1", "u1"));
    }

    @Test
    void throttleMakesThePrincipalThrottledAndIsAudited() {
        service.throttle("t1", "u1", "spend spike", Duration.ofMinutes(30), "admin@x");

        assertTrue(service.isThrottled("t1", "u1"));
        // A different user in the same tenant must NOT be caught by a per-user marker.
        assertFalse(service.isThrottled("t1", "other"));
        verify(auditLogService).record(
            eq("admin@x"), eq("abuse.throttle.set"), eq("abuse-principal"), eq("t1:u1"),
            eq(AuditOutcome.SUCCESS), contains("spend spike"));
    }

    @Test
    void expiredThrottleReadsAsFalseAndIsPurged() {
        service.throttle("t1", "u1", "old", Duration.ofMillis(-1), "admin@x");

        assertFalse(service.isThrottled("t1", "u1"));
        // The lazy purge must also keep the expired entry out of the admin snapshot.
        assertTrue(service.current().isEmpty());
    }

    @Test
    void clearUnthrottlesAndAuditsEvenWhenNothingWasThrottled() {
        service.throttle("t1", "u1", "reason", Duration.ofMinutes(5), "admin@x");
        service.clear("t1", "u1", "admin@x");

        assertFalse(service.isThrottled("t1", "u1"));
        verify(auditLogService).record(
            eq("admin@x"), eq("abuse.throttle.clear"), eq("abuse-principal"), eq("t1:u1"),
            eq(AuditOutcome.SUCCESS), eq("Cleared throttle"));

        // The no-op clear is still audited (the reversibility paper trail) and must not throw.
        assertDoesNotThrow(() -> service.clear("t2", "nobody", "admin@x"));
        verify(auditLogService).record(
            eq("admin@x"), eq("abuse.throttle.clear"), eq("abuse-principal"), eq("t2:nobody"),
            eq(AuditOutcome.SUCCESS), eq("Cleared throttle (was not throttled)"));
    }

    @Test
    void currentListsOnlyLiveThrottlesWithReasonAndExpiry() {
        service.throttle("t1", "u1", "flooding", Duration.ofMinutes(10), "admin@x");
        service.throttle("t2", null, "expired", Duration.ofMillis(-1), "admin@x");

        List<AbuseThrottleService.ThrottleEntry> entries = service.current();

        assertEquals(1, entries.size());
        assertEquals("t1:u1", entries.get(0).principal());
        assertEquals("flooding", entries.get(0).reason());
    }

    @Test
    void auditFailureNeverBreaksTheSafeguard() {
        // Documented posture: "auditing must never break the safeguard itself."
        // If the swallow regressed, an audit-DB outage would make throttling
        // (and un-throttling!) throw at every call site.
        when(auditLogService.record(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
            .thenThrow(new RuntimeException("audit db down"));

        assertDoesNotThrow(() -> service.throttle("t1", "u1", "r", Duration.ofMinutes(5), "a"));
        assertTrue(service.isThrottled("t1", "u1"));
        assertDoesNotThrow(() -> service.clear("t1", "u1", "a"));
        assertFalse(service.isThrottled("t1", "u1"));
    }
}
