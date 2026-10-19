package com.agenticai.core.billing.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * Audit D6 - pure Mockito unit coverage of {@link InvoiceService}: the
 * INR-inclusive 18% GST split, the zero-rated export path, VD-YYYYMM-NNNNN
 * sequencing, and gateway-payment-id idempotency (pre-check and lost-race
 * paths). No Spring context, mocked repositories - same test style as
 * {@link com.agenticai.core.billing.gateway.RazorpayPlanCatalogTest}.
 */
class InvoiceServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-31T10:15:30Z");
    private static final UUID TENANT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private InvoiceRepository invoiceRepository;
    private TenantBillingProfileRepository profileRepository;
    private InvoiceService service;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        profileRepository = mock(TenantBillingProfileRepository.class);
        service = new InvoiceService(invoiceRepository, profileRepository,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        when(invoiceRepository.findByGatewayPaymentId(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByTenantId(any())).thenReturn(Optional.empty());
        when(invoiceRepository.nextInvoiceSequence()).thenReturn(1L);
    }

    // ------------------------------------------------------------------
    // GST split math - INR inclusive 18%
    // ------------------------------------------------------------------

    @Test
    void inrAmountIsTreatedAsGstInclusiveAtEighteenPercent() {
        // Premium monthly Rs.399 = 39900 paise inclusive:
        // base = 39900 * 10000 / 11800 = 33813.55... -> 33814, GST = 6086.
        BillingInvoice invoice = record("pay_A", "INR", 39_900L);

        assertEquals(1800, invoice.gstRateBp());
        assertEquals(6_086L, invoice.gstAmountMinor());
        assertEquals(33_814L, invoice.taxableValueMinor());
        assertEquals(39_900L, invoice.taxableValueMinor() + invoice.gstAmountMinor());
    }

    @Test
    void gstSplitAlwaysReconstructsTheChargedAmountExactly() {
        long[] amounts = {1L, 99L, 100L, 118L, 39_900L, 159_900L, 1_599_900L, Long.MAX_VALUE / 20_000};
        for (long amount : amounts) {
            long gst = InvoiceService.gstPortionOfInclusiveAmount(amount, 1800);
            long base = amount - gst;
            assertEquals(amount, base + gst, "split must reconstruct " + amount);
            // 18% of the backed-out base, re-derived, must round back to the
            // stored GST amount (within the 1-minor-unit rounding envelope).
            assertEquals(gst, Math.round(base * 0.18), 1.0, "gst ~ 18% of base for " + amount);
        }
    }

    @Test
    void zeroRateAndZeroAmountYieldZeroGst() {
        assertEquals(0L, InvoiceService.gstPortionOfInclusiveAmount(39_900L, 0));
        assertEquals(0L, InvoiceService.gstPortionOfInclusiveAmount(0L, 1800));
    }

    @Test
    void usdIsZeroRatedAsLutExportOfServices() {
        BillingInvoice invoice = record("pay_B", "USD", 1_900L);

        assertEquals(0, invoice.gstRateBp());
        assertEquals(0L, invoice.gstAmountMinor());
        assertEquals(1_900L, invoice.taxableValueMinor());
        assertEquals("Outside India (export of services)", invoice.placeOfSupply());
    }

    // ------------------------------------------------------------------
    // Invoice number sequencing
    // ------------------------------------------------------------------

    @Test
    void invoiceNumberIsVdYearMonthAndZeroPaddedSequence() {
        when(invoiceRepository.nextInvoiceSequence()).thenReturn(1L, 2L, 12_345L);

        assertEquals("VD-202607-00001", record("pay_1", "INR", 100L).invoiceNumber());
        assertEquals("VD-202607-00002", record("pay_2", "INR", 100L).invoiceNumber());
        assertEquals("VD-202607-12345", record("pay_3", "INR", 100L).invoiceNumber());
    }

    // ------------------------------------------------------------------
    // Idempotency on gateway payment id
    // ------------------------------------------------------------------

    @Test
    void secondCallWithSamePaymentIdReturnsExistingInvoiceWithoutInserting() {
        BillingInvoice existing = record("pay_dup", "INR", 39_900L);
        when(invoiceRepository.findByGatewayPaymentId("pay_dup")).thenReturn(Optional.of(existing));

        BillingInvoice again = service.recordPaymentInvoice(
            TENANT_ID, "razorpay", "pay_dup", "PREMIUM", "MONTHLY", "INR", 39_900L);

        assertSame(existing, again);
        // Exactly the one insert + one sequence pull from the FIRST call.
        verify(invoiceRepository).insert(any());
        verify(invoiceRepository).nextInvoiceSequence();
    }

    @Test
    void lostInsertRaceFallsBackToTheWinnersRow() {
        BillingInvoice winner = new BillingInvoice(UUID.randomUUID(), TENANT_ID, "VD-202607-00007",
            "razorpay", "pay_race", "PREMIUM", "MONTHLY", "INR", 39_900L, 1800, 6_086L,
            "India (unregistered)", null, null, FIXED_NOW);
        doThrow(new DuplicateKeyException("uq_billing_invoice_gateway_payment"))
            .when(invoiceRepository).insert(any());
        // Pre-check missed it, the post-conflict re-read finds it.
        when(invoiceRepository.findByGatewayPaymentId("pay_race"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winner));

        BillingInvoice result = service.recordPaymentInvoice(
            TENANT_ID, "razorpay", "pay_race", "PREMIUM", "MONTHLY", "INR", 39_900L);

        assertSame(winner, result);
    }

    @Test
    void blankPaymentIdIsRejectedOutright() {
        assertThrows(IllegalArgumentException.class, () -> service.recordPaymentInvoice(
            TENANT_ID, "razorpay", " ", "PREMIUM", "MONTHLY", "INR", 39_900L));
        assertThrows(IllegalArgumentException.class, () -> service.recordPaymentInvoice(
            TENANT_ID, "razorpay", null, "PREMIUM", "MONTHLY", "INR", 39_900L));
    }

    // ------------------------------------------------------------------
    // Customer snapshot + place of supply
    // ------------------------------------------------------------------

    @Test
    void customerGstinAndNameAreSnapshottedAndDrivePlaceOfSupply() {
        when(profileRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(
            new TenantBillingProfileRepository.TenantBillingProfile(
                TENANT_ID, "acme", "Acme", "29AAPFU0939F1ZX", "Acme Industries Pvt Ltd", "Bengaluru")));

        BillingInvoice invoice = record("pay_C", "INR", 39_900L);

        assertEquals("29AAPFU0939F1ZX", invoice.customerGstin());
        assertEquals("Acme Industries Pvt Ltd", invoice.customerName());
        assertEquals("Karnataka", invoice.placeOfSupply());
    }

    @Test
    void unregisteredInrCustomerGetsIndiaUnregisteredPlaceOfSupply() {
        assertEquals("India (unregistered)", record("pay_D", "INR", 100L).placeOfSupply());
    }

    private BillingInvoice record(String paymentId, String currency, long amountMinor) {
        ArgumentCaptor<BillingInvoice> captor = ArgumentCaptor.forClass(BillingInvoice.class);
        BillingInvoice returned = service.recordPaymentInvoice(
            TENANT_ID, "razorpay", paymentId, "PREMIUM", "MONTHLY", currency, amountMinor);
        verify(invoiceRepository, atLeastOnce()).insert(captor.capture());
        // What the service persisted is exactly what it returned.
        assertSame(returned, captor.getValue());
        return returned;
    }
}
