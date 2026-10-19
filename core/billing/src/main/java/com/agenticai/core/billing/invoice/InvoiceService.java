package com.agenticai.core.billing.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * GST-ready invoicing - PRICING-MONETIZATION-AUDIT-2026-07-31 §10 item D6.
 * Writes one immutable {@code control_plane.billing_invoice} row (migration
 * V160) per captured payment.
 *
 * <p><b>GST treatment</b>:
 * <ul>
 *   <li><b>INR</b> - the charged amount is GST-<b>inclusive</b>: the listed
 *       price already contains 18% IGST (1800 bp, the standard rate for
 *       SaaS / OIDAR services), so the taxable value is backed out as
 *       {@code amount * 10000 / (10000 + rateBp)} (half-up) and the GST
 *       amount is the remainder - the two always sum exactly to the charged
 *       amount. IGST (not CGST+SGST) on the default assumption of
 *       inter-state supply; the split can be re-derived from
 *       {@code gst_rate_bp} + place-of-supply later without a schema
 *       change.</li>
 *   <li><b>USD (or any non-INR)</b> - zero-rated: treated as export of
 *       services under a Letter of Undertaking (LUT), so no GST is charged
 *       or backed out ({@code gst_rate_bp = 0}). This assumes the operating
 *       company actually files the LUT; the invoice states the export
 *       wording either way.</li>
 * </ul>
 *
 * <p><b>Invoice numbers</b> are {@code VD-YYYYMM-NNNNN} with {@code NNNNN}
 * drawn from {@code control_plane.billing_invoice_number_seq} - one global
 * monotonic series (unique + consecutive, which is what GST rules require
 * within a financial year), NOT reset monthly; the YYYYMM prefix is issue-
 * month provenance, not a per-month counter namespace.
 *
 * <p><b>Idempotent</b> on {@code gatewayPaymentId}: an application-level
 * pre-check plus V160's partial unique index means webhook redeliveries
 * (Razorpay retries anything that didn't 2xx) produce exactly one invoice -
 * a lost race falls into {@link DuplicateKeyException} and re-reads the
 * winner's row.
 *
 * <p><b>Wiring note</b>: the single call site is {@code
 * RazorpayWebhookService#handleVerifiedEvent} (core/tenancy) - its {@code
 * recordInvoiceForCapturedPayment} helper runs after {@code
 * tenant.confirmUpgrade(...)}/{@code tenantRepository.save} on the pending-
 * upgrade arm and after the live-subscription recovery/renewal arm, for any
 * verified event whose payload carries a captured {@code payment.entity}
 * (see {@code RazorpayGatewayClient#extractCapturedPayment}).
 */
@Service
public class InvoiceService {

    /** 18% IGST in basis points - the standard rate for SaaS/OIDAR services. */
    public static final int DEFAULT_GST_RATE_BP = 1800;

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    private static final DateTimeFormatter YYYYMM =
        DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);

    /** GST state codes -> state/UT name, for place-of-supply derivation from a customer GSTIN. */
    private static final Map<String, String> GST_STATE_CODES = Map.ofEntries(
        Map.entry("01", "Jammu and Kashmir"), Map.entry("02", "Himachal Pradesh"),
        Map.entry("03", "Punjab"), Map.entry("04", "Chandigarh"),
        Map.entry("05", "Uttarakhand"), Map.entry("06", "Haryana"),
        Map.entry("07", "Delhi"), Map.entry("08", "Rajasthan"),
        Map.entry("09", "Uttar Pradesh"), Map.entry("10", "Bihar"),
        Map.entry("11", "Sikkim"), Map.entry("12", "Arunachal Pradesh"),
        Map.entry("13", "Nagaland"), Map.entry("14", "Manipur"),
        Map.entry("15", "Mizoram"), Map.entry("16", "Tripura"),
        Map.entry("17", "Meghalaya"), Map.entry("18", "Assam"),
        Map.entry("19", "West Bengal"), Map.entry("20", "Jharkhand"),
        Map.entry("21", "Odisha"), Map.entry("22", "Chhattisgarh"),
        Map.entry("23", "Madhya Pradesh"), Map.entry("24", "Gujarat"),
        Map.entry("26", "Dadra and Nagar Haveli and Daman and Diu"),
        Map.entry("27", "Maharashtra"), Map.entry("29", "Karnataka"),
        Map.entry("30", "Goa"), Map.entry("31", "Lakshadweep"),
        Map.entry("32", "Kerala"), Map.entry("33", "Tamil Nadu"),
        Map.entry("34", "Puducherry"), Map.entry("35", "Andaman and Nicobar Islands"),
        Map.entry("36", "Telangana"), Map.entry("37", "Andhra Pradesh"),
        Map.entry("38", "Ladakh"), Map.entry("97", "Other Territory")
    );

    private final InvoiceRepository invoiceRepository;
    private final TenantBillingProfileRepository profileRepository;
    private final Clock clock;

    @Autowired
    public InvoiceService(InvoiceRepository invoiceRepository, TenantBillingProfileRepository profileRepository) {
        this(invoiceRepository, profileRepository, Clock.systemUTC());
    }

    /** Test seam - inject a fixed clock for deterministic invoice numbers/timestamps. */
    InvoiceService(InvoiceRepository invoiceRepository, TenantBillingProfileRepository profileRepository, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.profileRepository = profileRepository;
        this.clock = clock;
    }

    /**
     * Records the tax invoice for one captured payment. Idempotent on
     * {@code gatewayPaymentId}: calling again with the same payment id
     * returns the already-issued invoice unchanged (webhook redeliveries
     * must not double-invoice). See the class javadoc for GST treatment.
     *
     * @param tenantId the {@code control_plane.tenant} row's UUID PK (NOT
     *     the schema name) - {@code Tenant#getId()} at the webhook call site
     * @param gateway gateway key, e.g. {@code "razorpay"}
     * @param gatewayPaymentId the gateway's payment/charge id - the
     *     idempotency key; must be non-blank
     * @param plan e.g. {@code "PREMIUM"}/{@code "UNLIMITED"}
     * @param period e.g. {@code "MONTHLY"}/{@code "ANNUAL"}
     * @param currency ISO code, {@code "INR"} or {@code "USD"}
     * @param amountMinor the total charged amount in minor units
     *     (paise/cents), GST-inclusive for INR
     * @return the issued (or previously-issued) invoice row
     */
    public BillingInvoice recordPaymentInvoice(
        UUID tenantId,
        String gateway,
        String gatewayPaymentId,
        String plan,
        String period,
        String currency,
        long amountMinor
    ) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (gatewayPaymentId == null || gatewayPaymentId.isBlank()) {
            throw new IllegalArgumentException("gatewayPaymentId is required - it is the idempotency key");
        }
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amountMinor must be >= 0");
        }

        Optional<BillingInvoice> existing = invoiceRepository.findByGatewayPaymentId(gatewayPaymentId);
        if (existing.isPresent()) {
            return existing.get();
        }

        TenantBillingProfileRepository.TenantBillingProfile profile =
            profileRepository.findByTenantId(tenantId).orElse(null);
        String customerGstin = profile != null ? profile.gstin() : null;
        String customerName = profile != null ? profile.effectiveCustomerName() : null;

        boolean inr = "INR".equalsIgnoreCase(currency);
        int gstRateBp = inr ? DEFAULT_GST_RATE_BP : 0;
        long gstAmountMinor = gstPortionOfInclusiveAmount(amountMinor, gstRateBp);

        BillingInvoice invoice = new BillingInvoice(
            UUID.randomUUID(),
            tenantId,
            nextInvoiceNumber(),
            gateway,
            gatewayPaymentId,
            plan,
            period,
            currency != null ? currency.toUpperCase(java.util.Locale.ROOT) : null,
            amountMinor,
            gstRateBp,
            gstAmountMinor,
            placeOfSupply(inr, customerGstin),
            customerGstin,
            customerName,
            clock.instant()
        );

        try {
            invoiceRepository.insert(invoice);
        } catch (DuplicateKeyException raced) {
            // Two webhook deliveries raced past the pre-check; V160's partial
            // unique index on gateway_payment_id made the loser land here.
            // The winner's row IS this payment's invoice - return it.
            log.info("Invoice insert for payment '{}' lost an idempotency race - returning the existing invoice",
                gatewayPaymentId);
            return invoiceRepository.findByGatewayPaymentId(gatewayPaymentId).orElseThrow(() -> raced);
        }
        log.info("Issued invoice {} for tenant {} ({} {} {}, {} minor units)",
            invoice.invoiceNumber(), tenantId, plan, period, currency, amountMinor);
        return invoice;
    }

    /**
     * The GST portion of a GST-INCLUSIVE total: {@code total - base} where
     * {@code base = total * 10000 / (10000 + rateBp)} rounded half-up, so
     * base + GST always reconstructs the charged amount exactly. Rate 0
     * (exports) yields 0.
     */
    static long gstPortionOfInclusiveAmount(long totalMinor, int rateBp) {
        if (rateBp <= 0 || totalMinor <= 0) {
            return 0L;
        }
        long baseMinor = BigDecimal.valueOf(totalMinor)
            .multiply(BigDecimal.valueOf(10_000))
            .divide(BigDecimal.valueOf(10_000L + rateBp), 0, RoundingMode.HALF_UP)
            .longValueExact();
        return totalMinor - baseMinor;
    }

    private String nextInvoiceNumber() {
        return String.format("VD-%s-%05d", YYYYMM.format(clock.instant()), invoiceRepository.nextInvoiceSequence());
    }

    /**
     * Place of supply: the GSTIN's state when the customer is registered;
     * otherwise India-unregistered for INR, outside-India for exports.
     */
    static String placeOfSupply(boolean inr, String customerGstin) {
        if (customerGstin != null && customerGstin.length() >= 2) {
            String state = GST_STATE_CODES.get(customerGstin.substring(0, 2));
            if (state != null) {
                return state;
            }
        }
        return inr ? "India (unregistered)" : "Outside India (export of services)";
    }
}
