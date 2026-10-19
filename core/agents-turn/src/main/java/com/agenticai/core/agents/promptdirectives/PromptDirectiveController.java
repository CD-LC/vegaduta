package com.agenticai.core.agents.promptdirectives;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's view of the platform's built-in prompt directives: read them, switch one off,
 * reword it, add a free-text block for every agent. Tenant-admin only (the personal install's
 * owner holds that role). A security directive needs {@code acknowledgeRisk=true} to change.
 */
@RestController
@RequestMapping("/api/prompt-directives")
@PreAuthorize(PromptDirectiveController.TENANT_ADMIN)
public class PromptDirectiveController {

    static final String TENANT_ADMIN = "hasAnyAuthority('ROLE_tenant-admin', 'ROLE_platform-admin')";

    /** Body of {@code PUT /{key}}; blank or null {@code text} = the built-in wording. */
    public record DirectiveUpdate(boolean enabled, String text, boolean acknowledgeRisk) {}

    /** Body of {@code PUT /custom-instructions}. */
    public record CustomInstructionsUpdate(boolean enabled, String text) {}

    private final PromptDirectiveAdminService service;

    public PromptDirectiveController(PromptDirectiveAdminService service) {
        this.service = service;
    }

    @GetMapping
    public PromptDirectiveAdminService.Overview overview() {
        return service.overview();
    }

    @PutMapping("/custom-instructions")
    public PromptDirectiveAdminService.Overview putCustomInstructions(
            @RequestBody CustomInstructionsUpdate body) {
        service.putCustomInstructions(body.enabled(), body.text());
        return service.overview();
    }

    @DeleteMapping("/custom-instructions")
    public ResponseEntity<Void> clearCustomInstructions() {
        service.reset("custom_instructions");
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{key}")
    public PromptDirectiveAdminService.Overview put(
            @PathVariable String key, @RequestBody DirectiveUpdate body) {
        service.put(key, body.enabled(), body.text(), body.acknowledgeRisk());
        return service.overview();
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> reset(@PathVariable String key) {
        service.reset(key);
        return ResponseEntity.noContent().build();
    }
}
