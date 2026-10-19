package com.agenticai.core.workflow.portability;

import com.agenticai.core.security.http.AttachmentDisposition;
import com.agenticai.core.workflow.portability.WorkflowPortabilityService.ExportedFile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/workflows/{id}/export}: the workflow as a downloadable VegaDuta workflow DSL v1
 * JSON file (see {@link WorkflowDslV1}). Thin: access (VIEWER, answers 404 when the caller cannot
 * see it), the feature flag ({@code app.workflow.portability.enabled}, off = 404) and the
 * secret/tenant-id stripping all live in {@link WorkflowPortabilityService} and {@link
 * WorkflowDslExporter}.
 */
@RestController
@RequestMapping("/api/workflows/{workflowId}/export")
public class WorkflowExportController {

    private final WorkflowPortabilityService service;

    public WorkflowExportController(WorkflowPortabilityService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<byte[]> export(@PathVariable UUID workflowId) {
        ExportedFile file = service.export(workflowId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        AttachmentDisposition.attachment(file.fileName()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.json().getBytes(StandardCharsets.UTF_8));
    }
}
