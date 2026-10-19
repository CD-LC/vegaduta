package com.agenticai.core.apidocs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed, controller-independent parts of the generated document: the shared {@code Error}
 * schema matching {@code ErrorResponseFactory}'s body (RFC 9457 Problem Details plus the legacy
 * members), the reusable error responses under {@code components.responses}, the shared response
 * headers under {@code components.headers}, and the security schemes (see {@link OpenApiSecurity}).
 */
final class OpenApiFixedComponents {

    static final String PROBLEM_JSON = "application/problem+json";
    static final String REQUEST_ID = "X-Request-Id";
    static final String RETRY_AFTER = "Retry-After";
    static final String TOTAL_COUNT = "X-Total-Count";
    static final String HAS_MORE = "X-Has-More";
    static final String NEXT_CURSOR = "X-Next-Cursor";
    static final String ETAG = "ETag";
    static final String DEPRECATION = "Deprecation";
    static final String SUNSET = "Sunset";
    static final String RATE_LIMIT = "RateLimit";
    static final String RATE_LIMIT_POLICY = "RateLimit-Policy";

    private OpenApiFixedComponents() {}

    static Map<String, Object> errorSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(
                "type",
                stringProp(
                        "uri",
                        "Stable, machine-readable problem type, e.g."
                                + " urn:vegaduta:problem:agent-not-found. Branch on this, not on"
                                + " the message. Absent when problem details are switched off."));
        props.put("title", stringProp(null, "HTTP reason phrase of the status."));
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("type", "integer");
        status.put("format", "int32");
        status.put("description", "HTTP status code, repeated from the status line.");
        props.put("status", status);
        props.put("detail", stringProp(null, "Human-readable explanation; same text as error."));
        props.put(
                "instance",
                stringProp(null, "Request path the problem occurred on (never the query string)."));
        props.put("timestamp", stringProp("date-time", null));
        props.put("error", stringProp(null, "Human-readable message (legacy member)."));
        props.put(
                "code",
                stringProp(
                        null,
                        "Stable machine-readable code, present only on handlers that"
                                + " disambiguate with one."));
        props.put(
                "requestId",
                stringProp(null, "Correlation id, equal to the X-Request-Id response header."));
        props.put("traceId", stringProp(null, "OpenTelemetry trace id, when tracing is on."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put(
                "description",
                "Error body produced by ErrorResponseFactory: an RFC 9457 Problem Details object"
                        + " served as application/problem+json, which also keeps the legacy"
                        + " members (timestamp, error, code). With"
                        + " app.errors.problem-details.enabled=false only the legacy members are"
                        + " sent, as application/json. Some handlers add extension members"
                        + " (errors, budget, upstreamStatus, lockedReason ...); the gateway's own"
                        + " 401 may carry an empty body.");
        schema.put("properties", props);
        schema.put("required", new ArrayList<Object>(List.of("status", "error")));
        schema.put("additionalProperties", Boolean.TRUE);
        return schema;
    }

    private static Map<String, Object> stringProp(String format, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        if (format != null) {
            m.put("format", format);
        }
        if (description != null) {
            m.put("description", description);
        }
        return m;
    }

    static Map<String, Object> errorResponses() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("BadRequest", errorResponse("The request was malformed or failed validation."));
        out.put(
                "Conflict",
                errorResponse(
                        "Conflicts with the current state, including a request whose"
                                + " Idempotency-Key is still being processed"
                                + " (type idempotency-key-in-use; retry after Retry-After)."));
        out.put(
                "Forbidden",
                errorResponse(
                        "The caller is authenticated but not allowed (permission, plan limit or"
                                + " locked resource)."));
        out.put("InternalServerError", errorResponse("Unexpected server error."));
        out.put(
                "NotFound",
                errorResponse("The resource does not exist or is not visible to the caller."));
        out.put(
                "PreconditionFailed",
                errorResponse(
                        "If-Match named no current version: the resource changed since it was"
                                + " read. Fetch it again (new ETag) and reapply the change."));
        Map<String, Object> tooMany =
                errorResponse(
                        "Rate limit exceeded for this caller. Wait for Retry-After seconds before"
                                + " retrying.");
        @SuppressWarnings("unchecked")
        Map<String, Object> tooManyHeaders = (Map<String, Object>) tooMany.get("headers");
        tooManyHeaders.put(RETRY_AFTER, headerRef(RETRY_AFTER));
        out.put("TooManyRequests", tooMany);
        out.put("Unauthorized", errorResponse("Missing, expired or invalid credentials."));
        out.put(
                "UnprocessableEntity",
                errorResponse(
                        "The Idempotency-Key was already used with a different request body"
                                + " (type idempotency-key-reused)."));
        return out;
    }

