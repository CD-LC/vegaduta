package com.agenticai.core.agents.job.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobStatus;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

/**
 * Pins what the database actually enforces. The compare-and-set guards live in JPQL strings, so a test that
 * only mocks the repository cannot catch someone deleting a guard; these assertions read the statements.
 */
class JpaJobLeaseStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    private static String jpql(String method, Class<?>... params) throws Exception {
        Method m = AgentJobRepository.class.getMethod(method, params);
        return m.getAnnotation(Query.class).value();
    }

    @Test
    void renewalOnlyTouchesARunningRowOwnedByTheCaller() throws Exception {
        String q = jpql("renewLeaseInternal", UUID.class, String.class, Instant.class, AgentJobStatus.class);
        assertThat(q).contains("j.status = :running").contains("j.ownerInstance = :owner").contains("j.heartbeatAt = :now");
    }

    @Test
    void releaseOnlyTouchesARunningRowOwnedByTheCaller() throws Exception {
        String q = jpql("releaseLeaseInternal", UUID.class, String.class, Instant.class, AgentJobStatus.class);
        assertThat(q).contains("j.status = :running").contains("j.ownerInstance = :owner")
            .contains("j.heartbeatAt = :releasedAt");
    }

    @Test
    void adoptionIsGuardedOnStatusObservedAttemptAndAnExpiredLeaseAndNeverMatchesANullHeartbeat() throws Exception {
        String q = jpql("requeueIfLeaseExpiredInternal", UUID.class, int.class, Instant.class, String.class,
            AgentJobStatus.class, AgentJobStatus.class);
        assertThat(q).contains("j.status = :from").contains("j.attemptCount = :attempt")
            .contains("j.heartbeatAt < :cutoff");
        assertThat(q).doesNotContain("heartbeatAt is null");
        // clears everything that identifies the dead attempt, but never the attempt counter (the resume bound)
        assertThat(q).contains("j.ownerInstance = null").contains("j.heartbeatAt = null")
            .contains("j.startedAt = null").contains("j.deadlineAt = null");
        assertThat(q).doesNotContain("j.attemptCount = j.attemptCount");
    }

    @Test
    void failingAndCancellingAnExpiredLeaseCarryTheSameGuards() throws Exception {
        String fail = jpql("failIfLeaseExpiredInternal", UUID.class, int.class, Instant.class, String.class,
            String.class, Instant.class, AgentJobStatus.class, AgentJobStatus.class);
        assertThat(fail).contains("j.status = :from").contains("j.attemptCount = :attempt")
            .contains("j.heartbeatAt < :cutoff");
        String cancel = jpql("cancelIfLeaseExpiredInternal", UUID.class, int.class, Instant.class, Instant.class,
            AgentJobStatus.class, AgentJobStatus.class);
        assertThat(cancel).contains("j.status = :from").contains("j.attemptCount = :attempt")
            .contains("j.heartbeatAt < :cutoff").contains("j.cancelRequested = true");
    }

    @Test
    void theExpiredLeaseReadIsBoundedAndOldestFirst() throws Exception {
        Method m = AgentJobRepository.class.getMethod(
            "findExpiredLeases", AgentJobStatus.class, Instant.class, Pageable.class);
        String q = m.getAnnotation(Query.class).value();
        assertThat(q).contains("j.status = :status").contains("j.heartbeatAt < :cutoff")
            .contains("order by j.heartbeatAt asc");
    }

    @Test
    void theStoreDelegatesEachCompareAndSetAndMapsRowcountsToBooleans() {
        AgentJobRepository repository = mock(AgentJobRepository.class);
        JpaJobLeaseStore store = new JpaJobLeaseStore(repository);
        UUID id = UUID.randomUUID();
        Instant cutoff = NOW.minusSeconds(65);

        when(repository.renewLease(id, "me", NOW)).thenReturn(1);
        when(repository.releaseLease(id, "me", JobLeasePolicy.RELEASED_AT)).thenReturn(0);
        when(repository.requeueIfLeaseExpired(id, 2, cutoff, "why")).thenReturn(1);
        when(repository.failIfLeaseExpired(id, 2, cutoff, "CODE", "msg", NOW)).thenReturn(0);
        when(repository.cancelIfLeaseExpired(id, 2, cutoff, NOW)).thenReturn(1);

        assertThat(store.renew(id, "me", NOW)).isTrue();
        assertThat(store.release(id, "me", JobLeasePolicy.RELEASED_AT)).isFalse();
        assertThat(store.requeueExpired(id, 2, cutoff, "why")).isTrue();
        assertThat(store.failExpired(id, 2, cutoff, "CODE", "msg", NOW)).isFalse();
        assertThat(store.cancelExpired(id, 2, cutoff, NOW)).isTrue();
    }

    @Test
    void viewCombinesStatusAndOwnerAndIsEmptyForAGoneRow() {
        AgentJobRepository repository = mock(AgentJobRepository.class);
        JpaJobLeaseStore store = new JpaJobLeaseStore(repository);
        UUID id = UUID.randomUUID();
        when(repository.findStatusById(id)).thenReturn(Optional.of(AgentJobStatus.RUNNING));
        when(repository.findOwnerInstanceById(id)).thenReturn(Optional.of("other:1:ab"));
        assertThat(store.view(id)).contains(new JobLeaseStore.LeaseView(AgentJobStatus.RUNNING, "other:1:ab"));

        UUID gone = UUID.randomUUID();
        when(repository.findStatusById(gone)).thenReturn(Optional.empty());
        assertThat(store.view(gone)).isEmpty();
    }

    @Test
    void findExpiredMapsEntitiesToSnapshotsWithTheRequestedLimit() {
        AgentJobRepository repository = mock(AgentJobRepository.class);
        JpaJobLeaseStore store = new JpaJobLeaseStore(repository);
        AgentJob job = AgentJob.queued(UUID.randomUUID(), "t", UUID.randomUUID(), "goal", "alice", "alice",
            Set.of("ROLE_user"), AgentJobOrigin.API, null, 60, 1, 15, null);
        job.markRunning("dead:1:aa", NOW.minusSeconds(900));
        when(repository.findExpiredLeases(eq(AgentJobStatus.RUNNING), eq(NOW), any(Pageable.class)))
            .thenReturn(List.of(job));

        List<JobLeaseStore.LeasedJob> out = store.findExpired(NOW, 7);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).id()).isEqualTo(job.getId());
        assertThat(out.get(0).ownerInstance()).isEqualTo("dead:1:aa");
        assertThat(out.get(0).attemptCount()).isEqualTo(1);
        org.mockito.ArgumentCaptor<Pageable> page = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findExpiredLeases(eq(AgentJobStatus.RUNNING), eq(NOW), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(7);
    }
}
