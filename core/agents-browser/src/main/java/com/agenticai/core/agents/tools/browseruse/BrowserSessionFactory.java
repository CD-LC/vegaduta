package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.security.net.SsrfGuard;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the browser sessions of {@link BrowserUseSessionManager} with every domain-safety layer
 * installed, and answers the post-action "is this session still on public addresses?" check. Split
 * out of the manager; runs on the manager's Playwright thread.
 *
 * <h2>Domain safety (security review SEC-009)</h2>
 *
 * <ul>
 *   <li><b>Context-level route.</b> {@code context.route("**}{@code /*")} validates every request
 *       of every page/frame/popup of the session's context with {@link BrowserUseUrlValidator} (the
 *       shared strict {@code SsrfGuard}) and aborts the rest - not just the URL {@code
 *       browser_navigate} was called with.
 *   <li><b>Redirects.</b> Playwright only hands the FIRST request of a redirect chain to the route
 *       handler. Every redirect hop still raises a request event, and every frame navigation a
 *       framenavigated event; any that lands on a non-public URL taints the session. After EVERY
 *       action (and before and after reading text or taking a screenshot) the page URL and every
 *       frame URL are re-validated ({@link #reachedNonPublic}); a tainted or non-public session is
 *       closed and the action fails, so no content from an internal host is returned.
 *   <li><b>Service workers blocked</b> ({@code ServiceWorkerPolicy.BLOCK}) - their fetches bypass
 *       routing.
 *   <li><b>WebSockets blocked</b> ({@code context.routeWebSocket}) - they are not seen by {@code
 *       route}.
 *   <li><b>Popups / new pages</b> ({@code context.onPage}) are closed; the session only ever
 *       exposes its one page. Under browser agent v2 a new tab is adopted instead (up to a small
 *       cap) and gets the same frame-navigation taint check and post-action re-validation.
 * </ul>
 *
 * <b>Residual, requires network controls:</b> Chromium resolves DNS itself, so a host that answers
 * public to the guard and internal to Chromium (DNS rebinding) can still be connected to; and a
 * subresource redirected to an internal host is requested before the taint check fires (its content
 * is never returned as text, and the session is closed before the next action, but the request
 * itself has been made). Closing that needs an egress proxy or network policy for the core
 * container ({@code --host-resolver-rules} cannot express "public only"). The deploy's egress
 * controls must stay in place for browser-use to be safe to enable.
 */
final class BrowserSessionFactory {

    private static final Logger log = LoggerFactory.getLogger(BrowserUseSessionManager.class);

    /** Max tabs a v2 session may hold open at once; a popup beyond this is closed immediately. */
    private static final int MAX_V2_TABS = 4;

    private final BrowserUsePlaywright playwright;
    private final BrowserSessionLifecycles lifecycles;
    private final Supplier<BrowserDomainPolicy> domainPolicy;
    private final BooleanSupplier v2Enabled;
    private final int navTimeoutMs;
    private final int actionTimeoutMs;

    /**
     * Sessions a redirect hop or frame navigation took to a non-public URL; set from Playwright
     * event callbacks.
     */
    private final Set<String> taintedSessions = ConcurrentHashMap.newKeySet();

    /** Model-chosen URLs: the strict guard, never the operator's private-host allow-list. */
    private volatile SsrfGuard guard = SsrfGuard.strict();

    BrowserSessionFactory(
            BrowserUsePlaywright playwright,
            BrowserSessionLifecycles lifecycles,
            Supplier<BrowserDomainPolicy> domainPolicy,
            BooleanSupplier v2Enabled,
            int navTimeoutMs,
            int actionTimeoutMs) {
        this.playwright = playwright;
        this.lifecycles = lifecycles;
        this.domainPolicy = domainPolicy;
        this.v2Enabled = v2Enabled;
        this.navTimeoutMs = navTimeoutMs;
        this.actionTimeoutMs = actionTimeoutMs;
    }

    /** Test seam: the strict guard with a fixed host resolver. */
    void useGuard(SsrfGuard guard) {
        this.guard = guard;
    }

    BrowserUseSession newSession(String tenantId, UUID agentId, String key) {
        Browser browser = playwright.browser();
        BrowserContext context =
                lifecycles.newContext(
                        browser,
                        tenantId,
                        key,
                        new Browser.NewContextOptions()
                                .setViewportSize(1280, 800)
                                .setIgnoreHTTPSErrors(false)
                                // SEC-009: a service worker's fetches are not seen by route().
                                .setServiceWorkers(ServiceWorkerPolicy.BLOCK));
        context.setDefaultTimeout(actionTimeoutMs);
        context.setDefaultNavigationTimeout(navTimeoutMs);
        // SEC-009: WebSockets bypass route(); none are allowed.
        context.routeWebSocket(url -> true, ws -> ws.close());
        // SEC-009: redirect hops never reach the route handler - record them.
        context.onRequest(
                request -> {
                    if (request.redirectedFrom() != null
                            && BrowserUseUrlValidator.firstUnsafeUrl(List.of(request.url()), guard)
                                    .isPresent()) {
                        log.warn(
                                "browser-use session followed a redirect to a non-public address; tainting the session");
                        taintedSessions.add(key);
                    }
                });
        Page page = context.newPage();
        BrowserUseSession session =
                new BrowserUseSession(key, tenantId, context, page, Instant.now());
        // Domain-safety net for the LIFE of the session and for EVERY page of the context (popups
        // included). Installed before the first navigate (the new page is still about:blank).
        context.route(
                "**/*", route -> BrowserRouteGuard.guard(route, session, agentId, domainPolicy.get()));
        watchFrameNavigations(key, page);
        if (v2Enabled.getAsBoolean()) {
            // A click that opens a new tab makes it the page the next tool call acts on; extra tabs
            // beyond the cap are closed. Adopted tabs get the same SEC-009 frame-navigation taint
            // check as the first page.
            context.onPage(
                    popup -> {
                        if (popup == page) {
                            return;
                        }
                        if (session.openPageCount() > MAX_V2_TABS) {
                            popup.close();
                            return;
                        }
                        watchFrameNavigations(key, popup);
                        session.adoptPage(popup);
                        popup.onClose(session::onPageClosed);
                    });
        } else {
            // SEC-009: popups / window.open pages are closed - the session exposes one page.
            context.onPage(
                    other -> {
                        if (other != page) {
                            try {
                                other.close();
                            } catch (RuntimeException e) {
                                log.debug("browser-use could not close a popup: {}", e.toString());
                            }
                        }
                    });
        }
        lifecycles.sessionCreated(tenantId, key, page);
        return session;
    }

    /**
     * SEC-009: a frame navigation (redirects included) that lands on a non-public URL taints the
     * session.
     */
    private void watchFrameNavigations(String key, Page page) {
        page.onFrameNavigated(
                frame -> {
                    if (BrowserUseUrlValidator.firstUnsafeUrl(List.of(frame.url()), guard)
                            .isPresent()) {
                        taintedSessions.add(key);
                    }
                });
    }

    /**
     * SEC-009 post-action check: true (and the taint consumed) when a redirect/frame navigation
     * tainted the session or the page URL or any frame URL is not public.
     */
    boolean reachedNonPublic(String key, Page page) {
        List<String> urls = new ArrayList<>();
        urls.add(page.url());
        for (Frame frame : page.frames()) {
            urls.add(frame.url());
        }
        boolean tainted = taintedSessions.remove(key);
        return tainted || BrowserUseUrlValidator.firstUnsafeUrl(urls, guard).isPresent();
    }

    /** Forgets a closed session's taint. */
    void forget(String key) {
        taintedSessions.remove(key);
    }

    /**
     * Defence in depth after a navigation or go-back: if the page ended up on a domain the policy
     * refuses, blank it and say so.
     */
    static void enforceFinalUrl(Page page, UUID agentId, BrowserDomainPolicy policy) {
        if (!policy.isActive()) {
            return;
        }
        String finalUrl = page.url();
        String scheme = BrowserRouteGuard.schemeOf(finalUrl);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return;
        }
        BrowserDomainPolicy.Decision decision = policy.check(agentId, finalUrl);
        if (!decision.allowed()) {
            try {
                page.navigate("about:blank");
            } catch (RuntimeException ignored) {
                // best effort - the message below is what matters
            }
            throw new BrowserUseException(decision.message());
        }
    }

    /** The page title, or "" when the page cannot answer. */
    static String safeTitle(Page page) {
        try {
            return page.title();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
