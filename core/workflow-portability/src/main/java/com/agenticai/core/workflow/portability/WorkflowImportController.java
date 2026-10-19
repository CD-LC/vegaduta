package com.agenticai.core.workflow.portability;

import com.agenticai.core.workflow.portability.PortabilityDtos.ImportResponse;
import com.agenticai.core.workflow.portability.PortabilityDtos.PreviewResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflow migration routes: preview what importing a workflow file (VegaDuta DSL, n8n JSON, Dify
 * YAML) would produce, and create the DRAFT workflow. The same preview-then-create shape as the
 * agent importer ({@code /api/agents/import}).
 *
 * <p>Thin on purpose: the body is read as raw text through {@link
 * WorkflowPortabilityService#readBounded}, which answers 413 as soon as the size cap is crossed;
 * the feature flag ({@code app.workflow.portability.enabled}, off = 404), validation, limits and
 * every status code are decided in the service, using only exception types {@code
 * GlobalExceptionHandler} already maps. {@code SecurityConfig} requires {@code
 * PERM_MANAGE_WORKFLOWS_ADMIN} on both routes, and the tenant comes from {@code TenantContext},
 * never from the body.
 */
@RestController
@RequestMapping("/api/workflows/import")
public class WorkflowImportController {

    private final WorkflowPortabilityService service;

    public WorkflowImportController(WorkflowPortabilityService service) {
        this.service = service;
    }

    /** 200; creates nothing for the tenant. */
    @PostMapping("/preview")
    public PreviewResponse preview(HttpServletRequest request) {
        service.requireEnabled();
        return service.preview(readBody(request));
    }

    /** 201: a DRAFT workflow was created through the normal create path. */
    @PostMapping
    public ResponseEntity<ImportResponse> importWorkflow(HttpServletRequest request) {
        service.requireEnabled();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.importWorkflow(readBody(request)));
    }

    private String readBody(HttpServletRequest request) {
        try {
            return service.readBounded(request.getInputStream());
        } catch (IOException e) {
            throw new IllegalArgumentException("The request body could not be read.");
        }
    }
}
