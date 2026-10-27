package com.agenticai.core.knowledge;

import com.agenticai.core.knowledge.dto.IngestRequest;
import com.agenticai.core.knowledge.dto.IngestUrlRequest;
import com.agenticai.core.knowledge.dto.IngestResponse;
import com.agenticai.core.knowledge.dto.KnowledgeIngestionJobResponse;
import com.agenticai.core.knowledge.dto.KnowledgeReembedJobResponse;
import com.agenticai.core.knowledge.dto.KnowledgeReembedRequest;
import com.agenticai.core.knowledge.dto.KnowledgeStatsResponse;
import com.agenticai.core.knowledge.dto.SearchRequest;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.storage.FileStorageException;
import com.agenticai.core.storage.FileStorageService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import com.agenticai.core.security.http.AttachmentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stage 5 primitives (ingest/search over raw text) plus Stage 6's actual
 * user-facing entry point: upload() takes a real .md file, reads it as
 * plain text (no markdown-to-plaintext conversion - embedding raw markdown
 * works fine in practice and keeps this simple), and routes it through the
 * exact same ingestionService.ingest() the Stage 5 /ingest endpoint uses.
 * Retrieval for agents happens via KnowledgeSearchToolCallback (Stage 6),
 * not through this controller - this stays for direct/manual search only.
 */
