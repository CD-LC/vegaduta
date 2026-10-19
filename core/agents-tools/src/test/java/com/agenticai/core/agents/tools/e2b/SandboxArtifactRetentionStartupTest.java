package com.agenticai.core.agents.tools.e2b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.storage.FileStorageService.StoredObject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SandboxArtifactRetentionStartupTest {

    private final FileStorageService storage = mock(FileStorageService.class);
    private final AtomicLong now = new AtomicLong(10_000_000);
    private final SandboxArtifactRetention retention =
            new SandboxArtifactRetention(storage, 60_000, now::get);

    @Test
    void expiredStoredArtifactsAreDeletedAndLiveOnesReRegistered() {
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        when(storage.listAcrossTenants(SandboxArtifacts.KEY_PREFIX))
                .thenReturn(
                        List.of(
                                new StoredObject(
                                        "t1",
                                        SandboxArtifacts.dataKey(old, "png"),
                                        now.get() - 120_000),
                                new StoredObject(
                                        "t1", SandboxArtifacts.metaKey(old), now.get() - 120_000),
                                new StoredObject(
                                        "t2",
                                        SandboxArtifacts.dataKey(fresh, "xlsx"),
                                        now.get() - 10_000),
                                new StoredObject(
                                        "t2", SandboxArtifacts.metaKey(fresh), now.get() - 10_000),
                                new StoredObject("t2", "sandbox-artifacts/not-a-uuid.png", 0)));

        int deleted = retention.recoverOnStartup();

        assertEquals(1, deleted);
        verify(storage).delete(SandboxArtifacts.dataKey(old, "png"));
        verify(storage).delete(SandboxArtifacts.metaKey(old));
        verify(storage, never()).delete(SandboxArtifacts.dataKey(fresh, "xlsx"));
        assertEquals(1, retention.pendingCount());

        // the re-registered one now expires through the normal sweep
        now.addAndGet(60_000);
        retention.sweep();
        verify(storage).delete(SandboxArtifacts.dataKey(fresh, "xlsx"));
        assertEquals(0, retention.pendingCount());
    }

    @Test
    void anOrphanedDataFileWithoutItsSidecarIsStillExpired() {
        UUID id = UUID.randomUUID();
        when(storage.listAcrossTenants(SandboxArtifacts.KEY_PREFIX))
                .thenReturn(
                        List.of(new StoredObject(null, SandboxArtifacts.dataKey(id, "csv"), 0)));
        assertEquals(1, retention.recoverOnStartup());
        verify(storage).delete(SandboxArtifacts.dataKey(id, "csv"));
    }

    @Test
    void aListingFailureIsSwallowedAndDisabledSweepDoesNothing() {
        when(storage.listAcrossTenants(SandboxArtifacts.KEY_PREFIX))
                .thenThrow(new RuntimeException("s3 down"));
        assertEquals(0, retention.recoverOnStartup());

        SandboxArtifactRetention off =
                new SandboxArtifactRetention(storage, 60_000, now::get, false);
        assertEquals(0, off.recoverOnStartup());
        verify(storage, org.mockito.Mockito.times(1))
                .listAcrossTenants(SandboxArtifacts.KEY_PREFIX);
    }
}
