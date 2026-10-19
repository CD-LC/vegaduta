package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.dto.TrialClaimRequest;
import com.agenticai.core.tenancy.dto.TrialClaimResponse;
import com.agenticai.core.tenancy.dto.TrialStatusResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Event-triggered 7-day Premium trial (PRICING-MONETIZATION-AUDIT-2026-07-31.md
 * §5.4 / B6) - the tenant-facing surface of {@link TrialGrantService}.
 *
 * <p><b>Auth posture</b>, mirroring {@code TenantSettingsController}: both
 * routes fall under {@code SecurityConfig}'s {@code anyRequest().authenticated()}
 * (any tenant member can READ status - the web app probes it from gate moments
 * any member can hit), while the claim WRITE is tenant-admin-only, enforced here
 * via {@link TenantContext#hasRole} (the same in-controller pattern
 * {@code WorkflowService}/{@code AgentService} use) rather than a new
 * {@code SecurityConfig} matcher - SecurityConfig is outside this module's scope
 * and a claim changes the whole workspace's plan, the same trust tier as
 * {@code POST /api/tenant/upgrade}.
 *
 * <p>The ineligible case returns 409 with {@code {granted:false, reason}} plus
 * the platform's standard {@code timestamp/status/error} fields - built here
 * (from {@link TrialNotEligibleException}) instead of a GlobalExceptionHandler
 * mapping, since that file lives outside core/tenancy.
 */
@RestController
@RequestMapping("/api/tenant/trial")
@Profile("security")
public class TrialGrantController {

    private final TrialGrantService trialGrantService;

    public TrialGrantController(TrialGrantService trialGrantService) {
        this.trialGrantService = trialGrantService;
    }

    @GetMapping("/status")
    public TrialStatusResponse status() {
        return trialGrantService.status(tenantId());
    }

    @PostMapping("/claim")
    public ResponseEntity<?> claim(@Valid @RequestBody TrialClaimRequest request) {
        TenantContext context = context();
        // Granular RBAC (2026-09-23): starting a trial is a billing action -
        // the MANAGE_BILLING scope, not the blanket tenant-admin role.
        if (!context.hasRole("PERM_MANAGE_BILLING") && !context.hasRole("ROLE_platform-admin")) {
            return errorBody(HttpStatus.FORBIDDEN, "Only a billing admin can start the Premium trial.");
        }
        try {
            TrialClaimResponse response = trialGrantService.claim(context.getTenantId(), request.normalizedTrigger());
            return ResponseEntity.ok(response);
        } catch (TrialNotEligibleException e) {
            // 409: well-formed, authorized, but the tenant's state conflicts
            // with claiming a trial. granted/reason are the contract fields the
            // web app reads; timestamp/status/error match GlobalExceptionHandler's
            // standard error shape so generic error handling still works.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timestamp", Instant.now().toString());
            body.put("status", HttpStatus.CONFLICT.value());
            body.put("error", e.getMessage());
            body.put("granted", false);
            body.put("reason", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
    }

    private String tenantId() {
        return context().getTenantId();
    }

    private TenantContext context() {
        return TenantContext.current()
            .orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated /api/tenant/trial request - SecurityConfig should have blocked this"));
    }

    private ResponseEntity<Map<String, Object>> errorBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
