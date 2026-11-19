package com.agenticai.core.billing.invoice;

import com.agenticai.core.billing.invoice.InvoiceController.BillingProfileBody;
import com.agenticai.core.billing.invoice.InvoiceController.InvoiceSummaryResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Service facade for {@link InvoiceController}: wraps the invoice and billing-profile repositories
 * and the HTML renderer so the controller never touches a repository. Tenant identity comes
 * exclusively from {@link TenantContext} (never a path/query param).
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class InvoiceApiService {

    private final InvoiceRepository invoiceRepository;
    private final TenantBillingProfileRepository profileRepository;
    private final InvoiceHtmlRenderer htmlRenderer;

    public InvoiceApiService(
            InvoiceRepository invoiceRepository,
            TenantBillingProfileRepository profileRepository,
            InvoiceHtmlRenderer htmlRenderer) {
        this.invoiceRepository = invoiceRepository;
        this.profileRepository = profileRepository;
        this.htmlRenderer = htmlRenderer;
    }

    public List<InvoiceSummaryResponse> list() {
        UUID tenantId = currentProfile().tenantId();
        return invoiceRepository.listByTenantId(tenantId).stream()
                .map(InvoiceSummaryResponse::from)
                .toList();
    }

    /** The rendered printable invoice, or empty when no such invoice exists for this tenant. */
    public Optional<String> renderHtml(UUID id) {
        UUID tenantId = currentProfile().tenantId();
        return invoiceRepository.findByIdAndTenantId(id, tenantId).map(htmlRenderer::render);
    }

    public BillingProfileBody getProfile() {
        TenantBillingProfileRepository.TenantBillingProfile profile = currentProfile();
        return new BillingProfileBody(
                profile.gstin(), profile.billingName(), profile.billingAddress());
    }

    /** {@code gstin} is already normalized and validated by the caller. */
    public BillingProfileBody updateProfile(
            String gstin, String billingName, String billingAddress) {
        TenantBillingProfileRepository.TenantBillingProfile profile = currentProfile();
        profileRepository.updateProfile(profile.tenantId(), gstin, billingName, billingAddress);
        return getProfile();
    }

    private TenantBillingProfileRepository.TenantBillingProfile currentProfile() {
        String schemaName =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No TenantContext for an authenticated /api/tenant request - SecurityConfig should have blocked this"));
        return profileRepository
                .findBySchemaName(schemaName)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No Tenant row for schema '" + schemaName + "'"));
    }
}
