package com.agenticai.core.agents.face;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.knowledge.KnowledgeCollectionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Face recognition (2026-08-06) - CRUD against {@code knowledge_face_enrollment}
 * (migration V187). Raw JDBC, not Hibernate, for the exact same reason as
 * {@code KnowledgeIngestionService}/{@code knowledge_chunks}: pgvector's
 * {@code vector} column type has no Hibernate mapping. Lives in {@code
 * core/agents} rather than {@code core/knowledge} because the one thing
 * every method here does FIRST is check {@link AgentService#isBiometricConsentEnabled}
 * - {@code core/knowledge} has no dependency on {@code core/agents} (only
 * the reverse), so this couldn't live there without a circular module
 * dependency. KnowledgeSchemaSupport#qualifiedTableName(String) was
 * widened to {@code public} for exactly this reuse.
 *
 * <p>All ML compute (face detection, embedding, matching) happens
 * client-side (see {@code web/app/lib/edge/face.ts}) - this service only
 * ever stores/returns the 512 floats a browser already computed. Matching
 * itself (cosine similarity against the list {@link #listForCollection}
 * returns) also happens client-side, so this class never does a
 * pgvector similarity query - it's pure storage, not inference.
 */
@Service
public class FaceEnrollmentService {

    private static final int EMBEDDING_DIMENSIONS = 512;

    private final FaceEnrollmentJdbcRepository repository;
    private final AgentService agentService;
    private final KnowledgeCollectionService collectionService;

    public FaceEnrollmentService(FaceEnrollmentJdbcRepository repository, AgentService agentService, KnowledgeCollectionService collectionService) {
        this.repository = repository;
        this.agentService = agentService;
        this.collectionService = collectionService;
    }

    /**
     * Enrolls one person's face embedding. {@code consented} must be {@code
     * true} - it is the caller's (the web Playground's consent-gate UI)
     * explicit confirmation that the person whose photo this is (or the
     * tenant member uploading it, for their own reference photos) agreed to
     * biometric processing; this method refuses to write a row otherwise
     * rather than silently treating omission as consent. Throws {@link
     * BiometricConsentNotEnabledException} (403) if the agent hasn't been
     * opted in via {@code PATCH /api/agents/{id}/biometric-consent} - the
     * per-agent gate is checked in addition to, never instead of, this
     * per-enrollment consent flag.
     */
    @Transactional
    public UUID enroll(
        UUID agentId, UUID collectionId, UUID documentId, String personName, List<Double> embedding, boolean consented
    ) {
        requireConsent(agentId);
        collectionService.requireExists(collectionId);
        if (!consented) {
            throw new IllegalArgumentException("Face enrollment requires explicit consent (consented=true)");
        }
        if (embedding == null || embedding.size() != EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException(
                "Face embedding must have exactly " + EMBEDDING_DIMENSIONS + " dimensions (got: "
                    + (embedding == null ? "null" : embedding.size()) + ")"
            );
        }
        if (personName == null || personName.isBlank()) {
            throw new IllegalArgumentException("personName is required");
        }

        UUID id = UUID.randomUUID();
        repository.insert(id, collectionId, documentId, personName.trim(), embedding, Instant.now());
        return id;
    }

    /**
     * Every enrolled face in {@code collectionId}, embeddings included - the
     * web Playground fetches this ONCE per session (not per-frame) and does
     * cosine-similarity matching against camera frames entirely client-side,
     * so this is the only server round-trip the matching path ever makes.
     */
    @Transactional(readOnly = true)
    public List<FaceEnrollmentRecord> listForCollection(UUID agentId, UUID collectionId) {
        requireConsent(agentId);
        collectionService.requireExists(collectionId);
        return repository.listForCollection(collectionId);
    }

    /**
     * Erasure - deliberately does NOT require {@link #requireConsent}: per
     * {@code GraphMemoryService#eraseSubject}'s precedent, the ability to
     * delete biometric data must never itself be gated behind the consent
     * flag that created it (turning consent off first, then trying to
     * delete, must still work). Schema-per-tenant routing ({@link
     * KnowledgeSchemaSupport}) is the only isolation boundary needed - no
     * cross-tenant row can ever match this id.
     */
    @Transactional
    public void delete(UUID enrollmentId) {
        repository.deleteById(enrollmentId);
    }

    private void requireConsent(UUID agentId) {
        if (!agentService.isBiometricConsentEnabled(agentId)) {
            throw new BiometricConsentNotEnabledException(
                "Face recognition is not enabled for this agent. A tenant admin must turn it on via "
                    + "PATCH /api/agents/" + agentId + "/biometric-consent before enrolling or matching faces."
            );
        }
    }

    /** One enrolled face, as returned to the client for local matching. */
    public record FaceEnrollmentRecord(UUID id, String personName, List<Double> embedding, Instant createdAt) {
    }
}
