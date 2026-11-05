package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.razorpay.Order;
import com.razorpay.OrderClient;
import com.razorpay.PaymentLink;
import com.razorpay.PaymentLinkClient;
import com.razorpay.QrCode;
import com.razorpay.QrCodeClient;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Subscription;
import com.razorpay.SubscriptionClient;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The network-facing methods of {@link RazorpayGatewayClient}, run against a mocked Razorpay SDK
 * client injected into the lazily-built {@code client} field, so the request bodies this class
 * sends and the way it maps responses and SDK failures are pinned without a live account.
 */
class RazorpayGatewayClientApiCallsTest {

    private RazorpayGatewayClient gateway;
    private SubscriptionClient subscriptions;
    private OrderClient orders;
    private PaymentLinkClient paymentLinks;
    private QrCodeClient qrCodes;

    @BeforeEach
    void injectMockSdk() throws Exception {
        gateway = new RazorpayGatewayClient("rzp_test_id", "secret", "whsec");
        RazorpayClient sdk = mock(RazorpayClient.class);
        subscriptions = mock(SubscriptionClient.class);
        orders = mock(OrderClient.class);
        paymentLinks = mock(PaymentLinkClient.class);
        qrCodes = mock(QrCodeClient.class);
        setField(RazorpayClient.class, sdk, "subscriptions", subscriptions);
        setField(RazorpayClient.class, sdk, "orders", orders);
        setField(RazorpayClient.class, sdk, "paymentLink", paymentLinks);
        setField(RazorpayClient.class, sdk, "qrCode", qrCodes);
        setField(RazorpayGatewayClient.class, gateway, "client", sdk);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value)
            throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // --- subscriptions ----------------------------------------------------

    @Test
    void createSubscriptionWithTrialSendsPlanTrialStartAndNotes() throws Exception {
        when(subscriptions.create(any(JSONObject.class)))
                .thenReturn(
                        new Subscription(
                                new JSONObject()
                                        .put("id", "sub_1")
                                        .put("short_url", "https://rzp.io/s")
                                        .put("status", "created")));

        GatewaySubscription result =
                gateway.createSubscriptionWithTrial(
                        "t1", "plan_x", 14, "a@b.co", "Ann", "  offer_9 ");

        assertEquals("sub_1", result.id());
        ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
        verify(subscriptions).create(body.capture());
        JSONObject sent = body.getValue();
        assertEquals("plan_x", sent.getString("plan_id"));
        assertEquals(120, sent.getInt("total_count"));
        assertEquals("offer_9", sent.getString("offer_id"));
        long now = Instant.now().getEpochSecond();
        assertTrue(sent.getLong("start_at") > now + 13 * 86400L);
        assertTrue(sent.getLong("expire_by") > now + 47 * 3600L);
        assertEquals("t1", sent.getJSONObject("notes").getString("agentic_ai_tenant_id"));
        assertEquals("Ann", sent.getJSONObject("notes").getString("agentic_ai_customer_name"));
    }

    @Test
    void createSubscriptionWithoutOfferOmitsOfferId() throws Exception {
        when(subscriptions.create(any(JSONObject.class)))
                .thenReturn(new Subscription(new JSONObject().put("id", "s")));
        gateway.createSubscriptionWithTrial("t", "p", 1, "e", "n");
        ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
        verify(subscriptions).create(body.capture());
        assertFalse(body.getValue().has("offer_id"));
    }

    @Test
    void createSubscriptionWrapsSdkFailure() throws Exception {
        when(subscriptions.create(any(JSONObject.class))).thenThrow(new RazorpayException("boom"));
        PaymentGatewayException e =
                assertThrows(
                        PaymentGatewayException.class,
                        () -> gateway.createSubscriptionWithTrial("t", "p", 1, "e", "n"));
        assertTrue(e.getMessage().contains("boom"));
    }

    @Test
    void cancelSubscriptionPassesCycleEndFlagAndReturnsStatus() throws Exception {
        when(subscriptions.cancel(eq("sub_1"), any(JSONObject.class)))
                .thenReturn(new Subscription(new JSONObject().put("status", "cancelled")));
        assertEquals("cancelled", gateway.cancelSubscription("sub_1", true));
        ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
        verify(subscriptions).cancel(eq("sub_1"), body.capture());
        assertEquals(1, body.getValue().getInt("cancel_at_cycle_end"));
    }

