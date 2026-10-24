package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.tenancy.AgeSessionSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The workspace's and the agent's {@code memory.graph.*} settings reach the real recall: the number of paths the
 * query reads and the number of facts it returns follow the override, and with none stored they are exactly the
 * values {@link GraphMemoryProperties} gives.
 */
class GraphMemorySettingsTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final GraphMemoryService service = new GraphMemoryService(jdbcTemplate);
    private final InMemorySettingsStore store = new InMemorySettingsStore();
    private final GraphMemoryProperties properties = new GraphMemoryProperties();
    private final DefaultSettingsResolver resolver = new DefaultSettingsResolver(
            new SettingsRegistry(List.of(new GraphMemorySettings(properties))), store, List.of(), new SettingsProperties(), null);
    private final UUID agent = UUID.randomUUID();
    private final UUID otherAgent = UUID.randomUUID();

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
        service.setProperties(properties);
        service.setSettings(resolver);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void tenantOverride(String key, long value) {
        store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", key, value, 1, "alice", Instant.now()));
    }

    private void agentOverride(UUID agentId, String key, long value) {
        store.saveCurrent(new SettingOverride(SettingScope.AGENT, agentId.toString(), key, value, 1, "alice", Instant.now()));
    }

    private List<String> executedQueries() throws SQLException {
        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).executeQuery(queries.capture());
        return new ArrayList<>(queries.getAllValues());
    }

    private void emptyGraph() throws SQLException {
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        when(statement.executeQuery(anyString())).thenReturn(empty);
    }

    private static String path(int n) {
        return "[{\"id\": " + (n * 10) + ", \"properties\": {\"name\": \"Priya\"}}::vertex, "
                + "{\"id\": " + (n * 10 + 1) + ", \"end_id\": " + (n * 10 + 2) + ", \"start_id\": " + (n * 10)
                + ", \"properties\": {\"type\": \"LIKES\", \"valid_from\": 1}}::edge, "
                + "{\"id\": " + (n * 10 + 2) + ", \"properties\": {\"name\": \"thing" + n + "\"}}::vertex]::path";
    }

    private void graphWithPaths(int count) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        int[] next = {0};
        when(rs.next()).thenAnswer(inv -> next[0] < count);
        when(rs.getString("p")).thenAnswer(inv -> path(next[0]++));
        when(statement.executeQuery(anyString())).thenReturn(rs);
    }

    @Test
    void theCatalogDefaultsAreTheValuesThePropertiesCarry() {
        assertThat(resolver.getInt(GraphMemorySettings.RECALL_MAX_FACTS, agent)).isEqualTo(GraphMemoryProperties.DEFAULT_MAX_RECALL_FACTS);
        assertThat(resolver.getInt(GraphMemorySettings.RECALL_MAX_PATHS, agent)).isEqualTo(GraphMemoryProperties.DEFAULT_MAX_RECALL_PATHS);
    }

    @Test
    void withNothingOverriddenTheRecallReadsTwoHundredPathsAsBefore() throws SQLException {
        emptyGraph();
        service.recallRelated(agent, "s", "a", 1);
        assertThat(executedQueries()).anyMatch(q -> q.contains("RETURN p LIMIT 200"));
    }

    @Test
    void aWorkspaceOverrideChangesHowManyPathsAreRead() throws SQLException {
        emptyGraph();
        tenantOverride(GraphMemorySettings.RECALL_MAX_PATHS, 50);
        service.recallRelated(agent, "s", "a", 1);
        assertThat(executedQueries()).anyMatch(q -> q.contains("RETURN p LIMIT 50"));
    }

    @Test
    void anAgentOverrideBeatsTheWorkspaceOneAndOnlyAppliesToThatAgent() throws SQLException {
        emptyGraph();
        tenantOverride(GraphMemorySettings.RECALL_MAX_PATHS, 50);
        agentOverride(agent, GraphMemorySettings.RECALL_MAX_PATHS, 75);

        service.recallRelated(agent, "s", "a", 1);
        service.recallRelated(otherAgent, "s", "a", 1);

        List<String> queries = executedQueries();
        assertThat(queries).anyMatch(q -> q.contains("RETURN p LIMIT 75"));
        assertThat(queries).anyMatch(q -> q.contains("RETURN p LIMIT 50"));
    }

    @Test
    void anAgentCanBeGivenFewerFactsThanTheGraphHolds() throws SQLException {
        graphWithPaths(6);
        agentOverride(agent, GraphMemorySettings.RECALL_MAX_FACTS, 2);

        assertThat(service.recallRelated(agent, "s", "a", 1)).hasSize(2);
    }

    @Test
    void anotherAgentStillGetsTheDefaultNumberOfFacts() throws SQLException {
        graphWithPaths(6);
        agentOverride(agent, GraphMemorySettings.RECALL_MAX_FACTS, 2);

        assertThat(service.recallRelated(otherAgent, "s", "a", 1)).hasSize(6);
    }

    @Test
    void theSharedTierRecallFollowsTheWorkspaceOverride() throws SQLException {
        graphWithPaths(6);
        tenantOverride(GraphMemorySettings.RECALL_MAX_FACTS, 3);

        assertThat(service.recallSharedRelated("s", "a", 1)).hasSize(3);
    }

    @Test
    void anotherWorkspacesOverrideNeverReachesThisOne() throws SQLException {
        graphWithPaths(6);
        tenantOverride(GraphMemorySettings.RECALL_MAX_FACTS, 1);
        TenantContext.set(TenantContext.of("globex", "sub", Set.of()));

        assertThat(service.recallRelated(agent, "s", "a", 1)).hasSize(6);
    }

    @Test
    void withoutAResolverTheRecallKeepsUsingTheProperties() throws SQLException {
        graphWithPaths(6);
        tenantOverride(GraphMemorySettings.RECALL_MAX_FACTS, 1);
        GraphMemoryService bare = new GraphMemoryService(jdbcTemplate);
        bare.setProperties(properties);

        assertThat(bare.recallRelated(agent, "s", "a", 1)).hasSize(6);
    }
}
