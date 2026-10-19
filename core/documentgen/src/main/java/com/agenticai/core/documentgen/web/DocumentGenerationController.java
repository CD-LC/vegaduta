package com.agenticai.core.documentgen.web;

import com.agenticai.core.documentgen.DocumentGenerationQuotaExceededException;
import com.agenticai.core.documentgen.entity.DocumentGenerationJob;
import com.agenticai.core.documentgen.model.DocumentGenerationJobResponse;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentGenerationStatus;
import com.agenticai.core.documentgen.service.DocumentGenerationService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import jakarta.validation.Valid;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Authenticated document generation: {@code POST /api/documentgen/documents}
 * enqueues (quota/plan-gated - see {@code DocumentGenerationQuotaService}),
 * {@code GET /api/documentgen/documents} pages/searches the tenant's own job
 * history (backs the Files page), {@code GET
 * /api/documentgen/documents/{id}} streams the finished bytes once {@code
 * COMPLETE}. Same three-endpoint shape {@code VideoGenerationController}
 * already uses for its async-job case, plus real pagination/search (the
 * Files page's other four generation surfaces - imagegen, video, Sarvam
 * audio, knowledge documents - list everything client-side with none).
 */
@RestController
@RequestMapping("/api/documentgen/documents")
public class DocumentGenerationController {

    private final DocumentGenerationService service;
    private final FileStorageService fileStorageService;

    public DocumentGenerationController(DocumentGenerationService service, FileStorageService fileStorageService) {
        this.service = service;
        this.fileStorageService = fileStorageService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentGenerationJobResponse generate(@Valid @RequestBody DocumentGenerationRequest request) {
        return service.requestGeneration(request, currentTenantId(), currentSubject());
    }

    /**
     * A refused request (FREE plan, switched off, cap used up) is an expected answer with a readable
     * message, not a 500: 403 for the plan, 503 when the feature is off, 429 for a used-up cap.
     */
    @ExceptionHandler(DocumentGenerationQuotaExceededException.class)
    public ResponseEntity<java.util.Map<String, Object>> quotaExceeded(DocumentGenerationQuotaExceededException e) {
        HttpStatus status = switch (e.getReason()) {
            case PLAN -> HttpStatus.FORBIDDEN;
            case DISABLED -> HttpStatus.SERVICE_UNAVAILABLE;
            case LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
        };
        return ResponseEntity.status(status).body(java.util.Map.of(
            "status", status.value(), "error", status.getReasonPhrase(), "message", e.getMessage()));
    }

    @GetMapping
    public Page<DocumentGenerationJobResponse> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String search
    ) {
        Pageable pageable = PageRequest.of(page, size);
        return service.listPage(currentTenantId(), search, pageable);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id) {
        Optional<DocumentGenerationJob> jobOpt = service.findJob(id, currentTenantId());
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DocumentGenerationJob job = jobOpt.get();
        if (job.getStatus() != DocumentGenerationStatus.COMPLETE || job.getStorageKey() == null) {
            // 409: the job exists but isn't ready yet (or failed) - distinct
            // from 404 (no such job at all / belongs to another tenant).
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        MediaType contentType = MediaType.parseMediaType(job.getType().mimeType());
        String storageKey = job.getStorageKey();
        return ResponseEntity.ok()
            .contentType(contentType)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(job.getFilename()).build().toString())
            .body(streamBody(storageKey));
    }

    /**
     * Same {@link TenantContext} capture-and-restore need as {@code
     * GeneratedImageController#streamBody} - Spring dispatches this {@link
     * StreamingResponseBody} callback asynchronously on a separate thread,
     * where the request-scoped {@link TenantContext} ThreadLocal is empty.
     */
    private StreamingResponseBody streamBody(String storageKey) {
        TenantContext capturedContext = TenantContext.current().orElse(null);
        return outputStream -> {
            TenantContext.set(capturedContext);
            try (InputStream in = fileStorageService.get(storageKey)) {
                in.transferTo(outputStream);
            } finally {
                TenantContext.clear();
            }
        };
    }

    private String currentTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse(null);
    }

    private String currentSubject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }
}
