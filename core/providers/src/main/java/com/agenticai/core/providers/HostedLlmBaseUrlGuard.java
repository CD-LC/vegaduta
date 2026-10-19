package com.agenticai.core.providers;

import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;

/**
 * SSRF guard for LLM base URLs a platform admin (hosted pool) or a tenant
 * (BYOK Ollama / Azure OpenAI endpoint) types in. The name says "HostedLlm"
 * for history; it guards every such URL.
 *
 * <p><b>Since security review SEC-023 this delegates to the shared
 * {@link SsrfGuard}</b> instead of its own DNS-free literal list. That list
 * never resolved a host name, so {@code http://keycloak:8080},
 * {@code 127.0.0.1.nip.io} or any name whose DNS points inside the network
 * passed. Now:
 * <ul>
 *   <li>{@link #checkAllowed} (save time, and the BYOK chat factories at use
 *       time) resolves the host and refuses if ANY address is loopback,
 *       private, link-local/metadata, CGNAT, Azure WireServer, reserved,
 *       multicast, or an IPv6 form embedding one of those; single-label
 *       compose names ({@code core}, {@code keycloak}), {@code localhost},
 *       {@code *.internal}/{@code *.local} and friends are refused by name.</li>
 *   <li>{@link #checkAllowedWithoutDns} is the per-request re-check of a
 *       stored hosted-pool row: same rules, but host names are not looked up,
 *       so a DNS blip cannot silently empty the FREE-tier pool. The row was
 *       resolved and checked when it was saved.</li>
 * </ul>
 *
 * <p><b>Operator escape hatch.</b> A self-hosted install that runs Ollama (or
 * any model server) at a private address lists it in
 * {@code app.security.outbound.allowed-private-hosts} ({@code host} or
 * {@code host:port}, e.g. {@code ollama:11434}). Only the operator can set
 * that; a listed host skips the address checks. Nothing is listed by default.
 *
 * <p><b>No port-scan oracle.</b> Every refusal carries one of the guard's
 * fixed reasons ("points to a private or internal network address", "could
 * not be resolved", ...), never the resolved address, and the validators no
 * longer echo transport error text - so the answer for a closed, filtered or
 * internal port is the same.
 *
 * <p>The HTTP call itself is pinned separately: the BYOK chat factories and
 * validators use a {@code SafeHttpClients}-backed client that connects only
 * to addresses the guard validated and follows no redirects.
 */
public final class HostedLlmBaseUrlGuard {

    private HostedLlmBaseUrlGuard() {
    }

    /**
     * Throws {@link ProviderValidationException} (HTTP 400) unless {@code
     * baseUrl} passes the process-wide {@link SsrfGuard#defaultGuard()}.
     * Blank is a no-op - {@code requireRequiredFields} is the "mandatory" gate.
     */
    public static void checkAllowed(String baseUrl) {
        checkAllowed(baseUrl, SsrfGuard.defaultGuard());
    }

    /** {@link #checkAllowed(String)} against an explicit guard (tests, or a caller holding the bean). */
    public static void checkAllowed(String baseUrl, SsrfGuard guard) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        try {
            guard.validate(baseUrl);
        } catch (SsrfBlockedException e) {
            throw refusal(e);
        }
    }

    /**
     * The per-request re-check of an already-saved URL: every rule of
     * {@link #checkAllowed} except the DNS lookup of a host name (IP literals
     * are still classified). See the class javadoc.
     */
    public static void checkAllowedWithoutDns(String baseUrl, SsrfGuard guard) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        try {
            guard.validateWithoutDns(baseUrl);
        } catch (SsrfBlockedException e) {
            throw refusal(e);
        }
    }

    private static ProviderValidationException refusal(SsrfBlockedException e) {
        return new ProviderValidationException("This base URL is not allowed: " + e.getMessage()
            + ". Model servers must be reachable at a public address (a platform operator can allow a "
            + "self-hosted private host via app.security.outbound.allowed-private-hosts).");
    }
}
