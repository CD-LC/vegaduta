package com.agenticai.core.billing.gateway;

import org.json.JSONObject;

/**
 * Maps a Razorpay webhook body onto this app's gateway-neutral event vocabulary: the {@link
 * BillingEventType} for a raw event name and the one reference id the event is about. Split out of
 * {@link RazorpayGatewayClient} so the client keeps the outbound API calls and this class keeps the
 * inbound payload-shape knowledge.
 */
final class RazorpayWebhookEventMapper {

    private RazorpayWebhookEventMapper() {}

    // Every subscription.* webhook payload's shape (per Billing Build Plan
    // Stage 1's confirmed sample payloads):
    // { "payload": { "subscription": { "entity": { "id": "sub_...", ... } } } }
    //
    // UPI-collect follow-up: payment_link.paid and qr_code.credited nest
    // their entity the exact same way under a different payload key -
    // confirmed against razorpay.com/docs/webhooks/payloads/payment-links/
    // ({ "payload": { "payment_link": { "entity": {...} }, "payment": {...},
    // "order": {...} } }, contains: ["payment_link","order","payment"]) and
    // razorpay.com/docs/webhooks/payloads/qr-codes/ ({ "payload": {
    // "payment": {...}, "qr_code": { "entity": {...} } } }). This method
    // still returns a single reference id (the payment_link/qr_code id, not
    // the nested payment id) - same "one id identifying which gateway
    // object this event is about" contract BillingEvent#gatewaySubscriptionId
    // already had, and the same precedent PhonePeGatewayClient's own
    // parseWebhookEvent set by reusing that field for an order id too (see
    // its javadoc) rather than this app growing a second, gateway-specific
    // event vocabulary.
    static String extractReferenceId(JSONObject json, String rawEventName) {
        String payloadKey = payloadKeyFor(rawEventName);
        if (payloadKey == null) {
            return null;
        }
        JSONObject payload = json.optJSONObject("payload");
        if (payload == null) {
            return null;
        }
        JSONObject entityWrapper = payload.optJSONObject(payloadKey);
        if (entityWrapper == null) {
            return null;
        }
        JSONObject entity = entityWrapper.optJSONObject("entity");
        return entity == null ? null : entity.optString("id", null);
    }

    private static String payloadKeyFor(String rawEventName) {
        if (rawEventName.startsWith("subscription.")) {
            return "subscription";
        }
        if ("payment_link.paid".equals(rawEventName)) {
            return "payment_link";
        }
        if ("qr_code.credited".equals(rawEventName)) {
            return "qr_code";
        }
        return null;
    }

    static BillingEventType mapEventType(String rawEventName) {
        return switch (rawEventName) {
            case "subscription.authenticated" -> BillingEventType.SUBSCRIPTION_AUTHENTICATED;
            case "subscription.activated" -> BillingEventType.SUBSCRIPTION_ACTIVATED;
            case "subscription.charged" -> BillingEventType.SUBSCRIPTION_CHARGED;
            case "subscription.pending" -> BillingEventType.SUBSCRIPTION_PENDING;
            case "subscription.halted" -> BillingEventType.SUBSCRIPTION_HALTED;
            case "subscription.paused" -> BillingEventType.SUBSCRIPTION_PAUSED;
            case "subscription.resumed" -> BillingEventType.SUBSCRIPTION_RESUMED;
            case "subscription.cancelled" -> BillingEventType.SUBSCRIPTION_CANCELLED;
            case "subscription.completed" -> BillingEventType.SUBSCRIPTION_COMPLETED;
            case "subscription.updated" -> BillingEventType.SUBSCRIPTION_UPDATED;
            // UPI-collect follow-up - see extractReferenceId's comment above
            // for the payload shape these two share with subscription.*
            case "payment_link.paid" -> BillingEventType.PAYMENT_LINK_PAID;
            case "qr_code.credited" -> BillingEventType.QR_CODE_PAYMENT_RECEIVED;
            default -> BillingEventType.UNKNOWN;
        };
    }
}
