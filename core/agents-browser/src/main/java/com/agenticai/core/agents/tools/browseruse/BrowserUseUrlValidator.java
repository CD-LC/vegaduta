package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The egress check for browser-use: refuses any URL that would point the headless session at {@code
 * localhost}/{@code 127.0.0.1}/a private range or anywhere else inside this platform's own network.
 *
 * <p>{@code browser_navigate} is the one built-in tool that lets an LLM-chosen string become a real
 * outbound navigation from inside this platform's own network (compose DNS names like {@code
 * http://keycloak:8080} resolve here even though they are not "localhost" by spelling).
 *
 * <p>Since security review SEC-009/SEC-027 the classification is the shared {@link SsrfGuard}
 * (strict: the operator's private-host allow-list never applies to model-chosen URLs):
 *
 * <ul>
 *   <li>http/https only, no embedded credentials.
 *   <li>Every resolved address must be PUBLIC - loopback, RFC-1918, link-local/metadata, CGNAT,
 *       Azure WireServer, reserved, multicast, NAT64 and IPv6 forms embedding an internal IPv4 all
 *       refuse.
 *   <li>Internal names (single-label compose names, {@code *.internal}, {@code localhost}, ...) are
 *       refused by name.
 * </ul>
 *
 * <p>{@link #firstUnsafeUrl} is the post-action check {@link BrowserUseSessionManager} runs over
 * the page URL and every frame URL.
 *
 * <p>Plain, dependency-free class - no Spring bean, so it can be called from the Playwright
 * executor thread without any bean-scoping concern.
 */
final class BrowserUseUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(BrowserUseUrlValidator.class);
    private static final int MAX_URL_LENGTH = 2_048;
    private static final SsrfGuard STRICT = SsrfGuard.strict();

    /**
     * Schemes a page or frame can show without any network request leaving this process: inline
     * content, same-origin blobs, the blank page and Chromium's own error page (what a frame shows
     * after the route guard aborts its navigation).
     */
    private static final Set<String> NON_NETWORK_SCHEMES =
            Set.of("about", "data", "blob", "chrome-error");

    private BrowserUseUrlValidator() {}

    /**
     * Parses, scheme-checks, resolves and address-checks {@code raw}, throwing a reasoned {@link
     * BrowserUseException} on ANY doubt (fail closed).
     */
    static URI validatePublicHttpUrl(String raw) {
        return validatePublicHttpUrl(raw, STRICT);
    }

    /** {@link #validatePublicHttpUrl(String)} against an explicit guard (tests). */
    static URI validatePublicHttpUrl(String raw, SsrfGuard guard) {
        if (raw == null || raw.isBlank()) {
            throw new BrowserUseException("no URL was given");
        }
        String trimmed = raw.strip();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new BrowserUseException("the URL is too long");
        }
        String scheme = schemeOf(trimmed);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BrowserUseException("only http and https URLs can be navigated to");
        }
        try {
            return guard.validate(trimmed).uri();
        } catch (SsrfBlockedException e) {
            String reason = e.getMessage();
            if (reason.contains("private") || reason.contains("internal")) {
                log.warn("browser-use refused a non-public address");
                throw new BrowserUseException("the URL points inside the platform's own network");
            }
            throw new BrowserUseException(reason);
        }
    }

    /** True for a URL that involves no network request (see {@link #NON_NETWORK_SCHEMES}). */
    static boolean isNonNetworkUrl(String url) {
        return url == null || url.isBlank() || NON_NETWORK_SCHEMES.contains(schemeOf(url.strip()));
    }

    /**
     * The first URL in {@code urls} that a session must not be on (anything that is neither a
     * non-network URL nor a public http(s) URL), or empty. Used on the page URL and every frame URL
     * after each browser action, so a redirect Playwright followed internally (which the route
     * handler never saw) is caught before any content from it is returned.
     */
    static Optional<String> firstUnsafeUrl(Iterable<String> urls, SsrfGuard guard) {
        for (String url : urls) {
            if (isNonNetworkUrl(url)) {
                continue;
            }
            try {
                validatePublicHttpUrl(url, guard);
            } catch (BrowserUseException e) {
                return Optional.of(url);
            }
        }
        return Optional.empty();
    }

    static String schemeOf(String url) {
        if (url == null) {
            return "";
        }
        int colon = url.indexOf(':');
        return colon < 0 ? "" : url.substring(0, colon).toLowerCase(Locale.ROOT);
    }
}
