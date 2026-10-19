package com.agenticai.core.apidocs;

import com.agenticai.core.common.apicontract.ApiDeprecationInterceptor;
import com.agenticai.core.common.apicontract.ConditionalRequestFilter;
import com.agenticai.core.common.apicontract.IdempotencyKeyFilter;
import com.agenticai.core.security.http.ApiDeprecated;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-operation API conventions that come from runtime filters rather than from the handler's
 * signature: required permissions (from the security rule snapshot), deprecation ({@link
 * ApiDeprecated}), {@code Idempotency-Key} ({@link IdempotencyKeyFilter}), conditional requests
 * ({@link ConditionalRequestFilter}) and keyset paging ({@code cursor} / {@code X-Next-Cursor}).
 * Each reads the same static rule the filter applies, so the document cannot claim a header the
 * server ignores.
 */
final class OpenApiOperationConventions {

    /**
     * Collection paths of the {@code ResourceVersionSource} beans (agents, workflows). Beans need a
     * context, so the list is mirrored here; {@code OpenApiConventionsTest} checks it against every
     * implementation.
     */
    static final List<String> VERSIONED_COLLECTIONS = List.of("/api/agents", "/api/workflows");

    private final OpenApiPermissions permissions;

    OpenApiOperationConventions(OpenApiPermissions permissions) {
        this.permissions = permissions;
    }

    /** Adds what applies to {@code op} and its {@code responses} (success response at {@code status}). */
    @SuppressWarnings("unchecked")
    void apply(
            Map<String, Object> op,
            Map<String, Object> responses,
            String status,
            Class<?> controller,
            Method method,
            String verb,
            String path) {
        List<String> notes = new ArrayList<>();
        boolean secured = !((List<Object>) op.get("security")).isEmpty();
        if (secured && !OpenApiSecurity.isGrantKeyOnly(path)) {
            Map<String, Object> ext = permissions.extensionsFor(verb, path);
            Object note = ext.remove("note");
            op.putAll(ext);
            if (note != null) {
                notes.add((String) note);
            }
        }

        ApiDeprecated deprecated = ApiDeprecationInterceptor.find(method, controller);
        if (deprecated != null) {
            op.put("deprecated", Boolean.TRUE);
            StringBuilder d = new StringBuilder("Deprecated since " + deprecated.since());
            if (!deprecated.sunset().isBlank()) {
                op.put("x-sunset", deprecated.sunset());
                d.append("; may be removed after ").append(deprecated.sunset());
            }
            if (!deprecated.replacement().isBlank()) {
                op.put("x-replacement", deprecated.replacement());
                d.append("; use ").append(deprecated.replacement()).append(" instead");
            }
            if (!deprecated.reason().isBlank()) {
                d.append(". ").append(deprecated.reason());
            }
            notes.add(0, d + ".");
            addSuccessHeader(responses, status, OpenApiFixedComponents.DEPRECATION);
            if (!deprecated.sunset().isBlank()) {
                addSuccessHeader(responses, status, OpenApiFixedComponents.SUNSET);
            }
        }

        if (secured && IdempotencyKeyFilter.covers(verb, path) && jsonOrNoBody(op)) {
            addParameter(op, "IdempotencyKey");
            responses.put("409", OpenApiFixedComponents.errorRef("Conflict"));
            responses.put("422", OpenApiFixedComponents.errorRef("UnprocessableEntity"));
        }

        if (ConditionalRequestFilter.sendsEtag(verb, path, VERSIONED_COLLECTIONS)) {
            addSuccessHeader(responses, status, OpenApiFixedComponents.ETAG);
        }
        if (ConditionalRequestFilter.honoursIfMatch(verb, path, VERSIONED_COLLECTIONS)) {
            addParameter(op, "IfMatch");
            responses.put("412", OpenApiFixedComponents.errorRef("PreconditionFailed"));
        }

        if (hasQueryParam(op, "cursor") && hasQueryParam(op, "limit")) {
            addSuccessHeader(responses, status, OpenApiFixedComponents.NEXT_CURSOR);
            notes.add(
                    "Keyset paging: pass the X-Next-Cursor value of a page as cursor to get the"
                            + " next one; offset is ignored and X-Total-Count is not sent when"
                            + " cursor is given.");
        }

        if (!notes.isEmpty()) {
            String existing = (String) op.get("description");
            String added = String.join(" ", notes);
            op.put("description", existing == null ? added : existing + " " + added);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addParameter(Map<String, Object> op, String component) {
        List<Object> params = (List<Object>) op.computeIfAbsent("parameters", k -> new ArrayList<>());
        params.add(Map.of("$ref", "#/components/parameters/" + component));
    }

    @SuppressWarnings("unchecked")
    private static boolean hasQueryParam(Map<String, Object> op, String name) {
        Object params = op.get("parameters");
        if (!(params instanceof List<?> list)) {
            return false;
        }
        for (Object p : list) {
            if (p instanceof Map<?, ?> m && "query".equals(m.get("in")) && name.equals(m.get("name"))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean jsonOrNoBody(Map<String, Object> op) {
        Object body = op.get("requestBody");
        if (!(body instanceof Map<?, ?> b) || !(b.get("content") instanceof Map<?, ?> content)) {
            return true;
        }
        for (Object type : content.keySet()) {
            if (String.valueOf(type).contains("json")) {
                return true;
            }
        }
        return false;
    }

    /** Adds a {@code components.headers} reference to the success response, copying it first. */
    @SuppressWarnings("unchecked")
    private static void addSuccessHeader(Map<String, Object> responses, String status, String header) {
        Object current = responses.get(status);
        if (!(current instanceof Map<?, ?> r) || r.containsKey("$ref")) {
            return;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        Object description = r.get("description");
        if (description != null) {
            copy.put("description", description);
        }
        Map<String, Object> headers = new LinkedHashMap<>();
        if (r.get("headers") instanceof Map<?, ?> h) {
            headers.putAll((Map<String, Object>) h);
        }
        headers.put(header, OpenApiFixedComponents.headerRef(header));
        copy.put("headers", headers);
        for (Map.Entry<?, ?> e : r.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (!"description".equals(k) && !"headers".equals(k)) {
                copy.put(k, e.getValue());
            }
        }
        responses.put(status, copy);
    }
}