    @Test
    void cancelSubscriptionImmediatelySendsZero() throws Exception {
        when(subscriptions.cancel(eq("s"), any(JSONObject.class)))
                .thenReturn(new Subscription(new JSONObject().put("status", "cancelled")));
        gateway.cancelSubscription("s", false);
        ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
        verify(subscriptions).cancel(eq("s"), body.capture());
        assertEquals(0, body.getValue().getInt("cancel_at_cycle_end"));
    }

    @Test
    void cancelSubscriptionWrapsSdkFailure() throws Exception {
        when(subscriptions.cancel(any(), any())).thenThrow(new RazorpayException("nope"));
        assertThrows(PaymentGatewayException.class, () -> gateway.cancelSubscription("s", true));
    }

    @Test
    void overageAddonSendsItemAndQuantity() throws Exception {
        gateway.createOverageAddon("sub_1", "Extra tokens", 5000, "INR");
        ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
        verify(subscriptions).createAddon(eq("sub_1"), body.capture());
        JSONObject item = body.getValue().getJSONObject("item");
        assertEquals("Extra tokens", item.getString("name"));
        assertEquals(5000, item.getLong("amount"));
        assertEquals("INR", item.getString("currency"));
        assertEquals(1, body.getValue().getInt("quantity"));
    }

    @Test
    void overageAddonWrapsSdkFailure() throws Exception {
        when(subscriptions.createAddon(any(), any())).thenThrow(new RazorpayException("x"));
        assertThrows(
                PaymentGatewayException.class,
                () -> gateway.createOverageAddon("s", "d", 1, "INR"));
    }

    // --- orders -----------------------------------------------------------

    @Test
    void topUpOrderCarriesAmountReceiptAndTenantNote() throws Exception {
        when(orders.create(any(JSONObject.class)))
                .thenReturn(
                        new Order(new JSONObject().put("id", "order_1").put("status", "created")));
        GatewayOrder order =
                gateway.createTopUpOrder("t9", 10000, "INR", "rcpt-1", Map.of("k", "v"));
        assertEquals("order_1", order.orderId());
        ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
        verify(orders).create(body.capture());
        assertEquals("rcpt-1", body.getValue().getString("receipt"));
        assertEquals(1, body.getValue().getInt("payment_capture"));
        assertEquals(
                "t9", body.getValue().getJSONObject("notes").getString("agentic_ai_tenant_id"));
        assertEquals("v", body.getValue().getJSONObject("notes").getString("k"));
    }

    @Test
    void topUpOrderWithoutReceiptOrNotesOmitsReceipt() throws Exception {
        when(orders.create(any(JSONObject.class)))
                .thenReturn(new Order(new JSONObject().put("id", "o").put("status", "created")));
        gateway.createTopUpOrder("t", 1, "USD", null, null);
        ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
        verify(orders).create(body.capture());
        assertFalse(body.getValue().has("receipt"));
    }

    @Test
    void topUpOrderWrapsSdkFailure() throws Exception {
        when(orders.create(any())).thenThrow(new RazorpayException("down"));
        assertThrows(
                PaymentGatewayException.class,
                () -> gateway.createTopUpOrder("t", 1, "INR", null, null));
    }

    // --- UPI payment link -------------------------------------------------

    @Test
    void upiCollectRequestIsInrUpiLinkWithOptionalCustomerAndExpiry() throws Exception {
        when(paymentLinks.create(any(JSONObject.class)))
                .thenReturn(
                        new PaymentLink(
                                new JSONObject()
                                        .put("id", "plink_1")
                                        .put("short_url", "https://rzp.io/l")
                                        .put("status", "created")));
        Instant expiry = Instant.ofEpochSecond(2_000_000_000L);
        GatewayUpiCollectRequest r =
                gateway.createUpiCollectRequest(
                        "t", 4200, "desc", "Ann", "+9199", "a@b.co", Map.of("n", "1"), expiry);
        assertEquals("plink_1", r.id());
        ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
        verify(paymentLinks).create(body.capture());
        JSONObject sent = body.getValue();
        assertEquals("INR", sent.getString("currency"));
        assertTrue(sent.getBoolean("upi_link"));
        assertEquals(2_000_000_000L, sent.getLong("expire_by"));
        assertEquals("Ann", sent.getJSONObject("customer").getString("name"));
        assertEquals("+9199", sent.getJSONObject("customer").getString("contact"));
        assertEquals("1", sent.getJSONObject("notes").getString("n"));
    }

