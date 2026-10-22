package com.agenticai.core.documentgen.service;

import com.agenticai.core.documentgen.DocumentGenerationJobNotFoundException;
import com.agenticai.core.documentgen.entity.DocumentGenerationJob;
import com.agenticai.core.documentgen.event.DocumentGenerationRequestedEvent;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationJobResponse;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.repository.DocumentGenerationJobRepository;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the request-side lifecycle of a document generation job: quota check, saving the {@code
 * REQUESTED} row, and publishing {@link DocumentGenerationRequestedEvent} - never renders bytes
 * itself. {@code DocumentGenerationEventListener} owns the render-side lifecycle (PROCESSING ->
 * COMPLETE/FAILED), consuming the event this class publishes. Same split as {@code
 * VideoGenerationJobService}/{@code VideoGenerationJobRunner}: one class knows how to enqueue a
 * job, another knows how to run it.
 */
@Service
public class DocumentGenerationService {

    private final DocumentGenerationJobRepository jobRepository;
    private final DocumentGenerationQuotaService quotaService;
    private final TenantLimits tenantLimits;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public DocumentGenerationService(
            DocumentGenerationJobRepository jobRepository,
            DocumentGenerationQuotaService quotaService,
            TenantLimits tenantLimits,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.quotaService = quotaService;
        this.tenantLimits = tenantLimits;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * @throws com.agenticai.core.documentgen.DocumentGenerationQuotaExceededException before
     *     anything is spent, if this request would exceed the tenant's tier
     */
    @Transactional
    public DocumentGenerationJobResponse requestGeneration(
            DocumentGenerationRequest request, String tenantId, String requestedBy) {
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        quotaService.checkAllowed(plan, tenantId);

        String contentJson = serializeContent(request.content());
        String filenameWithExtension = request.filename() + request.type().extension();
        DocumentGenerationJob job =
                new DocumentGenerationJob(
                        tenantId, request.type(), filenameWithExtension, contentJson, requestedBy);
        job = jobRepository.save(job);

        // Published inside this @Transactional method on purpose - the
        // listener is a @TransactionalEventListener(phase = AFTER_COMMIT),
        // so it only actually runs once this row is durably committed, even
        // though publishEvent() itself is called here, mid-transaction.
        eventPublisher.publishEvent(
                new DocumentGenerationRequestedEvent(job.getId(), tenantId, request));

        return DocumentGenerationJobResponse.from(job);
    }

    @Transactional(readOnly = true)
    public DocumentGenerationJobResponse getStatus(UUID jobId, String tenantId) {
        return DocumentGenerationJobResponse.from(requireJob(jobId, tenantId));
    }

    /**
     * Tenant-scoped entity lookup for the download endpoint, which needs {@code storageKey}/{@code
     * type} beyond what {@link DocumentGenerationJobResponse} exposes.
     */
    @Transactional(readOnly = true)
    public Optional<DocumentGenerationJob> findJob(UUID jobId, String tenantId) {
        return jobRepository.findByIdAndTenantId(jobId, tenantId);
    }

    /**
     * Backs the Files page's "Generated documents" listing, newest first, with pagination and an
     * optional filename search.
     */
    @Transactional(readOnly = true)
    public Page<DocumentGenerationJobResponse> listPage(
            String tenantId, String search, Pageable pageable) {
        Page<DocumentGenerationJob> page =
                (search == null || search.isBlank())
                        ? jobRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                        : jobRepository
                                .findByTenantIdAndFilenameContainingIgnoreCaseOrderByCreatedAtDesc(
                                        tenantId, search.trim(), pageable);
        return page.map(DocumentGenerationJobResponse::from);
    }

    private DocumentGenerationJob requireJob(UUID jobId, String tenantId) {
        return jobRepository
                .findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(
                        () ->
                                new DocumentGenerationJobNotFoundException(
                                        "No such document generation job."));
    }

    private String serializeContent(DocumentContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            // A DocumentContent field Jackson can't serialize (e.g. an
            // un-serializable `json` passthrough value) is a caller bug in
            // the request body, not a server error - surfaced the same way
            // WorkflowEventPublisher treats an unserializable payload
            // (logged, but here it must actually stop the request since
            // there is nothing to persist).
            throw new IllegalArgumentException(
                    "Document content could not be serialized: " + e.getMessage(), e);
        }
    }
}
