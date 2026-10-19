package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.storage.FileStorageService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

/** Optional limit on the collection file table: default cap, explicit limit, hard max. */
class KnowledgeDocumentServiceListPagingTest {

    @org.junit.jupiter.api.BeforeEach
    void defaultCapOn() {
        System.setProperty("agenticai.list.default-cap", "true");
    }

    @org.junit.jupiter.api.AfterEach
    void defaultCapOff() {
        System.clearProperty("agenticai.list.default-cap");
    }

    private KnowledgeDocumentRepository repo;
    private KnowledgeDocumentService service;
    private final UUID collectionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(KnowledgeDocumentRepository.class);
        when(repo.findByCollectionIdOrderByCreatedAtDescIdDesc(
                        any(UUID.class), any(Pageable.class)))
                .thenReturn(List.of());
        service =
                new KnowledgeDocumentService(
                        repo,
                        mock(JdbcTemplate.class),
                        mock(KnowledgeIngestionJobRepository.class),
                        mock(FileStorageService.class));
    }

    private int pageSizeFor(Integer limit) {
        service.listByCollection(collectionId, limit);
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(repo, atLeastOnce())
                .findByCollectionIdOrderByCreatedAtDescIdDesc(any(UUID.class), c.capture());
        assertEquals(0, c.getValue().getOffset());
        return c.getValue().getPageSize();
    }

    @Test
    void defaultsToFiveHundredAndLegacyOverloadStaysUnbounded() {
        assertEquals(500, pageSizeFor(null));
        service.listByCollection(collectionId);
        // internal callers (DemoStudioDocumentService.deleteAllFor) must see every row
        verify(repo).findByCollectionIdOrderByCreatedAtDesc(collectionId);
        verify(repo, org.mockito.Mockito.times(1))
                .findByCollectionIdOrderByCreatedAtDescIdDesc(any(UUID.class), any(Pageable.class));
    }

    @Test
    void explicitLimitHonouredAndClamped() {
        assertEquals(40, pageSizeFor(40));
        assertEquals(2000, pageSizeFor(99_999));
        assertEquals(1, pageSizeFor(-1));
    }
}
