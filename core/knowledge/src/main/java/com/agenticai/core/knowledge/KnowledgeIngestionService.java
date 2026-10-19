package com.agenticai.core.knowledge;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.knowledge.dto.IngestResponse;
import com.agenticai.core.knowledge.dto.SidecarChunk;
import com.agenticai.core.knowledge.dto.SidecarEmbedResponse;
import com.agenticai.core.knowledge.ocr.OcrProviderConfigService;
import com.agenticai.core.knowledge.rag.KnowledgeRagSettingsService;
import com.agenticai.core.knowledge.rag.ParentChildChunker;
import com.agenticai.core.knowledge.rag.ParentChildPlan;
import com.agenticai.core.knowledge.rag.ParentChunkStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("knowledge")
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private static final String MARKDOWN_EXTENSION = ".md";

    // Grounding-fidelity fix (2026-08-07, migration V190) - the only OCR path
    // this module has (sidecarClient.extractText -> Tesseract for these
    // extensions, see extraction.py's _IMAGE_EXTENSIONS); pdf/docx/html go
    // through structured text extraction, not OCR, so they're tagged "text"
    // like markdown. Mirrors KnowledgeController.SUPPORTED_EXTENSIONS'
    // image subset rather than importing it, since that list is a private
    // HTTP-layer constant.
    private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".webp");
    private static final String SOURCE_TYPE_IMAGE = "image";
    private static final String SOURCE_TYPE_TEXT = "text";

    private final SidecarClient sidecarClient;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeCollectionRepository collectionRepository;
    private final KnowledgeDocumentService documentService;
    private final OcrProviderConfigService ocrProviderConfigService;

    public KnowledgeIngestionService(
        SidecarClient sidecarClient,
        JdbcTemplate jdbcTemplate,
        KnowledgeCollectionRepository collectionRepository,
        KnowledgeDocumentService documentService,
        OcrProviderConfigService ocrProviderConfigService
    ) {
        this.sidecarClient = sidecarClient;
        this.jdbcTemplate = jdbcTemplate;
        this.collectionRepository = collectionRepository;
        this.documentService = documentService;
        this.ocrProviderConfigService = ocrProviderConfigService;
    }

    /**
     * Parent-child chunking (RAG-quality lane, migration V432). Setter-injected so the constructor and
     * every test that builds this service are unchanged; unset, or the deployment/collection switch off,
     * means the flat {@link Chunker} path below runs exactly as before.
     */
    private KnowledgeRagSettingsService ragSettings;
    private ParentChunkStore parentChunkStore;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setParentChild(KnowledgeRagSettingsService ragSettings, ParentChunkStore parentChunkStore) {
        this.ragSettings = ragSettings;
        this.parentChunkStore = parentChunkStore;
    }

    /**
     * Refuses to mix vectors from a different embedding source into a collection (V550). Setter-injected
     * so the constructor and every test that builds this service are unchanged; unset = no guard.
     */
    private KnowledgeEmbeddingBinding embeddingBinding;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEmbeddingBinding(KnowledgeEmbeddingBinding embeddingBinding) {
        this.embeddingBinding = embeddingBinding;
    }

    /**
     * Knowledge Stage F3a - the real entry point for {@code
     * KnowledgeController.upload}: decides how to turn an uploaded file's
     * raw bytes into plain text (still in-process for {@code .md}, exactly
     * as before Stage F3a; routed through the sidecar's {@code /extract}
     * for every other supported format), then hands off to {@link #ingest}
     * exactly as before - nothing downstream of "plain text in hand" knows
     * or cares which format the tenant originally uploaded.
     *
     * <p>"Build the backend to make it real" - {@code documentId} (nullable)
     * is the {@link KnowledgeDocument} row {@code KnowledgeController.upload}
     * already created for this file; when present, this method marks it
     * EXTRACTING before the real extraction work happens, mirroring the
     * genuine pipeline stage rather than a fabricated one. Null is only
     * passed by call sites that don't track a per-file document (there are
     * none left as of this stage, but the parameter stays nullable so a
     * future caller isn't forced to fabricate a document just to call this).
     */
    public IngestResponse ingestFile(String filename, byte[] content, int chunkSize, int chunkOverlap, UUID collectionId, UUID documentId) {
        if (documentId != null) {
            documentService.markExtracting(documentId);
        }
        String text = filename.toLowerCase(java.util.Locale.ROOT).endsWith(MARKDOWN_EXTENSION)
            ? decodeMarkdown(content)
            : sidecarClient.extractText(filename, content, ocrProviderConfigService.resolveActive().orElse(null));
        String sourceType = isImageFile(filename) ? SOURCE_TYPE_IMAGE : SOURCE_TYPE_TEXT;
        return ingest(filename, text, chunkSize, chunkOverlap, collectionId, documentId, sourceType);
    }

    private static boolean isImageFile(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static String decodeMarkdown(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            throw new InvalidKnowledgeUploadException("Uploaded file has no readable text content");
        }
        return text;
    }

    /**
     * Knowledge Stage F1 - every chunk this ingestion produces is tagged
     * with {@code collectionId}. Multi-model ingestion: the embedding model
     * used is whatever {@code collectionId}'s {@code embeddingModel} says
     * (chosen once, at that collection's creation - see
     * EmbeddingModelCatalog's javadoc), never a caller-supplied override, so
     * a collection can never end up with chunks from two different models.
     *
     * <p>Backward-compatible overload for callers with no per-file document
     * to track (the raw {@code POST /api/knowledge/ingest} endpoint, which
     * takes arbitrary text rather than an uploaded file) - delegates to
     * {@link #ingest(String, String, int, int, UUID, UUID)} with a null
     * {@code documentId}, so no document-status calls happen.
     */
    public IngestResponse ingest(String source, String text, int chunkSize, int chunkOverlap, UUID collectionId) {
        return ingest(source, text, chunkSize, chunkOverlap, collectionId, null, SOURCE_TYPE_TEXT);
    }

    /**
     * "Build the backend to make it real" - {@code documentId} (nullable)
     * is marked EMBEDDING right before the real sidecar call below, then
     * READY with the real, final chunk count once every chunk is actually
     * persisted - see {@link KnowledgeDocumentStatus}'s javadoc for why
     * these are genuine stage transitions timed to real work, not a
     * simulated progress animation.
     *
     * <p>Grounding-fidelity fix (2026-08-07) - backward-compatible overload
     * for the two callers (raw text ingest, audio/video transcription jobs)
     * that don't distinguish source type; both default to {@code "text"}
     * (transcripts aren't OCR-derived noise the way an image is - see
     * {@link #ingestFile} for the one path that does classify as {@code
     * "image"}).
     */
    public IngestResponse ingest(String source, String text, int chunkSize, int chunkOverlap, UUID collectionId, UUID documentId) {
        return ingest(source, text, chunkSize, chunkOverlap, collectionId, documentId, SOURCE_TYPE_TEXT);
    }

    /**
     * Grounding-fidelity fix (2026-08-07, migration V190) - found live
     * against a real tenant (ruf_duta): Tesseract-OCR'd product images
     * outranked clean text catalog docs describing the same products in
     * search_knowledge_base results, and the garbled OCR context led the
     * model to fabricate products that appeared nowhere in the retrieved
     * chunks. {@code sourceType} is persisted into each chunk's {@code
     * metadata} column so KnowledgeSearchService can filter it back out on
     * request (see its {@code excludeImageSourced} parameter) rather than
     * relying purely on embedding/keyword ranking to surface the cleaner
     * source.
     */
    private IngestResponse ingest(
        String source, String text, int chunkSize, int chunkOverlap, UUID collectionId, UUID documentId, String sourceType
    ) {
        java.util.Optional<KnowledgeCollection> collection = collectionRepository.findById(collectionId);
        String embeddingModel = collection
            .map(KnowledgeCollection::getEmbeddingModel)
            .orElse(EmbeddingModelCatalog.DEFAULT_KEY); // defensive only - callers already validate collectionId exists first
        // RAG audit fix (2026-07-29, migration V154) - the collection's own
        // full-text-search config; null (pre-V154 schema, or the defensive
        // no-collection branch above) coalesces to the language-agnostic
        // default. Validated against TextSearchConfigCatalog at collection
        // creation, so the ::regconfig cast below can never see a bad value.
        String textSearchConfig = collection
            .map(KnowledgeCollection::getTextSearchConfig)
            .filter(config -> config != null && !config.isBlank())
            .orElse(TextSearchConfigCatalog.DEFAULT_KEY);

        if (embeddingBinding != null) {
            // Before any chunking/embedding work: a collection built by another embedding source is refused (409).
            collection.ifPresent(embeddingBinding::requireWritable);
        }
        if (documentId != null) {
            documentService.markEmbedding(documentId);
        }
        // Structure-aware chunking (headings > paragraphs > sentences > word
        // window) now happens here, in Java, via the one shared Chunker -
        // the sidecar only embeds. See Chunker's javadoc.
        // Parent-child (opt-in per collection, behind app.knowledge.parent-child.enabled): the embedded
        // pieces are the small children; the larger parents are stored separately and returned at search
        // time. Everything else - flat chunking included - is the unchanged path.
        ParentChildPlan parentChildPlan = parentChildPlanFor(collectionId, text, sourceType);
        List<String> pieces = parentChildPlan != null
            ? parentChildPlan.pieces()
            : Chunker.chunk(text, new Chunker.Settings(chunkSize, chunkOverlap));
        if (pieces.isEmpty()) {
            // Same outcome the sidecar's own 400 ("text produced no chunks
            // after stripping whitespace") used to produce, raised before any
            // network call.
            throw new InvalidKnowledgeUploadException("Text produced no chunks after stripping whitespace");
        }
        SidecarEmbedResponse embedded = sidecarClient.embedPreChunked(pieces, embeddingModel);
        if (embeddingBinding != null && collection.isPresent()) {
            // First successful embed on an unbound collection records which source built its vectors.
            embeddingBinding.bindIfUnbound(collectionId);
        }

        // Schema-qualified, not a bare "knowledge_chunks" - this table is
        // never routed through Hibernate's tenant-aware connection (pgvector
        // has no Hibernate mapping, see VectorSchemaInitializer), so the
        // schema has to be resolved and named explicitly here using the
        // exact same algorithm Hibernate uses for everything else. Without
        // this, every tenant's uploads land in one shared table visible to
        // every other tenant - a real bug this fixes, not a hypothetical.
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        String metadataJson = toMetadataJson(sourceType);

        if (parentChildPlan != null) {
            parentChunkStore.insertAll(collectionId, documentId, parentChildPlan.parentRows());
        }

        List<UUID> ids = new ArrayList<>();
        for (SidecarChunk chunk : embedded.chunks()) {
            UUID id = UUID.randomUUID();
            // chunk.index() is the position in `pieces` (see SidecarClient#embedPreChunked), so it
            // addresses the matching per-child metadata of a parent-child plan.
            String chunkMetadata = parentChildPlan != null ? parentChildPlan.metadata().get(chunk.index()) : metadataJson;
            // Stage 5 (KB multi-modal storage, migration V66) - document_id
            // lets a search hit resolve back to the KnowledgeDocument that
            // owns it (and from there, the original file in object storage
            // via KnowledgeDocument.objectKey), not just the free-text
            // `source` filename column this table already had.
            jdbcTemplate.update(
                "INSERT INTO " + table + " (id, source, chunk_index, content, embedding, collection_id, embedding_model, document_id, metadata) "
                    + "VALUES (?, ?, ?, ?, ?::public.vector, ?, ?, ?, ?::jsonb)",
                id, source, chunk.index(), chunk.text(), toVectorLiteral(chunk.embedding()), collectionId, embedded.model(), documentId, chunkMetadata
            );
            ids.add(id);
        }

        // RAG audit fix (2026-07-29, migration V154) - populate content_tsv
        // with THIS collection's text-search config (V87's GENERATED ALWAYS
        // 'english' column became a plain tsvector column in V154 precisely
        // because a generated column's expression can't vary per collection).
        // A separate post-insert UPDATE rather than a to_tsvector() term in
        // the INSERT above, deliberately: on a tenant schema that hasn't
        // applied V154 yet, content_tsv is still GENERATED and any explicit
        // write to it errors - there this UPDATE fails (caught below, keyword
        // search stays on the generated column's old 'english' behavior),
        // while the INSERT and the chunks themselves remain untouched. Same
        // degrade-don't-break posture as KnowledgeSearchService#keywordSearch's
        // documented handling of pre-V87 schemas.
        if (!ids.isEmpty()) {
            String idArrayLiteral = ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
            try {
                jdbcTemplate.update(
                    "UPDATE " + table + " SET content_tsv = to_tsvector(?::regconfig, content) WHERE id = ANY(?::uuid[])",
                    textSearchConfig, idArrayLiteral
                );
            } catch (DataAccessException e) {
                log.warn(
                    "Could not set per-collection content_tsv (schema likely pending migration V154; "
                        + "keyword search falls back to the legacy generated column): {}",
                    e.getMessage()
                );
            }
        }

        if (documentId != null) {
            documentService.markReady(documentId, ids.size());
        }

        return new IngestResponse(ids.size(), embedded.dimensions(), ids, documentId);
    }

    /**
     * The parent-child storage plan for this document, or null for the ordinary flat chunking - null
     * whenever the feature is not wired, switched off (deployment or collection), or the text yields
     * no parents (the caller then falls through to its normal "no chunks" error).
     */
    private ParentChildPlan parentChildPlanFor(UUID collectionId, String text, String sourceType) {
        if (ragSettings == null || parentChunkStore == null) {
            return null;
        }
        java.util.Optional<ParentChildChunker.Settings> settings = ragSettings.parentChildIngestSettings(collectionId);
        if (settings.isEmpty()) {
            return null;
        }
        List<ParentChildChunker.Parent> parents = ParentChildChunker.chunk(text, settings.get());
        return parents.isEmpty() ? null : ParentChildPlan.build(parents, sourceType);
    }

    /** Package-visible: reused by KnowledgeSearchService for the query vector. */
    static String toVectorLiteral(List<Double> embedding) {
        return embedding.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(",", "[", "]"));
    }

    /** pgvector literal straight from a {@code float[]} (query vectors): no boxing, one pre-sized builder. */
    static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 11 + 2).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    private static String toMetadataJson(String sourceType) {
        try {
            return MAPPER.writeValueAsString(Map.of("source_type", sourceType));
        } catch (Exception e) {
            // Map.of("source_type", <constant string>) can never actually fail
            // to serialize; defensive fallback so a chunk insert is never
            // blocked by this.
            return "{}";
        }
    }
}
