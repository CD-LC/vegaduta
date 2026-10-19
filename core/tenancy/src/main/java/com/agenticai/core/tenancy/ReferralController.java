package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.dto.ReferralRedeemRequest;
import com.agenticai.core.tenancy.dto.ReferralRedeemResponse;
import com.agenticai.core.tenancy.dto.ReferralStatusResponse;
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
 * Referral program (PRICING-MONETIZATION-AUDIT-2026-07-31.md §8.5/D3) - the
 * tenant-facing surface of {@link ReferralService}.
 *
 * <p><b>Auth posture</b>, mirroring {@code TrialGrantController}: both routes
 * fall under {@code SecurityConfig}'s {@code anyRequest().authenticated()} (any
 * tenant member can READ status - the web app shows the share code and the
 * "have a code?" input from surfaces any member sees), while the redeem WRITE is
 * tenant-admin-only, enforced here via {@link TenantContext#hasRole} rather than
 * a new {@code SecurityConfig} matcher - SecurityConfig is outside this module's
 * scope and a redemption changes the whole workspace's plan, the same trust tier
 * as {@code POST /api/tenant/trial/claim}.
 *
 * <p>The ineligible case returns 409 with {@code {granted:false, reason}} plus
 * the platform's standard {@code timestamp/status/error} fields - built here
 * (from {@link ReferralNotEligibleException}) instead of a GlobalExceptionHandler
 * mapping, since that file lives outside core/tenancy.
 */
@RestController
@RequestMapping("/api/tenant/referral")
@Profile("security")
public class ReferralController {

    private final ReferralService referralService;

    public ReferralController(ReferralService referralService) {
        this.referralService = referralService;
    }

    @GetMapping
    public ReferralStatusResponse status() {
        return referralService.status(context().getTenantId());
    }

    @PostMapping("/redeem")
    public ResponseEntity<?> redeem(@Valid @RequestBody ReferralRedeemRequest request) {
        TenantContext context = context();
        // Granular RBAC (2026-09-23): redeeming a referral is a billing action
        // - the MANAGE_BILLING scope, not the blanket tenant-admin role.
        if (!context.hasRole("PERM_MANAGE_BILLING") && !context.hasRole("ROLE_platform-admin")) {
            return errorBody(HttpStatus.FORBIDDEN, "Only a billing admin can redeem a referral code.");
        }
        try {
            ReferralRedeemResponse response = referralService.redeem(context.getTenantId(), request.normalizedCode());
            return ResponseEntity.ok(response);
        } catch (ReferralNotEligibleException e) {
            // 409: well-formed, authorized, but tenant state conflicts with
            // redeeming. granted/reason are the contract fields the web app
            // reads; timestamp/status/error match GlobalExceptionHandler's
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

    private TenantContext context() {
        return TenantContext.current()
            .orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated /api/tenant/referral request - SecurityConfig should have blocked this"));
    }

    private ResponseEntity<Map<String, Object>> errorBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
