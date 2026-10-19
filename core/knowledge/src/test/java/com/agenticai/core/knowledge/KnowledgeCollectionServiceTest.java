package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.dto.KnowledgeCollectionCreateRequest;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionResponse;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionUpdateRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link KnowledgeCollectionService} is the CRUD backbone of the Knowledge
 * feature's collection tree - every upload, search, and the RAG tool
 * callbacks all resolve a collection through this class first. It had zero
 * coverage despite three real invariants worth locking down: (1) a
 * collection can never become its own parent (an update() bug here would
 * corrupt the parent chain a UI recursively renders and {@link
 * KnowledgeSearchService} groups by), (2) the tenant's default collection
 * (migration V15's {@code is_default} seed row) can never be deleted since
 * every unscoped upload falls back to it, and (3) a non-default collection
 * still holding {@code knowledge_chunks} - detected via a raw JDBC {@code
 * COUNT(*)} against {@link KnowledgeSchemaSupport#qualifiedTableName()},
 * since {@code knowledge_chunks} has no Hibernate mapping - must also be
 * rejected rather than orphaning those chunks' {@code collection_id} FK.
 *
 * <p>Mocks {@link KnowledgeCollectionRepository}, {@link JdbcTemplate} (for
 * the raw {@code countChunksIn} SQL path), and {@link KnowledgeDocumentService}
 * (for the file/chunk counts folded into every {@link
 * KnowledgeCollectionResponse}) - manual {@code mock(X.class)} + constructor
 * injection in {@code setUp()}, matching this codebase's established
 * convention (no {@code @ExtendWith(MockitoExtension.class)}, no Spring
 * context).
 */
class KnowledgeCollectionServiceTest {

    private KnowledgeCollectionRepository repository;
    private JdbcTemplate jdbcTemplate;
    private KnowledgeDocumentService documentService;
    private com.agenticai.core.security.TenantLimits tenantLimits;
    private KnowledgeCollectionService service;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeCollectionRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        documentService = mock(KnowledgeDocumentService.class);
        tenantLimits = mock(com.agenticai.core.security.TenantLimits.class);
        // These unit tests run with no TenantContext, so the per-plan
        // max_kb_collections gate in create() is skipped (tenantId == null)
        // and tenantLimits is never consulted - the mock is here only to
        // satisfy the constructor.
        service = new KnowledgeCollectionService(repository, jdbcTemplate, documentService, tenantLimits);
    }

    // --- create() --------------------------------------------------------

    @Test
    void createDefaultsToTheCatalogDefaultModelWhenNoneIsRequested() {
        when(repository.save(any(KnowledgeCollection.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeCollectionResponse response = service.create(
            new KnowledgeCollectionCreateRequest("Support Docs", "FAQ content", null, null, null)
        );

        assertEquals(EmbeddingModelCatalog.DEFAULT_KEY, response.embeddingModel());
        // RAG audit fix - the language-agnostic 'simple' FTS config is the
        // default for new collections (see TextSearchConfigCatalog's javadoc).
        assertEquals(TextSearchConfigCatalog.DEFAULT_KEY, response.textSearchConfig());
        assertEquals(0L, response.fileCount());
        assertEquals(0L, response.chunkCount());
        // A brand-new collection's file/chunk counts are hardcoded to 0/0
        // without a database round trip - documentService must never be
        // consulted for a collection that cannot yet have any documents.
        verify(documentService, never()).fileCount(any());
        verify(documentService, never()).chunkCount(any());
    }

    @Test
    void createRejectsAnUnknownEmbeddingModelKey() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.create(
            new KnowledgeCollectionCreateRequest("Bad Model Collection", null, null, "text-embedding-3-small", null)
        ));

        assertTrue(ex.getMessage().contains("text-embedding-3-small"));
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsAnUnknownTextSearchConfig() {
        // The value is cast to ::regconfig in KnowledgeIngestionService's SQL,
        // so an unknown config must fail loudly at creation, not at first ingest.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.create(
            new KnowledgeCollectionCreateRequest("Bad FTS Collection", null, null, null, "klingon")
        ));

        assertTrue(ex.getMessage().contains("klingon"));
        verify(repository, never()).save(any());
    }

    @Test
    void createAcceptsAnExplicitValidTextSearchConfig() {
        when(repository.save(any(KnowledgeCollection.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeCollectionResponse response = service.create(
            new KnowledgeCollectionCreateRequest("Hindi Docs", "हिन्दी सामग्री", null, null, "hindi")
        );

        assertEquals("hindi", response.textSearchConfig());
    }

    @Test
    void createRequiresTheParentCollectionToAlreadyExistWhenOneIsSpecified() {
        UUID parentId = UUID.randomUUID();
        when(repository.existsById(parentId)).thenReturn(false);

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> service.create(
            new KnowledgeCollectionCreateRequest("Child", null, parentId, null, null)
        ));

        verify(repository, never()).save(any());
    }

    @Test
    void createSucceedsWithAnExplicitValidEmbeddingModelAndExistingParent() {
        UUID parentId = UUID.randomUUID();
        when(repository.existsById(parentId)).thenReturn(true);
        when(repository.save(any(KnowledgeCollection.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeCollectionResponse response = service.create(
            new KnowledgeCollectionCreateRequest("Legal", "Contracts", parentId, "mpnet-base-v2", null)
        );

        assertEquals("mpnet-base-v2", response.embeddingModel());
        assertEquals(parentId, response.parentCollectionId());
    }

    // --- get() / listAll(): file/chunk counts come from KnowledgeDocumentService ---

    @Test
    void getFoldsInRealFileAndChunkCountsFromKnowledgeDocumentService() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection collection = mock(KnowledgeCollection.class);
        when(collection.getId()).thenReturn(collectionId);
        when(repository.findById(collectionId)).thenReturn(Optional.of(collection));
        when(documentService.fileCount(collectionId)).thenReturn(5L);
        when(documentService.chunkCount(collectionId)).thenReturn(42L);

        KnowledgeCollectionResponse response = service.get(collectionId);

        assertEquals(5L, response.fileCount());
        assertEquals(42L, response.chunkCount());
    }

    @Test
    void getThrowsNotFoundForAnUnknownCollectionId() {
        UUID collectionId = UUID.randomUUID();
        when(repository.findById(collectionId)).thenReturn(Optional.empty());

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> service.get(collectionId));
    }

    // --- update() ----------------------------------------------------------

    @Test
    void updateRejectsACollectionBeingSetAsItsOwnParent() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection collection = mock(KnowledgeCollection.class);
        when(repository.findById(collectionId)).thenReturn(Optional.of(collection));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.update(
            collectionId, new KnowledgeCollectionUpdateRequest("Renamed", null, collectionId)
        ));

        assertTrue(ex.getMessage().toLowerCase().contains("own parent"));
        verify(collection, never()).update(any(), any(), any());
    }

    @Test
    void updateRequiresTheNewParentToExist() {
        UUID collectionId = UUID.randomUUID();
        UUID newParentId = UUID.randomUUID();
        KnowledgeCollection collection = mock(KnowledgeCollection.class);
        when(repository.findById(collectionId)).thenReturn(Optional.of(collection));
        when(repository.existsById(newParentId)).thenReturn(false);

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> service.update(
            collectionId, new KnowledgeCollectionUpdateRequest("Renamed", null, newParentId)
        ));
    }

    @Test
    void updateAppliesTheChangeAndSavesWhenTheNewParentIsValidAndDifferentFromSelf() {
        UUID collectionId = UUID.randomUUID();
        UUID newParentId = UUID.randomUUID();
        KnowledgeCollection collection = mock(KnowledgeCollection.class);
        when(collection.getId()).thenReturn(collectionId);
        when(repository.findById(collectionId)).thenReturn(Optional.of(collection));
        when(repository.existsById(newParentId)).thenReturn(true);
        when(repository.save(collection)).thenReturn(collection);

        service.update(collectionId, new KnowledgeCollectionUpdateRequest("Renamed", "New description", newParentId));

        verify(collection).update("Renamed", "New description", newParentId);
        verify(repository).save(collection);
    }

    // --- delete(): the two documented guard rails ---------------------------

    @Test
    void deleteRefusesToDeleteTheTenantsDefaultCollection() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection collection = mock(KnowledgeCollection.class);
        when(collection.isDefault()).thenReturn(true);
        when(repository.findById(collectionId)).thenReturn(Optional.of(collection));

        KnowledgeCollectionNotEmptyException ex = assertThrows(
            KnowledgeCollectionNotEmptyException.class, () -> service.delete(collectionId)
        );

        assertTrue(ex.getMessage().contains("default"));
        verify(repository, never()).delete(any());
        // The default check must short-circuit before ever touching the
        // database for a chunk count - a default collection is refused for
        // that reason alone, regardless of whether it happens to be empty.
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Long.class), any());
    }

    @Test
    void deleteRefusesANonDefaultCollectionThatStillHasChunks() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection collection = mock(KnowledgeCollection.class);
        when(collection.isDefault()).thenReturn(false);
        when(repository.findById(collectionId)).thenReturn(Optional.of(collection));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(collectionId))).thenReturn(7L);

        KnowledgeCollectionNotEmptyException ex = assertThrows(
            KnowledgeCollectionNotEmptyException.class, () -> service.delete(collectionId)
        );

        assertTrue(ex.getMessage().contains("7"));
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteSucceedsForAnEmptyNonDefaultCollection() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection collection = mock(KnowledgeCollection.class);
        when(collection.isDefault()).thenReturn(false);
        when(repository.findById(collectionId)).thenReturn(Optional.of(collection));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(collectionId))).thenReturn(0L);

        service.delete(collectionId);

        verify(repository).delete(collection);
    }

    @Test
    void deleteTreatsANullChunkCountAsZeroRatherThanThrowingANullPointerException() {
        // jdbcTemplate.queryForObject(..., Long.class, ...) can itself return
        // a null Long (COUNT(*) never actually does, but the code defends
        // against it explicitly with "count != null ? count : 0L") - this
        // locks that defensive branch in.
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection collection = mock(KnowledgeCollection.class);
        when(collection.isDefault()).thenReturn(false);
        when(repository.findById(collectionId)).thenReturn(Optional.of(collection));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(collectionId))).thenReturn(null);

        service.delete(collectionId);

        verify(repository).delete(collection);
    }

    // --- getDefaultCollectionId() --------------------------------------------

    @Test
    void getDefaultCollectionIdReturnsTheSeededDefaultsId() {
        UUID defaultId = UUID.randomUUID();
        KnowledgeCollection defaultCollection = mock(KnowledgeCollection.class);
        when(defaultCollection.getId()).thenReturn(defaultId);
        when(repository.findByIsDefaultTrue()).thenReturn(Optional.of(defaultCollection));

        assertEquals(defaultId, service.getDefaultCollectionId());
    }

    @Test
    void getDefaultCollectionIdThrowsIllegalStateWhenMigrationV15sSeedIsMissing() {
        when(repository.findByIsDefaultTrue()).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.getDefaultCollectionId());
        assertTrue(ex.getMessage().contains("V15"));
    }

    // --- count() / requireExists() -------------------------------------------

    @Test
    void countDelegatesDirectlyToTheRepository() {
        when(repository.count()).thenReturn(11L);

        assertEquals(11L, service.count());
    }

    @Test
    void requireExistsThrowsForAMissingCollectionAndReturnsSilentlyForAKnownOne() {
        UUID missingId = UUID.randomUUID();
        UUID knownId = UUID.randomUUID();
        when(repository.existsById(missingId)).thenReturn(false);
        when(repository.existsById(knownId)).thenReturn(true);

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> service.requireExists(missingId));
        service.requireExists(knownId);
    }

    // --- listAll() -----------------------------------------------------------

    @Test
    void listAllMapsEveryRepositoryRowThroughTheSameFileAndChunkCountEnrichment() {
        KnowledgeCollection first = mock(KnowledgeCollection.class);
        KnowledgeCollection second = mock(KnowledgeCollection.class);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(first.getId()).thenReturn(firstId);
        when(second.getId()).thenReturn(secondId);
        when(repository.findAll()).thenReturn(List.of(first, second));
        when(documentService.fileCount(firstId)).thenReturn(1L);
        when(documentService.fileCount(secondId)).thenReturn(2L);

        List<KnowledgeCollectionResponse> all = service.listAll();

        assertEquals(2, all.size());
        assertEquals(1L, all.get(0).fileCount());
        assertEquals(2L, all.get(1).fileCount());
    }

    // --- per-collection chunking (migration V361) ----------------------------

    @Test
    void createStoresAValidPerCollectionChunkingBudgetAndReportsIt() {
        when(repository.save(any(KnowledgeCollection.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeCollectionResponse response = service.create(
            new KnowledgeCollectionCreateRequest("Policies", null, null, null, null, 120, 20)
        );

        assertEquals(Integer.valueOf(120), response.chunkSize());
        assertEquals(Integer.valueOf(20), response.chunkOverlap());
        assertEquals(KnowledgeCollection.STATUS_READY, response.embeddingStatus());
    }

    @Test
    void createRejectsAnOverlapThatIsNotSmallerThanTheChunkSize() {
        assertThrows(IllegalArgumentException.class, () -> service.create(
            new KnowledgeCollectionCreateRequest("Bad Chunking", null, null, null, null, 40, 40)
        ));
        verify(repository, never()).save(any());
    }

    @Test
    void resolveChunkingPrefersTheRequestThenTheCollectionThenPlatformDefaults() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection configured = new KnowledgeCollection("C", null, null, EmbeddingModelCatalog.DEFAULT_KEY, null);
        configured.updateChunking(100, 10);
        when(repository.findById(collectionId)).thenReturn(java.util.Optional.of(configured));

        assertEquals(new Chunker.Settings(100, 10), service.resolveChunking(collectionId, null, null));
        assertEquals(new Chunker.Settings(60, 10), service.resolveChunking(collectionId, 60, null));

        UUID plainId = UUID.randomUUID();
        when(repository.findById(plainId)).thenReturn(java.util.Optional.of(
            new KnowledgeCollection("P", null, null, EmbeddingModelCatalog.DEFAULT_KEY, null)));
        assertEquals(Chunker.Settings.DEFAULTS, service.resolveChunking(plainId, null, null));
    }
}
