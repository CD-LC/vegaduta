package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.CompGrantRequest;
import com.agenticai.core.tenancy.dto.CompGrantResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Academy Fast-Track Scholarship one-click fulfilment (CORE-COMP-GRANT-CONTRACT.md).
 * Grants a tenant a COMPLIMENTARY paid plan - no gateway subscription, no charge -
 * that auto-expires, distinct from the paid self-serve {@code TenantUpgradeService}
 * flow. The Academy calls this service-to-service via {@code CompGrantController}
 * when an admin marks a scholarship claim fulfilled.
 *
 * <p><b>Why no gateway at all:</b> a scholarship recipient is being given the tier
 * for free, so there is nothing to bill and no Razorpay subscription to create -
 * this reuses {@link Tenant#grantComplimentaryPlan}, which sets the plan and an
 * expiry while leaving every {@code gateway*} column untouched, exactly the "plan
 * flips, billing state doesn't" primitive {@link Tenant#changePlan} (the
 * platform-admin override) already established. The only thing added over that
 * override is the recorded expiry, which {@link CompGrantExpiryReverter} acts on.
 *
 * <p><b>Idempotent-friendly:</b> re-granting the same (or a different) tenant
 * simply overwrites the comp fields with a fresh {@code now + durationMonths}
 * expiry - a repeated fulfilment click refreshes rather than stacks.
 */
@Service
@Profile("security")
public class CompGrantService {

    private static final Logger log = LoggerFactory.getLogger(CompGrantService.class);

    private final TenantRepository tenantRepository;

    public CompGrantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public CompGrantResponse grant(CompGrantRequest request) {
        if (request.targetPlan() == TenantPlan.FREE) {
            // 400 via GlobalExceptionHandler's IllegalArgumentException handler - a
            // comp grant of FREE is meaningless (FREE is the post-expiry default).
            throw new IllegalArgumentException("targetPlan must be a paid tier (PREMIUM or UNLIMITED), not FREE.");
        }

        Tenant tenant = tenantRepository.findById(request.tenantId())
            // 404 via GlobalExceptionHandler's NoSuchElementException handler.
            .orElseThrow(() -> new NoSuchElementException("No tenant with id '" + request.tenantId() + "'"));

        Instant grantedAt = Instant.now();
        // Instant has no calendar-month arithmetic; go through a UTC calendar date
        // so "3 months" lands on the same day-of-month three months out, not a flat
        // 90-day approximation.
        Instant expiresAt = grantedAt.atOffset(ZoneOffset.UTC).plusMonths(request.durationMonths()).toInstant();

        tenant.grantComplimentaryPlan(request.targetPlan(), grantedAt, expiresAt, request.reason());
        tenantRepository.save(tenant);

        log.info("Comp grant issued: tenant={} plan={} expiresAt={} reason={}",
            tenant.getId(), request.targetPlan(), expiresAt, request.reason());

        return new CompGrantResponse(
            tenant.getId(), tenant.getPlan(), grantedAt, expiresAt, request.reason()
        );
    }
}
