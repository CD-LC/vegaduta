package com.agenticai.core.knowledge;

import com.agenticai.core.knowledge.dto.KnowledgeCollectionCreateRequest;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionResponse;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionUpdateRequest;
import com.agenticai.core.knowledge.dto.KnowledgeDocumentResponse;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Knowledge Stage F1 - CRUD for the collections {@code KnowledgeController.upload} files into. */
@RestController
@RequestMapping("/api/knowledge/collections")
@Profile("knowledge")
public class KnowledgeCollectionController {

    private final KnowledgeCollectionService collectionService;
    private final KnowledgeDocumentService documentService;

    public KnowledgeCollectionController(
            KnowledgeCollectionService collectionService,
            KnowledgeDocumentService documentService) {
        this.collectionService = collectionService;
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<KnowledgeCollectionResponse> create(
            @Valid @RequestBody KnowledgeCollectionCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(collectionService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<KnowledgeCollectionResponse>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            collectionService.listAll(),
            limit, offset, 50, 500);
    }

    // Multi-model ingestion - the fixed catalog KnowledgeCollectionsPanel's
    // "Embedding model" dropdown renders. A literal path segment, not
    // ambiguous with "/{collectionId}" below - Spring's path matcher always
    // prefers the more specific literal match (see EmbeddingModelCatalog's
    // javadoc for the model list itself).
    @GetMapping("/embedding-models")
    public ResponseEntity<List<EmbeddingModelCatalog.ModelOption>> embeddingModels(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(EmbeddingModelCatalog.all(), limit, offset, 100, 500);
    }

    // RAG audit fix (2026-07-29) - the fixed catalog of Postgres text-search
    // configurations a collection's keyword-search side can be created with
    // (per-collection FTS language, migration V154). Same literal-path-
    // segment reasoning as /embedding-models above.
    @GetMapping("/text-search-configs")
    public ResponseEntity<List<TextSearchConfigCatalog.ConfigOption>> textSearchConfigs(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(TextSearchConfigCatalog.all(), limit, offset, 100, 500);
    }

    @GetMapping("/{collectionId}")
    public KnowledgeCollectionResponse get(@PathVariable UUID collectionId) {
        return collectionService.get(collectionId);
    }

    @PutMapping("/{collectionId}")
    public KnowledgeCollectionResponse update(
            @PathVariable UUID collectionId,
            @Valid @RequestBody KnowledgeCollectionUpdateRequest request) {
        return collectionService.update(collectionId, request);
    }

    @DeleteMapping("/{collectionId}")
    public ResponseEntity<Void> delete(@PathVariable UUID collectionId) {
        collectionService.delete(collectionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * "Build the backend to make it real" - the real, persisted file table for this collection
     * (every upload through both {@code KnowledgeController.upload} and {@code uploadAudio}, newest
     * first), replacing the frontend's earlier session-only "recent uploads" list.
     */
    @GetMapping("/{collectionId}/documents")
    public ResponseEntity<List<KnowledgeDocumentResponse>> documents(
            @PathVariable UUID collectionId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        collectionService.requireReadable(collectionId);
        List<KnowledgeDocumentResponse> rows =
                documentService.listByCollection(collectionId, limit, offset);
        // Truncation must be visible: X-Total-Count is the real count (queried only
        // for a full or paged-in page), X-Has-More flags older rows - reach them with ?offset=.
        return ListPaging.respond(
                rows,
                ListPaging.limit(
                        limit,
                        KnowledgeDocumentService.LIST_DEFAULT_LIMIT,
                        KnowledgeDocumentService.LIST_MAX_LIMIT),
                ListPaging.offset(offset),
                () -> documentService.countByCollection(collectionId));
    }
}
