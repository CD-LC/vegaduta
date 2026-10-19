package com.agenticai.core.apidocs;

import com.agenticai.core.eventbus.WorkflowEvent;
import com.agenticai.core.eventbus.webhook.WebhookEventCatalog;
import com.agenticai.core.eventbus.webhook.WebhookSignature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ResolvableType;

/**
 * The top-level {@code webhooks} object (OpenAPI 3.1): what the platform POSTs to a tenant's
 * webhook subscription ({@code /api/webhooks}). Mirrors {@code WebhookDeliveryRelay} (headers,
 * retries) and {@code WebhookDispatchListener} (body = the serialized {@link WorkflowEvent}); the
 * event types come from {@link WebhookEventCatalog}, the documented subset a subscription can name.
 */
final class OpenApiWebhooks {

    static final String EVENT_HEADER = "X-VegaDuta-Event";
    static final String DELIVERY_HEADER = "X-VegaDuta-Delivery";

    private OpenApiWebhooks() {}

    static Map<String, Object> webhooks(OpenApiSchemaBuilder schemas) {
        List<Object> types = new ArrayList<>();
        StringBuilder catalog = new StringBuilder();
        for (WebhookEventCatalog.Entry e : WebhookEventCatalog.ENTRIES) {
            types.add(e.eventType());
            catalog.append(" ").append(e.eventType()).append(": ").append(e.description()).append(".");
        }
        Map<String, Object> eventType = new LinkedHashMap<>();
        eventType.put("type", "string");
        eventType.put("description", "Matches the body's eventType. The documented types are in examples; subscriptions may"
                        + " also name other exact types, \"*\" or \"prefix.*\".");
        eventType.put("examples", types);

        List<Object> parameters = new ArrayList<>();
        parameters.add(header(EVENT_HEADER, eventType, "The event type of this delivery."));
        Map<String, Object> uuid = new LinkedHashMap<>();
        uuid.put("type", "string");
        uuid.put("format", "uuid");
        parameters.add(
                header(
                        DELIVERY_HEADER,
                        uuid,
                        "Delivery id, stable across retries of the same delivery. Deliveries are"
                                + " at-least-once: store it and ignore a repeat."));
        Map<String, Object> sig = new LinkedHashMap<>();
        sig.put("type", "string");
        sig.put("pattern", "^t=\\d+,v1=[0-9a-f]{64}$");
        parameters.add(
                header(
                        WebhookSignature.HEADER,
                        sig,
                        "t=<unix seconds>,v1=<hex HMAC-SHA256 of \"<t>.<raw body>\" keyed with the"
                                + " subscription secret (whsec_...)>. Recompute it over the raw"
                                + " bytes, compare in constant time, and reject a t older than a"
                                + " few minutes to stop replays."));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("required", Boolean.TRUE);
        body.put(
                "content",
                Map.of(
                        OpenApiSpecGenerator.JSON,
                        Map.of("schema", schemas.schemaFor(ResolvableType.forClass(WorkflowEvent.class)))));

        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put(
                "2XX",
                Map.of(
                        "description",
                        "Acknowledged. Any 2xx marks the delivery delivered; the body is ignored."));
        responses.put(
                "default",
                Map.of(
                        "description",
                        "Any other status, a redirect, a timeout (5 s connect, 15 s total) or a"
                                + " connection error is retried with exponential backoff (30 s"
                                + " doubling, capped at 30 min) up to 10 attempts; then the"
                                + " delivery is dead. 25 dead deliveries in a row disable the"
                                + " subscription."));

        Map<String, Object> op = new LinkedHashMap<>();
        op.put("tags", new ArrayList<Object>(List.of("WebhookSubscription")));
        op.put("summary", "Platform event delivered to a webhook subscription");
        op.put("operationId", "webhook_workflowEvent");
        op.put(
                "description",
                "POSTed to each enabled subscription whose event filter matches. The body is the"
                        + " WorkflowEvent envelope; payload is event-type specific. The test"
                        + " delivery (webhook.test) carries only eventType, tenantId, timestamp and"
                        + " payload. Documented event types:"
                        + catalog);
        op.put("parameters", parameters);
        op.put("requestBody", body);
        op.put("responses", responses);
        op.put("security", new ArrayList<>());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowEvent", Map.of("post", op));
        return out;
    }

    private static Map<String, Object> header(
            String name, Map<String, Object> schema, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("in", "header");
        p.put("required", Boolean.TRUE);
        p.put("description", description);
        p.put("schema", schema);
        return p;
    }
}
