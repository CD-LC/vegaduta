package com.agenticai.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class StalePendingApprovalWarningTest {

    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

    private final ApprovalRequestRepository repository = mock(ApprovalRequestRepository.class);
    private final TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);

    @SuppressWarnings("unchecked")
    private StalePendingApprovalWarning warning(Duration ttl) {
        doAnswer(
                        call -> {
                            ((Consumer<String>) call.getArgument(0)).accept("tenant_a");
                            return null;
                        })
                .when(iterator)
                .forEachActiveTenantSchema(any());
        ApprovalExpiryProperties properties = new ApprovalExpiryProperties();
        properties.setTtl(ttl);
        return new StalePendingApprovalWarning(properties, iterator, repository);
    }

    @Test
    void findsPendingRequestsOlderThanSevenDaysWhenTheTtlIsZero() {
        when(repository.countByStatusAndCreatedAtBefore(
                        eq(ApprovalStatus.PENDING), eq(NOW.minus(Duration.ofDays(7)))))
                .thenReturn(3L);

        assertThat(warning(Duration.ZERO).warnIfStale(NOW)).isEqualTo(3);
    }

    @Test
    void staysQuietWhenExpiryIsOn() {
        when(repository.countByStatusAndCreatedAtBefore(any(), any())).thenReturn(3L);

        assertThat(warning(Duration.ofHours(24)).warnIfStale(NOW)).isZero();
    }

    @Test
    void aFailingCountNeverPropagates() {
        when(repository.countByStatusAndCreatedAtBefore(any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(warning(Duration.ZERO).warnIfStale(NOW)).isZero();
    }
}
