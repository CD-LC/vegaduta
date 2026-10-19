package com.agenticai.core.documentgen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.documentgen.entity.DocumentGenerationJob;
import com.agenticai.core.documentgen.event.DocumentGenerationRequestedEvent;
import com.agenticai.core.documentgen.generator.DocumentGenerator;
import com.agenticai.core.documentgen.generator.DocumentGeneratorRegistry;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import com.agenticai.core.documentgen.repository.DocumentGenerationJobRepository;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Live incident, 2026-08-10: every document-generation job on this platform got stuck permanently
 * in {@code REQUESTED} with no error - 100% failure rate, confirmed back at least 3 days. Root
 * cause: {@link DocumentGenerationEventListener#onRequested} only restored {@link TenantContext}
 * around the {@code FileStorageService#put} call, not around the earlier {@code
 * jobRepository.findById} - so on the fresh {@code @Async} thread, that lookup ran with no tenant
 * context, {@code TenantSchemaResolver} fell back to the {@code public} schema, and the lookup
 * always came back empty even though the row was sitting in the tenant's real schema the whole
 * time. This suite locks in that {@link TenantContext} is populated BEFORE the very first
 * repository call, for the entire method, and cleared afterward either way.
 */
class DocumentGenerationEventListenerTest {

    private static final String TENANT_ID = "acme_corp";

    private DocumentGenerationJobRepository jobRepository;
    private DocumentGeneratorRegistry generatorRegistry;
    private FileStorageService fileStorageService;
    private ApplicationEventPublisher eventPublisher;
    private WorkflowEventPublisher workflowEventPublisher;
    private DocumentGenerationEventListener listener;

    private DocumentGenerationEventListener buildListener() {
        jobRepository = mock(DocumentGenerationJobRepository.class);
        generatorRegistry = mock(DocumentGeneratorRegistry.class);
        fileStorageService = mock(FileStorageService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        workflowEventPublisher = mock(WorkflowEventPublisher.class);
        return new DocumentGenerationEventListener(
            jobRepository, generatorRegistry, fileStorageService, eventPublisher, workflowEventPublisher);
    }

    private static DocumentGenerationRequestedEvent event(UUID jobId) {
        DocumentContent content = new DocumentContent("Title", List.of(), null, null, null);
        DocumentGenerationRequest request = new DocumentGenerationRequest(DocumentType.PDF, "report", content);
        return new DocumentGenerationRequestedEvent(jobId, TENANT_ID, request);
    }

    @Test
    void tenantContextIsPopulatedBeforeTheFirstRepositoryLookup() throws Exception {
        listener = buildListener();
        UUID jobId = UUID.randomUUID();
        DocumentGenerationJob job = new DocumentGenerationJob(
            TENANT_ID, DocumentType.PDF, "report.pdf", "{}", "tester");

        // The exact bug: capture whatever TenantContext looks like AT THE
        // MOMENT this lookup runs, same as TenantSchemaResolver would see it.
        java.util.concurrent.atomic.AtomicReference<Optional<TenantContext>> seenDuringLookup =
            new java.util.concurrent.atomic.AtomicReference<>();
        when(jobRepository.findById(jobId)).thenAnswer(invocation -> {
            seenDuringLookup.set(TenantContext.current());
            return Optional.of(job);
        });
        DocumentGenerator generator = mock(DocumentGenerator.class);
        when(generatorRegistry.find(DocumentType.PDF)).thenReturn(Optional.of(generator));
        when(generator.generate(any())).thenReturn(new byte[] {1, 2, 3});

        listener.onRequested(event(jobId));

        assertTrue(seenDuringLookup.get().isPresent(), "TenantContext must be set before the repository lookup, not after");
        assertEquals(TENANT_ID, seenDuringLookup.get().get().getTenantId());
    }

    @Test
    void tenantContextIsClearedAfterProcessingEvenOnFailure() {
        listener = buildListener();
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

        listener.onRequested(event(jobId));

        assertFalse(TenantContext.current().isPresent(), "must not leak TenantContext onto whatever this pool thread runs next");
    }

    @Test
    void jobIsMarkedCompleteWhenTenantContextResolvesTheRealRow() throws Exception {
        listener = buildListener();
        UUID jobId = UUID.randomUUID();
        DocumentGenerationJob job = new DocumentGenerationJob(
            TENANT_ID, DocumentType.PDF, "report.pdf", "{}", "tester");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        DocumentGenerator generator = mock(DocumentGenerator.class);
        when(generatorRegistry.find(DocumentType.PDF)).thenReturn(Optional.of(generator));
        when(generator.generate(any())).thenReturn(new byte[] {1, 2, 3});

        listener.onRequested(event(jobId));

        assertEquals(com.agenticai.core.documentgen.model.DocumentGenerationStatus.COMPLETE, job.getStatus());
    }
}
