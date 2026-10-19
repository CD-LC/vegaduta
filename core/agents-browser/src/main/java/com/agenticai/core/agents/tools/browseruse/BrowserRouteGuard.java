package com.agenticai.core.agents.tools.browseruse;

import com.microsoft.playwright.Route;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The context-level request guard of a browser-use session (SEC-009): domain policy first, then the
 * strict public-address check. Extracted verbatim from {@link BrowserUseSessionManager}.
 */
final class BrowserRouteGuard {

    private static final Logger log = LoggerFactory.getLogger(BrowserRouteGuard.class);

    private BrowserRouteGuard() {}

    static void guard(
            Route route, BrowserUseSession session, UUID agentId, BrowserDomainPolicy policy) {
        String url = route.request().url();
        String scheme = schemeOf(url);
        // data:/blob:/about: requests can't leave this process (inline content,
        // same-origin blobs, the blank page) - nothing for the SSRF guard to
        // check, and rejecting them would break ordinary page rendering
        // (inline base64 icons, etc.). Everything else must be http(s) and
        // must resolve to a public address.
        if ("data".equals(scheme)
                || "blob".equals(scheme)
                || "about".equals(scheme)
                || "filesystem".equals(scheme)) {
            route.resume();
            return;
        }
        if (policy.isActive()) {
            // Document requests = navigations, EVERY redirect hop, popups and iframes (the
            // allow-list applies to these); sub-resources are only checked against the blocked
            // list - see BrowserDomainPolicy.
            boolean document = "document".equals(route.request().resourceType());
            BrowserDomainPolicy.Decision decision = policy.check(agentId, url, document);
            if (!decision.allowed()) {
                if (document) {
                    session.noteBlocked(decision.message());
                }
                log.info(
                        "browser-use session blocked a request by domain policy (host {})",
                        decision.host());
                route.abort();
                return;
            }
        }
        try {
            BrowserUseUrlValidator.validatePublicHttpUrl(url);
            route.resume();
        } catch (BrowserUseException e) {
            log.warn(
                    "browser-use session blocked a request to a non-public address: {}",
                    e.getMessage());
            route.abort();
        }
    }

    static String schemeOf(String url) {
        if (url == null) {
            return "";
        }
        int colon = url.indexOf(':');
        return colon < 0 ? "" : url.substring(0, colon).toLowerCase(Locale.ROOT);
    }
}
