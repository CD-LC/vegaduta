package com.agenticai.core.billing.invoice;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-facing GST invoicing surface (audit D6):
 * <ul>
 *   <li>{@code GET /api/tenant/invoices} - this tenant's invoices, newest
 *       first</li>
 *   <li>{@code GET /api/tenant/invoices/{id}/html} - one invoice as a
 *       self-contained printable HTML tax invoice</li>
 *   <li>{@code GET/PUT /api/tenant/billing-profile} - the GSTIN /
 *       billing-name / billing-address block snapshotted onto future
 *       invoices; PUT rejects a structurally-or-checksum-invalid GSTIN with
 *       400</li>
 * </ul>
 *
 * <p>Tenant scoping mirrors {@code TenantSettingsController}: identity
 * comes exclusively from {@link TenantContext} (never a path/query param),
 * and SecurityConfig gates all four routes to {@code ROLE_tenant-admin} -
 * invoices are billing-sensitive, same trust tier as
 * {@code POST /api/tenant/upgrade}.
 */
@RestController
@Profile("security")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class InvoiceController {

    /** List-row shape for {@code GET /api/tenant/invoices}. */
    public record InvoiceSummaryResponse(
        UUID id,
        String invoiceNumber,
        String gateway,
        String plan,
        String period,
        String currency,
        long amountMinor,
        int gstRateBp,
        long gstAmountMinor,
        Instant issuedAt
    ) {
        static InvoiceSummaryResponse from(BillingInvoice invoice) {
            return new InvoiceSummaryResponse(
                invoice.id(), invoice.invoiceNumber(), invoice.gateway(), invoice.plan(),
                invoice.period(), invoice.currency(), invoice.amountMinor(), invoice.gstRateBp(),
                invoice.gstAmountMinor(), invoice.issuedAt());
        }
    }

    /** Request/response body for {@code /api/tenant/billing-profile}. All fields optional; blank clears. */
    public record BillingProfileBody(String gstin, String billingName, String billingAddress) {
    }

    private final InvoiceRepository invoiceRepository;
    private final TenantBillingProfileRepository profileRepository;
    private final InvoiceHtmlRenderer htmlRenderer;

    public InvoiceController(
        InvoiceRepository invoiceRepository,
        TenantBillingProfileRepository profileRepository,
        InvoiceHtmlRenderer htmlRenderer
    ) {
        this.invoiceRepository = invoiceRepository;
        this.profileRepository = profileRepository;
        this.htmlRenderer = htmlRenderer;
    }

    @GetMapping("/api/tenant/invoices")
    public List<InvoiceSummaryResponse> list() {
        UUID tenantId = currentTenantUuid();
        return invoiceRepository.listByTenantId(tenantId).stream()
            .map(InvoiceSummaryResponse::from)
            .toList();
    }

    @GetMapping(value = "/api/tenant/invoices/{id}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> html(@PathVariable("id") UUID id) {
        UUID tenantId = currentTenantUuid();
        BillingInvoice invoice = invoiceRepository.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice for this tenant"));
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .body(htmlRenderer.render(invoice));
    }

    @GetMapping("/api/tenant/billing-profile")
    public BillingProfileBody getProfile() {
        TenantBillingProfileRepository.TenantBillingProfile profile = currentProfile();
        return new BillingProfileBody(profile.gstin(), profile.billingName(), profile.billingAddress());
    }

    @PutMapping("/api/tenant/billing-profile")
    public BillingProfileBody updateProfile(@RequestBody BillingProfileBody request) {
        String gstin = request.gstin() != null ? request.gstin().trim().toUpperCase(java.util.Locale.ROOT) : null;
        if (gstin != null && !gstin.isBlank() && !GstinValidator.isValid(gstin)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid GSTIN - expected 15 characters (2-digit state code + PAN + entity code + 'Z' + checksum)");
        }
        TenantBillingProfileRepository.TenantBillingProfile profile = currentProfile();
        profileRepository.updateProfile(profile.tenantId(), gstin, request.billingName(), request.billingAddress());
        return getProfile();
    }

    private TenantBillingProfileRepository.TenantBillingProfile currentProfile() {
        String schemaName = TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated /api/tenant request - SecurityConfig should have blocked this"));
        return profileRepository.findBySchemaName(schemaName)
            .orElseThrow(() -> new IllegalStateException("No Tenant row for schema '" + schemaName + "'"));
    }

    private UUID currentTenantUuid() {
        return currentProfile().tenantId();
    }
}
