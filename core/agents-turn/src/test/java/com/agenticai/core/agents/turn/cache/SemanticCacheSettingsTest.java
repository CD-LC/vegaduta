package com.agenticai.core.agents.turn.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.settings.ProductControlsFixture;
import com.agenticai.core.agents.settings.SettingsRig;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.security.TenantContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@code chat.semanticCache.similarityThreshold}, {@code .ttl}, {@code .maxEntriesPerAgent}: the cache of an agent follows
 * the agent's own or its tenant's setting, {@code app.chat.semantic-cache.*} when there is none. JdbcTemplate is mocked, as
 * in {@link SemanticResponseCacheServiceTest}; the tenant of each call is the thread's.
 */
class SemanticCacheSettingsTest {

    private static final String TENANT = "tenant-a";
    private static final String USER = "kc-user";
    private final ProductControlsFixture f = new ProductControlsFixture();
    private final UUID agent = UUID.randomUUID();
    private final UUID sibling = UUID.randomUUID();
    private SidecarClient sidecar;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        sidecar = mock(SidecarClient.class);
        jdbc = mock(JdbcTemplate.class);
        when(sidecar.embedQuery(anyString())).thenReturn(new float[] {0.1f, 0.2f});
    }

    @AfterEach
    void clear() {
        SettingsRig.clear();
    }

    private SemanticResponseCacheService service() {
        SemanticResponseCacheService s = new SemanticResponseCacheService(sidecar, jdbc, f.semanticCache);
        s.setSettingsResolver(f.rig().resolver());
        return s;
    }

    @SuppressWarnings("unchecked")
    private void nearestIsAt(double distance) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of(new SemanticResponseCacheService.CacheHit(distance, "cached")));
    }

    private Optional<String> lookup(SemanticResponseCacheService s, UUID agentId) {
        TenantContext.set(TenantContext.of(TENANT, "u", Set.of()));
        try {
            return s.get(agentId, USER, "what are your hours?");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aTenantThatLowersTheThresholdAcceptsALooserMatchAndAnotherTenantStillDoesNot() {
        nearestIsAt(0.08); // similarity 0.92: below the platform's 0.95
        f.rig().tenant(TENANT, "chat.semanticCache.similarityThreshold", 0.9);
        SemanticResponseCacheService s = service();

        assertThat(lookup(s, agent)).contains("cached");
        TenantContext.set(TenantContext.of("tenant-b", "u", Set.of()));
        assertThat(s.get(agent, USER, "what are your hours?")).isEmpty();
    }

    @Test
    void anAgentsOwnStricterThresholdBeatsItsTenantsAndSiblingsKeepTheTenants() {
        nearestIsAt(0.03); // similarity 0.97
        f.rig().tenant(TENANT, "chat.semanticCache.similarityThreshold", 0.9)
                .agent(TENANT, agent, "chat.semanticCache.similarityThreshold", 0.99);
        SemanticResponseCacheService s = service();

        assertThat(lookup(s, agent)).as("0.97 does not clear this agent's 0.99").isEmpty();
        assertThat(lookup(s, sibling)).as("0.97 clears the tenant's 0.90").contains("cached");
    }

    @Test
    void withNoOverrideTheThresholdIsThePropertys() {
        f.semanticCache.setSimilarityThreshold(0.99);
        nearestIsAt(0.02);
        assertThat(lookup(service(), agent)).as("0.98 is under 0.99").isEmpty();
        nearestIsAt(0.005);
        assertThat(lookup(service(), agent)).contains("cached");
    }

    @Test
    void aStoredAnswerExpiresAfterTheTtlOfTheAgentsTenantOrTheAgent() {
        f.rig().tenant(TENANT, "chat.semanticCache.ttl", 7200).agent(TENANT, agent, "chat.semanticCache.ttl", 600);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
        SemanticResponseCacheService s = service();
        TenantContext.set(TenantContext.of(TENANT, "u", Set.of()));

        s.put(agent, USER, "q1", "a1");
        s.put(sibling, USER, "q2", "a2");
        TenantContext.set(TenantContext.of("tenant-b", "u", Set.of()));
        s.put(sibling, USER, "q3", "a3");

        ArgumentCaptor<Timestamp> created = ArgumentCaptor.forClass(Timestamp.class);
        ArgumentCaptor<Timestamp> expires = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbc, times(3)).update(contains("INSERT INTO"), any(), any(), any(), any(), any(), anyString(), created.capture(), expires.capture());
        List<Long> seconds = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            seconds.add(Duration.between(created.getAllValues().get(i).toInstant(), expires.getAllValues().get(i).toInstant()).toSeconds());
        }
        assertThat(seconds).containsExactly(600L, 7200L, 6L * 3600);
    }

    @Test
    void theCapOnStoredAnswersIsTheAgentsThenTheTenantsThenThePlatforms() {
        f.semanticCache.setMaxEntriesPerAgent(200);
        f.rig().tenant(TENANT, "chat.semanticCache.maxEntriesPerAgent", 50).agent(TENANT, agent, "chat.semanticCache.maxEntriesPerAgent", 20);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(60);
        SemanticResponseCacheService s = service();
        TenantContext.set(TenantContext.of(TENANT, "u", Set.of()));

        s.put(agent, USER, "q1", "a1");
        s.put(sibling, USER, "q2", "a2");

        // 60 stored: 40 over the agent's cap of 20, 10 over the tenant's 50
        verify(jdbc).update(contains("DELETE FROM"), eq(agent), eq(40));
        verify(jdbc).update(contains("DELETE FROM"), eq(sibling), eq(10));
    }

    @Test
    void aSettingsOutageLeavesThePlatformValuesAndTheCacheStillWorks() {
        nearestIsAt(0.02);
        SemanticResponseCacheService s = new SemanticResponseCacheService(sidecar, jdbc, f.semanticCache);
        s.setSettingsResolver(new com.agenticai.core.settings.SettingsResolver() {
            @Override
            public com.agenticai.core.settings.SettingValue get(String key, com.agenticai.core.settings.SettingContext ctx) {
                throw new IllegalStateException("store down");
            }

            @Override
            public java.util.Map<String, com.agenticai.core.settings.SettingValue> resolveAll(com.agenticai.core.settings.SettingContext ctx) {
                throw new IllegalStateException("store down");
            }

            @Override
            public com.agenticai.core.settings.SettingsRegistry registry() {
                throw new IllegalStateException("store down");
            }
        });
        assertThat(lookup(s, agent)).contains("cached");
    }
}
