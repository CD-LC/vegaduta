package com.agenticai.core.agents.importer;

import com.agenticai.core.agents.importer.AgentImportDtos.ImportResponse;
import com.agenticai.core.agents.importer.AgentImportDtos.PreviewResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Migration-kit routes: preview what importing an agent from another platform would produce, and create
 * the draft agent.
 *
 * <p>Thin on purpose. The body is read as raw text through {@link AgentImportService#readBounded}, which stops
 * buffering and answers 413 once the size cap is crossed (so an oversized upload never sits whole in memory),
 * and {@link AgentImportService} then enforces nesting limits before any object graph is built; the feature
 * flag, validation, limits and every status code are decided there, using only types {@code
 * GlobalExceptionHandler} already maps. {@code SecurityConfig} requires {@code PERM_MANAGE_AGENTS} on both
 * routes, and the tenant comes from the request's {@code TenantContext}, never from the body.
 */
@RestController
@RequestMapping("/api/agents/import")
public class AgentImportController {

    private final AgentImportService service;

    public AgentImportController(AgentImportService service) {
        this.service = service;
    }

    /** 200; creates nothing for the tenant. */
    @PostMapping("/preview")
    public PreviewResponse preview(HttpServletRequest request) {
        return service.preview(readBody(request));
    }

    /** 201: a private agent was created through the normal create path. */
    @PostMapping
    public ResponseEntity<ImportResponse> importAgent(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.importAgent(readBody(request)));
    }

    private String readBody(HttpServletRequest request) {
        try {
            return service.readBounded(request.getInputStream());
        } catch (IOException e) {
            throw new IllegalArgumentException("The request body could not be read.");
        }
    }
}
