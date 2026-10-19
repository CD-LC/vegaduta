package com.agenticai.core.agents.tools.openapi;

import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSRF guard for every URL the OpenAPI feature ever connects to - the spec URL a tenant imports
 * from, the OAuth2 token URL, and the {@code baseUrl + path} of every tool call an agent makes.
 *
 * <p>Since security review SEC-027 this is a thin adapter over the shared {@link SsrfGuard} (one
 * range list for the whole platform: loopback, RFC-1918, link-local/metadata, CGNAT, Azure
 * WireServer, reserved, multicast, NAT64, IPv6 forms embedding an internal IPv4, and internal host
 * names). It keeps this feature's own error type and wording.
 *
 * <p>The check here gives the tenant/model a clear refusal; the actual connection is made by {@link
 * OpenApiToolExecutor} through a {@code SafeHttpClients} client that re-validates at connect time
 * and connects only to the address it validated (no DNS-rebinding window), with redirects off.
 *
 * <p>Why this matters more here than for a page fetch: the base URL is tenant-configured and the
 * path/query are LLM-steered, and the request carries a stored credential - an unguarded call would
 * let a prompt turn an agent into an authenticated probe of {@code http://keycloak:8080} or the
 * cloud metadata endpoint.
 *
 * <p>The process-wide guard honours the operator allow-list ({@code
 * app.security.outbound.allowed-private-hosts}), so a self-hosted install can register an internal
 * API the operator has explicitly listed.
 */
final class OpenApiUrlGuard {

    private static final Logger log = LoggerFactory.getLogger(OpenApiUrlGuard.class);
    private static final int MAX_URL_LENGTH = 4_096;

    private OpenApiUrlGuard() {}

    /**
     * Parses, scheme-checks, resolves and address-checks; throws a reasoned {@link
     * OpenApiToolException} on ANY doubt.
     */
    static URI validatePublicHttpUri(String raw) {
        return validatePublicHttpUri(raw, SsrfGuard.defaultGuard());
    }

    /** {@link #validatePublicHttpUri(String)} against an explicit guard. */
    static URI validatePublicHttpUri(String raw, SsrfGuard guard) {
        if (raw == null || raw.isBlank()) {
            throw new OpenApiToolException("no URL was given");
        }
        if (raw.length() > MAX_URL_LENGTH) {
            throw new OpenApiToolException("the URL is too long");
        }
        String stripped = raw.strip();
        int colon = stripped.indexOf(':');
        String scheme = colon < 0 ? "" : stripped.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new OpenApiToolException("only http and https URLs are allowed");
        }
        try {
            return guard.validate(stripped).uri();
        } catch (SsrfBlockedException e) {
            String reason = e.getMessage();
            if (reason.contains("private") || reason.contains("internal")) {
                log.info("OpenAPI tool refused a non-public address");
                throw new OpenApiToolException("the URL points inside the platform's own network");
            }
            throw new OpenApiToolException(reason);
        }
    }
}
