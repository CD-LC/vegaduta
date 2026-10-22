package com.agenticai.core.knowledge.connector;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.knowledge.Chunker;
import com.agenticai.core.knowledge.KnowledgeCollectionRepository;
import com.agenticai.core.knowledge.KnowledgeDocument;
import com.agenticai.core.knowledge.KnowledgeDocumentRepository;
import com.agenticai.core.knowledge.KnowledgeDocumentService;
import com.agenticai.core.knowledge.KnowledgeDocumentStatus;
import com.agenticai.core.knowledge.KnowledgeIngestionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live-connector sync service (P2 product-completeness: "no Notion/Drive/
 * Confluence/SharePoint/web-crawl live sync with citations").
 *
 * <p><b>Sync semantics:</b> a sync replaces, never appends. Each fetched {@link ConnectorDocument}
 * resolves against {@code knowledge_document} by {@code (connector_id, external_ref)}; an existing
 * row is deleted first (chunks + row via {@link KnowledgeDocumentService#delete}) and the fresh
 * content re-ingested through {@code KnowledgeIngestionService.ingestFile} - the identical pipeline
 * uploads run, so chunking, embedding-model selection, and tsv population all behave exactly as
 * they do for files. Documents the provider no longer returns are pruned the same way (a deleted
 * Notion page must not keep answering queries).
 *
 * <p><b>Secrets:</b> {@code config} keys in {@link #SECRET_KEYS} are stored AES-GCM-encrypted
 * inside {@code config_jsonb} (each value individually - the JSON stays inspectable, only the
 * sensitive fields are ciphertext). {@link ConnectorResponse} redaction is belt-and-braces on top:
 * the API never returns a config value under a secret key at all.
 *
 * <p>{@code @Profile("knowledge")} matches the rest of this module - the whole knowledge feature is
 * off on deployments without the profile.
 */
@Service
@Profile("knowledge")
public class KnowledgeConnectorService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeConnectorService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    /** Config keys whose values are credentials - encrypted at rest, never re-served. */
    static final Set<String> SECRET_KEYS =
            Set.of("token", "apiToken", "accessToken", "clientSecret", "password");

    private final KnowledgeConnectorRepository connectorRepository;
    private final KnowledgeCollectionRepository collectionRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeIngestionService ingestionService;
    private final EncryptionService encryptionService;
    private final Map<ConnectorType, ConnectorFetcher> fetchers;

    public KnowledgeConnectorService(
            KnowledgeConnectorRepository connectorRepository,
            KnowledgeCollectionRepository collectionRepository,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeDocumentService documentService,
            KnowledgeIngestionService ingestionService,
            EncryptionService encryptionService,
            List<ConnectorFetcher> fetcherList) {
        this.connectorRepository = connectorRepository;
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.ingestionService = ingestionService;
        this.encryptionService = encryptionService;
        this.fetchers = new java.util.EnumMap<>(ConnectorType.class);
        for (ConnectorFetcher f : fetcherList) {
            this.fetchers.put(f.getType(), f);
        }
    }

    // ------------------------------- CRUD -----------------------------------

    public List<KnowledgeConnector> list(UUID collectionId) {
        return connectorRepository.findByCollectionId(collectionId);
    }

    public KnowledgeConnector getEntity(UUID id) {
        return connectorRepository
                .findById(id)
                .orElseThrow(() -> new KnowledgeConnectorNotFoundException(id));
    }

    @Transactional
    public KnowledgeConnector create(
            UUID collectionId,
            ConnectorType type,
            String name,
            Map<String, String> config,
            String subject) {
        if (!collectionRepository.existsById(collectionId)) {
            throw new com.agenticai.core.knowledge.KnowledgeCollectionNotFoundException(
                    collectionId);
        }
        fetcherFor(type); // validate the type has an implementation before persisting
        KnowledgeConnector connector = new KnowledgeConnector();
        connector.setCollectionId(collectionId);
        connector.setType(type);
        connector.setName(name);
        connector.setConfigJson(writeConfig(config));
        connector.setCreatedBy(subject);
        return connectorRepository.save(connector);
    }

    @Transactional
    public KnowledgeConnector update(
            UUID id, String name, Boolean enabled, Map<String, String> config) {
        KnowledgeConnector connector = getEntity(id);
        if (name != null && !name.isBlank()) {
            connector.setName(name.trim());
        }
        if (enabled != null) {
            connector.setEnabled(enabled);
        }
        if (config != null) {
            // Blank secret values on update mean "keep the stored one" -
            // same keep-the-secret convention as TenantSsoService.
            Map<String, String> merged = readConfig(connector.getConfigJson());
            config.forEach(
                    (k, v) -> {
                        if (SECRET_KEYS.contains(k) && (v == null || v.isBlank())) {
                            return; // keep
                        }
                        if (v == null) {
                            merged.remove(k);
                        } else {
                            merged.put(k, v);
                        }
                    });
            connector.setConfigJson(writeConfig(merged));
        }
        connector.setUpdatedAt(Instant.now());
        return connectorRepository.save(connector);
    }

    @Transactional
    public void delete(UUID id) {
        KnowledgeConnector connector = getEntity(id);
        connectorRepository.delete(connector);
        // Documents stay - deleting a connector stops future syncs, it does
        // not silently erase already-ingested knowledge (the tenant can
        // delete those documents individually if that's what they wanted).
    }

    // ------------------------------- sync -----------------------------------

    /**
     * Fetches the connector's documents and re-ingests them into its collection. Per-document
     * failures are isolated (one bad page can't kill the sync); a fetch-level failure marks {@code
     * last_error} on the row and rethrows so the caller sees it.
     */
    @Transactional
    public ConnectorSyncResult sync(UUID connectorId) {
        KnowledgeConnector connector = getEntity(connectorId);
        if (!connector.isEnabled()) {
            throw new IllegalStateException("Connector is disabled");
        }
        ConnectorFetcher fetcher = fetcherFor(connector.getType());
        Map<String, String> config = readConfig(connector.getConfigJson());

        List<ConnectorDocument> fetched;
        try {
            fetched = fetcher.fetch(config, connector.getSyncCursor());
        } catch (RuntimeException e) {
            connector.setLastError(e.getMessage());
            connector.setUpdatedAt(Instant.now());
            connectorRepository.save(connector);
            throw e;
        }

        int created = 0;
        int refreshed = 0;
        int failed = 0;
        Set<String> seenRefs = new java.util.HashSet<>();
        for (ConnectorDocument doc : fetched) {
            seenRefs.add(doc.externalRef());
            try {
                Optional<KnowledgeDocument> existing =
                        documentRepository.findByConnectorIdAndExternalRef(
                                connector.getId(), doc.externalRef());
                if (existing.isPresent()) {
                    documentService.delete(existing.get().getId());
                    refreshed++;
                } else {
                    created++;
                }
                KnowledgeDocument document =
                        documentService.create(
                                connector.getCollectionId(),
                                doc.filename(),
                                doc.content().length,
                                KnowledgeDocumentStatus.EXTRACTING,
                                null);
                document.attachConnectorSource(
                        doc.sourceUrl(), doc.externalRef(), connector.getId());
                documentRepository.save(document);
                // The target collection's own chunking config (V361), else Chunker's defaults.
                Chunker.Settings chunking =
                        collectionRepository
                                .findById(connector.getCollectionId())
                                .map(
                                        c ->
                                                Chunker.resolve(
                                                        null,
                                                        null,
                                                        c.getChunkSize(),
                                                        c.getChunkOverlap()))
                                .orElse(Chunker.Settings.DEFAULTS);
                ingestionService.ingestFile(
                        doc.filename(),
                        doc.content(),
                        chunking.chunkSize(),
                        chunking.chunkOverlap(),
                        connector.getCollectionId(),
                        document.getId());
            } catch (RuntimeException e) {
                failed++;
                log.warn(
                        "Connector {}: failed ingesting {} ({}): {}",
                        connector.getId(),
                        doc.externalRef(),
                        doc.title(),
                        e.getMessage());
            }
        }

        // Prune documents this connector produced that the provider no
        // longer returns (deleted upstream must stop answering queries).
        int pruned = 0;
        for (KnowledgeDocument doc :
                documentRepository.findByCollectionIdOrderByCreatedAtDesc(
                        connector.getCollectionId())) {
            if (java.util.Objects.equals(connector.getId(), doc.getConnectorId())
                    && doc.getExternalRef() != null
                    && !seenRefs.contains(doc.getExternalRef())) {
                documentService.delete(doc.getId());
                pruned++;
            }
        }

        connector.setLastSyncedAt(Instant.now());
        connector.setLastError(failed > 0 ? failed + " document(s) failed to ingest" : null);
        connector.setUpdatedAt(Instant.now());
        connectorRepository.save(connector);
        return new ConnectorSyncResult(created, refreshed, pruned, failed, fetched.size());
    }

    // --------------------------- config codec --------------------------------

    /** Encrypts secret values, writes the JSON. Blank secrets are dropped entirely. */
    private String writeConfig(Map<String, String> config) {
        Map<String, String> stored = new HashMap<>();
        config.forEach(
                (k, v) -> {
                    if (v == null || v.isBlank()) {
                        return;
                    }
                    stored.put(
                            k,
                            SECRET_KEYS.contains(k)
                                    ? encryptionService.encrypt(v.trim())
                                    : v.trim());
                });
        try {
            return MAPPER.writeValueAsString(stored);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize connector config", e);
        }
    }

    /** Decrypts secret values for fetcher use. Malformed ciphertext fails the sync, loudly. */
    Map<String, String> readConfig(String configJson) {
        try {
            Map<String, String> stored = MAPPER.readValue(configJson, STRING_MAP);
            Map<String, String> resolved = new HashMap<>();
            stored.forEach(
                    (k, v) ->
                            resolved.put(
                                    k, SECRET_KEYS.contains(k) ? encryptionService.decrypt(v) : v));
            return resolved;
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse connector config", e);
        }
    }

    /** Redacted view for API responses - secret keys are present-but-masked. */
    public Map<String, String> redactedConfig(KnowledgeConnector connector) {
        try {
            Map<String, String> stored = MAPPER.readValue(connector.getConfigJson(), STRING_MAP);
            Map<String, String> view = new HashMap<>();
            stored.forEach((k, v) -> view.put(k, SECRET_KEYS.contains(k) ? "***" : v));
            return view;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ConnectorFetcher fetcherFor(ConnectorType type) {
        ConnectorFetcher fetcher = fetchers.get(type);
        if (fetcher == null) {
            throw new IllegalStateException("No fetcher for connector type " + type);
        }
        return fetcher;
    }

    public record ConnectorSyncResult(
            int created, int refreshed, int pruned, int failed, int fetched) {}
}
