package com.agenticai.core.billing.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 * B9-PR4 revenue-path contract - the edges of {@link InvoiceService} that
 * {@link InvoiceServiceTest} does not reach. That suite already pins the
 * GST split, VD-numbering, the pre-check idempotency hit, and the
 * lost-race-then-reread-wins path; this one covers the remaining honest
 * failure modes: the race where the winner's row never materializes (the
 * pathological double-failure - must surface loudly, not invent an
 * invoice), input validation, and currency-case normalization (a
 * lowercase "inr" from a sloppy caller must still be taxed as INR, not
 * silently zero-rated as an "export").
 */
class InvoiceServiceRaceAndValidationTest {

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

    @Test
    void duplicateKeyWithNoReadableWinnerRethrowsTheOriginalConflict() {
        // The unique index said "someone else already invoiced this payment"
        // but the re-read finds nothing (e.g. the winner's transaction rolled
        // back, or replica lag on a split read path). Fabricating success
        // here would report an invoice number that doesn't exist - the only
        // honest move is to surface the original conflict so the webhook
        // redelivery retries the whole thing.
        DuplicateKeyException conflict = new DuplicateKeyException("uq_billing_invoice_gateway_payment");
        doThrow(conflict).when(invoiceRepository).insert(any());
        when(invoiceRepository.findByGatewayPaymentId("pay_ghost")).thenReturn(Optional.empty());

        DuplicateKeyException thrown = assertThrows(DuplicateKeyException.class,
            () -> service.recordPaymentInvoice(
                TENANT_ID, "razorpay", "pay_ghost", "PREMIUM", "MONTHLY", "INR", 39_900L));

        assertSame(conflict, thrown, "must rethrow the ORIGINAL exception, not wrap or replace it");
    }

    @Test
    void nullTenantAndNegativeAmountAreRejectedBeforeAnySideEffect() {
        assertThrows(IllegalArgumentException.class, () -> service.recordPaymentInvoice(
            null, "razorpay", "pay_X", "PREMIUM", "MONTHLY", "INR", 100L));
        assertThrows(IllegalArgumentException.class, () -> service.recordPaymentInvoice(
            TENANT_ID, "razorpay", "pay_X", "PREMIUM", "MONTHLY", "INR", -1L));
        // Neither may have consumed an invoice number - gaps in the GST
        // series are exactly what the single monotonic sequence exists to
        // prevent.
        verify(invoiceRepository, org.mockito.Mockito.never()).nextInvoiceSequence();
        verify(invoiceRepository, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void lowercaseInrIsNormalizedAndStillTaxedAsInr() {
        // "INR".equalsIgnoreCase drives the GST decision, and the stored
        // currency is uppercased - a lowercase caller must not accidentally
        // produce a zero-rated "export" of a domestic sale.
        ArgumentCaptor<BillingInvoice> captor = ArgumentCaptor.forClass(BillingInvoice.class);

        service.recordPaymentInvoice(TENANT_ID, "razorpay", "pay_lc", "PREMIUM", "MONTHLY", "inr", 39_900L);

        verify(invoiceRepository).insert(captor.capture());
        assertEquals("INR", captor.getValue().currency());
        assertEquals(1800, captor.getValue().gstRateBp());
        assertEquals(6_086L, captor.getValue().gstAmountMinor());
    }

    @Test
    void gstinWithAnUnmappedStateCodeFallsBackToUnregisteredPlaceOfSupply() {
        // "25" was Daman & Diu before the 2020 merger into code 26 - a
        // stale/garbage state code must degrade to the generic domestic
        // place of supply, never to a wrong named state.
        assertEquals("India (unregistered)", InvoiceService.placeOfSupply(true, "25AAPFU0939F1ZV"));
        assertEquals("India (unregistered)", InvoiceService.placeOfSupply(true, "99XXXXXXXXXXXXX"));
        // Too-short GSTIN can't even yield a state code.
        assertEquals("India (unregistered)", InvoiceService.placeOfSupply(true, "2"));
        // Export fallback is independent of the (absent) GSTIN.
        assertEquals("Outside India (export of services)", InvoiceService.placeOfSupply(false, null));
    }

    @Test
    void gstPortionIsZeroForNonPositiveInputsAndNeverNegative() {
        assertEquals(0L, InvoiceService.gstPortionOfInclusiveAmount(-500L, 1800));
        assertEquals(0L, InvoiceService.gstPortionOfInclusiveAmount(100L, -50));
        // 1 paisa inclusive: base rounds to 1, GST 0 - the split still
        // reconstructs the total without going negative.
        assertEquals(0L, InvoiceService.gstPortionOfInclusiveAmount(1L, 1800));
    }
}
