package com.agenticai.core.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmUsageRollupService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import org.junit.jupiter.api.Test;

class LlmUsageRollupSchedulerTest {

    @Test
    void theNightlyRollupFansOutOverRegisteredTenantsOnlyNeverDemoSandboxes() {
        TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
        PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
        when(lock.tryRunExclusive(anyString(), any()))
                .thenAnswer(
                        inv -> {
                            ((Runnable) inv.getArgument(1)).run();
                            return true;
                        });
        LlmUsageRollupScheduler scheduler =
                new LlmUsageRollupScheduler(iterator, mock(LlmUsageRollupService.class), lock);

        scheduler.rollupYesterday();

        verify(iterator).forEachActiveNonDemoTenantSchema(any());
        verify(iterator, never()).forEachActiveTenantSchema(any());
    }
}
