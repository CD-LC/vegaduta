package com.agenticai.core.platform.spendcap;

import com.agenticai.core.agents.spendcap.SpendCapService;
import com.agenticai.core.agents.spendcap.SpendCapService.CapStatus;
import com.agenticai.core.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/tenant/spend-cap} - any signed-in member of a tenant sees
 * their own tenant's monthly hosted spend cap and month-to-date spend (same
 * shape as the platform endpoint). Read-only; only platform admins set caps.
 */
@RestController
public class TenantSpendCapController {

    private final SpendCapService spendCaps;

    public TenantSpendCapController(SpendCapService spendCaps) {
        this.spendCaps = spendCaps;
    }

    @GetMapping("/api/tenant/spend-cap")
    public CapStatus get() {
        String schema = TenantContext.current().map(TenantContext::getTenantId)
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tenant on this request."));
        return spendCaps.statusForSchema(schema)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found."));
    }
}
