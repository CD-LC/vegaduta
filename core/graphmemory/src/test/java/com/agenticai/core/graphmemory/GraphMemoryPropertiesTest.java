package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.AgeSessionSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** {@code app.graph-memory.*} limits, through the real recall, forget-scan and arbitration code. */
class GraphMemoryPropertiesTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final GraphMemoryService service = new GraphMemoryService(jdbcTemplate);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws SQLException {
        AgeSessionSupport.resetCachedPreloadStateForTests();
        TenantContext.clear();
        TenantContext.set(TenantContext.of("acme", "sub", Set.of()));
        when(connection.createStatement()).thenReturn(statement);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(inv -> {
            ConnectionCallback<Object> callback = inv.getArgument(0);
            return callback.doInConnection(connection);
        });
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private List<String> executedQueries() throws SQLException {
        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).executeQuery(queries.capture());
        return new ArrayList<>(queries.getAllValues());
    }

    @Test
    void theDefaultsAreTheValuesTheCodeAlwaysUsed() {
        GraphMemoryProperties p = new GraphMemoryProperties();
        assertThat(p.getRecall().getMaxFacts()).isEqualTo(20);
        assertThat(p.getRecall().getMaxPaths()).isEqualTo(200);
        assertThat(p.getArbitration().getMaxContextFacts()).isEqualTo(30);
        assertThat(p.getForget().getScanPage()).isEqualTo(500);
        assertThat(p.getForget().getScanMaxPages()).isEqualTo(20);
    }

    @Test
    void theRecallReadsAsManyPathsAsConfigured() throws SQLException {
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        when(statement.executeQuery(anyString())).thenReturn(empty);
        GraphMemoryProperties properties = new GraphMemoryProperties();
        properties.getRecall().setMaxPaths(7);
        service.setProperties(properties);

        service.recallRelated(UUID.randomUUID(), "s", "a", 1);

        assertThat(executedQueries()).anyMatch(q -> q.contains("RETURN p LIMIT 7"));
    }

    @Test
    void theRecallDefaultStillReadsTwoHundredPaths() throws SQLException {
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        when(statement.executeQuery(anyString())).thenReturn(empty);

        service.recallRelated(UUID.randomUUID(), "s", "a", 1);

        assertThat(executedQueries()).anyMatch(q -> q.contains("RETURN p LIMIT 200"));
    }

    @Test
    void theForgetScanPageSizeIsConfigurable() throws SQLException {
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        when(statement.executeQuery(contains("RETURN a.name"))).thenReturn(empty);
        GraphMemoryProperties properties = new GraphMemoryProperties();
        properties.getForget().setScanPage(3);
        service.setProperties(properties);

        service.forgetFactText("subject-1", UUID.randomUUID(), "Priya's manager is Raj");

        assertThat(executedQueries()).anyMatch(q -> q.contains("SKIP 0 LIMIT 3"));
    }

    @Test
    void theArbitrationShowsOnlyAsManyKnownFactsAsConfigured() {
        MemoryWriteArbitrationService arbitration = new MemoryWriteArbitrationService();
        ReflectionTestUtils.setField(arbitration, "enabled", true);
        GraphMemoryProperties properties = new GraphMemoryProperties();
        properties.getArbitration().setMaxContextFacts(1);
        arbitration.setProperties(properties);
        GraphMemoryService store = mock(GraphMemoryService.class);
        UUID agent = UUID.randomUUID();
        when(store.recallRelated(eq(agent), eq("k"), eq("Priya"), eq(1))).thenReturn(List.of(
            new GraphFact("Priya", "MANAGER_OF_BY", "Raj"), new GraphFact("Priya", "WORKS_AT", "Acme")));
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(requestSpec.user(userPrompt.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("[]");

        arbitration.arbitrateAndWrite(chatClient, store, agent, "k", List.of(new GraphTriple("Priya", "LIKES", "tea")));

        assertThat(userPrompt.getValue()).contains("Priya manager of by Raj").doesNotContain("works at");
    }
}
