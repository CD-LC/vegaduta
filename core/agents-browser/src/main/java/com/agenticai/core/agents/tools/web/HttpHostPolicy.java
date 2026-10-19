package com.agenticai.core.agents.tools.web;

import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * HostPolicyStage of the {@link HttpRequestExecutor} pipeline: the destination checks - the shared
 * {@link SsrfGuard} (private/loopback/metadata classes, userinfo, scheme), the operator/agent host
 * allow-list checked BEFORE DNS, the refusal of non-canonical numeric hosts, the https-only rule
 * and origin comparison. Every check throws {@link WebPageFetchException}; nothing here sends.
 */
final class HttpHostPolicy {

    private HttpHostPolicy() {}

    /** SSRF-validates {@code url} with the shared guard (resolves the host once). */
    static SsrfGuard.Target validate(SsrfGuard guard, String url) {
        try {
            return guard.validate(url);
        } catch (SsrfBlockedException e) {
            throw new WebPageFetchException(e.getMessage());
        }
    }

    /**
     * First-URL allow-list check on the parsed host, BEFORE any DNS lookup. Unparseable URLs fall
     * through to the guard's own message.
     */
    static void checkHostBeforeResolution(HttpRequestPolicy policy, String url) {
        if (!policy.allowlistConfigured()) {
            return;
        }
        String host;
        try {
            host = new URI(url).getHost();
        } catch (URISyntaxException e) {
            return;
        }
        if (host != null && !policy.hostAllowed(host)) {
            throw hostNotAllowed(host);
        }
    }

    /**
     * Octal/hex/dword/shorthand IPv4 spellings are refused outright - see {@link
     * WebPageFetcher#isNonCanonicalNumericHost}.
     */
    static void refuseNonCanonicalNumericHost(String url) {
        String host;
        try {
            host = new URI(url).getHost();
        } catch (URISyntaxException e) {
            return; // the guard reports the precise reason
        }
        if (host != null && WebPageFetcher.isNonCanonicalNumericHost(host)) {
            throw new WebPageFetchException("the URL points inside the platform's own network");
        }
    }

    /** Scheme and allow-list check on an already SSRF-validated hop. */
    static void checkHop(HttpRequestPolicy policy, URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!policy.allowHttp() && !"https".equals(scheme)) {
            throw new WebPageFetchException("only https URLs are allowed (plain http is disabled)");
        }
        if (!policy.hostAllowed(uri.getHost())) {
            throw hostNotAllowed(uri.getHost());
        }
    }

    static WebPageFetchException hostNotAllowed(String host) {
        return new WebPageFetchException(
                "the host '" + host + "' is not on this agent's allowed hosts list");
    }

    /** Same scheme, host and effective port - the unit a credential is bound to. */
    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null
                && b.getScheme() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
