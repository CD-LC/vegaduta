package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The Razorpay raw-event-name -> {@link BillingEventType} mapping and the nested
 * {@code payload.<key>.entity.id} extraction every {@code subscription.*},
 * {@code payment_link.paid} and {@code qr_code.credited} delivery shares. An
 * unmapped name must surface as {@link BillingEventType#UNKNOWN} (logged, not
 * silently dropped) and a missing nesting level must yield a null reference id
 * rather than an exception - webhook bodies are untrusted input.
 */
class RazorpayWebhookEventMapperTest {

    @Test
    void everySubscriptionEventMapsToItsLifecycleType() {
        assertEquals(BillingEventType.SUBSCRIPTION_AUTHENTICATED,
            RazorpayWebhookEventMapper.mapEventType("subscription.authenticated"));
        assertEquals(BillingEventType.SUBSCRIPTION_ACTIVATED,
            RazorpayWebhookEventMapper.mapEventType("subscription.activated"));
        assertEquals(BillingEventType.SUBSCRIPTION_CHARGED,
            RazorpayWebhookEventMapper.mapEventType("subscription.charged"));
        assertEquals(BillingEventType.SUBSCRIPTION_PENDING,
            RazorpayWebhookEventMapper.mapEventType("subscription.pending"));
        assertEquals(BillingEventType.SUBSCRIPTION_HALTED,
            RazorpayWebhookEventMapper.mapEventType("subscription.halted"));
        assertEquals(BillingEventType.SUBSCRIPTION_PAUSED,
            RazorpayWebhookEventMapper.mapEventType("subscription.paused"));
        assertEquals(BillingEventType.SUBSCRIPTION_RESUMED,
            RazorpayWebhookEventMapper.mapEventType("subscription.resumed"));
        assertEquals(BillingEventType.SUBSCRIPTION_CANCELLED,
            RazorpayWebhookEventMapper.mapEventType("subscription.cancelled"));
        assertEquals(BillingEventType.SUBSCRIPTION_COMPLETED,
            RazorpayWebhookEventMapper.mapEventType("subscription.completed"));
        assertEquals(BillingEventType.SUBSCRIPTION_UPDATED,
            RazorpayWebhookEventMapper.mapEventType("subscription.updated"));
    }

    @Test
    void upiCollectEventsMapToTheirOwnTypesAndUnknownsFallThrough() {
        assertEquals(BillingEventType.PAYMENT_LINK_PAID,
            RazorpayWebhookEventMapper.mapEventType("payment_link.paid"));
        assertEquals(BillingEventType.QR_CODE_PAYMENT_RECEIVED,
            RazorpayWebhookEventMapper.mapEventType("qr_code.credited"));
        assertEquals(BillingEventType.UNKNOWN,
            RazorpayWebhookEventMapper.mapEventType("payment.captured"));
        assertEquals(BillingEventType.UNKNOWN,
            RazorpayWebhookEventMapper.mapEventType("order.paid"));
        assertEquals(BillingEventType.UNKNOWN,
            RazorpayWebhookEventMapper.mapEventType(""));
    }

    @Test
    void aSubscriptionPayloadYieldsTheNestedEntityId() {
        JSONObject json = new JSONObject(
            "{\"payload\":{\"subscription\":{\"entity\":{\"id\":\"sub_abc123\",\"status\":\"active\"}}}}");
        assertEquals("sub_abc123",
            RazorpayWebhookEventMapper.extractReferenceId(json, "subscription.charged"));
    }

    @Test
    void upiPayloadsReadTheirOwnNestingKey() {
        JSONObject paymentLink = new JSONObject(
            "{\"payload\":{\"payment_link\":{\"entity\":{\"id\":\"plink_1\"}},"
                + "\"payment\":{\"entity\":{\"id\":\"pay_inner\"}}}}");
        // The single reference id is the payment_link id, not the nested payment's.
        assertEquals("plink_1",
            RazorpayWebhookEventMapper.extractReferenceId(paymentLink, "payment_link.paid"));
        JSONObject qr = new JSONObject(
            "{\"payload\":{\"qr_code\":{\"entity\":{\"id\":\"qr_9\"}}}}");
        assertEquals("qr_9", RazorpayWebhookEventMapper.extractReferenceId(qr, "qr_code.credited"));
    }

    @Test
    void unmappedOrIncompletePayloadsYieldANullReferenceId() {
        JSONObject json = new JSONObject(
            "{\"payload\":{\"subscription\":{\"entity\":{\"id\":\"sub_1\"}}}}");
        assertNull(RazorpayWebhookEventMapper.extractReferenceId(json, "payment.captured"),
            "no payload key exists for unmapped event names");
        assertNull(RazorpayWebhookEventMapper.extractReferenceId(new JSONObject("{}"),
            "subscription.charged"));
        assertNull(RazorpayWebhookEventMapper.extractReferenceId(
            new JSONObject("{\"payload\":{}}"), "subscription.charged"));
        assertNull(RazorpayWebhookEventMapper.extractReferenceId(
            new JSONObject("{\"payload\":{\"subscription\":{}}}"), "subscription.charged"));
        assertNull(RazorpayWebhookEventMapper.extractReferenceId(
            new JSONObject("{\"payload\":{\"subscription\":{\"entity\":{}}}}"),
            "subscription.charged"));
    }
}
