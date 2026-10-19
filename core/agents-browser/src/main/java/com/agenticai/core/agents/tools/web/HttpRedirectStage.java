package com.agenticai.core.agents.tools.web;

import com.agenticai.core.agents.attestation.AgentAttestationService;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * RedirectStage of the {@link HttpRequestExecutor} pipeline: redirects are followed by hand with a
 * bounded hop count, every hop is re-validated, and when a hop changes origin the credential
 * headers (plus every header carrying a resolved vault secret) are dropped (SEC-025). The hop loop
 * itself stays in the executor next to the {@code send} seam; the rules live here.
 */
final class HttpRedirectStage {

    static final int MAX_REDIRECTS = 3;

    /**
     * Always dropped when a redirect changes origin (SEC-025), in addition to every secret-bearing
     * header.
     */
    private static final Set<String> CROSS_ORIGIN_STRIPPED_HEADERS =
            Set.of(
                    "authorization",
                    "proxy-authorization",
                    "cookie",
                    "x-payment",
                    AgentAttestationService.HEADER_NAME.toLowerCase(Locale.ROOT));

    private HttpRedirectStage() {}

    /** A 3xx that names a Location. */
    static boolean isRedirect(int status, Optional<String> location) {
        return status >= 300 && status < 400 && location.isPresent();
    }

    /**
     * Browser semantics: a 303 (and, by long-standing convention, a 301/302 answering a non-GET/HEAD
     * request) is followed with a body-less GET. 307/308 keep method and body.
     */
    static boolean followsAsGet(int status, String hopMethod) {
        return status == 303
                || ((status == 301 || status == 302)
                        && !"GET".equals(hopMethod)
                        && !"HEAD".equals(hopMethod));
    }

    /** Resolves a (possibly relative) {@code Location} through the SSRF guard. */
    static URI target(SsrfGuard guard, URI from, String location) {
        try {
            return guard.resolveRedirect(from, location).uri();
        } catch (SsrfBlockedException e) {
            throw new WebPageFetchException(e.getMessage());
        }
    }

    /**
     * Resolves a (possibly relative) {@code Location} against the hop it came from, without
     * validating it.
     */
    static URI resolveUnvalidated(URI from, String location) {
        if (location == null || location.isBlank()) {
            throw new WebPageFetchException("the server redirected without saying where to");
        }
        try {
            return from.resolve(location.strip());
        } catch (IllegalArgumentException e) {
            throw new WebPageFetchException("the server redirected to an invalid address");
        }
    }

    /**
     * Drops credential headers before a request follows a redirect to another origin (SEC-025): the
     * fixed credential set plus every header whose value contains a resolved vault secret.
     */
    static void stripCrossOriginCredentials(
            Map<String, String> headers, Set<String> resolvedSecrets) {
        headers.entrySet()
                .removeIf(
                        entry ->
                                CROSS_ORIGIN_STRIPPED_HEADERS.contains(
                                                entry.getKey().toLowerCase(Locale.ROOT))
                                        || containsAny(entry.getValue(), resolvedSecrets));
    }

    static boolean containsAny(String text, Set<String> values) {
        if (text == null || values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && !value.isEmpty() && text.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
