package com.agenticai.core.platform.ops;

import com.agenticai.core.platform.ops.PlatformEmailSuppressionService.SuppressionPage;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Email suppression list across tenants ({@link PlatformEmailSuppressionService}). Gated by the
 * blanket {@code /api/platform/** -> ROLE_platform-admin} rule. {@code page} is 1-based.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform/email/suppressions")
public class PlatformEmailSuppressionController {

    private final PlatformEmailSuppressionService suppressions;

    public PlatformEmailSuppressionController(PlatformEmailSuppressionService suppressions) {
        this.suppressions = suppressions;
    }

    @GetMapping
    public SuppressionPage list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return suppressions.list(q, page, limit);
    }

    /**
     * 204 once removed from at least one list; 404 when no list held it. Optional {@code tenantId}
     * scopes it.
     */
    @DeleteMapping("/{email}")
    public ResponseEntity<Void> remove(
            @PathVariable("email") String email,
            @RequestParam(name = "tenantId", required = false) String tenantId) {
        String actor = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        suppressions.remove(email, tenantId, actor);
        return ResponseEntity.noContent().build();
    }
}
