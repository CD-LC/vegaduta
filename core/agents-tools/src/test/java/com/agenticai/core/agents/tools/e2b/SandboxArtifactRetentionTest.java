package com.agenticai.core.agents.tools.e2b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.storage.FileStorageService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SandboxArtifactRetentionTest {

    private final FileStorageService storage = mock(FileStorageService.class);
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final SandboxArtifactRetention retention =
            new SandboxArtifactRetention(storage, 60_000, now::get);

    @Test
    void sweepDeletesOnlyArtifactsOlderThanTheTtl() {
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        retention.track("t1", old, "png");
        now.addAndGet(50_000);
        retention.track("t1", fresh, "xlsx");
        now.addAndGet(20_000); // old is 70s, fresh is 20s

        retention.sweep();

        verify(storage).delete(SandboxArtifacts.dataKey(old, "png"));
        verify(storage).delete(SandboxArtifacts.metaKey(old));
        verify(storage, never()).delete(SandboxArtifacts.dataKey(fresh, "xlsx"));
        assertEquals(1, retention.pendingCount());
    }

    @Test
    void aFailingDeleteDoesNotStopTheSweep() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        retention.track(null, a, "csv");
        retention.track(null, b, "py");
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(storage)
                .delete(SandboxArtifacts.dataKey(a, "csv"));
        now.addAndGet(120_000);

        retention.sweep();

        verify(storage).delete(SandboxArtifacts.dataKey(b, "py"));
        assertEquals(0, retention.pendingCount());
    }
}
