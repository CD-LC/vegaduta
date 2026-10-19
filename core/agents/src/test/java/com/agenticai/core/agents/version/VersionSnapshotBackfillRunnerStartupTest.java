package com.agenticai.core.agents.version;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.tenancy.TenantMigrationGate;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Cold-start lane: with {@code app.startup.background-warmup.enabled=true} the every-boot version backfill runs
 * off the main thread (so it stops holding readiness); with the flag off (default) it is synchronous exactly as
 * before, and a failure in the background copy can never fail start-up.
 */
class VersionSnapshotBackfillRunnerStartupTest {

    private final TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
    private final TenantMigrationGate gate = mock(TenantMigrationGate.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final AgentVersionRepository versions = mock(AgentVersionRepository.class);
    private final AgentVersionSnapshotService snapshots = mock(AgentVersionSnapshotService.class);
    private final Agent agent = mock(Agent.class);

    private VersionSnapshotBackfillRunner runner() {
        when(gate.isMigrated()).thenReturn(true);
        when(agent.getId()).thenReturn(UUID.randomUUID());
        when(agents.findAll()).thenReturn(List.of(agent));
        when(versions.findFirstByAgentIdOrderByVersionNumberDesc(any())).thenReturn(Optional.empty());
        return new VersionSnapshotBackfillRunner(iterator, gate, agents, versions, snapshots);
    }

    @SuppressWarnings("unchecked")
    private void iteratorRunsOneSchemaAfter(CountDownLatch gateLatch) {
        doAnswer(invocation -> {
            if (gateLatch != null) {
                gateLatch.await(5, TimeUnit.SECONDS);
            }
            ((Consumer<String>) invocation.getArgument(0)).accept("tenant_a");
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());
    }

    @Test
    void flagOffBackfillsSynchronouslyBeforeRunReturns() {
        VersionSnapshotBackfillRunner runner = runner();
        iteratorRunsOneSchemaAfter(null);

        runner.run(mock(ApplicationArguments.class));

        // No waiting: the snapshot was written by the time run() returned.
        verify(snapshots).snapshot(any(Agent.class), anyString());
    }

    @Test
    void flagOnReturnsWhileTheBackfillIsStillRunningAndThenFinishesIt() {
        VersionSnapshotBackfillRunner runner = runner();
        ReflectionTestUtils.setField(runner, "backgroundWarmup", true);
        CountDownLatch release = new CountDownLatch(1);
        iteratorRunsOneSchemaAfter(release);

        runner.run(mock(ApplicationArguments.class)); // must return although the iterator is blocked

        verify(snapshots, never()).snapshot(any(Agent.class), anyString());
        release.countDown();
        verify(snapshots, timeout(2000)).snapshot(any(Agent.class), anyString());
    }

    @Test
    void flagOnContainsAFailureSoStartupIsNeverFailedByTheBackfill() {
        VersionSnapshotBackfillRunner runner = runner();
        ReflectionTestUtils.setField(runner, "backgroundWarmup", true);
        doThrow(new IllegalStateException("database unreachable")).when(iterator).forEachActiveTenantSchema(any());

        // No exception reaches the caller (the main thread); the background thread logs and ends.
        runner.run(mock(ApplicationArguments.class));

        verify(iterator, timeout(2000)).forEachActiveTenantSchema(any());
    }
}
