package com.agenticai.core.documentgen.service;

import com.agenticai.core.documentgen.DocumentGenerationQuotaExceededException;
import com.agenticai.core.documentgen.model.DocumentGenerationStatus;
import com.agenticai.core.documentgen.repository.DocumentGenerationJobRepository;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Entitlement + caps for authenticated document generation - mirrors {@code
 * com.agenticai.core.imagegen.VideoGenerationQuotaService} exactly: FREE is
 * rejected outright (server-side document rendering is a PAID feature, same
 * product posture as server-side video), PREMIUM/UNLIMITED get tunable
 * daily/monthly ceilings read live from {@link PlatformSettingsService} with
 * {@code @Value} fallbacks for a fresh database that predates any admin-set
 * row.
 */
@Service
public class DocumentGenerationQuotaService {

    /** Package-private so a future tool-callback/controller test can assert the exact copy that reaches a FREE user. */
    static final String FREE_PLAN_REJECTION_MESSAGE =
        "Document generation (DOCX/PDF/XLSX/PPTX) is a paid feature — upgrade to generate files on VegaDūta's servers.";

    private final DocumentGenerationJobRepository jobRepository;
    private final PlatformSettingsService platformSettings;
    private final boolean defaultEnabled;
    private final int defaultPaidDailyCap;
    private final int defaultPaidMonthlyCap;

    public DocumentGenerationQuotaService(
        DocumentGenerationJobRepository jobRepository,
        PlatformSettingsService platformSettings,
        @Value("${app.documentgen.enabled:true}") boolean defaultEnabled,
        @Value("${app.documentgen.paid-daily-cap:50}") int defaultPaidDailyCap,
        @Value("${app.documentgen.paid-monthly-cap:500}") int defaultPaidMonthlyCap
    ) {
        this.jobRepository = jobRepository;
        this.platformSettings = platformSettings;
        this.defaultEnabled = defaultEnabled;
        this.defaultPaidDailyCap = defaultPaidDailyCap;
        this.defaultPaidMonthlyCap = defaultPaidMonthlyCap;
    }

    public boolean isEnabled() {
        return platformSettings.getBoolean("documentgen.enabled", defaultEnabled);
    }

    /**
     * @throws DocumentGenerationQuotaExceededException with a visitor-facing
     *     reason if this request would exceed the tenant's tier
     */
    public void checkAllowed(TenantPlan plan, String tenantId) {
        // PAID-ONLY comes first, before the enabled flag, on purpose - same
        // ordering VideoGenerationQuotaService uses, for the same reason: a
        // FREE tenant must get the honest "this is a paid feature" answer in
        // every platform state.
        if (plan == TenantPlan.FREE) {
            throw new DocumentGenerationQuotaExceededException(
                DocumentGenerationQuotaExceededException.Reason.PLAN, FREE_PLAN_REJECTION_MESSAGE);
        }
        if (!isEnabled()) {
            throw new DocumentGenerationQuotaExceededException(
                DocumentGenerationQuotaExceededException.Reason.DISABLED,
                "Document generation is turned off on this platform right now.");
        }
        int dailyCap = platformSettings.getInt("documentgen.paid-daily-cap", defaultPaidDailyCap);
        int monthlyCap = platformSettings.getInt("documentgen.paid-monthly-cap", defaultPaidMonthlyCap);

        Instant now = Instant.now();
        long usedToday = jobRepository.countByTenantIdAndCreatedAtAfterAndStatusNot(
            tenantId, now.minus(Duration.ofDays(1)), DocumentGenerationStatus.FAILED);
        if (usedToday >= dailyCap) {
            throw new DocumentGenerationQuotaExceededException(
                "You've reached today's document generation limit (" + dailyCap + "). Try again tomorrow.");
        }
        long usedThisMonth = jobRepository.countByTenantIdAndCreatedAtAfterAndStatusNot(
            tenantId, now.minus(Duration.ofDays(30)), DocumentGenerationStatus.FAILED);
        if (usedThisMonth >= monthlyCap) {
            throw new DocumentGenerationQuotaExceededException(
                "You've reached this month's document generation limit (" + monthlyCap + ").");
        }
    }
}