    @Test
    void upiCollectRequestWithoutCustomerOrExpiryOmitsThem() throws Exception {
        when(paymentLinks.create(any(JSONObject.class)))
                .thenReturn(new PaymentLink(new JSONObject().put("id", "p")));
        gateway.createUpiCollectRequest("t", 100, null, null, null, null, null);
        ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
        verify(paymentLinks).create(body.capture());
        assertFalse(body.getValue().has("customer"));
        assertFalse(body.getValue().has("expire_by"));
        assertFalse(body.getValue().has("description"));
    }

    @Test
    void upiCollectRequestWrapsSdkFailure() throws Exception {
        when(paymentLinks.create(any())).thenThrow(new RazorpayException("x"));
        assertThrows(
                PaymentGatewayException.class,
                () -> gateway.createUpiCollectRequest("t", 1, null, null, null, null, null));
    }

    // --- UPI QR -----------------------------------------------------------

    @Test
    void upiQrCodeIsSingleUseFixedAmountWithCloseBy() throws Exception {
        when(qrCodes.create(any(JSONObject.class)))
                .thenReturn(
                        new QrCode(
                                new JSONObject()
                                        .put("id", "qr_1")
                                        .put("image_url", "https://img")
                                        .put("status", "active")));
        GatewayUpiQrCode qr = gateway.createUpiQrCode("t", 7700, "n", "d", Map.of("a", "b"));
        assertEquals("qr_1", qr.id());
        ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
        verify(qrCodes).create(body.capture());
        JSONObject sent = body.getValue();
        assertEquals("upi_qr", sent.getString("type"));
        assertEquals("single_use", sent.getString("usage"));
        assertEquals(7700, sent.getLong("payment_amount"));
        assertTrue(sent.getLong("close_by") > Instant.now().getEpochSecond());
        assertEquals("b", sent.getJSONObject("notes").getString("a"));
    }

    @Test
    void upiQrCodeWrapsSdkFailure() throws Exception {
        when(qrCodes.create(any())).thenThrow(new RazorpayException("x"));
        assertThrows(
                PaymentGatewayException.class,
                () -> gateway.createUpiQrCode("t", 1, null, null, null));
    }

    @Test
    void closeUpiQrCodeDelegatesAndWrapsFailure() throws Exception {
        gateway.closeUpiQrCode("qr_1");
        verify(qrCodes).close("qr_1");
        when(qrCodes.close("qr_2")).thenThrow(new RazorpayException("x"));
        assertThrows(PaymentGatewayException.class, () -> gateway.closeUpiQrCode("qr_2"));
    }

    @Test
    void fetchUpiQrCodeReturnsStatusAndNotes() throws Exception {
        when(qrCodes.fetch("qr_1"))
                .thenReturn(
                        new QrCode(
                                new JSONObject()
                                        .put("id", "qr_1")
                                        .put("status", "closed")
                                        .put(
                                                "notes",
                                                new JSONObject()
                                                        .put("agentic_ai_tenant_id", "t"))));
        GatewayQrCodeDetails d = gateway.fetchUpiQrCode("qr_1");
        assertEquals("closed", d.status());
        assertEquals("t", d.notes().get("agentic_ai_tenant_id"));
    }

    @Test
    void fetchUpiQrCodeWithoutNotesYieldsEmptyMap() throws Exception {
        when(qrCodes.fetch("q")).thenReturn(new QrCode(new JSONObject().put("id", "q")));
        GatewayQrCodeDetails d = gateway.fetchUpiQrCode("q");
        assertNotNull(d.notes());
        assertTrue(d.notes().isEmpty());
    }

    @Test
    void fetchUpiQrCodeWrapsSdkFailure() throws Exception {
        when(qrCodes.fetch(any())).thenThrow(new RazorpayException("x"));
        assertThrows(PaymentGatewayException.class, () -> gateway.fetchUpiQrCode("q"));
    }

    // --- configuration guard ----------------------------------------------

    @Test
    void unconfiguredGatewayFailsWithActionableMessageOnFirstCall() {
        RazorpayGatewayClient blank = new RazorpayGatewayClient("", "", "");
        assertFalse(blank.isConfigured());
        PaymentGatewayException e =
                assertThrows(PaymentGatewayException.class, () -> blank.closeUpiQrCode("q"));
        assertTrue(e.getMessage().contains("RAZORPAY_KEY_ID"));
    }
}
