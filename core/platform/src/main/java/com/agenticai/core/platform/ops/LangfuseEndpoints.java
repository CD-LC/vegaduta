package com.agenticai.core.platform.ops;

import java.net.URI;
import java.util.Locale;

/**
 * Where Langfuse lives, derived from configuration core already has. Shared by
 * the trace proxy ({@link LangfuseApiClient}) and the dependency-health probe.
 */
public final class LangfuseEndpoints {

    /** Langfuse's documented native OTLP ingestion path - see application.yml's management.otlp.tracing.endpoint. */
    static final String OTLP_TRACES_SUFFIX = "/api/public/otel/v1/traces";

    private LangfuseEndpoints() {}

    /**
     * {@code explicitBaseUrl} when set, otherwise the OTLP traces endpoint minus
     * Langfuse's OTLP suffix. Null when neither yields an http(s) URL with a host,
     * or when the OTLP endpoint is not a Langfuse one (no suffix to strip - it
     * then points at some other collector, whose root is not Langfuse's API).
     * Trailing slashes are removed.
     */
    public static String baseUrl(String explicitBaseUrl, String otlpTracesEndpoint) {
        if (explicitBaseUrl != null && !explicitBaseUrl.isBlank()) {
            return validHttp(explicitBaseUrl.trim().replaceAll("/+$", ""));
        }
        if (otlpTracesEndpoint == null || otlpTracesEndpoint.isBlank()) {
            return null;
        }
        String endpoint = otlpTracesEndpoint.trim().replaceAll("/+$", "");
        if (!endpoint.endsWith(OTLP_TRACES_SUFFIX)) {
            return null;
        }
        return validHttp(
                endpoint.substring(0, endpoint.length() - OTLP_TRACES_SUFFIX.length())
                        .replaceAll("/+$", ""));
    }

    private static String validHttp(String raw) {
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null
                    ? raw
                    : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
