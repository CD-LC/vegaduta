package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The recall query must name pgvector's cosine operator schema-qualified, like the legacy fact
 * store does: on a tenant connection the search_path is the tenant schema only (for example inside
 * any transaction), where an unqualified {@code <=>} is "operator does not exist" and the recall
 * silently degrades to nothing. Found by the real-platform memory eval, which ran the recall on
 * such a connection.
 */
class JdbcMemoryItemRecallSqlTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    @Test
    void recallUsesTheSchemaQualifiedCosineOperator() {
        TenantContext.set(TenantContext.of("tenant_sql_test", "u", Set.of()));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        new JdbcMemoryItemStorage(provider)
                .recall(
                        UUID.randomUUID(),
                        MemoryKind.EPISODIC,
                        "alice",
                        new float[384],
                        5,
                        Instant.now());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("OPERATOR(public.<=>)").doesNotContain(" <=> ");
        assertThat(sql.getValue().split("OPERATOR\\(public\\.<=>\\)", -1)).hasSize(3);
    }
}
