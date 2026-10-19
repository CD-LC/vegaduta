package com.agenticai.core.documentgen.service;

import com.agenticai.core.documentgen.entity.DocumentGenerationJob;
import com.agenticai.core.documentgen.event.DocumentGenerationCompletedEvent;
import com.agenticai.core.documentgen.event.DocumentGenerationRequestedEvent;
import com.agenticai.core.documentgen.generator.DocumentGenerator;
import com.agenticai.core.documentgen.generator.DocumentGeneratorRegistry;
import com.agenticai.core.documentgen.repository.DocumentGenerationJobRepository;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.agenticai.core.documentgen.brand.DocumentBrand;
import com.agenticai.core.documentgen.brand.DocumentBrandResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumes {@link DocumentGenerationRequestedEvent} and actually renders the
 * file - the "run one job" half of the split described on {@code
 * DocumentGenerationService}'s javadoc.
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} means this
 * only ever runs once the {@code REQUESTED} row from {@code
 * DocumentGenerationService#requestGeneration} is durably committed;
 * {@code @Async} means it runs off the request thread, on a separate pool
 * thread, so a slow render (real PDF/DOCX layout) never holds up the
 * original HTTP response.
 *
 * <h2>Object-storage key convention - deliberately NOT literally
 * {@code "documentgen/<tenantId>/<jobId>.<ext>"}</h2>
 * {@link FileStorageService#put} transparently tenant-scopes a NEW key by
 * reading {@link TenantContext#current()} on the calling thread (see that
 * interface's implementation javadoc) - the same mechanism {@code
 * GeneratedImageService}/{@code VideoGenerationJobService} rely on with a
 * bare logical key ({@code "generated-images/" + uuid}). Because {@code
 * @Async} runs this method on a fresh thread, {@link TenantContext} is
 * empty here by default (it is a plain {@code ThreadLocal} populated per
 * HTTP request) - exactly the hazard {@code GeneratedImageController}'s own
 * javadoc documents for its streaming download path. This method restores
 * it from {@link DocumentGenerationRequestedEvent#tenantId()} for the
 * duration of {@link #onRequested} so the SAME tenant-prefixed physical key
 * is produced here (via {@link FileStorageService#put}) as {@code
 * DocumentGenerationController#download} will independently recompute on a
 * real, tenant-context-bearing request thread later - manually baking
 * {@code tenantId} into the logical key instead (as a first read of this
 * module's scaffold literally suggested) would have produced a key {@code
 * get} could never match, since {@code get} would additionally prepend its
 * own {@code tenant/<id>/} prefix on top.
 *
 * <h2>{@link TenantContext} must be restored for the WHOLE method, not just
 * the storage call</h2>
 * Live incident, 2026-08-10: every document-generation job on this platform
 * got stuck permanently in {@code REQUESTED} with no error (100% failure
 * rate, confirmed back at least 3 days) because an earlier version of this
 * method only restored {@link TenantContext} around the {@code
 * FileStorageService#put} call - {@link #jobRepository}'s {@code findById}
 * at the top of {@link #onRequested} ran first, on the fresh {@code @Async}
 * thread, with no tenant context at all. This platform is schema-per-tenant
 * (see {@code TenantSchemaResolver}), and that resolver falls back to the
 * {@code public} schema whenever {@link TenantContext} is empty - so the
 * lookup always queried the wrong schema, always came back empty, and
 * always hit the "job vanished" bail-out below, even though the row was
 * sitting exactly where it should be in the tenant's real schema the whole
 * time. {@link TenantContext} is now set before anything else in {@link
 * #onRequested} and held for the entire method body (repository reads,
 * generation, storage, and the final status save all need the same tenant
 * schema).
 */
@Component
public class DocumentGenerationEventListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentGenerationEventListener.class);
    private static final String OBJECT_KEY_PREFIX = "documentgen/";

    private final DocumentGenerationJobRepository jobRepository;
    private final DocumentGeneratorRegistry generatorRegistry;
    private final FileStorageService fileStorageService;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkflowEventPublisher workflowEventPublisher;

    public DocumentGenerationEventListener(
        DocumentGenerationJobRepository jobRepository,
        DocumentGeneratorRegistry generatorRegistry,
        FileStorageService fileStorageService,
        ApplicationEventPublisher eventPublisher,
        WorkflowEventPublisher workflowEventPublisher
    ) {
        this.jobRepository = jobRepository;
        this.generatorRegistry = generatorRegistry;
        this.fileStorageService = fileStorageService;
        this.eventPublisher = eventPublisher;
        this.workflowEventPublisher = workflowEventPublisher;
    }

    /** Optional so existing constructions (tests) keep working; absent -> VegaDuta brand. */
    private DocumentBrandResolver brandResolver;

    @Autowired(required = false)
    void setBrandResolver(DocumentBrandResolver brandResolver) {
        this.brandResolver = brandResolver;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(DocumentGenerationRequestedEvent event) {
        // MUST be set before the very first repository call, not just around
        // the file-storage put below - live incident, 2026-08-10: every
        // single document-generation job on this platform (going back at
        // least 3 days, 100% failure rate) got stuck permanently in
        // REQUESTED with no error, because @Async hands this method a fresh
        // thread with no TenantContext (a plain ThreadLocal, populated per
        // HTTP request - see this class's own javadoc on the same hazard for
        // the storage call). TenantSchemaResolver#resolveSchema falls back
        // to the "public" schema whenever TenantContext is empty
        // (TenantSchemaResolver.java, DEFAULT_SCHEMA) - so
        // jobRepository.findById(event.jobId()) below queried "public",
        // never the tenant's real schema where the REQUESTED row actually
        // lives, always came back empty, and hit the "should not happen"
        // bail-out path (log.warn + return) on every single job. Restoring
        // TenantContext for the ENTIRE method - not just the storage call -
        // fixes the repository lookup too.
        if (event.tenantId() != null && !event.tenantId().isBlank()) {
            TenantContext.set(TenantContext.of(event.tenantId(), "documentgen-async-listener", Set.of()));
        }
        try {
            processRequestedEvent(event);
        } finally {
            TenantContext.clear();
        }
    }

    private void processRequestedEvent(DocumentGenerationRequestedEvent event) {
        DocumentGenerationJob job = jobRepository.findById(event.jobId()).orElse(null);
        if (job == null) {
            // Should not happen (the row was committed before this event was
            // published), but a background/async handler must never assume
            // that - log and bail rather than NPE.
            log.warn("Document generation job {} vanished before processing could start", event.jobId());
            return;
        }

        job.markProcessing();
        jobRepository.save(job);

        boolean success;
        try {
            DocumentGenerator generator = generatorRegistry.find(event.request().type())
                .orElseThrow(() -> new IllegalStateException(
                    "No document generator is registered for " + event.request().type() + " yet."));
            // Tenant brand when the tenant set one, VegaDuta's otherwise (2026-10-09).
            DocumentBrand brand = brandResolver != null
                ? brandResolver.resolve(event.tenantId())
                : DocumentBrandResolver.platform();
            byte[] bytes = generator.generate(event.request(), brand);

            String objectKey = OBJECT_KEY_PREFIX + job.getId() + event.request().type().extension();
            fileStorageService.put(objectKey, bytes, event.request().type().mimeType());

            job.markComplete(objectKey);
            success = true;
        } catch (Exception e) {
            log.warn("Document generation job {} failed", job.getId(), e);
            job.markFailed(e.getMessage() == null ? "Document generation failed." : e.getMessage());
            success = false;
        }
        jobRepository.save(job);

        eventPublisher.publishEvent(new DocumentGenerationCompletedEvent(job.getId(), event.tenantId(), success));
        publishWorkflowEvent(job, success);
    }

    /**
     * Never allowed to fail the job - a disabled ({@code
     * app.workflow-events.enabled=false}, the default) or momentarily broken
     * eventbus must not turn a successfully generated document into a
     * FAILED job. Logged, not rethrown.
     */
    private void publishWorkflowEvent(DocumentGenerationJob job, boolean success) {
        try {
            workflowEventPublisher.publish(
                WorkflowEventTypes.DOCUMENT_GENERATION_COMPLETED,
                job.getId().toString(),
                Map.of(
                    "jobId", job.getId().toString(),
                    "tenantId", job.getTenantId() == null ? "" : job.getTenantId(),
                    "success", success
                )
            );
        } catch (RuntimeException e) {
            log.warn(
                "Failed to publish workflow event for document generation job {} - the job's own status is unaffected",
                job.getId(), e
            );
        }
    }
}
