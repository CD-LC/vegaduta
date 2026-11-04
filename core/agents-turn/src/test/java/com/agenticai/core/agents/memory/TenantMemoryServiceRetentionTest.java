package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.SidecarClient;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** G9: the retention purge spares memory-block rows like the cap prune does; the prune keeps recently used rows. */
class TenantMemoryServiceRetentionTest {

    private final SidecarClient sidecar = mock(SidecarClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private TenantMemoryService service;

    @BeforeEach
    void setUp() {
        service = new TenantMemoryService(sidecar, jdbc, true, 1200, 2, 4000, true);
    }

    @Test
    void purgeCreatedBeforeSparesBlockRows() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbc.update(anyString(), any(Object.class), anyInt())).thenReturn(0);

        service.purgeCreatedBefore(Instant.parse("2026-01-01T00:00:00Z"), 100);

        verify(jdbc).update(sql.capture(), any(Object.class), eq(100));
        assertTrue(
                sql.getValue().contains("position('" + MemoryBlocks.KEY_MARKER + "' in subject_key) = 0"),
                sql.getValue());
    }

    @Test
    void capPruneEvictsLeastRecentlyUsedFirst() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(10_000_000);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        ReflectionTestUtils.invokeMethod(service, "pruneOverCap", "t");

        verify(jdbc).update(sql.capture(), any(Object.class));
        assertTrue(sql.getValue().contains("last_used_at ASC, use_count ASC, created_at ASC"), sql.getValue());
    }
}
