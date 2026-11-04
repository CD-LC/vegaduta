package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.SidecarClient;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** The last-used stamp a recall writes comes from the memory clock: the system clock by default, a pinned one in a test. */
@SuppressWarnings("unchecked")
class MemoryClockTest {

    private static final Instant PINNED = Instant.parse("2031-03-04T05:06:07Z");

    private final SidecarClient sidecar = mock(SidecarClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UUID agentId = UUID.randomUUID();
    private final UUID factId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(sidecar.embedQuery(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(
                        List.of(
                                new AgentMemoryEntry(
                                        factId, agentId, "alice", "likes tea", 0.1, Instant.EPOCH, Instant.EPOCH, 0)));
    }

    @Test
    void aPinnedClockStampsTheAgentMemoryUsage() {
        AgentMemoryService service = new AgentMemoryService(sidecar, jdbc);
        service.useClock(Clock.fixed(PINNED, ZoneOffset.UTC));

        service.recall(agentId, "alice", "tea?", service.queryEmbedding("tea?"));

        verify(jdbc).update(contains("SET last_used_at = ?"), eq(Timestamp.from(PINNED)), eq(factId));
    }

    @Test
    void withoutAClockTheStampIsTheSystemTime() {
        AgentMemoryService service = new AgentMemoryService(sidecar, jdbc);
        Instant before = Instant.now();

        service.recall(agentId, "alice", "tea?", service.queryEmbedding("tea?"));

        ArgumentCaptor<Object> stamp = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("SET last_used_at = ?"), stamp.capture(), eq(factId));
        Instant used = ((Timestamp) stamp.getValue()).toInstant();
        assertFalse(used.isBefore(before.minusSeconds(1)));
        assertFalse(used.isAfter(Instant.now().plusSeconds(1)));
    }

    @Test
    void aNullClockFallsBackToTheSystemClock() {
        AgentMemoryService service = new AgentMemoryService(sidecar, jdbc);
        service.useClock(null);

        service.recall(agentId, "alice", "tea?", service.queryEmbedding("tea?"));

        verify(jdbc).update(contains("SET last_used_at = ?"), any(Timestamp.class), eq(factId));
    }
}
