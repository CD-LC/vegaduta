package com.agenticai.core.chat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/providers/default-model} - the provider + model a new agent in this workspace
 * should start on ({@link TenantDefaultModelService}). Lives beside the rest of {@code
 * /api/providers} but in this module, because the answer depends on the call log, which
 * core/providers cannot see. Read-only and carries no key material, so it needs no more than an
 * authenticated tenant - same as {@code GET /api/providers/availability}.
 */
@RestController
public class TenantDefaultModelController {

    private final TenantDefaultModelService service;

    public TenantDefaultModelController(TenantDefaultModelService service) {
        this.service = service;
    }

    @GetMapping("/api/providers/default-model")
    public TenantDefaultModelService.DefaultModel defaultModel() {
        return service.resolve();
    }
}
