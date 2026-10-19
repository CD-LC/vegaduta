package com.agenticai.core.knowledge.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.KnowledgeCollection;
import com.agenticai.core.knowledge.KnowledgeCollectionNotFoundException;
import com.agenticai.core.knowledge.KnowledgeCollectionRepository;
import com.agenticai.core.knowledge.rag.RagDtos.RagSettingsResponse;
import com.agenticai.core.knowledge.rag.RagDtos.RagSettingsUpdateRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

/** The collection's parent-child opt-in: deployment gate, collection flag, and a schema that has not run V432. */
class KnowledgeRagSettingsServiceTest {

    private JdbcTemplate jdbc;
    private KnowledgeCollectionRepository repository;
    private UUID collectionId;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = mock(KnowledgeCollectionRepository.class);
        collectionId = UUID.randomUUID();
        when(repository.findById(collectionId)).thenReturn(Optional.of(mock(KnowledgeCollection.class)));
    }

    private KnowledgeRagSettingsService service(boolean parentChildEnabled) {
        return new KnowledgeRagSettingsService(
            jdbc, repository, new RagRetrievalConfig(parentChildEnabled, 150, 900, false, 60, 1.0, 1.0, "", false, "multi", 3));
    }

    @SuppressWarnings("unchecked")
    private void stubFlag(boolean value) {
        when(jdbc.query(contains("parent_child_enabled"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(value));
    }

    @Test
    void ingestionUsesParentChildOnlyWhenTheDeploymentAndTheCollectionBothOptIn() {
        stubFlag(true);
        var settings = service(true).parentChildIngestSettings(collectionId);
        assertTrue(settings.isPresent());
        assertEquals(150, settings.get().childWords());
        assertEquals(900, settings.get().parentWords());

        stubFlag(false);
        assertTrue(service(true).parentChildIngestSettings(collectionId).isEmpty(), "collection did not opt in");
    }

    @Test
    void whenTheDeploymentSwitchIsOffTheDatabaseIsNeverAsked() {
        assertTrue(service(false).parentChildIngestSettings(collectionId).isEmpty());
        assertTrue(service(true).parentChildIngestSettings(null).isEmpty());
        verifyNoInteractions(jdbc);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aSchemaWithoutTheColumnIngestsFlatInsteadOfFailing() {
        when(jdbc.query(contains("parent_child_enabled"), any(RowMapper.class), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("column parent_child_enabled does not exist"));

        assertTrue(service(true).parentChildIngestSettings(collectionId).isEmpty());
    }

    @Test
    void getReportsTheCollectionFlagTheSizesAndTheDeploymentState() {
        stubFlag(true);
        when(jdbc.queryForObject(contains("knowledge_parent_chunk"), eq(Long.class), any(Object[].class))).thenReturn(7L);

        RagSettingsResponse r = service(true).get(collectionId);

        assertTrue(r.parentChildAvailable());
        assertTrue(r.parentChildEnabled());
        assertEquals(150, r.childTokens());
        assertEquals(900, r.parentTokens());
        assertEquals(7L, r.parentChunkCount());
        assertFalse(r.fusionEnabled());
        assertEquals("off", r.queryRewriteMode());
        assertTrue(r.schemaReady());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getOnASchemaPendingTheMigrationSaysSoInsteadOfFailing() {
        when(jdbc.query(contains("parent_child_enabled"), any(RowMapper.class), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("no such column"));

        RagSettingsResponse r = service(true).get(collectionId);

        assertFalse(r.schemaReady());
        assertFalse(r.parentChildEnabled());
        assertEquals(0L, r.parentChunkCount());
    }

    @Test
    void enablingStoresTheFlagAndReturnsTheFreshState() {
        when(jdbc.update(contains("SET parent_child_enabled"), any(Object[].class))).thenReturn(1);
        stubFlag(true);

        RagSettingsResponse r = service(true).update(collectionId, new RagSettingsUpdateRequest(true));

        verify(jdbc).update(contains("SET parent_child_enabled"), eq(true), eq(collectionId));
        assertTrue(r.parentChildEnabled());
    }

    @Test
    void enablingIsRefusedWhenTheDeploymentHasParentChildOffButDisablingIsAllowed() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> service(false).update(collectionId, new RagSettingsUpdateRequest(true)));
        assertEquals(409, e.getStatusCode().value());
        verify(jdbc, never()).update(contains("SET parent_child_enabled"), any(Object[].class));

        when(jdbc.update(contains("SET parent_child_enabled"), any(Object[].class))).thenReturn(1);
        stubFlag(false);
        assertFalse(service(false).update(collectionId, new RagSettingsUpdateRequest(false)).parentChildEnabled());
    }

    @Test
    void aMissingColumnOnUpdateIsAServiceUnavailableNotAServerError() {
        when(jdbc.update(contains("SET parent_child_enabled"), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("no such column"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> service(true).update(collectionId, new RagSettingsUpdateRequest(true)));
        assertEquals(503, e.getStatusCode().value());
    }

    @Test
    void anUnknownCollectionIsNotFoundOnBothEndpoints() {
        UUID other = UUID.randomUUID();
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> service(true).get(other));
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> service(true).update(other, new RagSettingsUpdateRequest(true)));
    }

    @Test
    void aNullPayloadChangesNothing() {
        stubFlag(false);

        service(true).update(collectionId, new RagSettingsUpdateRequest(null));
        service(true).update(collectionId, null);

        verify(jdbc, never()).update(contains("SET parent_child_enabled"), any(Object[].class));
    }
}
