package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.knowledge.dto.IngestRequest;
import com.agenticai.core.knowledge.dto.KnowledgeReembedRequest;
import com.agenticai.core.knowledge.dto.SearchRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import com.agenticai.core.storage.FileStorageService;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * REST enforcement in {@link KnowledgeController}: a member without a grant cannot search, download,
 * ingest, delete from or re-embed a restricted collection (404, nothing downstream touched), while a
 * pre-existing tenant-visible collection behaves exactly as before and grants open exactly what their
 * level says.
 */
class KnowledgeControllerAccessTest {

    private static final String OWNER = "owner-sub";
    private static final String MEMBER = "member-sub";

    private KnowledgeCollectionRepository collections;
    private KnowledgeDocumentRepository documents;
    private ResourceAccessService sharing;
    private KnowledgeIngestionService ingestion;
    private KnowledgeSearchService searchService;
    private KnowledgeCollectionService collectionService;
    private KnowledgeDocumentService documentService;
    private FileStorageService storage;
    private KnowledgeReembedService reembed;
    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        collections = mock(KnowledgeCollectionRepository.class);
        documents = mock(KnowledgeDocumentRepository.class);
        sharing = mock(ResourceAccessService.class);
        when(sharing.activeGrantLevels(eq(ResourceType.KNOWLEDGE_COLLECTION), any())).thenReturn(Map.of());
        ingestion = mock(KnowledgeIngestionService.class);
        searchService = mock(KnowledgeSearchService.class);
        collectionService = mock(KnowledgeCollectionService.class);
        documentService = mock(KnowledgeDocumentService.class);
        storage = mock(FileStorageService.class);
        reembed = mock(KnowledgeReembedService.class);
        KnowledgeAccessService access =
            new KnowledgeAccessService(collections, documents, sharing, mock(AuditLogService.class));
        controller = new KnowledgeController(
            ingestion, searchService, collectionService, mock(KnowledgeIngestionJobService.class),
            documentService, storage, reembed, mock(KnowledgeUrlIngester.class),
            mock(DoclingConversionService.class), access);
        TenantContext.set(TenantContext.of("tenant-1", MEMBER, Set.of()));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private KnowledgeCollection collection(String owner, KnowledgeVisibility visibility) {
        KnowledgeCollection c = new KnowledgeCollection("c", "d", null, "minilm-l6-v2", "simple", owner, visibility);
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private UUID documentIn(KnowledgeCollection c) {
        KnowledgeDocument doc = mock(KnowledgeDocument.class);
        when(doc.getCollectionId()).thenReturn(c.getId());
        when(doc.getObjectKey()).thenReturn("knowledge-documents/x/report.pdf");
        when(doc.getFilename()).thenReturn("report.pdf");
        UUID id = UUID.randomUUID();
        when(documents.findById(id)).thenReturn(Optional.of(doc));
        when(documentService.getEntity(id)).thenReturn(doc);
        return id;
    }

    // --- search --------------------------------------------------------------------------------

    @Test
    void searchNamingAPrivateCollectionIs404AndNeverReachesTheSearchService() {
        KnowledgeCollection secret = collection(OWNER, KnowledgeVisibility.PRIVATE);

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> controller.search(
            new SearchRequest("q", 5, Set.of(secret.getId()))));

        verifyNoInteractions(searchService);
    }

    @Test
    void searchOnAnExistingTenantVisibleCollectionStillWorksWithNoGrant() {
        KnowledgeCollection legacy = collection(null, null);
        when(searchService.search(anyString(), anyInt(), any(), eq(false), any())).thenReturn(List.of());

        controller.search(new SearchRequest("q", 5, Set.of(legacy.getId())));

        verify(searchService).search("q", 5, Set.of(legacy.getId()), false, null);
    }

    @Test
    void anUnscopedSearchIsLeftToTheSearchServiceToNarrow() {
        controller.search(new SearchRequest("q", 5, null));

        verify(searchService).search("q", 5, null, false, null);
    }

    // --- download / delete ---------------------------------------------------------------------

    @Test
    void downloadingADocumentOfAPrivateCollectionIs404AndNeverReadsStorage() {
        UUID doc = documentIn(collection(OWNER, KnowledgeVisibility.PRIVATE));

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> controller.downloadOriginalFile(doc));

        verifyNoInteractions(storage);
    }

    @Test
    void aViewerGrantAllowsDownloadButNotDelete() throws Exception {
        KnowledgeCollection team = collection(OWNER, KnowledgeVisibility.SHARED);
        UUID doc = documentIn(team);
        when(sharing.activeGrantLevels(ResourceType.KNOWLEDGE_COLLECTION, MEMBER))
            .thenReturn(Map.of(team.getId(), PermissionLevel.VIEWER));
        when(storage.get("knowledge-documents/x/report.pdf")).thenReturn(new ByteArrayInputStream(new byte[] {1, 2}));

        ResponseEntity<byte[]> response = controller.downloadOriginalFile(doc);
        assertEquals(2, response.getBody().length);

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> controller.deleteDocument(doc));
        verify(documentService, never()).delete(any());
    }

    @Test
    void anEditorGrantAllowsDeletingADocument() {
        KnowledgeCollection team = collection(OWNER, KnowledgeVisibility.SHARED);
        UUID doc = documentIn(team);
        when(sharing.activeGrantLevels(ResourceType.KNOWLEDGE_COLLECTION, MEMBER))
            .thenReturn(Map.of(team.getId(), PermissionLevel.EDITOR));

        controller.deleteDocument(doc);

        verify(documentService).delete(doc);
    }

    @Test
    void deletingADocumentFromAPrivateCollectionIs404() {
        UUID doc = documentIn(collection(OWNER, KnowledgeVisibility.PRIVATE));

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> controller.deleteDocument(doc));

        verify(documentService, never()).delete(any());
    }

    // --- ingest / reembed ----------------------------------------------------------------------

    @Test
    void ingestingIntoACollectionWithOnlyAViewerGrantIs404AndNothingIsIngested() {
        KnowledgeCollection team = collection(OWNER, KnowledgeVisibility.SHARED);
        when(sharing.activeGrantLevels(ResourceType.KNOWLEDGE_COLLECTION, MEMBER))
            .thenReturn(Map.of(team.getId(), PermissionLevel.VIEWER));

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> controller.ingest(
            new IngestRequest("src", "text", null, null, team.getId())));

        verifyNoInteractions(ingestion);
    }

    @Test
    void ingestingIntoTheDefaultTenantVisibleCollectionStillWorks() {
        KnowledgeCollection legacyDefault = collection(null, null);
        when(collectionService.getDefaultCollectionId()).thenReturn(legacyDefault.getId());
        when(collectionService.resolveChunking(any(), any(), any())).thenReturn(new Chunker.Settings(150, 30));

        controller.ingest(new IngestRequest("src", "text", null, null, null));

        verify(ingestion).ingest(eq("src"), eq("text"), anyInt(), anyInt(), eq(legacyDefault.getId()));
    }

    @Test
    void reembedNeedsWriteAccess() {
        KnowledgeCollection secret = collection(OWNER, KnowledgeVisibility.PRIVATE);

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> controller.reembed(
            secret.getId(), new KnowledgeReembedRequest("mpnet-base-v2")));

        verifyNoInteractions(reembed);
    }
}
