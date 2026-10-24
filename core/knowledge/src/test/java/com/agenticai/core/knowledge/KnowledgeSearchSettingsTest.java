package com.agenticai.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The workspace's {@code knowledge.search.*} settings reach the real search service: the candidate pool each scan
 * is asked for follows the tenant override, and with none stored it is exactly what the properties give.
 */
class KnowledgeSearchSettingsTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final SidecarClient sidecarClient = mock(SidecarClient.class);
    private final KnowledgeCollectionRepository collections = mock(KnowledgeCollectionRepository.class);
    private final KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
    private final UUID collectionId = UUID.randomUUID();
    private final InMemorySettingsStore store = new InMemorySettingsStore();
    private final KnowledgeSearchProperties properties = new KnowledgeSearchProperties();
    private final DefaultSettingsResolver resolver = new DefaultSettingsResolver(
            new SettingsRegistry(List.of(new KnowledgeSettings(properties))), store, List.of(), new SettingsProperties(), null);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private void tenant(String id) {
        TenantContext.set(TenantContext.of(id, "alice", Set.of("ROLE_tenant-admin")));
    }

    private void override(String key, long value) {
        store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", key, value, 1, "alice", Instant.now()));
    }

    @SuppressWarnings("unchecked")
    private KnowledgeSearchService service(boolean withResolver) {
        KnowledgeCollection collection = mock(KnowledgeCollection.class);
        when(collection.getId()).thenReturn(collectionId);
        when(collection.getEmbeddingModel()).thenReturn("minilm-l6-v2");
        when(collections.findAll()).thenReturn(List.of(collection));
        when(documents.findAllById(any())).thenReturn(List.of());
        when(sidecarClient.embedQuery("q", "minilm-l6-v2")).thenReturn(new float[] {0.1f});
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("embedding OPERATOR(public.<=>)")),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.<SearchResultResponse>of());
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("ts_rank_cd")),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.<SearchResultResponse>of());
        KnowledgeSearchService service = new KnowledgeSearchService(sidecarClient, jdbcTemplate, collections, documents, false);
        service.setSearchProperties(properties);
        if (withResolver) {
            service.setSettings(resolver);
        }
        return service;
    }

    @SuppressWarnings("unchecked")
    private int keywordPool(KnowledgeSearchService service, int topK) {
        service.search("q", topK);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(argThat(sql -> sql != null && sql.contains("ts_rank_cd")), any(RowMapper.class), params.capture());
        Object[] last = params.getValue();
        return (Integer) last[last.length - 1];
    }

    @Test
    void theCatalogDefaultsAreTheValuesThePropertiesCarry() {
        tenant("t1");
        assertThat(resolver.getInt(KnowledgeSettings.CANDIDATE_POOL_MULTIPLIER, com.agenticai.core.settings.SettingContext.tenant()))
                .isEqualTo(KnowledgeSearchProperties.DEFAULT_CANDIDATE_POOL_MULTIPLIER);
        assertThat(resolver.getInt(KnowledgeSettings.CANDIDATE_POOL_MAX, com.agenticai.core.settings.SettingContext.tenant()))
                .isEqualTo(KnowledgeSearchProperties.DEFAULT_CANDIDATE_POOL_MAX);
    }

    @Test
    void withNothingOverriddenThePoolIsTheOneThePropertiesGive() {
        tenant("t1");
        assertThat(keywordPool(service(true), 10)).isEqualTo(properties.candidatePoolSize(10)).isEqualTo(40);
    }

    @Test
    void aTenantMultiplierChangesHowManyCandidatesEachScanFetches() {
        tenant("t1");
        override(KnowledgeSettings.CANDIDATE_POOL_MULTIPLIER, 8);
        assertThat(keywordPool(service(true), 5)).isEqualTo(40);
    }

    @Test
    void aTenantCeilingCapsThePoolEvenWhenTheMultiplierWouldAskForMore() {
        tenant("t1");
        override(KnowledgeSettings.CANDIDATE_POOL_MAX, 25);
        assertThat(keywordPool(service(true), 20)).isEqualTo(25);
    }

    @Test
    void anotherTenantsOverrideNeverReachesThisOne() {
        tenant("t1");
        override(KnowledgeSettings.CANDIDATE_POOL_MULTIPLIER, 20);
        tenant("t2");
        assertThat(keywordPool(service(true), 5)).isEqualTo(properties.candidatePoolSize(5));
    }

    @Test
    void withoutAResolverTheServiceKeepsUsingTheProperties() {
        tenant("t1");
        override(KnowledgeSettings.CANDIDATE_POOL_MULTIPLIER, 20);
        assertThat(keywordPool(service(false), 5)).isEqualTo(properties.candidatePoolSize(5));
    }

    @Test
    void aResolverThatFailsDoesNotFailTheSearchItFallsBackToTheProperties() {
        tenant("t1");
        com.agenticai.core.settings.SettingsResolver broken = mock(
                com.agenticai.core.settings.SettingsResolver.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        doThrow(new IllegalArgumentException("unknown key")).when(broken).get(any(), any());
        KnowledgeSearchService service = service(false);
        service.setSettings(broken);
        assertThat(keywordPool(service, 5)).isEqualTo(properties.candidatePoolSize(5));
    }

    @Test
    void thePoolFormulaWithExplicitValuesKeepsTheConfiguredFloor() {
        properties.setCandidatePoolMin(30);
        assertThat(properties.candidatePoolSize(1, 2, 100)).isEqualTo(30);
        assertThat(properties.candidatePoolSize(100, 2, 100)).isEqualTo(100);
        assertThat(properties.candidatePoolSize(100, 2, 60)).isEqualTo(60);
    }
}