@RestController
@RequestMapping("/api/knowledge")
@Profile("knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024; // 5MB - see application-knowledge.yml multipart config

    // Knowledge Stage F3a/F3b - .pdf/.docx/.html/.htm (F3a) and
    // .png/.jpg/.jpeg/.webp (F3b, OCR) added alongside the original .md.
    // Kept as a literal list here (not sourced from extraction.py's
    // SUPPORTED_EXTENSIONS) since this is the HTTP-level "is this upload
    // even worth sending to the sidecar" check - .md never goes near the
    // sidecar at all (see KnowledgeIngestionService.ingestFile). A deployment
    // with the Docling service configured widens this at request time -
    // see supportedExtensions().
    private static final List<String> SUPPORTED_EXTENSIONS =
        List.of(".md", ".pdf", ".docx", ".html", ".htm", ".png", ".jpg", ".jpeg", ".webp");

    // Knowledge Stage F3c/F3d - audio AND video formats go through
    // upload-audio (async job), never through upload() above - a separate
    // list rather than folding into SUPPORTED_EXTENSIONS since these two
    // endpoints have genuinely different response shapes (IngestResponse
    // synchronously vs KnowledgeIngestionJobResponse + 202 Accepted).
    // Video containers (.mp4/.mov/.webm) reuse the exact same transcription
    // path as audio - no separate extraction step needed, since
    // faster-whisper's PyAV-based decoding already pulls just the audio
    // stream out of any container (see sidecar/app/transcription.py's
    // module docstring for the confirmed-by-reading-the-source explanation).
    // Kept the name TRANSCRIBABLE_EXTENSIONS rather than AUDIO_EXTENSIONS
    // now that video is included.
    private static final List<String> TRANSCRIBABLE_EXTENSIONS =
        List.of(".mp3", ".wav", ".m4a", ".ogg", ".mp4", ".mov", ".webm");

    // 25MB - deliberately conservative even for video, not raised further:
    // the uploaded bytes sit in Postgres as bytea (knowledge_ingestion_job.audio_content)
    // until the job runner consumes them, and this table is explicitly not
    // meant to hold a backlog of large blobs (see migration V17's header
    // comment). This caps video uploads to short clips only - a genuine
    // scale-out video feature would need object storage instead of
    // Postgres bytea, explicitly out of scope for this stage (Build Plan
    // Section 5).
    private static final long MAX_TRANSCRIBABLE_UPLOAD_BYTES = 25L * 1024 * 1024;

    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeSearchService searchService;
    private final KnowledgeCollectionService collectionService;
    private final KnowledgeIngestionJobService jobService;
    private final KnowledgeDocumentService documentService;
    private final FileStorageService fileStorageService;
    private final KnowledgeReembedService reembedService;
    private final KnowledgeUrlIngester urlIngester;
    // Docling-first extraction (app.knowledge.docling.base-url) - its extraExtensions() widen the
    // upload allowlist while the service is configured; empty otherwise. Same "bean exists but is
    // inert when unconfigured" profile-local wiring as the rest of this module.
    private final DoclingConversionService doclingConversion;

    public KnowledgeController(
        KnowledgeIngestionService ingestionService,
        KnowledgeSearchService searchService,
        KnowledgeCollectionService collectionService,
        KnowledgeIngestionJobService jobService,
        KnowledgeDocumentService documentService,
        FileStorageService fileStorageService,
        KnowledgeReembedService reembedService,
        KnowledgeUrlIngester urlIngester,
        DoclingConversionService doclingConversion
    ) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
        this.collectionService = collectionService;
        this.jobService = jobService;
        this.documentService = documentService;
        this.fileStorageService = fileStorageService;
        this.reembedService = reembedService;
        this.urlIngester = urlIngester;
        this.doclingConversion = doclingConversion;
    }

    /** Upload allowlist: the base formats, plus Docling-only ones while that service is configured. */
    private List<String> supportedExtensions() {
        List<String> extra = doclingConversion.extraExtensionsSorted();
        if (extra.isEmpty()) {
            return SUPPORTED_EXTENSIONS;
        }
        List<String> merged = new java.util.ArrayList<>(SUPPORTED_EXTENSIONS);
        merged.addAll(extra);
        return List.copyOf(merged);
    }

    @PostMapping("/ingest")
    public IngestResponse ingest(@Valid @RequestBody IngestRequest request) {
        String source = request.source() != null ? request.source() : "unnamed";
        UUID collectionId = resolveCollectionId(request.collectionId());
        // Request value > collection's configured value (V361) > platform default - see Chunker.
        Chunker.Settings chunking = collectionService.resolveChunking(collectionId, request.chunkSize(), request.chunkOverlap());
        return ingestionService.ingest(source, request.text(), chunking.chunkSize(), chunking.chunkOverlap(), collectionId);
    }

    /**
     * Download a document/page from a public URL and ingest it through the exact {@link #upload}
     * pipeline (dedup, document row, stored original, extraction) - the human-facing twin of
     * {@code add_to_knowledge_base}'s {@code url} argument. {@link
     * KnowledgeUrlIngester} owns the fetch (SSRF-guarded, 5MB cap, redirect re-validation) and the
     * document-vs-text decision; failures surface as its exceptions (HTTP 400-class for bad
     * URLs/unsupported content, 409 on a duplicate).
     */
    @PostMapping("/ingest-url")
    public KnowledgeUrlIngester.UrlIngestResult ingestUrl(@Valid @RequestBody IngestUrlRequest request) {
        UUID resolvedCollectionId = resolveCollectionId(request.collectionId());
        return urlIngester.ingestUrl(
            request.url(),
            resolvedCollectionId,
            "url: " + request.url(),
            request.chunkSize(),
            request.chunkOverlap());
    }

    /**
     * Stage 6: the real entry point - upload a file, chunk+embed+store it.
     * Knowledge Stage F3a extended this beyond {@code .md} to
     * {@code .pdf}/{@code .docx}/{@code .html}/{@code .htm} - text
     * extraction for those now happens in {@code KnowledgeIngestionService.ingestFile}
     * (via the sidecar's {@code /extract}), so this method only does
     * HTTP-level validation (empty/extension/size) and reading the raw bytes.
     */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public IngestResponse upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
        @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap,
        // Knowledge Stage F1 - omit to land in the tenant's default collection.
        @RequestParam(value = "collectionId", required = false) UUID collectionId
    ) {
        if (file.isEmpty()) {
            throw new InvalidKnowledgeUploadException("Uploaded file is empty");
        }
        String filename = file.getOriginalFilename();
        List<String> supported = supportedExtensions();
        if (filename == null || supported.stream().noneMatch(ext -> filename.toLowerCase(java.util.Locale.ROOT).endsWith(ext))) {
            throw new InvalidKnowledgeUploadException(
                "Unsupported file type (got: " + filename + "). Supported: " + String.join(", ", supported)
            );
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new InvalidKnowledgeUploadException("File exceeds the " + (MAX_UPLOAD_BYTES / 1024 / 1024) + "MB limit");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidKnowledgeUploadException("Could not read uploaded file: " + e.getMessage());
        }

        // "Build the backend to make it real" - the KnowledgeDocument row is
        // created right here, before any real chunking/embedding work has
        // happened, so the file table shows this upload immediately (status
        // UPLOADED) even though the rest of this method still runs
        // synchronously to completion within this one HTTP request.
        UUID resolvedCollectionId = resolveCollectionId(collectionId);
        Chunker.Settings chunking = collectionService.resolveChunking(resolvedCollectionId, chunkSize, chunkOverlap);
        // RAG audit fix (2026-07-29) - content-hash dedup BEFORE creating any
        // row or doing any work: re-uploading identical content into the same
        // collection is rejected with a clear remedy instead of silently
        // appending a duplicate chunk set (see requireNoDuplicate's javadoc
        // for why reject-not-replace).
        String contentHash = KnowledgeDocumentService.sha256Hex(content);
        documentService.requireNoDuplicate(resolvedCollectionId, contentHash);
        KnowledgeDocument document = documentService.create(
            resolvedCollectionId, filename, file.getSize(), KnowledgeDocumentStatus.UPLOADED, contentHash
        );
        storeOriginalFile(document, filename, content, file.getContentType());
        try {
            return ingestionService.ingestFile(
                filename,
                content,
                chunking.chunkSize(),
                chunking.chunkOverlap(),
                resolvedCollectionId,
                document.getId()
            );
        } catch (RuntimeException e) {
            documentService.markFailed(document.getId(), e.getMessage());
            throw e;
        }
    }

    /**
     * Knowledge Stage F3c/F3d - the async entry point for slow formats
     * (audio and audio-bearing video). Returns 202 immediately with a job
     * id ({@code GET /api/knowledge/jobs/{jobId}} to poll) rather than
     * blocking the HTTP request for however long transcription takes - see
     * {@code KnowledgeIngestionJob}'s javadoc for why. Validation here
     * mirrors {@link #upload} (empty/extension/size), just against
     * {@link #TRANSCRIBABLE_EXTENSIONS}/{@link #MAX_TRANSCRIBABLE_UPLOAD_BYTES}
     * instead. Endpoint path kept as {@code /upload-audio} rather than
     * renamed to something more video-inclusive - avoids an unnecessary
     * breaking rename of a just-shipped endpoint; the method/field names
     * below are what actually needed to reflect video support.
     */
    @PostMapping(value = "/upload-audio", consumes = "multipart/form-data")
    public ResponseEntity<KnowledgeIngestionJobResponse> uploadAudio(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
        @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap,
        @RequestParam(value = "collectionId", required = false) UUID collectionId
    ) {
        if (file.isEmpty()) {
            throw new InvalidKnowledgeUploadException("Uploaded file is empty");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || TRANSCRIBABLE_EXTENSIONS.stream().noneMatch(ext -> filename.toLowerCase(java.util.Locale.ROOT).endsWith(ext))) {
            throw new InvalidKnowledgeUploadException(
                "Unsupported file type (got: " + filename + "). Supported: " + String.join(", ", TRANSCRIBABLE_EXTENSIONS)
            );
        }
        if (file.getSize() > MAX_TRANSCRIBABLE_UPLOAD_BYTES) {
            throw new InvalidKnowledgeUploadException(
                "File exceeds the " + (MAX_TRANSCRIBABLE_UPLOAD_BYTES / 1024 / 1024) + "MB limit"
            );
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidKnowledgeUploadException("Could not read uploaded file: " + e.getMessage());
        }

        // RAG audit fix (2026-07-29) - same content-hash dedup as the sync
        // upload path above, checked before the job (and its document row)
        // is created.
        UUID resolvedCollectionId = resolveCollectionId(collectionId);
        Chunker.Settings chunking = collectionService.resolveChunking(resolvedCollectionId, chunkSize, chunkOverlap);
        String contentHash = KnowledgeDocumentService.sha256Hex(content);
        documentService.requireNoDuplicate(resolvedCollectionId, contentHash);

        KnowledgeIngestionJobResponse job = jobService.enqueue(
            filename,
            content,
            file.getContentType(),
            chunking.chunkSize(),
            chunking.chunkOverlap(),
            resolvedCollectionId,
            contentHash
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    @PostMapping("/search")
    public List<SearchResultResponse> search(@Valid @RequestBody SearchRequest request) {
        int topK = request.topK() != null ? request.topK() : 5;
        return searchService.search(request.query(), topK, request.collectionIds(), false, request.metadataFilter());
    }

    /**
     * Switch a collection to a different embedding model (see {@link
     * KnowledgeReembedService} for the shadow-then-flip mechanics). Async like
     * {@link #uploadAudio}: 202 with a job to poll via {@link #reembedJob}.
     * Re-submitting the same target while a job is active returns that job;
     * a different target while one is active is a 409. Same auth posture as
     * this controller's other mutating endpoints (tenant scoping comes from
     * schema-per-tenant resolution; no extra annotation, like {@link #ingest}).
     */
    @PostMapping("/collections/{collectionId}/reembed")
    public ResponseEntity<KnowledgeReembedJobResponse> reembed(
        @PathVariable UUID collectionId,
        @Valid @RequestBody KnowledgeReembedRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(reembedService.start(collectionId, request.targetModel()));
    }

    /** Latest re-embed job for a collection (any state), 404 if it never had one. */
    @GetMapping("/collections/{collectionId}/reembed")
    public ResponseEntity<KnowledgeReembedJobResponse> latestReembed(@PathVariable UUID collectionId) {
        collectionService.requireExists(collectionId);
        return reembedService.latestForCollection(collectionId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/reembed-jobs/{jobId}")
    public KnowledgeReembedJobResponse reembedJob(@PathVariable UUID jobId) {
        return reembedService.get(jobId);
    }

    /**
     * "Build the backend to make it real" - tenant-wide totals for the
     * Knowledge screen's topbar, every field a live query (see {@code
     * KnowledgeDocumentService.tenantStats}), no cached or fabricated
     * counter.
     */
    @GetMapping("/stats")
    public KnowledgeStatsResponse stats() {
        return documentService.tenantStats(collectionService.count());
    }

    /**
     * Stage 5 (KB multi-modal storage) - streams back the ORIGINAL uploaded
     * file (image/pdf/audio/video/etc), not the extracted text. Same shape
     * as {@code SarvamGeneratedAudioController#download}: an ordinary
     * authenticated GET, tenant isolation comes from schema-per-tenant
     * resolution ({@code documentService.getEntity} only ever sees rows in
     * the caller's own resolved schema) rather than a bespoke access check.
     * 404s both when the document itself doesn't exist and when it exists
     * but has no stored original (pre-migration row, or the original
     * {@code storeOriginalFile}/{@code put()} call failed) - the caller
     * can't distinguish those two cases from the response, which is fine
     * since the action they'd take (there's no file to show) is identical.
     */
    @GetMapping("/documents/{documentId}/file")
    public ResponseEntity<byte[]> downloadOriginalFile(@PathVariable UUID documentId) {
        KnowledgeDocument document = documentService.getEntity(documentId);
        if (document.getObjectKey() == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] content;
        try (InputStream in = fileStorageService.get(document.getObjectKey())) {
            content = in.readAllBytes();
        } catch (IOException e) {
            throw new FileStorageException("Could not read object '" + document.getObjectKey() + "'", e);
        }
        String contentType = document.getContentType() != null ? document.getContentType() : "application/octet-stream";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                AttachmentDisposition.attachment(document.getFilename()))
            .body(content);
    }

    /**
     * RAG audit fix (2026-07-29) - the per-document delete that didn't
     * exist: before this, the ONLY {@code @DeleteMapping} under knowledge
     * was collection-delete, which is itself refused while chunks reference
     * the collection - so ingested content had no erasure path at all
     * (a real gap next to the platform's tenant-level GDPR/DPDP erasure
     * work, {@code TenantDataErasureService}). Removes the document row,
     * its chunks, and its object-store original - see {@link
     * KnowledgeDocumentService#delete} for the mechanics and the
     * tenant-isolation reasoning (same schema-routing argument as
     * {@link #downloadOriginalFile}).
     */
    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID documentId) {
        documentService.delete(documentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Stage 5 (KB multi-modal storage) - keeps the ORIGINAL uploaded file in
     * object storage alongside whatever text {@code ingestionService}
     * extracts from it, so a later retrieval can hand back the real image/
     * pdf/audio/video, not just its text. Deliberately best-effort: a
     * storage failure here logs a warning and moves on rather than failing
     * the whole upload - the KB's core value (searchable text) doesn't
     * depend on the original file surviving, so it shouldn't be blocked by
     * an object-storage outage. Key convention mirrors {@code
     * ChatAttachmentService}'s ({@code chat-attachments/{agentId}/{uuid}}):
     * the document's own UUID is already tenant-schema-scoped (every tenant
     * has its own {@code knowledge_document} table), so no extra tenant
     * prefix is needed for isolation.
     */
    private void storeOriginalFile(KnowledgeDocument document, String filename, byte[] content, String contentType) {
        String objectKey = "knowledge-documents/" + document.getId() + "/" + filename;
        String resolvedContentType = contentType != null ? contentType : "application/octet-stream";
        try {
            fileStorageService.put(objectKey, content, resolvedContentType);
            documentService.attachOriginalFile(document.getId(), objectKey, resolvedContentType);
        } catch (RuntimeException e) {
            log.warn(
                "Failed to store original file for knowledge document {} ({}) - text extraction will proceed without it: {}",
                document.getId(), filename, e.getMessage()
            );
        }
    }

    /** Knowledge Stage F1 - null/absent means "the tenant's default collection", validated otherwise. */
    private UUID resolveCollectionId(UUID requested) {
        if (requested == null) {
            return collectionService.getDefaultCollectionId();
        }
        collectionService.requireExists(requested);
        return requested;
    }
}
