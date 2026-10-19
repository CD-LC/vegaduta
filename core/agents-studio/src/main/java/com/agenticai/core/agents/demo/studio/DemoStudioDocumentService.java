package com.agenticai.core.agents.demo.studio;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.demo.studio.DemoStudioService.StudioException;
import com.agenticai.core.knowledge.KnowledgeCollectionService;
import com.agenticai.core.knowledge.KnowledgeDocumentService;
import com.agenticai.core.knowledge.KnowledgeDocumentStatus;
import com.agenticai.core.knowledge.KnowledgeIngestionService;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionCreateRequest;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionResponse;
import com.agenticai.core.knowledge.dto.KnowledgeDocumentResponse;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Documents for /try Studio agents - real RAG, not a preview. An uploaded
 * file goes through the same chunk + embed + store pipeline the paid
 * Knowledge screen uses, into ONE collection per agent
 * ({@code studio-<agentId>}), and the agent's {@code search_knowledge_base}
 * tool is scoped to exactly that collection.
 *
 * <p><b>Why one collection per agent and not the tenant default.</b> The demo
 * schema is shared by every visitor, and {@code search_knowledge_base} with an
 * empty collection allow-list searches EVERY collection in the schema - so an
 * unscoped agent would read other visitors' uploads. The allow-list is set
 * the moment the first document lands, before the agent can be run again.
 *
 * <p>The knowledge module is {@code @Profile("knowledge")}; when that profile
 * (or the sidecar it embeds through) is absent the whole feature reports
 * {@link #available()} false and the page says so instead of failing.
 *
 * <p>Caps are deliberately tiny - this is a shop window, not storage:
 * {@code app.demo.studio.max-docs-per-agent} (default 3),
 * {@code max-doc-bytes} (default 400 KB).
 */
@Service
public class DemoStudioDocumentService {

    private static final Logger log = LoggerFactory.getLogger(DemoStudioDocumentService.class);

    /** Extensions the sandbox accepts: plain text and markdown ingest directly, the rest go through the sidecar's extractor. */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".txt", ".md", ".csv");
    /** Binary/markup formats that go through the sidecar's extractor. OFF for anonymous visitors by default (untrusted-file parsing, zip-bomb exposure); see extractedTypesEnabled. */
    private static final Set<String> EXTRACTED_EXTENSIONS = Set.of(".pdf", ".docx", ".html", ".htm");
    private static final int CHUNK_SIZE = 150;
    private static final int CHUNK_OVERLAP = 30;
    static final String COLLECTION_PREFIX = "studio-";

    private final ObjectProvider<KnowledgeCollectionService> collections;
    private final ObjectProvider<KnowledgeDocumentService> documents;
    private final ObjectProvider<KnowledgeIngestionService> ingestion;
    private final DemoStudioService studio;
    private final DemoSessionService demoSessionService;
    private final AgentService agentService;
    private final int maxDocsPerAgent;
    private final long maxDocBytes;
    private final boolean extractedTypesEnabled;
    private final DemoStudioModerationService moderation;

    public DemoStudioDocumentService(
        ObjectProvider<KnowledgeCollectionService> collections,
        ObjectProvider<KnowledgeDocumentService> documents,
        ObjectProvider<KnowledgeIngestionService> ingestion,
        DemoStudioService studio,
        DemoSessionService demoSessionService,
        AgentService agentService,
        @Value("${app.demo.studio.max-docs-per-agent:5}") int maxDocsPerAgent,
        @Value("${app.demo.studio.max-doc-bytes:409600}") long maxDocBytes,
        @Value("${app.demo.studio.extracted-doc-types-enabled:false}") boolean extractedTypesEnabled,
        DemoStudioModerationService moderation
    ) {
        this.collections = collections;
        this.documents = documents;
        this.ingestion = ingestion;
        this.studio = studio;
        this.demoSessionService = demoSessionService;
        this.agentService = agentService;
        this.maxDocsPerAgent = maxDocsPerAgent;
        this.maxDocBytes = maxDocBytes;
        this.extractedTypesEnabled = extractedTypesEnabled;
        this.moderation = moderation;
    }

    public record DocumentView(UUID id, String name, long sizeBytes, String status, Integer chunks, String error) {
        static DocumentView of(KnowledgeDocumentResponse d) {
            return new DocumentView(d.id(), d.filename(), d.fileSizeBytes(), d.status().name(), d.chunkCount(), d.errorMessage());
        }
    }

    public record DocumentLimits(boolean available, int maxDocs, long maxBytes, List<String> extensions) {
    }

    public boolean available() {
        return collections.getIfAvailable() != null && documents.getIfAvailable() != null && ingestion.getIfAvailable() != null;
    }

    public DocumentLimits limits() {
        List<String> ext = new java.util.ArrayList<>(TEXT_EXTENSIONS);
        if (extractedTypesEnabled) {
            ext.addAll(EXTRACTED_EXTENSIONS);
        }
        ext.sort(String::compareTo);
        return new DocumentLimits(available(), maxDocsPerAgent, maxDocBytes, ext);
    }

    private void requireAvailable() {
        if (!available()) {
            throw new StudioException("unavailable", 503,
                "Document search isn't switched on in this environment yet.");
        }
    }

    /** The agent's collection id if it already has one. */
    private java.util.Optional<UUID> findCollection(UUID agentId) {
        String name = COLLECTION_PREFIX + agentId;
        return collections.getObject().listAll().stream()
            .filter(c -> name.equals(c.name()))
            .map(KnowledgeCollectionResponse::id)
            .findFirst();
    }

    public List<DocumentView> list(DemoSession session, UUID agentId) {
        if (!available()) {
            return List.of();
        }
        DemoStudioService.Owned owned = studio.requireVisible(session, agentId);
        return demoSessionService.inDemoTenantContext(session, () ->
            findCollection(owned.agent().getId())
                .map(id -> documents.getObject().listByCollection(id).stream().map(DocumentView::of).toList())
                .orElse(List.<DocumentView>of())
        );
    }

    public DocumentView upload(DemoSession session, UUID agentId, String filename, byte[] content) {
        requireAvailable();
        DemoStudioService.Owned owned = studio.requireVisible(session, agentId);
        String cleanName = cleanFilename(filename);
        String ext = extensionOf(cleanName);
        if (!TEXT_EXTENSIONS.contains(ext) && !(extractedTypesEnabled && EXTRACTED_EXTENSIONS.contains(ext))) {
            throw new StudioException("invalid", 400,
                "The sandbox reads plain-text documents: .txt, .md or .csv. Paste the text of a PDF or Word file, or sign up for full document support.");
        }
        if (content == null || content.length == 0) {
            throw new StudioException("invalid", 400, "That file is empty.");
        }
        if (content.length > maxDocBytes) {
            throw new StudioException("invalid", 413,
                "Sandbox documents are limited to " + (maxDocBytes / 1024) + " KB. Sign up for the full knowledge base.");
        }
        // Moderation before anything is stored or embedded. Plain-text types are checked on their text (a file and a
        // pasted note take this same path); the sidecar-extracted types (off by default) can only be checked by name
        // here because their text does not exist until extraction.
        java.util.Map<String, String> visitorText = new java.util.LinkedHashMap<>();
        visitorText.put("document name", cleanName);
        if (TEXT_EXTENSIONS.contains(ext)) {
            visitorText.put("document text", new String(content, StandardCharsets.UTF_8));
        } else {
            log.warn("Studio upload of extracted type {} by sandbox {}: content cannot be moderated before extraction", ext, session.getId());
        }
        moderation.check(session, visitorText);
        UUID actor = owned.creator().getId();
        return demoSessionService.inDemoTenantContextAs(session, actor, () -> {
            KnowledgeCollectionService collectionService = collections.getObject();
            KnowledgeDocumentService documentService = documents.getObject();
            UUID collectionId = findCollection(agentId).orElseGet(() -> collectionService.create(
                new KnowledgeCollectionCreateRequest(COLLECTION_PREFIX + agentId, "Documents for a /try studio agent", null, null, null)
            ).id());
            long existing = documentService.fileCount(collectionId);
            if (existing >= maxDocsPerAgent) {
                throw new StudioException("doc_cap", 429,
                    "Each sandbox agent can hold " + maxDocsPerAgent + " documents. Remove one to add another.");
            }
            String hash = KnowledgeDocumentService.sha256Hex(content);
            try {
                documentService.requireNoDuplicate(collectionId, hash);
            } catch (RuntimeException dup) {
                throw new StudioException("invalid", 409, "That exact document is already attached to this agent.");
            }
            var document = documentService.create(collectionId, cleanName, content.length, KnowledgeDocumentStatus.UPLOADED, hash);
            try {
                if (TEXT_EXTENSIONS.contains(ext)) {
                    ingestion.getObject().ingest(cleanName, new String(content, StandardCharsets.UTF_8),
                        CHUNK_SIZE, CHUNK_OVERLAP, collectionId, document.getId());
                } else {
                    ingestion.getObject().ingestFile(cleanName, content, CHUNK_SIZE, CHUNK_OVERLAP, collectionId, document.getId());
                }
            } catch (RuntimeException e) {
                log.warn("Studio document ingest failed for sandbox {} agent {} ({})", session.getId(), agentId, cleanName, e);
                documentService.markFailed(document.getId(), String.valueOf(e.getMessage()));
                documentService.delete(document.getId());
                throw new StudioException("invalid", 422,
                    "We couldn't read that document. Try a text-based PDF, or paste the text as a .txt file.");
            }
            scopeAgentToCollection(agentId, collectionId, owned);
            return documentService.listByCollection(collectionId).stream()
                .filter(d -> d.id().equals(document.getId()))
                .map(DocumentView::of)
                .findFirst()
                .orElse(new DocumentView(document.getId(), cleanName, content.length, "READY", null, null));
        });
    }

    /** Idempotent: KB on, allow-list scoped to this collection ONLY, and the search tool present on a restricted tool list. */
    private void scopeAgentToCollection(UUID agentId, UUID collectionId, DemoStudioService.Owned owned) {
        if (owned.agent().getExperienceMode() == com.agenticai.core.security.ExperienceMode.SIMPLE) {
            agentService.setExperienceMode(agentId, com.agenticai.core.security.ExperienceMode.ADVANCED);
        }
        agentService.setKnowledgeBaseEnabled(agentId, true);
        agentService.setAllowedKnowledgeCollectionIds(agentId, Set.of(collectionId));
        Set<String> tools = new LinkedHashSet<>(
            owned.agent().getAllowedToolNames() == null ? Set.of() : owned.agent().getAllowedToolNames());
        if (!tools.isEmpty()) {
            tools.remove(DemoStudioService.NO_TOOLS_SENTINEL);
            tools.add("search_knowledge_base");
            agentService.setAllowedToolNames(agentId, tools);
        }
    }

    public void delete(DemoSession session, UUID agentId, UUID documentId) {
        requireAvailable();
        DemoStudioService.Owned owned = studio.requireVisible(session, agentId);
        demoSessionService.inDemoTenantContextAs(session, owned.creator().getId(), () -> {
            UUID collectionId = findCollection(agentId)
                .orElseThrow(() -> new StudioException("not_found", 404, "That document isn't attached to this agent."));
            boolean ours = documents.getObject().listByCollection(collectionId).stream().anyMatch(d -> d.id().equals(documentId));
            if (!ours) {
                throw new StudioException("not_found", 404, "That document isn't attached to this agent.");
            }
            documents.getObject().delete(documentId);
            return null;
        });
    }

    /**
     * Removes an agent's whole collection - documents first, because the
     * collection service refuses to drop a non-empty one. Best effort: an
     * agent delete must never fail because its knowledge could not be cleaned.
     * Runs inside whatever tenant context the caller already bound.
     */
    public void deleteAllFor(UUID agentId) {
        if (!available()) {
            return;
        }
        try {
            findCollection(agentId).ifPresent(collectionId -> {
                for (KnowledgeDocumentResponse d : documents.getObject().listByCollection(collectionId)) {
                    documents.getObject().delete(d.id());
                }
                collections.getObject().delete(collectionId);
            });
        } catch (RuntimeException e) {
            log.warn("Could not clean the knowledge collection of studio agent {}", agentId, e);
        }
    }

    static String cleanFilename(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N} ._\\-()]", "").trim();
        if (name.isEmpty()) {
            return "document.txt";
        }
        return name.length() > 80 ? name.substring(name.length() - 80) : name;
    }

    static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }
}