    static Map<String, Object> errorResponse(String description) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put(PROBLEM_JSON, Map.of("schema", OpenApiSchemaBuilder.ref("Error")));
        content.put(OpenApiSpecGenerator.JSON, Map.of("schema", OpenApiSchemaBuilder.ref("Error")));
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(REQUEST_ID, headerRef(REQUEST_ID));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("description", description);
        r.put("headers", headers);
        r.put("content", content);
        return r;
    }

    /** Response headers shared by many operations, referenced from {@code components.headers}. */
    static Map<String, Object> headers() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(
                RETRY_AFTER,
                header(
                        "integer",
                        "Seconds to wait before retrying (RFC 9110, section 10.2.3)."));
        out.put(
                HAS_MORE,
                header(
                        "boolean",
                        "Sent by paged list endpoints when limit is given: true when rows remain"
                                + " beyond this page. See docs/api/LIST-ENDPOINT-PAGING.md."));
        out.put(
                TOTAL_COUNT,
                header(
                        "integer",
                        "Sent by paged list endpoints when limit is given: rows matching the"
                                + " filters before paging. See docs/api/LIST-ENDPOINT-PAGING.md."));
        out.put(
                NEXT_CURSOR,
                header(
                        "string",
                        "Opaque cursor of the next page, sent when more rows exist. Pass it back"
                                + " unchanged as the cursor query parameter."));
        out.put(
                ETAG,
                header(
                        "string",
                        "Strong, opaque version of the resource (RFC 9110). Send it back in"
                                + " If-Match on a write to refuse it when the resource changed."));
        out.put(
                DEPRECATION,
                header(
                        "string",
                        "RFC 9745: the route is deprecated, as of @<unix seconds>. See"
                                + " docs/api/DEPRECATION.md."));
        out.put(
                SUNSET,
                header("string", "RFC 8594: HTTP-date after which the route may be removed."));
        out.put(
                RATE_LIMIT,
                header(
                        "string",
                        "Sent when app.rate-limit.api.enabled is on: \"api\";r=<remaining>;t=<seconds"
                                + " to reset> (IETF RateLimit header fields)."));
        out.put(
                RATE_LIMIT_POLICY,
                header(
                        "string",
                        "Sent when app.rate-limit.api.enabled is on: \"api\";q=<requests>;w=<window"
                                + " seconds>."));
        out.put(
                REQUEST_ID,
                header(
                        "string",
                        "Correlation id for this request: the caller's own X-Request-Id when it"
                                + " sent a valid one, otherwise generated. Quote it in support"
                                + " requests."));
        return out;
    }

    /** Request headers shared by many operations, under {@code components.parameters}. */
    static Map<String, Object> parameters() {
        Map<String, Object> idem = new LinkedHashMap<>();
        idem.put("name", "Idempotency-Key");
        idem.put("in", "header");
        idem.put("required", Boolean.FALSE);
        idem.put(
                "description",
                "Makes a retry safe (IETF Idempotency-Key header): a repeat with the same key and"
                        + " body replays the first response (Idempotent-Replayed: true) for 24"
                        + " hours; while the first is running a repeat gets 409, and the same key"
                        + " with a different body gets 422. Use a fresh random value (a UUID) per"
                        + " logical operation.");
        Map<String, Object> keySchema = new LinkedHashMap<>();
        keySchema.put("type", "string");
        keySchema.put("minLength", 1);
        keySchema.put("maxLength", 255);
        idem.put("schema", keySchema);
        Map<String, Object> ifMatch = new LinkedHashMap<>();
        ifMatch.put("name", "If-Match");
        ifMatch.put("in", "header");
        ifMatch.put("required", Boolean.FALSE);
        ifMatch.put(
                "description",
                "Optimistic concurrency (RFC 9110): the ETag from the resource's GET. The write is"
                        + " refused with 412 when the resource changed since; * always matches.");
        ifMatch.put("schema", Map.of("type", "string"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("IdempotencyKey", idem);
        out.put("IfMatch", ifMatch);
        return out;
    }

    private static Map<String, Object> header(String type, String description) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("description", description);
        h.put("schema", Map.of("type", type));
        return h;
    }

    static Map<String, Object> headerRef(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("$ref", "#/components/headers/" + name);
        return m;
    }

    static Map<String, Object> securitySchemes() {
        Map<String, Object> bearer = new LinkedHashMap<>();
        bearer.put("type", "http");
        bearer.put("scheme", "bearer");
        bearer.put("bearerFormat", "JWT");
        bearer.put("description", "Keycloak-issued access token: Authorization: Bearer <jwt>.");
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("type", "http");
        key.put("scheme", "bearer");
        key.put("bearerFormat", "vmcp_ grant key");
        key.put(
                "description",
                "Long-lived grant key (prefix vmcp_) issued under /api/mcpserver/grants:"
                        + " Authorization: Bearer vmcp_...");
        Map<String, Object> tenantKey = new LinkedHashMap<>();
        tenantKey.put("type", "http");
        tenantKey.put("scheme", "bearer");
        tenantKey.put("bearerFormat", "vdak_ tenant API key");
        tenantKey.put(
                "description",
                "Tenant REST API key (prefix vdak_) minted at /api/tenant/api-keys for"
                        + " server-to-server use: Authorization: Bearer vdak_... The key's stored"
                        + " PERM_* scopes are its authority set, so a route still answers 403 when"
                        + " the key lacks the permission.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(OpenApiSecurity.API_KEY, key);
        out.put(OpenApiSecurity.BEARER, bearer);
        out.put(OpenApiSecurity.TENANT_API_KEY, tenantKey);
        return out;
    }

    static Map<String, Object> errorRef(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("$ref", "#/components/responses/" + name);
        return m;
    }
}
