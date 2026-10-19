package com.agenticai.core.agents.tools.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One outbound HTTP call as described by whoever is asking for it - the LLM (via {@link
 * HttpRequestToolCallback}) or a workflow author (via the {@code http} workflow node). Every value
 * is still RAW at this point: header/query/body values may carry {@code {{secret.NAME}}} vault
 * references, and nothing has been SSRF-checked yet. {@link HttpRequestExecutor#execute} is the
 * only place a spec turns into a real request, and it does the resolving and checking.
 *
 * @param method HTTP verb, case-insensitive; null/blank means GET.
 * @param url absolute http(s) URL, may already carry a query string.
 * @param headers request headers by name (may be empty, never null after construction). Values may
 *     reference vault secrets.
 * @param query extra query parameters appended to {@code url} (URL-encoded by the executor). Values
 *     may reference vault secrets.
 * @param body request body, already rendered to text (a JSON document, a form-encoded string, or
 *     plain text); null/blank sends no body.
 * @param bodyType {@code json} (default when a body is present), {@code text} or {@code form} -
 *     picks the Content-Type when the caller did not set one explicitly.
 * @param timeoutSeconds per-request timeout; clamped to [1, {@link
 *     HttpRequestExecutor#MAX_TIMEOUT_SECONDS}] by the executor, default {@link
 *     HttpRequestExecutor#DEFAULT_TIMEOUT_SECONDS}.
 * @param maxChars cap on the returned body text; clamped like {@code WebPageFetcher}'s maxChars,
 *     default {@link HttpRequestExecutor#DEFAULT_MAX_BODY_CHARS}.
 * @param templateValues untrusted run values (workflow input fields) for {@code {{name}}}
 *     placeholders in url/header/query/body. The executor substitutes them in ONE pass over the
 *     author's template, after deciding which placeholders are vault references, so a value can
 *     never introduce a {@code {{secret.X}}} reference (SEC-007). Empty for the LLM tool path.
 */
public record HttpRequestSpec(
        String method,
        String url,
        Map<String, String> headers,
        Map<String, String> query,
        String body,
        String bodyType,
        Integer timeoutSeconds,
        Integer maxChars,
        Map<String, String> templateValues) {

    public HttpRequestSpec {
        headers =
                headers == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        query = query == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(query));
        templateValues =
                templateValues == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(templateValues));
    }

    /** No template values - the LLM tool path. */
    public HttpRequestSpec(
            String method,
            String url,
            Map<String, String> headers,
            Map<String, String> query,
            String body,
            String bodyType,
            Integer timeoutSeconds,
            Integer maxChars) {
        this(method, url, headers, query, body, bodyType, timeoutSeconds, maxChars, null);
    }

    /** Minimal GET - the shape most agent calls take. */
    public static HttpRequestSpec get(String url) {
        return new HttpRequestSpec("GET", url, null, null, null, null, null, null);
    }
}
