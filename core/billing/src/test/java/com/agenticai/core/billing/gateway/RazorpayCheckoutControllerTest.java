package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Plain-unit-test convention (no MockMvc/web layer - see {@code
 * CompGrantControllerTest}'s javadoc for why that's the established pattern
 * in this codebase): the controller is constructed directly with a mocked
 * {@link RazorpayGatewayClient}, and {@link TenantContext} is set/cleared
 * around each test the same way {@code TenantSettingsControllerTest} does.
 *
 * <p>Covers the two new UPI creation endpoints (create-upi-collect,
 * create-upi-qr) and the new close-upi-qr endpoint - amount validation,
 * tenant-context requirement, gateway-failure translation to a 500, and the
 * happy path delegating to {@link RazorpayGatewayClient} and mapping its
 * response correctly.
 */
class RazorpayCheckoutControllerTest {

    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";

    private RazorpayGatewayClient razorpayClient;
    private RazorpayCheckoutController controller;

    @BeforeEach
    void setUp() {
        razorpayClient = mock(RazorpayGatewayClient.class);
        controller = new RazorpayCheckoutController(razorpayClient);
        TenantContext.set(TenantContext.of(TENANT_ID, "user@acme.example", Set.of("tenant-admin")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // --- createUpiCollect ---------------------------------------------------

    @Test
    void createUpiCollectRejectsAmountBelowMinimum() {
        var request = new RazorpayCheckoutController.CreateUpiCollectRequest(
            99, "too small", null, null, null
        );

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.createUpiCollect(request)
        );
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(razorpayClient, never()).createUpiCollectRequest(
            anyString(), anyLong(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void createUpiCollectRequiresTenantContext() {
        TenantContext.clear();
        var request = new RazorpayCheckoutController.CreateUpiCollectRequest(
            10000, "desc", null, null, null
        );

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.createUpiCollect(request)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
    }

    @Test
    void createUpiCollectTranslatesGatewayFailureTo500() {
        when(razorpayClient.createUpiCollectRequest(
            eq(TENANT_ID), eq(10000L), any(), any(), any(), any(), anyMap()
        )).thenThrow(new PaymentGatewayException("Razorpay unreachable", null));

        var request = new RazorpayCheckoutController.CreateUpiCollectRequest(
            10000, "desc", null, null, null
        );

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.createUpiCollect(request)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
    }

    @Test
    void createUpiCollectHappyPathDelegatesAndMapsResponse() {
        when(razorpayClient.createUpiCollectRequest(
            eq(TENANT_ID), eq(10000L), eq("desc"), eq("Jane"), eq("9999999999"), eq("jane@acme.example"), anyMap()
        )).thenReturn(new GatewayUpiCollectRequest("plink_abc123", "https://rzp.io/i/abc123", "created"));

        var request = new RazorpayCheckoutController.CreateUpiCollectRequest(
            10000, "desc", "Jane", "9999999999", "jane@acme.example"
        );

        RazorpayCheckoutController.CreateUpiCollectResponse response = controller.createUpiCollect(request);

        assertEquals("plink_abc123", response.id());
        assertEquals("https://rzp.io/i/abc123", response.shortUrl());
        assertEquals("created", response.status());
    }

    // --- createUpiQr ---------------------------------------------------------

    @Test
    void createUpiQrRejectsAmountBelowMinimum() {
        var request = new RazorpayCheckoutController.CreateUpiQrRequest(50, "name", "desc");

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.createUpiQr(request)
        );
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(razorpayClient, never()).createUpiQrCode(anyString(), anyLong(), any(), any(), anyMap());
    }

    @Test
    void createUpiQrRequiresTenantContext() {
        TenantContext.clear();
        var request = new RazorpayCheckoutController.CreateUpiQrRequest(10000, "name", "desc");

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.createUpiQr(request)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
    }

    @Test
    void createUpiQrTranslatesGatewayFailureTo500() {
        when(razorpayClient.createUpiQrCode(eq(TENANT_ID), eq(10000L), any(), any(), anyMap()))
            .thenThrow(new PaymentGatewayException("Razorpay unreachable", null));

        var request = new RazorpayCheckoutController.CreateUpiQrRequest(10000, "name", "desc");

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.createUpiQr(request)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
    }

    @Test
    void createUpiQrHappyPathDelegatesAndMapsResponse() {
        when(razorpayClient.createUpiQrCode(eq(TENANT_ID), eq(10000L), eq("name"), eq("desc"), anyMap()))
            .thenReturn(new GatewayUpiQrCode("qr_abc123", "https://rzp.io/qr/abc123.png", "active", 10000));

        var request = new RazorpayCheckoutController.CreateUpiQrRequest(10000, "name", "desc");

        RazorpayCheckoutController.CreateUpiQrResponse response = controller.createUpiQr(request);

        assertEquals("qr_abc123", response.id());
        assertEquals("https://rzp.io/qr/abc123.png", response.imageUrl());
        assertEquals("active", response.status());
        assertEquals(10000, response.amountInPaise());
    }

    // --- closeUpiQr ----------------------------------------------------------

    @Test
    void closeUpiQrRejectsBlankId() {
        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.closeUpiQr("  ")
        );
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(razorpayClient, never()).closeUpiQrCode(anyString());
    }

    @Test
    void closeUpiQrRequiresTenantContext() {
        TenantContext.clear();

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.closeUpiQr("qr_abc123")
        );
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        verify(razorpayClient, never()).closeUpiQrCode(anyString());
    }

    @Test
    void closeUpiQrTranslatesGatewayFailureTo500() {
        when(razorpayClient.fetchUpiQrCode("qr_abc123")).thenReturn(
            new GatewayQrCodeDetails("qr_abc123", "active", java.util.Map.of("tenant_id", TENANT_ID))
        );
        com.agenticai.core.billing.gateway.PaymentGatewayException failure =
            new PaymentGatewayException("already closed", null);
        org.mockito.Mockito.doThrow(failure).when(razorpayClient).closeUpiQrCode("qr_abc123");

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.closeUpiQr("qr_abc123")
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
    }

    @Test
    void closeUpiQrFetchFailureTranslatesTo500() {
        when(razorpayClient.fetchUpiQrCode("qr_abc123"))
            .thenThrow(new PaymentGatewayException("Razorpay unreachable", null));

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.closeUpiQr("qr_abc123")
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
        verify(razorpayClient, never()).closeUpiQrCode(anyString());
    }

    @Test
    void closeUpiQrSameTenantSucceeds() {
        when(razorpayClient.fetchUpiQrCode("qr_abc123")).thenReturn(
            new GatewayQrCodeDetails("qr_abc123", "active", java.util.Map.of("tenant_id", TENANT_ID))
        );

        RazorpayCheckoutController.CloseUpiQrResponse response = controller.closeUpiQr("qr_abc123");

        verify(razorpayClient).closeUpiQrCode("qr_abc123");
        assertEquals("qr_abc123", response.id());
        assertEquals("closed", response.status());
    }

    @Test
    void closeUpiQrCrossTenantRejectedWith403() {
        String otherTenantId = "22222222-2222-2222-2222-222222222222";
        when(razorpayClient.fetchUpiQrCode("qr_abc123")).thenReturn(
            new GatewayQrCodeDetails("qr_abc123", "active", java.util.Map.of("tenant_id", otherTenantId))
        );

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.closeUpiQr("qr_abc123")
        );
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(razorpayClient, never()).closeUpiQrCode(anyString());
    }

    @Test
    void closeUpiQrMissingTenantNoteRejectedWith403() {
        when(razorpayClient.fetchUpiQrCode("qr_abc123")).thenReturn(
            new GatewayQrCodeDetails("qr_abc123", "active", java.util.Map.of())
        );

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.closeUpiQr("qr_abc123")
        );
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(razorpayClient, never()).closeUpiQrCode(anyString());
    }
}
