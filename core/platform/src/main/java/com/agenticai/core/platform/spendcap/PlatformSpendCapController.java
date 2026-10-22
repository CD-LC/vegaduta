package com.agenticai.core.platform.spendcap;

import com.agenticai.core.agents.spendcap.SpendCapService;
import com.agenticai.core.agents.spendcap.SpendCapService.CapStatus;
import com.agenticai.core.platform.PlatformTenantNotFoundException;
import com.agenticai.core.platform.TenantDeletionController;
import com.agenticai.core.platform.ops.PlatformTenantAuditor;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-tenant monthly hosted-pool spend cap for the platform console ({@link SpendCapService}).
 * Role-gated by the blanket {@code /api/platform/** -> ROLE_platform-admin} rule; changing a cap
 * also needs {@code X-Master-Key} and is written to the tenant's own audit log.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform")
public class PlatformSpendCapController {

    private final SpendCapService spendCaps;
    private final TenantRepository tenantRepository;
    private final PlatformTenantAuditor auditor;
    private final String masterKey;

    public PlatformSpendCapController(
            SpendCapService spendCaps,
            TenantRepository tenantRepository,
            PlatformTenantAuditor auditor,
            @Value("${app.platform.master-key:}") String masterKey) {
        this.spendCaps = spendCaps;
        this.tenantRepository = tenantRepository;
        this.auditor = auditor;
        this.masterKey = masterKey;
    }

    /** {@code capUsd} null removes the cap. */
    public record SpendCapUpdate(BigDecimal capUsd) {}

    @GetMapping("/tenants/{tenantId}/spend-cap")
    public CapStatus get(@PathVariable("tenantId") UUID tenantId) {
        return spendCaps
                .status(tenantId)
                .orElseThrow(() -> new PlatformTenantNotFoundException(tenantId));
    }

    @PutMapping("/tenants/{tenantId}/spend-cap")
    public CapStatus put(
            @PathVariable("tenantId") UUID tenantId,
            @RequestHeader(value = "X-Master-Key", required = false) String presentedMasterKey,
            @RequestBody SpendCapUpdate body) {
        TenantDeletionController.authenticateMasterKey(masterKey, presentedMasterKey);
        if (body == null) {
            throw new IllegalArgumentException(
                    "Request body is required ({\"capUsd\": number|null}).");
        }
        Tenant tenant =
                tenantRepository
                        .findById(tenantId)
                        .orElseThrow(() -> new PlatformTenantNotFoundException(tenantId));
        BigDecimal previous = spendCaps.status(tenantId).map(CapStatus::capUsd).orElse(null);
        CapStatus updated =
                spendCaps
                        .updateCap(tenantId, body.capUsd())
                        .orElseThrow(() -> new PlatformTenantNotFoundException(tenantId));
        String actor = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        auditor.record(
                tenant.getSchemaName(),
                actor,
                "platform.tenant.spend_cap",
                "tenant",
                tenantId.toString(),
                "Monthly hosted spend cap changed from "
                        + usd(previous)
                        + " to "
                        + usd(updated.capUsd())
                        + " (month-to-date "
                        + usd(updated.monthToDateUsd())
                        + ")");
        return updated;
    }

    private static String usd(BigDecimal value) {
        return value == null ? "none" : "$" + value.toPlainString();
    }
}
