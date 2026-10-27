package com.agenticai.core.knowledge;

import com.agenticai.core.knowledge.KnowledgeUrlFetcher.FetchedUrl;
import com.agenticai.core.knowledge.dto.IngestResponse;
import com.agenticai.core.storage.FileStorageService;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * "Fetch this URL into a knowledge collection" - the shared pipeline behind {@code
 * add_to_knowledge_base}'s {@code url} argument and {@code POST /api/knowledge/ingest-url}. It
 * reuses the exact upload path rather than inventing a second ingestion lane: a downloaded document
 * (.pdf/.docx/.html/.htm/.md/image) is content-hash deduplicated, gets a {@link KnowledgeDocument}
 * row, its original bytes stored, and goes through {@link KnowledgeIngestionService#ingestFile} -
 * the same three calls {@code KnowledgeController#upload} makes for a file a person uploaded.
 * Anything else (text/plain, JSON, an extensionless page) is decoded as UTF-8 text and handed to
 * {@link KnowledgeIngestionService#ingest} exactly like {@code POST /api/knowledge/ingest}.
 *
 * <p>The fetch itself is {@link KnowledgeUrlFetcher}'s job (SSRF guard + bounded download); this
 * class owns what happens to the bytes.
 */
@Service
@Profile("knowledge")
public class KnowledgeUrlIngester {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeUrlIngester.class);

    // Same set KnowledgeController.SUPPORTED_EXTENSIONS guards /upload with - kept local rather
    // than widening that HTTP-layer constant's visibility (same "private list, mirrored"
    // convention KnowledgeIngestionService's IMAGE_EXTENSIONS already uses).
    private static final List<String> SUPPORTED_EXTENSIONS =
            List.of(".md", ".pdf", ".docx", ".html", ".htm", ".png", ".jpg", ".jpeg", ".webp");

    private static final Map<String, String> CONTENT_TYPE_TO_EXTENSION =
            Map.ofEntries(
                    Map.entry("application/pdf", ".pdf"),
                    Map.entry(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            ".docx"),
                    Map.entry("text/html", ".html"),
                    Map.entry("text/markdown", ".md"),
                    Map.entry("text/x-markdown", ".md"),
                    Map.entry("image/png", ".png"),
                    Map.entry("image/jpeg", ".jpg"),
                    Map.entry("image/webp", ".webp"));

    // Consulted only while Docling is enabled - the same widening
    // KnowledgeController#supportedExtensions applies to /upload. text/csv stays
    // deliberately out of the base map above: without the service a CSV is best
    // treated as decodable text (the old behaviour), not a failed extraction.
    private static final Map<String, String> DOCLING_CONTENT_TYPE_TO_EXTENSION =
            Map.of(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            ".pptx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx",
                    "image/tiff", ".tiff",
                    "image/bmp", ".bmp",
                    "text/csv", ".csv");

    // Content-types that decode straight to text for the plain ingest() path - no extraction
    // step needed.
    private static boolean isTextual(String contentType) {
        return contentType != null
                && (contentType.startsWith("text/")
                        || contentType.equals("application/json")
                        || contentType.equals("application/xml")
                        || contentType.endsWith("+json")
                        || contentType.endsWith("+xml"));
    }

    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeCollectionService collectionService;
    private final KnowledgeDocumentService documentService;
    private final FileStorageService fileStorageService;
    private final KnowledgeUrlFetcher fetcher;

    // Docling-first extraction (app.knowledge.docling.base-url) - widens
    // isSupportedDocument/filenameFor for the formats only that service
    // handles, exactly as KnowledgeController widens /upload. Setter
    // injection keeps both constructors (and every test using them)
    // unchanged; null = inert.
    private volatile DoclingConversionService doclingConversion;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDoclingConversionService(DoclingConversionService service) {
        this.doclingConversion = service;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeUrlIngester(
            KnowledgeIngestionService ingestionService,
            KnowledgeCollectionService collectionService,
            KnowledgeDocumentService documentService,
            FileStorageService fileStorageService) {
        this(
                ingestionService,
                collectionService,
                documentService,
                fileStorageService,
                new KnowledgeUrlFetcher());
    }

    /** Test seam: a stubbed fetcher so no socket is touched. */
    KnowledgeUrlIngester(
            KnowledgeIngestionService ingestionService,
            KnowledgeCollectionService collectionService,
            KnowledgeDocumentService documentService,
            FileStorageService fileStorageService,
            KnowledgeUrlFetcher fetcher) {
        this.ingestionService = ingestionService;
        this.collectionService = collectionService;
        this.documentService = documentService;
        this.fileStorageService = fileStorageService;
        this.fetcher = fetcher;
    }

    /** What the caller reports back - {@code documentId} is null on the text route. */
    public record UrlIngestResult(
            String filename, String source, int chunkCount, UUID documentId, boolean document) {}

    /**
     * Fetches {@code url} and ingests it into {@code collectionId}. {@code textSource} is the
     * {@code source} label used on the plain-text route (the agent tool builds its auditable
     * "written by agent ..." label, the REST endpoint a URL-derived one); the document route labels
     * chunks by filename like a real upload.
     *
     * <p>The caller owns authorization: the agent tool checks its write allow-list before calling
     * here; the REST endpoint relies on the collection being resolved in the tenant's own schema,
     * same as {@code /upload}. This method still re-checks existence defensively.
     */
    public UrlIngestResult ingestUrl(
            String url,
            UUID collectionId,
            String textSource,
            Integer chunkSize,
            Integer chunkOverlap) {
        collectionService.requireExists(collectionId);
        Chunker.Settings chunking =
                collectionService.resolveChunking(collectionId, chunkSize, chunkOverlap);
        FetchedUrl fetched;
        try {
            fetched = fetcher.fetch(url);
        } catch (com.agenticai.core.security.net.SsrfBlockedException e) {
            // A private/link-local/blocked destination is a client error (400 via the shared
            // handler), never a 500 - same message the fetch_web_page tool reports.
            throw new InvalidKnowledgeUploadException(e.getMessage());
        }
        String filename = filenameFor(fetched);

        if (filename != null && isSupportedDocument(filename)) {
            return ingestDocument(collectionId, filename, fetched, chunking);
        }

        String text = decodeText(fetched);
        IngestResponse response =
                ingestionService.ingest(
                        textSource,
                        text,
                        chunking.chunkSize(),
                        chunking.chunkOverlap(),
                        collectionId);
        return new UrlIngestResult(
                filename != null ? filename : filenameFromType(fetched, "fetched.txt"),
                textSource,
                response.chunkCount(),
                null,
                false);
    }

    /**
     * The upload-pipeline twin: dedup -> document row -> store original -> ingestFile -> the row's
     * own status transitions inside ingestFile/markFailed mirror {@code KnowledgeController#upload}
     * call-for-call.
     */
    private UrlIngestResult ingestDocument(
            UUID collectionId, String filename, FetchedUrl fetched, Chunker.Settings chunking) {
        String contentHash = KnowledgeDocumentService.sha256Hex(fetched.body());
        documentService.requireNoDuplicate(collectionId, contentHash);
        KnowledgeDocument document =
                documentService.create(
                        collectionId,
                        filename,
                        fetched.body().length,
                        KnowledgeDocumentStatus.UPLOADED,
                        contentHash);
        storeOriginalFile(document, filename, fetched.body(), fetched.contentType());
        try {
            IngestResponse response =
                    ingestionService.ingestFile(
                            filename,
                            fetched.body(),
                            chunking.chunkSize(),
                            chunking.chunkOverlap(),
                            collectionId,
                            document.getId());
            return new UrlIngestResult(
                    filename, filename, response.chunkCount(), document.getId(), true);
        } catch (RuntimeException e) {
            documentService.markFailed(document.getId(), e.getMessage());
            throw e;
        }
    }

    /**
     * Byte-identical to {@code KnowledgeController#storeOriginalFile} (kept in sync deliberately).
     */
    private void storeOriginalFile(
            KnowledgeDocument document, String filename, byte[] content, String contentType) {
        String objectKey = "knowledge-documents/" + document.getId() + "/" + filename;
        String resolvedContentType =
                contentType != null && !contentType.isBlank()
                        ? contentType
                        : "application/octet-stream";
        try {
            fileStorageService.put(objectKey, content, resolvedContentType);
            documentService.attachOriginalFile(document.getId(), objectKey, resolvedContentType);
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to store original file for knowledge document {} ({}) - text extraction will proceed without it: {}",
                    document.getId(),
                    filename,
                    e.getMessage());
        }
    }

    /**
     * A filename the upload path accepts, or null when nothing names a supported document format
     * (the caller then falls back to the text route). Order: the URL path's own extension when
     * supported, then the Content-Type, then a leading {@code %PDF-} signature (plenty of servers
     * answer octet-stream for a PDF).
     */
    private String filenameFor(FetchedUrl fetched) {
        String fromPath = filenameFromType(fetched, null);
        if (fromPath != null) {
            return fromPath;
        }
        String mapped = CONTENT_TYPE_TO_EXTENSION.get(fetched.contentType());
        if (mapped == null) {
            DoclingConversionService docling = doclingConversion;
            if (docling != null && docling.enabled()) {
                mapped = DOCLING_CONTENT_TYPE_TO_EXTENSION.get(fetched.contentType());
            }
        }
        if (mapped != null) {
            return "fetched-document" + mapped;
        }
        if (looksLikePdf(fetched.body())) {
            return "fetched-document.pdf";
        }
        return null;
    }

    /** The URL path's last segment when it carries a supported extension; null otherwise. */
    private String filenameFromType(FetchedUrl fetched, String fallback) {
        String name = lastPathSegment(fetched.uri());
        if (name != null && isSupportedDocument(name)) {
            return name;
        }
        return fallback;
    }

    private static String lastPathSegment(URI uri) {
        String path = uri.getPath();
        if (path == null) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        // Never trust a remote name as-is: keep the characters a storage key and
        // a filesystem would treat as ordinary.
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? null : name;
    }

    private boolean isSupportedDocument(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith)) {
            return true;
        }
        DoclingConversionService docling = doclingConversion;
        return docling != null && docling.handles(filename);
    }

    private static boolean looksLikePdf(byte[] body) {
        return body.length > 4
                && body[0] == '%'
                && body[1] == 'P'
                && body[2] == 'D'
                && body[3] == 'F';
    }

    /**
     * UTF-8 for the text route. A body that is neither a supported document nor decodable text (an
     * unknown binary type) is rejected here with a caller-safe message rather than silently
     * ingesting mojibake.
     */
    private static String decodeText(FetchedUrl fetched) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(fetched.body()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidKnowledgeUploadException(
                    "The URL's content is not a supported document type (PDF/DOCX/HTML/Markdown/image) "
                            + "and is not decodable text"
                            + describeType(fetched));
        }
    }

    private static String describeType(FetchedUrl fetched) {
        return fetched.contentType() == null || fetched.contentType().isBlank()
                ? ""
                : " (Content-Type: " + fetched.contentType() + ")";
    }
}
